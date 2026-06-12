package com.claudecodesetup.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser

// ─── Glassmorphism icon system (frosted amber-milky) ─────────────────────────
// Frameless icons: a soft amber underglow + sharp warm-gradient accent shapes +
// a MILKY frosted-glass layer (warm-white vertical gradient + bright edge) +
// white detail strokes. Two-layer technique from the reference glass icon set,
// retinted to the app's amber house style. All geometry is authored in a 0..100
// viewBox (same as the !icons WebView preview) and parsed via PathParser, so the
// shapes match the approved preview 1:1.

// Path-data builders (PathParser only parses `d` strings — no rect/circle elems).
private fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float): String {
    val x2 = x + w; val y2 = y + h
    return "M${x + r} $y L${x2 - r} $y A$r $r 0 0 1 $x2 ${y + r} " +
           "L$x2 ${y2 - r} A$r $r 0 0 1 ${x2 - r} $y2 " +
           "L${x + r} $y2 A$r $r 0 0 1 $x ${y2 - r} " +
           "L$x ${y + r} A$r $r 0 0 1 ${x + r} $y Z"
}
private fun circ(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} $cy A$r $r 0 1 0 ${cx + r} $cy A$r $r 0 1 0 ${cx - r} $cy Z"

// Neutral white-frost palette (0..100 gradient space). The icons are now
// accent-agnostic monochrome glass — the per-feature colour lives on the
// surrounding icon box (border + glow), so the same glyph reads correctly under
// amber / cyan / rose / indigo / emerald cards. Geometry is unchanged.
private val GlassMilk = Brush.verticalGradient(
    listOf(Color(0x82FFFFFF), Color(0x2EFFFFFF)), startY = 4f, endY = 96f,
)
private val GlassEdge = SolidColor(Color(0xA6FFFFFF))
private val WhiteInk  = SolidColor(Color(0xF2FFFFFF))
private val AmberGrad = Brush.linearGradient(
    listOf(Color(0xF2FFFFFF), Color(0xB3FFFFFF)), start = Offset(20f, 18f), end = Offset(82f, 84f),
)
private val GoldGrad = Brush.linearGradient(
    listOf(Color(0xF2FFFFFF), Color(0xCCFFFFFF)), start = Offset(20f, 18f), end = Offset(82f, 84f),
)
private val CoralGrad = Brush.linearGradient(
    listOf(Color(0xF2FFFFFF), Color(0xC2FFFFFF)), start = Offset(18f, 16f), end = Offset(84f, 86f),
)
private val HoleInk = SolidColor(Color(0x99000000))

private fun DrawScope.fillP(d: String, brush: Brush, alpha: Float = 1f) {
    drawPath(PathParser().parsePathString(d).toPath(), brush, alpha = alpha)
}
private fun DrawScope.strokeP(d: String, brush: Brush, w: Float, alpha: Float = 1f) {
    drawPath(
        PathParser().parsePathString(d).toPath(), brush, alpha = alpha,
        style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
// Frosted glass shape = milky fill + bright edge in one call.
private fun DrawScope.glass(d: String) { fillP(d, GlassMilk); strokeP(d, GlassEdge, 1.4f) }

/** Renders a frosted-glass icon: amber underglow + the [draw] block (authored in
 *  a 0..100 viewBox). No frame/tile — drop it straight into a layout. */
@Composable
fun GlassIcon(
    modifier: Modifier = Modifier,
    glow: Color = Color(0x70FFB24D),
    draw: DrawScope.() -> Unit,
) {
    Canvas(modifier) {
        val r = size.minDimension
        val gc = Offset(size.width * 0.5f, size.height * 0.54f)
        drawCircle(
            brush = Brush.radialGradient(listOf(glow, Color.Transparent), center = gc, radius = r * 0.55f),
            radius = r * 0.55f, center = gc,
        )
        scale(size.width / 100f, size.height / 100f, pivot = Offset.Zero) { draw() }
    }
}

// ── Icon definitions (match the approved !icons preview, amber-tinted) ───────
val IconTerminal: DrawScope.() -> Unit = {
    fillP(rrect(42f, 12f, 46f, 40f, 12f), AmberGrad, 0.92f)
    glass(rrect(14f, 24f, 58f, 54f, 14f))
    strokeP("M26 44 L36 53 L26 62", WhiteInk, 2.6f)
    strokeP("M44 62 L60 62", WhiteInk, 2.6f)
}

val IconQuickAsk: DrawScope.() -> Unit = {
    strokeP(circ(68f, 58f, 15f), CoralGrad, 7f)
    strokeP("M79 69 L90 80", CoralGrad, 7f)
    glass(rrect(12f, 20f, 50f, 40f, 13f))
    strokeP("M22 34 L42 34", WhiteInk, 2.6f)
    strokeP("M22 46 L38 46", WhiteInk, 2.6f)
    fillP(circ(16f, 72f, 7f), GoldGrad)
}

val IconDiscussion: DrawScope.() -> Unit = {
    fillP("M40 22 h36 a10 10 0 0 1 10 10 v14 a10 10 0 0 1 -10 10 H58 l-12 10 v-10 H40 a10 10 0 0 1 -10 -10 V32 a10 10 0 0 1 10 -10 Z", AmberGrad, 0.92f)
    fillP("M12 14 h40 a10 10 0 0 1 10 10 v14 a10 10 0 0 1 -10 10 H32 l-12 10 v-10 H12 a10 10 0 0 1 -10 -10 V24 a10 10 0 0 1 10 -10 Z", GlassMilk)
    strokeP("M12 14 h40 a10 10 0 0 1 10 10 v14 a10 10 0 0 1 -10 10 H32 l-12 10 v-10 H12 a10 10 0 0 1 -10 -10 V24 a10 10 0 0 1 10 -10 Z", GlassEdge, 1.4f)
    fillP(circ(22f, 31f, 3.3f), WhiteInk)
    fillP(circ(32f, 31f, 3.3f), WhiteInk)
    fillP(circ(42f, 31f, 3.3f), WhiteInk)
}

val IconSettings: DrawScope.() -> Unit = {
    fillP(circ(50f, 50f, 22f), AmberGrad, 0.5f)
    for (i in 0..7) {
        rotate(i * 45f, pivot = Offset(50f, 50f)) { glass(rrect(44f, 6f, 12f, 17f, 3f)) }
    }
    glass(circ(50f, 50f, 26f))
    fillP(circ(50f, 50f, 11f), HoleInk)
}

val IconTesting: DrawScope.() -> Unit = {
    glass(rrect(14f, 16f, 72f, 68f, 14f))
    strokeP("M24 52 L36 52 L42 38 L50 66 L58 30 L64 52 L76 52", CoralGrad, 3.4f)
}

val IconUsage: DrawScope.() -> Unit = {
    glass(rrect(14f, 16f, 72f, 68f, 14f))
    fillP(rrect(26f, 54f, 9f, 18f, 3f), GoldGrad)
    fillP(rrect(45f, 42f, 9f, 30f, 3f), AmberGrad)
    fillP(rrect(64f, 30f, 9f, 42f, 3f), CoralGrad)
}

val IconDungeon: DrawScope.() -> Unit = {
    glass(rrect(22f, 48f, 56f, 34f, 4f))     // main wall
    glass(rrect(14f, 40f, 16f, 42f, 3f))     // left tower
    glass(rrect(70f, 40f, 16f, 42f, 3f))     // right tower
    glass(rrect(38f, 28f, 24f, 54f, 3f))     // center keep
    // merlons
    glass(rrect(14f, 34f, 5f, 7f, 1f)); glass(rrect(25f, 34f, 5f, 7f, 1f))
    glass(rrect(70f, 34f, 5f, 7f, 1f)); glass(rrect(81f, 34f, 5f, 7f, 1f))
    glass(rrect(38f, 22f, 6f, 7f, 1f)); glass(rrect(47f, 22f, 6f, 7f, 1f)); glass(rrect(56f, 22f, 6f, 7f, 1f))
    // amber gate arch
    fillP("M44 82 V64 a6 6 0 0 1 12 0 V82 Z", AmberGrad, 0.9f)
}

val IconSettingsGear = IconSettings   // alias for clarity at call sites
