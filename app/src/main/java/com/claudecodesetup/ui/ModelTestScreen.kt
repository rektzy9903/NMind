package com.claudecodesetup.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.Cap
import com.claudecodesetup.data.Provider
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
        val text = JSONObject(bodyStr)
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

// ── Per-tab data ───────────────────────────────────────────────────────────────

private data class ProviderTab(
    val provider: Provider,
    val apiKey: String,
    val baseUrl: String,
)

// ── Entry point ────────────────────────────────────────────────────────────────

@Composable
fun ModelTestScreen(
    keys: Map<String, String>,
    urls: Map<String, String>,
    currentProviderId: String,
    onBack: () -> Unit,
) {
    // Build tab list from the loaded provider list (incl. hotloaded), only providers
    // with a key configured.
    val tabs = remember(keys) {
        ProvidersRepository.currentList()
            .filter { it.supportsLiveFetch && keys[it.id].orEmpty().isNotEmpty() }
            .map { provider ->
                ProviderTab(
                    provider = provider,
                    apiKey   = keys[provider.id]!!,
                    baseUrl  = urls[provider.id] ?: provider.baseUrl,
                )
            }
    }

    if (tabs.isEmpty()) {
        // No provider configured — fall back to static model list for current provider
        val provider = Providers.byId(currentProviderId)
        SingleProviderTestScreen(
            apiKey      = "",
            providerId  = currentProviderId,
            providerUrl = urls[currentProviderId] ?: provider?.baseUrl ?: "",
            onBack      = onBack,
        )
        return
    }

    val initialTab = tabs.indexOfFirst { it.provider.id == currentProviderId }.takeIf { it >= 0 } ?: 0

    TabbedModelTestScreen(tabs = tabs, initialTab = initialTab, onBack = onBack)
}

// ── Tabbed screen (all configured live-fetch providers) ────────────────────────

@Composable
private fun TabbedModelTestScreen(
    tabs: List<ProviderTab>,
    initialTab: Int,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(initialTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))) }

    // Parallel state lists — one slot per tab.
    val loadStates: SnapshotStateList<ModelLoadState> = remember {
        mutableStateListOf<ModelLoadState>().also { list -> repeat(tabs.size) { list.add(ModelLoadState.Loading) } }
    }
    val resultsList: SnapshotStateList<List<ModelTestResult>> = remember {
        mutableStateListOf<List<ModelTestResult>>().also { list -> repeat(tabs.size) { list.add(emptyList()) } }
    }
    val testingFlags: SnapshotStateList<Boolean> = remember {
        mutableStateListOf<Boolean>().also { list -> repeat(tabs.size) { list.add(false) } }
    }

    fun fetchTab(idx: Int) {
        val tab = tabs.getOrNull(idx) ?: return
        loadStates[idx] = ModelLoadState.Loading
        resultsList[idx] = emptyList()
        scope.launch {
            try {
                val models = when (tab.provider.id) {
                    // OpenRouter: filter to free models only (mixed free/paid catalogue)
                    "openrouter" -> ProvidersRepository.fetchOpenRouterModels(tab.apiKey)
                        .filter { Cap.FREE in it.caps }
                    "nvidia_nim" -> ProvidersRepository.fetchNvidiaFreeModels(tab.apiKey)
                    else         -> ProvidersRepository.fetchModels(tab.provider, tab.apiKey)
                }
                val loaded = if (models.isEmpty())
                    ModelLoadState.Error("No models found")
                else
                    ModelLoadState.Loaded(models)
                loadStates[idx] = loaded
                if (loaded is ModelLoadState.Loaded) {
                    resultsList[idx] = loaded.models.map { ModelTestResult(it) }
                }
            } catch (e: Exception) {
                loadStates[idx] = ModelLoadState.Error(e.message ?: "Fetch failed")
            }
        }
    }

    fun runTests(idx: Int) {
        val tab = tabs.getOrNull(idx) ?: return
        val models = (loadStates.getOrNull(idx) as? ModelLoadState.Loaded)?.models ?: return
        if (testingFlags.getOrElse(idx) { false }) return
        testingFlags[idx] = true
        resultsList[idx] = models.map { ModelTestResult(it, TestStatus.TESTING) }
        scope.launch {
            try {
                coroutineScope {
                    models.mapIndexed { i, model ->
                        async {
                            try {
                                val (status, latency) = testModel(tab.provider.id, tab.baseUrl, tab.apiKey, model)
                                val cur = resultsList[idx].toMutableList()
                                cur[i] = cur[i].copy(status = status, latencyMs = latency)
                                resultsList[idx] = cur
                            } catch (_: Exception) {
                                val cur = resultsList[idx].toMutableList()
                                cur[i] = cur[i].copy(status = TestStatus.FAIL)
                                resultsList[idx] = cur
                            }
                        }
                    }.awaitAll()
                }
            } finally { testingFlags[idx] = false }
        }
    }

    LaunchedEffect(Unit) { tabs.indices.forEach { fetchTab(it) } }

    val clampedTab    = selectedTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val activeLoad    = loadStates.getOrElse(clampedTab) { ModelLoadState.Loading }
    val activeResults = resultsList.getOrElse(clampedTab) { emptyList() }
    val activeTesting = testingFlags.getOrElse(clampedTab) { false }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0C0C0F))) {
        Column(modifier = Modifier.fillMaxSize()) {
            val activeTestedCount = activeResults.count {
                it.status !in listOf(TestStatus.PENDING, TestStatus.TESTING)
            }
            ScreenHeader(
                subtitle     = "live · ${tabs[clampedTab].provider.name} · $activeTestedCount tested",
                onBack       = onBack,
                isLoading    = activeLoad is ModelLoadState.Loading,
                isTesting    = activeTesting,
                onRefresh    = { fetchTab(clampedTab) },
                onTestAll    = { runTests(clampedTab) },
                testingLabel = if (activeTesting) "Testing…" else "Test All",
                testEnabled  = activeLoad is ModelLoadState.Loaded && !activeTesting,
            )

            // Tab bar — hidden when only one provider is configured
            if (tabs.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(tabs) { idx, tab ->
                        val isSelected = clampedTab == idx
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (isSelected) NexusAccentDim else NexusSurface2)
                                .border(1.dp, if (isSelected) NexusAccent else NexusBorder, RoundedCornerShape(7.dp))
                                .clickable { selectedTab = idx }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                tab.provider.name,
                                fontSize   = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color      = if (isSelected) NexusAccent else NexusText2,
                                fontFamily = DmSansFamily,
                            )
                        }
                    }
                }
            }

            ModelLoadContent(
                loadState = activeLoad,
                results   = activeResults,
                onRetry   = { fetchTab(clampedTab) },
            )
        }
    }
}

// ── Shared screen header ───────────────────────────────────────────────────────

@Composable
private fun ScreenHeader(
    subtitle: String,
    onBack: () -> Unit,
    isLoading: Boolean,
    isTesting: Boolean,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
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
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(NexusSurface, RoundedCornerShape(17.dp))
                .border(1.dp, NexusBorder, RoundedCornerShape(17.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Text("←", fontSize = 16.sp, color = NexusAccent, fontFamily = DmSansFamily)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Model Test",
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                color      = NexusText,
                fontFamily = DmSansFamily,
            )
            Text(subtitle, fontSize = 11.sp, color = NexusText3, fontFamily = JetBrainsMonoFamily)
        }

        Box(
            modifier = Modifier
                .size(34.dp)
                .background(NexusSurface, RoundedCornerShape(10.dp))
                .border(1.dp, NexusBorder, RoundedCornerShape(10.dp))
                .run { if (!isLoading && !isTesting) clickable(onClick = onRefresh) else this },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "↻",
                fontSize = 16.sp,
                color = if (!isLoading && !isTesting) NexusAccent else NexusText3,
                fontFamily = DmSansFamily,
            )
        }
        Spacer(Modifier.width(6.dp))

        Row(
            modifier = Modifier
                .background(NexusGreenDim, RoundedCornerShape(20.dp))
                .border(1.dp, NexusGreen.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(5.dp).background(NexusGreen.copy(alpha = pulseAlpha), CircleShape))
            Spacer(Modifier.width(4.dp))
            Text(
                "Live",
                fontSize   = 10.sp,
                color      = NexusGreen,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.width(8.dp))

        TestButton(label = testingLabel, enabled = testEnabled, color = NexusBlue, onClick = onTestAll)
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
                    CircularProgressIndicator(color = NexusAccent, strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Fetching models…", fontSize = 13.sp, color = NexusText3, fontFamily = JetBrainsMonoFamily)
                }
            }
        }
        is ModelLoadState.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Failed to load models",
                        fontSize   = 14.sp,
                        color      = Color(0xFFEF4444),
                        fontFamily = SyneFamily,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(state.message, fontSize = 11.sp, color = NexusText3, fontFamily = JetBrainsMonoFamily)
                    Spacer(Modifier.height(16.dp))
                    TestButton(label = "↻ Retry", enabled = true, color = NexusBlue, onClick = onRetry)
                }
            }
        }
        is ModelLoadState.Loaded -> {
            val hasAnyResult = results.any { it.status != TestStatus.PENDING && it.status != TestStatus.TESTING }
            if (hasAnyResult) StatsStrip(results)
            LegendRow(totalCount = results.size)
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(results, key = { it.model.modelId }) { result -> ModelResultRow(result) }
            }
        }
    }
}

// ── Single-provider screen (non-live or fallback) ──────────────────────────────

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
            } finally { isTesting = false }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0C0C0F))) {
        Column(modifier = Modifier.fillMaxSize()) {
            val testedCount = results.count { it.status !in listOf(TestStatus.PENDING, TestStatus.TESTING) }
            ScreenHeader(
                subtitle     = if (isLive) "live · free models · $testedCount tested"
                               else "${provider?.name ?: providerId} · $testedCount tested",
                onBack       = onBack,
                isLoading    = loadState is ModelLoadState.Loading,
                isTesting    = isTesting,
                onRefresh    = ::fetchModels,
                onTestAll    = ::runAllTests,
                testingLabel = if (isTesting) "Testing…" else "Test All",
                testEnabled  = loadState is ModelLoadState.Loaded && !isTesting,
            )
            ModelLoadContent(loadState = loadState, results = results, onRetry = ::fetchModels)
        }
    }
}

// ── Stats strip ────────────────────────────────────────────────────────────────

@Composable
private fun StatsStrip(results: List<ModelTestResult>) {
    val passCount  = results.count { it.status == TestStatus.PASS }
    val rateLimitCount = results.count { it.status == TestStatus.RATE_LIMITED || it.status == TestStatus.TIMEOUT }
    val failCount  = results.count { it.status == TestStatus.FAIL }
    val completedWithLatency = results.filter {
        it.status !in listOf(TestStatus.PENDING, TestStatus.TESTING) && it.latencyMs > 0
    }
    val avgLatency = if (completedWithLatency.isEmpty()) 0L
                     else completedWithLatency.sumOf { it.latencyMs } / completedWithLatency.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NexusSurface)
            .drawBehind {
                drawRect(color = NexusBorder, size = GeomSize(size.width, 1.dp.toPx()))
                drawRect(
                    color = NexusBorder,
                    topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = GeomSize(size.width, 1.dp.toPx())
                )
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatCell(passCount.toString(),                                "Pass",     NexusGreen,  Modifier.weight(1f))
        StatCell(rateLimitCount.toString(),                           "Rate ltd", NexusAmber,  Modifier.weight(1f))
        StatCell(failCount.toString(),                                "Failed",   NexusRed,    Modifier.weight(1f))
        StatCell(if (avgLatency > 0) "${avgLatency}ms" else "—",     "Avg ms",   NexusBlue,   Modifier.weight(1f), smallFont = avgLatency >= 10_000)
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    smallFont: Boolean = false,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = if (smallFont) 16.sp else 20.sp, fontWeight = FontWeight.Bold, color = valueColor, fontFamily = DmSansFamily)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = NexusText3, fontFamily = JetBrainsMonoFamily)
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
            .border(1.dp, NexusBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LegendDot(NexusGreen, "Pass")
        LegendDot(Color(0xFFF59E0B), "Rate limited")
        LegendDot(Color(0xFFEF4444), "Fail")
        Spacer(Modifier.weight(1f))
        Text("$totalCount models", fontSize = 10.sp, color = NexusText3, fontFamily = JetBrainsMonoFamily)
    }
}

// ── Model result row ───────────────────────────────────────────────────────────

@Composable
private fun ModelResultRow(result: ModelTestResult) {
    val (dotColor, badgeLabel, badgeFg, badgeBg) = when (result.status) {
        TestStatus.PENDING      -> Quad(NexusText3,  "—",        NexusText3, NexusSurface2)
        TestStatus.TESTING      -> Quad(NexusAccent, "Testing…", NexusAccent, NexusAccentDim)
        TestStatus.PASS         -> Quad(NexusGreen,  "Pass",     NexusGreen,  NexusGreenDim)
        TestStatus.EMPTY        -> Quad(NexusAmber,  "Empty",    NexusAmber,  Color(0x1AFBBF24))
        TestStatus.RATE_LIMITED -> Quad(NexusAmber,  "Rate-ltd", NexusAmber,  Color(0x1AFBBF24))
        TestStatus.FAIL         -> Quad(NexusRed,    "Fail",     NexusRed,    Color(0x1AF87171))
        TestStatus.TIMEOUT      -> Quad(NexusAmber,  "Timeout",  NexusAmber,  Color(0x1AFBBF24))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NexusBg)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (result.status == TestStatus.TESTING) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NexusAccent, strokeWidth = 2.dp)
            } else {
                Box(Modifier.size(8.dp).background(dotColor, CircleShape))
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.model.name,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color      = NexusText,
                    fontFamily = DmSansFamily,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                Text(
                    result.model.modelId,
                    fontSize   = 10.sp,
                    color      = NexusText3,
                    fontFamily = JetBrainsMonoFamily,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .background(badgeBg, RoundedCornerShape(20.dp))
                        .border(1.dp, badgeFg.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(badgeLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = badgeFg, fontFamily = DmSansFamily)
                }
                if (result.status !in listOf(TestStatus.PENDING, TestStatus.TESTING) && result.latencyMs > 0) {
                    Spacer(Modifier.height(3.dp))
                    Text("${result.latencyMs}ms", fontSize = 10.sp, color = NexusText3, fontFamily = JetBrainsMonoFamily)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(NexusBorder))
    }
}

// ── TestButton ─────────────────────────────────────────────────────────────────

@Composable
private fun TestButton(label: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) color.copy(alpha = 0.15f) else NexusSurface)
            .border(1.dp, if (enabled) color.copy(alpha = 0.45f) else NexusBorder, RoundedCornerShape(10.dp))
            .run { if (enabled) clickable(onClick = onClick) else this }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) color else NexusText3, fontFamily = DmSansFamily)
    }
}

// ── LegendDot ──────────────────────────────────────────────────────────────────

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = NexusText3, fontFamily = DmSansFamily)
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
private operator fun <A, B, C, D> Quad<A, B, C, D>.component1() = a
private operator fun <A, B, C, D> Quad<A, B, C, D>.component2() = b
private operator fun <A, B, C, D> Quad<A, B, C, D>.component3() = c
private operator fun <A, B, C, D> Quad<A, B, C, D>.component4() = d
