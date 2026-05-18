package com.claudecodesetup.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private data class OllamaModel(
    val id: String,
    val name: String,
    val description: String,
    val sizeLabel: String,
    val ramGb: Int
)

private val CATALOG = listOf(
    OllamaModel("qwen3:0.6b",        "Qwen3 0.6B",        "Lightest, basic tasks",              "~400 MB", 1),
    OllamaModel("qwen3:1.5b",        "Qwen3 1.5B",        "Fast, everyday tasks",               "~1.1 GB", 2),
    OllamaModel("llama3.2:1b",       "Llama 3.2 1B",      "Meta's compact model",               "~630 MB", 2),
    OllamaModel("phi4-mini",         "Phi-4 Mini",        "Microsoft, smart & compact",         "~2.5 GB", 4),
    OllamaModel("qwen3:4b",          "Qwen3 4B",          "Great balance, reasoning",           "~2.6 GB", 4),
    OllamaModel("llama3.2:3b",       "Llama 3.2 3B",      "Meta's capable mid model",           "~2.0 GB", 4),
    OllamaModel("gemma3:4b",         "Gemma 3 4B",        "Google, code & reasoning",           "~3.3 GB", 5),
    OllamaModel("qwen3:8b",          "Qwen3 8B",          "High quality flagship",              "~5.2 GB", 8),
    OllamaModel("qwen2.5-coder:7b",  "Qwen2.5 Coder 7B",  "Code specialist",                    "~4.7 GB", 8),
    OllamaModel("llama3.1:8b",       "Llama 3.1 8B",      "Meta flagship, well-rounded",        "~4.7 GB", 8),
    OllamaModel("mistral:7b",        "Mistral 7B",        "Fast general-purpose",               "~4.1 GB", 8),
)

private val accent = Color(0xFFEF4444)
private val green  = Color(0xFF10B981)
private val blue   = Color(0xFF60A5FA)

@Composable
fun LocalModelsScreen(
    onModelSelected: (modelId: String) -> Unit,
    onRemoteServer: () -> Unit,
    onBack: () -> Unit
) {
    var entered by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(400), label = "alpha")
    val offset by animateFloatAsState(
        if (entered) 0f else 20f, tween(400, easing = FastOutSlowInEasing), label = "offset")
    LaunchedEffect(Unit) { entered = true }

    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // unlimited for streaming pull
            .build()
    }
    val scope = rememberCoroutineScope()

    var ollamaReachable by remember { mutableStateOf<Boolean?>(null) }
    var installedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pullingId by remember { mutableStateOf<String?>(null) }
    var pullProgress by remember { mutableStateOf(0f) }
    var pullError by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) } // 0=on-device 1=remote

    // Check Ollama status and list installed models
    fun refreshOllama() {
        scope.launch(Dispatchers.IO) {
            try {
                val resp = client.newCall(
                    Request.Builder().url("http://localhost:11434/api/tags").build()
                ).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val arr = JSONObject(body).optJSONArray("models")
                    val ids = mutableSetOf<String>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            ids += arr.getJSONObject(i).optString("name", "")
                        }
                    }
                    withContext(Dispatchers.Main) {
                        ollamaReachable = true
                        installedIds = ids
                    }
                } else {
                    withContext(Dispatchers.Main) { ollamaReachable = false }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { ollamaReachable = false }
            }
        }
    }

    LaunchedEffect(Unit) { refreshOllama() }

    fun pullModel(model: OllamaModel) {
        pullError = null
        pullingId = model.id
        pullProgress = 0f
        scope.launch(Dispatchers.IO) {
            try {
                val body = """{"name":"${model.id}","stream":true}"""
                    .toRequestBody("application/json".toMediaType())
                val resp = client.newCall(
                    Request.Builder().url("http://localhost:11434/api/pull").post(body).build()
                ).execute()
                val stream = resp.body?.byteStream()?.bufferedReader() ?: run {
                    withContext(Dispatchers.Main) {
                        pullError = "No response from Ollama"
                        pullingId = null
                    }
                    return@launch
                }
                stream.use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val json = runCatching { JSONObject(line) }.getOrNull()
                        if (json != null) {
                            val status = json.optString("status", "")
                            val completed = json.optLong("completed", 0L)
                            val total = json.optLong("total", 0L)
                            val progress = if (total > 0) completed.toFloat() / total else 0f
                            withContext(Dispatchers.Main) {
                                pullProgress = progress
                                if (status == "success") {
                                    pullingId = null
                                    refreshOllama()
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pullError = e.message ?: "Pull failed"
                    pullingId = null
                }
            }
        }
    }

    fun deleteModel(modelId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val body = """{"name":"$modelId"}"""
                    .toRequestBody("application/json".toMediaType())
                client.newCall(
                    Request.Builder().url("http://localhost:11434/api/delete")
                        .delete(body).build()
                ).execute().close()
                refreshOllama()
            } catch (_: Exception) {}
        }
    }

    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha; translationY = offset * density }
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "←", fontSize = 20.sp, color = blue,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "PERSONAL AI", fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                        letterSpacing = 3.sp, color = accent.copy(alpha = 0.7f)
                    )
                    Text(
                        "Local Models", fontFamily = DmSansFamily, fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, color = Color.White
                    )
                }
            }

            // Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(Color(0x0AFFFFFF), RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TabButton("On Device", tab == 0, green) { tab = 0 }
                TabButton("Remote Server", tab == 1, blue) { tab = 1 }
            }

            Spacer(Modifier.height(12.dp))

            if (tab == 1) {
                // Remote server — delegate to existing API key flow
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("🌐", fontSize = 40.sp)
                        Text(
                            "Connect to a remote Ollama server\nor any OpenAI-compatible API.",
                            fontFamily = DmSansFamily, fontSize = 14.sp,
                            color = Color(0xFF9CA3AF), textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                        ActionButton(
                            label = "Enter Server URL →",
                            color = blue,
                            onClick = onRemoteServer
                        )
                    }
                }
            } else {
                // On-device — Ollama status + model list
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        OllamaStatusCard(
                            reachable = ollamaReachable,
                            onRetry = { refreshOllama() }
                        )
                    }

                    if (pullError != null) {
                        item {
                            Text(
                                "Pull failed: $pullError",
                                fontFamily = DmSansFamily, fontSize = 12.sp,
                                color = Color(0xFFEF4444),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0x15EF4444), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            )
                        }
                    }

                    item {
                        Text(
                            "Available Models",
                            fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                            letterSpacing = 2.sp, color = Color(0xFF4B5563)
                        )
                    }

                    items(CATALOG) { model ->
                        val isPulling = pullingId == model.id
                        val isInstalled = installedIds.any { installed ->
                            installed == model.id ||
                            installed.startsWith("${model.id}:") ||
                            (model.id.endsWith(":latest") && installed == model.id.removeSuffix(":latest")) ||
                            installed == "${model.id}:latest"
                        }
                        ModelCard(
                            model = model,
                            isInstalled = isInstalled,
                            isPulling = isPulling,
                            pullProgress = if (isPulling) pullProgress else 0f,
                            ollamaReachable = ollamaReachable == true,
                            onPull = { if (ollamaReachable == true) pullModel(model) },
                            onDelete = { deleteModel(model.id) },
                            onUse = { onModelSelected(model.id) }
                        )
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TabButton(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .background(
                if (selected) color.copy(alpha = 0.15f) else Color.Transparent,
                RoundedCornerShape(9.dp)
            )
            .then(if (selected) Modifier.border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(9.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontFamily = DmSansFamily, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) color else Color(0xFF6B7280)
        )
    }
}

@Composable
private fun OllamaStatusCard(reachable: Boolean?, onRetry: () -> Unit) {
    val (icon, statusText, statusColor, bgColor) = when (reachable) {
        true  -> Quad("●", "Ollama connected · localhost:11434", green, Color(0x0F10B981))
        false -> Quad("○", "Ollama not found · is it running?", Color(0xFFF59E0B), Color(0x0FF59E0B))
        null  -> Quad("◌", "Checking Ollama…", Color(0xFF6B7280), Color(0x0A6B7280))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.dp, statusColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .clickable(enabled = reachable == false, onClick = onRetry)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(icon, fontSize = 14.sp, color = statusColor)
        Text(
            statusText, fontFamily = DmSansFamily, fontSize = 12.sp,
            color = statusColor, modifier = Modifier.weight(1f)
        )
        if (reachable == false) {
            Text(
                "Retry", fontFamily = DmSansFamily, fontSize = 11.sp,
                color = blue, fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: OllamaModel,
    isInstalled: Boolean,
    isPulling: Boolean,
    pullProgress: Float,
    ollamaReachable: Boolean,
    onPull: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit
) {
    val ramColor = when {
        model.ramGb <= 2 -> green
        model.ramGb <= 5 -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x0AFFFFFF), RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (isInstalled) green.copy(alpha = 0.25f) else Color(0x15FFFFFF),
                RoundedCornerShape(14.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    model.name, fontFamily = DmSansFamily, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, color = Color.White
                )
                Text(
                    model.description, fontFamily = DmSansFamily, fontSize = 11.sp,
                    color = Color(0xFF6B7280)
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    model.sizeLabel, fontFamily = SpaceMonoFamily, fontSize = 10.sp,
                    color = Color(0xFF4B5563)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(ramColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .border(1.dp, ramColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${model.ramGb}GB+", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                            color = ramColor
                        )
                    }
                }
            }
        }

        if (isPulling) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Downloading…", fontFamily = DmSansFamily, fontSize = 11.sp,
                        color = blue
                    )
                    Text(
                        "${(pullProgress * 100).toInt()}%", fontFamily = SpaceMonoFamily,
                        fontSize = 11.sp, color = blue
                    )
                }
                LinearProgressIndicator(
                    progress = { pullProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = blue,
                    trackColor = Color(0x1560A5FA),
                    strokeCap = StrokeCap.Round
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isInstalled) {
                    SmallButton(
                        label = "Use",
                        color = green,
                        modifier = Modifier.weight(1f),
                        onClick = onUse
                    )
                    SmallButton(
                        label = "Delete",
                        color = Color(0xFFEF4444),
                        modifier = Modifier.weight(1f),
                        onClick = onDelete
                    )
                } else {
                    SmallButton(
                        label = if (ollamaReachable) "Pull" else "Ollama offline",
                        color = if (ollamaReachable) blue else Color(0xFF4B5563),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { if (ollamaReachable) onPull() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, tween(100), label = "scale")

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontFamily = DmSansFamily, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, color = color
        )
    }
}

@Composable
private fun ActionButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(color.copy(alpha = 0.8f), color)),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontFamily = DmSansFamily, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold, color = Color.White
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
