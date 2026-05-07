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
        // Fallback to standard prefs if encryption fails (e.g., hardware issue)
        context.getSharedPreferences("claude_prefs", Context.MODE_PRIVATE)
    }

    // ─── Setup state ────────────────────────────────────────────────────────

    fun isSetupComplete(): Boolean = prefs.getBoolean(KEY_SETUP_DONE, false)
    fun setSetupComplete(done: Boolean) = prefs.edit().putBoolean(KEY_SETUP_DONE, done).apply()

    fun getSetupStep(): Int = prefs.getInt(KEY_SETUP_STEP, 0)
    fun setSetupStep(step: Int) = prefs.edit().putInt(KEY_SETUP_STEP, step).apply()

    fun getDetectedArch(): String = prefs.getString(KEY_ARCH, "") ?: ""
    fun setDetectedArch(arch: String) = prefs.edit().putString(KEY_ARCH, arch).apply()

    // ─── Provider config ─────────────────────────────────────────────────────

    fun isProviderConfigured(): Boolean = prefs.getBoolean(KEY_PROVIDER_SET, false)
    fun setProviderConfigured(set: Boolean) = prefs.edit().putBoolean(KEY_PROVIDER_SET, set).apply()

    fun getLoginMode(): String = prefs.getString(KEY_LOGIN_MODE, MODE_PROXY) ?: MODE_PROXY
    fun setLoginMode(mode: String) = prefs.edit().putString(KEY_LOGIN_MODE, mode).apply()

    fun getProviderId(): String = prefs.getString(KEY_PROVIDER_ID, "") ?: ""
    fun setProviderId(id: String) = prefs.edit().putString(KEY_PROVIDER_ID, id).apply()

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
    fun setApiKey(key: String) = prefs.edit().putString(KEY_API_KEY, key).apply()

    fun getModelId(): String = prefs.getString(KEY_MODEL_ID, "") ?: ""
    fun setModelId(id: String) = prefs.edit().putString(KEY_MODEL_ID, id).apply()

    fun getBaseUrl(): String = prefs.getString(KEY_BASE_URL, "") ?: ""
    fun setBaseUrl(url: String) = prefs.edit().putString(KEY_BASE_URL, url).apply()

    // ─── Session ─────────────────────────────────────────────────────────────

    fun isSessionActive(): Boolean = prefs.getBoolean(KEY_SESSION_ACTIVE, false)
    fun setSessionActive(active: Boolean) = prefs.edit().putBoolean(KEY_SESSION_ACTIVE, active).apply()

    // ─── Language ────────────────────────────────────────────────────────────

    fun getInstalledClaudeVersion(): String = prefs.getString(KEY_CLAUDE_VERSION, "") ?: ""
    fun setInstalledClaudeVersion(v: String) = prefs.edit().putString(KEY_CLAUDE_VERSION, v).apply()

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
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_SETUP_STEP = "setup_step"
        private const val KEY_ARCH = "cpu_arch"
        private const val KEY_PROVIDER_SET = "provider_set"
        private const val KEY_LOGIN_MODE = "login_mode"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SESSION_ACTIVE = "session_active"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_CLAUDE_VERSION = "claude_version"

        const val MODE_SUBSCRIPTION = "subscription"
        const val MODE_PROXY = "proxy"
        const val MODE_GEMINI = "gemini"
    }
}
