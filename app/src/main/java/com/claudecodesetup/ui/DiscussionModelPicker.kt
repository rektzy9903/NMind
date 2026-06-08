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
import com.claudecodesetup.data.Cap
import com.claudecodesetup.data.Provider
import com.claudecodesetup.data.Providers
import com.claudecodesetup.data.ProvidersRepository
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.discussion.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SpeakerCandidate(val provider: Provider, val model: AiModel)

/**
 * Multi-select model picker for Discussion. Lists every model from every
 * configured provider (i.e. provider has an API key set). Cap.CODING-bias
 * happens upstream via the `biasCoding` flag — we just visually flag those
 * models with a small "code" chip so the user can prefer them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscussionModelPickerSheet(
    prefs: AppPreferences,
    initiallySelected: List<String>,   // list of "<providerId>:<modelId>"
    biasCoding: Boolean,
    minPick: Int = 2,
    maxPick: Int = 4,
    onConfirm: (List<Speaker>) -> Unit,
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

    // Per-provider live model state. Null = not fetched yet / no live fetch.
    // On successful live fetch, the entry is replaced with the fetched list,
    // overriding the provider's static model list (same semantics as ModelPickerScreen).
    val liveModels = remember { mutableStateMapOf<String, List<AiModel>>() }
    val loadingProviders = remember { mutableStateListOf<String>() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        configured.filter { it.provider.supportsLiveFetch }.forEach { cfg ->
            loadingProviders.add(cfg.provider.id)
            scope.launch {
                try {
                    // Pass a provider copy with the user's custom baseUrl so e.g.
                    // ollama remote/HF Space URLs are honored (same as ModelPickerScreen).
                    val effective = cfg.provider.copy(baseUrl = cfg.baseUrl)
                    val fetched = withContext(Dispatchers.IO) {
                        ProvidersRepository.fetchModels(effective, cfg.apiKey)
                    }
                    if (fetched.isNotEmpty()) liveModels[cfg.provider.id] = fetched
                } catch (_: Exception) {
                    // Keep static fallback — don't blank out the provider on fetch error.
                } finally {
                    loadingProviders.remove(cfg.provider.id)
                }
            }
        }
    }

    // Candidate list — recomputed whenever liveModels changes.
    val candidates: List<SpeakerCandidate> = run {
        val out = mutableListOf<SpeakerCandidate>()
        for (cfg in configured) {
            val models = liveModels[cfg.provider.id] ?: cfg.provider.models
            for (m in models) out.add(SpeakerCandidate(cfg.provider, m))
        }
        out
    }
    val isFetching = loadingProviders.isNotEmpty()

    val selected = remember {
        mutableStateListOf<String>().apply { addAll(initiallySelected) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NexusOverlay,
        contentColor = Color.White,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "Pick $minPick–$maxPick speakers",
                fontFamily = DmSansFamily, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, color = Color.White,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (biasCoding) "Code Review mode — models with a code chip are recommended."
                    else "Tap to toggle. Order is preserved for Debate / Critique modes.",
                    fontFamily = DmSansFamily, fontSize = 12.sp, color = NexusText3,
                    modifier = Modifier.weight(1f),
                )
                if (isFetching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = NexusAccent,
                    )
                    Text(
                        "fetching live models…",
                        fontFamily = SpaceMonoFamily, fontSize = 10.sp,
                        color = NexusAccent,
                    )
                }
            }
            if (candidates.isEmpty()) {
                Text(
                    "No providers with API keys configured. Go to Login → pick a provider first.",
                    fontFamily = DmSansFamily, fontSize = 13.sp,
                    color = Color(0xFFEF4444),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 480.dp),
                ) {
                    items(candidates) { cand ->
                        val id = "${cand.provider.id}:${cand.model.modelId}"
                        val isSelected = selected.contains(id)
                        val codingCap = Cap.CODING in cand.model.caps
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
                                    if (isSelected) selected.remove(id)
                                    else if (selected.size < maxPick) selected.add(id)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (isSelected) (selected.indexOf(id) + 1).toString() else "○",
                                fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) NexusAccent else NexusText3,
                                modifier = Modifier.padding(end = 10.dp).width(16.dp),
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
                            if (codingCap) {
                                Text(
                                    "code", fontFamily = SpaceMonoFamily,
                                    fontSize = 9.sp,
                                    color = if (biasCoding) NexusAccent else NexusText3,
                                    modifier = Modifier
                                        .background(
                                            (if (biasCoding) NexusAccent else NexusText3).copy(alpha = 0.15f),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                            if (Cap.FREE in cand.model.caps) {
                                Spacer(Modifier.size(4.dp))
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancel", color = NexusText2) }
                Button(
                    onClick = {
                        val chosen = selected.mapNotNull { id ->
                            val cand = candidates.firstOrNull {
                                "${it.provider.id}:${it.model.modelId}" == id
                            } ?: return@mapNotNull null
                            // Reuse the configured snapshot (already has apiKey + resolved baseUrl).
                            val cfg = configured.firstOrNull { it.provider.id == cand.provider.id }
                                ?: return@mapNotNull null
                            Speaker(cand.provider, cand.model, cfg.apiKey, cfg.baseUrl)
                        }
                        if (chosen.size in minPick..maxPick) onConfirm(chosen)
                    },
                    enabled = selected.size in minPick..maxPick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NexusAccent, contentColor = Color.White,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Use ${selected.size}", fontFamily = DmSansFamily, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
