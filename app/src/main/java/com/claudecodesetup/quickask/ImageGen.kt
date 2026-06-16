package com.claudecodesetup.quickask

import android.content.Context
import android.util.Base64
import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Cap
import com.claudecodesetup.data.MalaysiaStatus
import com.claudecodesetup.data.Provider
import com.claudecodesetup.data.Providers
import com.claudecodesetup.discussion.Speaker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Native (bridge-free) image generation for Quick Ask. Deliberately kept OUT of
 * [Providers.ALL] — these are NOT terminal engines, so they must never appear in
 * the login / provider-selection flow. They surface ONLY in the Quick Ask model
 * picker under the "Image" filter chip.
 *
 * Routes:
 *   - Pollinations (keyless, FLUX) — verified working from a phone's residential
 *     IP, zero setup. The default.
 *   - Gemini "nano-banana" (gemini-2.5-flash-image) — uses the configured Gemini
 *     API key for higher quality.
 *
 * Video is intentionally absent: no free keyless route exists and it needs an
 * async submit+poll flow. The "Video" filter chip shows a "coming soon" state.
 */
object ImageGen {

    /** Synthetic provider for the keyless Pollinations route. id is namespaced
     *  with `_img` so it can never collide with a real terminal provider id. */
    private val POLLINATIONS = Provider(
        id = "pollinations_img",
        name = "Pollinations",
        signupUrl = "https://pollinations.ai",
        rateLimit = "Free · keyless",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "",
        baseUrl = "https://image.pollinations.ai",
        requiresProxy = false,
        requiresApiKey = false,
        models = emptyList(),
    )

    private val POLLINATIONS_MODEL = AiModel(
        name = "Pollinations FLUX",
        modelId = "flux",
        caps = setOf(Cap.IMAGE, Cap.FREE),
        description = "Keyless · zero setup",
    )

    private val GEMINI_IMAGE_MODEL = AiModel(
        name = "Gemini Image",
        modelId = "gemini-2.5-flash-image",
        caps = setOf(Cap.IMAGE, Cap.FREE),
        description = "nano-banana · uses Gemini key",
    )

    /**
     * The image-gen speakers available right now. Pollinations is always present
     * (no key needed); Gemini-image only when a Gemini API key is configured.
     */
    fun availableSpeakers(prefs: AppPreferences): List<Speaker> {
        val out = mutableListOf<Speaker>()
        out.add(Speaker(POLLINATIONS, POLLINATIONS_MODEL, apiKey = "", baseUrl = POLLINATIONS.baseUrl))
        val geminiKey = prefs.getApiKeyForProvider(Providers.GEMINI.id)
        if (geminiKey.isNotEmpty()) {
            out.add(Speaker(Providers.GEMINI, GEMINI_IMAGE_MODEL, apiKey = geminiKey, baseUrl = Providers.GEMINI.baseUrl))
        }
        return out
    }

    /** True if this speaker is one of ours (an image generator), not a chat model. */
    fun isImageSpeaker(speaker: Speaker): Boolean = Cap.IMAGE in speaker.model.caps

    sealed class Result {
        data class Success(val filePath: String) : Result()
        data class Failure(val message: String) : Result()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)   // image gen can take 10–30s
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Generate an image for [prompt]. Blocking — call from Dispatchers.IO. */
    fun generate(context: Context, speaker: Speaker, prompt: String): Result = try {
        when (speaker.provider.id) {
            POLLINATIONS.id -> generatePollinations(context, prompt)
            Providers.GEMINI.id -> generateGemini(context, speaker.apiKey, speaker.model.modelId, prompt)
            else -> Result.Failure("Unknown image route: ${speaker.provider.id}")
        }
    } catch (e: Exception) {
        Result.Failure(e.message ?: e.javaClass.simpleName)
    }

    private fun generatePollinations(context: Context, prompt: String): Result {
        val url = "https://image.pollinations.ai/prompt/" +
            URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        val req = Request.Builder().url(url).header("Accept", "image/*").get().build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                return Result.Failure("Pollinations HTTP ${res.code}: ${res.body?.string()?.take(160) ?: ""}")
            }
            val bytes = res.body?.bytes() ?: return Result.Failure("Pollinations returned no data")
            val ext = imageExt(bytes) ?: return Result.Failure("Pollinations did not return a valid image")
            return saveImage(context, bytes, ext)
        }
    }

    private fun generateGemini(context: Context, apiKey: String, model: String, prompt: String): Result {
        if (apiKey.isEmpty()) return Result.Failure("Gemini key missing")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
            })
        }
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { res ->
            val raw = res.body?.string() ?: ""
            if (!res.isSuccessful) {
                return Result.Failure("Gemini HTTP ${res.code}: ${raw.take(200)}")
            }
            val b64 = extractGeminiImage(raw)
                ?: return Result.Failure("Gemini returned no image (model may have refused the prompt)")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val ext = imageExt(bytes) ?: "png"
            return saveImage(context, bytes, ext)
        }
    }

    /** Pull the first inlineData.data base64 from a generateContent response. */
    private fun extractGeminiImage(raw: String): String? {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val parts = candidates.getJSONObject(i)
                .optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val inline = parts.getJSONObject(j).optJSONObject("inlineData")
                    ?: parts.getJSONObject(j).optJSONObject("inline_data")
                val data = inline?.optString("data", "")
                if (!data.isNullOrEmpty()) return data
            }
        }
        return null
    }

    /** Detect image type by magic bytes; null if not a recognized image. */
    private fun imageExt(b: ByteArray): String? {
        if (b.size < 12) return null
        fun u(i: Int) = b[i].toInt() and 0xFF
        return when {
            u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47 -> "png"
            u(0) == 0xFF && u(1) == 0xD8 -> "jpeg"
            u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46 -> "gif"
            u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(8) == 0x57 && u(9) == 0x45 -> "webp"
            else -> null
        }
    }

    /** Save to the public gallery (Pictures/NexusMind); fall back to app files. */
    private fun saveImage(context: Context, bytes: ByteArray, ext: String): Result {
        val name = "qa_img_${System.currentTimeMillis()}.$ext"
        val gallery = File("/sdcard/Pictures/NexusMind")
        try {
            if (gallery.exists() || gallery.mkdirs()) {
                val f = File(gallery, name)
                f.writeBytes(bytes)
                return Result.Success(f.absolutePath)
            }
        } catch (_: Exception) { /* fall through to private dir */ }
        return try {
            val f = File(context.filesDir, name)
            f.writeBytes(bytes)
            Result.Success(f.absolutePath)
        } catch (e: Exception) {
            Result.Failure("Could not save image: ${e.message}")
        }
    }
}
