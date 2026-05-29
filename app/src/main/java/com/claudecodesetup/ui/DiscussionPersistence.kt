package com.claudecodesetup.ui

import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Providers
import com.claudecodesetup.discussion.DiscussionConfig
import com.claudecodesetup.discussion.DiscussionMode
import com.claudecodesetup.discussion.HumanRole
import com.claudecodesetup.discussion.Speaker
import org.json.JSONArray
import org.json.JSONObject

/**
 * Option 3B: save the last successful discussion config so the next launch
 * pre-fills the setup screen. We only persist user choices — NOT the
 * transcript itself.
 *
 * API keys are deliberately re-read from AppPreferences at load time, not
 * stored in this file, so rotating a key doesn't leave a stale copy here.
 */
object DiscussionPersistence {

    fun save(prefs: AppPreferences, cfg: DiscussionConfig) {
        try {
            val obj = JSONObject().apply {
                put("topic", cfg.topic)
                put("mode", cfg.mode.name)
                put("maxTurns", cfg.maxTurns)
                put("enableJudge", cfg.enableJudge)
                put("humanRole", cfg.humanRole.name)
                put("speakers", JSONArray().apply {
                    for (s in cfg.speakers) put(JSONObject().apply {
                        put("providerId", s.provider.id)
                        put("modelId", s.model.modelId)
                    })
                })
            }
            prefs.saveDiscussionLastConfigJson(obj.toString())
        } catch (_: Exception) {}
    }

    fun load(prefs: AppPreferences): DiscussionConfig? {
        val raw = prefs.getDiscussionLastConfigJson()
        if (raw.isEmpty()) return null
        return try {
            val obj = JSONObject(raw)
            val mode = try { DiscussionMode.valueOf(obj.optString("mode")) }
                       catch (_: Exception) { DiscussionMode.ROUNDTABLE }
            val arr = obj.optJSONArray("speakers") ?: JSONArray()
            val speakers = mutableListOf<Speaker>()
            for (i in 0 until arr.length()) {
                val so = arr.getJSONObject(i)
                val pid = so.optString("providerId")
                val mid = so.optString("modelId")
                val prov = Providers.ALL.firstOrNull { it.id == pid }
                    ?: Providers.byId(pid)
                    ?: continue
                val model: AiModel = prov.models.firstOrNull { it.modelId == mid }
                    ?: continue
                val apiKey = prefs.getApiKeyForProvider(pid)
                if (apiKey.isEmpty()) continue   // key rotated away; skip silently
                val custom = prefs.getCustomBaseUrlForProvider(pid)
                val baseUrl = if (custom.isNotEmpty()) custom else prov.baseUrl
                speakers.add(Speaker(prov, model, apiKey, baseUrl))
            }
            DiscussionConfig(
                topic = obj.optString("topic"),
                mode = mode,
                speakers = speakers,
                maxTurns = obj.optInt("maxTurns", 6),
                enableJudge = obj.optBoolean("enableJudge", false),
                judgeSpeaker = if (obj.optBoolean("enableJudge") && speakers.isNotEmpty()) speakers.first() else null,
                humanRole = try { HumanRole.valueOf(obj.optString("humanRole", "NONE")) }
                            catch (_: Exception) { HumanRole.NONE },
            )
        } catch (_: Exception) { null }
    }
}
