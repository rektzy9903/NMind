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

    // Set to a public URL to enable live updates (e.g. a public Gist raw URL).
    // Empty string means always use the bundled asset.
    private const val REMOTE_URL = ""

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
        if (REMOTE_URL.isNotEmpty()) {
            try {
                val json = fetchRemote(REMOTE_URL)
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

    private fun loadAsset(context: Context): String =
        context.assets.open(ASSET_PATH).bufferedReader().readText()

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
        return Provider(
            id              = obj.getString("id"),
            name            = obj.getString("name"),
            signupUrl       = obj.getString("signupUrl"),
            rateLimit       = obj.getString("rateLimit"),
            malaysiaStatus  = parseMalaysiaStatus(obj.getString("malaysiaStatus")),
            malaysiaNote    = obj.getString("malaysiaNote"),
            warningNote     = obj.optString("warningNote").takeIf { it.isNotEmpty() },
            baseUrl         = obj.getString("baseUrl"),
            requiresProxy   = obj.getBoolean("requiresProxy"),
            requiresApiKey  = obj.optBoolean("requiresApiKey", true),
            models          = models
        )
    }

    private fun parseMalaysiaStatus(s: String) = when (s) {
        "GREEN"  -> MalaysiaStatus.GREEN
        "RED"    -> MalaysiaStatus.RED
        else     -> MalaysiaStatus.YELLOW
    }

    // Update REMOTE_URL at runtime (e.g. from settings)
    fun remoteUrl(): String = REMOTE_URL
}
