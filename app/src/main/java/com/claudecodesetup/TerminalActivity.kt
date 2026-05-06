package com.claudecodesetup

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.databinding.ActivityTerminalBinding
import com.claudecodesetup.services.ClaudeService

class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var prefs: AppPreferences

    private var claudeService: ClaudeService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as ClaudeService.LocalBinder
            claudeService = b.getService()
            serviceBound = true

            claudeService!!.onOutputLine = { chunk ->
                writeToTerminal(chunk)
            }

            claudeService!!.onProcessExit = {
                runOnUiThread {
                    writeToTerminal("\r\n[33m[Claude Code exited][0m\r\n")
                    binding.btnRestart.visibility = View.VISIBLE
                }
            }

            // Start session now that service is bound
            claudeService!!.startSession(prefs.getLoginMode())
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
                // Terminal is ready; hide loading indicator
                binding.tvLoading.visibility = View.GONE
            }
        }

        wv.loadUrl("file:///android_asset/terminal/index.html")
    }

    private fun writeToTerminal(data: String) {
        val escaped = data
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", "\\r")
            .replace("\n", "\\n")

        runOnUiThread {
            binding.webViewTerminal.evaluateJavascript(
                "window.termWrite('$escaped')", null
            )
        }
    }

    // ─── Quick-action buttons ─────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnYes.setOnClickListener { sendInput("y\n") }
        binding.btnNo.setOnClickListener { sendInput("n\n") }
        binding.btnCancel.setOnClickListener { sendInput("") } // Ctrl+C
        binding.btnEnter.setOnClickListener { sendInput("\n") }
        binding.btnTab.setOnClickListener { sendInput("\t") }

        binding.btnRestart.setOnClickListener {
            binding.btnRestart.visibility = View.GONE
            claudeService?.startSession(prefs.getLoginMode())
        }

        binding.btnSwitchProvider.setOnClickListener {
            showSwitchProviderDialog()
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
                binding.webViewTerminal.evaluateJavascript(
                    "window.termCopySelection()", null
                )
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
            .setMessage("This will stop your current session and let you choose a new provider.")
            .setPositiveButton("Switch") { _, _ ->
                claudeService?.stopSession()
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
