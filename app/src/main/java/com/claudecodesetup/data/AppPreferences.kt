package com.claudecodesetup.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AppPreferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "claude_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("claude_prefs", Context.MODE_PRIVATE)
    }

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

    fun getSkipMalaysiaPrompt(): Boolean = prefs.getBoolean(KEY_SKIP_MALAYSIA, false)
    fun setSkipMalaysiaPrompt(skip: Boolean) = prefs.edit().putBoolean(KEY_SKIP_MALAYSIA, skip).apply()

    // ─── Projects ────────────────────────────────────────────────────────────

    fun getProjectsJson(): String = prefs.getString(KEY_PROJECTS, "[]") ?: "[]"
    fun saveProjectsJson(json: String) = prefs.edit().putString(KEY_PROJECTS, json).apply()

    // ─── MCP Servers ─────────────────────────────────────────────────────────

    fun getMcpServersJson(): String = prefs.getString(KEY_MCP_SERVERS, "[]") ?: "[]"
    fun saveMcpServersJson(json: String) = prefs.edit().putString(KEY_MCP_SERVERS, json).apply()

    fun getMcpStdioServersJson(): String = prefs.getString(KEY_MCP_STDIO_SERVERS, "[]") ?: "[]"
    fun saveMcpStdioServersJson(json: String) = prefs.edit().putString(KEY_MCP_STDIO_SERVERS, json).apply()

    // ─── TTS ─────────────────────────────────────────────────────────────────

    fun getTtsEnabled(): Boolean = prefs.getBoolean(KEY_TTS_ENABLED, false)
    fun setTtsEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()

    // ─── Overlay ─────────────────────────────────────────────────────────────

    fun getOverlayEnabled(): Boolean = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)
    fun setOverlayEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()

    // ─── Misc ────────────────────────────────────────────────────────────────

    fun getInstalledClaudeVersion(): String = prefs.getString(KEY_CLAUDE_VERSION, "") ?: ""
    fun setInstalledClaudeVersion(v: String) =
        prefs.edit().putString(KEY_CLAUDE_VERSION, v).apply()

    fun getLanguage(): String = prefs.getString(KEY_LANGUAGE, "en") ?: "en"
    fun setLanguage(lang: String) = prefs.edit().putString(KEY_LANGUAGE, lang).apply()

    // ─── Clear ───────────────────────────────────────────────────────────────

    fun clearAll() = prefs.edit().clear().apply()

    fun clearProviderOnly() {
        prefs.edit()
            .remove(KEY_PROVIDER_SET)
            .remove(KEY_LOGIN_MODE)
            .remove(KEY_PROVIDER_ID)
            .remove(KEY_API_KEY)
            .remove(KEY_MODEL_ID)
            .remove(KEY_BASE_URL)
            .apply()
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
        private const val KEY_LANGUAGE            = "language"
        private const val KEY_CLAUDE_VERSION      = "claude_version"
        private const val KEY_PROJECT_PATH        = "project_path"
        private const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"
        private const val KEY_SKIP_MALAYSIA        = "skip_malaysia_prompt"
        private const val KEY_PROJECTS             = "projects_json"
        private const val KEY_MCP_SERVERS          = "mcp_servers_json"
        private const val KEY_MCP_STDIO_SERVERS    = "mcp_stdio_servers_json"
        private const val KEY_TTS_ENABLED          = "tts_enabled"
        private const val KEY_OVERLAY_ENABLED      = "overlay_enabled"

        const val MODE_SUBSCRIPTION = "subscription"
        const val MODE_PROXY        = "proxy"
        const val MODE_GEMINI       = "gemini"
    }
}
