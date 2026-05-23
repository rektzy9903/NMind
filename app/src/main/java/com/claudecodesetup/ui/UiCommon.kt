package com.claudecodesetup.ui

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

@Composable
fun AppBackground(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Base dark violet gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF07061A),
                            Color(0xFF0E0C28),
                            Color(0xFF07061A),
                        )
                    )
                )
        )
        // Violet orb — top-left
        Box(
            modifier = Modifier
                .offset((-80).dp, (-60).dp)
                .size(320.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x2E6D28D9), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        // Cyan orb — bottom-right
        Box(
            modifier = Modifier
                .offset(180.dp, 560.dp)
                .size(260.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x1A06B6D4), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        // Violet orb — center
        Box(
            modifier = Modifier
                .offset(60.dp, 280.dp)
                .size(200.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x147C3AED), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        content()
    }
}
