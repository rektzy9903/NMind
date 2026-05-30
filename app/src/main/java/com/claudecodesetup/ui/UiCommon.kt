package com.claudecodesetup.ui

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
val NexusBg      = Color(0xFF0C0C0F)
val NexusSurface = Color(0xFF151518)
val NexusSurface2 = Color(0xFF1E1E22)
val NexusBorder  = Color(0xFF2A2A30)
val NexusBorder2 = Color(0xFF3A3A42)
val NexusAccent  = Color(0xFFE8834A)   // amber — primary
val NexusAccentDim = Color(0x22E8834A)
val NexusGreen   = Color(0xFF3DD68C)
val NexusGreenDim = Color(0x183DD68C)
val NexusBlue    = Color(0xFF60A5FA)
val NexusAmber   = Color(0xFFFBBF24)
val NexusRed     = Color(0xFFF87171)
val NexusText    = Color(0xFFF0F0F2)
val NexusText2   = Color(0xFF9090A0)
val NexusText3   = Color(0xFF60606E)

@Composable
fun AppBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusBg)
    ) {
        content()
    }
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
        textStyle = TextStyle(fontFamily = SpaceMonoFamily, fontSize = 13.sp, color = NexusText),
        shape = RoundedCornerShape(14.dp),
        minLines = 1,
        maxLines = maxLines,
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = InputWrapBg,
            unfocusedContainerColor = InputWrapBg,
            disabledContainerColor = InputWrapBg,
            focusedTextColor = NexusText,
            unfocusedTextColor = NexusText,
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
        Brush.linearGradient(listOf(Color(0xFFE8834A), Color(0xFFC4632A)))
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
