package com.claudecodesetup.receivers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.claudecodesetup.ClaudeApp
import com.claudecodesetup.R
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AppPreferences

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = AppPreferences(context)
        if (!prefs.isSetupComplete() || !prefs.isProviderConfigured()) return

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, TerminalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, ClaudeApp.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle("Claude Code")
            .setContentText("Tap to resume your session")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(2001, notification)
    }
}
