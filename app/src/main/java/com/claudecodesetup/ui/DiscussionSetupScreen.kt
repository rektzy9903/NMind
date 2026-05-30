package com.claudecodesetup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.discussion.DiscussionConfig
import com.claudecodesetup.discussion.DiscussionMode
import com.claudecodesetup.discussion.HumanRole
import com.claudecodesetup.discussion.Pacing
import com.claudecodesetup.discussion.Speaker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscussionSetupScreen(
    prefs: AppPreferences,
    initialConfig: DiscussionConfig?,
    onStart: (DiscussionConfig) -> Unit,
    onBack: () -> Unit,
) {
    var topic by remember { mutableStateOf(initialConfig?.topic ?: "") }
    var mode by remember { mutableStateOf(initialConfig?.mode ?: DiscussionMode.ROUNDTABLE) }
    var speakers by remember { mutableStateOf<List<Speaker>>(initialConfig?.speakers ?: emptyList()) }
    // Max-turns source of truth is the text box: "" = unlimited, else the number.
    // The slider is a convenience that writes 2..20 into this same box.
    var turnsText by remember {
        mutableStateOf((initialConfig?.maxTurns ?: 6).let { if (it <= 0) "" else it.toString() })
    }
    var enableJudge by remember { mutableStateOf(initialConfig?.enableJudge ?: false) }
    var humanRole by remember { mutableStateOf(initialConfig?.humanRole ?: HumanRole.NONE) }
    var enableVoting by remember { mutableStateOf(initialConfig?.enableVoting ?: false) }
    var pacing by remember { mutableStateOf(initialConfig?.pacing ?: Pacing.DELAY) }
    var reactionDelaySec by remember { mutableStateOf(initialConfig?.reactionDelaySec ?: 5) }
    var showPicker by remember { mutableStateOf(false) }

    val canStart = topic.isNotBlank() && speakers.size in 2..4

    AppBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("←", fontSize = 20.sp, color = NexusBlue,
                        modifier = Modifier.clickable(onClick = onBack).padding(end = 10.dp))
                    Column {
                        Text("DISCUSSION", fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                            letterSpacing = 3.sp, color = NexusBlue.copy(alpha = 0.7f))
                        Text("Set up a debate", fontFamily = DmSansFamily,
                            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .verticalScrollable(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Topic
                SectionLabel("TOPIC")
                OutlinedTextField(
                    value = topic, onValueChange = { topic = it },
                    placeholder = { Text("Paste code, ask a question, propose a plan…") },
                    minLines = 3, maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default,
                )

                // Mode
                SectionLabel("MODE")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiscussionMode.values().forEach { m ->
                        ModeCard(m, mode == m, onClick = { mode = m })
                    }
                }
                if (mode == DiscussionMode.CODE_REVIEW) {
                    Text(
                        "⚠ Limitation: models cannot run your code — they reason from the text only. " +
                        "For verify-by-execution, use the terminal after this discussion.",
                        fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusAmber,
                        modifier = Modifier
                            .background(NexusAmber.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                    )
                }

                // Speakers
                SectionLabel("SPEAKERS")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NexusSurface, RoundedCornerShape(10.dp))
                        .border(1.dp, NexusBorder, RoundedCornerShape(10.dp))
                        .clickable { showPicker = true }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (speakers.isEmpty()) {
                            Text("Pick 2–4 models", fontFamily = DmSansFamily,
                                fontSize = 14.sp, color = NexusText2)
                            Text("Tap to choose", fontFamily = DmSansFamily,
                                fontSize = 11.sp, color = NexusText3)
                        } else {
                            Text("${speakers.size} speakers", fontFamily = DmSansFamily,
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                speakers.joinToString(" · ") { it.model.name },
                                fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusText3,
                                maxLines = 2,
                            )
                        }
                    }
                    Text("→", fontSize = 16.sp, color = NexusAccent)
                }

                // Max turns — slider (quick pick 2..20) + exact/unlimited box.
                val turnsUnlimited = turnsText.isBlank()
                val effectiveTurns = if (turnsUnlimited) 0 else (turnsText.toIntOrNull() ?: 0)
                val turnsWarn = turnsUnlimited || effectiveTurns > 12
                val sliderPos = (turnsText.toIntOrNull() ?: 20).coerceIn(2, 20)
                val turnsAccent = if (turnsWarn) NexusRed else NexusAccent
                SectionLabel(
                    "MAX TURNS  (${if (turnsUnlimited) "∞" else effectiveTurns})",
                    color = if (turnsWarn) NexusRed else NexusBlue.copy(alpha = 0.8f),
                )
                Slider(
                    value = sliderPos.toFloat(),
                    onValueChange = { turnsText = it.toInt().toString() },
                    valueRange = 2f..20f, steps = 17,
                    colors = SliderDefaults.colors(
                        thumbColor = turnsAccent,
                        activeTrackColor = turnsAccent,
                        inactiveTrackColor = NexusBorder2,
                    ),
                )
                OutlinedTextField(
                    value = turnsText,
                    onValueChange = { new -> turnsText = new.filter { it.isDigit() }.take(3) },
                    placeholder = {
                        Text("∞ unlimited", fontFamily = DmSansFamily, fontSize = 13.sp, color = NexusText3)
                    },
                    label = {
                        Text("Exact turns — leave blank for unlimited",
                            fontFamily = DmSansFamily, fontSize = 11.sp)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(fontFamily = SpaceMonoFamily, fontSize = 14.sp, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = turnsAccent,
                        unfocusedBorderColor = NexusBorder2,
                        focusedLabelColor = turnsAccent,
                        unfocusedLabelColor = NexusText3,
                        cursorColor = NexusAccent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Each turn is one speaker. Discussion may end early if the speakers converge.",
                    fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusText3,
                )
                if (turnsWarn) {
                    Text(
                        if (turnsUnlimited)
                            "⚠ Unlimited — paid / high-capability models only. No turn cap: the " +
                            "debate runs until the panel converges, credits run out, or you stop it. " +
                            "The full transcript is re-sent every turn."
                        else
                            "⚠ 12+ turns — paid / high-capability models only. The full transcript " +
                            "is re-sent every turn, so free-tier providers may fail (request too " +
                            "large) as it grows.",
                        fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusRed,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // Judge toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NexusSurface, RoundedCornerShape(10.dp))
                        .border(1.dp, NexusBorder, RoundedCornerShape(10.dp))
                        .clickable { enableJudge = !enableJudge }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Final judge summary", fontFamily = DmSansFamily,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(
                            "First speaker writes a 3-paragraph summary at the end. +1 API call.",
                            fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusText3,
                        )
                    }
                    Switch(
                        checked = enableJudge,
                        onCheckedChange = { enableJudge = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NexusAccent,
                            checkedTrackColor = NexusAccent.copy(alpha = 0.4f),
                        ),
                    )
                }

                // Your role
                SectionLabel("YOUR ROLE")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoleCard("Just set the topic", "Pick the models, then watch them debate.",
                        humanRole == HumanRole.NONE) { humanRole = HumanRole.NONE }
                    RoleCard("Take a seat in rotation", "You're a speaker too. Each round pauses for you to type.",
                        humanRole == HumanRole.SEAT) { humanRole = HumanRole.SEAT }
                    RoleCard("Interject freely", "Models auto-debate; drop in a comment any time and they react.",
                        humanRole == HumanRole.INTERJECT) { humanRole = HumanRole.INTERJECT }
                }

                // Pacing — only relevant when the human interjects freely
                if (humanRole == HumanRole.INTERJECT) {
                    SectionLabel("PACING")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoleCard("Open floor — wait for me", "After each model, the debate pauses until you interject or tap Pass.",
                            pacing == Pacing.OPEN_FLOOR) { pacing = Pacing.OPEN_FLOOR }
                        RoleCard("Reaction delay", "A timed gap after each model to interject; auto-continues if you stay quiet.",
                            pacing == Pacing.DELAY) { pacing = Pacing.DELAY }
                    }
                    if (pacing == Pacing.DELAY) {
                        SectionLabel("DELAY  (${reactionDelaySec}s)")
                        Slider(
                            value = reactionDelaySec.toFloat(),
                            onValueChange = { reactionDelaySec = it.toInt() },
                            valueRange = 2f..15f, steps = 12,
                            colors = SliderDefaults.colors(
                                thumbColor = NexusAccent, activeTrackColor = NexusAccent,
                                inactiveTrackColor = NexusBorder2),
                        )
                    }
                }

                // Concluding vote
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NexusSurface, RoundedCornerShape(10.dp))
                        .border(1.dp, NexusBorder, RoundedCornerShape(10.dp))
                        .clickable { enableVoting = !enableVoting }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Concluding vote", fontFamily = DmSansFamily,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Each model votes FOR / AGAINST / UNDECIDED at the end; you vote too. +1 call per model.",
                            fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusText3)
                    }
                    Switch(
                        checked = enableVoting,
                        onCheckedChange = { enableVoting = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NexusAccent,
                            checkedTrackColor = NexusAccent.copy(alpha = 0.4f)),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val cfg = DiscussionConfig(
                            topic = topic.trim(),
                            mode = mode,
                            speakers = speakers,
                            maxTurns = if (turnsText.isBlank()) 0
                                       else (turnsText.toIntOrNull()?.coerceAtLeast(2) ?: 6),
                            enableJudge = enableJudge,
                            judgeSpeaker = if (enableJudge && speakers.isNotEmpty()) speakers.first() else null,
                            humanRole = humanRole,
                            pacing = pacing,
                            reactionDelaySec = reactionDelaySec,
                            enableVoting = enableVoting,
                        )
                        onStart(cfg)
                    },
                    enabled = canStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NexusAccent, contentColor = Color.White,
                        disabledContainerColor = NexusBorder2,
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Start Discussion", fontFamily = DmSansFamily,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(14.dp))
            }

            if (showPicker) {
                DiscussionModelPickerSheet(
                    prefs = prefs,
                    initiallySelected = speakers.map { it.id },
                    biasCoding = mode == DiscussionMode.CODE_REVIEW,
                    onConfirm = { picked ->
                        speakers = picked
                        showPicker = false
                    },
                    onDismiss = { showPicker = false },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(s: String, color: Color = NexusBlue.copy(alpha = 0.8f)) {
    Text(s, fontFamily = SpaceMonoFamily, fontSize = 10.sp,
        letterSpacing = 2.sp, color = color)
}

@Composable
private fun ModeCard(mode: DiscussionMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) NexusAccent.copy(alpha = 0.18f) else NexusSurface,
                RoundedCornerShape(10.dp)
            )
            .border(
                1.dp,
                if (selected) NexusAccent else NexusBorder,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "●" else "○",
            color = if (selected) NexusAccent else NexusText3,
            fontFamily = SpaceMonoFamily, fontSize = 14.sp,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(mode.label, fontFamily = DmSansFamily,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(mode.tagline, fontFamily = DmSansFamily,
                fontSize = 11.sp, color = NexusText3)
        }
    }
}

@Composable
private fun RoleCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) NexusAccent.copy(alpha = 0.18f) else NexusSurface,
                RoundedCornerShape(10.dp)
            )
            .border(
                1.dp,
                if (selected) NexusAccent else NexusBorder,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "●" else "○",
            color = if (selected) NexusAccent else NexusText3,
            fontFamily = SpaceMonoFamily, fontSize = 14.sp,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontFamily = DmSansFamily,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(subtitle, fontFamily = DmSansFamily,
                fontSize = 11.sp, color = NexusText3)
        }
    }
}

@Composable
private fun Modifier.verticalScrollable(): Modifier {
    val scroll = rememberScrollState()
    return this.verticalScroll(scroll)
}
