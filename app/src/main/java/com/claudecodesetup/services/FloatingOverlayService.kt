package com.claudecodesetup.services

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.animation.OvershootInterpolator
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
        const val ACTION_CLIPBOARD_READY  = "com.claudecodesetup.CLIPBOARD_READY"
        const val ACTION_CLIPBOARD_EMPTY  = "com.claudecodesetup.CLIPBOARD_EMPTY"
        const val NOTIF_ID                = 1003
        private const val BRIDGE_PORT     = 8083
        private const val TAG             = "FloatingOverlay"
        private const val IDLE_DELAY_MS   = 3500L
        private const val IDLE_ALPHA      = 0.20f
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
    private val btnPx get() = dpToPx(60)
    private var expanded = false

    private var pendingScreenshotQuery: String? = null
    private lateinit var overlayParams: WindowManager.LayoutParams

    // Idle-fade: fades to IDLE_ALPHA after IDLE_DELAY_MS of no interaction
    private val idleHandler  = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable {
        mainBtn.animate().alpha(IDLE_ALPHA).setDuration(900).start()
    }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SCREENSHOT_READY -> {
                    val path  = intent.getStringExtra("path") ?: return
                    val query = pendingScreenshotQuery ?: "Describe what you see in this screenshot."
                    pendingScreenshotQuery = null
                    attachImageAndSend(path, query)
                    wakeUp()
                }
                ACTION_VOICE_RESULT -> {
                    val text = intent.getStringExtra("text") ?: return
                    wakeUp()
                    handleVoiceResult(text)
                }
                ACTION_CLIPBOARD_READY -> {
                    val text = intent.getStringExtra("text") ?: return
                    sendToSocket("$text\n")
                    toast("Sent to Claude ✓")
                }
                ACTION_CLIPBOARD_EMPTY -> {
                    toast("Clipboard is empty")
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
            addAction(ACTION_CLIPBOARD_READY)
            addAction(ACTION_CLIPBOARD_EMPTY)
        }
        registerReceiver(resultReceiver, filter, RECEIVER_NOT_EXPORTED)

        initTts()
        buildOverlayView()
        addOverlayToWindow()

        startForeground(NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        connectSocket()
        wakeUp()
    }

    override fun onDestroy() {
        super.onDestroy()
        idleHandler.removeCallbacks(idleRunnable)
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

    // ─── Idle fade ────────────────────────────────────────────────────────────

    private fun wakeUp() {
        idleHandler.removeCallbacks(idleRunnable)
        mainBtn.animate().alpha(1f).setDuration(200).start()
        idleHandler.postDelayed(idleRunnable, IDLE_DELAY_MS)
    }

    // ─── Overlay construction ─────────────────────────────────────────────────

    private fun buildOverlayView() {
        overlayRoot = FrameLayout(this)

        // Sub-menu: glassmorphic horizontal pill
        subMenu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility  = View.GONE
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            background = glassPill(dpToPx(32))
            elevation  = dpToPx(12).toFloat()
        }

        val subDefs = listOf("📋", "📸", "⚡", "🔊", "📱")
        subDefs.forEachIndexed { i, emoji ->
            val btn = glassSubButton(emoji)
            if (emoji == "🔊") ttsSubBtn = btn
            btn.setOnClickListener { collapseAll(); wakeUp(); onSubAction(i) }
            subMenu.addView(btn, LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                if (i > 0) leftMargin = dpToPx(10)
            })
        }

        quickPromptsPanel = buildQuickPromptsPanel()

        mainBtn = makeMainButton()
        setupDragAndTap()

        overlayRoot.addView(subMenu,            subMenuLp())
        overlayRoot.addView(quickPromptsPanel,  quickPanelLp())
        overlayRoot.addView(mainBtn,            mainBtnLp())

        refreshTtsColor()
    }

    private fun addOverlayToWindow() {
        overlayParams = WindowManager.LayoutParams(
            btnPx, btnPx, btnX, btnY,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(overlayRoot, overlayParams)
        repositionViews()
    }

    // ─── View factories ───────────────────────────────────────────────────────

    private fun makeMainButton(): ImageView {
        val iv = ImageView(this)
        iv.setImageResource(R.mipmap.ic_launcher)
        iv.scaleType  = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
        iv.background = glassMainButtonBg()
        iv.elevation  = dpToPx(12).toFloat()
        return iv
    }

    /**
     * Two-layer drawable:
     *  Layer 0 — purple→cyan gradient glow ring (full size oval)
     *  Layer 1 — dark glass fill with white stroke (inset 2dp so the glow peeks out as a border)
     */
    private fun glassMainButtonBg(): LayerDrawable {
        val glow = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF7C3AED.toInt(), 0xFF06B6D4.toInt())
        ).apply { shape = GradientDrawable.OVAL }

        val glass = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC0D0D1F.toInt())          // 80% very dark navy
            setStroke(dpToPx(1), 0x60FFFFFF)       // 37% white inner ring
        }

        val inset = dpToPx(2)
        return LayerDrawable(arrayOf(glow, glass)).also {
            it.setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun glassSubButton(emoji: String) = TextView(this).apply {
        text      = emoji
        textSize  = 21f
        gravity   = Gravity.CENTER
        elevation = dpToPx(4).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x1AFFFFFF)                   // 10% white fill
            setStroke(dpToPx(1), 0x40FFFFFF)       // 25% white border
        }
    }

    /** Frosted-glass horizontal pill behind the 5 sub-buttons */
    private fun glassPill(radius: Int) = GradientDrawable().apply {
        shape         = GradientDrawable.RECTANGLE
        cornerRadius  = radius.toFloat()
        setColor(0xCC0D0D1F.toInt())               // 80% very dark navy
        setStroke(dpToPx(1), 0x33FFFFFF)           // 20% white border
    }

    private fun buildQuickPromptsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility  = View.GONE
            setPadding(dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(6))
            elevation   = dpToPx(14).toFloat()
            background  = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(18).toFloat()
                setColor(0xF00D0D1F.toInt())       // 94% dark navy
                setStroke(dpToPx(1), 0x33FFFFFF)
            }
        }

        val prompts = prefs.getOverlayPrompts()
        prompts.forEachIndexed { i, prompt ->
            if (i > 0) {
                panel.addView(View(this).apply {
                    setBackgroundColor(0x20FFFFFF)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)).apply {
                    leftMargin = dpToPx(14); rightMargin = dpToPx(14)
                })
            }
            val tv = TextView(this).apply {
                text     = prompt
                textSize = 14f
                setTextColor(0xFFE2E8F0.toInt())
                setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
                setOnClickListener {
                    collapseAll()
                    wakeUp()
                    sendToSocket("$prompt\n")
                    toast("Sending to Claude…")
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
                    wakeUp()
                    downX = ev.rawX; downY = ev.rawY; moved = false
                    longRunnable = Runnable { startVoice() }
                    longHandler.postDelayed(longRunnable!!, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val threshold = dpToPx(8).toFloat()
                    if (!moved &&
                        (ev.rawX - downX).let { it * it } +
                        (ev.rawY - downY).let { it * it } > threshold * threshold
                    ) {
                        moved = true
                        longHandler.removeCallbacks(longRunnable!!)
                    }
                    if (moved) {
                        btnX = (ev.rawX - btnPx / 2).toInt().coerceIn(0, screenW - btnPx)
                        btnY = (ev.rawY - btnPx / 2).toInt()
                            .coerceIn(dpToPx(24), screenH - btnPx - dpToPx(24))
                        repositionViews()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longHandler.removeCallbacks(longRunnable!!)
                    if (!moved) toggleMenu() else snapToEdge()
                }
            }
            true
        }
    }

    private fun snapToEdge() {
        btnX = if (btnX + btnPx / 2 < screenW / 2) dpToPx(12)
               else screenW - btnPx - dpToPx(12)
        repositionViews()
    }

    private fun toggleMenu() {
        if (quickPromptsPanel.visibility == View.VISIBLE) { collapseAll(); return }
        expanded = !expanded
        if (expanded) showSubMenu() else hideSubMenu()
    }

    private fun collapseAll() {
        expanded = false
        hideSubMenu()
        hideQuickPanel()
    }

    private fun showSubMenu() {
        subMenu.visibility = View.VISIBLE
        subMenu.scaleX = 0.75f; subMenu.scaleY = 0.75f; subMenu.alpha = 0f
        subMenu.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
        repositionViews()
    }

    private fun hideSubMenu() {
        if (subMenu.visibility != View.VISIBLE) return
        subMenu.animate()
            .scaleX(0.75f).scaleY(0.75f).alpha(0f)
            .setDuration(160)
            .withEndAction { subMenu.visibility = View.GONE; repositionViews() }
            .start()
    }

    private fun hideQuickPanel() {
        if (quickPromptsPanel.visibility != View.VISIBLE) return
        quickPromptsPanel.animate()
            .alpha(0f).setDuration(160)
            .withEndAction {
                quickPromptsPanel.visibility = View.GONE
                quickPromptsPanel.alpha = 1f
                repositionViews()
            }
            .start()
    }

    // Computes the minimal bounding box covering the button and any visible menus, then updates
    // the window size/position accordingly. Child views are positioned relative to this box,
    // so touches outside the box pass through to the underlying app via FLAG_NOT_TOUCH_MODAL.
    private fun repositionViews() {
        val subW = dpToPx(48) * 5 + dpToPx(10) * 4 + dpToPx(24)
        val subLeft = (btnX + btnPx / 2 - subW / 2)
            .coerceIn(dpToPx(8), (screenW - subW - dpToPx(8)).coerceAtLeast(dpToPx(8)))
        val subTop = if (btnY - dpToPx(72) >= dpToPx(24)) btnY - dpToPx(72)
                     else btnY + btnPx + dpToPx(8)

        val panelW = dpToPx(230)
        val panelLeft = (btnX + btnPx / 2 - panelW / 2)
            .coerceIn(dpToPx(8), (screenW - panelW - dpToPx(8)).coerceAtLeast(dpToPx(8)))
        val panelTop = if (btnY - dpToPx(260) >= dpToPx(24)) btnY - dpToPx(260)
                       else btnY + btnPx + dpToPx(8)

        // Bounding box in screen coords — start with button, expand to visible menus
        var winL = btnX; var winT = btnY; var winR = btnX + btnPx; var winB = btnY + btnPx
        if (subMenu.visibility == View.VISIBLE) {
            winL = minOf(winL, subLeft); winT = minOf(winT, subTop)
            winR = maxOf(winR, subLeft + subW); winB = maxOf(winB, subTop + dpToPx(72))
        }
        if (quickPromptsPanel.visibility == View.VISIBLE) {
            winL = minOf(winL, panelLeft); winT = minOf(winT, panelTop)
            winR = maxOf(winR, panelLeft + panelW); winB = maxOf(winB, panelTop + dpToPx(260))
        }

        overlayParams.x = winL; overlayParams.y = winT
        overlayParams.width = winR - winL; overlayParams.height = winB - winT
        runCatching { windowManager.updateViewLayout(overlayRoot, overlayParams) }

        // Child positions are relative to the window's own top-left
        (mainBtn.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = btnX - winL; topMargin = btnY - winT
        }.also { mainBtn.layoutParams = it }

        (subMenu.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = subLeft - winL; topMargin = subTop - winT
        }.also { subMenu.layoutParams = it }

        (quickPromptsPanel.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = panelLeft - winL; topMargin = panelTop - winT; width = panelW
        }.also { quickPromptsPanel.layoutParams = it }
    }

    // ─── Layout params ────────────────────────────────────────────────────────

    private fun mainBtnLp() = FrameLayout.LayoutParams(btnPx, btnPx)
    private fun subMenuLp() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
    )
    private fun quickPanelLp() = FrameLayout.LayoutParams(
        dpToPx(230), FrameLayout.LayoutParams.WRAP_CONTENT
    )

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
        // Reading clipboard requires a focused Activity context on Android 10+.
        // ClipboardHelperActivity reads it and broadcasts the result back to us.
        startActivity(Intent(this, ClipboardHelperActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun requestScreenshot(voiceQuery: String?) {
        pendingScreenshotQuery = voiceQuery
        val svc = DeviceControlService.instance
        if (svc != null && DeviceControlService.isAvailable()) {
            // Silent capture via AccessibilityService — no dialog
            toast("Capturing screen…")
            svc.takeScreenshot { path ->
                if (path != null) {
                    sendBroadcast(
                        Intent(ACTION_SCREENSHOT_READY).setPackage(packageName)
                            .putExtra("path", path)
                    )
                } else {
                    toast("Screenshot failed — try again")
                }
            }
        } else {
            // Fallback: MediaProjection (shows casting dialog on first use)
            toast("Enable Accessibility Service for silent screenshots")
            startActivity(Intent(this, MediaProjectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun showQuickPrompts() {
        hideSubMenu()
        expanded = false
        quickPromptsPanel.alpha = 0f
        quickPromptsPanel.visibility = View.VISIBLE
        quickPromptsPanel.animate().alpha(1f).setDuration(200).start()
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
            ttsSubBtn.alpha = if (ttsEnabled) 1f else 0.38f
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
            lc.contains("showing") || (lc.contains("what") && lc.contains("here"))
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
                        .replace(Regex("\\[[0-9;]*[a-zA-Z]"), "")
                        .replace(Regex("][^]*"), "")
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
        val service = ClaudeService.instance
        if (service != null) {
            service.sendInput(text)
            // Open terminal after short delay so the user can see the response
            Handler(Looper.getMainLooper()).postDelayed({ openApp() }, 300)
        } else {
            // Fallback: own socket connection (no active ClaudeService session)
            scope.launch(Dispatchers.IO) {
                try {
                    outputStream?.write(text.toByteArray(Charsets.UTF_8))
                    outputStream?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "sendToSocket failed", e)
                    withContext(Dispatchers.Main) { toast("No active session — open the terminal first") }
                }
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
            .setContentText("Floating assistant active")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .build()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
