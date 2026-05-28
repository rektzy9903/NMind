package com.claudecodesetup.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AppPreferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private var _isEncrypted = true

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "claude_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e("AppPreferences", "CRITICAL: EncryptedSharedPreferences unavailable — API keys will NOT be encrypted. Cause: ${e.message}")
        _isEncrypted = false
        context.getSharedPreferences("claude_prefs", Context.MODE_PRIVATE)
    }

    /** True if API keys and preferences are stored in encrypted storage. */
    val isEncrypted: Boolean get() = _isEncrypted

    // ─── Node.js / bridge setup ──────────────────────────────────────────────

    /** True after bridge.js completed npm install and port 8083 was detected. */
    fun isNodeSetupComplete(): Boolean = prefs.getBoolean(KEY_NODE_SETUP_DONE, false)
    fun setNodeSetupComplete(done: Boolean) =
        prefs.edit().putBoolean(KEY_NODE_SETUP_DONE, done).apply()

    // ─── Provider config ─────────────────────────────────────────────────────

    fun isProviderConfigured(): Boolean = prefs.getBoolean(KEY_PROVIDER_SET, false)
    fun setProviderConfigured(set: Boolean) =
        prefs.edit().putBoolean(KEY_PROVIDER_SET, set).apply()

    fun getLoginMode(): String = prefs.getString(KEY_LOGIN_MODE, MODE_PROXY) ?: MODE_PROXY
    fun setLoginMode(mode: String) = prefs.edit().putString(KEY_LOGIN_MODE, mode).apply()

    fun getProviderId(): String = prefs.getString(KEY_PROVIDER_ID, "") ?: ""
    fun setProviderId(id: String) = prefs.edit().putString(KEY_PROVIDER_ID, id).apply()

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
    fun setApiKey(key: String) = prefs.edit().putString(KEY_API_KEY, key).apply()

    fun getApiKeyForProvider(providerId: String): String =
        prefs.getString("api_key_$providerId", "") ?: ""
    fun setApiKeyForProvider(providerId: String, key: String) =
        prefs.edit().putString("api_key_$providerId", key).apply()

    fun getModelId(): String = prefs.getString(KEY_MODEL_ID, "") ?: ""
    fun setModelId(id: String) = prefs.edit().putString(KEY_MODEL_ID, id).apply()

    fun getBaseUrl(): String = prefs.getString(KEY_BASE_URL, "") ?: ""
    fun setBaseUrl(url: String) = prefs.edit().putString(KEY_BASE_URL, url).apply()

    // ─── Session ─────────────────────────────────────────────────────────────

    fun isSessionActive(): Boolean = prefs.getBoolean(KEY_SESSION_ACTIVE, false)
    fun setSessionActive(active: Boolean) =
        prefs.edit().putBoolean(KEY_SESSION_ACTIVE, active).apply()

    // ─── Project ─────────────────────────────────────────────────────────────

    fun getProjectPath(): String = prefs.getString(KEY_PROJECT_PATH, "") ?: ""
    fun setProjectPath(path: String) = prefs.edit().putString(KEY_PROJECT_PATH, path).apply()

    fun getCustomSystemPrompt(): String = prefs.getString(KEY_CUSTOM_SYSTEM_PROMPT, "") ?: ""
    fun setCustomSystemPrompt(prompt: String) = prefs.edit().putString(KEY_CUSTOM_SYSTEM_PROMPT, prompt).apply()

    // ─── Projects ────────────────────────────────────────────────────────────

    fun getProjectsJson(): String = prefs.getString(KEY_PROJECTS, "[]") ?: "[]"
    fun saveProjectsJson(json: String) = prefs.edit().putString(KEY_PROJECTS, json).apply()

    // ─── MCP Servers ─────────────────────────────────────────────────────────

    fun getMcpServersJson(): String = prefs.getString(KEY_MCP_SERVERS, "[]") ?: "[]"
    fun saveMcpServersJson(json: String) = prefs.edit().putString(KEY_MCP_SERVERS, json).apply()

    fun getMcpStdioServersJson(): String = prefs.getString(KEY_MCP_STDIO_SERVERS, "[]") ?: "[]"
    fun saveMcpStdioServersJson(json: String) = prefs.edit().putString(KEY_MCP_STDIO_SERVERS, json).apply()

    // ─── Discussion (last config remembered, option 3B) ──────────────────────
    fun getDiscussionLastConfigJson(): String = prefs.getString("discussion_last_config", "") ?: ""
    fun saveDiscussionLastConfigJson(json: String) = prefs.edit().putString("discussion_last_config", json).apply()

    // ─── Quick Ask (last speaker only — no transcript persistence) ───────────
    fun getQuickAskLastSpeaker(): String = prefs.getString("quickask_last_speaker", "") ?: ""
    fun saveQuickAskLastSpeaker(s: String) = prefs.edit().putString("quickask_last_speaker", s).apply()

    // ─── TTS ─────────────────────────────────────────────────────────────────

    fun getTtsEnabled(): Boolean = prefs.getBoolean(KEY_TTS_ENABLED, false)
    fun setTtsEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()

    // ─── Live provider updates ────────────────────────────────────────────────

    fun getProviderRemoteUrl(): String = prefs.getString(KEY_PROVIDER_REMOTE_URL, "") ?: ""
    fun setProviderRemoteUrl(url: String) = prefs.edit().putString(KEY_PROVIDER_REMOTE_URL, url).apply()

    fun getPtyCols(): Int = prefs.getInt("pty_cols", 220)
    fun setPtyCols(cols: Int) = prefs.edit().putInt("pty_cols", cols).apply()

    fun getPtyRows(): Int = prefs.getInt("pty_rows", 50)
    fun setPtyRows(rows: Int) = prefs.edit().putInt("pty_rows", rows).apply()

    // ─── Per-provider custom server URL (URL-configurable providers like Ollama) ─

    /** Saved URL entered by the user for a URL-configurable provider. */
    fun getCustomBaseUrlForProvider(providerId: String): String =
        prefs.getString("custom_url_$providerId", "") ?: ""
    fun setCustomBaseUrlForProvider(providerId: String, url: String) =
        prefs.edit().putString("custom_url_$providerId", url).apply()

    // ─── Misc ────────────────────────────────────────────────────────────────

    fun getInstalledClaudeVersion(): String = prefs.getString(KEY_CLAUDE_VERSION, "") ?: ""
    fun setInstalledClaudeVersion(v: String) =
        prefs.edit().putString(KEY_CLAUDE_VERSION, v).apply()

    // ─── Preferences toggles ─────────────────────────────────────────────────

    fun isResponseNotificationsEnabled(): Boolean = prefs.getBoolean(KEY_RESPONSE_NOTIFICATIONS, true)
    fun setResponseNotificationsEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_RESPONSE_NOTIFICATIONS, enabled).apply()

    fun isAutoStartOnBoot(): Boolean = prefs.getBoolean(KEY_AUTO_START_BOOT, false)
    fun setAutoStartOnBoot(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO_START_BOOT, enabled).apply()

    // ─── Scheduled prompts ───────────────────────────────────────────────────

    fun getScheduledPromptsJson(): String = prefs.getString(KEY_SCHEDULED_PROMPTS, "[]") ?: "[]"
    fun saveScheduledPromptsJson(json: String) = prefs.edit().putString(KEY_SCHEDULED_PROMPTS, json).apply()

    // ─── Clear ───────────────────────────────────────────────────────────────

    fun clearAll() = prefs.edit().clear().apply()

    fun clearProviderOnly() {
        val editor = prefs.edit()
            .remove(KEY_PROVIDER_SET)
            .remove(KEY_LOGIN_MODE)
            .remove(KEY_PROVIDER_ID)
            .remove(KEY_API_KEY)
            .remove(KEY_MODEL_ID)
            .remove(KEY_BASE_URL)
        Providers.ALL.forEach { provider ->
            editor.remove("api_key_${provider.id}")
            editor.remove("custom_url_${provider.id}")
        }
        editor.apply()
    }

    companion object {
        private const val KEY_NODE_SETUP_DONE     = "node_setup_done"
        private const val KEY_PROVIDER_SET        = "provider_set"
        private const val KEY_LOGIN_MODE          = "login_mode"
        private const val KEY_PROVIDER_ID         = "provider_id"
        private const val KEY_API_KEY             = "api_key"
        private const val KEY_MODEL_ID            = "model_id"
        private const val KEY_BASE_URL            = "base_url"
        private const val KEY_SESSION_ACTIVE      = "session_active"
        private const val KEY_CLAUDE_VERSION      = "claude_version"
        private const val KEY_PROJECT_PATH        = "project_path"
        private const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"
        private const val KEY_PROJECTS             = "projects_json"
        private const val KEY_MCP_SERVERS          = "mcp_servers_json"
        private const val KEY_MCP_STDIO_SERVERS    = "mcp_stdio_servers_json"
        private const val KEY_TTS_ENABLED          = "tts_enabled"
        private const val KEY_PROVIDER_REMOTE_URL  = "provider_remote_url"
        private const val KEY_SCHEDULED_PROMPTS    = "scheduled_prompts_json"
        private const val KEY_RESPONSE_NOTIFICATIONS = "response_notifications"
        private const val KEY_AUTO_START_BOOT       = "auto_start_boot"

        const val MODE_SUBSCRIPTION = "subscription"
        const val MODE_PROXY        = "proxy"
        const val MODE_GEMINI       = "gemini"
    }
}
