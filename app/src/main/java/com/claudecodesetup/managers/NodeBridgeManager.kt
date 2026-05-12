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

    fun startBridge(mode: String, apiKey: String, modelId: String, baseUrl: String, providerId: String = "",
                    projectPath: String = "", customSystemPrompt: String = "", prefs: AppPreferences? = null) {
        writeConfig(mode, apiKey, modelId, baseUrl, providerId, projectPath, customSystemPrompt)
        writeDeviceContext()
        if (prefs != null) {
            writeProjectsForBridge(prefs)
            writeMcpStdioConfig(prefs)
        }
        startNodeEngine()
    }

    fun writeMcpConfig(prefs: AppPreferences) {
        try {
            val serversJson = prefs.getMcpServersJson()
            val arr = org.json.JSONArray(serversJson)
            if (arr.length() == 0) return
            val mcpServers = org.json.JSONObject()
            for (i in 0 until arr.length()) {
                val server = arr.getJSONObject(i)
                val name = server.optString("name")
                val url = server.optString("url")
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    mcpServers.put(name, org.json.JSONObject().apply {
                        put("url", url)
                        put("type", "sse")
                    })
                }
            }
            val config = org.json.JSONObject().apply {
                put("mcpServers", mcpServers)
            }
            File(context.filesDir, ".claude.json").writeText(config.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Could not write MCP config", e)
        }
    }

    fun writeMcpStdioConfig(prefs: AppPreferences) {
        try {
            val serversJson = prefs.getMcpStdioServersJson()
            val arr = org.json.JSONArray(serversJson)
            if (arr.length() == 0) {
                File(context.filesDir, "mcp_stdio.json").delete()
                return
            }
            val out = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val server = arr.getJSONObject(i)
                val name = server.optString("name")
                val command = server.optString("command")
                val argsStr = server.optString("args")
                if (name.isNotEmpty() && command.isNotEmpty()) {
                    val argsArr = org.json.JSONArray()
                    argsStr.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.forEach { argsArr.put(it) }
                    out.put(org.json.JSONObject().apply {
                        put("name", name)
                        put("command", command)
                        put("args", argsArr)
                    })
                }
            }
            File(context.filesDir, "mcp_stdio.json").writeText(out.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Could not write stdio MCP config", e)
        }
    }

    /** Re-write bridge_config.json from current prefs without restarting Node.js.
     *  bridge.js reads the config fresh on every spawn, so the next message picks
     *  up the new model immediately. */
    fun refreshConfig(prefs: AppPreferences) {
        writeConfig(
            mode               = prefs.getLoginMode(),
            apiKey             = prefs.getApiKey(),
            modelId            = prefs.getModelId(),
            baseUrl            = prefs.getBaseUrl(),
            providerId         = prefs.getProviderId(),
            projectPath        = prefs.getProjectPath(),
            customSystemPrompt = prefs.getCustomSystemPrompt()
        )
        writeDeviceContext()
        writeProjectsForBridge(prefs)
        writeMcpStdioConfig(prefs)
    }

    /** Write projects.json so bridge.js can auto-apply per-project system prompts. */
    fun writeProjectsForBridge(prefs: AppPreferences) {
        try {
            val json = prefs.getProjectsJson().ifBlank { "[]" }
            java.io.File(context.filesDir, "projects.json").writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Could not write projects.json", e)
        }
    }

    fun writeDeviceContext() {
        try {
            val batteryManager = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
            val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm z", java.util.Locale.getDefault()).format(java.util.Date())
            val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            val androidVersion = android.os.Build.VERSION.RELEASE
            val json = org.json.JSONObject().apply {
                put("time", now)
                put("battery", "$batteryLevel%")
                put("device", deviceModel)
                put("androidVersion", "Android $androidVersion")
            }
            java.io.File(context.filesDir, "device_context.json").writeText(json.toString())
        } catch (_: Exception) {}
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

    private fun writeConfig(mode: String, apiKey: String, modelId: String, baseUrl: String,
                            providerId: String = "", projectPath: String = "",
                            customSystemPrompt: String = "") {
        val isSubscription = mode == AppPreferences.MODE_SUBSCRIPTION
        val authToken = if (!isSubscription) "freecc" else ""
        val effectiveBaseUrl = if (!isSubscription) "http://127.0.0.1:8082" else baseUrl
        val providerUrl = if (!isSubscription) baseUrl else ""
        val models = Providers.byId(providerId)?.models ?: emptyList()
        val modelList = JSONArray().apply { models.forEach { put(it.modelId) } }
        val json = JSONObject().apply {
            put("mode",               mode)
            put("apiKey",             apiKey)
            put("modelId",            modelId)
            put("baseUrl",            effectiveBaseUrl)
            put("authToken",          authToken)
            put("providerUrl",        providerUrl)
            put("modelList",          modelList)
            put("projectPath",        projectPath)
            put("customSystemPrompt", customSystemPrompt)
        }
        try {
            File(context.filesDir, CONFIG_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Could not write bridge config", e)
        }
    }
}
