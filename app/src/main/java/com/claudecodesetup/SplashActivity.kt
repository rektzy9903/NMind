package com.claudecodesetup

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.ui.HomeActivity
import com.claudecodesetup.ui.SplashAnimationScreen

class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = AppPreferences(this)

        val sharedText = if (intent?.action == Intent.ACTION_SEND &&
            intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else null

        val shouldPlay = !ClaudeApp.introPlayed
        ClaudeApp.introPlayed = true

        setContent {
            SplashAnimationScreen(shouldPlay = shouldPlay) {
                val next: Class<*> = when {
                    !prefs.isNodeSetupComplete() -> SetupActivity::class.java
                    sharedText != null           -> TerminalActivity::class.java
                    else                         -> HomeActivity::class.java
                }
                val nextIntent = Intent(this, next)
                if (sharedText != null) nextIntent.putExtra("shared_text", sharedText)
                startActivity(nextIntent)
                finish()
            }
        }
    }
}
