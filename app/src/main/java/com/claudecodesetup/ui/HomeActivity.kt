package com.claudecodesetup.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.claudecodesetup.R
import com.claudecodesetup.SettingsActivity
import com.claudecodesetup.TerminalActivity

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HomeScreen(
                appName = getString(R.string.app_name),
                onChatBox  = { startActivity(Intent(this, TerminalActivity::class.java)) },
                onTesting  = { startActivity(Intent(this, ModelTestActivity::class.java)) },
                onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
            )
        }
    }
}
