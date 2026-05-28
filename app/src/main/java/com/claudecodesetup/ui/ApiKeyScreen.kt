package com.claudecodesetup.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.claudecodesetup.R
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class KeyStatus { IDLE, LOADING, SUCCESS, ERROR }

private val httpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
}

private suspend fun validateKey(provider: Provider, key: String, serverUrl: String = ""): String? {
    // For URL-configurable providers, test that the server is reachable (with optional Bearer auth)
    if (!provider.requiresApiKey && provider.isUrlConfigurable && serverUrl.isNotEmpty()) {
        return withContext(Dispatchers.IO) {
            try {
                val testUrl = serverUrl.trimEnd('/') + "/models"
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                val reqBuilder = Request.Builder().url(testUrl)
                if (key.isNotEmpty()) reqBuilder.header("Authorization", "Bearer $key")
                val resp = client.newCall(reqBuilder.build()).execute()
                val code = resp.code
                resp.body?.close()
                when {
                    code in 200..299 -> null
                    code == 401 || code == 403 -> "Invalid token — check your HuggingFace token"
                    code in 200..499 -> null
                    else -> "Server returned HTTP $code — check URL or token"
                }
            } catch (e: Exception) {
                "Cannot reach server — check the URL and ensure it is running"
            }
        }
    }
    if (!provider.requiresApiKey || key.isEmpty()) return null
    return withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(provider, key) ?: return@withContext null
            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            resp.body?.close()
            when {
                code in 200..299 -> null
                code == 429 -> null // rate-limited but key is valid
                code == 401 || code == 403 -> "Invalid API key (HTTP $code)"
                else -> "Validation failed (HTTP $code) — check key and try again"
            }
        } catch (e: Exception) {
            "Network error — check your connection"
        }
    }
}

private fun buildRequest(provider: Provider, key: String): Request? {
    val builder = Request.Builder()
    return when (provider.id) {
        "anthropic", "anthropic_api" -> builder
            .url("https://api.anthropic.com/v1/models")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .build()
        "gemini" -> builder
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$key")
            .build()
        "openrouter" -> builder
            .url("https://openrouter.ai/api/v1/models")
            .header("Authorization", "Bearer $key")
            .build()
        "nvidia_nim" -> builder
            .url("https://integrate.api.nvidia.com/v1/models")
            .header("Authorization", "Bearer $key")
            .build()
        "meta_llama" -> builder
            .url("https://api.llama.com/v1/models")
            .header("Authorization", "Bearer $key")
            .build()
        "deepseek" -> builder
            .url("https://api.deepseek.com/models")
            .header("Authorization", "Bearer $key")
            .build()
        "kimi" -> builder
            .url("https://api.moonshot.ai/v1/users/me")
            .header("Authorization", "Bearer $key")
            .build()
        "groq" -> builder
            .url("https://api.groq.com/openai/v1/models")
            .header("Authorization", "Bearer $key")
            .build()
        else -> null
    }
}

@Composable
fun ApiKeyScreen(provider: Provider, onSuccess: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(KeyStatus.IDLE) }
    var errorMessage by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val (accentColor, _) = providerDisplayInfo(provider.id)

    var entered by remember { mutableStateOf(false) }
    val entryAlpha by animateFloatAsState(if (entered) 1f else 0f,
        tween(500, easing = FastOutSlowInEasing), label = "alpha")
    val entryOffset by animateFloatAsState(if (entered) 0f else 18f,
        tween(500, easing = FastOutSlowInEasing), label = "offset")
    LaunchedEffect(Unit) { entered = true }

    val borderColor by animateColorAsState(
        when (status) {
            KeyStatus.IDLE    -> NexusBorder
            KeyStatus.LOADING -> Color(0x99E8834A)
            KeyStatus.SUCCESS -> NexusGreen
            KeyStatus.ERROR   -> Color(0xFFEF4444)
        }, tween(250), label = "border"
    )
    val glowColor by animateColorAsState(
        when (status) {
            KeyStatus.IDLE    -> Color.Transparent
            KeyStatus.LOADING -> NexusAccentDim
            KeyStatus.SUCCESS -> NexusGreenDim
            KeyStatus.ERROR   -> Color(0x1FEF4444)
        }, tween(250), label = "glow"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse_scale"
    )
    val successScale by animateFloatAsState(
        targetValue = if (status == KeyStatus.SUCCESS) 1f else 0.5f,
        animationSpec = if (status == KeyStatus.SUCCESS) keyframes {
            durationMillis = 300
            0.5f at 0
            1.15f at 150 with LinearEasing
            1.0f at 300
        } else snap(), label = "success_scale"
    )
    val iconScale = when (status) {
        KeyStatus.LOADING -> pulseScale
        KeyStatus.SUCCESS -> successScale
        else -> 1f
    }
    val iconRes = when (status) {
        KeyStatus.SUCCESS -> R.drawable.ic_status_success
        KeyStatus.ERROR   -> R.drawable.ic_status_error
        else              -> R.drawable.ic_status_key
    }

    val buttonInteraction = remember { MutableInteractionSource() }
    val isPressed by buttonInteraction.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(if (isPressed) 0.97f else 1f, tween(150), label = "btn_scale")
    val buttonGradient = if (status == KeyStatus.SUCCESS)
        Brush.linearGradient(listOf(NexusGreen, Color(0xFF2DA870)))
    else
        Brush.linearGradient(listOf(NexusAccent, Color(0xFFC4632A)))
    val buttonGlowColor = if (status == KeyStatus.SUCCESS) Color(0x663DD68C) else NexusAccent.copy(alpha = 0.35f)
    val isDisabled = status == KeyStatus.LOADING || status == KeyStatus.SUCCESS

    fun validate() {
        if (apiKey.isBlank() && provider.requiresApiKey) {
            status = KeyStatus.ERROR
            errorMessage = "Please enter your API key"
            return
        }
        status = KeyStatus.LOADING
        scope.launch {
            val effectiveKey = apiKey.trim()
            val error = validateKey(provider, effectiveKey)
            if (error == null) {
                status = KeyStatus.SUCCESS
                delay(700)
                onSuccess(effectiveKey)
            } else {
                status = KeyStatus.ERROR
                errorMessage = error
            }
        }
    }

    AppBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // Back button row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    "←", fontSize = 20.sp, color = NexusBlue,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .graphicsLayer {
                            alpha = entryAlpha
                            translationY = entryOffset * density
                        }
                        .glowShadow(NexusAccent.copy(alpha = 0.10f), 20.dp, 24.dp)
                        .background(NexusSurface2, RoundedCornerShape(24.dp))
                        .border(1.dp, NexusBorder, RoundedCornerShape(24.dp))
                        .padding(32.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {

                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(62.dp)
                                    .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                                    .background(
                                        Brush.linearGradient(
                                            listOf(accentColor.copy(alpha = 0.22f), accentColor.copy(alpha = 0.12f))
                                        ),
                                        RoundedCornerShape(18.dp)
                                    )
                                    .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = Color.Unspecified
                                )
                            }
                            Text(
                                provider.name.uppercase(),
                                fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                                letterSpacing = 3.sp, color = accentColor
                            )
                            Text(
                                if (provider.requiresApiKey) "Enter your API Key"
                                else "No API Key Required",
                                fontFamily = DmSansFamily, fontSize = 20.sp,
                                fontWeight = FontWeight.Bold, color = Color.White
                            )
                            Text(
                                if (provider.requiresApiKey) "Required to access ${provider.name} models"
                                else "Tap Continue to connect to ${provider.name}",
                                fontFamily = DmSansFamily, fontSize = 12.sp,
                                color = NexusText3, textAlign = TextAlign.Center
                            )
                        }

                        if (provider.requiresApiKey) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .glowShadow(glowColor, 8.dp, 12.dp)
                                        .background(NexusSurface, RoundedCornerShape(12.dp))
                                        .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                                        .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicTextField(
                                        value = apiKey,
                                        onValueChange = {
                                            apiKey = it
                                            if (status == KeyStatus.ERROR) status = KeyStatus.IDLE
                                        },
                                        modifier = Modifier.weight(1f),
                                        textStyle = TextStyle(
                                            fontFamily = SpaceMonoFamily,
                                            fontSize = 13.sp,
                                            color = Color.White,
                                            letterSpacing = if (!passwordVisible && apiKey.isNotEmpty()) 3.sp else 0.sp
                                        ),
                                        visualTransformation = if (passwordVisible)
                                            VisualTransformation.None else PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                                        singleLine = true,
                                        decorationBox = { inner ->
                                            Box(contentAlignment = Alignment.CenterStart) {
                                                if (apiKey.isEmpty()) {
                                                    Text(
                                                        "Enter API key…", fontFamily = SpaceMonoFamily,
                                                        fontSize = 13.sp, color = Color(0x55FFFFFF)
                                                    )
                                                }
                                                inner()
                                            }
                                        }
                                    )
                                    IconButton(
                                        onClick = { passwordVisible = !passwordVisible },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                if (passwordVisible) R.drawable.ic_eye
                                                else R.drawable.ic_eye_off
                                            ),
                                            contentDescription = if (passwordVisible) "Hide" else "Show",
                                            modifier = Modifier.size(20.dp),
                                            tint = Color(0xFF9090A0)
                                        )
                                    }
                                }
                                AnimatedVisibility(
                                    visible = status == KeyStatus.ERROR,
                                    enter = fadeIn(tween(200)),
                                    exit = fadeOut(tween(200))
                                ) {
                                    Text(
                                        errorMessage, fontFamily = DmSansFamily,
                                        fontSize = 11.sp, color = Color(0xFFEF4444)
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .graphicsLayer { scaleX = buttonScale; scaleY = buttonScale }
                                .glowShadow(buttonGlowColor, 12.dp, 13.dp)
                                .background(buttonGradient, RoundedCornerShape(13.dp))
                                .clickable(
                                    interactionSource = buttonInteraction,
                                    indication = null,
                                    enabled = !isDisabled
                                ) { validate() },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (status == KeyStatus.LOADING) {
                                    CircularProgressIndicator(
                                        Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                                }
                                Text(
                                    text = when (status) {
                                        KeyStatus.IDLE, KeyStatus.ERROR -> "Continue →"
                                        KeyStatus.LOADING               -> "Validating…"
                                        KeyStatus.SUCCESS               -> "✓  Validated!"
                                    },
                                    fontFamily = DmSansFamily, fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold, color = Color.White
                                )
                            }
                        }

                        if (provider.requiresApiKey) {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(color = NexusText2, fontSize = 11.sp,
                                        fontFamily = DmSansFamily)) {
                                        append("Don't have a key? ")
                                    }
                                    withStyle(SpanStyle(color = NexusBlue, fontSize = 11.sp,
                                        fontFamily = DmSansFamily)) {
                                        append("Get one free at ${provider.signupUrl} →")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
