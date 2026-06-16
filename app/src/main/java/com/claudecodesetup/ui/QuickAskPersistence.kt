package com.claudecodesetup.ui

import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Providers
import com.claudecodesetup.discussion.Speaker
import com.claudecodesetup.quickask.ImageGen

/**
 * Persists ONLY the last-used speaker (provider + model). Conversation
 * history is intentionally NOT persisted — closing the activity loses
 * the chat per MVP scope.
 *
 * Format on disk: "<providerId>:<modelId>" string. API key is
 * re-resolved at load time so rotating a key never leaves a stale
 * copy behind.
 */
object QuickAskPersistence {
    fun saveSpeaker(prefs: AppPreferences, speaker: Speaker) {
        prefs.saveQuickAskLastSpeaker("${speaker.provider.id}:${speaker.model.modelId}")
    }

    fun loadSpeaker(prefs: AppPreferences): Speaker? {
        val raw = prefs.getQuickAskLastSpeaker()
        if (raw.isEmpty()) return null
        val parts = raw.split(":", limit = 2)
        if (parts.size != 2) return null
        val (pid, mid) = parts[0] to parts[1]
        // Image-gen routes live outside Providers.ALL — resolve them first.
        ImageGen.availableSpeakers(prefs).firstOrNull { it.id == raw }?.let { return it }
        val prov = Providers.ALL.firstOrNull { it.id == pid } ?: Providers.byId(pid) ?: return null
        val model = prov.models.firstOrNull { it.modelId == mid } ?: return null
        val apiKey = prefs.getApiKeyForProvider(pid)
        if (apiKey.isEmpty()) return null  // key was rotated away
        val custom = prefs.getCustomBaseUrlForProvider(pid)
        val baseUrl = if (custom.isNotEmpty()) custom else prov.baseUrl
        return Speaker(prov, model, apiKey, baseUrl)
    }
}
