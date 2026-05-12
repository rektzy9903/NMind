package com.claudecodesetup.managers

import android.content.Context
import android.util.Log
import com.claudecodesetup.NodeEngine
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Providers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Socket

/**
 * Manages the embedded Node.js bridge.
 *
 * The bridge is bridge.js (assets/nodejs-project/bridge.js) which:
 *   - On first run: downloads and installs @anthropic-ai/claude-code from npm
 *   - After install: opens TCP port 8083; each connection gets its own claude process
 *
 * Config is passed via a JSON file in filesDir so provider changes propagate
 * to new sessions without restarting Node.js.
 */
class NodeBridgeManager(private val context: Context) {

    companion object {
        const val BRIDGE_PORT = 8083
        const val BRIDGE_HOST = "127.0.0.1"
        private const val TAG = "NodeBridgeManager"

        private const val CONFIG_FILE   = "bridge_config.json"
        const val SETUP_LOG_FILE        = "setup.log"
        const val SETUP_DONE_FILE       = "setup_done"
        const val SETUP_FAILED_FILE     = "setup_failed"
    }

    // ─── Bridge reachability ──────────────────────────────────────────────────

    fun isBridgeReachable(): Boolean = try {
        Socket(BRIDGE_HOST, BRIDGE_PORT).use { true }
    } catch (_: Exception) { false }

    fun openSession(): Socket? = try {
        Socket(BRIDGE_HOST, BRIDGE_PORT)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open session socket", e)
        null
    }

    // ─── Setup helpers ────────────────────────────────────────────────────────

    fun isSetupDone(): Boolean =
        File(context.filesDir, SETUP_DONE_FILE).exists() && isBridgeReachable()

    fun isSetupFailed(): Boolean =
        File(context.filesDir, SETUP_FAILED_FILE).exists()

    fun clearSetupFailedFlag() =
        File(context.filesDir, SETUP_FAILED_FILE).delete()

    fun readSetupLog(): String = try {
        File(context.filesDir, SETUP_LOG_FILE).readText()
    } catch (_: Exception) { "" }

    // ─── Start Node.js ────────────────────────────────────────────────────────

    fun startBridge(mode: String, apiKey: String, modelId: String, baseUrl: String, providerId: String = "") {
        writeConfig(mode, apiKey, modelId, baseUrl, providerId)
        startNodeEngine()
    }

    /** Re-write bridge_config.json from current prefs without restarting Node.js.
     *  bridge.js reads the config fresh on every spawn, so the next message picks
     *  up the new model immediately. */
    fun refreshConfig(prefs: AppPreferences) {
        writeConfig(
            mode       = prefs.getLoginMode(),
            apiKey     = prefs.getApiKey(),
            modelId    = prefs.getModelId(),
            baseUrl    = prefs.getBaseUrl(),
            providerId = prefs.getProviderId()
        )
    }

    fun startSetup() {
        // Clearing setup_failed is the "retry" signal that bridge.js polls for.
        // Do NOT clear the log here — bridge.js owns the log and clears it at
        // the start of each install attempt so the user can read previous errors
        // up until the retry actually begins.
        clearSetupFailedFlag()
        File(context.filesDir, SETUP_DONE_FILE).delete()
        startNodeEngine()
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun startNodeEngine() {
        val bridgeFile = ensureBridgeJs() ?: return
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val launcherPath = "$nativeLibDir/libnode-launcher.so"
        NodeEngine.startWithArguments(
            arrayOf("node", bridgeFile.absolutePath, context.filesDir.absolutePath, launcherPath)
        )
    }

    private fun ensureBridgeJs(): File? {
        val dest = File(context.filesDir, "bridge.js")
        return try {
            context.assets.open("nodejs-project/bridge.js").use { src ->
                dest.outputStream().use { src.copyTo(it) }
            }
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy bridge.js from assets", e)
            null
        }
    }

    private fun writeConfig(mode: String, apiKey: String, modelId: String, baseUrl: String, providerId: String = "") {
        val isSubscription = mode == AppPreferences.MODE_SUBSCRIPTION
        val authToken = if (!isSubscription) "freecc" else ""
        val effectiveBaseUrl = if (!isSubscription) "http://127.0.0.1:8082" else baseUrl
        val providerUrl = if (!isSubscription) baseUrl else ""
        // Build model list for fallback: use the provider's static model list.
        // bridge.js uses this to auto-switch on persistent 429s.
        val models = Providers.byId(providerId)?.models ?: emptyList()
        val modelList = JSONArray().apply { models.forEach { put(it.modelId) } }
        val json = JSONObject().apply {
            put("mode",        mode)
            put("apiKey",      apiKey)
            put("modelId",     modelId)
            put("baseUrl",     effectiveBaseUrl)
            put("authToken",   authToken)
            put("providerUrl", providerUrl)
            put("modelList",   modelList)
        }
        try {
            File(context.filesDir, CONFIG_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Could not write bridge config", e)
        }
    }
}
