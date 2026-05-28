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
import androidx.compose.ui.geometry.Offset
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
    onDiscussion: () -> Unit = {},
) {
    val pulseTransition = rememberInfiniteTransition(label = "pulse")

    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
        label = "pulse"
    )

    var card1Visible by remember { mutableStateOf(false) }
    var cardDiscussionVisible by remember { mutableStateOf(false) }
    var card2Visible by remember { mutableStateOf(false) }
    var card3Visible by remember { mutableStateOf(false) }
    var card4Visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250L); card1Visible = true
        delay(130L); cardDiscussionVisible = true
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
                    .glowShadow(Color(0x40E8834A), 24.dp, 20.dp)
                    .background(Color(0xFF151518), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF2A2A30), RoundedCornerShape(20.dp)),
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
                color = NexusText,
                fontFamily = SyneFamily,
            )

            Spacer(Modifier.height(8.dp))

            // Pulsing status pill
            Box(
                modifier = Modifier
                    .background(NexusAccentDim, RoundedCornerShape(99.dp))
                    .border(1.dp, Color(0x40E8834A), RoundedCornerShape(99.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                NexusAccent.copy(alpha = pulseAlpha),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "All systems online",
                        fontSize = 12.sp,
                        color = NexusAccent,
                        fontFamily = DmSansFamily,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(44.dp))

            // Section label
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "MENU",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    color = NexusText3,
                    fontFamily = JetBrainsMonoFamily,
                    letterSpacing = 2.5.sp,
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(NexusBorder)
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
                    accentColor = NexusAccent,
                    onClick = onChatBox,
                    iconContent = { ChatBoxIcon() }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Card — Discussion (multi-model debate)
            AnimatedVisibility(
                visible = cardDiscussionVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOutCubic)) { it / 3 }
            ) {
                MenuCard(
                    title = "Discussion",
                    subtitle = "Have 2–4 models debate a topic",
                    accentColor = NexusAmber,
                    onClick = onDiscussion,
                    iconContent = { DiscussionIcon() }
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
                    accentColor = NexusBlue,
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
                    accentColor = Color(0xFF9575CD),
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
                    accentColor = NexusGreen,
                    onClick = onProjects,
                    iconContent = { ProjectsIcon() }
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

// ── Icon tile composables ──────────────────────────────────────────────────────

// Shared neutral icon container — NexusSurface2 bg + NexusBorder2 border
@Composable
private fun IconBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .background(NexusSurface2, RoundedCornerShape(13.dp))
            .border(1.dp, NexusBorder2, RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun BoxScope.ChatBoxIcon() {
    IconBox {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
            val s = size
            // Scale factor: icon is drawn on a 17-unit viewBox mapped to s.width
            val scx = s.width / 17f
            val scy = s.height / 17f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.6f * scx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
            // Chat bubble outline: M2.5,3 h12 a1,1 0 0 1 1,1 v6.5 a1,1 0 0 1-1,1 H5.5 l-3,2.5 V4 a1,1 0 0 1 1-1 z
            val bubble = Path().apply {
                moveTo(2.5f * scx, 3f * scy)
                lineTo(14.5f * scx, 3f * scy)
                // top-right rounded corner arc approximated with quadratic
                quadraticTo(15.5f * scx, 3f * scy, 15.5f * scx, 4f * scy)
                lineTo(15.5f * scx, 10.5f * scy)
                quadraticTo(15.5f * scx, 11.5f * scy, 14.5f * scx, 11.5f * scy)
                lineTo(5.5f * scx, 11.5f * scy)
                lineTo(2.5f * scx, 14f * scy)
                lineTo(2.5f * scx, 4f * scy)
                quadraticTo(2.5f * scx, 3f * scy, 3.5f * scx, 3f * scy)
                close()
            }
            drawPath(bubble, NexusAccent, style = stroke)
            // Three dots inside: cx=6,7  cx=8.5,7  cx=11,7 — r=1 filled
            val dotR = 1f * scx
            drawCircle(NexusAccent, dotR, center = Offset(6f * scx, 7f * scy))
            drawCircle(NexusAccent, dotR, center = Offset(8.5f * scx, 7f * scy))
            drawCircle(NexusAccent, dotR, center = Offset(11f * scx, 7f * scy))
        }
    }
}

@Composable
private fun BoxScope.TestingIcon() {
    IconBox {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
            val s = size
            val scx = s.width / 17f
            val scy = s.height / 17f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.6f * scx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
            // ECG/pulse: 1,8.5  4,8.5  5.5,5  7,12  9,3  11,14  12.5,8.5  16,8.5
            val ecg = Path().apply {
                moveTo(1f * scx, 8.5f * scy)
                lineTo(4f * scx, 8.5f * scy)
                lineTo(5.5f * scx, 5f * scy)
                lineTo(7f * scx, 12f * scy)
                lineTo(9f * scx, 3f * scy)
                lineTo(11f * scx, 14f * scy)
                lineTo(12.5f * scx, 8.5f * scy)
                lineTo(16f * scx, 8.5f * scy)
            }
            drawPath(ecg, NexusText2, style = stroke)
        }
    }
}

@Composable
private fun BoxScope.SettingsIcon() {
    IconBox {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
            val s = size
            val scx = s.width / 17f
            val scy = s.height / 17f
            val lineStroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.6f * scx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
            val circleStroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.6f * scx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // Horizontal line 1: x1=2 y1=5 x2=15 y2=5
            val line1 = Path().apply {
                moveTo(2f * scx, 5f * scy)
                lineTo(15f * scx, 5f * scy)
            }
            drawPath(line1, NexusText2, style = lineStroke)
            // Horizontal line 2: x1=2 y1=12 x2=15 y2=12
            val line2 = Path().apply {
                moveTo(2f * scx, 12f * scy)
                lineTo(15f * scx, 12f * scy)
            }
            drawPath(line2, NexusText2, style = lineStroke)
            // Open circle 1: cx=6 cy=5 r=2 (stroke only)
            drawCircle(NexusText2, 2f * scx, center = Offset(6f * scx, 5f * scy), style = circleStroke)
            // Open circle 2: cx=11 cy=12 r=2 (stroke only)
            drawCircle(NexusText2, 2f * scx, center = Offset(11f * scx, 12f * scy), style = circleStroke)
        }
    }
}

@Composable
private fun BoxScope.ProjectsIcon() {
    IconBox {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
            val s = size
            val scx = s.width / 17f
            val scy = s.height / 17f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.6f * scx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
            // Folder: M1.5,4.5 C1.5,3.7 2.2,3 3,3 H6.5 L8,4.5 H13 C13.8,4.5 14.5,5.2 14.5,6 V12
            //         C14.5,12.8 13.8,13.5 13,13.5 H3 C2.2,13.5 1.5,12.8 1.5,12 Z
            val folder = Path().apply {
                moveTo(1.5f * scx, 4.5f * scy)
                cubicTo(
                    1.5f * scx, 3.7f * scy,
                    2.2f * scx, 3f * scy,
                    3f * scx, 3f * scy
                )
                lineTo(6.5f * scx, 3f * scy)
                lineTo(8f * scx, 4.5f * scy)
                lineTo(13f * scx, 4.5f * scy)
                cubicTo(
                    13.8f * scx, 4.5f * scy,
                    14.5f * scx, 5.2f * scy,
                    14.5f * scx, 6f * scy
                )
                lineTo(14.5f * scx, 12f * scy)
                cubicTo(
                    14.5f * scx, 12.8f * scy,
                    13.8f * scx, 13.5f * scy,
                    13f * scx, 13.5f * scy
                )
                lineTo(3f * scx, 13.5f * scy)
                cubicTo(
                    2.2f * scx, 13.5f * scy,
                    1.5f * scx, 12.8f * scy,
                    1.5f * scx, 12f * scy
                )
                close()
            }
            drawPath(folder, NexusText2, style = stroke)
        }
    }
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
        targetValue = if (pressed) accentColor.copy(alpha = 0.10f) else NexusSurface,
        animationSpec = tween(150),
        label = "bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed) accentColor.copy(alpha = 0.45f) else NexusBorder,
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
        // Top-edge separator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NexusBorder2)
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
                    color = NexusText,
                    fontFamily = SyneFamily,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = NexusText2,
                    fontFamily = DmSansFamily,
                    lineHeight = 17.sp,
                )
            }

            Spacer(Modifier.width(8.dp))

            Image(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                colorFilter = ColorFilter.tint(NexusText3),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// Two overlapping speech bubbles — represents multi-speaker debate.
@Composable
private fun BoxScope.DiscussionIcon() {
    IconBox {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
            val s = size
            val sc = s.width / 20f
            val strokeBack = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.5f * sc,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
            // Back bubble — blue, top-right
            val back = Path().apply {
                moveTo(8f * sc, 2.5f * sc)
                lineTo(16.5f * sc, 2.5f * sc)
                quadraticTo(18f * sc, 2.5f * sc, 18f * sc, 4f * sc)
                lineTo(18f * sc, 9f * sc)
                quadraticTo(18f * sc, 10.5f * sc, 16.5f * sc, 10.5f * sc)
                lineTo(13f * sc, 10.5f * sc)
                lineTo(11.5f * sc, 12f * sc)
                lineTo(11.5f * sc, 10.5f * sc)
                lineTo(8f * sc, 10.5f * sc)
                quadraticTo(6.5f * sc, 10.5f * sc, 6.5f * sc, 9f * sc)
                lineTo(6.5f * sc, 4f * sc)
                quadraticTo(6.5f * sc, 2.5f * sc, 8f * sc, 2.5f * sc)
                close()
            }
            drawPath(back, NexusBlue, style = strokeBack)
            // Front bubble — amber, bottom-left, overlaps
            val front = Path().apply {
                moveTo(3.5f * sc, 8f * sc)
                lineTo(12f * sc, 8f * sc)
                quadraticTo(13.5f * sc, 8f * sc, 13.5f * sc, 9.5f * sc)
                lineTo(13.5f * sc, 14.5f * sc)
                quadraticTo(13.5f * sc, 16f * sc, 12f * sc, 16f * sc)
                lineTo(7f * sc, 16f * sc)
                lineTo(5f * sc, 17.5f * sc)
                lineTo(5f * sc, 16f * sc)
                lineTo(3.5f * sc, 16f * sc)
                quadraticTo(2f * sc, 16f * sc, 2f * sc, 14.5f * sc)
                lineTo(2f * sc, 9.5f * sc)
                quadraticTo(2f * sc, 8f * sc, 3.5f * sc, 8f * sc)
                close()
            }
            drawPath(front, NexusAccent, style = strokeBack)
        }
    }
}
