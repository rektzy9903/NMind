package com.claudecodesetup.ui

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.claudecodesetup.R
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.discussion.DiscussionConfig
import com.claudecodesetup.discussion.DiscussionMode
import com.claudecodesetup.discussion.HumanRole
import com.claudecodesetup.discussion.Pacing
import com.claudecodesetup.discussion.PromptBuilder
import com.claudecodesetup.discussion.Speaker
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/** Outcome of reading an attached file: either extracted text or a user-facing error. */
private sealed class AttachResult {
    data class Ok(val name: String, val body: String) : AttachResult()
    data class Err(val message: String) : AttachResult()
}

/** Heuristic: does this byte stream look like binary (not UTF-8 text)? */
private fun looksBinary(bytes: ByteArray): Boolean {
    val n = minOf(bytes.size, 8000)
    if (n == 0) return false
    var control = 0
    for (i in 0 until n) {
        val b = bytes[i].toInt() and 0xFF
        if (b == 0) return true                       // NUL byte → definitely binary
        if (b < 0x09 || b in 0x0E..0x1F) control++     // control chars (excl. tab/LF/CR)
    }
    return control * 100 / n > 5                        // >5% control chars → binary
}

/** A one-tap discussion shape: mode + turn count + judge/vote defaults (#12). */
private data class DiscussionPreset(
    val label: String,
    val mode: DiscussionMode,
    val turns: Int,
    val judge: Boolean,
    val voting: Boolean,
)

private val DISCUSSION_PRESETS = listOf(
    DiscussionPreset("Quick debate", DiscussionMode.DEBATE, 6, judge = true, voting = false),
    DiscussionPreset("Deep roundtable", DiscussionMode.ROUNDTABLE, 8, judge = true, voting = false),
    DiscussionPreset("Code review", DiscussionMode.CODE_REVIEW, 6, judge = true, voting = false),
    DiscussionPreset("Critique my plan", DiscussionMode.CRITIQUE, 6, judge = false, voting = false),
    DiscussionPreset("Decide & vote", DiscussionMode.DEBATE, 8, judge = true, voting = true),
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Attach a code/text/PDF file: read its contents and insert them into the topic,
    // wrapped in a fenced block with the filename so the models see it as code. PDFs
    // are run through PdfBox text extraction; plain text/code is decoded as UTF-8.
    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    var name = uri.lastPathSegment ?: "file"
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) name = c.getString(idx) ?: name
                        }
                    }
                    val mime = context.contentResolver.getType(uri) ?: ""
                    val isPdf = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@withContext AttachResult.Err("Couldn't read that file")
                    // PDFs can be larger than plain text; cap raw bytes accordingly.
                    if (isPdf && bytes.size > 5_000_000)
                        return@withContext AttachResult.Err("PDF too large (max 5 MB)")
                    if (!isPdf && bytes.size > 200_000)
                        return@withContext AttachResult.Err("File too large (max 200 KB)")
                    if (isPdf) {
                        PDFBoxResourceLoader.init(context)
                        val text = PDDocument.load(bytes).use { doc ->
                            PDFTextStripper().getText(doc)
                        }.trim()
                        if (text.isBlank())
                            return@withContext AttachResult.Err("No extractable text in that PDF (it may be scanned images)")
                        // Guard against a huge PDF blowing up the token count.
                        val capped = if (text.length > 200_000) text.substring(0, 200_000) + "\n…[truncated]" else text
                        AttachResult.Ok(name, capped)
                    } else {
                        if (looksBinary(bytes))
                            return@withContext AttachResult.Err("That looks like a binary file — attach text, code, or a PDF.")
                        AttachResult.Ok(name, String(bytes, Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    AttachResult.Err("Couldn't read that file: ${e.message ?: "unknown error"}")
                }
            }
            when (loaded) {
                is AttachResult.Err ->
                    Toast.makeText(context, loaded.message, Toast.LENGTH_SHORT).show()
                is AttachResult.Ok -> {
                    val block = "```" + loaded.name + "\n" + loaded.body.trimEnd() + "\n```"
                    topic = if (topic.isBlank()) block else topic.trimEnd() + "\n\n" + block
                    Toast.makeText(context, "Attached ${loaded.name} (${loaded.body.length} chars)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val canStart = topic.isNotBlank() && speakers.size in 2..4

    // Debate: give each speaker a default side as soon as the lineup is set (or
    // changes), so the SIDES picker shows a starting assignment to flip.
    LaunchedEffect(mode, speakers.map { it.id }) {
        if (mode == DiscussionMode.DEBATE && speakers.size in 2..4 && speakers.any { it.role.isEmpty() }) {
            speakers = PromptBuilder.defaultDebateRoles(speakers)
        }
    }

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
                // Presets — one tap configures mode + turns + judge/vote for a
                // common shape of discussion. Topic is left untouched (#12).
                SectionLabel("PRESETS")
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DISCUSSION_PRESETS.forEach { p ->
                        val active = mode == p.mode &&
                            (turnsText.toIntOrNull() ?: 0) == p.turns &&
                            enableJudge == p.judge && enableVoting == p.voting
                        PresetChip(p.label, active) {
                            mode = p.mode
                            turnsText = p.turns.toString()
                            enableJudge = p.judge
                            enableVoting = p.voting
                        }
                    }
                }

                // Topic
                SectionLabel("TOPIC")
                OutlinedTextField(
                    value = topic, onValueChange = { topic = it },
                    placeholder = {
                        Text("Paste code, ask a question, propose a plan…",
                            fontFamily = DmSansFamily, fontSize = 13.sp, color = NexusText3)
                    },
                    minLines = 3, maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default,
                    textStyle = TextStyle(fontFamily = DmSansFamily, fontSize = 14.sp, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NexusAccent,
                        unfocusedBorderColor = NexusBorder2,
                        cursorColor = NexusAccent,
                    ),
                )
                Row(
                    modifier = Modifier
                        .background(NexusSurface2, RoundedCornerShape(8.dp))
                        .border(1.dp, NexusBorder, RoundedCornerShape(8.dp))
                        .clickable { attachLauncher.launch(arrayOf("*/*")) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_attach),
                        contentDescription = "Attach file",
                        tint = NexusAccent,
                        modifier = Modifier.size(15.dp).padding(end = 6.dp),
                    )
                    Text("Attach code / PDF", fontFamily = DmSansFamily, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, color = NexusAccent)
                }

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

                // Debate sides — let the user assign Defence / Opposition / Moderator.
                if (mode == DiscussionMode.DEBATE && speakers.isNotEmpty()) {
                    SectionLabel("SIDES")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        speakers.forEachIndexed { i, sp ->
                            DebateSideRow(
                                speaker = sp,
                                onPick = { newRole ->
                                    speakers = speakers.toMutableList()
                                        .also { it[i] = it[i].copy(role = newRole) }
                                },
                            )
                        }
                    }
                    val hasFor = speakers.any { it.role == "Defence" }
                    val hasAgainst = speakers.any { it.role == "Opposition" }
                    if (!hasFor || !hasAgainst) {
                        Text(
                            "⚠ A debate needs at least one Defence and one Opposition.",
                            fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusAmber,
                        )
                    }
                    Text(
                        "For a yes/no topic, Defence argues for it and Opposition against. For an " +
                        "either/or topic (\"Toyota or Honda\"), Defence champions the first option and " +
                        "Opposition the second. Moderator stays neutral. Sides are fixed for the whole " +
                        "debate — pick Roundtable instead if you want models free to change their minds.",
                        fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusText3,
                    )
                }

                // Max turns — slider (quick pick 2..20) + exact/unlimited box.
                val turnsUnlimited = turnsText.isBlank()
                val effectiveTurns = if (turnsUnlimited) 0 else (turnsText.toIntOrNull() ?: 0)
                val turnsWarn = turnsUnlimited || effectiveTurns > 12
                val sliderPos = (turnsText.toIntOrNull() ?: 20).coerceIn(2, 20)
                val turnsAccent = if (turnsWarn) NexusRed else NexusAccent
                // The box wins. Whenever it holds a value the slider can't show
                // (blank = unlimited, or > 20), the slider is disabled/greyed so
                // it's clear the exact box below is in control.
                val sliderActive = !turnsUnlimited && effectiveTurns in 2..20
                SectionLabel(
                    "MAX TURNS  (${if (turnsUnlimited) "∞" else effectiveTurns})",
                    color = if (turnsWarn) NexusRed else NexusBlue.copy(alpha = 0.8f),
                )
                Slider(
                    value = sliderPos.toFloat(),
                    onValueChange = { turnsText = it.toInt().toString() },
                    valueRange = 2f..20f, steps = 17,
                    enabled = sliderActive,
                    colors = SliderDefaults.colors(
                        thumbColor = turnsAccent,
                        activeTrackColor = turnsAccent,
                        inactiveTrackColor = NexusBorder2,
                        disabledThumbColor = NexusText3,
                        disabledActiveTrackColor = NexusBorder2,
                        disabledInactiveTrackColor = NexusBorder2,
                    ),
                )
                if (!sliderActive) {
                    Text(
                        "Slider off — using the exact value below.",
                        fontFamily = DmSansFamily, fontSize = 10.sp, color = NexusText3,
                    )
                }
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
                        Text(
                            when (mode) {
                                DiscussionMode.DEBATE      -> "Final verdict (neutral judge)"
                                DiscussionMode.CODE_REVIEW -> "Final findings report"
                                else                       -> "Final judge summary"
                            },
                            fontFamily = DmSansFamily,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(
                            when (mode) {
                                DiscussionMode.DEBATE ->
                                    "A neutral judge (your Moderator if set) reads the anonymized arguments " +
                                    "and declares a winner on the merits — not on which side it argued. +1 API call."
                                DiscussionMode.CODE_REVIEW ->
                                    "Consolidates the review into one numbered findings list, then every model " +
                                    "votes agree/disagree on each — you see how many confirmed each finding. +1 call/model."
                                else ->
                                    "First speaker writes a 3-paragraph summary at the end. +1 API call."
                            },
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
private fun PresetChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (active) NexusAccent.copy(alpha = 0.20f) else NexusSurface,
                RoundedCornerShape(9.dp),
            )
            .border(1.dp, if (active) NexusAccent else NexusBorder, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontFamily = DmSansFamily, fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) NexusAccent else NexusText2)
    }
}

@Composable
private fun SectionLabel(s: String, color: Color = NexusBlue.copy(alpha = 0.8f)) {
    Text(s, fontFamily = SpaceMonoFamily, fontSize = 10.sp,
        letterSpacing = 2.sp, color = color)
}

// One row per speaker in Debate mode: model name + Defence/Opposition/Moderator
// chips. The stored role values stay "For"/"Against"/"Moderator" (what the
// prompt + start() expect); the UI just labels them for the user.
@Composable
private fun DebateSideRow(speaker: Speaker, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NexusSurface, RoundedCornerShape(10.dp))
            .border(1.dp, NexusBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text(speaker.model.name, fontFamily = DmSansFamily, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SideChip("Defence", speaker.role == "Defence", NexusGreen) { onPick("Defence") }
            SideChip("Opposition", speaker.role == "Opposition", NexusRed) { onPick("Opposition") }
            SideChip("Moderator", speaker.role == "Moderator", NexusBlue) { onPick("Moderator") }
        }
    }
}

@Composable
private fun SideChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) accent.copy(alpha = 0.20f) else NexusSurface2,
                RoundedCornerShape(8.dp),
            )
            .border(1.dp, if (selected) accent else NexusBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, fontFamily = DmSansFamily, fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else NexusText2)
    }
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
