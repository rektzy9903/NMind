package com.claudecodesetup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Providers
import com.claudecodesetup.discussion.Speaker
import com.claudecodesetup.quickask.Message
import com.claudecodesetup.quickask.MessageRole
import com.claudecodesetup.quickask.MessageStatus
import com.claudecodesetup.quickask.QuickAskViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAskScreen(
    prefs: AppPreferences,
    vm: QuickAskViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // On first composition, restore last-used speaker if there is one.
    LaunchedEffect(Unit) {
        if (state.activeSpeaker == null) {
            QuickAskPersistence.loadSpeaker(prefs)?.let { vm.setSpeaker(it) }
        }
    }

    // Auto-scroll to bottom on new messages / streaming deltas
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
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
                        Text("QUICK ASK", fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                            letterSpacing = 3.sp, color = NexusBlue.copy(alpha = 0.7f))
                        Text("Native chat", fontFamily = DmSansFamily,
                            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                if (state.messages.isNotEmpty()) {
                    Text(
                        "New", fontFamily = DmSansFamily, fontSize = 12.sp,
                        color = NexusText2,
                        modifier = Modifier
                            .clickable { vm.newChat() }
                            .padding(8.dp),
                    )
                }
            }

            // Model pill
            ModelPill(
                speaker = state.activeSpeaker,
                tokenSummary = "↑${state.totalPromptTokens} ↓${state.totalCompletionTokens}",
                onClick = { showPicker = true },
            )

            // Transcript or empty state
            if (state.messages.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (state.activeSpeaker == null) "Pick a model to start"
                            else "Type a message to start chatting",
                            fontFamily = DmSansFamily, fontSize = 15.sp, color = NexusText2,
                        )
                        Text(
                            "Switching model mid-chat preserves history.",
                            fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusText3,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages) { msg ->
                        QuickAskBubble(msg, onCopy = { clipboard.setText(AnnotatedString(msg.text)) })
                    }
                }
            }

            // Input row — shared terminal-style composer (no terminal toolbar)
            ChatInputBar {
                ChatTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "Message…",
                    enabled = state.activeSpeaker != null,
                    modifier = Modifier.weight(1f),
                )
                if (state.isStreaming) {
                    BarButton(
                        label = "Stop",
                        container = Color(0xFFEF4444),
                        contentColor = Color.White,
                        onClick = { vm.stop() },
                    )
                } else {
                    SendButton(
                        enabled = input.isNotBlank() && state.activeSpeaker != null,
                        onClick = {
                            val t = input.trim()
                            if (t.isNotEmpty() && state.activeSpeaker != null) {
                                vm.send(t)
                                input = ""
                            }
                        },
                    )
                }
            }
        }

        if (showPicker) {
            QuickAskModelPickerSheet(
                prefs = prefs,
                currentId = state.activeSpeaker?.id,
                onPick = { speaker ->
                    vm.setSpeaker(speaker)
                    QuickAskPersistence.saveSpeaker(prefs, speaker)
                    showPicker = false
                },
                onDismiss = { showPicker = false },
            )
        }
    }
}

@Composable
private fun ModelPill(speaker: Speaker?, tokenSummary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .background(NexusSurface, RoundedCornerShape(99.dp))
                .border(1.dp, NexusBorder, RoundedCornerShape(99.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(
                if (speaker == null) NexusText3 else NexusGreen,
                CircleShape,
            ))
            Spacer(Modifier.size(8.dp))
            Text(
                speaker?.model?.name ?: "Pick a model",
                fontFamily = DmSansFamily, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, color = Color.White,
            )
            if (speaker != null) {
                Spacer(Modifier.size(6.dp))
                Text(
                    "· ${speaker.provider.name}",
                    fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusText3,
                )
            }
            Spacer(Modifier.size(6.dp))
            Text("▾", fontSize = 10.sp, color = NexusText3)
        }
        Text(tokenSummary, fontFamily = SpaceMonoFamily, fontSize = 9.sp, color = NexusText3)
    }
}

@Composable
private fun QuickAskBubble(msg: Message, onCopy: () -> Unit) {
    val isUser = msg.role == MessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (isUser) NexusAccent.copy(alpha = 0.16f) else NexusSurface,
                    RoundedCornerShape(
                        topStart = 14.dp, topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 4.dp,
                        bottomEnd   = if (isUser) 4.dp else 14.dp,
                    )
                )
                .border(
                    1.dp,
                    if (isUser) NexusAccent.copy(alpha = 0.30f) else NexusBorder,
                    RoundedCornerShape(
                        topStart = 14.dp, topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 4.dp,
                        bottomEnd   = if (isUser) 4.dp else 14.dp,
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!isUser && msg.speakerLabel != null) {
                Text(
                    msg.speakerLabel, fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                    color = NexusText3, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(2.dp))
            }
            if (msg.text.isNotEmpty()) {
                Text(
                    msg.text,
                    fontFamily = DmSansFamily, fontSize = 14.sp,
                    color = NexusText, lineHeight = 20.sp,
                )
            } else if (msg.status == MessageStatus.STREAMING) {
                Text("typing…", fontFamily = SpaceMonoFamily, fontSize = 11.sp, color = NexusText3)
            }
            if (msg.errorMessage != null) {
                Spacer(Modifier.size(4.dp))
                Text(
                    msg.errorMessage.take(240),
                    fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                    color = Color(0xFFEF4444),
                )
            }
            if (msg.status == MessageStatus.DONE && msg.text.isNotEmpty()) {
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (msg.completionTokens > 0) {
                        Text(
                            "${msg.completionTokens}t",
                            fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                            color = NexusText3,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    if (!isUser) {
                        Text(
                            "copy", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                            color = NexusBlue,
                            modifier = Modifier
                                .clickable(onClick = onCopy)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
