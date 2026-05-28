package com.claudecodesetup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Cap
import com.claudecodesetup.data.Providers
import com.claudecodesetup.discussion.Speaker

/**
 * Single-select model picker for Quick Ask. Mirrors the multi-select
 * [DiscussionModelPickerSheet] structure but returns one Speaker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAskModelPickerSheet(
    prefs: AppPreferences,
    currentId: String?,
    onPick: (Speaker) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = remember {
        val out = mutableListOf<SpeakerCandidate>()
        for (p in Providers.ALL) {
            val key = prefs.getApiKeyForProvider(p.id)
            if (key.isEmpty()) continue
            for (m in p.models) out.add(SpeakerCandidate(p, m))
        }
        out
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NexusSurface,
        contentColor = Color.White,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "Pick a model",
                fontFamily = DmSansFamily, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, color = Color.White,
            )
            Text(
                "Tap to switch. Chat history is preserved across model changes.",
                fontFamily = DmSansFamily, fontSize = 12.sp, color = NexusText3,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (candidates.isEmpty()) {
                Text(
                    "No providers with API keys configured. Go to Login → pick a provider first.",
                    fontFamily = DmSansFamily, fontSize = 13.sp,
                    color = Color(0xFFEF4444),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Close", color = NexusText2)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 520.dp),
                ) {
                    items(candidates) { cand ->
                        val id = "${cand.provider.id}:${cand.model.modelId}"
                        val isSelected = id == currentId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) NexusAccent.copy(alpha = 0.18f) else NexusSurface2,
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) NexusAccent else NexusBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    val apiKey = prefs.getApiKeyForProvider(cand.provider.id)
                                    val custom = prefs.getCustomBaseUrlForProvider(cand.provider.id)
                                    val baseUrl = if (custom.isNotEmpty()) custom else cand.provider.baseUrl
                                    onPick(Speaker(cand.provider, cand.model, apiKey, baseUrl))
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (isSelected) "●" else "○",
                                fontFamily = SpaceMonoFamily, fontSize = 12.sp,
                                color = if (isSelected) NexusAccent else NexusText3,
                                modifier = Modifier.padding(end = 10.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    cand.model.name, fontFamily = DmSansFamily,
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                                Text(
                                    cand.provider.name, fontFamily = SpaceMonoFamily,
                                    fontSize = 10.sp, color = NexusText3,
                                )
                            }
                            if (Cap.FREE in cand.model.caps) {
                                Text(
                                    "free", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                                    color = NexusGreen,
                                    modifier = Modifier
                                        .background(NexusGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
