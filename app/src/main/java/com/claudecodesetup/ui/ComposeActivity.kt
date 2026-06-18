package com.claudecodesetup.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Provider
import com.claudecodesetup.data.Providers
import com.claudecodesetup.managers.NodeBridgeManager

class ComposeActivity : ComponentActivity() {
    /** Apply FLAG_SECURE only on screens that show secrets (API-key entry, OAuth login).
     *  The provider list and model picker stay non-secure so users can screenshot them. */
    fun setScreenSecure(secure: Boolean) {
        if (secure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

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
                // For URL-configurable providers (Ollama, private servers), prefer the
                // URL the user actually typed over the hardcoded provider default.
                val effectiveBaseUrl = if (provider.isUrlConfigurable)
                    prefs.getCustomBaseUrlForProvider(provider.id).ifEmpty { provider.baseUrl }
                else provider.baseUrl
                prefs.setBaseUrl(effectiveBaseUrl)
                prefs.setProviderConfigured(true)
                // Rewrite bridge_config.json NOW so the new provider/key/url is live
                // for the next request — including the interactive 🐧 Ubuntu `claude`
                // (gateway mode, proxy reads cfg.apiKey/cfg.providerUrl fresh per
                // request). Previously this relied on TerminalActivity.onResume's
                // modelId-only change check, which left a stale-config window after a
                // provider switch → upstream 403 "Invalid or unauthorised API key".
                NodeBridgeManager(this).refreshConfig(prefs)
                // When launched from the terminal header pill (start_at=picker), just
                // save prefs and return — the existing TerminalActivity resumes via back
                // stack and its onResume() detects the model change. Launching a new
                // TerminalActivity here would destroy the existing session.
                if (startAt != "picker") {
                    startActivity(Intent(this, TerminalActivity::class.java))
                }
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
    // Tracks whether the most recent transition into "picker" auto-skipped
    // the ApiKeyScreen (saved key + Skip-Key-Prompt toggle on). Used so the
    // picker's back button returns to the provider list instead of landing
    // on a key screen the user never saw.
    var skippedKeyScreen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            storedKey = ""
            screen = "picker"
        }
        // On cancel, stay on "claude_auth" so user can try again or switch to API key
    }

    // Secure only the screens that display secrets; leave provider list + picker
    // screenshottable.
    LaunchedEffect(screen) {
        (context as? ComposeActivity)?.setScreenSecure(screen == "key" || screen == "claude_auth")
    }

    when (screen) {
        "subscription" -> SubscriptionScreen(
            onYes = {
                selectedProvider = Providers.ANTHROPIC
                screen = "claude_auth"
            },
            onNo = { screen = "providers" }
        )
        "claude_auth" -> ClaudeAuthScreen(
            onLoginWithClaude = {
                loginLauncher.launch(Intent(context, ClaudeLoginActivity::class.java))
            },
            onUseApiKey = { screen = "key" },
            onBack = { screen = "subscription" }
        )
        "providers" -> ProviderListScreen(
            onSelect = { provider ->
                selectedProvider = Providers.byId(provider.id) ?: provider
                val savedKey = prefs.getApiKeyForProvider(provider.id)
                val canSkip = prefs.isSkipKeyPromptEnabled() && savedKey.isNotEmpty()
                screen = when {
                    provider.id == "ollama" -> "local_models"
                    canSkip -> {
                        storedKey = savedKey
                        skippedKeyScreen = true
                        "picker"
                    }
                    else -> { skippedKeyScreen = false; "key" }
                }
            },
            onBack = { screen = "subscription" }
        )
        "local_models" -> LocalModelsScreen(
            onModelSelected = { modelId ->
                // Model loaded in-app — auto-configure localhost llama-server and jump to chat
                prefs.setBaseUrl("http://127.0.0.1:8080/v1")
                storedKey = ""
                val model = AiModel(modelId, modelId, emptySet(), "")
                onComplete(Providers.LOCAL_LLAMA, "", model)
            },
            onRemoteServer = { url, apiKey ->
                // User entered a remote server URL — save it and go to model picker
                val normalized = if (!url.contains("/v1")) url.trimEnd('/') + "/v1" else url
                prefs.setCustomBaseUrlForProvider("ollama", normalized)
                prefs.setBaseUrl(normalized)
                if (apiKey.isNotBlank()) {
                    prefs.setApiKey(apiKey)
                    prefs.setApiKeyForProvider("ollama", apiKey)
                }
                selectedProvider = Providers.OLLAMA
                storedKey = apiKey
                screen = "picker"
            },
            onBack = { screen = "providers" }
        )
        "key" -> ApiKeyScreen(
            provider = selectedProvider ?: Providers.GEMINI,
            onSuccess = { key ->
                storedKey = key
                screen = "picker"
            },
            onBack = {
                screen = when (selectedProvider?.id) {
                    "anthropic" -> "claude_auth"
                    "ollama"    -> "local_models"
                    else -> "providers"
                }
            }
        )
        "picker" -> {
            val baseProvider = selectedProvider ?: Providers.GEMINI
            val effectiveProvider = if (baseProvider.isUrlConfigurable) {
                val customUrl = prefs.getCustomBaseUrlForProvider(baseProvider.id)
                if (customUrl.isNotBlank()) baseProvider.copy(baseUrl = customUrl) else baseProvider
            } else baseProvider
            ModelPickerScreen(
                provider = effectiveProvider,
                apiKey = storedKey,
                onConfirm = { model ->
                    onComplete(effectiveProvider, storedKey, model)
                },
                onBack = {
                    if (startAt == "picker") (context as? android.app.Activity)?.finish()
                    else screen = when {
                        startAt == "providers" -> "providers"
                        selectedProvider?.id == "ollama" -> "local_models"
                        skippedKeyScreen -> { skippedKeyScreen = false; "providers" }
                        else -> "key"
                    }
                }
            )
        }
    }
}

@Composable
private fun ClaudeAuthScreen(
    onLoginWithClaude: () -> Unit,
    onUseApiKey: () -> Unit,
    onBack: () -> Unit
) {
    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Sign in to Claude",
                color = Color.White,
                fontFamily = DmSansFamily,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Use your Claude.ai subscription or enter an API key.",
                color = Color(0xFF9CA3AF),
                fontFamily = DmSansFamily,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(40.dp))

            // Primary: OAuth login
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexusAccent, RoundedCornerShape(14.dp))
                    .clickable { onLoginWithClaude() }
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Login with claude.ai",
                    color = Color.White,
                    fontFamily = DmSansFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(14.dp))

            // Secondary: API key
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexusSurface2, RoundedCornerShape(14.dp))
                    .clickable { onUseApiKey() }
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Use API key instead",
                    color = Color(0xFF60A5FA),
                    fontFamily = DmSansFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(32.dp))
            Text(
                "← Back",
                color = Color(0xFF6B7280),
                fontFamily = DmSansFamily,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onBack() }
            )
        }
    }
}
