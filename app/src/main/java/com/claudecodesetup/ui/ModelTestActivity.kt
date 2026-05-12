package com.claudecodesetup.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Providers

class ModelTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPreferences(this)
        setContent {
            ModelTestScreen(
                apiKey      = prefs.getApiKey(),
                providerId  = prefs.getProviderId(),
                providerUrl = prefs.getBaseUrl(),
                onBack      = { finish() }
            )
        }
    }
}
