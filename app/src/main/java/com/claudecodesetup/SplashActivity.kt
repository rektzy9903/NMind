package com.claudecodesetup

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.managers.EnvironmentManager

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = AppPreferences(this)
        val envManager = EnvironmentManager(this)

        val next: Class<*> = when {
            !prefs.isSetupComplete() -> SetupActivity::class.java
            !envManager.isBootstrapped() -> {
                // Bootstrap files missing despite the "setup complete" flag — can
                // happen if Android cleared app files or the package was reinstalled.
                // Force re-extraction from step 4 (ubuntu/node checks in later steps
                // have their own existence guards so they won't repeat unnecessarily).
                prefs.setSetupComplete(false)
                prefs.setSetupStep(4)
                SetupActivity::class.java
            }
            !prefs.isProviderConfigured() -> LoginFlowActivity::class.java
            else -> TerminalActivity::class.java
        }

        startActivity(Intent(this, next))
        finish()
    }
}
