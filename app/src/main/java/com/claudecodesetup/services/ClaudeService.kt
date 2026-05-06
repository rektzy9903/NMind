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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class ClaudeService : LifecycleService() {

    private val TAG = "ClaudeService"
    private val binder = LocalBinder()

    private lateinit var prefs: AppPreferences
    private lateinit var envManager: EnvironmentManager
    private lateinit var proxyManager: ProxyManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private var claudeProcess: Process? = null
    private var proxyRestartJob: Job? = null

    // Exposed streams for TerminalActivity
    var processStdin: OutputStream? = null
    var processStdout: InputStream? = null
    var onOutputLine: ((String) -> Unit)? = null
    var onProcessExit: (() -> Unit)? = null

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

        startForeground(NOTIF_ID, buildNotification("Claude Code is running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_RESTART_PROXY -> restartProxy()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSession()
        if (wakeLock.isHeld) wakeLock.release()
    }

    // ─── Session lifecycle ────────────────────────────────────────────────────

    fun startSession(mode: String) {
        if (!wakeLock.isHeld) wakeLock.acquire()
        lifecycleScope.launch(Dispatchers.IO) {
            when (mode) {
                AppPreferences.MODE_PROXY -> startProxySession()
                AppPreferences.MODE_GEMINI -> startGeminiSession()
                AppPreferences.MODE_SUBSCRIPTION -> startSubscriptionSession()
                else -> startProxySession()
            }
        }
        scheduleProxyRestart()
    }

    private suspend fun startProxySession() {
        updateNotification("Starting proxy...")
        proxyManager.writeEnvFile(
            prefs.getProviderId(),
            prefs.getApiKey(),
            prefs.getModelId(),
            prefs.getBaseUrl()
        )
        val proxyOk = proxyManager.startProxy { status ->
            updateNotification(status)
        }
        if (!proxyOk) {
            updateNotification("Proxy failed — tap to restart")
            return
        }
        launchClaude(buildProxyEnv())
    }

    private suspend fun startGeminiSession() {
        updateNotification("Connecting to Gemini...")
        launchClaude(buildGeminiEnv())
    }

    private suspend fun startSubscriptionSession() {
        updateNotification("Starting Claude Code...")
        launchClaude(buildSubscriptionEnv())
    }

    private suspend fun launchClaude(extraEnv: Map<String, String>) = withContext(Dispatchers.IO) {
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

            val pb = ProcessBuilder(
                envManager.buildUbuntuShellArgs(shellCmd)
            ).apply {
                directory(envManager.homeDir)
                environment().apply {
                    put("HOME", envManager.homeDir.absolutePath)
                    put("PATH", "${envManager.termuxPrefix.absolutePath}/bin:" +
                        "${envManager.homeDir.absolutePath}/node-v20.11.0-linux-$nodeArch/bin:" +
                        "/usr/local/bin:/usr/bin:/bin")
                }
            }

            claudeProcess = pb.start()
            processStdin = claudeProcess!!.outputStream
            processStdout = claudeProcess!!.inputStream
            prefs.setSessionActive(true)

            updateNotification("Claude Code is running")

            // Stream output
            val reader = claudeProcess!!.inputStream.bufferedReader(Charsets.UTF_8)
            val errReader = claudeProcess!!.errorStream.bufferedReader(Charsets.UTF_8)

            // Merge stdout + stderr for terminal
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val buf = CharArray(4096)
                    var read: Int
                    while (reader.read(buf).also { read = it } != -1) {
                        val chunk = String(buf, 0, read)
                        onOutputLine?.invoke(chunk)
                        watchForAuthErrors(chunk)
                    }
                } catch (_: IOException) {}
            }

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val buf = CharArray(1024)
                    var read: Int
                    while (errReader.read(buf).also { read = it } != -1) {
                        onOutputLine?.invoke(String(buf, 0, read))
                    }
                } catch (_: IOException) {}
            }

            claudeProcess!!.waitFor()
            prefs.setSessionActive(false)
            onProcessExit?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch claude", e)
            onOutputLine?.invoke("\r\n[31mFailed to start Claude Code: ${e.message}[0m\r\n")
        }
    }

    private fun watchForAuthErrors(output: String) {
        when {
            output.contains("Not logged in", ignoreCase = true) ||
            output.contains("authentication", ignoreCase = true) -> {
                // Signal auth issue — TerminalActivity will show dialog
            }
        }
    }

    fun sendInput(text: String) {
        try {
            processStdin?.apply {
                write(text.toByteArray(Charsets.UTF_8))
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send input", e)
        }
    }

    fun stopSession() {
        proxyRestartJob?.cancel()
        claudeProcess?.destroy()
        claudeProcess = null
        proxyManager.killProxy()
        prefs.setSessionActive(false)
        processStdin = null
        processStdout = null
    }

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
            proxyManager.startProxy { updateNotification(it) }
        }
    }

    private fun scheduleProxyRestart() {
        proxyRestartJob?.cancel()
        proxyRestartJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2 * 60 * 60 * 1000L) // 2 hours
                if (prefs.getLoginMode() == AppPreferences.MODE_PROXY) {
                    Log.i(TAG, "Scheduled proxy restart")
                    restartProxy()
                }
            }
        }
    }

    // ─── Env builders ─────────────────────────────────────────────────────────

    private fun buildProxyEnv(): Map<String, String> = mapOf(
        "ANTHROPIC_AUTH_TOKEN" to "freecc",
        "ANTHROPIC_BASE_URL" to "http://localhost:8082",
        "REQUEST_TIMEOUT" to "120",
        "CONNECT_TIMEOUT" to "30"
    )

    private fun buildGeminiEnv(): Map<String, String> = mapOf(
        "ANTHROPIC_API_KEY" to prefs.getApiKey(),
        "ANTHROPIC_BASE_URL" to "https://generativelanguage.googleapis.com/v1beta/openai/",
        "ANTHROPIC_MODEL" to prefs.getModelId(),
        "REQUEST_TIMEOUT" to "120",
        "CONNECT_TIMEOUT" to "30"
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.claudecodesetup.STOP"
        const val ACTION_RESTART_PROXY = "com.claudecodesetup.RESTART_PROXY"
    }
}
