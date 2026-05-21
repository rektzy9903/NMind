package com.claudecodesetup.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.claudecodesetup.R
import com.claudecodesetup.SettingsActivity
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AppPreferences

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPreferences(this)
        setContent {
            HomeScreen(
                appName    = getString(R.string.app_name),
                onChatBox  = {
                    if (prefs.isProviderConfigured()) startActivity(Intent(this, TerminalActivity::class.java))
                    else startActivity(Intent(this, ComposeActivity::class.java))
                },
                onTesting  = {
                    if (prefs.isProviderConfigured()) startActivity(Intent(this, ModelTestActivity::class.java))
                    else startActivity(Intent(this, ComposeActivity::class.java))
                },
                onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onProjects = { Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show() },
            )
        }
    }
}
