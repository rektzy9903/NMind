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
import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Cap
import com.claudecodesetup.data.Provider
import com.claudecodesetup.data.Providers
import com.claudecodesetup.data.ProvidersRepository
import com.claudecodesetup.discussion.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // Configured providers — provider object + (apiKey, customBaseUrl) snapshot.
    // Stable for the lifetime of the sheet.
    data class Configured(val provider: Provider, val apiKey: String, val baseUrl: String)
    val configured = remember {
        ProvidersRepository.currentList().mapNotNull { p ->
            val key = prefs.getApiKeyForProvider(p.id)
            if (key.isEmpty()) return@mapNotNull null
            val custom = prefs.getCustomBaseUrlForProvider(p.id)
            val baseUrl = if (custom.isNotEmpty()) custom else p.baseUrl
            Configured(p, key, baseUrl)
        }
    }

    // Per-provider live model state. Live fetch overrides static models on
    // success; static list is kept as fallback on error. Same pattern as
    // DiscussionModelPickerSheet — cures the "sunset model ID 404" class of bug.
    val liveModels = remember { mutableStateMapOf<String, List<AiModel>>() }
    val loadingProviders = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        configured.filter { it.provider.supportsLiveFetch }.forEach { cfg ->
            loadingProviders.add(cfg.provider.id)
            scope.launch {
                try {
                    val effective = cfg.provider.copy(baseUrl = cfg.baseUrl)
                    val fetched = withContext(Dispatchers.IO) {
                        ProvidersRepository.fetchModels(effective, cfg.apiKey)
                    }
                    if (fetched.isNotEmpty()) liveModels[cfg.provider.id] = fetched
                } catch (_: Exception) {
                    // Keep static fallback.
                } finally {
                    loadingProviders.remove(cfg.provider.id)
                }
            }
        }
    }

    val candidates: List<SpeakerCandidate> = run {
        val out = mutableListOf<SpeakerCandidate>()
        for (cfg in configured) {
            val models = liveModels[cfg.provider.id] ?: cfg.provider.models
            for (m in models) out.add(SpeakerCandidate(cfg.provider, m))
        }
        out
    }
    val isFetching = loadingProviders.isNotEmpty()
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
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            if (isFetching) {
                Text(
                    "fetching live models…",
                    fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusAccent,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            } else {
                Spacer(Modifier.height(4.dp))
            }
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
