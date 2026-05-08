package com.claudecodesetup

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.managers.BridgeManager

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = AppPreferences(this)
        val bridge = BridgeManager(this)

        val next: Class<*> = when {
            !bridge.isTermuxInstalled() -> TermuxInstallActivity::class.java
            !prefs.isTermuxSetupComplete() -> SetupActivity::class.java
            !prefs.isProviderConfigured() -> LoginFlowActivity::class.java
            else -> TerminalActivity::class.java
        }

        startActivity(Intent(this, next))
        finish()
    }
}
