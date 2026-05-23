package com.claudecodesetup.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.claudecodesetup.R
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    appName: String,
    onChatBox: () -> Unit,
    onTesting: () -> Unit,
    onSettings: () -> Unit,
    onProjects: () -> Unit = {},
) {
    val pulseTransition = rememberInfiniteTransition(label = "pulse")

    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
        label = "pulse"
    )

    var card1Visible by remember { mutableStateOf(false) }
    var card2Visible by remember { mutableStateOf(false) }
    var card3Visible by remember { mutableStateOf(false) }
    var card4Visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250L); card1Visible = true
        delay(130L); card2Visible = true
        delay(130L); card3Visible = true
        delay(130L); card4Visible = true
    }

    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))

            // App icon — loaded as Bitmap to support adaptive icons (API 26+)
            val context = LocalContext.current
            val appIconBitmap = remember {
                try {
                    val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                    val px = 192
                    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                    val canvas = AndroidCanvas(bmp)
                    drawable?.setBounds(0, 0, px, px)
                    drawable?.draw(canvas)
                    bmp
                } catch (_: Exception) { null }
            }
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .glowShadow(Color(0x408B5CF6), 24.dp, 20.dp)
                    .background(Color(0x14FFFFFF), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0x20FFFFFF), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (appIconBitmap != null) {
                    Image(
                        bitmap = appIconBitmap.asImageBitmap(),
                        contentDescription = "App icon",
                        modifier = Modifier.size(54.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = appName,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFF0EEFF),
                fontFamily = SyneFamily,
            )

            Spacer(Modifier.height(8.dp))

            // Pulsing status row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            Color(0xFF22C55E).copy(alpha = pulseAlpha),
                            CircleShape
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "All systems online",
                    fontSize = 12.sp,
                    color = Color(0xFF86EFAC).copy(alpha = 0.8f),
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(44.dp))

            // Section label — JetBrains Mono style with gradient line
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "MENU",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF7C6FAA),
                    fontFamily = JetBrainsMonoFamily,
                    letterSpacing = 2.5.sp,
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0x407C6FAA), Color.Transparent)
                            )
                        )
                )
            }

            Spacer(Modifier.height(12.dp))

            // Card 1 — Chat Box
            AnimatedVisibility(
                visible = card1Visible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOutCubic)) { it / 3 }
            ) {
                MenuCard(
                    title = "Chat Box",
                    subtitle = "Start a conversation with AI",
                    accentColor = Color(0xFF8B5CF6),
                    onClick = onChatBox,
                    iconContent = { ChatBoxIcon() }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Card 2 — Testing Response
            AnimatedVisibility(
                visible = card2Visible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOutCubic)) { it / 3 }
            ) {
                MenuCard(
                    title = "Testing Response",
                    subtitle = "Check if the free AI model is responding",
                    accentColor = Color(0xFF22D3EE),
                    onClick = onTesting,
                    iconContent = { TestingIcon() }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Card 3 — Settings
            AnimatedVisibility(
                visible = card3Visible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOutCubic)) { it / 3 }
            ) {
                MenuCard(
                    title = "Setting",
                    subtitle = "Manage your preferences & app options",
                    accentColor = Color(0xFF8B5CF6),
                    onClick = onSettings,
                    iconContent = { SettingsIcon() }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Card 4 — Projects
            AnimatedVisibility(
                visible = card4Visible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOutCubic)) { it / 3 }
            ) {
                MenuCard(
                    title = "Projects",
                    subtitle = "Switch between saved project workspaces",
                    accentColor = Color(0xFF22C55E),
                    onClick = onProjects,
                    iconContent = { ProjectsIcon() }
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

// ── Icon tile composables ──────────────────────────────────────────────────────

@Composable
private fun BoxScope.ChatBoxIcon() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .drawBehind {
                // Gradient background
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF8B5CF6), Color(0xFF6D28D9))
                    ),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
                // Top shine overlay
                drawRoundRect(
                    color = Color(0x12FFFFFF),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height * 0.42f),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
                // Chat bubble body
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.93f),
                    topLeft = Offset(11.dp.toPx(), 9.dp.toPx()),
                    size = Size(34.dp.toPx(), 22.dp.toPx()),
                    cornerRadius = CornerRadius(5.dp.toPx())
                )
                // Bubble tail
                val path = Path().apply {
                    moveTo(14.dp.toPx(), 31.dp.toPx())
                    lineTo(11.dp.toPx(), 38.dp.toPx())
                    lineTo(22.dp.toPx(), 31.dp.toPx())
                    close()
                }
                drawPath(path, Color.White.copy(alpha = 0.93f))
                // 3 dots inside bubble
                drawCircle(Color(0xFF8B5CF6), 2.5.dp.toPx(), center = Offset(20.dp.toPx(), 20.dp.toPx()))
                drawCircle(Color(0xFF8B5CF6), 2.5.dp.toPx(), center = Offset(28.dp.toPx(), 20.dp.toPx()))
                drawCircle(Color(0xFF8B5CF6), 2.5.dp.toPx(), center = Offset(36.dp.toPx(), 20.dp.toPx()))
            }
    )
}

@Composable
private fun BoxScope.TestingIcon() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .drawBehind {
                // Gradient background
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF06B6D4), Color(0xFF0891B2))
                    ),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
                // Top shine overlay
                drawRoundRect(
                    color = Color(0x12FFFFFF),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height * 0.42f),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
                // ECG pulse wave
                val midY = size.height * 0.52f
                val path = Path().apply {
                    moveTo(6.dp.toPx(), midY)
                    lineTo(14.dp.toPx(), midY)
                    lineTo(18.dp.toPx(), midY - 10.dp.toPx())
                    lineTo(22.dp.toPx(), midY + 12.dp.toPx())
                    lineTo(26.dp.toPx(), midY - 16.dp.toPx())
                    lineTo(30.dp.toPx(), midY + 8.dp.toPx())
                    lineTo(34.dp.toPx(), midY)
                    lineTo(50.dp.toPx(), midY)
                }
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = 0.92f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
                // End dot with glow circle
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    radius = 5.dp.toPx(),
                    center = Offset(50.dp.toPx(), midY)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = 2.5.dp.toPx(),
                    center = Offset(50.dp.toPx(), midY)
                )
            }
    )
}

@Composable
private fun BoxScope.SettingsIcon() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .drawBehind {
                // Gradient background
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF7C3AED), Color(0xFF4C1D95))
                    ),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
                // Top shine overlay
                drawRoundRect(
                    color = Color(0x12FFFFFF),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height * 0.42f),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
                val trackColor = Color.White.copy(alpha = 0.35f)
                val thumbColor = Color.White.copy(alpha = 0.95f)
                val trackH = 2.dp.toPx()
                val thumbR = 4.dp.toPx()
                val left = 10.dp.toPx()
                val right = 46.dp.toPx()
                // Track 1 (y=16)
                val y1 = 16.dp.toPx()
                drawRoundRect(trackColor, topLeft = Offset(left, y1 - trackH / 2), size = Size(right - left, trackH), cornerRadius = CornerRadius(2.dp.toPx()))
                drawCircle(thumbColor, thumbR, center = Offset(24.dp.toPx(), y1))
                // Track 2 (y=28)
                val y2 = 28.dp.toPx()
                drawRoundRect(trackColor, topLeft = Offset(left, y2 - trackH / 2), size = Size(right - left, trackH), cornerRadius = CornerRadius(2.dp.toPx()))
                drawCircle(thumbColor, thumbR, center = Offset(36.dp.toPx(), y2))
                // Track 3 (y=40)
                val y3 = 40.dp.toPx()
                drawRoundRect(trackColor, topLeft = Offset(left, y3 - trackH / 2), size = Size(right - left, trackH), cornerRadius = CornerRadius(2.dp.toPx()))
                drawCircle(thumbColor, thumbR, center = Offset(20.dp.toPx(), y3))
            }
    )
}

@Composable
private fun BoxScope.ProjectsIcon() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .drawBehind {
                // Gradient background
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF10B981), Color(0xFF059669))
                    ),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
                // Top shine overlay
                drawRoundRect(
                    color = Color(0x12FFFFFF),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height * 0.42f),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
                val bodyColor = Color.White.copy(alpha = 0.92f)
                val lineColor = Color(0xFF059669)
                // Folder tab
                drawRoundRect(
                    color = bodyColor,
                    topLeft = Offset(10.dp.toPx(), 14.dp.toPx()),
                    size = Size(14.dp.toPx(), 5.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
                // Folder body
                drawRoundRect(
                    color = bodyColor,
                    topLeft = Offset(10.dp.toPx(), 18.dp.toPx()),
                    size = Size(36.dp.toPx(), 24.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
                // File lines inside
                val lineH = 2.dp.toPx()
                val lineLeft = 16.dp.toPx()
                val lineRight = 40.dp.toPx()
                drawRoundRect(lineColor, topLeft = Offset(lineLeft, 25.dp.toPx()), size = Size(lineRight - lineLeft, lineH), cornerRadius = CornerRadius(1.dp.toPx()))
                drawRoundRect(lineColor, topLeft = Offset(lineLeft, 30.dp.toPx()), size = Size(lineRight - lineLeft, lineH), cornerRadius = CornerRadius(1.dp.toPx()))
                drawRoundRect(lineColor, topLeft = Offset(lineLeft, 35.dp.toPx()), size = Size((lineRight - lineLeft) * 0.7f, lineH), cornerRadius = CornerRadius(1.dp.toPx()))
            }
    )
}

// ── MenuCard ───────────────────────────────────────────────────────────────────

@Composable
private fun MenuCard(
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit,
    iconContent: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }

    val bgColor by animateColorAsState(
        targetValue = if (pressed) accentColor.copy(alpha = 0.12f) else Color(0x09FFFFFF),
        animationSpec = tween(150),
        label = "bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed) accentColor.copy(alpha = 0.45f) else Color(0x12FFFFFF),
        animationSpec = tween(150),
        label = "border"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
    ) {
        // Top-edge shine strip
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0x18FFFFFF),
                            Color(0x18FFFFFF),
                            Color.Transparent
                        )
                    )
                )
                .align(Alignment.TopCenter)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Icon tile
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center,
                content = iconContent
            )

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF0EEFF),
                    fontFamily = SyneFamily,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF5B5880),
                    fontFamily = DmSansFamily,
                    lineHeight = 17.sp,
                )
            }

            Spacer(Modifier.width(8.dp))

            Image(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(0xFF3D3A5C)),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
