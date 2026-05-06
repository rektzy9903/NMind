package com.claudecodesetup

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ClaudeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        NotificationChannel(
            CHANNEL_RUNNING,
            "Claude Code Running",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Claude Code is active"
            setShowBadge(false)
        }.also { manager.createNotificationChannel(it) }

        NotificationChannel(
            CHANNEL_SETUP,
            "Setup Progress",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "First-time setup notifications"
        }.also { manager.createNotificationChannel(it) }
    }

    companion object {
        const val CHANNEL_RUNNING = "claude_running"
        const val CHANNEL_SETUP = "claude_setup"
    }
}
