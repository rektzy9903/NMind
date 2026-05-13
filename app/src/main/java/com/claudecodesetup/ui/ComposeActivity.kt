package com.claudecodesetup.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Provider
import com.claudecodesetup.data.Providers

class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPreferences(this)
        val startAt = intent.getStringExtra("start_at") ?: "subscription"
        setContent {
            AppRoot(startAt = startAt, prefs = prefs) { provider, apiKey, model ->
                val mode = if (provider.id == "anthropic") AppPreferences.MODE_SUBSCRIPTION
                           else AppPreferences.MODE_PROXY
                prefs.setLoginMode(mode)
                prefs.setProviderId(provider.id)
                prefs.setApiKey(apiKey)
                prefs.setApiKeyForProvider(provider.id, apiKey)
                prefs.setModelId(model.modelId)
                prefs.setBaseUrl(provider.baseUrl)
                prefs.setProviderConfigured(true)
                startActivity(Intent(this, TerminalActivity::class.java))
                finish()
            }
        }
    }
}

@Composable
private fun AppRoot(
    startAt: String,
    prefs: AppPreferences,
    onComplete: (Provider, String, AiModel) -> Unit
) {
    // When jumping directly to picker (e.g. from Settings → Change model), restore current prefs
    val initProvider = remember {
        if (startAt == "picker") Providers.byId(prefs.getProviderId()) ?: Providers.GEMINI
        else null
    }
    val initKey = remember { if (startAt == "picker") prefs.getApiKey() else "" }

    var screen by remember { mutableStateOf(startAt) }
    var selectedProvider by remember { mutableStateOf<Provider?>(initProvider) }
    var storedKey by remember { mutableStateOf(initKey) }

    when (screen) {
        "subscription" -> SubscriptionScreen(
            onYes = {
                selectedProvider = Providers.ANTHROPIC
                screen = "key"
            },
            onNo = { screen = if (prefs.getSkipMalaysiaPrompt()) "providers" else "malaysia" }
        )
        "malaysia" -> MalaysiaScreen(
            onYes = { skip -> if (skip) prefs.setSkipMalaysiaPrompt(true); screen = "gemini_recommend" },
            onNo  = { skip -> if (skip) prefs.setSkipMalaysiaPrompt(true); screen = "providers" }
        )
        "gemini_recommend" -> GeminiRecommendScreen(
            onUseGemini = {
                selectedProvider = Providers.GEMINI
                screen = "key"
            },
            onChoose = { screen = "providers" }
        )
        "providers" -> ProviderListScreen(
            onSelect = { provider ->
                selectedProvider = provider
                screen = "key"
            },
            onBack = { screen = "malaysia" }
        )
        "key" -> ApiKeyScreen(
            provider = selectedProvider ?: Providers.GEMINI,
            onSuccess = { key ->
                storedKey = key
                screen = "picker"
            },
            onBack = {
                screen = when (selectedProvider?.id) {
                    "anthropic" -> "subscription"
                    else -> "providers"
                }
            }
        )
        "picker" -> ModelPickerScreen(
            provider = selectedProvider ?: Providers.GEMINI,
            apiKey = storedKey,
            onConfirm = { model ->
                onComplete(selectedProvider ?: Providers.GEMINI, storedKey, model)
            },
            onBack = { screen = if (startAt == "picker") "providers" else "key" }
        )
    }
}
