package com.claudecodesetup

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.databinding.ActivityTerminalBinding
import com.claudecodesetup.services.ClaudeService
import com.claudecodesetup.ui.ComposeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var prefs: AppPreferences

    private var claudeService: ClaudeService? = null
    private var serviceBound = false

    private var activeSessionId: Int = -1
    private val tabButtons = LinkedHashMap<Int, Button>()

    companion object {
        private const val REQUEST_IMAGE = 1002
        const val EXTRA_SCHEDULED_PROMPT = "scheduled_prompt"
        const val EXTRA_PROJECT_PATH = "project_path"
    }

    // ─── TTS ──────────────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null

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
            claudeService!!.isActivityVisible = true
            claudeService!!.cancelResponseNotification()
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

    // shared text passed via ACTION_SEND intent (set after terminal loads)
    private var pendingSharedText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        // Capture shared text from ACTION_SEND intent (or forwarded from SplashActivity)
        pendingSharedText = intent?.getStringExtra("shared_text")

        setupWebView()
        setupRestartButton()
        setupHeaderButtons()
        setupStatusBar()
        startAndBindService()

        // Initialize TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    // Called when ProjectManagerActivity opens a project while this activity is already in the stack.
    // FLAG_ACTIVITY_CLEAR_TOP brings us to front and delivers the new project path here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH) ?: return
        if (projectPath.isNotEmpty() && serviceBound) {
            claudeService?.createSession(prefs.getLoginMode(), projectPath)
        }
    }

    override fun onResume() {
        super.onResume()
        claudeService?.isActivityVisible = true
        claudeService?.cancelResponseNotification()
        // Send scheduled prompt if launched from notification
        intent.getStringExtra(EXTRA_SCHEDULED_PROMPT)?.let { prompt ->
            intent.removeExtra(EXTRA_SCHEDULED_PROMPT)
            claudeService?.sendInput(prompt + "\n")
        }
        // Refresh model name + avatar in case user changed provider in Settings
        val model = prefs.getModelId().let { m ->
            when {
                m.isEmpty() -> "claude"
                m.contains('/') -> m.substringAfterLast('/').removeSuffix(":free")
                else -> m
            }
        }
        binding.tvModelName.text = model
        val providerId = prefs.getProviderId()
        binding.webViewTerminal.evaluateJavascript(
            "window.termSetMeta('','${model.replace("'","\\'")}','${providerId.replace("'","\\'")}');", null
        )
        // Refresh project pill in case user changed project while away
        val projectPath = prefs.getProjectPath()
        if (projectPath.isNotEmpty()) {
            binding.tvProjectName.text = "📂 " + projectPath.substringAfterLast('/').ifEmpty { projectPath }
            binding.tvProjectName.visibility = android.view.View.VISIBLE
        } else {
            binding.tvProjectName.visibility = android.view.View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        claudeService?.isActivityVisible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelThinkingTimeout()
        tts?.stop()
        tts?.shutdown()
        tts = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        if (serviceBound) {
            claudeService?.isActivityVisible = false
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
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false   // hide the +/- overlay buttons
        }

        wv.addJavascriptInterface(TerminalBridge(), "Android")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                binding.tvLoading.visibility = View.GONE
                val model = prefs.getModelId().let { m ->
                    when {
                        m.isEmpty() -> "claude"
                        m.contains('/') -> m.substringAfterLast('/').removeSuffix(":free")
                        else -> m
                    }
                }
                binding.tvModelName.text = model
                val providerId = prefs.getProviderId()
                view.evaluateJavascript(
                    "window.termSetMeta('','${model.replace("'","\\'")}','${providerId.replace("'","\\'")}');", null
                )

                // Pre-fill input with shared text (from ACTION_SEND intent)
                pendingSharedText?.let { text ->
                    pendingSharedText = null
                    val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r")
                    view.evaluateJavascript("window.termSetInput(\"$escaped\")", null)
                }
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
            if (sessionBusy[sessionId] == true) {
                sessionBusy[sessionId] = false
                if (sessionId == activeSessionId) {
                    runOnUiThread { hideStatus() }
                }
            }
            // Parse cwd OSC: ESC ] 9 ; cwd:<path> BEL — update session and header pill
            val cwdMatch = Regex("]9;cwd:([^]+)").find(chunk)
            if (cwdMatch != null) {
                val newCwd = cwdMatch.groupValues[1].trim()
                claudeService?.getSession(sessionId)?.cwd = newCwd
                if (sessionId == activeSessionId) {
                    runOnUiThread { updateCwdPill(newCwd) }
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
                        showStatusError("Connection lost — tap Retry or Restart")
                    }
                    writeToTerminal("\r\n[33m[Nexus Mind session ended][0m\r\n")
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
            setOnLongClickListener { showSessionOptionsDialog(session.id); true }
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

    private fun updateCwdPill(cwd: String) {
        if (cwd.isNotEmpty()) {
            binding.tvProjectName.text = "📂 " + cwd.substringAfterLast('/').ifEmpty { cwd }
            binding.tvProjectName.visibility = android.view.View.VISIBLE
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

        cancelThinkingTimeout()

        activeSessionId = id
        claudeService?.switchToSession(id)

        clearTerminal()
        binding.btnRestart.visibility = View.GONE
        updateTabActive(id)
        updateStatusForSession(id)

        // Restore cwd pill for this session (or fall back to project path)
        val sessionCwd = claudeService?.getSession(id)?.cwd ?: ""
        val displayPath = sessionCwd.ifEmpty { prefs.getProjectPath() }
        if (displayPath.isNotEmpty()) {
            binding.tvProjectName.text = "📂 " + displayPath.substringAfterLast('/').ifEmpty { displayPath }
            binding.tvProjectName.visibility = android.view.View.VISIBLE
        } else {
            binding.tvProjectName.visibility = android.view.View.GONE
        }

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

    private fun showSessionOptionsDialog(sessionId: Int) {
        val session = claudeService?.getSession(sessionId) ?: return
        AlertDialog.Builder(this)
            .setTitle(session.name)
            .setItems(arrayOf("Rename", "Close")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(sessionId, session.name)
                    1 -> confirmCloseSession(sessionId)
                }
            }
            .show()
    }

    private fun showRenameDialog(sessionId: Int, currentName: String) {
        val input = android.widget.EditText(this).apply {
            setText(currentName)
            selectAll()
            setPadding(64, 32, 64, 32)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Session")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    claudeService?.getSession(sessionId)?.name = newName
                    tabButtons[sessionId]?.text = newName
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmCloseSession(sessionId: Int) {
        if ((claudeService?.getAllSessions()?.size ?: 0) <= 1) {
            AlertDialog.Builder(this)
                .setTitle("Close session?")
                .setMessage("This is your last session. Closing it will stop Nexus Mind.")
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

    // ─── Restart / New-session buttons ────────────────────────────────────────

    private fun setupRestartButton() {
        binding.btnRestart.setOnClickListener {
            binding.btnRestart.visibility = View.GONE
            hideStatus()
            clearTerminal()
            claudeService?.restartSession(activeSessionId)
        }

        binding.btnNewSession.setOnClickListener {
            showNewSessionDialog()
        }
    }

    private fun showNewSessionDialog() {
        val defaultCwd = prefs.getProjectPath().ifEmpty { "" }
        val input = android.widget.EditText(this).apply {
            hint = "Leave empty to use default project path"
            setText(defaultCwd)
            setPadding(64, 32, 64, 32)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("New Session")
            .setMessage("Starting directory (optional)")
            .setView(input)
            .setPositiveButton("Start") { _, _ ->
                val cwd = input.text.toString().trim()
                val id = claudeService?.createSession(prefs.getLoginMode(), cwd) ?: return@setPositiveButton
                if (id >= 0) runOnUiThread { switchToSession(id, replay = false) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Header buttons ───────────────────────────────────────────────────────

    private fun setupHeaderButtons() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        // Tapping the model pill navigates directly to the model picker
        binding.tvModelName.setOnClickListener {
            startActivity(Intent(this, ComposeActivity::class.java).apply {
                putExtra("start_at", "picker")
            })
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        // Project name pill — shows active project folder, taps open project manager
        val projectPath = prefs.getProjectPath()
        if (projectPath.isNotEmpty()) {
            binding.tvProjectName.text = "📂 " + projectPath.substringAfterLast('/').ifEmpty { projectPath }
            binding.tvProjectName.visibility = android.view.View.VISIBLE
        }
        binding.tvProjectName.setOnClickListener {
            startActivity(Intent(this, com.claudecodesetup.ui.ProjectManagerActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        // TTS toggle button
        binding.btnTts.apply {
            text = if (prefs.getTtsEnabled()) "🔊" else "🔇"
            setOnClickListener {
                val newState = !prefs.getTtsEnabled()
                prefs.setTtsEnabled(newState)
                text = if (newState) "🔊" else "🔇"
                if (!newState) tts?.stop()
            }
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
        if (!isOnline()) {
            showStatusError("No internet connection — check your network and try again")
            return
        }
        hideStatus()
        writeToTerminal("\r\n[32m❯ $text[0m  [33m[retry][0m\r\n")
        claudeService?.sendInput(text + "\r")
        sessionBusy[activeSessionId] = true
        showStatusThinking()
        startThinkingTimeout()
    }

    // ─── TTS ──────────────────────────────────────────────────────────────────

    fun speakText(text: String) {
        if (!prefs.getTtsEnabled()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "claude_response")
    }

    // ─── Offline detection ────────────────────────────────────────────────────

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ─── Image picker ─────────────────────────────────────────────────────────

    private fun pickImage() {
        // Check if current model supports vision
        val modelId = prefs.getModelId().lowercase()
        val hasVision = listOf("vision", "vl", "scout", "maverick", "gemini", "claude", "gpt-4", "llava", "llama-4")
            .any { it in modelId }
        if (!hasVision) {
            android.widget.Toast.makeText(
                this,
                "This model doesn't support images. Switch to a vision model (e.g. Gemini Flash, Llama 4 Scout).",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    // ─── Voice input (background SpeechRecognizer — no Google popup) ─────────

    private var speechRecognizer: SpeechRecognizer? = null

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            android.widget.Toast.makeText(this, "Voice recognition not available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: android.os.Bundle?) {}
            override fun onEvent(t: Int, p: android.os.Bundle?) {}
            override fun onError(error: Int) {
                android.widget.Toast.makeText(this@TerminalActivity, "Voice error — try again", android.widget.Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: android.os.Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
                runOnUiThread {
                    binding.webViewTerminal.evaluateJavascript("window.termSetInput(\"$escaped\")", null)
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        speechRecognizer?.startListening(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE && resultCode == Activity.RESULT_OK && data?.data != null) {
            try {
                val uri = data.data!!
                @Suppress("DEPRECATION")
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    minOf(bitmap.width, 1024),
                    minOf(bitmap.height, 1024),
                    true
                )
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                // Write directly to file — avoids passing large base64 through JS interface
                File(filesDir, "pending_image.b64").writeText(b64)
                File(filesDir, "pending_image.mime").writeText("image/jpeg")
                runOnUiThread {
                    binding.webViewTerminal.evaluateJavascript("window.termSetImageReady()", null)
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Image error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── Quick-action prompts ─────────────────────────────────────────────────

    private fun showQuickActions() {
        val prompts = arrayOf(
            "Explain this code to me",
            "Find and fix any bugs in my code",
            "Write unit tests for this",
            "Optimize this code for performance",
            "Add error handling and logging",
            "Refactor this code to be cleaner",
            "Create a README for this project",
            "List all files in the project",
            "Run $ ls and show directory structure",
            "Help me with git: show status and recent commits"
        )

        AlertDialog.Builder(this)
            .setTitle("Quick Actions")
            .setItems(prompts) { _, which ->
                val escaped = prompts[which].replace("\"", "\\\"")
                binding.webViewTerminal.evaluateJavascript(
                    "window.termSetInput(\"$escaped\")", null
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── File browser ─────────────────────────────────────────────────────────

    private fun showFileBrowser(dirPath: String) {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            android.widget.Toast.makeText(this, "Directory not found: $dirPath", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val entries = try {
            dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val names = entries.map { (if (it.isDirectory) "📂 " else "📄 ") + it.name }.toTypedArray()
        if (names.isEmpty()) {
            android.widget.Toast.makeText(this, "Empty directory", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(dirPath)
            .setItems(names) { _, which ->
                val selected = entries[which]
                if (selected.isDirectory) {
                    showFileBrowser(selected.absolutePath)
                } else {
                    showFileContent(selected)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showFileContent(file: File) {
        val content = try {
            if (file.length() > 100_000) file.readText(Charsets.UTF_8).take(100_000) + "\n…(truncated)"
            else file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }

        val scrollView = ScrollView(this)
        val tv = TextView(this).apply {
            text = content
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 16, 32, 16)
        }
        scrollView.addView(tv)

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(scrollView)
            .setPositiveButton("🔗 Attach to Claude") { _, _ ->
                // !attach sends file content as next-message context via bridge.js
                claudeService?.sendInput("!attach ${file.absolutePath}\r")
                android.widget.Toast.makeText(this, "Attached: ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Edit") { _, _ -> openCodeEditor(file) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openCodeEditor(file: File) {
        val content = try { file.readText(Charsets.UTF_8) } catch (e: Exception) { return }
        val editText = android.widget.EditText(this).apply {
            setText(content)
            setTextColor(Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#0d1a2e"))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 10
            gravity = android.view.Gravity.TOP
        }
        val scroll = ScrollView(this)
        scroll.addView(editText)

        AlertDialog.Builder(this)
            .setTitle("Edit: ${file.name}")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                try {
                    file.writeText(editText.text.toString(), Charsets.UTF_8)
                    android.widget.Toast.makeText(this, "Saved", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "Save failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Service binding ──────────────────────────────────────────────────────

    private fun startAndBindService() {
        val intent = Intent(this, ClaudeService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ─── JavaScript bridge ────────────────────────────────────────────────────

    inner class TerminalBridge {

        /** Called by JS when user presses Enter on the inline terminal input. */
        @JavascriptInterface
        fun submitMessage(text: String) {
            if (text.isEmpty()) return
            if (!isOnline()) {
                runOnUiThread {
                    showStatusError("No internet connection — check your network and try again")
                }
                return
            }
            lastSentMessage[activeSessionId] = text
            sessionBusy[activeSessionId] = true
            runOnUiThread {
                showStatusThinking()
                startThinkingTimeout()
            }
            claudeService?.sendInput(text + "\r")
        }

        /** Called by JS toolbar buttons that send raw control characters. */
        @JavascriptInterface
        fun sendInput(text: String) {
            claudeService?.sendInput(text)
        }

        /** Called by JS when the terminal dimensions change. Saves the new size and
         *  forwards an in-band resize sequence to bridge.js so libpty-helper.so
         *  can issue TIOCSWINSZ + SIGWINCH to the running claude process. */
        @JavascriptInterface
        fun notifyResize(cols: Int, rows: Int) {
            if (cols !in 10..999 || rows !in 5..500) return
            prefs.setPtyCols(cols)
            prefs.setPtyRows(rows)
            claudeService?.sendResizeAll(cols, rows)
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
        fun shareText(text: String) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "Nexus Mind Conversation")
            }
            runOnUiThread {
                startActivity(Intent.createChooser(intent, "Export Conversation"))
            }
        }

        @JavascriptInterface
        fun saveFile(filename: String, content: String) {
            val projectPath = prefs.getProjectPath().ifEmpty { filesDir.absolutePath }
            val file = File(projectPath, filename)
            try {
                file.parentFile?.mkdirs()
                file.writeText(content, Charsets.UTF_8)
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@TerminalActivity,
                        "Saved: ${file.absolutePath}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@TerminalActivity,
                        "Save failed: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun startVoiceInput() {
            runOnUiThread { this@TerminalActivity.startVoiceInput() }
        }

        @JavascriptInterface
        fun showQuickActions() {
            runOnUiThread { this@TerminalActivity.showQuickActions() }
        }

        @JavascriptInterface
        fun browseFiles() {
            runOnUiThread {
                val path = prefs.getProjectPath().ifEmpty { filesDir.absolutePath }
                showFileBrowser(path)
            }
        }

        @JavascriptInterface
        fun openPreview() {
            runOnUiThread { this@TerminalActivity.openWebPreview() }
        }

        @JavascriptInterface
        fun pickImage() {
            runOnUiThread { this@TerminalActivity.pickImage() }
        }

        @JavascriptInterface
        fun submitMessageWithImage(text: String) {
            if (!isOnline()) {
                runOnUiThread {
                    showStatusError("No internet connection — check your network and try again")
                }
                return
            }
            // Image files (pending_image.b64 / .mime) already written by onActivityResult
            val msg = text.ifEmpty { "What do you see in this image?" }
            lastSentMessage[activeSessionId] = msg
            sessionBusy[activeSessionId] = true
            runOnUiThread {
                showStatusThinking()
                startThinkingTimeout()
            }
            claudeService?.sendInput(msg + "\r")
        }

        @JavascriptInterface
        fun sendConfirm(id: String, choice: String) {
            claudeService?.sendInput("!confirm:$id:$choice\r")
        }

        @JavascriptInterface
        fun cancelPendingImage() {
            try {
                File(filesDir, "pending_image.b64").delete()
                File(filesDir, "pending_image.mime").delete()
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun runAndFeedback(code: String, lang: String) {
            Thread {
                val escapedCode = code.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                val cmd = when (lang.lowercase()) {
                    "python", "py" -> "python3 -c \"$escapedCode\""
                    "javascript", "js", "node" -> "node -e \"$escapedCode\""
                    else -> code
                }
                val result = try {
                    val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
                    process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                    val stdout = process.inputStream.bufferedReader().readText()
                    val stderr = process.errorStream.bufferedReader().readText()
                    val parts = listOf(stdout, if (stderr.isNotEmpty()) "stderr:\n$stderr" else "")
                        .filter { it.isNotEmpty() }
                    val combined = parts.joinToString("\n").trim()
                    if (combined.isEmpty()) "[exit ${process.exitValue()} — no output]" else combined.take(3000)
                } catch (e: Exception) {
                    "Error running code: ${e.message}"
                }
                val feedback = "I ran the code. Here's the output:\n```\n$result\n```\nWhat does this mean / what should I do next?"
                lastSentMessage[activeSessionId] = feedback
                sessionBusy[activeSessionId] = true
                runOnUiThread {
                    showStatusThinking()
                    startThinkingTimeout()
                    binding.webViewTerminal.evaluateJavascript("window.termResetRunBtn()", null)
                }
                claudeService?.sendInput(feedback + "\r")
            }.start()
        }

        @JavascriptInterface
        fun speakText(text: String) {
            this@TerminalActivity.speakText(text)
        }

        @JavascriptInterface
        fun regenerateLastResponse() {
            val text = lastSentMessage[activeSessionId]
            if (text.isNullOrEmpty()) return
            runOnUiThread {
                hideStatus()
                writeToTerminal("\r\n[33m[Regenerating...][0m\r\n")
                sessionBusy[activeSessionId] = true
                showStatusThinking()
                startThinkingTimeout()
            }
            claudeService?.sendInput(text + "\r")
        }
    }

    private fun openWebPreview() {
        val projectPath = prefs.getProjectPath().ifEmpty { filesDir.absolutePath }
        val intent = android.content.Intent(this, com.claudecodesetup.ui.PreviewActivity::class.java)
            .putExtra("project_path", projectPath)
        startActivity(intent)
    }
}
