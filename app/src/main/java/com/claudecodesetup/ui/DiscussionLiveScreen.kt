package com.claudecodesetup.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.Providers
import com.claudecodesetup.discussion.DiscussionMode
import com.claudecodesetup.discussion.DiscussionState
import com.claudecodesetup.discussion.Turn
import com.claudecodesetup.discussion.TurnStatus

@Composable
fun DiscussionLiveScreen(
    state: DiscussionState,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onNewDiscussion: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // Auto-scroll to bottom as new turns or streaming chunks arrive
    LaunchedEffect(state.turns.size, state.turns.lastOrNull()?.text?.length) {
        if (state.turns.isNotEmpty()) {
            listState.animateScrollToItem(state.turns.size - 1)
        }
    }

    AppBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("←", fontSize = 20.sp, color = NexusBlue,
                        modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
                    Text(
                        modeBadge(state.mode), fontFamily = SpaceMonoFamily, fontSize = 10.sp,
                        letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold,
                        color = NexusBlue,
                    )
                }
                if (state.isRunning) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444).copy(alpha = 0.15f),
                            contentColor = Color(0xFFEF4444),
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) { Text("Stop", fontFamily = DmSansFamily, fontSize = 12.sp) }
                }
            }

            // ── Status strip ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val doneCount = state.turns.count { it.status == TurnStatus.DONE }
                Text(
                    "Turn $doneCount / ${state.maxTurns}",
                    fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusText2,
                )
                Text(
                    "↑${state.totalPromptTokens}  ↓${state.totalCompletionTokens}",
                    fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusText3,
                )
                if (state.converged) {
                    Text("● converged", fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusGreen)
                }
                if (!state.isRunning && state.stoppedReason != null) {
                    Text("● ${state.stoppedReason}", fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusText3)
                }
            }

            // ── Terminal-style transcript ─────────────────────────────────────
            // Topic shows as an amber "user bubble" (sharp bottom-right tail),
            // each AI turn as a dark "ai bubble" (sharp top-left tail) with the
            // speaker name in brand color as a header line inside the bubble.
            // Mirrors the terminal's .user-bubble / .ai-bubble aesthetic.
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item("topic") { TopicBubble(state.topic) }
                items(state.turns) { turn ->
                    val visuals = remember(turn.speakerId) { speakerVisuals(turn.speakerId) }
                    TurnBubble(
                        turn = turn,
                        visuals = visuals,
                        onCopy = { clipboard.setText(AnnotatedString(turn.text)) },
                    )
                }
            }

            // ── Footer ────────────────────────────────────────────────────────
            if (!state.isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onNewDiscussion,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NexusText),
                    ) { Text("New", fontFamily = DmSansFamily) }
                    Button(
                        onClick = onContinue,
                        enabled = state.turns.any { it.status == TurnStatus.DONE } && !state.converged,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NexusAccent, contentColor = Color.White,
                            disabledContainerColor = NexusBorder2,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text("Continue", fontFamily = DmSansFamily) }
                }
            }
        }
    }
}

// ── Topic at the top — amber "user bubble" mirroring terminal's .user-bubble ─
@Composable
private fun TopicBubble(topic: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    Color(0x29E8834A),  // rgba(232,131,74,0.16) — matches .user-bubble
                    RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 4.dp, bottomStart = 14.dp),
                )
                .border(
                    1.dp,
                    Color(0x4DE8834A),  // 0.30 alpha
                    RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 4.dp, bottomStart = 14.dp),
                )
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            Text(
                topic,
                fontFamily = SpaceMonoFamily, fontSize = 13.sp,
                color = Color(0xFFF0F0F2), lineHeight = 20.sp,
            )
        }
    }
}

// ── One AI turn — dark "ai bubble" mirroring terminal's .ai-bubble ──────────
// No avatar circle. Speaker name as a brand-colored label inside the bubble
// header so multiple speakers are still distinguishable at a glance.
@Composable
private fun TurnBubble(turn: Turn, visuals: SpeakerVisuals, onCopy: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color(0xFF1E1E22),  // matches .ai-bubble bg
                    RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp),
                )
                .border(
                    1.dp,
                    Color(0xFF2A2A30),  // matches .ai-bubble border
                    RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp),
                )
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            // Header row — small brand-color dot + speaker label + status/tokens/copy
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(visuals.accent, CircleShape),
                )
                Spacer(Modifier.size(7.dp))
                Text(
                    turn.speakerLabel,
                    fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, color = visuals.accent,
                    modifier = Modifier.weight(1f),
                )
                if (turn.status == TurnStatus.STREAMING) {
                    StatusPill("typing", visuals.accent)
                } else if (turn.status == TurnStatus.FAILED) {
                    StatusPill("failed", Color(0xFFEF4444))
                } else if (turn.status == TurnStatus.SKIPPED) {
                    StatusPill("skipped", NexusAmber)
                } else if (turn.status == TurnStatus.STOPPED) {
                    StatusPill("stopped", NexusText3)
                }
                if (turn.completionTokens > 0) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "${turn.completionTokens}t",
                        fontFamily = SpaceMonoFamily, fontSize = 9.sp, color = NexusText3,
                    )
                }
                if (turn.status == TurnStatus.DONE && turn.text.isNotEmpty()) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "copy", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                        color = NexusBlue,
                        modifier = Modifier
                            .clickable(onClick = onCopy)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }

            // Body — streaming text, typing dots, or error
            if (turn.text.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    turn.text, fontFamily = SpaceMonoFamily, fontSize = 13.sp,
                    color = Color(0xFFF0F0F2), lineHeight = 20.sp,
                )
            } else if (turn.status == TurnStatus.STREAMING) {
                Spacer(Modifier.size(7.dp))
                TypingDots(visuals.accent)
            }

            if (turn.errorMessage != null) {
                Spacer(Modifier.size(6.dp))
                Text(
                    turn.errorMessage.take(240),
                    fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                    color = Color(0xFFEF4444),
                )
            }
        }
    }
}

// ── Status pill (typing / failed / skipped / stopped) ───────────────────────
@Composable
private fun StatusPill(label: String, color: Color) {
    Text(
        label,
        fontFamily = SpaceMonoFamily, fontSize = 9.sp,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

// ── Three pulsing dots — "currently speaking" indicator ─────────────────────
@Composable
private fun TypingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "typing-dots")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Restart,
        ),
        label = "typing-phase",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val a = ((phase - i + 3f) % 3f) / 3f
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(0.35f + 0.65f * (1f - a))
                    .background(color, CircleShape),
            )
        }
    }
}

// ── Per-speaker visual identity (icon + brand color) ────────────────────────
private data class SpeakerVisuals(
    val iconResId: Int,
    val accent: Color,
    val initial: String,
)

/** Look up the speaker's provider from the "providerId:modelId" composite id
 *  and resolve its bundled brand drawable + accent color. Falls back to a
 *  stylized initial tile when no brand mark is bundled. */
private fun speakerVisuals(speakerId: String): SpeakerVisuals {
    val providerId = speakerId.substringBefore(":")
    val provider = Providers.byId(providerId)
    val (accent, _) = providerDisplayInfo(providerId)
    val initial = provider?.name?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val iconRes = provider?.iconResId ?: 0
    return SpeakerVisuals(iconResId = iconRes, accent = accent, initial = initial)
}

private fun modeBadge(m: DiscussionMode): String = when (m) {
    DiscussionMode.ROUNDTABLE  -> "ROUNDTABLE"
    DiscussionMode.DEBATE      -> "DEBATE"
    DiscussionMode.CRITIQUE    -> "CRITIQUE"
    DiscussionMode.CODE_REVIEW -> "CODE REVIEW"
}
