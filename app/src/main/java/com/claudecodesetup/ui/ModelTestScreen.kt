package com.claudecodesetup.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.Cap
import com.claudecodesetup.data.Providers
import com.claudecodesetup.data.ProvidersRepository
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ── Test status ────────────────────────────────────────────────────────────────

enum class TestStatus { PENDING, TESTING, PASS, EMPTY, RATE_LIMITED, FAIL, TIMEOUT }

data class ModelTestResult(
    val model: AiModel,
    val status: TestStatus = TestStatus.PENDING,
    val latencyMs: Long = 0L,
)

private val httpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
}

// ── HTTP test helper ───────────────────────────────────────────────────────────

private suspend fun testModel(
    providerId: String,
    providerUrl: String,
    apiKey: String,
    model: AiModel,
): Pair<TestStatus, Long> = withContext(Dispatchers.IO) {
    val startMs = System.currentTimeMillis()
    try {
        val result = when (providerId) {
            "gemini" -> testGemini(apiKey, model)
            else     -> testOpenAiCompat(providerUrl, apiKey, model)
        }
        val latency = System.currentTimeMillis() - startMs
        Pair(result, latency)
    } catch (_: java.net.SocketTimeoutException) {
        Pair(TestStatus.TIMEOUT, System.currentTimeMillis() - startMs)
    } catch (_: Exception) {
        Pair(TestStatus.FAIL, System.currentTimeMillis() - startMs)
    }
}

private fun testOpenAiCompat(baseUrl: String, apiKey: String, model: AiModel): TestStatus {
    val url = baseUrl.trimEnd('/') + "/chat/completions"
    val body = JSONObject().apply {
        put("model", model.modelId)
        put("messages", org.json.JSONArray().apply {
            put(JSONObject().apply { put("role", "user"); put("content", "hi") })
        })
        put("max_tokens", 8)
    }.toString().toRequestBody("application/json".toMediaType())

    val reqBuilder = Request.Builder()
        .url(url)
        .post(body)
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")

    // OpenRouter attribution
    if (baseUrl.contains("openrouter")) {
        reqBuilder
            .header("HTTP-Referer", "https://github.com/fahmi304/Nexus-Mind")
            .header("X-Title", "Nexus Mind")
    }

    val resp = httpClient.newCall(reqBuilder.build()).execute()
    val code = resp.code
    val bodyStr = resp.body?.string() ?: ""
    resp.close()

    if (code == 429) return TestStatus.RATE_LIMITED
    if (code !in 200..299) return TestStatus.FAIL

    return try {
        val json = JSONObject(bodyStr)
        val text = json
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            ?.trim() ?: ""
        if (text.isNotEmpty()) TestStatus.PASS else TestStatus.EMPTY
    } catch (_: Exception) {
        TestStatus.EMPTY
    }
}

private fun testGemini(apiKey: String, model: AiModel): TestStatus {
    val url = "https://generativelanguage.googleapis.com/v1beta/models/${model.modelId}:generateContent?key=$apiKey"
    val body = """{"contents":[{"parts":[{"text":"hi"}]}],"generationConfig":{"maxOutputTokens":8}}"""
        .toRequestBody("application/json".toMediaType())
    val resp = httpClient.newCall(Request.Builder().url(url).post(body).build()).execute()
    val code = resp.code
    val bodyStr = resp.body?.string() ?: ""
    resp.close()

    if (code == 429) return TestStatus.RATE_LIMITED
    if (code !in 200..299) return TestStatus.FAIL

    return try {
        val text = JSONObject(bodyStr)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text", "")
            ?.trim() ?: ""
        if (text.isNotEmpty()) TestStatus.PASS else TestStatus.EMPTY
    } catch (_: Exception) {
        TestStatus.EMPTY
    }
}

// ── Model load state ───────────────────────────────────────────────────────────

private sealed class ModelLoadState {
    object Loading : ModelLoadState()
    data class Loaded(val models: List<AiModel>) : ModelLoadState()
    data class Error(val message: String) : ModelLoadState()
}

// ── Composable ─────────────────────────────────────────────────────────────────

@Composable
fun ModelTestScreen(
    apiKey: String,
    orApiKey: String = "",
    nvApiKey: String = "",
    providerId: String,
    providerUrl: String,
    onBack: () -> Unit,
) {
    val resolvedOrKey = orApiKey.ifEmpty { if (providerId == "openrouter") apiKey else "" }
    val resolvedNvKey = nvApiKey.ifEmpty { if (providerId == "nvidia_nim") apiKey else "" }
    val hasOrNv = resolvedOrKey.isNotEmpty() || resolvedNvKey.isNotEmpty()

    if (hasOrNv) {
        TabbedModelTestScreen(
            orApiKey = resolvedOrKey,
            nvApiKey = resolvedNvKey,
            initialTab = if (providerId == "nvidia_nim") 1 else 0,
            onBack = onBack
        )
    } else {
        SingleProviderTestScreen(
            apiKey = apiKey,
            providerId = providerId,
            providerUrl = providerUrl,
            onBack = onBack
        )
    }
}

// ── Tabbed screen (OpenRouter + NVIDIA NIM) ────────────────────────────────────

@Composable
private fun TabbedModelTestScreen(
    orApiKey: String,
    nvApiKey: String,
    initialTab: Int,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(initialTab) }

    // OpenRouter state
    var orLoad by remember { mutableStateOf<ModelLoadState>(ModelLoadState.Loading) }
    var orResults by remember { mutableStateOf<List<ModelTestResult>>(emptyList()) }
    var orTesting by remember { mutableStateOf(false) }

    // NVIDIA state
    var nvLoad by remember { mutableStateOf<ModelLoadState>(ModelLoadState.Loading) }
    var nvResults by remember { mutableStateOf<List<ModelTestResult>>(emptyList()) }
    var nvTesting by remember { mutableStateOf(false) }

    fun fetchOr() {
        orLoad = ModelLoadState.Loading
        scope.launch {
            try {
                if (orApiKey.isEmpty()) { orLoad = ModelLoadState.Error("No OpenRouter key configured"); return@launch }
                val models = ProvidersRepository.fetchOpenRouterModels(orApiKey).filter { Cap.FREE in it.caps }
                orLoad = if (models.isEmpty()) ModelLoadState.Error("No free models found")
                         else ModelLoadState.Loaded(models)
            } catch (e: Exception) {
                orLoad = ModelLoadState.Error(e.message ?: "Fetch failed")
            }
        }
    }

    fun fetchNv() {
        nvLoad = ModelLoadState.Loading
        scope.launch {
            try {
                if (nvApiKey.isEmpty()) { nvLoad = ModelLoadState.Error("No NVIDIA key configured"); return@launch }
                val models = ProvidersRepository.fetchNvidiaFreeModels(nvApiKey)
                nvLoad = if (models.isEmpty()) ModelLoadState.Error("No models found")
                         else ModelLoadState.Loaded(models)
            } catch (e: Exception) {
                nvLoad = ModelLoadState.Error(e.message ?: "Fetch failed")
            }
        }
    }

    LaunchedEffect(orLoad) {
        if (orLoad is ModelLoadState.Loaded)
            orResults = (orLoad as ModelLoadState.Loaded).models.map { ModelTestResult(it) }
    }
    LaunchedEffect(nvLoad) {
        if (nvLoad is ModelLoadState.Loaded)
            nvResults = (nvLoad as ModelLoadState.Loaded).models.map { ModelTestResult(it) }
    }

    LaunchedEffect(Unit) {
        fetchOr()
        fetchNv()
    }

    fun runOrTests() {
        val models = (orLoad as? ModelLoadState.Loaded)?.models ?: return
        if (orTesting) return
        orTesting = true
        orResults = models.map { ModelTestResult(it, TestStatus.TESTING) }
        scope.launch {
            try {
                coroutineScope {
                    models.mapIndexed { i, model ->
                        async {
                            try {
                                val (status, latency) = testModel("openrouter", Providers.OPENROUTER.baseUrl, orApiKey, model)
                                orResults = orResults.toMutableList().also { it[i] = it[i].copy(status = status, latencyMs = latency) }
                            } catch (_: Exception) {
                                orResults = orResults.toMutableList().also { it[i] = it[i].copy(status = TestStatus.FAIL) }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                orTesting = false
            }
        }
    }

    fun runNvTests() {
        val models = (nvLoad as? ModelLoadState.Loaded)?.models ?: return
        if (nvTesting) return
        nvTesting = true
        nvResults = models.map { ModelTestResult(it, TestStatus.TESTING) }
        scope.launch {
            try {
                coroutineScope {
                    models.mapIndexed { i, model ->
                        async {
                            try {
                                val (status, latency) = testModel("nvidia_nim", Providers.NVIDIA_NIM.baseUrl, nvApiKey, model)
                                nvResults = nvResults.toMutableList().also { it[i] = it[i].copy(status = status, latencyMs = latency) }
                            } catch (_: Exception) {
                                nvResults = nvResults.toMutableList().also { it[i] = it[i].copy(status = TestStatus.FAIL) }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                nvTesting = false
            }
        }
    }

    val activeLoad    = if (selectedTab == 0) orLoad    else nvLoad
    val activeResults = if (selectedTab == 0) orResults else nvResults
    val activeTesting = if (selectedTab == 0) orTesting else nvTesting
    val onFetch       = if (selectedTab == 0) ::fetchOr  else ::fetchNv
    val onTestAll     = if (selectedTab == 0) ::runOrTests else ::runNvTests

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF07061A), Color(0xFF0E0C28))))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Testing Response",
                subtitle = "Free models · live fetch",
                onBack = onBack,
                isLoading = activeLoad is ModelLoadState.Loading,
                isTesting = activeTesting,
                onRefresh = onFetch,
                onTestAll = onTestAll,
                showRefresh = true,
                testingLabel = if (activeTesting) "Testing…" else "Test All",
                testEnabled = activeLoad is ModelLoadState.Loaded && !activeTesting,
            )

            // Tab bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 10.dp)
                    .background(Color(0x07FFFFFF), RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(14.dp))
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("OpenRouter", "NVIDIA NIM").forEachIndexed { idx, label ->
                    val isSelected = selectedTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .then(
                                if (isSelected) Modifier.background(
                                    Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFF6D28D9)))
                                ) else Modifier.background(Color.Transparent)
                            )
                            .clickable { selectedTab = idx },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color(0xFFF0EEFF) else Color(0xFF5B5880),
                            fontFamily = DmSansFamily
                        )
                    }
                }
            }

            ModelLoadContent(
                loadState = activeLoad,
                results = activeResults,
                onRetry = onFetch
            )
        }
    }
}

// ── Shared screen header ───────────────────────────────────────────────────────

@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    isLoading: Boolean,
    isTesting: Boolean,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
    showRefresh: Boolean,
    testingLabel: String,
    testEnabled: Boolean,
) {
    val pulseTransition = rememberInfiniteTransition(label = "livePulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
        label = "lp"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Color(0x09FFFFFF), RoundedCornerShape(17.dp))
                .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(17.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "←",
                fontSize = 16.sp,
                color = Color(0xFF8B5CF6),
                fontFamily = DmSansFamily
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF0EEFF),
                fontFamily = SyneFamily
            )
            Text(
                subtitle.uppercase(),
                fontSize = 10.sp,
                color = Color(0xFF5B5880),
                fontFamily = JetBrainsMonoFamily,
                letterSpacing = 1.sp,
            )
        }

        // Refresh icon-btn
        if (showRefresh) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color(0x09FFFFFF), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(10.dp))
                    .run { if (!isLoading && !isTesting) clickable(onClick = onRefresh) else this },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "↻",
                    fontSize = 16.sp,
                    color = if (!isLoading && !isTesting) Color(0xFF8B5CF6) else Color(0xFF3D3A5C),
                    fontFamily = DmSansFamily
                )
            }
            Spacer(Modifier.width(6.dp))
        }

        // Live status pill
        Row(
            modifier = Modifier
                .background(Color(0x0C8B5CF6), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x258B5CF6), RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(Color(0xFF8B5CF6).copy(alpha = pulseAlpha), CircleShape)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Live",
                fontSize = 10.sp,
                color = Color(0xFF8B5CF6),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.width(8.dp))

        // Test All button
        TestButton(
            label = testingLabel,
            enabled = testEnabled,
            color = Color(0xFF22D3EE),
            onClick = onTestAll
        )
    }
}

@Composable
private fun ModelLoadContent(
    loadState: ModelLoadState,
    results: List<ModelTestResult>,
    onRetry: () -> Unit,
) {
    when (val state = loadState) {
        is ModelLoadState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF8B5CF6), strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Fetching models…",
                        fontSize = 13.sp,
                        color = Color(0xFF5B5880),
                        fontFamily = JetBrainsMonoFamily
                    )
                }
            }
        }
        is ModelLoadState.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Failed to load models",
                        fontSize = 14.sp,
                        color = Color(0xFFEF4444),
                        fontFamily = SyneFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.message,
                        fontSize = 11.sp,
                        color = Color(0xFF5B5880),
                        fontFamily = JetBrainsMonoFamily
                    )
                    Spacer(Modifier.height(16.dp))
                    TestButton(label = "↻ Retry", enabled = true, color = Color(0xFF22D3EE), onClick = onRetry)
                }
            }
        }
        is ModelLoadState.Loaded -> {
            // Stats strip — only show once at least one result is resolved
            val hasAnyResult = results.any {
                it.status != TestStatus.PENDING && it.status != TestStatus.TESTING
            }
            if (hasAnyResult) {
                StatsStrip(results)
            }

            // Legend row
            LegendRow(totalCount = results.size)

            Spacer(Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { it.model.modelId }) { result -> ModelResultRow(result) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// ── Stats strip ────────────────────────────────────────────────────────────────

@Composable
private fun StatsStrip(results: List<ModelTestResult>) {
    val passCount = results.count { it.status == TestStatus.PASS }
    val rateLimitCount = results.count { it.status == TestStatus.RATE_LIMITED || it.status == TestStatus.TIMEOUT }
    val failCount = results.count { it.status == TestStatus.FAIL }
    val completedWithLatency = results.filter {
        it.status !in listOf(TestStatus.PENDING, TestStatus.TESTING) && it.latencyMs > 0
    }
    val avgLatency = if (completedWithLatency.isEmpty()) 0L
                     else completedWithLatency.sumOf { it.latencyMs } / completedWithLatency.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(
            value = passCount.toString(),
            label = "Passing",
            valueColor = Color(0xFF22C55E),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            value = rateLimitCount.toString(),
            label = "Rate Ltd",
            valueColor = Color(0xFFF59E0B),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            value = failCount.toString(),
            label = "Failed",
            valueColor = Color(0xFFEF4444),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            value = if (avgLatency > 0) "${avgLatency}ms" else "—",
            label = "Avg. Time",
            valueColor = Color(0xFFA78BFA),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatCard(value: String, label: String, valueColor: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0x09FFFFFF), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                fontFamily = SyneFamily,
            )
            Text(
                label,
                fontSize = 9.sp,
                color = Color(0xFF7C6FAA),
                fontFamily = JetBrainsMonoFamily,
            )
        }
    }
}

// ── Legend row ─────────────────────────────────────────────────────────────────

@Composable
private fun LegendRow(totalCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 4.dp)
            .background(Color(0x07FFFFFF), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LegendDot(Color(0xFF22C55E), "Pass")
        LegendDot(Color(0xFFF59E0B), "Rate limited")
        LegendDot(Color(0xFFEF4444), "Fail")
        Spacer(Modifier.weight(1f))
        Text(
            "$totalCount models",
            fontSize = 10.sp,
            color = Color(0xFF5B5880),
            fontFamily = JetBrainsMonoFamily,
        )
    }
}

// ── Single-provider screen (non-live providers) ────────────────────────────────

@Composable
private fun SingleProviderTestScreen(
    apiKey: String,
    providerId: String,
    providerUrl: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val provider = Providers.byId(providerId)
    val isLive = provider?.supportsLiveFetch == true

    var loadState by remember {
        mutableStateOf<ModelLoadState>(
            if (isLive) ModelLoadState.Loading
            else ModelLoadState.Loaded(provider?.models ?: emptyList())
        )
    }

    var results by remember { mutableStateOf<List<ModelTestResult>>(emptyList()) }
    var isTesting by remember { mutableStateOf(false) }

    fun fetchModels() {
        if (!isLive || provider == null) return
        loadState = ModelLoadState.Loading
        scope.launch {
            try {
                val fetched = ProvidersRepository.fetchModels(provider, apiKey)
                loadState = if (fetched.isEmpty()) ModelLoadState.Loaded(provider.models)
                            else ModelLoadState.Loaded(fetched)
            } catch (_: Exception) {
                loadState = ModelLoadState.Loaded(provider.models)
            }
        }
    }

    LaunchedEffect(Unit) { if (isLive) fetchModels() }

    LaunchedEffect(loadState) {
        if (loadState is ModelLoadState.Loaded)
            results = (loadState as ModelLoadState.Loaded).models.map { ModelTestResult(it) }
    }

    fun runAllTests() {
        val models = (loadState as? ModelLoadState.Loaded)?.models ?: return
        if (isTesting) return
        isTesting = true
        results = models.map { ModelTestResult(it, TestStatus.TESTING) }
        scope.launch {
            try {
                coroutineScope {
                    models.mapIndexed { i, model ->
                        async {
                            try {
                                val (status, latency) = testModel(providerId, providerUrl, apiKey, model)
                                results = results.toMutableList().also { it[i] = it[i].copy(status = status, latencyMs = latency) }
                            } catch (_: Exception) {
                                results = results.toMutableList().also { it[i] = it[i].copy(status = TestStatus.FAIL) }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                isTesting = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF07061A), Color(0xFF0E0C28))))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Testing Response",
                subtitle = "Provider: ${provider?.name ?: providerId}",
                onBack = onBack,
                isLoading = loadState is ModelLoadState.Loading,
                isTesting = isTesting,
                onRefresh = ::fetchModels,
                onTestAll = ::runAllTests,
                showRefresh = isLive,
                testingLabel = if (isTesting) "Testing…" else "Test All",
                testEnabled = loadState is ModelLoadState.Loaded && !isTesting,
            )
            ModelLoadContent(loadState = loadState, results = results, onRetry = ::fetchModels)
        }
    }
}

// ── ModelResultRow ─────────────────────────────────────────────────────────────

@Composable
private fun ModelResultRow(result: ModelTestResult) {
    val (bgColor, borderColor, label, labelColor) = when (result.status) {
        TestStatus.PENDING      -> Quad(Color(0x08FFFFFF), Color(0x12FFFFFF), "—",             Color(0xFF5B5880))
        TestStatus.TESTING      -> Quad(Color(0x0F22D3EE), Color(0x3022D3EE), "Testing…",     Color(0xFF22D3EE))
        TestStatus.PASS         -> Quad(Color(0x0F22C55E), Color(0x2522C55E), "Responds",      Color(0xFF22C55E))
        TestStatus.EMPTY        -> Quad(Color(0x0FF59E0B), Color(0x25F59E0B), "Empty",         Color(0xFFF59E0B))
        TestStatus.RATE_LIMITED -> Quad(Color(0x0FF59E0B), Color(0x25F59E0B), "Rate limit",    Color(0xFFF59E0B))
        TestStatus.FAIL         -> Quad(Color(0x0FEF4444), Color(0x25EF4444), "Failed",        Color(0xFFEF4444))
        TestStatus.TIMEOUT      -> Quad(Color(0x0FF59E0B), Color(0x25F59E0B), "Timeout",       Color(0xFFF59E0B))
    }

    val glowBarColor = when (result.status) {
        TestStatus.PASS         -> Color(0xFF22C55E)
        TestStatus.FAIL         -> Color(0xFFEF4444)
        TestStatus.RATE_LIMITED,
        TestStatus.TIMEOUT,
        TestStatus.EMPTY        -> Color(0xFFF59E0B)
        TestStatus.TESTING      -> Color(0xFF22D3EE)
        TestStatus.PENDING      -> Color(0xFF3D3A5C)
    }

    val cappedLatency = result.latencyMs.coerceAtMost(10_000L)
    val latencyFraction = if (cappedLatency > 0) cappedLatency / 10_000f else 0f

    Box(modifier = Modifier.fillMaxWidth()) {
        // Left glow bar
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(3.dp)
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(glowBarColor.copy(alpha = if (result.status == TestStatus.PENDING) 0.2f else 0.6f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            if (result.status == TestStatus.TESTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color(0xFF22D3EE),
                    strokeWidth = 2.dp
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .drawBehind {
                            // glow
                            if (result.status != TestStatus.PENDING) {
                                drawCircle(glowBarColor.copy(alpha = 0.3f), radius = size.minDimension)
                            }
                            drawCircle(glowBarColor)
                        }
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.model.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFF0EEFF),
                    fontFamily = DmSansFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    result.model.modelId,
                    fontSize = 9.5.sp,
                    color = Color(0xFF5B5880),
                    fontFamily = JetBrainsMonoFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = labelColor,
                    fontFamily = SyneFamily,
                )
                if (result.status !in listOf(TestStatus.PENDING, TestStatus.TESTING) && result.latencyMs > 0) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${result.latencyMs}ms",
                        fontSize = 10.sp,
                        color = Color(0xFF5B5880),
                        fontFamily = JetBrainsMonoFamily,
                    )
                    Spacer(Modifier.height(4.dp))
                    // Mini latency bar
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(2.dp)
                            .background(Color(0x12FFFFFF), RoundedCornerShape(1.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = latencyFraction.coerceIn(0f, 1f))
                                .background(labelColor.copy(alpha = 0.7f), RoundedCornerShape(1.dp))
                        )
                    }
                }
            }
        }
    }
}

// ── TestButton ─────────────────────────────────────────────────────────────────

@Composable
private fun TestButton(label: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) color.copy(alpha = 0.15f) else Color(0x09FFFFFF))
            .border(1.dp, if (enabled) color.copy(alpha = 0.45f) else Color(0x12FFFFFF), RoundedCornerShape(10.dp))
            .run { if (enabled) clickable(onClick = onClick) else this }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) color else Color(0xFF3D3A5C),
            fontFamily = DmSansFamily,
        )
    }
}

// ── LegendDot ──────────────────────────────────────────────────────────────────

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = Color(0xFF5B5880), fontFamily = DmSansFamily)
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
private operator fun <A, B, C, D> Quad<A, B, C, D>.component1() = a
private operator fun <A, B, C, D> Quad<A, B, C, D>.component2() = b
private operator fun <A, B, C, D> Quad<A, B, C, D>.component3() = c
private operator fun <A, B, C, D> Quad<A, B, C, D>.component4() = d
