package com.claudecodesetup.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Providers
import com.claudecodesetup.data.ProvidersRepository

class ModelTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPreferences(this)
        val currentProviderId = prefs.getProviderId()
        val currentKey = prefs.getApiKey()
        val currentUrl = prefs.getBaseUrl()

        // Collect keys + URLs for all live-fetch providers that have a key configured.
        // Fall back to the current key when the provider was set before per-provider key
        // storage was added.
        val keys = mutableMapOf<String, String>()
        val urls = mutableMapOf<String, String>()
        for (provider in ProvidersRepository.currentList().filter { it.supportsLiveFetch }) {
            val key = prefs.getApiKeyForProvider(provider.id)
                .ifEmpty { if (currentProviderId == provider.id) currentKey else "" }
            if (key.isNotEmpty()) {
                keys[provider.id] = key
                urls[provider.id] = if (currentProviderId == provider.id) currentUrl else provider.baseUrl
            }
        }

        setContent {
            ModelTestScreen(
                keys               = keys,
                urls               = urls,
                currentProviderId  = currentProviderId,
                onBack             = { finish() }
            )
        }
    }
}
