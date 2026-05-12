package com.claudecodesetup.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.R
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    appName: String,
    onChatBox: () -> Unit,
    onTesting: () -> Unit,
    onSettings: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "home")

    val orb1Y by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 40f,
        animationSpec = infiniteRepeatable(tween(4200, easing = EaseInOut), RepeatMode.Reverse),
        label = "orb1"
    )
    val orb2X by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = -30f,
        animationSpec = infiniteRepeatable(tween(5800, easing = EaseInOut), RepeatMode.Reverse),
        label = "orb2"
    )
    val orb3Y by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 25f,
        animationSpec = infiniteRepeatable(tween(7000, easing = EaseInOut), RepeatMode.Reverse),
        label = "orb3"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
        label = "pulse"
    )

    var card1Visible by remember { mutableStateOf(false) }
    var card2Visible by remember { mutableStateOf(false) }
    var card3Visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250L); card1Visible = true
        delay(130L); card2Visible = true
        delay(130L); card3Visible = true
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Background ─────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f   to Color(0xFF08041A),
                        0.45f to Color(0xFF0C0828),
                        1f   to Color(0xFF060210)
                    )
                )
        )

        // Purple orb — top-left, animated
        Box(
            modifier = Modifier
                .offset((-60).dp, orb1Y.dp)
                .size(340.dp)
                .background(
                    Brush.radialGradient(listOf(Color(0x4A7C3AED), Color.Transparent)),
                    CircleShape
                )
        )
        // Navy orb — bottom-right, animated
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(orb2X.dp, 0.dp)
                .size(280.dp)
                .background(
                    Brush.radialGradient(listOf(Color(0x301E40AF), Color.Transparent)),
                    CircleShape
                )
        )
        // Cyan orb — center-right, animated
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(80.dp, orb3Y.dp)
                .size(200.dp)
                .background(
                    Brush.radialGradient(listOf(Color(0x220E7490), Color.Transparent)),
                    CircleShape
                )
        )

        // Grid dot overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 38.dp.toPx()
            val cols = (size.width / step).toInt() + 2
            val rows = (size.height / step).toInt() + 2
            repeat(cols) { c ->
                repeat(rows) { r ->
                    drawCircle(
                        color = Color(0x0CFFFFFF),
                        radius = 1.2.dp.toPx(),
                        center = Offset(c * step, r * step)
                    )
                }
            }
        }

        // ── Content ────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(72.dp))

            // App icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color(0x1AFFFFFF), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0x25FFFFFF), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher),
                    contentDescription = "App icon",
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = appName,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFamily = DmSansFamily,
            )

            Spacer(Modifier.height(10.dp))

            // Pulsing status row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            Color(0xFF22C55E).copy(alpha = pulseAlpha),
                            CircleShape
                        )
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "All systems online",
                    fontSize = 13.sp,
                    color = Color(0xFF86EFAC),
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(52.dp))

            // Menu label
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "MENU",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF64748B),
                    fontFamily = DmSansFamily,
                    letterSpacing = 2.5.sp,
                )
            }

            Spacer(Modifier.height(14.dp))

            // Card 1 — Chat Box
            AnimatedVisibility(
                visible = card1Visible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOutCubic)) { it / 3 }
            ) {
                MenuCard(
                    iconRes = R.drawable.ic_chat,
                    title = "Chat Box",
                    subtitle = "Start a conversation with AI",
                    accentColor = Color(0xFF8B5CF6),
                    onClick = onChatBox,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Card 2 — Testing Response
            AnimatedVisibility(
                visible = card2Visible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOutCubic)) { it / 3 }
            ) {
                MenuCard(
                    iconRes = R.drawable.ic_bolt,
                    title = "Testing Response",
                    subtitle = "Check if the free AI model is responding",
                    accentColor = Color(0xFF06B6D4),
                    onClick = onTesting,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Card 3 — Setting
            AnimatedVisibility(
                visible = card3Visible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOutCubic)) { it / 3 }
            ) {
                MenuCard(
                    iconRes = R.drawable.ic_settings,
                    title = "Setting",
                    subtitle = "Manage your preferences & app options",
                    accentColor = Color(0xFF6D28D9),
                    onClick = onSettings,
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun MenuCard(
    iconRes: Int,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }

    val bgColor by animateColorAsState(
        targetValue = if (pressed) accentColor.copy(alpha = 0.18f) else Color(0x0FFFFFFF),
        animationSpec = tween(150),
        label = "bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed) accentColor.copy(alpha = 0.6f) else Color(0x1AFFFFFF),
        animationSpec = tween(150),
        label = "border"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
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
            .padding(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Icon pill
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(accentColor.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                    .border(1.dp, accentColor.copy(alpha = 0.28f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = title,
                    colorFilter = ColorFilter.tint(accentColor),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontFamily = DmSansFamily,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    fontFamily = DmSansFamily,
                    lineHeight = 17.sp,
                )
            }

            Spacer(Modifier.width(10.dp))

            Image(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(0xFF475569)),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
