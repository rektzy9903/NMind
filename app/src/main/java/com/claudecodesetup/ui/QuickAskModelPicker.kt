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
import com.claudecodesetup.quickask.ImageGen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Capability filter for the Quick Ask picker. */
enum class QaFilter(val label: String) {
    ALL("All"), CHAT("Chat"), IMAGE("Image"), VIDEO("Video")
}

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

    val chatCandidates: List<SpeakerCandidate> = run {
        val out = mutableListOf<SpeakerCandidate>()
        for (cfg in configured) {
            val models = liveModels[cfg.provider.id] ?: cfg.provider.models
            for (m in models) out.add(SpeakerCandidate(cfg.provider, m))
        }
        out
    }
    // Image-gen routes live in a SEPARATE registry (ImageGen) — never in
    // Providers.ALL — so they only ever appear here, never in the terminal flow.
    val imageCandidates: List<SpeakerCandidate> = remember {
        ImageGen.availableSpeakers(prefs).map { SpeakerCandidate(it.provider, it.model) }
    }
    val allCandidates = chatCandidates + imageCandidates

    var filter by remember { mutableStateOf(QaFilter.ALL) }
    var query by remember { mutableStateOf("") }
    val candidates: List<SpeakerCandidate> = run {
        val byFilter = when (filter) {
            QaFilter.ALL   -> allCandidates
            QaFilter.CHAT  -> chatCandidates
            QaFilter.IMAGE -> imageCandidates
            QaFilter.VIDEO -> emptyList()
        }
        val q = query.trim().lowercase()
        if (q.isEmpty()) byFilter
        else byFilter.filter { c ->
            c.model.name.lowercase().contains(q) ||
                c.model.modelId.lowercase().contains(q) ||
                c.provider.name.lowercase().contains(q)
        }
    }
    val isFetching = loadingProviders.isNotEmpty()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NexusOverlay,
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
            // Capability filter chips. Image routes are bridge-free generators
            // (ImageGen); Video is a placeholder until a free route lands.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                for (f in QaFilter.values()) {
                    val active = f == filter
                    Text(
                        f.label,
                        fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) NexusAccent else NexusText3,
                        modifier = Modifier
                            .background(
                                if (active) NexusAccent.copy(alpha = 0.18f) else NexusSurface2,
                                RoundedCornerShape(99.dp),
                            )
                            .border(
                                1.dp,
                                if (active) NexusAccent else NexusBorder,
                                RoundedCornerShape(99.dp),
                            )
                            .clickable { filter = f }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
            run {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(
                            "Search models…",
                            fontFamily = DmSansFamily, fontSize = 13.sp, color = NexusText3,
                        )
                    },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Text(
                                "✕",
                                color = NexusText3, fontSize = 14.sp,
                                modifier = Modifier
                                    .clickable { query = "" }
                                    .padding(horizontal = 12.dp),
                            )
                        }
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White, fontFamily = DmSansFamily, fontSize = 13.sp,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = NexusAccent,
                        focusedBorderColor = NexusAccent,
                        unfocusedBorderColor = NexusBorder,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
                if (candidates.isEmpty()) {
                    val emptyMsg = when {
                        filter == QaFilter.VIDEO -> "Video generation coming soon — no free route yet."
                        query.isNotEmpty()       -> "No models match \"$query\"."
                        filter == QaFilter.CHAT  -> "No chat providers configured. Go to Login → pick a provider first."
                        else                     -> "Nothing here yet."
                    }
                    Text(
                        emptyMsg,
                        fontFamily = DmSansFamily, fontSize = 13.sp, color = NexusText3,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
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
