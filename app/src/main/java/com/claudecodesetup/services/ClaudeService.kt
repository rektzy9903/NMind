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
import android.util.Base64
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.claudecodesetup.ClaudeApp
import com.claudecodesetup.R
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.managers.LlamaServerManager
import com.claudecodesetup.managers.NodeBridgeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class ClaudeService : LifecycleService() {

    private val TAG = "ClaudeService"
    private val binder = LocalBinder()

    private lateinit var prefs: AppPreferences
    private lateinit var bridge: NodeBridgeManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private val sessions = ConcurrentHashMap<Int, ClaudeSession>()
    private var nextSessionId = 0

    // P6.5: per-session Ubuntu PTY shell connections (the 🐧 tab). A second socket
    // per session, opened lazily on the first setMode('ubuntu'), connects with the
    // ":ubuntu" mode header so bridge.js routes it to attachPtySession (raw byte
    // relay to a live `bash -li` in the guest). Separate from the chat socket.
    private val ptyConns = ConcurrentHashMap<Int, PtyConn>()

    private class PtyConn(val socket: Socket) {
        val out: OutputStream = socket.outputStream
        @Volatile var alive: Boolean = true
    }
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
    // P6.5: raw Ubuntu-PTY output for a session, already base64-encoded for window.ptyWrite().
    var onPtyOutput: ((sessionId: Int, b64: String) -> Unit)? = null
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
                // Transient gate message — strip so replayed history never shows stale busy state
                .replace("[busy — please wait]", "")
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

    private val deviceControlServer = DeviceControlHttpServer()

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs  = AppPreferences(this)
        bridge = NodeBridgeManager(this)
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClaudeCode:WakeLock")
        startForeground(NOTIF_ID, buildNotification("Nexus Mind is ready"))
        deviceControlServer.start(this)
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
        instance = null
        deviceControlServer.stop()
        stopAllSessions()
        releaseWakeLock()
        LlamaServerManager.get(this).stopServer()
    }

    // ─── Session lifecycle ────────────────────────────────────────────────────

    fun createSession(mode: String, initialCwd: String = ""): Int {
        if (sessions.size >= MAX_SESSIONS) {
            onOutput?.invoke(-1, "\r\n[31m[Max $MAX_SESSIONS sessions reached — close a tab first.][0m\r\n")
            return -1
        }

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
        ptyConns.remove(sessionId)?.let { it.alive = false; runCatching { it.socket.close() } }

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

    fun isSessionConnected(): Boolean {
        val session = sessions[activeSessionId] ?: return false
        return session.alive && session.outputStream != null
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

    // ─── Ubuntu PTY (P6.5) ──────────────────────────────────────────────────────

    /** Open the live Ubuntu shell socket for [sessionId] if not already open.
     *  Idempotent: a reconnect re-attaches to the same guest shell (kept alive by
     *  bridge.js for PTY_IDLE_MS), so shell state survives tab/mode switches. */
    fun openPty(sessionId: Int) {
        if (sessionId < 0) return
        if (ptyConns[sessionId]?.alive == true) return
        lifecycleScope.launch(Dispatchers.IO) { connectPty(sessionId) }
    }

    private fun connectPty(sessionId: Int) {
        // Don't open a shell for a session that no longer exists.
        if (!sessions.containsKey(sessionId)) return
        // Guard against a racing second open.
        if (ptyConns[sessionId]?.alive == true) return

        val sock = bridge.openSession() ?: run {
            val err = "\r\n[31m[ubuntu] could not connect to bridge (port ${NodeBridgeManager.BRIDGE_PORT})[0m\r\n"
            onPtyOutput?.invoke(sessionId, Base64.encodeToString(err.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            return
        }
        val conn = PtyConn(sock)
        ptyConns[sessionId] = conn

        val localToken = try {
            java.io.File(filesDir, "local_token").readText().trim()
        } catch (_: Exception) { "" }
        try {
            conn.out.write("SESSION:$sessionId:$localToken:ubuntu\n".toByteArray(Charsets.UTF_8))
            conn.out.flush()
        } catch (_: Exception) {}

        try {
            val buf = ByteArray(8192)
            val input = sock.inputStream
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                if (n == 0) continue
                val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                onPtyOutput?.invoke(sessionId, b64)
            }
        } catch (_: IOException) {
            // Normal socket close (tab switch / shell exit).
        } finally {
            runCatching { sock.close() }
            conn.alive = false
            ptyConns.remove(sessionId, conn)
        }
    }

    /** Write raw bytes from xterm.js to the active session's Ubuntu shell. */
    fun sendPty(sessionId: Int, data: ByteArray) {
        val conn = ptyConns[sessionId] ?: return
        try {
            conn.out.write(data)
            conn.out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "sendPty failed for session $sessionId", e)
        }
    }

    /** Resize the Ubuntu PTY. libpty.so parses the ESC 0xFE control sequence off
     *  the input stream (never forwarded to the shell) and applies TIOCSWINSZ. */
    fun resizePty(sessionId: Int, cols: Int, rows: Int) {
        val conn = ptyConns[sessionId] ?: return
        val resize = byteArrayOf(
            0x1b, 0xfe.toByte(),
            ((cols shr 8) and 0xff).toByte(), (cols and 0xff).toByte(),
            ((rows shr 8) and 0xff).toByte(), (rows and 0xff).toByte()
        )
        try {
            conn.out.write(resize)
            conn.out.flush()
        } catch (_: Exception) {}
    }

    /** Close the local PTY socket. The guest shell stays alive in bridge.js for
     *  PTY_IDLE_MS, so re-opening reattaches to the same session. */
    fun closePty(sessionId: Int) {
        val conn = ptyConns.remove(sessionId) ?: return
        conn.alive = false
        runCatching { conn.socket.close() }
    }

    fun stopAllSessions() {
        ptyConns.values.forEach { runCatching { it.socket.close() }; it.alive = false }
        ptyConns.clear()
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

        // Auto-start llama-server when using local AI provider (e.g. after app restart)
        if (prefs.getProviderId() == "local_llama") {
            val llamaMgr = LlamaServerManager.get(this@ClaudeService)
            if (!llamaMgr.isServerRunning()) {
                if (!llamaMgr.isBinaryAvailable()) {
                    val err = "\r\n[31m[Local AI] libllamaserver.so not found — this build doesn't include on-device AI.[0m\r\n"
                    session.appendOutput(err); onOutput?.invoke(session.id, err)
                    session.alive = false; onSessionEnded?.invoke(session.id)
                    updateNotificationSessionCount(); return@withContext
                }
                val modelId = prefs.getModelId()
                val modelFile = llamaMgr.modelFile(modelId)
                if (!modelFile.exists()) {
                    val err = "\r\n[31m[Local AI] Model '$modelId' not found. Open Personal AI to download it.[0m\r\n"
                    session.appendOutput(err); onOutput?.invoke(session.id, err)
                    session.alive = false; onSessionEnded?.invoke(session.id)
                    updateNotificationSessionCount(); return@withContext
                }
                updateNotification("Starting local AI server…")
                llamaMgr.startServer(modelId)
                if (!llamaMgr.waitUntilReady(30_000L)) {
                    val err = "\r\n[31m[Local AI] Server failed to start. Try re-loading the model in Personal AI.[0m\r\n"
                    session.appendOutput(err); onOutput?.invoke(session.id, err)
                    session.alive = false; onSessionEnded?.invoke(session.id)
                    updateNotificationSessionCount(); return@withContext
                }
            }
        }

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

        // Send session ID and local auth token as the first line so bridge.js
        // can reattach to the correct persistent claude process on reconnect
        // and reject unauthorized connections from other apps on the device.
        val localToken = try {
            java.io.File(filesDir, "local_token").readText().trim()
        } catch (_: Exception) { "" }
        try {
            sock.outputStream.write("SESSION:${session.id}:$localToken\n".toByteArray(Charsets.UTF_8))
            sock.outputStream.flush()
        } catch (_: Exception) {}

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
        @Suppress("WakelockTimeout")
        if (!wakeLock.isHeld) wakeLock.acquire() // released explicitly in releaseWakeLock()
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
            .setContentTitle("Nexus Mind")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(sessions.isEmpty())
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
        if (instance == null || isActivityVisible) return
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
            .setContentTitle("Nexus Mind")
            .setContentText(bodyText)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESPONSE_NOTIF_ID, notif)
    }

    private fun updateNotificationSessionCount() {
        val alive = sessions.values.count { it.alive }
        val text  = when {
            alive == 0 -> "Nexus Mind stopped"
            alive == 1 -> "Nexus Mind is running"
            else       -> "Nexus Mind — $alive sessions running"
        }
        updateNotification(text)
    }

    companion object {
        const val NOTIF_ID          = 1001
        const val RESPONSE_NOTIF_ID = 1002
        const val ACTION_STOP           = "com.claudecodesetup.STOP"
        const val ACTION_RESTART_BRIDGE = "com.claudecodesetup.RESTART_BRIDGE"
        const val MAX_SESSIONS = 4

        var instance: ClaudeService? = null
    }
}
