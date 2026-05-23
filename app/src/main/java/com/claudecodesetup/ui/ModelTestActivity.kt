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
        val currentProviderId = prefs.getProviderId()
        val currentKey = prefs.getApiKey()
        // Fall back to current key if the provider was configured before per-provider storage was added
        val orKey = prefs.getApiKeyForProvider("openrouter")
            .ifEmpty { if (currentProviderId == "openrouter") currentKey else "" }
        val nvKey = prefs.getApiKeyForProvider("nvidia_nim")
            .ifEmpty { if (currentProviderId == "nvidia_nim") currentKey else "" }
        val groqKey = prefs.getApiKeyForProvider("groq")
            .ifEmpty { if (currentProviderId == "groq") currentKey else "" }
        setContent {
            ModelTestScreen(
                apiKey      = currentKey,
                orApiKey    = orKey,
                nvApiKey    = nvKey,
                groqApiKey  = groqKey,
                providerId  = currentProviderId,
                providerUrl = prefs.getBaseUrl(),
                onBack      = { finish() }
            )
        }
    }
}
