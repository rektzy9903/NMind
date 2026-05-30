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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.Providers
import com.claudecodesetup.discussion.DiscussionMode
import com.claudecodesetup.discussion.DiscussionState
import com.claudecodesetup.discussion.HumanRole
import com.claudecodesetup.discussion.Turn
import com.claudecodesetup.discussion.TurnStatus
import com.claudecodesetup.discussion.VoteChoice

// Group-chat style discussion screen: each model speaks in a left-aligned chat
// row with its model-picker brand avatar + brand-color name tag; the human's
// turns are right-aligned amber bubbles. Colors are the app's Nexus theme (no
// WhatsApp green/beige). Avatars reuse ModelPickerScreen.brandIconForModel so
// they're identical to the picker. (Replaces the prior terminal-bubble look —
// CLAUDE.md invariant 59 updated accordingly.)
@Composable
fun DiscussionLiveScreen(
    state: DiscussionState,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onNewDiscussion: () -> Unit,
    onBack: () -> Unit,
    onSubmitHuman: (String) -> Unit = {},
    onSubmitVote: (VoteChoice) -> Unit = {},
    onPass: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // Auto-scroll to bottom as new turns, streaming chunks, or votes arrive
    LaunchedEffect(state.turns.size, state.turns.lastOrNull()?.text?.length, state.votes.size) {
        val count = state.turns.size + if (state.votes.isNotEmpty()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count) // last item (vote card or last turn)
    }

    AppBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header — back · group avatar stack · mode + roster · Stop ──────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("←", fontSize = 20.sp, color = NexusBlue,
                    modifier = Modifier.clickable(onClick = onBack))
                AvatarStack(state)
                Column(modifier = Modifier.weight(1f)) {
                    Text(modeBadge(state.mode), fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                        letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, color = NexusBlue)
                    Text(
                        state.speakers.joinToString(", ") { it.model.name },
                        fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusText3, maxLines = 1,
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
                Text("Turn $doneCount / ${if (state.maxTurns <= 0) "∞" else state.maxTurns}",
                    fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusText2)
                Text("↑${state.totalPromptTokens}  ↓${state.totalCompletionTokens}",
                    fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusText3)
                if (state.converged) {
                    Text("● converged", fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusGreen)
                }
                if (!state.isRunning && state.stoppedReason != null && !state.votingPhase) {
                    Text("● ${state.stoppedReason}", fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusText3)
                }
            }

            // ── Chat transcript ────────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("topic") { TopicCard(state.topic) }
                items(state.turns) { turn ->
                    if (turn.isHuman) HumanBubble(turn.speakerLabel, turn.text)
                    else AiChatRow(turn, onCopy = { clipboard.setText(AnnotatedString(turn.text)) })
                }
                if (state.votes.isNotEmpty()) {
                    item("votes") { VoteResultsCard(state) }
                }
            }

            // ── Voting affordances ─────────────────────────────────────────────
            if (state.votingPhase) {
                Text(
                    "● the panel is voting…",
                    fontFamily = SpaceMonoFamily, fontSize = 11.sp, color = NexusAccent,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            if (state.awaitingHumanVote) {
                HumanVoteButtons(onVote = onSubmitVote)
            }

            // ── Human input bar ─────────────────────────────────────────────────
            // SEAT: shown only when paused for the human's slot.
            // INTERJECT: shown whenever running (covers DELAY window + OPEN_FLOOR).
            val showInput = (state.humanRole == HumanRole.SEAT && state.awaitingHuman) ||
                            (state.humanRole == HumanRole.INTERJECT && state.isRunning)
            if (showInput && !state.awaitingHumanVote) {
                HumanInputBar(
                    awaiting = state.awaitingHuman,
                    showPass = state.floorOpen,
                    onSend = onSubmitHuman,
                    onPass = onPass,
                )
            }

            // ── Footer ──────────────────────────────────────────────────────────
            if (!state.isRunning && !state.votingPhase && !state.awaitingHumanVote) {
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

// ── Topic — full-width amber-tinted card (handles long pasted topics/code) ──
@Composable
private fun TopicCard(topic: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x22E8834A), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0x4DE8834A), RoundedCornerShape(10.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Text("TOPIC", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
            letterSpacing = 2.sp, color = NexusAccent)
        Spacer(Modifier.size(4.dp))
        Text(topic, fontFamily = SpaceMonoFamily, fontSize = 13.sp,
            color = Color.White, lineHeight = 20.sp)
    }
}

// ── One AI turn — avatar + brand-color name tag + dark chat bubble ──────────
@Composable
private fun AiChatRow(turn: Turn, onCopy: () -> Unit) {
    val v = remember(turn.speakerId) { speakerVisuals(turn.speakerId) }
    val initial = turn.speakerLabel.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        SpeakerAvatar(v.iconResId, v.accent, initial)
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Name + status + tokens
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(turn.speakerLabel, fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, color = v.accent, modifier = Modifier.weight(1f))
                when (turn.status) {
                    TurnStatus.STREAMING -> StatusPill("typing", v.accent)
                    TurnStatus.FAILED    -> StatusPill("failed", Color(0xFFEF4444))
                    TurnStatus.SKIPPED   -> StatusPill("skipped", NexusAmber)
                    TurnStatus.STOPPED   -> StatusPill("stopped", NexusText3)
                    else -> {}
                }
                if (turn.completionTokens > 0) {
                    Spacer(Modifier.size(6.dp))
                    Text("${turn.completionTokens}t", fontFamily = SpaceMonoFamily,
                        fontSize = 9.sp, color = NexusText3)
                }
            }
            Spacer(Modifier.size(4.dp))
            // Bubble — sharp top-left tail toward the avatar
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E1E22),
                        RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                    .border(1.dp, Color(0xFF2A2A30),
                        RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                    .padding(horizontal = 13.dp, vertical = 10.dp),
            ) {
                if (turn.text.isNotEmpty()) {
                    Text(turn.text, fontFamily = SpaceMonoFamily, fontSize = 13.sp,
                        color = Color.White, lineHeight = 20.sp)
                } else if (turn.status == TurnStatus.STREAMING) {
                    TypingDots(v.accent)
                }
                if (turn.errorMessage != null) {
                    Spacer(Modifier.size(6.dp))
                    Text(turn.errorMessage.take(240), fontFamily = SpaceMonoFamily,
                        fontSize = 11.sp, color = Color(0xFFEF4444))
                }
                if (turn.status == TurnStatus.DONE && turn.text.isNotEmpty()) {
                    Spacer(Modifier.size(4.dp))
                    Text("copy", fontFamily = SpaceMonoFamily, fontSize = 9.sp, color = NexusBlue,
                        modifier = Modifier.clickable(onClick = onCopy).padding(vertical = 2.dp))
                }
            }
        }
    }
}

// ── A human turn — right-aligned amber bubble with a "You" label ────────────
@Composable
private fun HumanBubble(label: String, text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(Color(0x29E8834A),
                    RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 4.dp, bottomStart = 14.dp))
                .border(1.dp, Color(0x4DE8834A),
                    RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 4.dp, bottomStart = 14.dp))
                .padding(horizontal = 13.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(label, fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, color = NexusAccent)
            Spacer(Modifier.size(5.dp))
            Text(text, fontFamily = SpaceMonoFamily, fontSize = 13.sp,
                color = Color.White, lineHeight = 20.sp)
        }
    }
}

// ── Concluding vote results ─────────────────────────────────────────────────
@Composable
private fun VoteResultsCard(state: DiscussionState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NexusSurface, RoundedCornerShape(12.dp))
            .border(1.dp, NexusBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text("VOTE", fontFamily = SpaceMonoFamily, fontSize = 10.sp,
            letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, color = NexusAccent)
        Spacer(Modifier.size(8.dp))
        state.votes.forEach { vote ->
            val c = voteColor(vote.choice)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Box(modifier = Modifier.size(7.dp).background(c, CircleShape))
                Spacer(Modifier.size(7.dp))
                Text(vote.speakerLabel, fontFamily = SpaceMonoFamily, fontSize = 12.sp,
                    color = NexusText, modifier = Modifier.weight(1f))
                Text(vote.choice.name, fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, color = c)
            }
            if (vote.reason.isNotBlank()) {
                Text(vote.reason, fontFamily = SpaceMonoFamily, fontSize = 11.sp, color = NexusText2,
                    lineHeight = 16.sp, modifier = Modifier.padding(start = 14.dp, bottom = 4.dp))
            }
        }
        Spacer(Modifier.size(6.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(NexusBorder))
        Spacer(Modifier.size(6.dp))
        Text(
            "Result: ${state.votesFor} FOR · ${state.votesAgainst} AGAINST · ${state.votesUndecided} UNDECIDED",
            fontFamily = SpaceMonoFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NexusText,
        )
    }
}

@Composable
private fun HumanVoteButtons(onVote: (VoteChoice) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text("Your vote", fontFamily = SpaceMonoFamily, fontSize = 10.sp,
            letterSpacing = 1.sp, color = NexusAccent, modifier = Modifier.padding(bottom = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onVote(VoteChoice.FOR) }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NexusGreen.copy(alpha = 0.18f), contentColor = NexusGreen),
            ) { Text("FOR", fontFamily = DmSansFamily, fontSize = 13.sp) }
            Button(
                onClick = { onVote(VoteChoice.AGAINST) }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF4444).copy(alpha = 0.18f), contentColor = Color(0xFFEF4444)),
            ) { Text("AGAINST", fontFamily = DmSansFamily, fontSize = 13.sp) }
            OutlinedButton(
                onClick = { onVote(VoteChoice.UNDECIDED) }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NexusText2),
            ) { Text("Skip", fontFamily = DmSansFamily, fontSize = 13.sp) }
        }
    }
}

// ── Human input bar — interject / seat / open-floor Pass ────────────────────
@Composable
private fun HumanInputBar(awaiting: Boolean, showPass: Boolean, onSend: (String) -> Unit, onPass: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (awaiting) {
            Text("Your turn — the panel is waiting", fontFamily = SpaceMonoFamily,
                fontSize = 10.sp, color = NexusAccent,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp))
        } else if (showPass) {
            Text("Floor is open — interject or pass", fontFamily = SpaceMonoFamily,
                fontSize = 10.sp, color = NexusAccent,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp))
        }
        ChatInputBar {
            ChatTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Add your point…",
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            SendButton(
                enabled = text.isNotBlank(),
                onClick = { val t = text.trim(); if (t.isNotEmpty()) { onSend(t); text = "" } },
            )
            if (showPass) {
                BarButton(
                    label = "Pass",
                    container = NexusSurface2,
                    contentColor = NexusText2,
                    onClick = onPass,
                )
            }
        }
    }
}

// ── Speaker avatar — identical treatment to the model picker ────────────────
@Composable
private fun SpeakerAvatar(iconResId: Int, accent: Color, initial: String, size: Dp = 32.dp) {
    val shape = RoundedCornerShape(8.dp)
    if (iconResId != 0) {
        Box(
            modifier = Modifier.size(size).background(accent.copy(alpha = 0.18f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconResId), contentDescription = null,
                tint = accent, modifier = Modifier.size(size * 0.6f),
            )
        }
    } else {
        Box(
            modifier = Modifier.size(size).background(accent, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initial, color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.4f).sp)
        }
    }
}

@Composable
private fun AvatarStack(state: DiscussionState) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        state.speakers.take(4).forEach { sp ->
            val (accent, _) = providerDisplayInfo(sp.provider.id)
            val res = brandIconForModel(sp.model.modelId)
            val initial = sp.model.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            SpeakerAvatar(res, accent, initial, size = 26.dp)
        }
    }
}

// ── Status pill (typing / failed / skipped / stopped) ───────────────────────
@Composable
private fun StatusPill(label: String, color: Color) {
    Text(
        label,
        fontFamily = SpaceMonoFamily, fontSize = 9.sp, color = color,
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
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900), repeatMode = RepeatMode.Restart),
        label = "typing-phase",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val a = ((phase - i + 3f) % 3f) / 3f
            Box(modifier = Modifier.size(6.dp).alpha(0.35f + 0.65f * (1f - a)).background(color, CircleShape))
        }
    }
}

private fun voteColor(choice: VoteChoice): Color = when (choice) {
    VoteChoice.FOR       -> NexusGreen
    VoteChoice.AGAINST   -> Color(0xFFEF4444)
    VoteChoice.UNDECIDED -> NexusAmber
}

// ── Per-speaker visual identity (brand icon + accent), from picker mapping ──
private data class SpeakerVisuals(val iconResId: Int, val accent: Color, val initial: String)

private fun speakerVisuals(speakerId: String): SpeakerVisuals {
    val providerId = speakerId.substringBefore(":")
    val modelId = speakerId.substringAfter(":")
    val (accent, _) = providerDisplayInfo(providerId)
    val initial = Providers.byId(providerId)?.name?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    return SpeakerVisuals(iconResId = brandIconForModel(modelId), accent = accent, initial = initial)
}

private fun modeBadge(m: DiscussionMode): String = when (m) {
    DiscussionMode.ROUNDTABLE  -> "ROUNDTABLE"
    DiscussionMode.DEBATE      -> "DEBATE"
    DiscussionMode.CRITIQUE    -> "CRITIQUE"
    DiscussionMode.CODE_REVIEW -> "CODE REVIEW"
}
