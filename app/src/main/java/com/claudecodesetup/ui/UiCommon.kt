package com.claudecodesetup.ui

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font
import com.claudecodesetup.R

val GoogleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val DmSansFamily = FontFamily(
    Font(googleFont = GoogleFont("DM Sans"), fontProvider = GoogleFontProvider),
    Font(googleFont = GoogleFont("DM Sans"), fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("DM Sans"), fontProvider = GoogleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("DM Sans"), fontProvider = GoogleFontProvider, weight = FontWeight.Bold),
)

val SpaceMonoFamily = FontFamily(
    Font(googleFont = GoogleFont("Space Mono"), fontProvider = GoogleFontProvider),
    Font(googleFont = GoogleFont("Space Mono"), fontProvider = GoogleFontProvider, weight = FontWeight.Bold),
)

val SyneFamily = FontFamily(
    Font(googleFont = GoogleFont("Syne"), fontProvider = GoogleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Syne"), fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Syne"), fontProvider = GoogleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Syne"), fontProvider = GoogleFontProvider, weight = FontWeight.Bold),
    Font(googleFont = GoogleFont("Syne"), fontProvider = GoogleFontProvider, weight = FontWeight.ExtraBold),
)

val JetBrainsMonoFamily = FontFamily(
    Font(googleFont = GoogleFont("JetBrains Mono"), fontProvider = GoogleFontProvider, weight = FontWeight.Light),
    Font(googleFont = GoogleFont("JetBrains Mono"), fontProvider = GoogleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("JetBrains Mono"), fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
)

/** Draws a blurred glow shadow behind the composable using BlurMaskFilter. */
fun Modifier.glowShadow(color: Color, blurRadius: Dp, cornerRadius: Dp): Modifier =
    this.drawBehind {
        if (color.alpha < 0.01f) return@drawBehind
        drawIntoCanvas { canvas ->
            val paint = Paint()
            paint.asFrameworkPaint().apply {
                isAntiAlias = true
                this.color = android.graphics.Color.TRANSPARENT
                maskFilter = BlurMaskFilter(blurRadius.toPx(), BlurMaskFilter.Blur.NORMAL)
                this.color = color.toArgb()
            }
            canvas.drawRoundRect(
                left = 0f, top = 0f,
                right = size.width, bottom = size.height,
                radiusX = cornerRadius.toPx(), radiusY = cornerRadius.toPx(),
                paint = paint
            )
        }
    }

// Design system color tokens
// ── Nexus dark-frosted glassmorphism (2026-06-12) ───────────────────────────
// Pure-black base + neutral-white translucent frosted surfaces over a multi-hue
// ambient-orb aurora (cyan / amber / rose / emerald — NO purple). The surface &
// border tokens are deliberately COLOURLESS white-translucency so cards read as
// clean frosted glass and the per-feature accent (amber/cyan/rose/indigo/
// emerald) is what carries identity, not the card itself.
// (Prior amber-milky values archived in git history of feat/glass-ui.)
val NexusBg      = Color(0xFF000000)   // pure black base
val NexusSurface = Color(0x0BFFFFFF)   // rgba(255,255,255,0.045) — frosted card fill
val NexusSurface2 = Color(0x14FFFFFF)  // ~8% white — elevated frosted surface
val NexusBorder  = Color(0x17FFFFFF)   // rgba(255,255,255,0.09) — glass edge
val NexusBorder2 = Color(0x2EFFFFFF)   // ~18% white — stronger glass edge
// Overlay glass — for surfaces that float OVER live content (bottom sheets,
// dialogs, dropdowns). Near-opaque black so content behind can't bleed through
// and text stays readable; the faint translucency keeps a frosted edge.
val NexusOverlay = Color(0xF0050507)   // ~94% black frosted panel
val NexusAccent  = Color(0xFFFF8C42)   // amber — primary CTA / Chat Box accent
val NexusAccentDim = Color(0x22FF8C42)
val NexusGreen   = Color(0xFF10FFAB)   // emerald — success / online / Testing accent
val NexusGreenDim = Color(0x1410FFAB)
val NexusBlue    = Color(0xFF00D4FF)   // cyan — info / Quick Ask accent
val NexusAmber   = Color(0xFFFBBF24)   // warning (warm)
val NexusRed     = Color(0xFFFF4D6D)   // rose — error / Discussion accent
val NexusText    = Color(0xE6FFFFFF)   // white .9 — primary text
val NexusText2   = Color(0x73FFFFFF)   // white .45 — secondary / muted
val NexusText3   = Color(0x4DFFFFFF)   // white .30 — tertiary / section labels

/**
 * Tappable with a subtle press response: scales to [pressedScale] while held +
 * the platform ripple. Use everywhere a card/row/chip/tile is clickable so the
 * whole app gives consistent tactile feedback (not just the home tiles).
 */
fun Modifier.pressClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.975f,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(120), label = "pressScale",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
}

// Every screen that wraps in AppBackground now gets the aurora automatically.
@Composable
fun AppBackground(content: @Composable () -> Unit) = AuroraBackground(content)

// ── Glass design layer (feat/glass-ui) ──────────────────────────────────────
// A slow, color-rich "aurora" behind the UI + translucent frosted cards.
// On a soft aurora there is no sharp detail to frost, so a light translucent
// fill reads as glass with zero blur cost — smooth on every device (minSdk 29).
// Real backdrop-blur is reserved for surfaces where sharp content scrolls
// behind a panel (terminal/dungeon WebViews — free CSS blur there).

// Ambient-orb aurora hues — cyan / amber / rose / emerald ONLY (no purple).
// Soft, saturated blobs drifting over the pure-black base; the frosted white
// glass refracts them. Indigo is reserved as a per-feature card accent (Dungeon)
// and intentionally NOT used as an ambient orb.
private val AuroraCyan    = Color(0xFF00D4FF)
private val AuroraAmber   = Color(0xFFFF8C42)
private val AuroraRose    = Color(0xFFFF4D6D)
private val AuroraEmerald = Color(0xFF10FFAB)

// Glass surface tokens — colourless white frost (rgba spec values).
val GlassFill        = Color(0x0BFFFFFF)   // rgba(255,255,255,0.045) — frosted card fill
val GlassFillStrong  = Color(0x14FFFFFF)   // ~8% white — tiles / pills
val GlassStroke      = Color(0x17FFFFFF)   // rgba(255,255,255,0.09) — glass edge
val GlassStroke2     = Color(0x2EFFFFFF)   // ~18% white — stronger edge
val GlassSheen       = Color(0x0AFFFFFF)   // rgba(255,255,255,0.04) — top-left shimmer

/** Full-screen animated aurora over the base background. Drop-in replacement
 *  for [AppBackground] on glass screens. */
@Composable
fun AuroraBackground(content: @Composable () -> Unit) {
    val tr = rememberInfiniteTransition(label = "aurora")
    val drift by tr.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusBg)
            .drawBehind {
                val w = size.width; val h = size.height
                fun blob(cx: Float, cy: Float, rad: Float, color: Color) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color, Color.Transparent),
                            center = Offset(cx, cy), radius = rad,
                        ),
                        radius = rad, center = Offset(cx, cy),
                    )
                }
                // 5 ambient orbs (cyan/amber/rose/emerald) drifting slowly over
                // pure black — ~0.12 opacity each so they read as soft light, not
                // colour washes. The frosted white glass refracts them.
                blob(w * 0.14f, h * (0.08f + 0.05f * drift), w * 0.78f, AuroraCyan.copy(alpha = 0.13f))
                blob(w * 0.92f, h * (0.14f + 0.04f * drift), w * 0.62f, AuroraAmber.copy(alpha = 0.13f))
                blob(w * 0.88f, h * (0.90f - 0.05f * drift), w * 0.84f, AuroraRose.copy(alpha = 0.12f))
                blob(w * 0.08f, h * (0.94f - 0.04f * drift), w * 0.66f, AuroraEmerald.copy(alpha = 0.12f))
                blob(w * 0.50f, h * (0.46f + 0.06f * drift), w * 0.70f, AuroraCyan.copy(alpha = 0.07f))
            }
    ) {
        content()
    }
}

/** Translucent frosted card: light fill + hairline stroke + a soft top-left
 *  sheen so it reads as glass over the aurora. Brand icons placed inside are
 *  untouched. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    fill: Color = GlassFill,
    stroke: Color = GlassStroke,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .background(
                Brush.linearGradient(
                    colors = listOf(GlassSheen, Color.Transparent),
                    start = Offset.Zero, end = Offset(220f, 220f),
                )
            )
            .border(1.dp, stroke, shape),
        content = content,
    )
}

// ── Shared chat input bar ───────────────────────────────────────────────────
// Mirrors the terminal WebView composer (index.html #input-bar / #input-wrap /
// #send-btn) so the terminal, Quick Ask, and Discussion all share one look.
// The terminal-only keyboard toolbar and the mic/image/quick buttons are NOT
// part of this — screens that don't support them simply omit them.

private val InputWrapBg     = Color(0x0DFFFFFF)   // rgba(255,255,255,0.05)
private val InputWrapBorder = Color(0x1AFFFFFF)   // rgba(255,255,255,0.10)
private val InputBarTopLine = Color(0x12FFFFFF)   // rgba(255,255,255,0.07)

/** The bottom bar container: dark background + hairline top border. Place a
 *  [ChatTextField] and one or more [SendButton]/[BarButton]s inside. */
@Composable
fun ChatInputBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NexusBg)
            .drawBehind { drawRect(InputBarTopLine, size = Size(size.width, 1.dp.toPx())) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** Rounded, subtly-tinted text field with light (readable) text — matches the
 *  terminal's #input-wrap. Caller supplies `Modifier.weight(1f)`. */
@Composable
fun ChatTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = 5,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        placeholder = {
            Text(placeholder, fontFamily = SpaceMonoFamily, fontSize = 13.sp, color = NexusText3)
        },
        textStyle = TextStyle(fontFamily = SpaceMonoFamily, fontSize = 13.sp, color = Color.White),
        shape = RoundedCornerShape(14.dp),
        minLines = 1,
        maxLines = maxLines,
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = InputWrapBg,
            unfocusedContainerColor = InputWrapBg,
            disabledContainerColor = InputWrapBg,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = NexusText2,
            focusedBorderColor = NexusAccent,
            unfocusedBorderColor = InputWrapBorder,
            disabledBorderColor = InputWrapBorder,
            cursorColor = NexusAccent,
        ),
    )
}

/** Amber gradient send button with an up-arrow glyph — matches #send-btn. */
@Composable
fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val brush = if (enabled)
        Brush.linearGradient(listOf(Color(0xFFFF8C42), Color(0xFFE5612A)))
    else
        Brush.linearGradient(listOf(NexusBorder2, NexusBorder2))
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(brush, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("↑", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** Secondary pill button for the bar (Stop / Pass) — same height as SendButton. */
@Composable
fun BarButton(
    label: String,
    container: Color,
    contentColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .background(container, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = contentColor, fontFamily = DmSansFamily,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
