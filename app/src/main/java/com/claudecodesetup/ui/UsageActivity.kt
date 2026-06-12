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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

// Input = blue, Output = amber — consistent across every chart + the live chip.
private val InColor = NexusBlue
private val OutColor = NexusAccent

// Distinct-but-warm-leaning series palette for the donut (no purple/cyan, per
// the design system; reuses established blue/green tokens as functional accents).
private val SeriesPalette = listOf(
    NexusAccent, NexusBlue, NexusGreen, NexusAmber,
    Color(0xFFE0795B), Color(0xFFC98A3C), Color(0xFF8FB8F0), Color(0xFF7FD8A8),
)

private fun fmtTok(n: Long): String = when {
    n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
    n >= 1_000     -> "%.1fk".format(n / 1_000.0)
    else           -> n.toString()
}

private fun shortModel(id: String): String =
    id.substringAfterLast('/').ifEmpty { id }.let { if (it.length > 18) it.take(17) + "…" else it }

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

    AppBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(NexusSurface, RoundedCornerShape(17.dp))
                        .border(1.dp, NexusBorder, RoundedCornerShape(17.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) { Text("←", fontSize = 16.sp, color = NexusAccent, fontFamily = DmSansFamily) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Token Usage", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = NexusText, fontFamily = DmSansFamily)
                    Text("proxy providers only · subscription not metered",
                        fontSize = 10.sp, color = NexusText3, fontFamily = SpaceMonoFamily)
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(NexusSurface, RoundedCornerShape(17.dp))
                        .border(1.dp, NexusBorder, RoundedCornerShape(17.dp))
                        .clickable { refreshKey++ },
                    contentAlignment = Alignment.Center
                ) { Text("⟳", fontSize = 16.sp, color = NexusText2, fontFamily = DmSansFamily) }
            }

            // ── Period segmented control ──
            PeriodSegmented(period) { period = it }

            val rep = report
            if (rep == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("…", color = NexusText3, fontSize = 24.sp)
                }
                return@Column
            }

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
                        color = NexusText3, fontSize = 13.sp, fontFamily = SpaceMonoFamily,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                return@Column
            }

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
                if (rep.byModel.isNotEmpty()) DonutCard(rep)
                if (rep.byModel.isNotEmpty()) StackedModelCard(rep.byModel)
                if (rep.byDay.size > 1) TimeGraphCard(rep)
                ProviderListCard(rep)
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NexusSurface, RoundedCornerShape(16.dp))
            .border(1.dp, NexusBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun CardTitle(t: String) {
    Text(t, fontSize = 12.sp, color = NexusText2, fontFamily = SpaceMonoFamily,
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
            .clip(RoundedCornerShape(10.dp))
            .background(NexusSurface, RoundedCornerShape(10.dp))
            .border(1.dp, NexusBorder, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items.forEach { (p, label) ->
            val sel = p == period
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) NexusAccentDim else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onChange(p) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 12.sp,
                    color = if (sel) NexusAccent else NexusText2,
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
        Chip("All providers", providerFilter == null) { onProvider(null) }
        providers.forEach { p -> Chip(p, providerFilter == p) { onProvider(p) } }
        if (providerFilter != null && models.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            Chip("All models", modelFilter == null) { onModel(null) }
            models.forEach { m -> Chip(shortModel(m), modelFilter == m) { onModel(m) } }
        }
    }
}

@Composable
private fun Chip(label: String, sel: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (sel) NexusAccentDim else NexusSurface, RoundedCornerShape(8.dp))
            .border(1.dp, if (sel) NexusAccent else NexusBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 11.sp, color = if (sel) NexusAccent else NexusText2,
            fontFamily = SpaceMonoFamily)
    }
}

@Composable
private fun HeroCard(rep: UsageReport) {
    GlassCard {
        Text(fmtTok(rep.grandTotal), fontSize = 34.sp, fontWeight = FontWeight.Bold,
            color = NexusText, fontFamily = DmSansFamily)
        Text("tokens", fontSize = 12.sp, color = NexusText3, fontFamily = SpaceMonoFamily)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(InColor); Spacer(Modifier.width(4.dp))
            Text("↑ in  ${fmtTok(rep.totalIn)}", fontSize = 13.sp, color = NexusText2, fontFamily = SpaceMonoFamily)
            Spacer(Modifier.width(16.dp))
            LegendDot(OutColor); Spacer(Modifier.width(4.dp))
            Text("↓ out  ${fmtTok(rep.totalOut)}", fontSize = 13.sp, color = NexusText2, fontFamily = SpaceMonoFamily)
        }
        Spacer(Modifier.height(6.dp))
        Text("${rep.totalReq} requests", fontSize = 12.sp, color = NexusText3, fontFamily = SpaceMonoFamily)
        Spacer(Modifier.height(10.dp))
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

@Composable
private fun LegendDot(c: Color) {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
}

@Composable
private fun DonutCard(rep: UsageReport) {
    GlassCard {
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
                        color = NexusText, fontFamily = DmSansFamily)
                    Text("tokens", fontSize = 9.sp, color = NexusText3, fontFamily = SpaceMonoFamily)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rep.byModel.take(6).forEachIndexed { i, e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(SeriesPalette[i % SeriesPalette.size])
                        Spacer(Modifier.width(6.dp))
                        Text(shortModel(e.model), fontSize = 11.sp, color = NexusText,
                            fontFamily = SpaceMonoFamily, modifier = Modifier.weight(1f))
                        Text(fmtTok(e.total), fontSize = 11.sp, color = NexusText2, fontFamily = SpaceMonoFamily)
                    }
                }
            }
        }
    }
}

@Composable
private fun StackedModelCard(byModel: List<UsageEntry>) {
    GlassCard {
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
                    Text(fmtTok(e.total), fontSize = 8.sp, color = NexusText2, fontFamily = SpaceMonoFamily)
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
                    Text(shortModel(e.model).take(8), fontSize = 7.sp, color = NexusText3,
                        fontFamily = SpaceMonoFamily, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(InColor); Spacer(Modifier.width(4.dp))
            Text("input", fontSize = 10.sp, color = NexusText3, fontFamily = SpaceMonoFamily)
            Spacer(Modifier.width(14.dp))
            LegendDot(OutColor); Spacer(Modifier.width(4.dp))
            Text("output", fontSize = 10.sp, color = NexusText3, fontFamily = SpaceMonoFamily)
        }
    }
}

@Composable
private fun TimeGraphCard(rep: UsageReport) {
    GlassCard {
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
            Text(days.firstOrNull()?.day?.substring(5) ?: "", fontSize = 9.sp, color = NexusText3, fontFamily = SpaceMonoFamily)
            Text(days.lastOrNull()?.day?.substring(5) ?: "", fontSize = 9.sp, color = NexusText3, fontFamily = SpaceMonoFamily)
        }
    }
}

@Composable
private fun ProviderListCard(rep: UsageReport) {
    GlassCard {
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
                        fontSize = 11.sp, color = NexusText3, fontFamily = SpaceMonoFamily)
                    Text(p.provider, fontSize = 13.sp, color = NexusText,
                        fontFamily = SpaceMonoFamily, modifier = Modifier.weight(1f))
                    Text("${p.req} req", fontSize = 10.sp, color = NexusText3,
                        fontFamily = SpaceMonoFamily)
                    Spacer(Modifier.width(10.dp))
                    Text(fmtTok(p.total), fontSize = 13.sp, color = NexusAccent,
                        fontFamily = SpaceMonoFamily, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(NexusSurface2)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(p.total.toFloat() / maxTotal)
                            .fillMaxHeight()
                            .background(NexusAccent)
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
                                Icon(painterResource(icon), null, tint = NexusText2,
                                    modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(shortModel(m.model), fontSize = 11.sp, color = NexusText2,
                                fontFamily = SpaceMonoFamily, modifier = Modifier.weight(1f))
                            Text("in ${fmtTok(m.inTok)} / out ${fmtTok(m.outTok)}",
                                fontSize = 9.sp, color = NexusText3, fontFamily = SpaceMonoFamily)
                        }
                    }
                }
            }
        }
    }
}
