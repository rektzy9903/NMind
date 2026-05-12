package com.claudecodesetup

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.claudecodesetup.data.AppPreferences

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = AppPreferences(this)

        val next: Class<*> = when {
            !prefs.isNodeSetupComplete()  -> SetupActivity::class.java
            !prefs.isProviderConfigured() -> com.claudecodesetup.ui.ComposeActivity::class.java
            else                          -> com.claudecodesetup.ui.HomeActivity::class.java
        }

        startActivity(Intent(this, next))
        finish()
    }
}
