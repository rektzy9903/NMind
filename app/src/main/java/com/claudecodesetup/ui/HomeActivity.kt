package com.claudecodesetup.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.claudecodesetup.BuildConfig
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
                    // DEBUG builds: open the terminal even with no provider configured
                    // so engine probes (e.g. !test-proot) are reachable without going
                    // through the API-key flow — which is unpasteable on Appetize.
                    // Release builds keep the normal provider gate.
                    if (prefs.isProviderConfigured() || BuildConfig.DEBUG)
                        startActivity(Intent(this, TerminalActivity::class.java))
                    else startActivity(Intent(this, ComposeActivity::class.java))
                },
                onTesting  = {
                    if (prefs.isProviderConfigured()) startActivity(Intent(this, ModelTestActivity::class.java))
                    else startActivity(Intent(this, ComposeActivity::class.java))
                },
                onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onProjects = { startActivity(Intent(this, ProjectManagerActivity::class.java)) },
                onDiscussion = { startActivity(Intent(this, DiscussionActivity::class.java)) },
                onQuickAsk   = { startActivity(Intent(this, QuickAskActivity::class.java)) },
            )
        }
    }
}
