package com.claudecodesetup.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.*

// ─── Timeline constants (seconds) ───────────────────────────────────────────
private const val TOTAL = 3.3f          // total animation time before fade-out

// ─── Design tokens ───────────────────────────────────────────────────────────
// Dark-frosted glass theme: pure-black stage, amber core ("one door"), and four
// palette-hued "paths" (cyan / rose / amber / emerald — no purple) that converge
// into the core — a literal rendering of the tagline MANY PATHS · ONE DOOR.
private val AmberAccent  = Color(0xFFFF8C42)   // the core / primary
private val BgColor      = Color(0xFF000000)   // pure black
private val SurfaceColor = Color(0x14FFFFFF)   // frosted track for the loading bar

// Per-cardinal path colours, indexed [top, right, bottom, left] to match dotDirs.
private val PathColors = listOf(
    Color(0xFF00D4FF),   // top    — cyan
    Color(0xFFFF4D6D),   // right  — rose
    Color(0xFFFF8C42),   // bottom — amber
    Color(0xFF10FFAB),   // left   — emerald
)

// ─── Easing helpers ──────────────────────────────────────────────────────────
private fun easeOutCubic(t: Float)   = 1f - (1f - t).pow(3)
private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f; val c3 = c1 + 1f
    return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
}
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

// Convenience: clamp (start..start+dur) to 0..1 progress
private fun prog(t: Float, start: Float, dur: Float) = ((t - start) / dur).coerceIn(0f, 1f)

// ─── Canvas helpers ──────────────────────────────────────────────────────────

/** Multi-layer circle bloom for soft glow effect */
private fun DrawScope.drawGlow(center: Offset, radius: Float, color: Color, layers: Int = 4) {
    for (i in layers downTo 1) {
        val ratio = i.toFloat() / layers
        val r = (radius * (1f + ratio * 0.8f)).coerceAtLeast(0.5f)
        val a = (color.alpha * (1f - ratio) * 0.5f).coerceIn(0f, 1f)
        drawCircle(color.copy(alpha = a), r, center)
    }
    drawCircle(color, radius.coerceAtLeast(0.5f), center)
}

/** Draw a line whose visible length is [progress] fraction from [start] to [end] */
private fun DrawScope.drawLineProgress(
    color: Color,
    start: Offset,
    end: Offset,
    progress: Float,
    strokeWidth: Float
) {
    if (progress < 0.001f) return
    val tip = Offset(
        lerp(start.x, end.x, progress),
        lerp(start.y, end.y, progress)
    )
    drawLine(color, start, tip, strokeWidth)
}

// ─── Main composable ─────────────────────────────────────────────────────────

@Composable
fun SplashAnimationScreen(shouldPlay: Boolean = true, onFinished: () -> Unit) {
    if (!shouldPlay) {
        LaunchedEffect(Unit) { onFinished() }
        return
    }

    var time by remember { mutableFloatStateOf(0f) }
    var fadeOut by remember { mutableStateOf(false) }
    val screenAlpha by animateFloatAsState(
        targetValue = if (fadeOut) 0f else 1f,
        animationSpec = tween(500),
        label = "splash_fade"
    )

    LaunchedEffect(Unit) {
        var startNanos = -1L
        while (true) {
            val frameNanos = withFrameNanos { it }
            if (startNanos < 0L) startNanos = frameNanos
            val elapsed = (frameNanos - startNanos) / 1_000_000_000f
            time = elapsed.coerceAtMost(TOTAL)
            if (elapsed >= TOTAL) break
        }
        fadeOut = true
        delay(520L)
        onFinished()
    }

    val t = time

    // ── Phase progress values ────────────────────────────────────────────────
    // Phase 2 (0.2s): 4 outer source dots appear, 80ms stagger
    val dotScales   = (0..3).map { i ->
        val p = prog(t, 0.20f + i * 0.08f, 0.25f)
        easeOutBack(p)
    }

    // Phase 3a (0.6s): cardinal lines draw inward, 40ms stagger
    val cardinalProg = (0..3).map { i ->
        easeOutCubic(prog(t, 0.60f + i * 0.04f, 0.35f))
    }
    // Phase 3b (0.7s): diagonal lines fade in together
    val diagAlpha    = easeOutCubic(prog(t, 0.70f, 0.30f)) * 0.22f

    // Phase 4 (1.2s): center node activates
    val centerT      = easeOutCubic(prog(t, 1.20f, 0.25f))
    val glowRing1T   = easeOutCubic(prog(t, 1.20f, 0.45f))
    val glowRing2T   = easeOutCubic(prog(t, 1.30f, 0.45f))
    val glowRing3T   = easeOutCubic(prog(t, 1.40f, 0.45f))
    val centerDotT   = easeOutBack(prog(t, 1.25f, 0.20f))

    // Phase 5 (1.4s): diamond outline traces
    val diamondT     = easeOutCubic(prog(t, 1.40f, 0.35f))

    // Phase 6 (1.7s): wordmark slides up + fades in
    val wordmarkT    = easeOutCubic(prog(t, 1.70f, 0.40f))

    // Phase 7 (2.0s): loading bar fills left→right over 0.6s
    val loadBarT     = easeOutCubic(prog(t, 2.00f, 0.60f))
    val taglineT     = easeOutCubic(prog(t, 1.95f, 0.40f))

    // ── Dot positions: cardinal — top, right, bottom, left ──────────────────
    // (stored as normalized offsets from center, resolved in Canvas)
    // top=(0,-1), right=(1,0), bottom=(0,1), left=(-1,0)  × outerRadius
    val dotDirs = listOf(
        Offset(0f, -1f),
        Offset(1f,  0f),
        Offset(0f,  1f),
        Offset(-1f, 0f)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .graphicsLayer { alpha = screenAlpha },
        contentAlignment = Alignment.Center
    ) {
        // Pure-black stage — no ambient wash (frosted glass theme).
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // ── Canvas: icon animation ───────────────────────────────────────
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val canvasSize = minOf(280.dp, maxWidth * 0.80f)

                Canvas(modifier = Modifier.size(canvasSize)) {
                    val s   = size.width
                    val cx  = s * 0.5f
                    val cy  = s * 0.5f

                    // distances from center
                    val outerR  = s * 0.36f   // where source dots sit
                    val lineEnd = s * 0.14f   // where cardinal lines terminate (inner edge of diamond)
                    val dotR    = s * 0.028f  // radius of outer source dot
                    val centerR = s * 0.055f  // radius of center dot
                    val diamondHalf = s * 0.28f // half-extent of diamond

                    // ── Diagonal lines (phase 3b) ────────────────────────────
                    if (diagAlpha > 0.001f) {
                        val lineColor = Color.White.copy(alpha = diagAlpha * 0.6f)
                        val sw = s * 0.006f
                        // 4 diagonals at 45°, 135°, 225°, 315°
                        listOf(45f, 135f, 225f, 315f).forEach { angleDeg ->
                            val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
                            drawLine(
                                color = lineColor,
                                start = Offset(cx + cos(rad) * outerR, cy + sin(rad) * outerR),
                                end   = Offset(cx - cos(rad) * outerR, cy - sin(rad) * outerR),
                                strokeWidth = sw
                            )
                        }
                    }

                    // ── Cardinal lines draw inward (phase 3a) ────────────────
                    dotDirs.forEachIndexed { i, dir ->
                        val p = cardinalProg[i]
                        if (p < 0.001f) return@forEachIndexed
                        val lineColor = PathColors[i].copy(alpha = 0.70f * p.coerceAtMost(1f))
                        val sw = s * 0.007f
                        val startPt = Offset(cx + dir.x * outerR, cy + dir.y * outerR)
                        val endPt   = Offset(cx + dir.x * lineEnd, cy + dir.y * lineEnd)
                        // draw from outer dot inward with progress
                        drawLineProgress(lineColor, startPt, endPt, p, sw)
                    }

                    // ── Glow rings expanding from center (phase 4) ───────────
                    val ringData = listOf(
                        Triple(glowRing1T, s * 0.06f, s * 0.13f),
                        Triple(glowRing2T, s * 0.06f, s * 0.16f),
                        Triple(glowRing3T, s * 0.06f, s * 0.20f),
                    )
                    ringData.forEach { (rt, startRadius, endRadius) ->
                        if (rt > 0.001f) {
                            val ringAlpha = (1f - rt) * 0.22f * centerT
                            val ringR = lerp(startRadius, endRadius, rt)
                            drawCircle(
                                color = AmberAccent.copy(alpha = ringAlpha.coerceIn(0f, 1f)),
                                radius = ringR.coerceAtLeast(1f),
                                center = Offset(cx, cy),
                                style = Stroke(width = s * 0.005f)
                            )
                        }
                    }

                    // Halo fill behind center dot
                    if (centerT > 0.001f) {
                        drawCircle(
                            color = AmberAccent.copy(alpha = 0.08f * centerT),
                            radius = (s * 0.11f).coerceAtLeast(1f),
                            center = Offset(cx, cy)
                        )
                    }

                    // ── Diamond outline traces itself (phase 5) ───────────────
                    // We draw all 4 edges of the diamond, each edge fading in
                    // sequentially as diamondT progresses 0→1 (4 edges × 0.25 each)
                    if (diamondT > 0.001f) {
                        // The "door" — a frosted white-glass diamond (neutral, so the
                        // coloured paths read against it).
                        val diamondColor = Color.White.copy(alpha = 0.80f * diamondT.coerceAtMost(1f))
                        val sw = s * 0.008f
                        val topPt    = Offset(cx,              cy - diamondHalf)
                        val rightPt  = Offset(cx + diamondHalf, cy)
                        val bottomPt = Offset(cx,              cy + diamondHalf)
                        val leftPt   = Offset(cx - diamondHalf, cy)

                        val edges = listOf(
                            topPt    to rightPt,
                            rightPt  to bottomPt,
                            bottomPt to leftPt,
                            leftPt   to topPt,
                        )
                        edges.forEachIndexed { i, (a, b) ->
                            val edgeStart = i * 0.25f
                            val edgeProg  = ((diamondT - edgeStart) / 0.25f).coerceIn(0f, 1f)
                            if (edgeProg > 0.001f) {
                                drawLineProgress(diamondColor, a, b, edgeProg, sw)
                            }
                        }

                        // Convergence lines from each diamond tip to the core, each
                        // carrying its cardinal path's hue (tips are top/right/bottom/left
                        // → same order as PathColors).
                        val convSw = s * 0.005f
                        listOf(topPt, rightPt, bottomPt, leftPt).forEachIndexed { i, tip ->
                            val convColor = PathColors[i].copy(alpha = (0.45f * diamondT).coerceIn(0f, 1f))
                            drawLine(convColor, tip, Offset(cx, cy), convSw)
                        }
                    }

                    // ── Outer source dots (phase 2) ───────────────────────────
                    dotDirs.forEachIndexed { i, dir ->
                        val scale = dotScales[i].coerceIn(0f, 1.3f)
                        if (scale < 0.01f) return@forEachIndexed
                        val pathColor = PathColors[i]
                        val dotCenter = Offset(cx + dir.x * outerR, cy + dir.y * outerR)
                        val r = dotR * scale
                        // glow halo
                        drawCircle(
                            color = pathColor.copy(alpha = 0.22f * scale.coerceAtMost(1f)),
                            radius = (r * 2.4f).coerceAtLeast(0.5f),
                            center = dotCenter
                        )
                        // dot fill
                        drawCircle(
                            color = pathColor.copy(alpha = 0.92f),
                            radius = r.coerceAtLeast(0.5f),
                            center = dotCenter
                        )
                    }

                    // ── Center node: glow + dot (phase 4) ────────────────────
                    if (centerDotT > 0.001f) {
                        val r = centerR * centerDotT.coerceAtMost(1f)
                        drawGlow(Offset(cx, cy), r, AmberAccent.copy(alpha = 0.85f), layers = 5)
                    }
                }
            }

            // ── Phase 6: Wordmark ────────────────────────────────────────────
            Spacer(Modifier.height(20.dp))
            Text(
                text = "NEXUS MIND",
                fontFamily = SpaceMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                letterSpacing = 8.sp,
                textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFFFFFFFF), Color(0xFF00D4FF), Color(0xFFFF8C42))
                    )
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = wordmarkT
                        translationY = lerp(14f * density, 0f, wordmarkT)
                    }
            )

            // ── Tagline ──────────────────────────────────────────────────────
            Spacer(Modifier.height(10.dp))
            Text(
                text = "MANY PATHS · ONE DOOR",
                fontFamily = SpaceMonoFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                letterSpacing = 3.sp,
                color = NexusText2,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = taglineT
                        translationY = lerp(10f * density, 0f, taglineT)
                    }
            )

            // ── Phase 7: Loading bar ─────────────────────────────────────────
            Spacer(Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(2.dp)
                    .background(SurfaceColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp))
                    .graphicsLayer {
                        alpha = wordmarkT   // bar appears with wordmark
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = loadBarT.coerceIn(0f, 1f))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF00D4FF), Color(0xFFFF8C42))
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}
