package com.claudecodesetup

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
                // Activity recreated — restore tab strip and replay active session
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        setSupportActionBar(binding.toolbar)

        setupWebView()
        setupButtons()
        startAndBindService()
    }

    // ─── WebView terminal ─────────────────────────────────────────────────────

    private fun setupWebView() {
        val wv = binding.webViewTerminal
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
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
        // Encode as a JSON string literal so all control chars and Unicode are safe
        // to inject into evaluateJavascript without breaking the JS parser.
        val json = buildString(data.length + 2) {
            append('"')
            for (ch in data) {
                when (ch) {
                    '"'      -> append("\\\"")
                    '\\'     -> append("\\\\")
                    '\n'     -> append("\\n")
                    '\r'     -> append("\\r")
                    '\t'     -> append("\\t")
                    ' ' -> append("\\u2028")
                    ' ' -> append("\\u2029")
                    else     -> if (ch.code < 0x20) append("\\u${ch.code.toString(16).padStart(4, '0')}") else append(ch)
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

    // ─── Session tab management ───────────────────────────────────────────────

    private fun attachServiceCallbacks() {
        claudeService!!.onOutput = { sessionId, chunk ->
            if (sessionId == activeSessionId) {
                writeToTerminal(chunk)
            }
        }

        claudeService!!.onSessionAdded = { session ->
            runOnUiThread { addTabForSession(session) }
        }

        claudeService!!.onSessionEnded = { sessionId ->
            runOnUiThread {
                updateTabAlive(sessionId, false)
                if (sessionId == activeSessionId) {
                    writeToTerminal("\r\n[33m[Claude Code session ended][0m\r\n")
                    binding.btnRestart.visibility = View.VISIBLE
                }
                updateNewSessionButton()
            }
        }
    }

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
            setOnLongClickListener {
                confirmCloseSession(session.id)
                true
            }
        }

        tabButtons[session.id] = btn

        // Insert before the "+" button
        val container = binding.tabContainer
        val plusIndex = container.indexOfChild(binding.btnNewSession)
        container.addView(btn, plusIndex)

        if (activeSessionId == -1) {
            switchToSession(session.id, replay = false)
        }

        updateTabActive(session.id)
        updateNewSessionButton()
    }

    private fun updateTabActive(sessionId: Int) {
        tabButtons.forEach { (id, btn) ->
            val isActive = id == sessionId
            val session = claudeService?.getSession(id)
            val isAlive = session?.alive ?: false

            btn.backgroundTintList = ColorStateList.valueOf(
                when {
                    isActive -> getColor(R.color.accent_green)
                    !isAlive -> getColor(R.color.surface)
                    else     -> getColor(R.color.surface)
                }
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

        activeSessionId = id
        claudeService?.switchToSession(id)

        clearTerminal()
        binding.btnRestart.visibility = View.GONE
        updateTabActive(id)

        if (replay) {
            val output = claudeService?.getSession(id)?.getOutput() ?: ""
            if (output.isNotEmpty()) {
                // Write buffered output in background chunks to keep the UI responsive
                lifecycleScope.launch(Dispatchers.IO) {
                    val chunkSize = 8192
                    var offset = 0
                    while (offset < output.length) {
                        val end = minOf(offset + chunkSize, output.length)
                        val chunk = output.substring(offset, end)
                        withContext(Dispatchers.Main) { writeToTerminal(chunk) }
                        offset = end
                    }
                    // Show restart button if the session has already ended
                    val alive = claudeService?.getSession(id)?.alive ?: false
                    if (!alive) {
                        withContext(Dispatchers.Main) {
                            binding.btnRestart.visibility = View.VISIBLE
                        }
                    }
                }
            } else {
                // New empty session — show nothing yet
                val alive = claudeService?.getSession(id)?.alive ?: true
                if (!alive) binding.btnRestart.visibility = View.VISIBLE
            }
        }
    }

    private fun confirmCloseSession(sessionId: Int) {
        if ((claudeService?.getAllSessions()?.size ?: 0) <= 1) {
            // Last session — just show a notice
            AlertDialog.Builder(this)
                .setTitle("Close session?")
                .setMessage("This is your last session. Closing it will stop Claude Code.")
                .setPositiveButton("Close") { _, _ ->
                    claudeService?.closeSession(sessionId)
                    tabButtons.remove(sessionId)?.let { binding.tabContainer.removeView(it) }
                    if (tabButtons.isEmpty()) {
                        // No sessions left — clear terminal
                        clearTerminal()
                        activeSessionId = -1
                    }
                    updateNewSessionButton()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            claudeService?.closeSession(sessionId)
            tabButtons.remove(sessionId)?.let { binding.tabContainer.removeView(it) }
            // Auto-switch to the next available session
            val nextId = claudeService?.getAllSessions()?.firstOrNull()?.id ?: -1
            if (nextId >= 0) switchToSession(nextId, replay = true)
            updateNewSessionButton()
        }
    }

    // ─── Quick-action buttons ─────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnYes.setOnClickListener { sendInput("y\n") }
        binding.btnNo.setOnClickListener { sendInput("n\n") }
        binding.btnCancel.setOnClickListener { sendInput("") } // ETX = Ctrl+C (embedded \x03)
        binding.btnEnter.setOnClickListener { sendInput("\n") }
        binding.btnTab.setOnClickListener { sendInput("\t") }

        binding.btnRestart.setOnClickListener {
            binding.btnRestart.visibility = View.GONE
            clearTerminal()
            claudeService?.restartSession(activeSessionId)
        }

        binding.btnSwitchProvider.setOnClickListener {
            showSwitchProviderDialog()
        }

        binding.btnNewSession.setOnClickListener {
            val id = claudeService?.createSession(prefs.getLoginMode()) ?: return@setOnClickListener
            if (id >= 0) {
                // onSessionAdded callback will add the tab; auto-switch after it arrives
                runOnUiThread { switchToSession(id, replay = false) }
            }
        }
    }

    private fun sendInput(text: String) {
        claudeService?.sendInput(text)
    }

    // ─── Service binding ──────────────────────────────────────────────────────

    private fun startAndBindService() {
        val intent = Intent(this, ClaudeService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            // Clear callbacks so dangling lambdas don't reference a dead Activity
            claudeService?.onOutput = null
            claudeService?.onSessionAdded = null
            claudeService?.onSessionEnded = null
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // ─── Menus ────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.terminal_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.menu_copy -> {
                binding.webViewTerminal.evaluateJavascript("window.termCopySelection()", null)
                true
            }
            R.id.menu_restart_proxy -> {
                Intent(this, ClaudeService::class.java).also {
                    it.action = ClaudeService.ACTION_RESTART_PROXY
                    startService(it)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ─── Provider switch dialog ───────────────────────────────────────────────

    private fun showSwitchProviderDialog() {
        AlertDialog.Builder(this)
            .setTitle("Switch provider?")
            .setMessage("This will stop all sessions and let you choose a new provider.")
            .setPositiveButton("Switch") { _, _ ->
                claudeService?.stopAllSessions()
                prefs.clearProviderOnly()
                startActivity(Intent(this, LoginFlowActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── JavaScript interface ─────────────────────────────────────────────────

    inner class TerminalBridge {

        @JavascriptInterface
        fun sendInput(text: String) {
            claudeService?.sendInput(text)
        }

        @JavascriptInterface
        fun onTerminalReady() {
            // Terminal JS is fully loaded
        }

        @JavascriptInterface
        fun copyText(text: String) {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(
                android.content.ClipData.newPlainText("Claude Output", text)
            )
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@TerminalActivity, "Copied", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        @JavascriptInterface
        fun showConfirmDialog(filename: String) {
            runOnUiThread {
                AlertDialog.Builder(this@TerminalActivity)
                    .setTitle("Claude wants to create a file")
                    .setMessage(filename)
                    .setPositiveButton("Yes, create it") { _, _ -> sendInput("y\n") }
                    .setNegativeButton("No, skip") { _, _ -> sendInput("n\n") }
                    .show()
            }
        }
    }
}
