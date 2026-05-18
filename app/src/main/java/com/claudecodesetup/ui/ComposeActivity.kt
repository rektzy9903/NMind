package com.claudecodesetup.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
                selectedProvider = provider
                screen = if (provider.id == "ollama") "local_models" else "key"
            },
            onBack = { screen = "subscription" }
        )
        "local_models" -> LocalModelsScreen(
            onModelSelected = { modelId ->
                // Model pulled in-app — auto-configure localhost and jump straight to chat
                prefs.setCustomBaseUrlForProvider("ollama", "http://localhost:11434")
                prefs.setBaseUrl("http://localhost:11434")
                storedKey = ""
                val model = AiModel(modelId, modelId, emptySet(), "")
                onComplete(Providers.OLLAMA, "", model)
            },
            onRemoteServer = { screen = "key" },
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
        "picker" -> ModelPickerScreen(
            provider = selectedProvider ?: Providers.GEMINI,
            apiKey = storedKey,
            onConfirm = { model ->
                onComplete(selectedProvider ?: Providers.GEMINI, storedKey, model)
            },
            onBack = { screen = if (startAt == "picker" || startAt == "providers") "providers" else "key" }
        )
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
                    .background(Color(0xFF7C3AED), RoundedCornerShape(14.dp))
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
                    .background(Color(0xFF1E1B4B), RoundedCornerShape(14.dp))
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
