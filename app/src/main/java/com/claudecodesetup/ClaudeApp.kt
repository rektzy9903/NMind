package com.claudecodesetup

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.managers.NodeBridgeManager

class ClaudeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Start Node.js engine as early as possible so the bridge is warm
        // by the time the user reaches the terminal. NodeEngine guards
        // against duplicate starts via its started flag.
        val prefs = AppPreferences(this)
        if (prefs.isNodeSetupComplete()) {
            NodeBridgeManager(this).startBridge(
                prefs.getLoginMode(),
                prefs.getApiKey(),
                prefs.getModelId(),
                prefs.getBaseUrl(),
                prefs.getProviderId()
            )
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        NotificationChannel(
            CHANNEL_RUNNING,
            "Nexus Mind Running",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Nexus Mind is active"
            setShowBadge(false)
        }.also { manager.createNotificationChannel(it) }

        NotificationChannel(
            CHANNEL_SETUP,
            "Setup Progress",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "First-time setup notifications"
        }.also { manager.createNotificationChannel(it) }

        NotificationChannel(
            CHANNEL_RESPONSE,
            "AI Response Ready",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifies when the AI finishes responding while the app is in the background"
        }.also { manager.createNotificationChannel(it) }

        NotificationChannel(
            CHANNEL_OVERLAY,
            "Floating Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while the floating Claude assistant is active"
            setShowBadge(false)
        }.also { manager.createNotificationChannel(it) }
    }

    companion object {
        const val CHANNEL_RUNNING  = "claude_running"
        const val CHANNEL_SETUP    = "claude_setup"
        const val CHANNEL_RESPONSE = "claude_response"
        const val CHANNEL_OVERLAY  = "claude_overlay"

        // True once the intro animation has played this process lifetime.
        // Resets to false when the OS kills the process (cold start → plays again).
        // Stays true while the app is backgrounded (warm return → skips).
        val introPlayed = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
