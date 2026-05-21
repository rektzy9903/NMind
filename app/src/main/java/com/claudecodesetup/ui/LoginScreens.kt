package com.claudecodesetup.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Provider
import com.claudecodesetup.data.Providers
import com.claudecodesetup.data.ProvidersRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// ── Simple question screens ───────────────────────────────────────────────────

@Composable
fun SubscriptionScreen(onYes: () -> Unit, onNo: () -> Unit) {
    QuestionCard(
        icon = "🧬",
        question = "Do you have a Claude subscription?",
        subtitle = "Use your Claude.ai account or API key for direct access",
        primaryLabel = "Yes — use my account",
        secondaryLabel = "No — use a free provider",
        onPrimary = onYes,
        onSecondary = onNo,
        accentColor = Color(0xFF8B5CF6)
    )
}

@Composable
private fun QuestionCard(
    icon: String,
    question: String,
    subtitle: String,
    primaryLabel: String,
    secondaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    accentColor: Color,
    extraContent: (@Composable () -> Unit)? = null
) {
    var entered by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(400), label = "alpha")
    val offset by animateFloatAsState(
        if (entered) 0f else 20f, tween(400, easing = FastOutSlowInEasing), label = "offset")
    LaunchedEffect(Unit) { entered = true }

    AppBackground {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .graphicsLayer { this.alpha = alpha; translationY = offset * density }
                    .glowShadow(accentColor.copy(alpha = 0.14f), 20.dp, 24.dp)
                    .background(Color(0x12FFFFFF), RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(24.dp))
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(accentColor.copy(alpha = 0.22f), accentColor.copy(alpha = 0.12f))
                            ),
                            RoundedCornerShape(18.dp)
                        )
                        .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(icon, fontSize = 26.sp)
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        question, fontFamily = DmSansFamily, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        subtitle, fontFamily = DmSansFamily, fontSize = 13.sp,
                        color = Color(0xFF9CA3AF), textAlign = TextAlign.Center
                    )
                }

                if (extraContent != null) extraContent()

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FlowButton(
                        label = primaryLabel,
                        gradient = Brush.linearGradient(
                            listOf(accentColor, accentColor.copy(
                                red = (accentColor.red * 0.85f).coerceIn(0f, 1f),
                                green = (accentColor.green * 0.85f).coerceIn(0f, 1f),
                                blue = (accentColor.blue * 0.85f).coerceIn(0f, 1f)
                            ))
                        ),
                        onClick = onPrimary
                    )
                    FlowOutlineButton(label = secondaryLabel, onClick = onSecondary)
                }
            }
        }
    }
}

// ── Provider list screen ──────────────────────────────────────────────────────

@Composable
fun ProviderListScreen(onSelect: (Provider) -> Unit, onBack: () -> Unit) {
    var entered by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(400), label = "alpha")
    LaunchedEffect(Unit) { entered = true }

    val context = LocalContext.current
    // Load providers from remote URL if configured; fall back to bundled list
    val providers by produceState(initialValue = Providers.ALL) {
        val result = runCatching { ProvidersRepository.load(context) }.getOrNull()
        if (result != null && result.providers.isNotEmpty()) value = result.providers
    }

    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "←", fontSize = 20.sp, color = Color(0xFF60A5FA),
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "PROVIDER", fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                        letterSpacing = 3.sp, color = Color(0xB360A5FA)
                    )
                    Text(
                        "Choose Provider", fontFamily = DmSansFamily, fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, color = Color.White
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(providers) { provider ->
                    ProviderCard(provider = provider, onSelect = { onSelect(provider) })
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun ProviderCard(provider: Provider, onSelect: () -> Unit) {
    val (emoji, accentColor, badge) = providerDisplayInfo(provider.id)

    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, tween(120), label = "scale")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(Color(0x0CFFFFFF), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .border(1.dp, accentColor.copy(alpha = 0.28f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (provider.iconUrl.isNotEmpty()) {
                SubcomposeAsyncImage(
                    model = provider.iconUrl,
                    contentDescription = provider.name,
                    modifier = Modifier.size(26.dp).clip(CircleShape),
                    error = { Text(emoji, fontSize = 20.sp) },
                    loading = { Text(emoji, fontSize = 20.sp) }
                )
            } else {
                Text(emoji, fontSize = 20.sp)
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    provider.name, fontFamily = DmSansFamily, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, color = Color.White
                )
                Box(
                    modifier = Modifier
                        .background(accentColor.copy(alpha = 0.13f), RoundedCornerShape(20.dp))
                        .border(1.dp, accentColor.copy(alpha = 0.28f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        badge, fontFamily = DmSansFamily, fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold, color = accentColor.copy(alpha = 0.9f)
                    )
                }
            }
            Text(provider.rateLimit, fontFamily = DmSansFamily, fontSize = 11.sp, color = Color(0xFF6B7280))
            if (provider.warningNote != null) {
                Text(
                    "⚠ ${provider.warningNote}", fontFamily = DmSansFamily,
                    fontSize = 10.sp, color = Color(0xFFF59E0B)
                )
            }
        }

        Text("›", fontSize = 20.sp, color = Color(0xFF374151))
    }
}

internal fun providerDisplayInfo(id: String): Triple<String, Color, String> = when (id) {
    "gemini"     -> Triple("✨", Color(0xFF10B981), "Best Free")
    "openrouter" -> Triple("🔀", Color(0xFF8B5CF6), "Aggregator")
    "deepseek"   -> Triple("🧠", Color(0xFF06B6D4), "Reasoning")
    "kimi"       -> Triple("🌙", Color(0xFFF59E0B), "Long CTX")
    "nvidia_nim" -> Triple("⚡", Color(0xFF76B900), "40 req/min")
    "meta_llama" -> Triple("🦙", Color(0xFF0467DF), "Open Source")
    "ollama"     -> Triple("💻", Color(0xFFEF4444), "Personal AI")
    "anthropic"  -> Triple("🧬", Color(0xFF8B5CF6), "Subscription")
    "groq"       -> Triple("⚡", Color(0xFFF97316), "14,400/day")
    else         -> Triple("🤖", Color(0xFF6440FF), "AI Provider")
}

// ── Shared button components ──────────────────────────────────────────────────
// ── Shared button components ──────────────────────────────────────────────────

@Composable
fun FlowButton(label: String, gradient: Brush, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, tween(150), label = "btn")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(gradient, RoundedCornerShape(13.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontFamily = DmSansFamily, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold, color = Color.White
        )
    }
}

@Composable
fun FlowOutlineButton(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, tween(150), label = "btn")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(Color(0x0FFFFFFF), RoundedCornerShape(13.dp))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(13.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontFamily = DmSansFamily, fontSize = 15.sp,
            fontWeight = FontWeight.Medium, color = Color(0xFF9CA3AF)
        )
    }
}
