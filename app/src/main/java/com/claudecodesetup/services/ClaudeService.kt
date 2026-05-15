package com.claudecodesetup.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.claudecodesetup.ClaudeApp
import com.claudecodesetup.R
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.managers.NodeBridgeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.LinkedHashMap

class ClaudeService : LifecycleService() {

    private val TAG = "ClaudeService"
    private val binder = LocalBinder()

    private lateinit var prefs: AppPreferences
    private lateinit var bridge: NodeBridgeManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private val sessions = LinkedHashMap<Int, ClaudeSession>()
    private var nextSessionId = 0
    var activeSessionId: Int = -1
        private set

    // Start the bridge only once per service lifecycle
    private var bridgeStartedThisSession = false

    // Tracks whether TerminalActivity is currently visible; used to suppress
    // background response notifications when the user is already watching.
    var isActivityVisible = false

    private val responseNotifHandler = Handler(Looper.getMainLooper())
    private val responseNotifRunnable = Runnable { fireResponseNotification() }
    private var responseNotifPending = false
    // Accumulates the first 200 chars of response text (ANSI stripped) for notification body.
    private val responsePreviewBuf = StringBuilder()

    // ─── Callbacks (set by TerminalActivity) ─────────────────────────────────

    var onOutput: ((sessionId: Int, data: String) -> Unit)? = null
    var onSessionEnded: ((sessionId: Int) -> Unit)? = null
    var onSessionAdded: ((ClaudeSession) -> Unit)? = null

    // ─── Session model ────────────────────────────────────────────────────────

    class ClaudeSession(val id: Int, var name: String) {
        var socket: Socket? = null
        var outputStream: OutputStream? = null
        var alive: Boolean = false
        var cwd: String = ""

        private val buffer = StringBuilder()

        @Synchronized fun appendOutput(text: String) {
            // Strip ephemeral thinking OSC sequences before buffering so they
            // aren't replayed when TerminalActivity reconnects (would create
            // empty bubbles for every previous AI response).
            val stripped = text
                .replace("]9;thinking-start", "")
                .replace("]9;thinking-done", "")
            buffer.append(stripped)
            if (buffer.length > MAX_BUFFER_CHARS) {
                buffer.delete(0, buffer.length - MAX_BUFFER_CHARS)
            }
        }

        @Synchronized fun clearOutput() = buffer.clear()
        @Synchronized fun getOutput(): String = buffer.toString()

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
        prefs  = AppPreferences(this)
        bridge = NodeBridgeManager(this)
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClaudeCode:WakeLock")
        startForeground(NOTIF_ID, buildNotification("Claude Code is ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP           -> { stopAllSessions(); stopSelf() }
            ACTION_RESTART_BRIDGE -> restartBridge()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllSessions()
        releaseWakeLock()
    }

    // ─── Session lifecycle ────────────────────────────────────────────────────

    fun createSession(mode: String, initialCwd: String = ""): Int {
        if (sessions.size >= MAX_SESSIONS) return -1

        val id      = nextSessionId++
        val session = ClaudeSession(id, "Session ${id + 1}")
        sessions[id] = session

        if (activeSessionId == -1) activeSessionId = id

        onSessionAdded?.invoke(session)

        lifecycleScope.launch(Dispatchers.IO) {
            connectSession(session, mode, initialCwd)
        }

        return id
    }

    fun restartSession(sessionId: Int) {
        val session = sessions[sessionId] ?: return
        runCatching { session.socket?.close() }
        session.alive = false
        session.clearOutput()

        lifecycleScope.launch(Dispatchers.IO) {
            delay(500)
            connectSession(session)
        }
    }

    fun closeSession(sessionId: Int) {
        val session = sessions.remove(sessionId) ?: return
        runCatching { session.socket?.close() }
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
            session.outputStream?.write(text.toByteArray(Charsets.UTF_8))
            session.outputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "sendInput failed for session $activeSessionId", e)
        }
    }

    fun sendResizeAll(cols: Int, rows: Int) {
        val hiC = (cols shr 8) and 0xff
        val loC = cols and 0xff
        val hiR = (rows shr 8) and 0xff
        val loR = rows and 0xff
        val resize = byteArrayOf(0x1b, 0xfe.toByte(), hiC.toByte(), loC.toByte(), hiR.toByte(), loR.toByte())
        sessions.values.forEach { s ->
            try { s.outputStream?.write(resize); s.outputStream?.flush() } catch (_: Exception) {}
        }
    }

    fun stopAllSessions() {
        sessions.values.forEach { s -> runCatching { s.socket?.close() }; s.alive = false }
        sessions.clear()
        activeSessionId = -1
        bridgeStartedThisSession = false
        prefs.setSessionActive(false)
        releaseWakeLock()
    }

    fun getAllSessions(): List<ClaudeSession> = sessions.values.toList()
    fun getSession(id: Int): ClaudeSession? = sessions[id]

    // ─── TCP socket connection ────────────────────────────────────────────────

    private suspend fun connectSession(
        session: ClaudeSession,
        mode: String? = null,
        initialCwd: String = ""
    ) = withContext(Dispatchers.IO) {
        val currentMode = mode ?: prefs.getLoginMode()

        // Start/refresh the Node.js bridge once per service lifecycle.
        // Always refresh config so model/key changes from Settings take effect.
        if (!bridgeStartedThisSession) {
            bridgeStartedThisSession = true
            updateNotification("Starting Claude bridge…")
            bridge.writeMcpConfig(prefs)
            bridge.startBridge(
                currentMode,
                prefs.getApiKey(),
                prefs.getModelId(),
                prefs.getBaseUrl(),
                prefs.getProviderId(),
                prefs.getProjectPath(),
                prefs.getCustomSystemPrompt(),
                prefs
            )
        } else {
            bridge.refreshConfig(prefs)
        }

        // Wait up to 60 s for bridge.js to open port 8083
        if (!waitForBridge(60)) {
            val err = "\r\n[31mBridge timeout — Node.js did not start in time.[0m\r\n"
            session.appendOutput(err)
            onOutput?.invoke(session.id, err)
            session.alive = false
            onSessionEnded?.invoke(session.id)
            updateNotificationSessionCount()
            return@withContext
        }

        val sock = bridge.openSession()
        if (sock == null) {
            val err = "\r\n[31mCould not connect to Claude bridge (port ${NodeBridgeManager.BRIDGE_PORT}).[0m\r\n"
            session.appendOutput(err)
            onOutput?.invoke(session.id, err)
            session.alive = false
            onSessionEnded?.invoke(session.id)
            updateNotificationSessionCount()
            return@withContext
        }

        session.socket       = sock
        session.outputStream = sock.outputStream
        session.alive        = true
        prefs.setSessionActive(true)
        acquireWakeLock()
        updateNotificationSessionCount()

        // If a starting directory was requested, send a cd command immediately
        if (initialCwd.isNotEmpty()) {
            try {
                session.outputStream?.write(("$ cd $initialCwd\r").toByteArray(Charsets.UTF_8))
                session.outputStream?.flush()
            } catch (_: Exception) {}
        }

        // Stream output until socket closes
        try {
            val buf   = ByteArray(4096)
            val input = sock.inputStream
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                val chunk = String(buf, 0, n, Charsets.UTF_8)
                session.appendOutput(chunk)
                onOutput?.invoke(session.id, chunk)
                // Schedule a background notification if the user isn't watching.
                // Accumulate a preview snippet (ANSI stripped) for the notification body.
                // Debounced: fires 1.5 s after the last chunk (response likely done).
                if (!isActivityVisible) {
                    if (responsePreviewBuf.length < 200) {
                        val clean = chunk.replace(Regex("\\[[0-9;]*[a-zA-Z]"), "")
                            .replace(Regex("][^]*"), "")
                            .replace('\r', ' ')
                        responsePreviewBuf.append(clean)
                    }
                    if (!responseNotifPending) {
                        responseNotifPending = true
                        responseNotifHandler.postDelayed(responseNotifRunnable, 1500)
                    }
                }
            }
        } catch (_: IOException) {
            // Normal socket close
        } finally {
            runCatching { sock.close() }
            session.alive        = false
            session.socket       = null
            session.outputStream = null

            if (sessions.values.none { it.alive }) {
                prefs.setSessionActive(false)
                releaseWakeLock()
            }
            onSessionEnded?.invoke(session.id)
            updateNotificationSessionCount()
        }
    }

    private suspend fun waitForBridge(timeoutSeconds: Int): Boolean {
        repeat(timeoutSeconds) {
            if (bridge.isBridgeReachable()) return true
            delay(1000)
        }
        return bridge.isBridgeReachable()
    }

    private fun restartBridge() {
        bridgeStartedThisSession = false
        lifecycleScope.launch(Dispatchers.IO) {
            val mode = prefs.getLoginMode()
            bridge.startBridge(mode, prefs.getApiKey(), prefs.getModelId(), prefs.getBaseUrl(),
                prefs.getProviderId(), prefs.getProjectPath(), prefs.getCustomSystemPrompt(), prefs)
        }
    }

    // ─── WakeLock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire(4 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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

    fun cancelResponseNotification() {
        responseNotifHandler.removeCallbacks(responseNotifRunnable)
        responseNotifPending = false
        responsePreviewBuf.clear()
        getSystemService(NotificationManager::class.java).cancel(RESPONSE_NOTIF_ID)
    }

    private fun fireResponseNotification() {
        responseNotifPending = false
        if (isActivityVisible) return
        val preview = responsePreviewBuf.toString().take(120).trimEnd().replace('\n', ' ')
        responsePreviewBuf.clear()
        val bodyText = if (preview.isNotBlank()) preview else "AI response ready — tap to view"
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, TerminalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, ClaudeApp.CHANNEL_RESPONSE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Claude Code")
            .setContentText(bodyText)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESPONSE_NOTIF_ID, notif)
    }

    private fun updateNotificationSessionCount() {
        val alive = sessions.values.count { it.alive }
        val text  = when {
            alive == 0 -> "Claude Code stopped"
            alive == 1 -> "Claude Code is running"
            else       -> "Claude Code — $alive sessions running"
        }
        updateNotification(text)
    }

    companion object {
        const val NOTIF_ID          = 1001
        const val RESPONSE_NOTIF_ID = 1002
        const val ACTION_STOP           = "com.claudecodesetup.STOP"
        const val ACTION_RESTART_BRIDGE = "com.claudecodesetup.RESTART_BRIDGE"
        const val MAX_SESSIONS = 4
    }
}
