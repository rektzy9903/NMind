package com.claudecodesetup.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.UsageEntry
import com.claudecodesetup.data.UsagePeriod
import com.claudecodesetup.data.UsageReport
import com.claudecodesetup.data.UsageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Token-usage dashboard (CLAUDE.md inv 82, Phase 2). Reads usage_stats.json. */
class UsageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = filesDir
        setContent { UsageDashboardScreen(filesDir = dir, onBack = { finish() }) }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Local glassmorphism palette — SCOPED to this screen only (does NOT touch the
// app-wide UiCommon amber tokens). Deep-navy backdrop + frosted translucent
// cards + cool sky/violet/emerald accents, per the analytics-dashboard brief.
// ─────────────────────────────────────────────────────────────────────────
private val GlassBgTop    = Color(0xFF000000)   // pure black base (app system)
private val GlassBgBottom = Color(0xFF000000)
private val GlassFillC    = Color(0x0BFFFFFF)   // rgba(255,255,255,0.045)
private val GlassFill2C   = Color(0x14FFFFFF)   // slightly brighter inner surface
private val GlassBorderC  = Color(0x17FFFFFF)   // rgba(255,255,255,0.09)
private val GlassBorder2C = Color(0x2EFFFFFF)   // stronger edge (active)
private val ShadowC       = Color(0xFF000000)   // 0 8px 32px rgba(0,0,0,.4) sim

// Cyan / amber / rose / emerald — the app palette (NO purple).
private val Sky     = Color(0xFF00D4FF)         // cyan  — primary / input
private val Amber   = Color(0xFFFF8C42)         // amber — output
private val Rose    = Color(0xFFFF4D6D)         // rose
private val Emerald = Color(0xFF10FFAB)         // emerald

private val TxtPrimary = Color(0xE6FFFFFF)      // white .9
private val TxtMuted   = Color(0x73FFFFFF)      // white .45
private val TxtFaint   = Color(0x40FFFFFF)      // white .25

// Input = cyan, Output = amber — consistent across every chart + legend.
private val InColor = Sky
private val OutColor = Amber

// Series palette for the donut (cyan / amber / rose / emerald + tints, no purple).
private val SeriesPalette = listOf(
    Sky, Amber, Rose, Emerald,
    Color(0xFF38BDF8), Color(0xFFFFB37A), Color(0xFFFF85A0), Color(0xFF6EE7B7),
)

private fun fmtTok(n: Long): String = when {
    n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
    n >= 1_000     -> "%.1fk".format(n / 1_000.0)
    else           -> n.toString()
}

private fun shortModel(id: String): String =
    id.substringAfterLast('/').ifEmpty { id }.let { if (it.length > 18) it.take(17) + "…" else it }

// ── List-price reference rates (USD per 1M tokens, [in, out]) ────────────────
// Used ONLY to show the "list-price equivalent" — what these tokens WOULD cost
// at each model's standard published API rate. Most proxy providers here are
// free tiers, so this is a value/savings indicator, not a bill. Matched by the
// first substring that appears in the (lower-cased) model id; unknown → free.
private val PRICE_TABLE: List<Pair<String, Pair<Double, Double>>> = listOf(
    "opus"          to (15.0 to 75.0),
    "sonnet"        to (3.0 to 15.0),
    "haiku"         to (0.8 to 4.0),
    "gpt-4o-mini"   to (0.15 to 0.6),
    "gpt-4o"        to (2.5 to 10.0),
    "gpt-4.1"       to (2.0 to 8.0),
    "o3"            to (2.0 to 8.0),
    "gemini-3"      to (1.25 to 10.0),
    "gemini-2.5-pro" to (1.25 to 10.0),
    "gemini-1.5-pro" to (1.25 to 5.0),
    "gemini"        to (0.075 to 0.3),   // flash-class (paid rate; usually free key)
    "deepseek"      to (0.27 to 1.1),
    "qwen"          to (0.2 to 0.6),
    "mistral"       to (0.2 to 0.6),
    "llama"         to (0.2 to 0.6),
    "kimi"          to (0.15 to 2.5),
)

/** Rough list-price for one (model,in,out) cell in USD. Unknown ids → 0 (free). */
private fun estCost(model: String, inTok: Long, outTok: Long): Double {
    val id = model.lowercase()
    val rate = PRICE_TABLE.firstOrNull { id.contains(it.first) }?.second ?: return 0.0
    return inTok / 1_000_000.0 * rate.first + outTok / 1_000_000.0 * rate.second
}

private fun fmtUsd(v: Double): String = when {
    v >= 100  -> "$%.0f".format(v)
    v >= 1    -> "$%.2f".format(v)
    v > 0     -> "$%.3f".format(v)
    else      -> "$0"
}

// Common free-tier per-minute token ceilings (TPM). claude-code's baseline
// request (~25KB system prompt + ~8KB tool schemas) already costs ~30K input
// tokens before the user even types, so anything under ~30K TPM rejects terminal
// turns (CLAUDE.md inv 58). Used to colour the request-size pressure gauge.
private const val TPM_TIGHT = 6_000L     // Groq free, some OpenRouter free
private const val TPM_OK     = 30_000L    // claude-code baseline floor

@Composable
fun UsageDashboardScreen(filesDir: File, onBack: () -> Unit) {
    var period by remember { mutableStateOf(UsagePeriod.TODAY) }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    var modelFilter by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var report by remember { mutableStateOf<UsageReport?>(null) }

    LaunchedEffect(period, providerFilter, modelFilter, refreshKey) {
        report = withContext(Dispatchers.IO) {
            UsageStats.aggregate(filesDir, period, providerFilter, modelFilter)
        }
    }

    GlassBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleButton("←", Sky, onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Token Usage", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = TxtPrimary, fontFamily = DmSansFamily)
                    Text("proxy providers only · subscription not metered",
                        fontSize = 10.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
                }
                CircleButton("⟳", TxtMuted) { refreshKey++ }
            }

            // ── Period segmented control ──
            PeriodSegmented(period) { period = it }

            val rep = report
            // NOTE: no early `return@Column` here — non-local returns out of the
            // inline Column content lambda corrupt Compose's group bookkeeping
            // (IntStack.peek2 index=-2 crash). Use balanced if/else branches.
            if (rep == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("…", color = TxtMuted, fontSize = 24.sp)
                }
            } else {
                // ── Filter chips ──
                FilterChips(
                    providers = rep.providers,
                    models = rep.models,
                    providerFilter = providerFilter,
                    modelFilter = modelFilter,
                    onProvider = { providerFilter = it; modelFilter = null },
                    onModel = { modelFilter = it },
                )

                if (rep.isEmpty) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Nothing yet.\nSend a message through a proxy provider, then pull to refresh.",
                            color = TxtMuted, fontSize = 13.sp, fontFamily = SpaceMonoFamily,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Spacer(Modifier.height(2.dp))
                        HeroCard(rep)
                        CostInsightRow(rep)
                        RequestPressureCard(rep)
                        // Multi-panel analytics grid (donut / bars / time-series /
                        // in-out rate) — 2 columns on wide screens, 1 on mobile.
                        ChartGrid(rep)
                        ProviderListCard(rep)
                    }
                }
            }
        }
    }
}

/** Deep-navy backdrop with soft sky/violet/emerald glow blobs behind the glass. */
@Composable
private fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GlassBgTop, GlassBgBottom)))
    ) {
        // ambient accent glows (the "light" the frosted glass refracts)
        Box(
            Modifier.size(420.dp).align(Alignment.TopEnd)
                .background(Brush.radialGradient(listOf(Sky.copy(alpha = 0.14f), Color.Transparent)))
        )
        Box(
            Modifier.size(380.dp).align(Alignment.BottomStart)
                .background(Brush.radialGradient(listOf(Rose.copy(alpha = 0.14f), Color.Transparent)))
        )
        Box(
            Modifier.size(300.dp).align(Alignment.CenterEnd)
                .background(Brush.radialGradient(listOf(Emerald.copy(alpha = 0.08f), Color.Transparent)))
        )
        content()
    }
}

@Composable
private fun CircleButton(glyph: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .shadow(8.dp, RoundedCornerShape(17.dp), ambientColor = ShadowC, spotColor = ShadowC)
            .background(GlassFillC, RoundedCornerShape(17.dp))
            .border(1.dp, GlassBorderC, RoundedCornerShape(17.dp))
            .pressClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(glyph, fontSize = 16.sp, color = tint, fontFamily = DmSansFamily) }
}

/** Frosted glass surface: translucent white fill + hairline border + soft shadow. */
@Composable
private fun UsageCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(16.dp), ambientColor = ShadowC, spotColor = ShadowC)
            .clip(RoundedCornerShape(16.dp))
            .background(GlassFillC, RoundedCornerShape(16.dp))
            .border(1.dp, GlassBorderC, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun CardTitle(t: String) {
    Text(t, fontSize = 12.sp, color = TxtMuted, fontFamily = SpaceMonoFamily,
        fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun PeriodSegmented(period: UsagePeriod, onChange: (UsagePeriod) -> Unit) {
    val items = listOf(UsagePeriod.TODAY to "Today", UsagePeriod.MONTH to "Month", UsagePeriod.ALL to "All-time")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GlassFillC, RoundedCornerShape(12.dp))
            .border(1.dp, GlassBorderC, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items.forEach { (p, label) ->
            val sel = p == period
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (sel) Modifier.shadow(10.dp, RoundedCornerShape(9.dp),
                            ambientColor = Sky, spotColor = Sky) else Modifier
                    )
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (sel) Sky.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(9.dp))
                    .then(if (sel) Modifier.border(1.dp, Sky.copy(alpha = 0.45f), RoundedCornerShape(9.dp)) else Modifier)
                    .pressClickable { onChange(p) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 12.sp,
                    color = if (sel) Sky else TxtMuted,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = DmSansFamily)
            }
        }
    }
}

@Composable
private fun FilterChips(
    providers: List<String>,
    models: List<String>,
    providerFilter: String?,
    modelFilter: String?,
    onProvider: (String?) -> Unit,
    onModel: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Chip("All providers", providerFilter == null, Sky) { onProvider(null) }
        providers.forEach { p -> Chip(p, providerFilter == p, Sky) { onProvider(p) } }
        if (providerFilter != null && models.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            Chip("All models", modelFilter == null, Rose) { onModel(null) }
            models.forEach { m -> Chip(shortModel(m), modelFilter == m, Rose) { onModel(m) } }
        }
    }
}

@Composable
private fun Chip(label: String, sel: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .then(
                if (sel) Modifier.shadow(12.dp, RoundedCornerShape(9.dp),
                    ambientColor = accent, spotColor = accent) else Modifier
            )
            .clip(RoundedCornerShape(9.dp))
            .background(if (sel) accent.copy(alpha = 0.16f) else GlassFillC, RoundedCornerShape(9.dp))
            .border(1.dp, if (sel) accent.copy(alpha = 0.55f) else GlassBorderC, RoundedCornerShape(9.dp))
            .pressClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 11.sp, color = if (sel) accent else TxtMuted,
            fontFamily = SpaceMonoFamily)
    }
}

@Composable
private fun HeroCard(rep: UsageReport) {
    UsageCard {
        Text(fmtTok(rep.grandTotal), fontSize = 38.sp, fontWeight = FontWeight.Bold,
            color = TxtPrimary, fontFamily = DmSansFamily)
        Text("total tokens", fontSize = 12.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(InColor); Spacer(Modifier.width(4.dp))
            Text("↑ in  ${fmtTok(rep.totalIn)}", fontSize = 13.sp, color = TxtPrimary, fontFamily = SpaceMonoFamily)
            Spacer(Modifier.width(16.dp))
            LegendDot(OutColor); Spacer(Modifier.width(4.dp))
            Text("↓ out  ${fmtTok(rep.totalOut)}", fontSize = 13.sp, color = TxtPrimary, fontFamily = SpaceMonoFamily)
        }
        Spacer(Modifier.height(6.dp))
        Text("${rep.totalReq} requests", fontSize = 12.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
        Spacer(Modifier.height(12.dp))
        // In vs Out proportion bar.
        val sum = (rep.totalIn + rep.totalOut).coerceAtLeast(1L)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        ) {
            Box(Modifier.weight(rep.totalIn.toFloat() / sum + 0.0001f).fillMaxHeight().background(InColor))
            Box(Modifier.weight(rep.totalOut.toFloat() / sum + 0.0001f).fillMaxHeight().background(OutColor))
        }
    }
}

/**
 * #4 — List-price equivalent. Sums each model cell at its standard published API
 * rate, so the user sees what these tokens WOULD cost (most proxy tiers are free,
 * making this a "you saved this much" indicator). Free/unknown models contribute
 * $0 and are surfaced in the subtitle so the number is never mistaken for a bill.
 */
@Composable
private fun CostInsightRow(rep: UsageReport) {
    val total = rep.byModel.sumOf { estCost(it.model, it.inTok, it.outTok) }
    val paidModels = rep.byModel.count { estCost(it.model, it.inTok, it.outTok) > 0 }
    val freeTok = rep.byModel.filter { estCost(it.model, it.inTok, it.outTok) <= 0 }.sumOf { it.total }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Left: list-price equivalent.
        UsageCard(modifier = Modifier.weight(1f)) {
            Text("≈ ${fmtUsd(total)}", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                color = Emerald, fontFamily = DmSansFamily)
            Text("list-price equiv.", fontSize = 10.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
            Spacer(Modifier.height(6.dp))
            Text(
                if (total <= 0.0) "all free-tier — $0 billed"
                else "at standard API rates · $paidModels paid model${if (paidModels == 1) "" else "s"}",
                fontSize = 9.sp, color = TxtFaint, fontFamily = SpaceMonoFamily)
        }
        // Right: free tokens (the savings story).
        UsageCard(modifier = Modifier.weight(1f)) {
            Text(fmtTok(freeTok), fontSize = 26.sp, fontWeight = FontWeight.Bold,
                color = Sky, fontFamily = DmSansFamily)
            Text("free-tier tokens", fontSize = 10.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
            Spacer(Modifier.height(6.dp))
            Text("served at $0 cost", fontSize = 9.sp, color = TxtFaint, fontFamily = SpaceMonoFamily)
        }
    }
}

/**
 * #3 — Request-size pressure. claude-code stages a ~30K-token baseline (system
 * prompt + tool schemas) on EVERY turn, so low-TPM free providers reject terminal
 * requests regardless of how short the user message is (inv 58). Shows the average
 * input tokens/request and rates it against common free-tier ceilings, plus a tip.
 */
@Composable
private fun RequestPressureCard(rep: UsageReport) {
    if (rep.totalReq <= 0) return
    val avgIn = rep.totalIn / rep.totalReq
    val (statusColor, statusLabel, tip) = when {
        avgIn >= TPM_OK -> Triple(Rose, "HEAVY",
            "Above ~30K/req — low-TPM free tiers (Groq 6K, some OpenRouter free) will reject these. Use Quick Ask for those, or a higher-TPM provider.")
        avgIn >= TPM_TIGHT -> Triple(Amber, "MODERATE",
            "Fits ~30K-TPM providers but not the tightest free tiers. Trimming tools (Settings → Tools) or `!defer` lowers it.")
        else -> Triple(Emerald, "LIGHT",
            "Comfortable on most free tiers. Headroom for larger context or more tools.")
    }
    // Scale gauge against the 30K floor (cap fill at 1.0 past it).
    val frac = (avgIn.toFloat() / TPM_OK).coerceIn(0f, 1f)
    UsageCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Request-size pressure", fontSize = 12.sp, color = TxtMuted,
                fontFamily = SpaceMonoFamily, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(7.dp))
                    .background(statusColor.copy(alpha = 0.15f))
                    .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) { Text(statusLabel, fontSize = 10.sp, color = statusColor,
                fontFamily = SpaceMonoFamily, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtTok(avgIn), fontSize = 30.sp, fontWeight = FontWeight.Bold,
                color = statusColor, fontFamily = DmSansFamily)
            Spacer(Modifier.width(6.dp))
            Text("avg input / req", fontSize = 11.sp, color = TxtMuted,
                fontFamily = SpaceMonoFamily, modifier = Modifier.padding(bottom = 5.dp))
        }
        Spacer(Modifier.height(10.dp))
        // Gauge with tick marks at the two thresholds.
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(GlassFill2C)) {
            Box(
                Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(5.dp))
                    .background(Brush.horizontalGradient(listOf(statusColor.copy(alpha = 0.55f), statusColor)))
            )
            // 6K tick.
            Box(Modifier.fillMaxWidth(TPM_TIGHT.toFloat() / TPM_OK).fillMaxHeight()) {
                Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight().background(TxtFaint))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", fontSize = 8.sp, color = TxtFaint, fontFamily = SpaceMonoFamily)
            Text("6K", fontSize = 8.sp, color = TxtFaint, fontFamily = SpaceMonoFamily)
            Text("30K+", fontSize = 8.sp, color = TxtFaint, fontFamily = SpaceMonoFamily)
        }
        Spacer(Modifier.height(10.dp))
        Text(tip, fontSize = 10.sp, color = TxtMuted, fontFamily = SpaceMonoFamily, lineHeight = 14.sp)
    }
}

@Composable
private fun LegendDot(c: Color) {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
}

/**
 * Responsive analytics grid. Collects the available chart panels and lays them
 * out 2-per-row on wide screens (>= 600dp), single-column on narrow phones.
 * Purely presentational — reads the same [rep] fields the old single-column
 * layout did, just arranged into the donut / bars / time-series / rate quadrants.
 */
@Composable
private fun ChartGrid(rep: UsageReport) {
    val panels = buildList<@Composable () -> Unit> {
        if (rep.byModel.isNotEmpty()) add { DonutCard(rep) }                 // top-left  : donut
        if (rep.byModel.isNotEmpty()) add { StackedModelCard(rep.byModel) }  // top-right : bars
        if (rep.byDay.size > 1) add { TimeGraphCard(rep) }                   // bot-left  : time-series
        add { InOutRateCard(rep) }                                           // bot-right : in/out rate
    }
    BoxWithConstraints {
        val twoCol = maxWidth >= 600.dp
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (twoCol) {
                panels.chunked(2).forEach { rowPanels ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        rowPanels.forEach { panel ->
                            Box(Modifier.weight(1f)) { panel() }
                        }
                        if (rowPanels.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                panels.forEach { it() }
            }
        }
    }
}

@Composable
private fun DonutCard(rep: UsageReport) {
    UsageCard {
        CardTitle("Tokens by model")
        Row(verticalAlignment = Alignment.CenterVertically) {
            val total = rep.grandTotal.coerceAtLeast(1L)
            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(140.dp)) {
                    val stroke = 26f
                    val inset = stroke / 2f + 2f
                    val topLeft = Offset(inset, inset)
                    val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                    var start = -90f
                    rep.byModel.forEachIndexed { i, e ->
                        val sweep = 360f * (e.total.toFloat() / total)
                        drawArc(
                            color = SeriesPalette[i % SeriesPalette.size],
                            startAngle = start, sweepAngle = sweep - 1.2f, useCenter = false,
                            topLeft = topLeft, size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Butt)
                        )
                        start += sweep
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtTok(rep.grandTotal), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = TxtPrimary, fontFamily = DmSansFamily)
                    Text("tokens", fontSize = 9.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rep.byModel.take(6).forEachIndexed { i, e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(SeriesPalette[i % SeriesPalette.size])
                        Spacer(Modifier.width(6.dp))
                        Text(shortModel(e.model), fontSize = 11.sp, color = TxtPrimary,
                            fontFamily = SpaceMonoFamily, modifier = Modifier.weight(1f))
                        Text(fmtTok(e.total), fontSize = 11.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
                    }
                }
            }
        }
    }
}

@Composable
private fun StackedModelCard(byModel: List<UsageEntry>) {
    UsageCard {
        CardTitle("Input / output by model")
        val rows = byModel.take(8)
        val maxTotal = (rows.maxOfOrNull { it.total } ?: 1L).coerceAtLeast(1L)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            rows.forEach { e ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    val barMaxH = 110f
                    val totH = barMaxH * (e.total.toFloat() / maxTotal)
                    val outH = totH * (e.outTok.toFloat() / e.total.coerceAtLeast(1L))
                    val inH = totH - outH
                    Text(fmtTok(e.total), fontSize = 8.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
                    Spacer(Modifier.height(2.dp))
                    Column(
                        modifier = Modifier
                            .width(20.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    ) {
                        Box(Modifier.width(20.dp).height(outH.dp).background(OutColor))
                        Box(Modifier.width(20.dp).height(inH.dp).background(InColor))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(shortModel(e.model).take(8), fontSize = 7.sp, color = TxtMuted,
                        fontFamily = SpaceMonoFamily, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        InOutLegend()
    }
}

@Composable
private fun TimeGraphCard(rep: UsageReport) {
    UsageCard {
        CardTitle("Per-day usage")
        val days = rep.byDay.takeLast(30)
        val maxTotal = (days.maxOfOrNull { it.total } ?: 1L).coerceAtLeast(1L)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            days.forEach { d ->
                val barMaxH = 110f
                val totH = barMaxH * (d.total.toFloat() / maxTotal)
                val outH = totH * (d.outTok.toFloat() / d.total.coerceAtLeast(1L))
                val inH = totH - outH
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.fillMaxWidth().height(outH.dp).background(OutColor))
                    Box(Modifier.fillMaxWidth().height(inH.dp).background(InColor))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(days.firstOrNull()?.day?.substring(5) ?: "", fontSize = 9.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
            Text(days.lastOrNull()?.day?.substring(5) ?: "", fontSize = 9.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
        }
    }
}

/**
 * Bottom-right quadrant — input vs output rate. Two horizontal frosted bars
 * (input = sky, output = violet) scaled to the larger of the two, plus the
 * output:input ratio and average tokens/request. Reads only existing totals.
 */
@Composable
private fun InOutRateCard(rep: UsageReport) {
    UsageCard {
        CardTitle("Input / output rate")
        val peak = maxOf(rep.totalIn, rep.totalOut, 1L)
        RateBar("input", rep.totalIn, peak, InColor)
        Spacer(Modifier.height(10.dp))
        RateBar("output", rep.totalOut, peak, OutColor)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val ratio = if (rep.totalIn > 0) rep.totalOut.toDouble() / rep.totalIn else 0.0
            val avg = if (rep.totalReq > 0) rep.grandTotal / rep.totalReq else 0L
            StatPill("out : in", "%.2f×".format(ratio), Amber, Modifier.weight(1f))
            StatPill("avg / req", fmtTok(avg), Emerald, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RateBar(label: String, value: Long, peak: Long, color: Color) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 11.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
            Text(fmtTok(value), fontSize = 11.sp, color = color, fontFamily = SpaceMonoFamily,
                fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(GlassFill2C)
        ) {
            Box(
                Modifier
                    .fillMaxWidth((value.toFloat() / peak).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), color)))
            )
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(accent.copy(alpha = 0.10f), RoundedCornerShape(11.dp))
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(value, fontSize = 16.sp, color = accent, fontWeight = FontWeight.Bold,
            fontFamily = DmSansFamily)
        Text(label, fontSize = 9.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
    }
}

@Composable
private fun InOutLegend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendDot(InColor); Spacer(Modifier.width(4.dp))
        Text("input", fontSize = 10.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
        Spacer(Modifier.width(14.dp))
        LegendDot(OutColor); Spacer(Modifier.width(4.dp))
        Text("output", fontSize = 10.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
    }
}

@Composable
private fun ProviderListCard(rep: UsageReport) {
    UsageCard {
        CardTitle("By provider")
        val maxTotal = (rep.byProvider.maxOfOrNull { it.total } ?: 1L).coerceAtLeast(1L)
        rep.byProvider.forEach { p ->
            var expanded by remember(p.provider) { mutableStateOf(false) }
            val models = rep.byModel.filter { it.provider == p.provider }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = models.size > 1) { expanded = !expanded }
                    .padding(vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (models.size > 1) (if (expanded) "▾ " else "▸ ") else "  ",
                        fontSize = 11.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
                    Text(p.provider, fontSize = 13.sp, color = TxtPrimary,
                        fontFamily = SpaceMonoFamily, modifier = Modifier.weight(1f))
                    Text("${p.req} req", fontSize = 10.sp, color = TxtMuted,
                        fontFamily = SpaceMonoFamily)
                    Spacer(Modifier.width(10.dp))
                    Text(fmtTok(p.total), fontSize = 13.sp, color = Sky,
                        fontFamily = SpaceMonoFamily, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(GlassFill2C)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(p.total.toFloat() / maxTotal)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(Brush.horizontalGradient(listOf(Sky.copy(alpha = 0.5f), Sky)))
                    )
                }
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    models.forEach { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = brandIconForModel(m.model)
                            if (icon != 0) {
                                Icon(painterResource(icon), null, tint = TxtMuted,
                                    modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(shortModel(m.model), fontSize = 11.sp, color = TxtMuted,
                                fontFamily = SpaceMonoFamily, modifier = Modifier.weight(1f))
                            Text("in ${fmtTok(m.inTok)} / out ${fmtTok(m.outTok)}",
                                fontSize = 9.sp, color = TxtMuted, fontFamily = SpaceMonoFamily)
                        }
                    }
                }
            }
        }
    }
}
