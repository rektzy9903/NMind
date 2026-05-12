package com.claudecodesetup.ui

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.AiModel
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
            .header("HTTP-Referer", "https://github.com/rektzy9903/ClaudeCodeSetup")
            .header("X-Title", "ClaudeCodeSetup")
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
    providerId: String,
    providerUrl: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var loadState by remember {
        mutableStateOf<ModelLoadState>(
            if (providerId == "openrouter") ModelLoadState.Loading
            else {
                val models = Providers.byId(providerId)?.models ?: emptyList()
                ModelLoadState.Loaded(models)
            }
        )
    }

    var results by remember { mutableStateOf<List<ModelTestResult>>(emptyList()) }
    var isTesting by remember { mutableStateOf(false) }

    fun fetchModels() {
        loadState = ModelLoadState.Loading
        scope.launch {
            try {
                val models = ProvidersRepository.fetchOpenRouterFreeModels(apiKey)
                loadState = if (models.isEmpty()) ModelLoadState.Error("No free models found")
                            else ModelLoadState.Loaded(models)
            } catch (e: Exception) {
                loadState = ModelLoadState.Error(e.message ?: "Fetch failed")
            }
        }
    }

    LaunchedEffect(providerId) {
        if (providerId == "openrouter") fetchModels()
    }

    LaunchedEffect(loadState) {
        if (loadState is ModelLoadState.Loaded) {
            results = (loadState as ModelLoadState.Loaded).models.map { ModelTestResult(it) }
        }
    }

    fun runAllTests() {
        val models = (loadState as? ModelLoadState.Loaded)?.models ?: return
        if (isTesting) return
        isTesting = true
        results = models.map { ModelTestResult(it, TestStatus.PENDING) }
        scope.launch {
            models.forEachIndexed { i, model ->
                results = results.toMutableList().also {
                    it[i] = it[i].copy(status = TestStatus.TESTING)
                }
                val (status, latency) = testModel(providerId, providerUrl, apiKey, model)
                results = results.toMutableList().also {
                    it[i] = it[i].copy(status = status, latencyMs = latency)
                }
                delay(300L)
            }
            isTesting = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // Background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF08041A),
                        1f to Color(0xFF060210)
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x1AFFFFFF), CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Text("‹", fontSize = 22.sp, color = Color.White, fontFamily = DmSansFamily)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Testing Response",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = DmSansFamily,
                    )
                    Text(
                        "Provider: ${Providers.byId(providerId)?.name ?: providerId}",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        fontFamily = DmSansFamily,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (providerId == "openrouter") {
                    TestButton(
                        label = "↻",
                        enabled = loadState !is ModelLoadState.Loading && !isTesting,
                        color = Color(0xFF818CF8),
                        onClick = ::fetchModels
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TestButton(
                    label = if (isTesting) "Testing…" else "Test All",
                    enabled = loadState is ModelLoadState.Loaded && !isTesting,
                    color = Color(0xFF06B6D4),
                    onClick = ::runAllTests
                )
            }

            when (val state = loadState) {
                is ModelLoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF06B6D4), strokeWidth = 2.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("Fetching models…", fontSize = 13.sp, color = Color(0xFF64748B), fontFamily = DmSansFamily)
                        }
                    }
                }
                is ModelLoadState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Failed to load models", fontSize = 14.sp, color = Color(0xFFEF4444), fontFamily = DmSansFamily)
                            Spacer(Modifier.height(6.dp))
                            Text(state.message, fontSize = 11.sp, color = Color(0xFF475569), fontFamily = SpaceMonoFamily)
                            Spacer(Modifier.height(16.dp))
                            TestButton(label = "↻ Retry", enabled = true, color = Color(0xFF06B6D4), onClick = ::fetchModels)
                        }
                    }
                }
                is ModelLoadState.Loaded -> {
                    // Legend
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LegendDot(Color(0xFF22C55E), "Pass")
                        LegendDot(Color(0xFFF59E0B), "Empty / Rate limit")
                        LegendDot(Color(0xFFEF4444), "Fail")
                    }

                    Spacer(Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(results, key = { it.model.modelId }) { result ->
                            ModelResultRow(result)
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelResultRow(result: ModelTestResult) {
    val (bgColor, borderColor, label, labelColor) = when (result.status) {
        TestStatus.PENDING      -> Quad(Color(0x08FFFFFF), Color(0x15FFFFFF), "—",          Color(0xFF64748B))
        TestStatus.TESTING      -> Quad(Color(0x0F06B6D4), Color(0x3006B6D4), "Testing…",  Color(0xFF06B6D4))
        TestStatus.PASS         -> Quad(Color(0x0F22C55E), Color(0x2522C55E), "✓ Responds", Color(0xFF22C55E))
        TestStatus.EMPTY        -> Quad(Color(0x0FF59E0B), Color(0x25F59E0B), "∅ Empty",   Color(0xFFF59E0B))
        TestStatus.RATE_LIMITED -> Quad(Color(0x0FF59E0B), Color(0x25F59E0B), "⚡ Rate limit", Color(0xFFF59E0B))
        TestStatus.FAIL         -> Quad(Color(0x0FEF4444), Color(0x25EF4444), "✗ Failed",  Color(0xFFEF4444))
        TestStatus.TIMEOUT      -> Quad(Color(0x0FF59E0B), Color(0x25F59E0B), "⏱ Timeout", Color(0xFFF59E0B))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        if (result.status == TestStatus.TESTING) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color(0xFF06B6D4),
                strokeWidth = 2.dp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        when (result.status) {
                            TestStatus.PASS -> Color(0xFF22C55E)
                            TestStatus.FAIL -> Color(0xFFEF4444)
                            TestStatus.PENDING -> Color(0xFF334155)
                            else -> Color(0xFFF59E0B)
                        },
                        CircleShape
                    )
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                result.model.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                fontFamily = DmSansFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                result.model.modelId,
                fontSize = 11.sp,
                color = Color(0xFF475569),
                fontFamily = SpaceMonoFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = labelColor,
                fontFamily = DmSansFamily,
            )
            if (result.status !in listOf(TestStatus.PENDING, TestStatus.TESTING) && result.latencyMs > 0) {
                Text(
                    "${result.latencyMs}ms",
                    fontSize = 10.sp,
                    color = Color(0xFF475569),
                    fontFamily = SpaceMonoFamily,
                )
            }
        }
    }
}

@Composable
private fun TestButton(label: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) color.copy(alpha = 0.2f) else Color(0x0FFFFFFF))
            .border(1.dp, if (enabled) color.copy(alpha = 0.5f) else Color(0x15FFFFFF), RoundedCornerShape(10.dp))
            .run { if (enabled) clickable(onClick = onClick) else this }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) color else Color(0xFF475569),
            fontFamily = DmSansFamily,
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = Color(0xFF64748B), fontFamily = DmSansFamily)
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
private operator fun <A, B, C, D> Quad<A, B, C, D>.component1() = a
private operator fun <A, B, C, D> Quad<A, B, C, D>.component2() = b
private operator fun <A, B, C, D> Quad<A, B, C, D>.component3() = c
private operator fun <A, B, C, D> Quad<A, B, C, D>.component4() = d
