package com.claudecodesetup.services

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import com.claudecodesetup.ClaudeApp
import com.claudecodesetup.R
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AppPreferences
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.util.Locale

class FloatingOverlayService : Service() {

    companion object {
        const val ACTION_STOP             = "com.claudecodesetup.OVERLAY_STOP"
        const val ACTION_SCREENSHOT_READY = "com.claudecodesetup.SCREENSHOT_READY"
        const val ACTION_VOICE_RESULT     = "com.claudecodesetup.VOICE_RESULT"
        const val NOTIF_ID                = 1003
        private const val BRIDGE_PORT     = 8083
        private const val TAG             = "FloatingOverlay"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayRoot: FrameLayout
    private lateinit var mainBtn: View
    private lateinit var subMenu: LinearLayout
    private lateinit var quickPromptsPanel: LinearLayout
    private lateinit var ttsSubBtn: TextView

    private lateinit var prefs: AppPreferences
    private lateinit var tts: TextToSpeech
    private var ttsEnabled = false
    private var ttsReady   = false

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var btnX = 0
    private var btnY = 0
    private var screenW = 0
    private var screenH = 0
    private val btnPx get() = dpToPx(56)
    private var expanded = false

    private var pendingScreenshotQuery: String? = null

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SCREENSHOT_READY -> {
                    val path  = intent.getStringExtra("path") ?: return
                    val query = pendingScreenshotQuery ?: "Describe what you see in this screenshot."
                    pendingScreenshotQuery = null
                    attachImageAndSend(path, query)
                }
                ACTION_VOICE_RESULT -> {
                    val text = intent.getStringExtra("text") ?: return
                    mainBtn.alpha = 0.88f
                    handleVoiceResult(text)
                }
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs      = AppPreferences(this)
        ttsEnabled = prefs.getTtsEnabled()

        windowManager = getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        btnX    = screenW - btnPx - dpToPx(12)
        btnY    = screenH / 3

        val filter = IntentFilter().apply {
            addAction(ACTION_SCREENSHOT_READY)
            addAction(ACTION_VOICE_RESULT)
        }
        registerReceiver(resultReceiver, filter, RECEIVER_NOT_EXPORTED)

        initTts()
        buildOverlayView()
        addOverlayToWindow()

        startForeground(NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        connectSocket()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(resultReceiver)
        scope.cancel()
        tts.shutdown()
        runCatching { socket?.close() }
        runCatching { windowManager.removeView(overlayRoot) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    // ─── Overlay construction ─────────────────────────────────────────────────

    private fun buildOverlayView() {
        overlayRoot = FrameLayout(this)

        // Sub-menu row (5 action buttons)
        subMenu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility  = View.GONE
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            background = roundRect(0xCC1A1A2E.toInt(), dpToPx(28))
            elevation  = dpToPx(8).toFloat()
        }

        val subDefs = listOf(
            "📋" to "Clipboard",
            "📸" to "Screenshot",
            "⚡" to "Quick",
            "🔊" to "TTS",
            "📱" to "App"
        )
        subDefs.forEachIndexed { i, (emoji, _) ->
            val btn = subButton(emoji)
            if (emoji == "🔊") ttsSubBtn = btn
            btn.setOnClickListener { collapseAll(); onSubAction(i) }
            subMenu.addView(btn, LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                if (i > 0) leftMargin = dpToPx(8)
            })
        }

        // Quick-prompts panel
        quickPromptsPanel = buildQuickPromptsPanel()

        // Main button
        mainBtn = makeMainButton()
        setupDragAndTap()

        overlayRoot.addView(subMenu,         subMenuLp())
        overlayRoot.addView(quickPromptsPanel, quickPanelLp())
        overlayRoot.addView(mainBtn,          mainBtnLp())

        refreshTtsColor()
    }

    private fun addOverlayToWindow() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(overlayRoot, params)
    }

    private fun makeMainButton(): ImageView {
        val iv = ImageView(this)
        iv.setImageResource(R.mipmap.ic_launcher)
        iv.scaleType  = ImageView.ScaleType.CENTER_CROP
        iv.alpha      = 0.88f
        iv.background = oval(0xCC1A1A2E.toInt())
        iv.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
        iv.elevation  = dpToPx(8).toFloat()
        return iv
    }

    private fun subButton(emoji: String) = TextView(this).apply {
        text       = emoji
        textSize   = 20f
        gravity    = Gravity.CENTER
        background = oval(0xFF2D2D44.toInt())
        elevation  = dpToPx(4).toFloat()
    }

    private fun buildQuickPromptsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility  = View.GONE
            setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
            background = roundRect(0xEE1A1A2E.toInt(), dpToPx(16))
            elevation  = dpToPx(10).toFloat()
        }
        val prompts = listOf(
            "Summarize this",
            "Fact check this",
            "Translate to English",
            "Fix grammar",
            "Explain like I'm 5"
        )
        prompts.forEach { p ->
            val tv = TextView(this).apply {
                text      = p
                textSize  = 14f
                setTextColor(0xFFE2E8F0.toInt())
                setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
                background = roundRect(0x00000000, dpToPx(10))
                setOnClickListener {
                    collapseAll()
                    sendToSocket("$p\n")
                    toast("Sent: $p")
                }
            }
            panel.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        return panel
    }

    // ─── Drag, tap, long-press ────────────────────────────────────────────────

    private fun setupDragAndTap() {
        var downX = 0f; var downY = 0f; var moved = false
        val longHandler = Handler(Looper.getMainLooper())
        var longRunnable: Runnable? = null

        mainBtn.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; moved = false
                    longRunnable = Runnable { startVoice() }
                    longHandler.postDelayed(longRunnable!!, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val threshold = dpToPx(8).toFloat()
                    if (!moved && (ev.rawX - downX).let { it * it } + (ev.rawY - downY).let { it * it } > threshold * threshold) {
                        moved = true
                        longHandler.removeCallbacks(longRunnable!!)
                    }
                    if (moved) {
                        btnX = (ev.rawX - btnPx / 2).toInt().coerceIn(0, screenW - btnPx)
                        btnY = (ev.rawY - btnPx / 2).toInt().coerceIn(dpToPx(24), screenH - btnPx - dpToPx(24))
                        repositionViews()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longHandler.removeCallbacks(longRunnable!!)
                    if (!moved) { toggleMenu() } else { snapToEdge() }
                }
            }
            true
        }
    }

    private fun snapToEdge() {
        btnX = if (btnX + btnPx / 2 < screenW / 2) dpToPx(12) else screenW - btnPx - dpToPx(12)
        repositionViews()
    }

    private fun toggleMenu() {
        if (quickPromptsPanel.visibility == View.VISIBLE) {
            collapseAll(); return
        }
        expanded = !expanded
        subMenu.visibility = if (expanded) View.VISIBLE else View.GONE
        mainBtn.alpha = if (expanded) 1f else 0.88f
        repositionViews()
    }

    private fun collapseAll() {
        expanded = false
        subMenu.visibility          = View.GONE
        quickPromptsPanel.visibility = View.GONE
        mainBtn.alpha = 0.88f
    }

    private fun repositionViews() {
        (mainBtn.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = btnX; topMargin = btnY
        }.also { mainBtn.layoutParams = it }

        val subW = dpToPx(48) * 5 + dpToPx(8) * 4 + dpToPx(20)
        val subLeft = (btnX + btnPx / 2 - subW / 2).coerceIn(0, (screenW - subW).coerceAtLeast(0))
        (subMenu.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = subLeft
            topMargin  = (btnY - dpToPx(66)).coerceAtLeast(dpToPx(24))
        }.also { subMenu.layoutParams = it }

        val panelW = dpToPx(220)
        val panelLeft = (btnX + btnPx / 2 - panelW / 2).coerceIn(0, (screenW - panelW).coerceAtLeast(0))
        (quickPromptsPanel.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = panelLeft
            topMargin  = (btnY - dpToPx(230)).coerceAtLeast(dpToPx(24))
            width      = panelW
        }.also { quickPromptsPanel.layoutParams = it }
    }

    // ─── Layout params helpers ────────────────────────────────────────────────

    private fun mainBtnLp()  = FrameLayout.LayoutParams(btnPx, btnPx).apply {
        leftMargin = btnX; topMargin = btnY
    }
    private fun subMenuLp()  = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
        leftMargin = btnX; topMargin = (btnY - dpToPx(66)).coerceAtLeast(dpToPx(24))
    }
    private fun quickPanelLp() = FrameLayout.LayoutParams(dpToPx(220),
        FrameLayout.LayoutParams.WRAP_CONTENT).apply {
        leftMargin = btnX; topMargin = (btnY - dpToPx(230)).coerceAtLeast(dpToPx(24))
    }

    // ─── Sub-button actions ───────────────────────────────────────────────────

    private fun onSubAction(index: Int) {
        when (index) {
            0 -> sendClipboard()
            1 -> requestScreenshot(null)
            2 -> showQuickPrompts()
            3 -> toggleTts()
            4 -> openApp()
        }
    }

    private fun sendClipboard() {
        val cm   = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) { toast("Clipboard is empty"); return }
        sendToSocket("$text\n")
        toast("Sent to Claude ✓")
    }

    private fun requestScreenshot(voiceQuery: String?) {
        pendingScreenshotQuery = voiceQuery
        startActivity(Intent(this, MediaProjectionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun showQuickPrompts() {
        quickPromptsPanel.visibility = View.VISIBLE
        subMenu.visibility = View.GONE
        expanded = false
        repositionViews()
    }

    private fun toggleTts() {
        ttsEnabled = !ttsEnabled
        prefs.setTtsEnabled(ttsEnabled)
        refreshTtsColor()
        toast(if (ttsEnabled) "Voice reply: on" else "Voice reply: off")
    }

    private fun openApp() {
        startActivity(Intent(this, TerminalActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun refreshTtsColor() {
        if (::ttsSubBtn.isInitialized)
            ttsSubBtn.alpha = if (ttsEnabled) 1f else 0.45f
    }

    private fun attachImageAndSend(jpegPath: String, query: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = File(jpegPath).readBytes()
                val b64   = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                File(filesDir, "pending_image.b64").writeText(b64)
                File(filesDir, "pending_image.mime").writeText("image/jpeg")
                sendToSocket("$query\n")
            } catch (e: Exception) {
                Log.e(TAG, "attachImageAndSend error", e)
                withContext(Dispatchers.Main) { toast("Screenshot failed") }
            }
        }
    }

    // ─── Voice ────────────────────────────────────────────────────────────────

    private fun startVoice() {
        collapseAll()
        mainBtn.alpha = 0.55f
        toast("Listening…")
        startActivity(Intent(this, VoiceInputActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun handleVoiceResult(text: String) {
        val lc = text.lowercase()
        val wantsVision = lc.contains("see") || lc.contains("look") ||
            lc.contains("screen") || (lc.contains("this") && lc.contains("check")) ||
            lc.contains("showing") || lc.contains("what") && lc.contains("here")
        if (wantsVision) {
            toast("Taking screenshot…")
            requestScreenshot(text)
        } else {
            sendToSocket("$text\n")
        }
    }

    // ─── Socket ───────────────────────────────────────────────────────────────

    private fun connectSocket() {
        scope.launch(Dispatchers.IO) {
            repeat(30) {
                try {
                    val s = Socket("127.0.0.1", BRIDGE_PORT)
                    socket       = s
                    outputStream = s.outputStream
                    readLoop(s)
                    return@launch
                } catch (_: Exception) { delay(2000) }
            }
        }
    }

    private suspend fun readLoop(s: Socket) = withContext(Dispatchers.IO) {
        val buf  = ByteArray(4096)
        val resp = StringBuilder()
        try {
            val inp = s.inputStream
            var n: Int
            while (inp.read(buf).also { n = it } != -1) {
                if (ttsEnabled && ttsReady) {
                    val clean = String(buf, 0, n, Charsets.UTF_8)
                        .replace(Regex("\\[[0-9;]*[a-zA-Z]"), "")
                        .replace(Regex("][^]*"), "")
                    resp.append(clean)
                }
            }
        } catch (_: IOException) { }

        if (ttsEnabled && ttsReady && resp.isNotBlank()) {
            val spoken = resp.toString().trim()
                .replace(Regex("▶[^\n]*\n?"), "")
                .replace(Regex("[`*#>]"), "")
                .take(500)
            withContext(Dispatchers.Main) {
                tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "overlay_resp")
            }
        }

        runCatching { s.close() }
        socket = null; outputStream = null
        delay(1000)
        connectSocket()
    }

    private fun sendToSocket(text: String) {
        scope.launch(Dispatchers.IO) {
            try {
                outputStream?.write(text.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "sendToSocket failed", e)
            }
        }
    }

    // ─── TTS ─────────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts.language = Locale.getDefault()
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 99,
            Intent(this, FloatingOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, ClaudeApp.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Claude Overlay")
            .setContentText("Floating assistant active — tap to dismiss")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .build()
    }

    // ─── Drawing helpers ──────────────────────────────────────────────────────

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun roundRect(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius.toFloat(); setColor(color)
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
