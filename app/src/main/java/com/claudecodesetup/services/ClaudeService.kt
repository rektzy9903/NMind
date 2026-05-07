package com.claudecodesetup.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.claudecodesetup.ClaudeApp
import com.claudecodesetup.R
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.managers.EnvironmentManager
import com.claudecodesetup.managers.ProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.LinkedHashMap

class ClaudeService : LifecycleService() {

    private val TAG = "ClaudeService"
    private val binder = LocalBinder()

    private lateinit var prefs: AppPreferences
    private lateinit var envManager: EnvironmentManager
    private lateinit var proxyManager: ProxyManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private val sessions = LinkedHashMap<Int, ClaudeSession>()
    private var nextSessionId = 0
    var activeSessionId: Int = -1
        private set

    private var proxyRestartJob: Job? = null

    // ─── Callbacks (set by TerminalActivity) ─────────────────────────────────

    /** Called on IO thread when any session produces output. */
    var onOutput: ((sessionId: Int, data: String) -> Unit)? = null

    /** Called on IO thread when a session process exits. */
    var onSessionEnded: ((sessionId: Int) -> Unit)? = null

    /** Called on main thread after a new session is created and added. */
    var onSessionAdded: ((ClaudeSession) -> Unit)? = null

    // ─── Session model ────────────────────────────────────────────────────────

    class ClaudeSession(val id: Int, val name: String) {
        var process: Process? = null
        var stdin: OutputStream? = null
        var alive: Boolean = false

        private val buffer = StringBuilder()

        @Synchronized
        fun appendOutput(text: String) {
            buffer.append(text)
            if (buffer.length > MAX_BUFFER_CHARS) {
                buffer.delete(0, buffer.length - MAX_BUFFER_CHARS)
            }
        }

        @Synchronized
        fun clearOutput() = buffer.clear()

        @Synchronized
        fun getOutput(): String = buffer.toString()

        companion object {
            private const val MAX_BUFFER_CHARS = 200_000
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ClaudeService = this@ClaudeService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        envManager = EnvironmentManager(this)
        proxyManager = ProxyManager(envManager)

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClaudeCode:WakeLock")

        startForeground(NOTIF_ID, buildNotification("Claude Code is ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopAllSessions()
                stopSelf()
            }
            ACTION_RESTART_PROXY -> restartProxy()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllSessions()
        releaseWakeLock()
    }

    // ─── Session lifecycle ────────────────────────────────────────────────────

    /**
     * Create a new session (up to [MAX_SESSIONS]). Returns the session ID
     * or -1 if the limit is reached.
     */
    fun createSession(mode: String): Int {
        if (sessions.size >= MAX_SESSIONS) return -1

        val id = nextSessionId++
        val session = ClaudeSession(id, "Session ${id + 1}")
        sessions[id] = session

        if (activeSessionId == -1) activeSessionId = id

        lifecycleScope.launch(Dispatchers.IO) {
            when (mode) {
                AppPreferences.MODE_PROXY        -> startProxySession(session)
                AppPreferences.MODE_GEMINI       -> startGeminiSession(session)
                AppPreferences.MODE_SUBSCRIPTION -> startSubscriptionSession(session)
                else                             -> startProxySession(session)
            }
        }

        onSessionAdded?.invoke(session)
        scheduleProxyRestart()
        return id
    }

    /**
     * Restart (re-launch claude) inside an existing session slot, reusing its
     * tab position and ID. Clears the session's output buffer first.
     */
    fun restartSession(sessionId: Int) {
        val session = sessions[sessionId] ?: return
        session.process?.destroy()
        session.alive = false
        session.clearOutput()

        lifecycleScope.launch(Dispatchers.IO) {
            val mode = prefs.getLoginMode()
            when (mode) {
                AppPreferences.MODE_PROXY        -> startProxySession(session)
                AppPreferences.MODE_GEMINI       -> startGeminiSession(session)
                AppPreferences.MODE_SUBSCRIPTION -> startSubscriptionSession(session)
                else                             -> startProxySession(session)
            }
        }
    }

    fun closeSession(sessionId: Int) {
        val session = sessions.remove(sessionId) ?: return
        session.process?.destroy()
        session.alive = false

        if (activeSessionId == sessionId) {
            activeSessionId = sessions.keys.firstOrNull() ?: -1
        }

        updateNotificationSessionCount()
        if (sessions.values.none { it.alive }) {
            prefs.setSessionActive(false)
            releaseWakeLock()
        }
    }

    fun switchToSession(id: Int) {
        if (sessions.containsKey(id)) activeSessionId = id
    }

    fun sendInput(text: String) {
        val session = sessions[activeSessionId] ?: return
        try {
            session.stdin?.apply {
                write(text.toByteArray(Charsets.UTF_8))
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send input to session $activeSessionId", e)
        }
    }

    fun stopAllSessions() {
        proxyRestartJob?.cancel()
        sessions.values.forEach { s -> s.process?.destroy(); s.alive = false }
        sessions.clear()
        activeSessionId = -1
        proxyManager.killProxy()
        prefs.setSessionActive(false)
        releaseWakeLock()
    }

    fun getAllSessions(): List<ClaudeSession> = sessions.values.toList()
    fun getSession(id: Int): ClaudeSession? = sessions[id]

    // ─── Session launchers ────────────────────────────────────────────────────

    private suspend fun startProxySession(session: ClaudeSession) {
        if (!proxyManager.isProxyAlive()) {
            updateNotification("Starting proxy...")
            proxyManager.writeEnvFile(
                prefs.getProviderId(),
                prefs.getApiKey(),
                prefs.getModelId(),
                prefs.getBaseUrl()
            )
            val arch = prefs.getDetectedArch().ifEmpty { "arm64" }
            val nodeArch = envManager.nodeArchString(arch)
            val proxyOk = proxyManager.startProxy(nodeArch) { updateNotification(it) }
            if (!proxyOk) {
                val err = "\r\n[31mProxy failed to start — check logs[0m\r\n"
                session.appendOutput(err)
                onOutput?.invoke(session.id, err)
                return
            }
        }
        launchClaude(session, buildProxyEnv())
    }

    private suspend fun startGeminiSession(session: ClaudeSession) {
        updateNotification("Connecting to Gemini...")
        launchClaude(session, buildGeminiEnv())
    }

    private suspend fun startSubscriptionSession(session: ClaudeSession) {
        updateNotification("Starting Claude Code...")
        launchClaude(session, buildSubscriptionEnv())
    }

    private suspend fun launchClaude(
        session: ClaudeSession,
        extraEnv: Map<String, String>
    ) = withContext(Dispatchers.IO) {
        try {
            val arch = prefs.getDetectedArch().ifEmpty { "arm64" }
            val nodeArch = envManager.nodeArchString(arch)

            val fullEnv = buildMap {
                put("HOME", "/root")
                put("PATH", "/root/node-v20.11.0-linux-$nodeArch/bin:/usr/local/bin:/usr/bin:/bin")
                put("TERM", "xterm-256color")
                put("LANG", "en_US.UTF-8")
                putAll(extraEnv)
            }

            val shellCmd = buildString {
                fullEnv.forEach { (k, v) -> append("export $k='$v'; ") }
                append("claude")
            }

            val pb = ProcessBuilder(envManager.buildUbuntuShellArgs(shellCmd)).apply {
                directory(envManager.homeDir)
                environment().apply {
                    put("HOME", envManager.homeDir.absolutePath)
                    put("PATH", "${envManager.termuxPrefix.absolutePath}/bin:" +
                        "${envManager.homeDir.absolutePath}/node-v20.11.0-linux-$nodeArch/bin:" +
                        "/usr/local/bin:/usr/bin:/bin")
                }
            }

            val proc = pb.start()
            session.process = proc
            session.stdin = proc.outputStream
            session.alive = true
            prefs.setSessionActive(true)
            acquireWakeLock()
            updateNotificationSessionCount()

            // Stream stdout and stderr — each on its own coroutine so neither blocks the other
            val stdoutJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
                    val buf = CharArray(4096)
                    var read: Int
                    while (reader.read(buf).also { read = it } != -1) {
                        val chunk = String(buf, 0, read)
                        session.appendOutput(chunk)
                        onOutput?.invoke(session.id, chunk)
                        watchForAuthErrors(chunk)
                    }
                } catch (_: IOException) {}
            }

            val stderrJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val reader = proc.errorStream.bufferedReader(Charsets.UTF_8)
                    val buf = CharArray(1024)
                    var read: Int
                    while (reader.read(buf).also { read = it } != -1) {
                        val chunk = String(buf, 0, read)
                        session.appendOutput(chunk)
                        onOutput?.invoke(session.id, chunk)
                    }
                } catch (_: IOException) {}
            }

            proc.waitFor()
            stdoutJob.join()
            stderrJob.join()

            session.alive = false
            session.stdin = null

            if (sessions.values.none { it.alive }) {
                prefs.setSessionActive(false)
                releaseWakeLock()
            }

            onSessionEnded?.invoke(session.id)
            updateNotificationSessionCount()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch claude in session ${session.id}", e)
            val err = "\r\n[31mFailed to start Claude Code: ${e.message}[0m\r\n"
            session.appendOutput(err)
            onOutput?.invoke(session.id, err)
            session.alive = false
            onSessionEnded?.invoke(session.id)
        }
    }

    private fun watchForAuthErrors(output: String) {
        // Reserved for future auth-error detection and user notification
        @Suppress("UNUSED_EXPRESSION")
        output
    }

    // ─── WakeLock helpers ─────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            // 4-hour hard cap: prevents runaway battery drain if sessions are
            // abandoned without explicitly stopping the service.
            wakeLock.acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    // ─── Proxy management ────────────────────────────────────────────────────

    private fun restartProxy() {
        lifecycleScope.launch(Dispatchers.IO) {
            proxyManager.killProxy()
            delay(500)
            proxyManager.writeEnvFile(
                prefs.getProviderId(),
                prefs.getApiKey(),
                prefs.getModelId(),
                prefs.getBaseUrl()
            )
            val arch = prefs.getDetectedArch().ifEmpty { "arm64" }
            val nodeArch = envManager.nodeArchString(arch)
            proxyManager.startProxy(nodeArch) { updateNotification(it) }
        }
    }

    private fun scheduleProxyRestart() {
        proxyRestartJob?.cancel()
        proxyRestartJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2 * 60 * 60 * 1000L) // every 2 hours
                if (prefs.getLoginMode() == AppPreferences.MODE_PROXY &&
                    sessions.values.any { it.alive }) {
                    Log.i(TAG, "Scheduled proxy restart")
                    restartProxy()
                }
            }
        }
    }

    // ─── Env builders ─────────────────────────────────────────────────────────

    private fun buildProxyEnv(): Map<String, String> = mapOf(
        "ANTHROPIC_AUTH_TOKEN" to "freecc",
        "ANTHROPIC_BASE_URL"  to "http://localhost:8082",
        "REQUEST_TIMEOUT"     to "120",
        "CONNECT_TIMEOUT"     to "30"
    )

    private fun buildGeminiEnv(): Map<String, String> = mapOf(
        "ANTHROPIC_API_KEY" to prefs.getApiKey(),
        "ANTHROPIC_BASE_URL" to "https://generativelanguage.googleapis.com/v1beta/openai/",
        "ANTHROPIC_MODEL"   to prefs.getModelId(),
        "REQUEST_TIMEOUT"   to "120",
        "CONNECT_TIMEOUT"   to "30"
    )

    private fun buildSubscriptionEnv(): Map<String, String> = emptyMap()

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, TerminalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ClaudeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, ClaudeApp.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle("Claude Code")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun updateNotificationSessionCount() {
        val alive = sessions.values.count { it.alive }
        val text = when {
            alive == 0 -> "Claude Code stopped"
            alive == 1 -> "Claude Code is running"
            else       -> "Claude Code — $alive sessions running"
        }
        updateNotification(text)
    }

    companion object {
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.claudecodesetup.STOP"
        const val ACTION_RESTART_PROXY = "com.claudecodesetup.RESTART_PROXY"
        const val MAX_SESSIONS = 4
    }
}
