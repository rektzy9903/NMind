package com.claudecodesetup.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.Cap
import com.claudecodesetup.data.Provider
import com.claudecodesetup.data.Providers
import com.claudecodesetup.data.ProvidersRepository
import kotlinx.coroutines.launch
import kotlin.math.ceil

// ── Model display helpers ─────────────────────────────────────────────────────

// Capability filter chips shown at top of picker
val CAP_FILTERS = listOf(
    "All"       to null,
    "Tools"     to Cap.TOOLS,
    "Vision"    to Cap.VISION,
    "Reasoning" to Cap.REASONING,
    "Fast"      to Cap.FAST,
    "Free"      to Cap.FREE,
    "Coding"    to Cap.CODING,
    "Long Ctx"  to Cap.LONG_CTX
)

// Ordered list of caps shown as pills on each card
val CAP_PILL_ORDER = listOf(
    Cap.TOOLS     to "tools",
    Cap.VISION    to "vision",
    Cap.REASONING to "reason",
    Cap.FAST      to "fast",
    Cap.LONG_CTX  to "ctx",
    Cap.CODING    to "code",
    Cap.FREE      to "free"
)

// Color per cap type (matching HTML design: tools=blue, vision=purple, reason=amber, fast=green, ctx=red, code=accent)
private fun capColor(cap: String): Color = when (cap) {
    "tools"  -> Color(0xFF60A5FA)
    "vision" -> Color(0xFFA78BFA)
    "reason" -> Color(0xFFF59E0B)
    "fast"   -> Color(0xFF3DD68C)
    "ctx"    -> Color(0xFFF87171)
    "code"   -> Color(0xFFE8834A)
    else     -> Color(0xFF9090A0)
}

private data class ModelDisplay(
    val model: AiModel,
    val effectiveCaps: Set<String>,
    val emoji: String,
    val color: Color,
    val speed: Int,
    val badge: String,
    val tokens: String,
    val description: String
)

private fun toDisplay(model: AiModel): ModelDisplay {
    val id = model.modelId.lowercase()
    val caps = model.caps.ifEmpty { Providers.deriveCaps(model.modelId) }
    val emoji = when {
        Cap.REASONING in caps && Cap.FAST !in caps -> "🧠"
        Cap.CODING    in caps                      -> "💻"
        Cap.VISION    in caps && Cap.TOOLS in caps -> "👁"
        Cap.FAST      in caps                      -> "⚡"
        "llama"   in id -> "🦙"
        "gemini"  in id -> "✨"
        "gpt"     in id -> "🧬"
        "kimi"    in id || "moonshot" in id -> "🌙"
        "deepseek" in id -> "🔍"
        "qwen"    in id -> "🌟"
        "mistral" in id || "mixtral" in id -> "🌀"
        "claude"  in id -> "🎭"
        "nemotron" in id || "nvidia" in id -> "⚡"
        "minimax" in id -> "🔮"
        "baidu"   in id || "cobuddy" in id -> "🔵"
        "poolside" in id || "laguna"  in id -> "🏊"
        "liquid"  in id || "lfm"     in id -> "💧"
        else -> "🤖"
    }
    val color = when {
        "gemini"  in id -> NexusGreen
        "claude"  in id -> NexusAccent
        "gpt"     in id || "openai"   in id -> NexusAccent
        "llama"   in id || "meta"     in id -> Color(0xFFF97316)
        "deepseek" in id -> Color(0xFF06B6D4)
        "kimi"    in id || "moonshot" in id -> Color(0xFFF59E0B)
        "nemotron" in id || "nvidia"   in id -> Color(0xFF76B900)
        "qwen"    in id -> Color(0xFFF59E0B)
        "mistral" in id || "mixtral"  in id -> Color(0xFF22D3EE)
        "minimax" in id -> Color(0xFFA78BFA)
        else -> NexusBlue
    }
    val speed = when {
        Cap.FAST in caps && "1.2b" in id -> 97
        Cap.FAST in caps && ("8b" in id || "nano" in id || "mini" in id) -> 90
        Cap.FAST in caps -> 85
        Cap.REASONING in caps -> 50
        "120b" in id || "235b" in id -> 55
        "70b" in id -> 65
        else -> 75
    }
    val badge = when {
        Cap.FREE      in caps && Cap.REASONING in caps -> "Free · Smart"
        Cap.FREE      in caps -> "Free"
        Cap.REASONING in caps -> "Reasoning"
        Cap.FAST      in caps -> "Fast"
        "preview"     in id   -> "Preview"
        else -> "Pro"
    }
    val tokens = when {
        Cap.LONG_CTX in caps && ("gemini" in id || "kimi" in id) -> "1M"
        Cap.LONG_CTX in caps -> "200K+"
        "deepseek" in id && "r1" in id -> "64K"
        else -> "128K"
    }
    val description = model.description.ifEmpty { Providers.deriveDescription(model.modelId, caps) }
    return ModelDisplay(model, caps, emoji, color, speed, badge, tokens, description)
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
    val isLive = provider.supportsLiveFetch
    var liveModels by remember { mutableStateOf<List<AiModel>?>(null) }
    var isRefreshing by remember { mutableStateOf(isLive) }
    var fetchError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun fetchLive() {
        scope.launch {
            isRefreshing = true
            fetchError = false
            try {
                val fetched = ProvidersRepository.fetchModels(provider, apiKey)
                liveModels = fetched
            } catch (_: Exception) {
                fetchError = true
            }
            isRefreshing = false
        }
    }

    if (isLive) LaunchedEffect(Unit) { fetchLive() }

    val modelList = if (isLive) (liveModels ?: emptyList()) else provider.models
    val displays = remember(modelList) { modelList.map { toDisplay(it) } }

    // Only show cap filters that actually have matching models
    val activeCapFilters = remember(displays) {
        CAP_FILTERS.filter { (_, cap) -> cap == null || displays.any { cap in it.effectiveCaps } }
    }

    var selectedCap by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    var selectedModel by remember { mutableStateOf<AiModel?>(displays.firstOrNull()?.model) }

    LaunchedEffect(displays) {
        if (selectedModel != null && displays.none { it.model == selectedModel }) {
            selectedModel = displays.firstOrNull()?.model
        }
    }

    val filtered = remember(selectedCap, searchQuery, displays) {
        var list = if (selectedCap == null) displays else displays.filter { selectedCap in it.effectiveCaps }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            list = list.filter { it.model.name.lowercase().contains(q) || it.model.modelId.lowercase().contains(q) }
        }
        list
    }
    LaunchedEffect(selectedCap, searchQuery) { page = 0 }

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
                    .background(NexusBg)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        "←", fontSize = 20.sp, color = NexusText2,
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            provider.name.uppercase(),
                            fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                            letterSpacing = 3.sp, color = NexusText3
                        )
                        Text(
                            "Pick a Model", fontFamily = DmSansFamily, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold, color = NexusText
                        )
                    }
                }
                // Refresh button (live providers only)
                if (isLive) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (isRefreshing) NexusSurface else NexusSurface,
                                RoundedCornerShape(8.dp)
                            )
                            .border(1.dp, NexusBorder, RoundedCornerShape(8.dp))
                            .clickable(enabled = !isRefreshing) { fetchLive() }
                            .size(30.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                Modifier.size(12.dp), color = NexusAccent, strokeWidth = 1.5.dp)
                        } else {
                            Text("↻", fontFamily = DmSansFamily, fontSize = 14.sp, color = NexusText2)
                        }
                    }
                }
            }

            // ── Search bar ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexusBg)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .background(NexusSurface, RoundedCornerShape(9.dp))
                    .border(1.dp, NexusBorder, RoundedCornerShape(9.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Search icon
                Text("⌕", fontSize = 13.sp, color = NexusText3)
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = NexusText, fontSize = 13.sp, fontFamily = DmSansFamily
                    ),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                "Search models…",
                                color = NexusText3,
                                fontSize = 13.sp,
                                fontFamily = DmSansFamily
                            )
                        }
                        inner()
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    Text(
                        "✕", fontSize = 11.sp, color = NexusText3,
                        modifier = Modifier.clickable { searchQuery = "" }
                    )
                }
            }

            // ── Capability filter chips ───────────────────────────────────────
            if (activeCapFilters.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .background(NexusBg)
                        .padding(bottom = 0.dp, top = 0.dp)
                        .padding(vertical = 8.dp)
                ) {
                    items(activeCapFilters) { (label, cap) ->
                        val isActive = selectedCap == cap
                        val chipBg by animateColorAsState(
                            if (isActive) NexusAccentDim else NexusSurface, tween(150), label = "chip_bg")
                        val chipBorder by animateColorAsState(
                            if (isActive) NexusAccent else NexusBorder, tween(150), label = "chip_border")
                        val chipText by animateColorAsState(
                            if (isActive) NexusAccent else NexusText2, tween(150), label = "chip_text")
                        Box(
                            modifier = Modifier
                                .background(chipBg, RoundedCornerShape(20.dp))
                                .border(1.dp, chipBorder, RoundedCornerShape(20.dp))
                                .clickable { selectedCap = cap }
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                label, fontFamily = DmSansFamily, fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold, color = chipText
                            )
                        }
                    }
                }
            }

            // ── Live status bar ───────────────────────────────────────────────
            if (isLive && liveModels != null) {
                val freeCount = displays.count { Cap.FREE in it.effectiveCaps }
                val paidCount = displays.size - freeCount
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NexusBg)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pulsing green dot
                    val pulse = rememberInfiniteTransition(label = "live_pulse")
                    val dotAlpha by pulse.animateFloat(
                        initialValue = 1f, targetValue = 0.3f,
                        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
                        label = "dot_alpha"
                    )
                    Box(
                        Modifier
                            .size(6.dp)
                            .graphicsLayer { alpha = dotAlpha }
                            .background(NexusGreen, CircleShape)
                    )
                    Text(
                        "Live · fetched just now",
                        fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusText3
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (paidCount > 0) "$freeCount free · $paidCount paid" else "$freeCount models",
                        fontFamily = SpaceMonoFamily, fontSize = 11.sp, color = NexusText2
                    )
                }
            } else if (!isLive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NexusBg)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Static list · ${displays.size} models",
                        fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusText3
                    )
                    Spacer(Modifier.weight(1f))
                    val allFree = displays.isNotEmpty() && displays.all { Cap.FREE in it.effectiveCaps }
                    if (allFree) {
                        Text("Free tier", fontFamily = SpaceMonoFamily, fontSize = 11.sp, color = NexusGreen)
                    }
                }
            }

            // ── Empty / error state (live providers only, while loading or on error) ──
            if (isLive && !isRefreshing && modelList.isEmpty()) {
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
                            if (fetchError) "⚠ Could not load models" else "No models found",
                            fontFamily = DmSansFamily, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, color = NexusText
                        )
                        Text(
                            if (fetchError) "Check your API key and internet connection, then tap Refresh."
                            else "Tap ↻ Refresh to try again.",
                            fontFamily = DmSansFamily, fontSize = 12.sp,
                            color = NexusText3
                        )
                        Box(
                            modifier = Modifier
                                .background(NexusAccentDim, RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x50E8834A), RoundedCornerShape(8.dp))
                                .clickable { fetchLive() }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("↻ Retry", fontFamily = DmSansFamily, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, color = NexusAccent)
                        }
                    }
                }
            } else if (isLive && isRefreshing && liveModels == null) {
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
                            Modifier.size(32.dp), color = NexusAccent, strokeWidth = 2.dp)
                        Text("Loading models…", fontFamily = DmSansFamily, fontSize = 13.sp,
                            color = NexusText3)
                    }
                }
            } else

            // ── Model list ────────────────────────────────────────────────────
            {
                val pagedFree = paged.filter { Cap.FREE in it.effectiveCaps }
                val pagedPaid = paged.filter { Cap.FREE !in it.effectiveCaps }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (pagedFree.isNotEmpty()) {
                        item { ModelSectionHeader("Free", NexusGreen, pagedFree.size) }
                        item {
                            ModelRowList(
                                models = pagedFree,
                                selectedModel = selectedModel,
                                onSelect = { selectedModel = it }
                            )
                        }
                    }
                    if (pagedFree.isNotEmpty() && pagedPaid.isNotEmpty()) {
                        item { Spacer(Modifier.height(4.dp)) }
                    }
                    if (pagedPaid.isNotEmpty()) {
                        item { ModelSectionHeader("Paid", NexusAmber, pagedPaid.size) }
                        item {
                            ModelRowList(
                                models = pagedPaid,
                                selectedModel = selectedModel,
                                onSelect = { selectedModel = it }
                            )
                        }
                    }
                }
            }

            // ── Pagination ────────────────────────────────────────────────────
            if (totalPages > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NexusBg)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ‹ Prev
                    val prevEnabled = page > 0
                    Box(
                        modifier = Modifier
                            .background(NexusSurface, RoundedCornerShape(7.dp))
                            .border(1.dp, if (prevEnabled) NexusBorder else Color.Transparent, RoundedCornerShape(7.dp))
                            .then(if (prevEnabled) Modifier.clickable { page-- } else Modifier)
                            .padding(horizontal = 13.dp, vertical = 5.dp)
                            .graphicsLayer { alpha = if (prevEnabled) 1f else 0.3f },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("‹ Prev", fontFamily = DmSansFamily, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, color = NexusText2)
                    }
                    // Page X of Y
                    Box(
                        modifier = Modifier
                            .background(NexusSurface, RoundedCornerShape(7.dp))
                            .border(1.dp, NexusBorder, RoundedCornerShape(7.dp))
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Page ", fontFamily = SpaceMonoFamily, fontSize = 11.sp, color = NexusText2)
                            Text("${page + 1}", fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, color = NexusAccent)
                            Text(" of $totalPages", fontFamily = SpaceMonoFamily, fontSize = 11.sp, color = NexusText2)
                        }
                    }
                    // Next ›
                    val nextEnabled = page < totalPages - 1
                    Box(
                        modifier = Modifier
                            .background(NexusSurface, RoundedCornerShape(7.dp))
                            .border(1.dp, if (nextEnabled) NexusBorder else Color.Transparent, RoundedCornerShape(7.dp))
                            .then(if (nextEnabled) Modifier.clickable { page++ } else Modifier)
                            .padding(horizontal = 13.dp, vertical = 5.dp)
                            .graphicsLayer { alpha = if (nextEnabled) 1f else 0.3f },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Next ›", fontFamily = DmSansFamily, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, color = NexusText2)
                    }
                }
            }

            // ── Confirm button ────────────────────────────────────────────────
            val canConfirm = selectedModel != null
            val confirmInteraction = remember { MutableInteractionSource() }
            val isConfirmPressed by confirmInteraction.collectIsPressedAsState()
            val confirmScale by animateFloatAsState(
                if (isConfirmPressed) 0.97f else 1f, tween(150), label = "confirm_btn")

            val confirmLabel = selectedModel?.name?.let { "Use $it" } ?: "Select a model"

            Box(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .padding(top = 10.dp, bottom = 14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .graphicsLayer {
                            scaleX = confirmScale; scaleY = confirmScale
                            alpha = if (canConfirm) 1f else 0.4f
                        }
                        .glowShadow(Color(0x94E8834A), 12.dp, 13.dp)
                        .background(NexusAccent, RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = confirmInteraction,
                            indication = null,
                            enabled = canConfirm
                        ) { selectedModel?.let { onConfirm(it) } },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        confirmLabel, fontFamily = DmSansFamily, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ── Model row (list style, 2-column grid) ─────────────────────────────────────

@Composable
private fun ModelCard(display: ModelDisplay, isSelected: Boolean, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val cardScale by animateFloatAsState(if (isPressed) 0.97f else 1f, tween(150), label = "card_scale")

    val cardBg by animateColorAsState(
        if (isSelected) Color(0x0AE8834A) else NexusSurface,
        tween(200), label = "card_bg"
    )
    val cardBorderColor by animateColorAsState(
        if (isSelected) NexusAccent else NexusBorder,
        tween(200), label = "card_border"
    )
    val isFree = Cap.FREE in display.effectiveCaps

    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = cardScale; scaleY = cardScale }
            .fillMaxHeight()
            .background(cardBg, RoundedCornerShape(12.dp))
            .border(1.dp, cardBorderColor, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null) { onSelect() }
            .heightIn(min = 90.dp)
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Model name (with right padding for the badge/tick)
            Text(
                display.model.name,
                fontFamily = DmSansFamily, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, color = NexusText,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(end = 20.dp)
            )
            // Description
            Text(
                display.description,
                fontFamily = DmSansFamily, fontSize = 11.sp,
                color = NexusText2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp
            )
            // Capability chips
            if (display.effectiveCaps.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    CAP_PILL_ORDER.forEach { (cap, label) ->
                        if (cap in display.effectiveCaps) {
                            val cc = capColor(label)
                            Box(
                                modifier = Modifier
                                    .background(cc.copy(alpha = 0.10f), RoundedCornerShape(3.dp))
                                    .border(1.dp, cc.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    label.uppercase(), fontFamily = SpaceMonoFamily,
                                    fontSize = 8.sp, fontWeight = FontWeight.SemiBold,
                                    color = cc, letterSpacing = 0.3.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Top-right: selected tick OR FREE/PAID badge
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp)
                    .background(NexusAccent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        } else {
            // Show FREE or PAID badge
            val badgeColor = if (isFree) NexusGreen else NexusAmber
            val badgeBg = if (isFree) NexusGreenDim else Color(0x15FBBF24)
            val badgeBorder = if (isFree) Color(0x303DD68C) else Color(0x30FBBF24)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(badgeBg, RoundedCornerShape(3.dp))
                    .border(1.dp, badgeBorder, RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    if (isFree) "free" else "paid",
                    fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold, color = badgeColor
                )
            }
        }
    }
}

@Composable
private fun ModelSectionHeader(label: String, color: Color, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label.uppercase(),
            fontFamily = DmSansFamily, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            color = color
        )
        // Divider line
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(NexusBorder)
        )
        // Count badge
        Box(
            modifier = Modifier
                .background(
                    if (color == NexusGreen) NexusGreenDim else Color(0x15FBBF24),
                    RoundedCornerShape(4.dp)
                )
                .border(
                    1.dp,
                    if (color == NexusGreen) Color(0x303DD68C) else Color(0x30FBBF24),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(
                "$count models",
                fontFamily = SpaceMonoFamily, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, color = color
            )
        }
    }
}

@Composable
private fun ModelRowList(
    models: List<ModelDisplay>,
    selectedModel: AiModel?,
    onSelect: (AiModel) -> Unit
) {
    // 2-column grid layout
    val rows = models.chunked(2)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { display ->
                    Box(modifier = Modifier.weight(1f)) {
                        ModelCard(
                            display = display,
                            isSelected = selectedModel == display.model,
                            onSelect = { onSelect(display.model) }
                        )
                    }
                }
                // Fill odd slot with empty placeholder
                if (row.size == 1) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0x03FFFFFF), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0x08FFFFFF), RoundedCornerShape(12.dp))
                    )
                }
            }
        }
    }
}

// Keep ModelSubGrid for backwards compatibility (unused but avoids break if referenced elsewhere)
@Composable
private fun ModelSubGrid(
    models: List<ModelDisplay>,
    selectedModel: AiModel?,
    onSelect: (AiModel) -> Unit
) = ModelRowList(models, selectedModel, onSelect)

@Composable
private fun SectionHeader(label: String, color: Color) {
    ModelSectionHeader(label.removePrefix("🆓 ").removePrefix("💳 "), color, 0)
}
