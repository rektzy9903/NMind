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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.managers.LlamaServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private data class LocalModel(
    val id: String,
    val name: String,
    val description: String,
    val sizeLabel: String,
    val ramGb: Int,
    val downloadUrl: String
)

private val CATALOG = listOf(
    LocalModel(
        id = "qwen3-0.6b",
        name = "Qwen3 0.6B",
        description = "Lightest model, basic tasks",
        sizeLabel = "~400 MB",
        ramGb = 1,
        downloadUrl = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/qwen3-0.6b-q4_k_m.gguf"
    ),
    LocalModel(
        id = "qwen3-1.5b",
        name = "Qwen3 1.5B",
        description = "Fast, everyday tasks",
        sizeLabel = "~1.1 GB",
        ramGb = 2,
        downloadUrl = "https://huggingface.co/Qwen/Qwen3-1.5B-GGUF/resolve/main/qwen3-1.5b-q4_k_m.gguf"
    ),
    LocalModel(
        id = "llama3.2-1b",
        name = "Llama 3.2 1B",
        description = "Meta's compact model",
        sizeLabel = "~630 MB",
        ramGb = 2,
        downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
    ),
    LocalModel(
        id = "qwen3-4b",
        name = "Qwen3 4B",
        description = "Great balance, reasoning",
        sizeLabel = "~2.6 GB",
        ramGb = 4,
        downloadUrl = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/qwen3-4b-q4_k_m.gguf"
    ),
    LocalModel(
        id = "llama3.2-3b",
        name = "Llama 3.2 3B",
        description = "Meta's capable mid model",
        sizeLabel = "~2.0 GB",
        ramGb = 4,
        downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"
    ),
    LocalModel(
        id = "phi4-mini",
        name = "Phi-4 Mini",
        description = "Microsoft, smart & compact",
        sizeLabel = "~2.5 GB",
        ramGb = 4,
        downloadUrl = "https://huggingface.co/bartowski/phi-4-mini-instruct-GGUF/resolve/main/phi-4-mini-instruct-Q4_K_M.gguf"
    ),
    LocalModel(
        id = "qwen3-8b",
        name = "Qwen3 8B",
        description = "High quality flagship",
        sizeLabel = "~5.2 GB",
        ramGb = 8,
        downloadUrl = "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/qwen3-8b-q4_k_m.gguf"
    ),
    LocalModel(
        id = "llama3.1-8b",
        name = "Llama 3.1 8B",
        description = "Meta flagship, well-rounded",
        sizeLabel = "~4.7 GB",
        ramGb = 8,
        downloadUrl = "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"
    )
)

private val accent = Color(0xFF8B5CF6)
private val green  = Color(0xFF10B981)
private val blue   = Color(0xFF60A5FA)
private val amber  = Color(0xFFF59E0B)

@Composable
fun LocalModelsScreen(
    onModelSelected: (modelId: String) -> Unit,
    onRemoteServer: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val llamaMgr = remember { LlamaServerManager.get(context) }
    val scope = rememberCoroutineScope()

    var entered by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(400), label = "alpha")
    val offset by animateFloatAsState(
        if (entered) 0f else 20f, tween(400, easing = FastOutSlowInEasing), label = "offset")
    LaunchedEffect(Unit) { entered = true }

    val binaryAvailable = remember { llamaMgr.isBinaryAvailable() }
    var installedIds by remember { mutableStateOf(llamaMgr.getInstalledModelIds().toSet()) }
    var serverRunning by remember { mutableStateOf(llamaMgr.isServerRunning()) }
    var activeModelId by remember { mutableStateOf<String?>(null) }

    var downloadingId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    var loadingId by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var tab by remember { mutableStateOf(0) }

    val downloadClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun refreshStatus() {
        installedIds = llamaMgr.getInstalledModelIds().toSet()
        serverRunning = llamaMgr.isServerRunning()
    }

    fun downloadModel(model: LocalModel) {
        downloadError = null
        downloadingId = model.id
        downloadProgress = 0f
        scope.launch(Dispatchers.IO) {
            val dest = llamaMgr.modelFile(model.id)
            val tmpFile = File(dest.parent, "${model.id}.gguf.tmp")
            try {
                val resp = downloadClient.newCall(
                    Request.Builder().url(model.downloadUrl).build()
                ).execute()
                if (!resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        downloadError = "HTTP ${resp.code} — try again"
                        downloadingId = null
                    }
                    resp.body?.close()
                    return@launch
                }
                val body = resp.body ?: run {
                    withContext(Dispatchers.Main) {
                        downloadError = "Empty response"
                        downloadingId = null
                    }
                    return@launch
                }
                val total = body.contentLength()
                var downloaded = 0L
                tmpFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val p = downloaded.toFloat() / total
                                withContext(Dispatchers.Main) { downloadProgress = p }
                            }
                        }
                    }
                }
                tmpFile.renameTo(dest)
                withContext(Dispatchers.Main) {
                    downloadingId = null
                    refreshStatus()
                }
            } catch (e: Exception) {
                tmpFile.delete()
                withContext(Dispatchers.Main) {
                    downloadError = e.message ?: "Download failed"
                    downloadingId = null
                }
            }
        }
    }

    fun loadModel(model: LocalModel) {
        loadError = null
        loadingId = model.id
        scope.launch(Dispatchers.IO) {
            val started = llamaMgr.startServer(model.id)
            if (!started) {
                withContext(Dispatchers.Main) {
                    loadError = "Failed to start server"
                    loadingId = null
                }
                return@launch
            }
            val ready = llamaMgr.waitUntilReady(30_000L)
            withContext(Dispatchers.Main) {
                loadingId = null
                if (ready) {
                    serverRunning = true
                    activeModelId = model.id
                } else {
                    loadError = "Server did not start in time"
                    llamaMgr.stopServer()
                }
            }
        }
    }

    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha; translationY = offset * density }
        ) {
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(Color(0x0AFFFFFF), RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LocalTabButton("On Device", tab == 0, green) { tab = 0 }
                LocalTabButton("Remote Server", tab == 1, blue) { tab = 1 }
            }

            Spacer(Modifier.height(12.dp))

            if (tab == 1) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
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
                        LocalActionButton("Enter Server URL →", blue, onRemoteServer)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        LlamaStatusCard(binaryAvailable, serverRunning, activeModelId)
                    }

                    if (!binaryAvailable) {
                        item {
                            Text(
                                "Local AI is not included in this build. It requires a special build with the llama.cpp server binary compiled for Android ARM64.",
                                fontFamily = DmSansFamily, fontSize = 12.sp,
                                color = Color(0xFF9CA3AF),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0x0AFFFFFF), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            )
                        }
                    } else {
                        if (downloadError != null) {
                            item {
                                Text(
                                    "Download failed: $downloadError",
                                    fontFamily = DmSansFamily, fontSize = 12.sp,
                                    color = Color(0xFFEF4444),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0x15EF4444), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                )
                            }
                        }
                        if (loadError != null) {
                            item {
                                Text(
                                    "Load failed: $loadError",
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
                            val isDownloading = downloadingId == model.id
                            val isInstalled = model.id in installedIds
                            val isLoading = loadingId == model.id
                            val isActive = activeModelId == model.id && serverRunning

                            LocalModelCard(
                                model = model,
                                isInstalled = isInstalled,
                                isDownloading = isDownloading,
                                downloadProgress = if (isDownloading) downloadProgress else 0f,
                                isLoading = isLoading,
                                isActive = isActive,
                                onDownload = { if (downloadingId == null) downloadModel(model) },
                                onLoad = { if (loadingId == null) loadModel(model) },
                                onUnload = {
                                    llamaMgr.stopServer()
                                    serverRunning = false
                                    activeModelId = null
                                },
                                onUse = { onModelSelected(model.id) },
                                onDelete = {
                                    if (isActive) {
                                        llamaMgr.stopServer()
                                        serverRunning = false
                                        activeModelId = null
                                    }
                                    llamaMgr.deleteModel(model.id)
                                    refreshStatus()
                                }
                            )
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.LocalTabButton(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
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
private fun LlamaStatusCard(binaryAvailable: Boolean, serverRunning: Boolean, activeModel: String?) {
    val icon: String
    val statusText: String
    val statusColor: Color
    val bgColor: Color
    when {
        !binaryAvailable -> { icon = "✗"; statusText = "Local AI binary not available in this build"; statusColor = Color(0xFF6B7280); bgColor = Color(0x0A6B7280) }
        serverRunning && activeModel != null -> { icon = "●"; statusText = "Server running · $activeModel · localhost:8080"; statusColor = green; bgColor = Color(0x0F10B981) }
        serverRunning -> { icon = "●"; statusText = "Server running · localhost:8080"; statusColor = green; bgColor = Color(0x0F10B981) }
        else -> { icon = "○"; statusText = "No model loaded — download a model below"; statusColor = amber; bgColor = Color(0x0FF59E0B) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.dp, statusColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(icon, fontSize = 14.sp, color = statusColor)
        Text(
            statusText, fontFamily = DmSansFamily, fontSize = 12.sp,
            color = statusColor, modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LocalModelCard(
    model: LocalModel,
    isInstalled: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    isLoading: Boolean,
    isActive: Boolean,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit
) {
    val ramColor = when {
        model.ramGb <= 2 -> green
        model.ramGb <= 4 -> amber
        else -> Color(0xFFEF4444)
    }
    val borderColor = when {
        isActive -> green.copy(alpha = 0.4f)
        isInstalled -> green.copy(alpha = 0.2f)
        else -> Color(0x15FFFFFF)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x0AFFFFFF), RoundedCornerShape(14.dp))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        model.name, fontFamily = DmSansFamily, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, color = Color.White
                    )
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .background(green.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .border(1.dp, green.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text("ACTIVE", fontFamily = SpaceMonoFamily, fontSize = 8.sp, color = green)
                        }
                    }
                }
                Text(
                    model.description, fontFamily = DmSansFamily, fontSize = 11.sp,
                    color = Color(0xFF6B7280)
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    model.sizeLabel, fontFamily = SpaceMonoFamily, fontSize = 10.sp,
                    color = Color(0xFF4B5563)
                )
                Box(
                    modifier = Modifier
                        .background(ramColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .border(1.dp, ramColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text("${model.ramGb}GB+", fontFamily = SpaceMonoFamily, fontSize = 9.sp, color = ramColor)
                }
            }
        }

        when {
            isDownloading -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Downloading…", fontFamily = DmSansFamily, fontSize = 11.sp, color = blue)
                        Text("${(downloadProgress * 100).toInt()}%", fontFamily = SpaceMonoFamily, fontSize = 11.sp, color = blue)
                    }
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = blue,
                        trackColor = Color(0x1560A5FA),
                        strokeCap = StrokeCap.Round
                    )
                }
            }
            isLoading -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = amber,
                        trackColor = Color(0x1FF59E0B)
                    )
                    Text("Loading…", fontFamily = DmSansFamily, fontSize = 11.sp, color = amber)
                }
            }
            isActive -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalSmallButton("Use", green, Modifier.weight(1f), onUse)
                    LocalSmallButton("Unload", amber, Modifier.weight(1f), onUnload)
                }
            }
            isInstalled -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalSmallButton("Load", amber, Modifier.weight(1f), onLoad)
                    LocalSmallButton("Delete", Color(0xFFEF4444), Modifier.weight(1f), onDelete)
                }
            }
            else -> {
                LocalSmallButton("Download", blue, Modifier.fillMaxWidth(), onDownload)
            }
        }
    }
}

@Composable
private fun LocalSmallButton(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
        Text(label, fontFamily = DmSansFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun LocalActionButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.8f), color)), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontFamily = DmSansFamily, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}
