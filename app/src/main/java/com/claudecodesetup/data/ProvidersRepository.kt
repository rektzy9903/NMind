package com.claudecodesetup.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ProvidersRepository {

    private const val TAG = "ProvidersRepository"
    private const val ASSET_PATH = "providers.json"

    // Set at runtime from AppPreferences to enable live provider updates.
    // Empty string means always use the bundled asset.
    var remoteUrl: String = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class Result(
        val providers: List<Provider>,
        val fromRemote: Boolean,
        val error: String? = null
    )

    suspend fun load(context: Context): Result = withContext(Dispatchers.IO) {
        val url = remoteUrl.ifEmpty { AppPreferences(context).getProviderRemoteUrl() }
        if (url.isNotEmpty()) {
            try {
                val json = fetchRemote(url)
                val providers = parseProviders(json)
                if (providers.isNotEmpty()) return@withContext Result(providers, fromRemote = true)
            } catch (e: Exception) {
                Log.w(TAG, "Remote fetch failed, using bundled: ${e.message}")
            }
        }
        val json = loadAsset(context)
        val providers = try { parseProviders(json) } catch (e: Exception) {
            Log.e(TAG, "Asset parse failed, using hardcoded list", e)
            Providers.ALL
        }
        Result(providers, fromRemote = false)
    }

    private fun fetchRemote(url: String): String {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string() ?: throw Exception("Empty response")
        }
    }

    private fun loadAsset(context: Context): String {
        // DEBUG hot-load (P6.6): prefer a valid providers_dev.json downloaded by the
        // `!hotload-ui` terminal command. Release always uses the bundled asset.
        if (com.claudecodesetup.BuildConfig.DEBUG) {
            val dev = java.io.File(context.filesDir, "providers_dev.json")
            if (dev.exists() && dev.length() > 100) {
                val txt = runCatching { dev.readText() }.getOrNull()
                val ok = txt != null && runCatching { JSONObject(txt).has("providers") }.getOrDefault(false)
                if (ok) {
                    Log.i(TAG, "providers.json hot-loaded from providers_dev.json (${dev.length()} bytes)")
                    return txt!!
                }
            }
        }
        return context.assets.open(ASSET_PATH).bufferedReader().readText()
    }

    private fun parseProviders(json: String): List<Provider> {
        val root = JSONObject(json)
        val arr  = root.getJSONArray("providers")
        return (0 until arr.length()).map { i -> parseProvider(arr.getJSONObject(i)) }
    }

    private fun parseProvider(obj: JSONObject): Provider {
        val modelsArr = obj.getJSONArray("models")
        val models = (0 until modelsArr.length()).map { i ->
            val m = modelsArr.getJSONObject(i)
            AiModel(m.getString("name"), m.getString("modelId"))
        }
        val id = obj.getString("id")
        return Provider(
            id              = id,
            name            = obj.getString("name"),
            signupUrl       = obj.getString("signupUrl"),
            rateLimit       = obj.getString("rateLimit"),
            malaysiaStatus  = parseMalaysiaStatus(obj.getString("malaysiaStatus")),
            malaysiaNote    = obj.getString("malaysiaNote"),
            warningNote     = obj.optString("warningNote").takeIf { it.isNotEmpty() },
            baseUrl         = obj.getString("baseUrl"),
            requiresProxy   = obj.getBoolean("requiresProxy"),
            requiresApiKey  = obj.optBoolean("requiresApiKey", true),
            models          = models,
            // JSON can't carry an Android @DrawableRes Int, so map the provider id
            // back to the bundled brand drawable here. Without this, every provider
            // loaded from assets/providers.json falls through to the letter fallback.
            iconResId       = brandResIdForProvider(id),
            // These two gate live model fetch (↻ Refresh) and the URL-config field.
            // Default to true so JSON-loaded providers don't silently lose live fetch
            // (only Ollama overrides isUrlConfigurable). Without reading them here the
            // JSON path forces both false, diverging from Providers.ALL.
            supportsLiveFetch  = obj.optBoolean("supportsLiveFetch", true),
            isUrlConfigurable  = obj.optBoolean("isUrlConfigurable", id == "ollama"),
        )
    }

    /** Provider id → bundled brand drawable resource id. Mirror of the
     *  iconResId assignments in Providers.kt for the static constants —
     *  needed because JSON deserialization can't carry resource ids. */
    private fun brandResIdForProvider(id: String): Int = when (id) {
        "nvidia_nim"    -> com.claudecodesetup.R.drawable.ic_brand_nvidia
        "openrouter"    -> com.claudecodesetup.R.drawable.ic_brand_openrouter
        "gemini"        -> com.claudecodesetup.R.drawable.ic_brand_gemini
        "meta_llama"    -> com.claudecodesetup.R.drawable.ic_brand_meta
        "deepseek"      -> com.claudecodesetup.R.drawable.ic_brand_deepseek
        "qwen"          -> com.claudecodesetup.R.drawable.ic_brand_qwen
        "mistral"       -> com.claudecodesetup.R.drawable.ic_brand_mistral
        "ollama"        -> com.claudecodesetup.R.drawable.ic_brand_ollama
        "anthropic"     -> com.claudecodesetup.R.drawable.ic_brand_claude
        "anthropic_api" -> com.claudecodesetup.R.drawable.ic_brand_claude
        else            -> 0   // groq, kimi, etc. — no CC0 mark bundled
    }

    private fun parseMalaysiaStatus(s: String) = when (s) {
        "GREEN"  -> MalaysiaStatus.GREEN
        "RED"    -> MalaysiaStatus.RED
        else     -> MalaysiaStatus.YELLOW
    }

    /**
     * Fetch all models from OpenRouter's public models endpoint.
     * Free models (ID ends with ":free" or price == 0.0) get Cap.FREE added to their caps.
     * Paid models are included without Cap.FREE.
     * Throws on network error or bad API key — caller should show a toast.
     */
    suspend fun fetchOpenRouterModels(apiKey: String): List<AiModel> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("Empty response")
            }
            val data = JSONObject(body).getJSONArray("data")
            val models = mutableListOf<AiModel>()
            for (i in 0 until data.length()) {
                val m       = data.getJSONObject(i)
                val id      = m.getString("id")
                val pricing = m.optJSONObject("pricing")
                val prompt  = pricing?.optString("prompt", "1")?.toDoubleOrNull() ?: 1.0
                val compl   = pricing?.optString("completion", "1")?.toDoubleOrNull() ?: 1.0
                val isFree  = id.endsWith(":free") || (prompt == 0.0 && compl == 0.0)
                val name    = m.optString("name", "").ifEmpty { id }
                val caps    = Providers.deriveCaps(id).toMutableSet()
                if (isFree) caps += Cap.FREE
                models.add(AiModel(name, id, caps))
            }
            models.sortedBy { it.modelId }
        }

    /**
     * Unified model fetch — dispatches to the right implementation per provider.
     * Falls back silently to the static model list on any failure.
     */
    suspend fun fetchModels(provider: Provider, apiKey: String): List<AiModel> = when (provider.id) {
        "openrouter"  -> fetchOpenRouterModels(apiKey)
        "nvidia_nim"  -> fetchNvidiaFreeModels(apiKey)
        "gemini"      -> fetchGeminiModels(apiKey)
        "groq"        -> fetchOpenAiStyleModels("https://api.groq.com/openai/v1/models", apiKey, provider, isAlwaysFree = true)
        "deepseek"    -> fetchOpenAiStyleModels("https://api.deepseek.com/models", apiKey, provider)
        "kimi"        -> fetchOpenAiStyleModels("https://api.moonshot.ai/v1/models", apiKey, provider)
        "qwen"        -> fetchOpenAiStyleModels("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/models", apiKey, provider)
        "mistral"     -> fetchOpenAiStyleModels("https://api.mistral.ai/v1/models", apiKey, provider)
        "anthropic"     -> fetchAnthropicModels(apiKey)
        "anthropic_api" -> fetchAnthropicModels(apiKey)
        "meta_llama"  -> fetchOpenAiStyleModels("https://api.llama.com/v1/models", apiKey, provider)
        "ollama"      -> fetchOllamaModels(provider.baseUrl.ifEmpty { "http://localhost:11434" }, apiKey)
        else          -> provider.models
    }

    /** Fetch models from Gemini's own /v1beta/models endpoint. */
    suspend fun fetchGeminiModels(apiKey: String): List<AiModel> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                .header("Accept", "application/json")
                .build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("Empty response")
            }
            val arr = JSONObject(body).getJSONArray("models")
            val models = mutableListOf<AiModel>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods")
                val supportsChat = methods != null && (0 until methods.length()).any {
                    methods.getString(it) == "generateContent"
                }
                if (!supportsChat) continue
                val id = m.getString("name").removePrefix("models/")
                val name = m.optString("displayName", "").ifEmpty { id }
                models.add(AiModel(name, id, Providers.deriveCaps(id) + Cap.FREE))
            }
            models.sortedBy { it.modelId }
        }

    /** Fetch models from Anthropic's /v1/models endpoint. */
    suspend fun fetchAnthropicModels(apiKey: String): List<AiModel> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/models")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Accept", "application/json")
                .build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("Empty response")
            }
            val arr = JSONObject(body).getJSONArray("data")
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val id = m.getString("id")
                val name = m.optString("display_name", "").ifEmpty { id }
                AiModel(name, id, Providers.deriveCaps(id))
            }.sortedByDescending { it.modelId }
        }

    /** Generic OpenAI-compatible /v1/models fetch (Groq, DeepSeek, Kimi, Meta Llama). */
    private suspend fun fetchOpenAiStyleModels(url: String, apiKey: String, provider: Provider, isAlwaysFree: Boolean = false): List<AiModel> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("Empty response")
            }
            val data = JSONObject(body).getJSONArray("data")
            (0 until data.length()).map { i ->
                val m = data.getJSONObject(i)
                val id = m.getString("id")
                val rawName = m.optString("name", "").ifEmpty {
                    id.substringAfterLast("/").split("-")
                        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                }
                val caps = Providers.deriveCaps(id).toMutableSet()
                if (isAlwaysFree) caps += Cap.FREE
                AiModel(rawName, id, caps)
            }.sortedBy { it.modelId }
        }

    /** Fetch models from an Ollama-compatible server (tries /api/tags first, then /v1/models). */
    suspend fun fetchOllamaModels(baseUrl: String, apiKey: String): List<AiModel> =
        withContext(Dispatchers.IO) {
            val normalized = baseUrl.trimEnd('/')
            // Strip trailing /v1 to get the server root for path construction
            val root = if (normalized.endsWith("/v1")) normalized.dropLast(3) else normalized
            // Try Ollama native /api/tags
            try {
                val req = Request.Builder().url("$root/api/tags")
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .build()
                val body = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    resp.body?.string() ?: throw Exception("Empty response")
                }
                val arr = JSONObject(body).getJSONArray("models")
                return@withContext (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    val id = m.getString("name")
                    AiModel(id, id, setOf(Cap.FREE, Cap.TOOLS))
                }.sortedBy { it.modelId }
            } catch (_: Exception) {}
            // Fallback: OpenAI-compat /v1/models
            val req = Request.Builder().url("$root/v1/models")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("Empty response")
            }
            val data = JSONObject(body).getJSONArray("data")
            (0 until data.length()).map { i ->
                val id = data.getJSONObject(i).getString("id")
                AiModel(id, id, setOf(Cap.FREE, Cap.TOOLS))
            }.sortedBy { it.modelId }
        }

    /**
     * Fetch all models from NVIDIA NIM's OpenAI-compatible /v1/models endpoint.
     * All returned models are free-tier accessible (rate-limited).
     */
    suspend fun fetchNvidiaFreeModels(apiKey: String): List<AiModel> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://integrate.api.nvidia.com/v1/models")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string() ?: throw Exception("Empty response")
            }
            val data = JSONObject(body).getJSONArray("data")
            val seen = mutableSetOf<String>()
            val models = mutableListOf<AiModel>()
            for (i in 0 until data.length()) {
                val m = data.getJSONObject(i)
                val id = m.getString("id")
                if (!seen.add(id)) continue
                val rawName = id.substringAfterLast("/")
                val name = rawName.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                models.add(AiModel(name, id, Providers.deriveCaps(id) + Cap.FREE))
            }
            models.sortedBy { it.modelId }
        }

}
