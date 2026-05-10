package com.claudecodesetup

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.databinding.ActivityTerminalBinding
import com.claudecodesetup.services.ClaudeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var prefs: AppPreferences

    private var claudeService: ClaudeService? = null
    private var serviceBound = false

    private var activeSessionId: Int = -1
    private val tabButtons = LinkedHashMap<Int, Button>()

    // ─── Message-status tracking ──────────────────────────────────────────────

    /** Per-session: waiting for the first response chunk after a user send. */
    private val sessionBusy = mutableMapOf<Int, Boolean>()

    /** Per-session: last message text sent by the user (used for Retry). */
    private val lastSentMessage = mutableMapOf<Int, String>()

    private val statusHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private val THINKING_TIMEOUT_MS = 15_000L

    // ─── Service connection ───────────────────────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as ClaudeService.LocalBinder
            claudeService = b.getService()
            serviceBound = true
            attachServiceCallbacks()

            val existing = claudeService!!.getAllSessions()
            if (existing.isEmpty()) {
                claudeService!!.createSession(prefs.getLoginMode())
            } else {
                existing.forEach { addTabForSession(it) }
                val resumeId = claudeService!!.activeSessionId
                if (resumeId >= 0) switchToSession(resumeId, replay = true)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            claudeService = null
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        setupWebView()
        setupQuickActions()
        setupInputBar()
        setupHeaderButtons()
        setupStatusBar()
        startAndBindService()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelThinkingTimeout()
        if (serviceBound) {
            claudeService?.onOutput = null
            claudeService?.onSessionAdded = null
            claudeService?.onSessionEnded = null
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // ─── WebView terminal ─────────────────────────────────────────────────────

    private fun setupWebView() {
        val wv = binding.webViewTerminal
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
        }

        wv.addJavascriptInterface(TerminalBridge(), "Android")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                binding.tvLoading.visibility = View.GONE
            }
        }

        wv.loadUrl("file:///android_asset/terminal/index.html")
    }

    private fun writeToTerminal(data: String) {
        val json = buildString(data.length + 2) {
            append('"')
            for (ch in data) {
                when (ch) {
                    '"'  -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    ' ' -> append("\\u2028")
                    ' ' -> append("\\u2029")
                    else -> if (ch.code < 0x20) append("\\u${ch.code.toString(16).padStart(4, '0')}") else append(ch)
                }
            }
            append('"')
        }
        runOnUiThread {
            binding.webViewTerminal.evaluateJavascript("window.termWrite($json)", null)
        }
    }

    private fun clearTerminal() {
        runOnUiThread {
            binding.webViewTerminal.evaluateJavascript("window.termClear()", null)
        }
    }

    // ─── Service callbacks ────────────────────────────────────────────────────

    private fun attachServiceCallbacks() {
        claudeService!!.onOutput = { sessionId, chunk ->
            if (sessionId == activeSessionId) writeToTerminal(chunk)
            // First output chunk means the bridge responded — cancel the thinking indicator
            if (sessionBusy[sessionId] == true) {
                sessionBusy[sessionId] = false
                if (sessionId == activeSessionId) {
                    runOnUiThread { hideStatus() }
                }
            }
        }

        claudeService!!.onSessionAdded = { session ->
            runOnUiThread { addTabForSession(session) }
        }

        claudeService!!.onSessionEnded = { sessionId ->
            runOnUiThread {
                val wasBusy = sessionBusy[sessionId] == true
                sessionBusy[sessionId] = false

                updateTabAlive(sessionId, false)
                if (sessionId == activeSessionId) {
                    if (wasBusy) {
                        // Died while waiting for a reply — surface an error with Retry
                        showStatusError("Connection lost — tap Retry or Restart")
                    }
                    writeToTerminal("\r\n[33m[Claude Code session ended][0m\r\n")
                    binding.btnRestart.visibility = View.VISIBLE
                }
                updateNewSessionButton()
            }
        }
    }

    // ─── Session tab management ───────────────────────────────────────────────

    private fun addTabForSession(session: ClaudeService.ClaudeSession) {
        val btn = Button(this).apply {
            text = session.name
            isAllCaps = false
            textSize = 11f
            setPadding(32, 0, 32, 0)
            minWidth = 0
            minimumWidth = 0
            height = resources.getDimensionPixelSize(R.dimen.tab_height)
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.surface))
            setTextColor(getColor(R.color.text_secondary))

            setOnClickListener { switchToSession(session.id, replay = true) }
            setOnLongClickListener { confirmCloseSession(session.id); true }
        }

        tabButtons[session.id] = btn

        val container = binding.tabContainer
        val plusIndex = container.indexOfChild(binding.btnNewSession)
        container.addView(btn, plusIndex)

        if (activeSessionId == -1) switchToSession(session.id, replay = false)

        updateTabActive(session.id)
        updateNewSessionButton()
    }

    private fun updateTabActive(sessionId: Int) {
        tabButtons.forEach { (id, btn) ->
            val isActive = id == sessionId
            val isAlive = claudeService?.getSession(id)?.alive ?: false
            btn.backgroundTintList = ColorStateList.valueOf(
                if (isActive) getColor(R.color.accent_orange) else getColor(R.color.surface)
            )
            btn.setTextColor(
                when {
                    isActive -> Color.WHITE
                    !isAlive -> getColor(R.color.error_red)
                    else     -> getColor(R.color.text_secondary)
                }
            )
        }
    }

    private fun updateTabAlive(sessionId: Int, alive: Boolean) {
        val btn = tabButtons[sessionId] ?: return
        if (sessionId != activeSessionId) {
            btn.setTextColor(
                if (alive) getColor(R.color.text_secondary) else getColor(R.color.error_red)
            )
        }
    }

    private fun updateNewSessionButton() {
        val count = claudeService?.getAllSessions()?.size ?: 0
        binding.btnNewSession.isEnabled = count < ClaudeService.MAX_SESSIONS
        binding.btnNewSession.alpha = if (binding.btnNewSession.isEnabled) 1f else 0.4f
    }

    private fun switchToSession(id: Int, replay: Boolean) {
        if (id == activeSessionId && !replay) return

        // Cancel any running timeout for the old session before switching
        cancelThinkingTimeout()

        activeSessionId = id
        claudeService?.switchToSession(id)

        clearTerminal()
        binding.btnRestart.visibility = View.GONE
        updateTabActive(id)
        updateStatusForSession(id)

        if (replay) {
            val output = claudeService?.getSession(id)?.getOutput() ?: ""
            if (output.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val chunkSize = 8192
                    var offset = 0
                    while (offset < output.length) {
                        val end = minOf(offset + chunkSize, output.length)
                        withContext(Dispatchers.Main) { writeToTerminal(output.substring(offset, end)) }
                        offset = end
                    }
                    val alive = claudeService?.getSession(id)?.alive ?: false
                    if (!alive) withContext(Dispatchers.Main) {
                        binding.btnRestart.visibility = View.VISIBLE
                    }
                }
            } else {
                val alive = claudeService?.getSession(id)?.alive ?: true
                if (!alive) binding.btnRestart.visibility = View.VISIBLE
            }
        }
    }

    private fun confirmCloseSession(sessionId: Int) {
        if ((claudeService?.getAllSessions()?.size ?: 0) <= 1) {
            AlertDialog.Builder(this)
                .setTitle("Close session?")
                .setMessage("This is your last session. Closing it will stop Claude Code.")
                .setPositiveButton("Close") { _, _ ->
                    claudeService?.closeSession(sessionId)
                    tabButtons.remove(sessionId)?.let { binding.tabContainer.removeView(it) }
                    if (tabButtons.isEmpty()) { clearTerminal(); activeSessionId = -1 }
                    updateNewSessionButton()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            claudeService?.closeSession(sessionId)
            tabButtons.remove(sessionId)?.let { binding.tabContainer.removeView(it) }
            val nextId = claudeService?.getAllSessions()?.firstOrNull()?.id ?: -1
            if (nextId >= 0) switchToSession(nextId, replay = true)
            updateNewSessionButton()
        }
    }

    // ─── Quick-action buttons ─────────────────────────────────────────────────

    private fun setupQuickActions() {
        binding.btnYes.setOnClickListener    { sendInput("y\r") }
        binding.btnNo.setOnClickListener     { sendInput("n\r") }
        binding.btnCancel.setOnClickListener { sendInput("") }   // Ctrl+C
        binding.btnEsc.setOnClickListener    { sendInput("") }   // Escape
        binding.btnTab.setOnClickListener    { sendInput("\t") }
        binding.btnEnter.setOnClickListener  { sendInput("\r") }
        binding.btnArrowUp.setOnClickListener   { sendInput("[A") }
        binding.btnArrowDown.setOnClickListener { sendInput("[B") }

        binding.btnRestart.setOnClickListener {
            binding.btnRestart.visibility = View.GONE
            hideStatus()
            clearTerminal()
            claudeService?.restartSession(activeSessionId)
        }

        binding.btnNewSession.setOnClickListener {
            val id = claudeService?.createSession(prefs.getLoginMode()) ?: return@setOnClickListener
            if (id >= 0) runOnUiThread { switchToSession(id, replay = false) }
        }
    }

    // ─── Input bar ────────────────────────────────────────────────────────────

    private fun setupInputBar() {
        binding.btnSend.setOnClickListener { submitInput() }

        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submitInput(); true } else false
        }
    }

    private fun submitInput() {
        val text = binding.etInput.text?.toString()?.trimEnd() ?: return
        if (text.isEmpty()) return

        // Local echo so the user immediately sees what they typed
        writeToTerminal("\r\n[32m❯ $text[0m\r\n")
        sendInput(text + "\r")
        binding.etInput.setText("")

        // Mark this session as busy and start the 15 s thinking-timeout
        lastSentMessage[activeSessionId] = text
        sessionBusy[activeSessionId] = true
        showStatusThinking()
        startThinkingTimeout()
    }

    // ─── Header buttons ───────────────────────────────────────────────────────

    private fun setupHeaderButtons() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }

    // ─── Status bar ───────────────────────────────────────────────────────────

    private fun setupStatusBar() {
        binding.btnRetryMessage.setOnClickListener { retryLastMessage() }
    }

    private fun showStatusThinking() {
        binding.llStatus.visibility        = View.VISIBLE
        binding.progressThinking.visibility = View.VISIBLE
        binding.tvStatus.text              = "Thinking…"
        binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
        binding.btnRetryMessage.visibility = View.GONE
        binding.llStatus.setBackgroundColor(getColor(R.color.surface))
    }

    private fun showStatusError(msg: String) {
        cancelThinkingTimeout()
        binding.llStatus.visibility        = View.VISIBLE
        binding.progressThinking.visibility = View.GONE
        binding.tvStatus.text              = msg
        binding.tvStatus.setTextColor(getColor(R.color.error_red))
        binding.btnRetryMessage.visibility = View.VISIBLE
        binding.llStatus.setBackgroundColor(getColor(R.color.warning_bg))
    }

    private fun hideStatus() {
        cancelThinkingTimeout()
        binding.llStatus.visibility = View.GONE
    }

    /** Restore correct status bar state after switching to a different session. */
    private fun updateStatusForSession(sessionId: Int) {
        if (sessionBusy[sessionId] == true) showStatusThinking() else hideStatus()
    }

    private fun startThinkingTimeout() {
        cancelThinkingTimeout()
        timeoutRunnable = Runnable {
            sessionBusy[activeSessionId] = false
            showStatusError("No response after 15 s — check your connection or tap Retry")
        }
        statusHandler.postDelayed(timeoutRunnable!!, THINKING_TIMEOUT_MS)
    }

    private fun cancelThinkingTimeout() {
        timeoutRunnable?.let { statusHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun retryLastMessage() {
        val text = lastSentMessage[activeSessionId]
        if (text.isNullOrEmpty()) return
        hideStatus()
        writeToTerminal("\r\n[32m❯ $text[0m  [33m[retry][0m\r\n")
        sendInput(text + "\r")
        sessionBusy[activeSessionId] = true
        showStatusThinking()
        startThinkingTimeout()
    }

    // ─── Input routing ────────────────────────────────────────────────────────

    private fun sendInput(text: String) {
        claudeService?.sendInput(text)
    }

    // ─── Service binding ──────────────────────────────────────────────────────

    private fun startAndBindService() {
        val intent = Intent(this, ClaudeService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ─── JavaScript bridge ────────────────────────────────────────────────────

    inner class TerminalBridge {

        @JavascriptInterface
        fun sendInput(text: String) {
            claudeService?.sendInput(text)
        }

        @JavascriptInterface
        fun onTerminalReady() {}

        @JavascriptInterface
        fun copyText(text: String) {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Claude Output", text))
            runOnUiThread {
                android.widget.Toast.makeText(this@TerminalActivity, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun showConfirmDialog(filename: String) {
            runOnUiThread {
                AlertDialog.Builder(this@TerminalActivity)
                    .setTitle("Claude wants to create a file")
                    .setMessage(filename)
                    .setPositiveButton("Yes, create it") { _, _ -> sendInput("y\r") }
                    .setNegativeButton("No, skip")       { _, _ -> sendInput("n\r") }
                    .show()
            }
        }
    }
}
