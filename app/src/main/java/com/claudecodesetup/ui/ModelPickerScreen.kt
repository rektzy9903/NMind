package com.claudecodesetup.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.Provider
import com.claudecodesetup.data.ProvidersRepository
import kotlinx.coroutines.launch
import kotlin.math.ceil

// ── Model display helpers ─────────────────────────────────────────────────────

private data class ModelDisplay(
    val model: AiModel,
    val emoji: String,
    val color: Color,
    val speed: Int,
    val badge: String,
    val tokens: String,
    val category: String
)

private fun toDisplay(model: AiModel): ModelDisplay {
    val id = model.modelId.lowercase()
    val emoji = when {
        "flash" in id || "fast" in id -> "⚡"
        "reason" in id || "r1" in id || "think" in id -> "🧠"
        "vision" in id || "vl" in id -> "👁"
        "coder" in id || "code" in id -> "💻"
        "llama" in id -> "🦙"
        "gemini" in id -> "✨"
        "gpt" in id -> "🧬"
        "kimi" in id || "moonshot" in id -> "🌙"
        "deepseek" in id -> "🔍"
        "qwen" in id -> "🌟"
        "mistral" in id || "mixtral" in id -> "🌀"
        "claude" in id -> "🎭"
        "nemotron" in id || "nvidia" in id -> "⚡"
        "minimax" in id -> "🔮"
        "baidu" in id || "cobuddy" in id || "qianfan" in id -> "🔵"
        "poolside" in id || "laguna" in id -> "🏊"
        "liquid" in id || "lfm" in id -> "💧"
        else -> "🤖"
    }
    val color = when {
        "gemini" in id -> Color(0xFF10B981)
        "claude" in id -> Color(0xFF8B5CF6)
        "gpt" in id || "openai" in id -> Color(0xFF3B82F6)
        "llama" in id || "meta" in id -> Color(0xFFF97316)
        "deepseek" in id -> Color(0xFF06B6D4)
        "kimi" in id || "moonshot" in id -> Color(0xFFF59E0B)
        "nemotron" in id || "nvidia" in id -> Color(0xFF76B900)
        "qwen" in id -> Color(0xFFF59E0B)
        "mistral" in id || "mixtral" in id -> Color(0xFF22D3EE)
        "minimax" in id -> Color(0xFFA78BFA)
        else -> Color(0xFF60A5FA)
    }
    val speed = when {
        "flash" in id || "fast" in id -> 92
        "1.2b" in id || "tiny" in id -> 95
        "nano" in id || "8b" in id || "mini" in id -> 87
        "235b" in id || "120b" in id -> 58
        "70b" in id -> 65
        "reason" in id || "r1" in id -> 52
        else -> 75
    }
    val badge = when {
        ":free" in id -> "Free"
        "kimi-k2" in id || "kimi-k2.5" in id -> "Free"
        "flash" in id || "fast" in id -> "Fast"
        "reason" in id || "r1" in id -> "Smart"
        "preview" in id -> "Preview"
        else -> "Pro"
    }
    val tokens = when {
        "gemini" in id -> "1M"
        "claude" in id -> "200K"
        "kimi-k2" in id -> "1M"
        "deepseek" in id && "r1" in id -> "64K"
        else -> "128K"
    }
    val category = when {
        "flash" in id || "fast" in id -> "Fast"
        "reason" in id || "r1" in id || "think" in id -> "Reasoning"
        "vision" in id || "vl" in id -> "Vision"
        "coder" in id || "code" in id -> "Coding"
        "nano" in id || "mini" in id || "8b" in id -> "Compact"
        else -> "General"
    }
    return ModelDisplay(model, emoji, color, speed, badge, tokens, category)
}

private const val PAGE_SIZE = 9

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun ModelPickerScreen(
    provider: Provider,
    apiKey: String,
    onConfirm: (AiModel) -> Unit,
    onBack: () -> Unit
) {
    val isOpenRouter = provider.id == "openrouter"
    var liveModels by remember { mutableStateOf<List<AiModel>?>(null) }
    var isRefreshing by remember { mutableStateOf(isOpenRouter) }
    var fetchError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun fetchLive() {
        scope.launch {
            isRefreshing = true
            fetchError = false
            try {
                val fetched = ProvidersRepository.fetchOpenRouterFreeModels(apiKey)
                liveModels = fetched
            } catch (_: Exception) {
                fetchError = true
                if (liveModels == null) liveModels = emptyList()
            }
            isRefreshing = false
        }
    }

    if (isOpenRouter) LaunchedEffect(Unit) { fetchLive() }

    // OpenRouter: live-only (no hardcoded fallback). Other providers: static list.
    val modelList = if (isOpenRouter) (liveModels ?: emptyList()) else provider.models
    val displays = remember(modelList) { modelList.map { toDisplay(it) } }
    val categories = remember(displays) {
        val cats = displays.map { it.category }.distinct()
        if (cats.size > 1) listOf("All") + cats else emptyList()
    }

    var filter by remember { mutableStateOf("All") }
    var page by remember { mutableStateOf(0) }
    var selectedModel by remember { mutableStateOf<AiModel?>(displays.firstOrNull()?.model) }

    val filtered = remember(filter, displays) {
        if (filter == "All") displays else displays.filter { it.category == filter }
    }
    LaunchedEffect(filter) { page = 0 }

    val totalPages = remember(filtered) { maxOf(1, ceil(filtered.size / PAGE_SIZE.toFloat()).toInt()) }
    val paged = filtered.drop(page * PAGE_SIZE).take(PAGE_SIZE)
    val selectedDisplay = displays.find { it.model == selectedModel }

    var entered by remember { mutableStateOf(false) }
    val entryAlpha by animateFloatAsState(if (entered) 1f else 0f, tween(500), label = "alpha")
    LaunchedEffect(Unit) { entered = true }

    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = entryAlpha }
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        "←", fontSize = 20.sp, color = Color(0xFF60A5FA),
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            provider.name.uppercase(),
                            fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                            letterSpacing = 3.sp, color = Color(0xB360A5FA)
                        )
                        Text(
                            "Choose Your Model", fontFamily = DmSansFamily, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold, color = Color.White
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isOpenRouter) {
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isRefreshing) Color(0x0FFFFFFF) else Color(0x1F8B5CF6),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(1.dp,
                                    if (isRefreshing) Color(0x14FFFFFF) else Color(0x508B5CF6),
                                    RoundedCornerShape(8.dp))
                                .clickable(enabled = !isRefreshing) { fetchLive() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    Modifier.size(12.dp), color = Color(0xFF8B5CF6), strokeWidth = 1.5.dp)
                            } else {
                                Text("↻ Refresh", fontFamily = DmSansFamily, fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold, color = Color(0xFF8B5CF6))
                            }
                        }
                    }
                    if (apiKey.isNotEmpty()) {
                        val shortKey = if (apiKey.length > 8) apiKey.take(8) + "…" else apiKey
                        Row(
                            modifier = Modifier
                                .background(Color(0x1F10B981), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x5010B981), RoundedCornerShape(8.dp))
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(Modifier.size(6.dp).background(Color(0xFF10B981), CircleShape))
                            Text(shortKey, fontFamily = SpaceMonoFamily, fontSize = 9.sp, color = Color(0xFF10B981))
                        }
                    }
                    AnimatedVisibility(
                        visible = selectedDisplay != null,
                        enter = fadeIn() + slideInVertically { -it },
                        exit = fadeOut() + slideOutVertically { -it }
                    ) {
                        selectedDisplay?.let { d ->
                            Row(
                                modifier = Modifier
                                    .background(d.color.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                                    .border(1.dp, d.color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(d.emoji, fontSize = 10.sp)
                                Text(
                                    "Selected", fontFamily = DmSansFamily,
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold, color = d.color
                                )
                            }
                        }
                    }
                }
            }

            // ── Filter chips (only when categories are meaningful) ────────────
            if (categories.size > 2) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    items(categories) { cat ->
                        val isActive = cat == filter
                        val chipBg by animateColorAsState(
                            if (isActive) Color(0x2E60A5FA) else Color(0x0DFFFFFF), tween(150), label = "chip_bg")
                        val chipBorder by animateColorAsState(
                            if (isActive) Color(0x7260A5FA) else Color(0x17FFFFFF), tween(150), label = "chip_border")
                        val chipText by animateColorAsState(
                            if (isActive) Color(0xFF93C5FD) else Color(0xFF6B7280), tween(150), label = "chip_text")
                        Box(
                            modifier = Modifier
                                .background(chipBg, RoundedCornerShape(20.dp))
                                .border(1.dp, chipBorder, RoundedCornerShape(20.dp))
                                .clickable { filter = cat }
                                .padding(horizontal = 11.dp, vertical = 4.dp)
                        ) {
                            Text(
                                cat, fontFamily = DmSansFamily, fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold, color = chipText
                            )
                        }
                    }
                }
            }

            // ── Empty / error state (OpenRouter only, while loading or on error) ──
            if (isOpenRouter && !isRefreshing && modelList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            if (fetchError) "⚠ Could not load models" else "No free models found",
                            fontFamily = DmSansFamily, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, color = Color.White
                        )
                        Text(
                            if (fetchError) "Check your API key and internet connection, then tap Refresh."
                            else "Tap ↻ Refresh to try again.",
                            fontFamily = DmSansFamily, fontSize = 12.sp,
                            color = Color(0xFF6B7280)
                        )
                        Box(
                            modifier = Modifier
                                .background(Color(0x1F8B5CF6), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x508B5CF6), RoundedCornerShape(8.dp))
                                .clickable { fetchLive() }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("↻ Retry", fontFamily = DmSansFamily, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, color = Color(0xFF8B5CF6))
                        }
                    }
                }
            } else if (isOpenRouter && isRefreshing && liveModels == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(32.dp), color = Color(0xFF8B5CF6), strokeWidth = 2.dp)
                        Text("Loading free models…", fontFamily = DmSansFamily, fontSize = 13.sp,
                            color = Color(0xFF6B7280))
                    }
                }
            } else

            // ── Model grid ────────────────────────────────────────────────────
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp)
            ) {
                items(paged, key = { it.model.modelId }) { display ->
                    ModelCard(
                        display = display,
                        isSelected = selectedModel == display.model,
                        onSelect = { selectedModel = display.model }
                    )
                }
                val emptyCount = PAGE_SIZE - paged.size
                items(emptyCount) {
                    Box(
                        modifier = Modifier
                            .aspectRatio(0.85f)
                            .border(1.dp, Color(0x0AFFFFFF), RoundedCornerShape(12.dp))
                            .background(Color(0x03FFFFFF), RoundedCornerShape(12.dp))
                    )
                }
            }

            // ── Pagination (only when needed) ─────────────────────────────────
            if (totalPages > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PaginationButton("‹", page > 0) { page-- }
                    repeat(totalPages) { i ->
                        val isActive = i == page
                        val pageBg by animateColorAsState(
                            if (isActive) Color(0x3860A5FA) else Color(0x0DFFFFFF), tween(150), label = "page_bg")
                        val pageBorder by animateColorAsState(
                            if (isActive) Color(0x8060A5FA) else Color.Transparent, tween(150), label = "page_border")
                        val pageText by animateColorAsState(
                            if (isActive) Color(0xFF93C5FD) else Color(0xFF374151), tween(150), label = "page_text")
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(pageBg, RoundedCornerShape(8.dp))
                                .border(1.dp, pageBorder, RoundedCornerShape(8.dp))
                                .clickable { page = i },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${i + 1}", fontFamily = DmSansFamily, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, color = pageText
                            )
                        }
                    }
                    PaginationButton("›", page < totalPages - 1) { page++ }
                }
            }

            // ── Confirm button ────────────────────────────────────────────────
            val canConfirm = selectedModel != null
            val confirmInteraction = remember { MutableInteractionSource() }
            val isConfirmPressed by confirmInteraction.collectIsPressedAsState()
            val confirmScale by animateFloatAsState(
                if (isConfirmPressed) 0.97f else 1f, tween(150), label = "confirm_btn")

            Box(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .padding(top = 8.dp, bottom = 14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .graphicsLayer {
                            scaleX = confirmScale; scaleY = confirmScale
                            alpha = if (canConfirm) 1f else 0.4f
                        }
                        .glowShadow(Color(0x943B82F6), 12.dp, 13.dp)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF6366F1))),
                            RoundedCornerShape(13.dp)
                        )
                        .clickable(
                            interactionSource = confirmInteraction,
                            indication = null,
                            enabled = canConfirm
                        ) { selectedModel?.let { onConfirm(it) } },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Start Claude Code →", fontFamily = DmSansFamily, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCard(display: ModelDisplay, isSelected: Boolean, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val cardScale by animateFloatAsState(if (isPressed) 0.95f else 1f, tween(150), label = "card_scale")

    val cardBg by animateColorAsState(
        if (isSelected) display.color.copy(alpha = 0.22f) else Color(0x0FFFFFFF),
        tween(200), label = "card_bg"
    )
    val cardBorder by animateColorAsState(
        if (isSelected) display.color.copy(alpha = 0.66f) else Color(0x14FFFFFF),
        tween(200), label = "card_border"
    )

    Box(
        modifier = Modifier
            .aspectRatio(0.85f)
            .graphicsLayer { scaleX = cardScale; scaleY = cardScale }
            .run {
                if (isSelected) glowShadow(display.color.copy(alpha = 0.30f), 14.dp, 12.dp) else this
            }
            .background(cardBg, RoundedCornerShape(12.dp))
            .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null) { onSelect() }
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(display.color.copy(alpha = 0.12f), RoundedCornerShape(9.dp))
                    .border(1.dp, display.color.copy(alpha = 0.35f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(display.emoji, fontSize = 15.sp)
            }

            Column(modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    display.model.name, fontFamily = DmSansFamily, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp
                )
                Text(
                    display.category, fontFamily = DmSansFamily, fontSize = 10.sp,
                    color = Color(0xFF374151), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x0FFFFFFF))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(display.speed / 100f)
                        .fillMaxHeight()
                        .background(
                            Brush.linearGradient(
                                listOf(display.color.copy(alpha = 0.55f), display.color)
                            )
                        )
                )
            }

            Column {
                Divider(
                    color = Color(0x0DFFFFFF), thickness = 1.dp,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(display.color.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                            .border(1.dp, display.color.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            display.badge, fontFamily = DmSansFamily, fontSize = 7.sp,
                            fontWeight = FontWeight.Bold, color = display.color
                        )
                    }
                    Text(display.tokens, fontFamily = SpaceMonoFamily, fontSize = 7.sp, color = Color(0xFF374151))
                }
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(14.dp)
                    .background(display.color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

@Composable
private fun PaginationButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0x0FFFFFFF), RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.2f },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontFamily = DmSansFamily, fontSize = 14.sp, color = Color(0xFF6B7280))
    }
}
