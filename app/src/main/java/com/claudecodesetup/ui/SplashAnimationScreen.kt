package com.claudecodesetup.ui

import android.graphics.DashPathEffect
import android.graphics.Typeface
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.*

private const val TOTAL = 5.5f
private const val CX = 0.50f
private const val CY = 0.46f

private data class ProviderDot(
    val x: Float, val y: Float,
    val color: Color, val label: String
)

private val PROVIDERS = listOf(
    ProviderDot(0.12f, 0.13f, Color(0xFFf97316), "Claude"),
    ProviderDot(0.87f, 0.10f, Color(0xFF10b981), "GPT"),
    ProviderDot(0.94f, 0.55f, Color(0xFF3b82f6), "Gemini"),
    ProviderDot(0.80f, 0.90f, Color(0xFF8b5cf6), "Llama"),
    ProviderDot(0.16f, 0.88f, Color(0xFFef4444), "Grok"),
    ProviderDot(0.06f, 0.48f, Color(0xFF06b6d4), "DeepSeek"),
)

private fun easeOutCubic(t: Float) = 1f - (1f - t).pow(3)
private fun easeInOutQuart(t: Float) =
    if (t < 0.5f) 8f * t.pow(4) else 1f - (-2f * t + 2f).pow(4) / 2f
private fun easeOutElastic(t: Float): Float {
    if (t == 0f || t == 1f) return t
    return (2f.pow(-10f * t) * sin((t * 10f - 0.75f) * (2.0 * PI / 3.0)).toFloat() + 1f)
}
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

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

    // Timeline:
    // 0.3–1.7s  dots appear staggered
    // 1.4–2.5s  dots converge to center
    // 2.4–2.8s  convergence flash burst
    // 2.5–3.4s  diamond forms (elastic)
    // 2.6–3.2s  bloom ring expands and fades
    // 3.1–4.0s  "NEXUS MIND" slides in
    // 3.8–4.4s  tagline slides in
    // 4.5–5.5s  hold then external fade

    val dotAppear = PROVIDERS.mapIndexed { i, _ ->
        easeOutCubic(((t - (0.3f + i * 0.2f)) / 0.4f).coerceIn(0f, 1f))
    }
    val dotProgress = PROVIDERS.mapIndexed { i, _ ->
        easeInOutQuart(((t - (1.4f + i * 0.05f)) / 0.8f).coerceIn(0f, 1f))
    }

    val flashT       = run {
        val rise = ((t - 2.4f) / 0.18f).coerceIn(0f, 1f)
        val fall = ((t - 2.58f) / 0.22f).coerceIn(0f, 1f)
        (rise * (1f - fall)).coerceIn(0f, 1f)
    }
    val diamondT       = easeOutElastic(((t - 2.5f) / 0.75f).coerceIn(0f, 1f))
    val diamondOpacity = ((t - 2.5f) / 0.3f).coerceIn(0f, 1f)
    val bloomT         = easeOutCubic(((t - 2.6f) / 0.65f).coerceIn(0f, 1f))
    val bloomOpacity   = if (bloomT > 0f) sin(bloomT * PI.toFloat()) else 0f
    val appNameT       = easeOutCubic(((t - 3.1f) / 0.9f).coerceIn(0f, 1f))
    val taglineT       = easeOutCubic(((t - 3.8f) / 0.55f).coerceIn(0f, 1f))
    val pulseT         = if (t > 2.8f) sin((t - 2.8f) * 3.5f) * 0.5f + 0.5f else 0f

    val dotPositions = PROVIDERS.mapIndexed { i, p ->
        Offset(lerp(p.x, CX, dotProgress[i]), lerp(p.y, CY, dotProgress[i]))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF030712))
            .graphicsLayer { alpha = screenAlpha },
        contentAlignment = Alignment.Center
    ) {
        // Ambient purple glow — intensifies during bloom and flash
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0.35f, 0.196f, 0.706f,
                                (0.05f + bloomOpacity * 0.09f + flashT * 0.14f).coerceIn(0f, 1f)),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val canvasSize = minOf(360.dp, maxWidth * 0.92f)

                Canvas(modifier = Modifier.size(canvasSize)) {
                    val s = size.width

                    // Subtle background grid rings
                    if (t > 0.5f) {
                        val gridAlpha = (((t - 0.5f) / 1.2f).coerceIn(0f, 1f) * 0.055f)
                        listOf(20f, 35f, 48f).forEach { r ->
                            drawCircle(
                                color = Color(0xFF6366F1).copy(alpha = gridAlpha),
                                radius = r / 100f * s,
                                center = Offset(CX * s, CY * s),
                                style = Stroke(width = 0.3f / 100f * s)
                            )
                        }
                    }

                    // Convergence flash burst
                    if (flashT > 0.01f) {
                        repeat(8) { idx ->
                            val angle = (2.0 * PI / 8 * idx + PI / 8).toFloat()
                            val rayLen = 30f / 100f * s * flashT
                            drawLine(
                                color = Color.White.copy(alpha = 0.60f * flashT),
                                start = Offset(CX * s, CY * s),
                                end = Offset(CX * s + cos(angle) * rayLen, CY * s + sin(angle) * rayLen),
                                strokeWidth = 1.1f / 100f * s
                            )
                        }
                        drawCircle(
                            color = Color(0xFFF0E6FF).copy(alpha = 0.75f * flashT),
                            radius = 7f / 100f * s * flashT,
                            center = Offset(CX * s, CY * s)
                        )
                    }

                    // Bloom — expanding ring with inner radial fill
                    if (bloomOpacity > 0f) {
                        val bRadius = lerp(4f / 100f * s, 46f / 100f * s, bloomT)
                        drawCircle(
                            color = Color(0xFFA78BFA).copy(alpha = 0.40f * bloomOpacity),
                            radius = bRadius.coerceAtLeast(1f),
                            center = Offset(CX * s, CY * s),
                            style = Stroke(width = 1.3f / 100f * s)
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFE9D5FF).copy(alpha = 0.20f * bloomOpacity),
                                    Color(0xFFA78BFA).copy(alpha = 0.08f * bloomOpacity),
                                    Color.Transparent
                                ),
                                center = Offset(CX * s, CY * s),
                                radius = bRadius.coerceAtLeast(1f)
                            ),
                            radius = bRadius.coerceAtLeast(1f),
                            center = Offset(CX * s, CY * s)
                        )
                    }

                    // Dashed trail lines from origin to current dot position
                    PROVIDERS.forEachIndexed { i, p ->
                        val alpha = dotAppear[i] * (1f - dotProgress[i] * 0.65f) * 0.70f
                        if (alpha < 0.01f) return@forEachIndexed
                        val pos = dotPositions[i]
                        drawDashedLine(
                            color = p.color.copy(alpha = alpha),
                            start = Offset(p.x * s, p.y * s),
                            end = Offset(pos.x * s, pos.y * s),
                            strokeWidth = 0.6f / 100f * s,
                            dashLength = 1.4f / 100f * s,
                            gapLength = 1.4f / 100f * s
                        )
                    }

                    // Provider dots and labels
                    PROVIDERS.forEachIndexed { i, p ->
                        val appear = dotAppear[i]
                        val prog   = dotProgress[i]
                        val pos    = dotPositions[i]
                        if (appear < 0.01f) return@forEachIndexed
                        val fade       = 1f - prog.pow(3)
                        val groupAlpha = appear * fade
                        val r          = lerp(4.5f, 2.0f, prog) / 100f * s

                        drawCircleGlow(
                            center = Offset(pos.x * s, pos.y * s),
                            radius = r * 2.8f,
                            color = p.color.copy(alpha = 0.12f * groupAlpha),
                            blurRadius = 2.0f / 100f * s
                        )
                        drawCircleGlow(
                            center = Offset(pos.x * s, pos.y * s),
                            radius = r,
                            color = p.color.copy(alpha = 0.96f * groupAlpha),
                            blurRadius = 1.0f / 100f * s
                        )

                        if (prog < 0.42f && appear > 0.4f) {
                            val textAlpha = ((1f - prog * 2.4f).coerceAtLeast(0f) * appear)
                            if (textAlpha > 0.01f) {
                                val dx  = p.x - CX; val dy = p.y - CY
                                val mag = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                                val lx  = (pos.x + (dx / mag) * 11f / 100f) * s
                                val ly  = (pos.y + (dy / mag) * 11f / 100f) * s
                                drawIntoCanvas { canvas ->
                                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        color        = p.color.copy(alpha = textAlpha).toArgb()
                                        textSize     = 5.0f / 100f * s
                                        textAlign    = android.graphics.Paint.Align.CENTER
                                        typeface     = Typeface.MONOSPACE
                                        isFakeBoldText = true
                                    }
                                    canvas.nativeCanvas.drawText(p.label, lx, ly + 1.4f / 100f * s, paint)
                                }
                            }
                        }
                    }

                    // Diamond gate
                    if (diamondOpacity > 0.01f) {
                        val dScale    = lerp(0.1f, 1f, diamondT)
                        val cx        = CX * s
                        val cy        = CY * s
                        val half      = 15f / 100f * s * dScale
                        val outerHalf = 21f / 100f * s * dScale

                        // Outer glow diamond
                        drawPath(
                            path = diamondPath(cx, cy, outerHalf),
                            color = Color(0xFFA78BFA).copy(alpha = (0.22f + pulseT * 0.22f) * diamondOpacity),
                            style = Stroke(width = 0.4f / 100f * s)
                        )

                        // Inner filled diamond
                        drawPath(
                            path = diamondPath(cx, cy, half),
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFF0E6FF),
                                    Color(0xFFA78BFA),
                                    Color(0xFF4C1D95).copy(alpha = 0.3f)
                                ),
                                center = Offset(cx - half * 0.23f, cy - half * 0.37f),
                                radius = half * 1.4f
                            ),
                            alpha = diamondOpacity
                        )
                        drawPath(
                            path = diamondPath(cx, cy, half),
                            color = Color(0xFFA78BFA).copy(alpha = 0.90f * diamondOpacity),
                            style = Stroke(width = 0.8f / 100f * s)
                        )

                        // Inner cross-facet lines
                        val crossAlpha = 0.20f * diamondOpacity
                        drawLine(Color(0xFFE9D5FF).copy(alpha = crossAlpha),
                            Offset(cx, cy - half), Offset(cx, cy + half), 0.4f / 100f * s)
                        drawLine(Color(0xFFE9D5FF).copy(alpha = crossAlpha),
                            Offset(cx - half, cy), Offset(cx + half, cy), 0.4f / 100f * s)

                        // Specular face
                        drawPath(
                            path = Path().apply {
                                moveTo(cx, cy - half)
                                lineTo(cx + half * 0.10f, cy)
                                lineTo(cx, cy + half * 0.93f)
                                lineTo(cx - half * 0.10f, cy)
                                close()
                            },
                            color = Color.White.copy(alpha = 0.07f * diamondOpacity)
                        )

                        // Highlight ellipse (top-left inner)
                        val ew = 11f / 100f * s * dScale
                        val eh = 6.4f / 100f * s * dScale
                        drawOval(
                            color = Color.White.copy(alpha = 0.16f * diamondOpacity),
                            topLeft = Offset(
                                cx - 3.5f / 100f * s * dScale - ew / 2f,
                                cy - 5.5f / 100f * s * dScale - eh / 2f
                            ),
                            size = Size(ew, eh)
                        )

                        // 3 pulsing concentric rings
                        listOf(9f, 13f, 18f).forEachIndexed { ri, rr ->
                            drawCircle(
                                color = Color(0xFFA78BFA).copy(alpha = (0.20f - ri * 0.04f) * diamondOpacity),
                                radius = (rr + pulseT * 1.8f) / 100f * s,
                                center = Offset(cx, cy),
                                style = Stroke(width = 0.35f / 100f * s)
                            )
                        }

                        // Edge lines
                        val edgeAlpha = (0.38f + pulseT * 0.22f) * diamondOpacity
                        listOf(
                            Offset(cx, cy - half) to Offset(cx + half, cy),
                            Offset(cx + half, cy) to Offset(cx, cy + half),
                            Offset(cx, cy + half) to Offset(cx - half, cy),
                            Offset(cx - half, cy) to Offset(cx, cy - half),
                        ).forEach { (a, b) ->
                            drawLine(Color(0xFFE9D5FF).copy(alpha = edgeAlpha), a, b,
                                strokeWidth = 0.55f / 100f * s)
                        }

                        // Center bright point
                        drawCircleGlow(
                            center = Offset(cx, cy),
                            radius = (2.2f + pulseT * 0.8f) / 100f * s,
                            color = Color(0xFFF0E6FF).copy(alpha = diamondOpacity),
                            blurRadius = 2.0f / 100f * s
                        )
                    }

                    // Corner tick marks
                    if (diamondOpacity > 0.3f) {
                        val tickAlpha = (diamondOpacity - 0.3f) / 0.7f
                        val tickColor = Color(0xFFA78BFA).copy(alpha = 0.32f * tickAlpha)
                        listOf(
                            listOf(3f, 3f, 9f, 3f, 3f, 3f, 3f, 9f),
                            listOf(91f, 3f, 97f, 3f, 97f, 3f, 97f, 9f),
                            listOf(3f, 91f, 9f, 91f, 3f, 91f, 3f, 97f),
                            listOf(91f, 97f, 97f, 97f, 97f, 91f, 97f, 97f),
                        ).forEach { c ->
                            drawLine(tickColor,
                                Offset(c[0]/100f*s, c[1]/100f*s),
                                Offset(c[2]/100f*s, c[3]/100f*s), 0.45f/100f*s)
                            drawLine(tickColor,
                                Offset(c[4]/100f*s, c[5]/100f*s),
                                Offset(c[6]/100f*s, c[7]/100f*s), 0.45f/100f*s)
                        }
                    }
                }
            }

            // App name
            if (appNameT > 0.01f) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "NEXUS MIND",
                    fontFamily = SpaceMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    letterSpacing = 8.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = appNameT
                            translationY = lerp(12f * density, 0f, appNameT)
                        }
                )
            }

            // Tagline
            if (taglineT > 0.01f) {
                Spacer(Modifier.height(10.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = taglineT
                            translationY = lerp(12f * density, 0f, taglineT)
                        }
                ) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = Color.White)) { append("One App. ") }
                            withStyle(
                                SpanStyle(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFFA78BFA),
                                            Color(0xFFE879F9),
                                            Color(0xFFf97316)
                                        )
                                    )
                                )
                            ) { append("All Models.") }
                        },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = DmSansFamily,
                        letterSpacing = (-0.3).sp,
                        lineHeight = 32.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "MANY PATHS · ONE DOOR",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        letterSpacing = 3.sp,
                        fontFamily = SpaceMonoFamily,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private fun diamondPath(cx: Float, cy: Float, half: Float): Path = Path().apply {
    moveTo(cx, cy - half)
    lineTo(cx + half, cy)
    lineTo(cx, cy + half)
    lineTo(cx - half, cy)
    close()
}

private fun DrawScope.drawCircleGlow(
    center: Offset, radius: Float, color: Color, blurRadius: Float
) {
    val steps = 4
    for (i in steps downTo 1) {
        val ratio = i.toFloat() / steps
        val r = (radius + blurRadius * ratio).coerceAtLeast(0f)
        val a = (color.alpha * (1f - ratio) * 0.55f).coerceIn(0f, 1f)
        drawCircle(color.copy(alpha = a), r, center)
    }
    drawCircle(color, radius.coerceAtLeast(0f), center)
}

private fun DrawScope.drawDashedLine(
    color: Color, start: Offset, end: Offset,
    strokeWidth: Float, dashLength: Float, gapLength: Float
) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color       = color.toArgb()
            this.strokeWidth = strokeWidth
            style            = android.graphics.Paint.Style.STROKE
            pathEffect       = DashPathEffect(floatArrayOf(dashLength, gapLength), 0f)
        }
        canvas.nativeCanvas.drawLine(start.x, start.y, end.x, end.y, paint)
    }
}
