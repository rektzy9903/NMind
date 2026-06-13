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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
    onDiscussion: () -> Unit = {},
    onQuickAsk: () -> Unit = {},
    onDungeon: () -> Unit = {},
) {
    val pulseTransition = rememberInfiniteTransition(label = "pulse")

    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
        label = "pulse"
    )

    var card1Visible by remember { mutableStateOf(false) }
    var cardQuickAskVisible by remember { mutableStateOf(false) }
    var cardDiscussionVisible by remember { mutableStateOf(false) }
    var cardDungeonVisible by remember { mutableStateOf(false) }
    var card2Visible by remember { mutableStateOf(false) }
    var card3Visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250L); card1Visible = true
        delay(110L); cardQuickAskVisible = true
        delay(110L); cardDiscussionVisible = true
        delay(110L); cardDungeonVisible = true
        delay(110L); card2Visible = true
        delay(110L); card3Visible = true
    }

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))

            // App logo — the Convergence Gate, drawn as a crisp Compose vector
            // (not the rasterized launcher icon, which left dead safe-zone padding)
            // and animated: energy pulses sweep inward along the spokes into a
            // breathing core. Sized to fill the tile properly.
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .glowShadow(Color(0x2E00D4FF), 16.dp, 22.dp),
                contentAlignment = Alignment.Center
            ) {
                ConvergenceLogo(modifier = Modifier.size(80.dp))
            }

            Spacer(Modifier.height(16.dp))

            // Title with the brand gradient (white → cyan → amber).
            Text(
                text = appName,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = SyneFamily,
                style = androidx.compose.ui.text.TextStyle(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFFFFFFFF), Color(0xFF00D4FF), Color(0xFFFF8C42))
                    )
                ),
            )

            Spacer(Modifier.height(8.dp))

            // Pulsing status pill — emerald (system-status colour).
            Box(
                modifier = Modifier
                    .background(NexusGreenDim, RoundedCornerShape(99.dp))
                    .border(1.dp, NexusGreen.copy(alpha = 0.25f), RoundedCornerShape(99.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .glowShadow(NexusGreen.copy(alpha = pulseAlpha), 6.dp, 3.dp)
                            .background(
                                NexusGreen.copy(alpha = pulseAlpha),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "All systems online",
                        fontSize = 12.sp,
                        color = NexusGreen,
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
                    iconContent = { GlassIcon(Modifier.size(34.dp), NexusAccent.copy(alpha = 0.45f), IconTerminal) }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Card — Quick Ask (native multi-provider chat)
            AnimatedVisibility(
                visible = cardQuickAskVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOutCubic)) { it / 3 }
            ) {
                MenuCard(
                    title = "Quick Ask",
                    subtitle = "Chat directly with any model — no tools",
                    accentColor = NexusBlue,
                    onClick = onQuickAsk,
                    iconContent = { GlassIcon(Modifier.size(34.dp), NexusBlue.copy(alpha = 0.45f), IconQuickAsk) }
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
                    accentColor = NexusRed,
                    onClick = onDiscussion,
                    iconContent = { GlassIcon(Modifier.size(34.dp), NexusRed.copy(alpha = 0.45f), IconDiscussion) }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Card — Dungeon (gamified project map)
            AnimatedVisibility(
                visible = cardDungeonVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400, easing = EaseOutCubic)) { it / 3 }
            ) {
                MenuCard(
                    title = "Dungeon",
                    subtitle = "Your project as a D&D map — hunt bugs, dispatch heroes",
                    accentColor = Color(0xFF6366F1),   // indigo (per-feature accent; not an ambient hue)
                    onClick = onDungeon,
                    iconContent = { GlassIcon(Modifier.size(34.dp), Color(0x736366F1), IconDungeon) }
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
                    accentColor = NexusGreen,
                    onClick = onTesting,
                    iconContent = { GlassIcon(Modifier.size(34.dp), NexusGreen.copy(alpha = 0.45f), IconTesting) }
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
                    accentColor = Color(0xFF8B93A7),   // neutral slate (no per-feature hue)
                    onClick = onSettings,
                    iconContent = { GlassIcon(Modifier.size(34.dp), Color(0x668B93A7), IconSettings) }
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
            .clip(RoundedCornerShape(13.dp))
            .background(GlassFillStrong)
            .border(1.dp, GlassStroke2, RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
        content = content
    )
}

// The app logo, drawn 1:1 from ic_launcher_foreground.xml (108×108, center 54,54)
// as a live Compose vector. Animation: a bright energy pulse sweeps inward along
// each of the 8 spokes (4 cardinal + 4 diagonal) into the core, which breathes —
// a literal rendering of "convergence", the app's namesake.
@Composable
private fun ConvergenceLogo(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "logo")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val breathe by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val accent = NexusAccent
    // Four "paths" (cyan / rose / amber / emerald — no purple) converging into the
    // amber core, indexed [top, right, bottom, left] to match `cardinals`. Mirrors
    // the splash animation; the tagline "many paths · one door" made literal.
    val pathColors = listOf(
        Color(0xFF00D4FF), Color(0xFFFF4D6D), Color(0xFFFF8C42), Color(0xFF10FFAB)
    )
    androidx.compose.foundation.Canvas(modifier = modifier) {
        // The artwork's structural extent is the cardinal tips at ±45 from center
        // (54). The 108 viewBox is an adaptive-icon box that carries ~9 units of dead
        // bleed-zone margin on every side — irrelevant here (we draw the full box, no
        // mask), so dividing by 108 left the logo filling only ~70% of the tile.
        // Scale by the content half-extent (45) and recenter explicitly so the tips
        // sit a small uniform margin from the canvas edge at any size. `fill` is the
        // one knob: 1.0 = tips touch the edge; lower = more breathing room.
        val fill = 0.90f
        val k = (size.minDimension / 2f) * fill / 45f
        val cx = size.width / 2f
        val cy = size.height / 2f
        fun p(x: Float, y: Float) = Offset(cx + (x - 54f) * k, cy + (y - 54f) * k)
        val c = p(54f, 54f)
        val capRound = androidx.compose.ui.graphics.StrokeCap.Round

        val cardinals = listOf(p(54f, 9f), p(99f, 54f), p(54f, 99f), p(9f, 54f))
        val diagonals = listOf(p(18f, 18f), p(90f, 18f), p(18f, 90f), p(90f, 90f))

        // Frosted glass pane (drawn first, behind the gate): diagonal white sheen →
        // faint warm tint, so the logo reads as a glass tile matching the reskin.
        val glassPane = Path().apply {
            val a = p(54f, 27f); val b = p(81f, 54f); val d = p(54f, 81f); val e = p(27f, 54f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); lineTo(e.x, e.y); close()
        }
        drawPath(
            glassPane,
            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                colors = listOf(
                    androidx.compose.ui.graphics.Color(0x2EFFFFFF),
                    androidx.compose.ui.graphics.Color(0x12FFFFFF),
                    accent.copy(alpha = 0.04f),
                ),
                start = p(34f, 34f), end = p(74f, 74f),
            ),
        )

        // Base spokes — diagonals faint white; cardinals carry their path hue.
        for (o in diagonals) drawLine(Color.White.copy(alpha = 0.14f), o, c, strokeWidth = 2.5f * k, cap = capRound)
        cardinals.forEachIndexed { i, o ->
            drawLine(pathColors[i].copy(alpha = 0.55f), o, c, strokeWidth = 3f * k, cap = capRound)
        }

        // Diamond outline — frosted white "door" (neutral, so paths read against it)
        val diamond = Path().apply {
            val a = p(54f, 27f); val b = p(81f, 54f); val d = p(54f, 81f); val e = p(27f, 54f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); lineTo(e.x, e.y); close()
        }
        drawPath(
            diamond, Color.White.copy(alpha = 0.62f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3f * k, join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )

        // Glass top-left edge highlight (catches the light)
        drawLine(
            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.30f),
            p(54f, 27f), p(27f, 54f), strokeWidth = 1.6f * k, cap = capRound,
        )

        // Tip dots — each its cardinal's path hue.
        listOf(p(54f, 27f), p(81f, 54f), p(54f, 81f), p(27f, 54f)).forEachIndexed { i, t ->
            drawCircle(pathColors[i].copy(alpha = 0.85f), 3.6f * k, t)
        }

        // Traveling convergence pulses — fade in then out (sin) so they're invisible
        // at the outer edge and at the core, brightest mid-spoke. No snap on loop.
        // Cardinal pulses carry the path hue; diagonal pulses are faint white.
        val pulseA = kotlin.math.sin(sweep * Math.PI).toFloat().coerceIn(0f, 1f)
        fun travel(o: Offset, col: Color, weight: Float, dotR: Float) {
            val pos = Offset(o.x + (c.x - o.x) * sweep, o.y + (c.y - o.y) * sweep)
            drawCircle(col.copy(alpha = 0.22f * pulseA * weight), dotR * 2.4f, pos)
            drawCircle(col.copy(alpha = 0.95f * pulseA * weight), dotR, pos)
        }
        cardinals.forEachIndexed { i, o -> travel(o, pathColors[i], 1f, 2.7f * k) }
        for (o in diagonals) travel(o, Color.White, 0.7f, 2.2f * k)

        // Center glow halo — breathes
        drawCircle(accent.copy(alpha = 0.15f + 0.20f * breathe), (9f + 4f * breathe) * k, c)
        // Center node — solid, slight pulse
        drawCircle(accent, (5.4f + 0.6f * breathe) * k, c)
    }
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
        targetValue = if (pressed) accentColor.copy(alpha = 0.16f) else GlassFill,
        animationSpec = tween(150),
        label = "bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed) accentColor.copy(alpha = 0.45f) else GlassStroke,
        animationSpec = tween(150),
        label = "border"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bgColor)
            .background(
                Brush.linearGradient(
                    colors = listOf(GlassSheen, Color.Transparent),
                    start = Offset.Zero, end = Offset(260f, 260f),
                )
            )
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            // Left-edge accent glow stripe — the per-feature identity marker.
            // Drawn from the card's real pixel size (drawBehind) to avoid the
            // unbounded-height constraint a fillMaxHeight child would hit inside
            // the scrolling column.
            .drawBehind {
                val inset = 14.dp.toPx()
                val w = 3.dp.toPx()
                val h = (size.height - inset * 2f).coerceAtLeast(0f)
                // faux outer glow (wider, translucent)
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.22f),
                    topLeft = Offset(0f, inset),
                    size = androidx.compose.ui.geometry.Size(w * 2.6f, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w),
                )
                // solid stripe
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(0f, inset),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f),
                )
            }
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
                .background(GlassStroke2)
                .align(Alignment.TopCenter)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Icon box — 48×48 frosted tile with the card's accent border + outer
            // glow. The icon glyph inside is preserved exactly; only this container
            // carries the feature colour.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .glowShadow(accentColor.copy(alpha = 0.30f), 14.dp, 14.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x12FFFFFF))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x1FFFFFFF), Color.Transparent),
                            center = Offset(10f, 10f), radius = 70f,
                        )
                    )
                    .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
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

// Lightning-bolt-in-bubble — represents fast single-shot chat.
@Composable
private fun BoxScope.QuickAskIcon() {
    IconBox {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
            val s = size
            val sc = s.width / 20f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.6f * sc,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
            // Bubble — rounded rect with bottom-left tail
            val bubble = Path().apply {
                moveTo(3f * sc, 3f * sc)
                lineTo(17f * sc, 3f * sc)
                quadraticTo(18.5f * sc, 3f * sc, 18.5f * sc, 4.5f * sc)
                lineTo(18.5f * sc, 13.5f * sc)
                quadraticTo(18.5f * sc, 15f * sc, 17f * sc, 15f * sc)
                lineTo(7f * sc, 15f * sc)
                lineTo(4f * sc, 17.5f * sc)
                lineTo(4f * sc, 15f * sc)
                lineTo(3f * sc, 15f * sc)
                quadraticTo(1.5f * sc, 15f * sc, 1.5f * sc, 13.5f * sc)
                lineTo(1.5f * sc, 4.5f * sc)
                quadraticTo(1.5f * sc, 3f * sc, 3f * sc, 3f * sc)
                close()
            }
            drawPath(bubble, NexusBlue, style = stroke)
            // Lightning bolt — filled amber
            val bolt = Path().apply {
                moveTo(11f * sc, 5f * sc)
                lineTo(7.5f * sc, 10f * sc)
                lineTo(10f * sc, 10f * sc)
                lineTo(8.5f * sc, 13.5f * sc)
                lineTo(12f * sc, 8f * sc)
                lineTo(9.5f * sc, 8f * sc)
                close()
            }
            drawPath(bolt, NexusAccent)
        }
    }
}

// Castle silhouette — represents the dungeon project map.
@Composable
private fun BoxScope.DungeonIcon() {
    IconBox {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
            val s = size
            val sc = s.width / 24f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.5f * sc,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
            val purple = Color(0xFF7C5CBF)
            // Ground line
            drawLine(purple, Offset(2f * sc, 21f * sc), Offset(22f * sc, 21f * sc), 1.5f * sc)
            // Left tower body
            val leftTower = Path().apply {
                moveTo(3f * sc, 21f * sc); lineTo(3f * sc, 10f * sc)
                lineTo(6f * sc, 10f * sc); lineTo(6f * sc, 21f * sc)
            }
            drawPath(leftTower, purple, style = stroke)
            // Left battlement
            val leftBattle = Path().apply {
                moveTo(3f * sc, 10f * sc); lineTo(3f * sc, 8f * sc)
                lineTo(4.5f * sc, 8f * sc); lineTo(4.5f * sc, 10f * sc)
                moveTo(4.5f * sc, 8f * sc); lineTo(6f * sc, 8f * sc); lineTo(6f * sc, 10f * sc)
            }
            drawPath(leftBattle, purple, style = stroke)
            // Right tower body
            val rightTower = Path().apply {
                moveTo(18f * sc, 21f * sc); lineTo(18f * sc, 10f * sc)
                lineTo(21f * sc, 10f * sc); lineTo(21f * sc, 21f * sc)
            }
            drawPath(rightTower, purple, style = stroke)
            // Right battlement
            val rightBattle = Path().apply {
                moveTo(18f * sc, 10f * sc); lineTo(18f * sc, 8f * sc)
                lineTo(19.5f * sc, 8f * sc); lineTo(19.5f * sc, 10f * sc)
                moveTo(19.5f * sc, 8f * sc); lineTo(21f * sc, 8f * sc); lineTo(21f * sc, 10f * sc)
            }
            drawPath(rightBattle, purple, style = stroke)
            // Main wall
            val wall = Path().apply {
                moveTo(6f * sc, 21f * sc); lineTo(6f * sc, 13f * sc)
                lineTo(18f * sc, 13f * sc); lineTo(18f * sc, 21f * sc)
            }
            drawPath(wall, purple, style = stroke)
            // Gate arch
            val gate = Path().apply {
                moveTo(10f * sc, 21f * sc); lineTo(10f * sc, 17f * sc)
                quadraticTo(12f * sc, 15f * sc, 14f * sc, 17f * sc)
                lineTo(14f * sc, 21f * sc)
            }
            drawPath(gate, purple, style = stroke)
            // Center tower body
            val centerTower = Path().apply {
                moveTo(9f * sc, 13f * sc); lineTo(9f * sc, 7f * sc)
                lineTo(15f * sc, 7f * sc); lineTo(15f * sc, 13f * sc)
            }
            drawPath(centerTower, purple, style = stroke)
            // Center battlement
            val centerBattle = Path().apply {
                moveTo(9f * sc, 7f * sc); lineTo(9f * sc, 5f * sc)
                lineTo(10.5f * sc, 5f * sc); lineTo(10.5f * sc, 7f * sc)
                moveTo(10.5f * sc, 5f * sc); lineTo(13.5f * sc, 5f * sc); lineTo(13.5f * sc, 7f * sc)
                moveTo(13.5f * sc, 5f * sc); lineTo(15f * sc, 5f * sc); lineTo(15f * sc, 7f * sc)
            }
            drawPath(centerBattle, purple, style = stroke)
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
