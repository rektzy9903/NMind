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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
    // Separate transitions so the fast pulse (900 ms) doesn't force slow orbs to recompose
    val orbTransition   = rememberInfiniteTransition(label = "orbs")
    val pulseTransition = rememberInfiniteTransition(label = "pulse")

    val orb1Y by orbTransition.animateFloat(
        initialValue = 0f, targetValue = 40f,
        animationSpec = infiniteRepeatable(tween(4200, easing = EaseInOut), RepeatMode.Reverse),
        label = "orb1"
    )
    val orb2X by orbTransition.animateFloat(
        initialValue = 0f, targetValue = -30f,
        animationSpec = infiniteRepeatable(tween(5800, easing = EaseInOut), RepeatMode.Reverse),
        label = "orb2"
    )
    val orb3Y by orbTransition.animateFloat(
        initialValue = 0f, targetValue = 25f,
        animationSpec = infiniteRepeatable(tween(7000, easing = EaseInOut), RepeatMode.Reverse),
        label = "orb3"
    )
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
                .size(320.dp)
                .background(
                    Brush.radialGradient(listOf(Color(0x357C3AED), Color.Transparent)),
                    CircleShape
                )
        )
        // Navy orb — bottom-right, animated
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(orb2X.dp, 0.dp)
                .size(260.dp)
                .background(
                    Brush.radialGradient(listOf(Color(0x221E40AF), Color.Transparent)),
                    CircleShape
                )
        )
        // Cyan orb — center-right, animated
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(80.dp, orb3Y.dp)
                .size(180.dp)
                .background(
                    Brush.radialGradient(listOf(Color(0x160E7490), Color.Transparent)),
                    CircleShape
                )
        )

        // ── Content ────────────────────────────────────────────────────────────
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
                    .glowShadow(Color(0x267C3AED), 20.dp, 20.dp)
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
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFamily = DmSansFamily,
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

            // Menu label
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "MENU",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF475569),
                    fontFamily = SpaceMonoFamily,
                    letterSpacing = 3.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

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

            Spacer(Modifier.height(12.dp))

            // Card 4 — Projects
            AnimatedVisibility(
                visible = card4Visible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOutCubic)) { it / 3 }
            ) {
                MenuCard(
                    iconRes = R.drawable.ic_settings,
                    title = "Projects",
                    subtitle = "Switch between saved project workspaces",
                    accentColor = Color(0xFF10B981),
                    onClick = onProjects,
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
        targetValue = if (pressed) accentColor.copy(alpha = 0.14f) else Color(0x0CFFFFFF),
        animationSpec = tween(150),
        label = "bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed) accentColor.copy(alpha = 0.5f) else Color(0x14FFFFFF),
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
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Icon pill
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(accentColor.copy(alpha = 0.13f), RoundedCornerShape(13.dp))
                    .border(1.dp, accentColor.copy(alpha = 0.22f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = title,
                    colorFilter = ColorFilter.tint(accentColor),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontFamily = DmSansFamily,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    fontFamily = DmSansFamily,
                    lineHeight = 17.sp,
                )
            }

            Spacer(Modifier.width(8.dp))

            Image(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(0xFF374151)),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
