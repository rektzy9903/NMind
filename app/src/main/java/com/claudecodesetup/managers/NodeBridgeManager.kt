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
import java.util.UUID

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

        private const val CONFIG_FILE      = "bridge_config.json"
        private const val LOCAL_TOKEN_FILE = "local_token"
        const val SETUP_LOG_FILE           = "setup.log"
        const val SETUP_DONE_FILE          = "setup_done"
        const val SETUP_FAILED_FILE        = "setup_failed"

        /** Preview/dated model IDs that have been retired → their stable replacements. */
        private val RETIRED_MODEL_MAP = mapOf(
            "gemini-2.5-flash-preview-05-20" to "gemini-2.5-flash",
            "gemini-2.5-flash-preview-04-17" to "gemini-2.5-flash",
            "gemini-2.5-pro-preview-05-06"   to "gemini-2.5-pro",
            "gemini-2.5-pro-preview-03-25"   to "gemini-2.5-pro"
        )
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
        writeConfig(mode, apiKey, modelId, baseUrl, providerId, projectPath, customSystemPrompt, prefs)
        writeDeviceContext()
        if (prefs != null) writeMcpConfig(prefs)
        startNodeEngine()
    }

    // Writes mcp_config.json (for claude-code --mcp-config) and mcp_http.json
    // (for bridge.js agentic HTTP MCP client), combining HTTP/SSE and stdio servers.
    fun writeMcpConfig(prefs: AppPreferences) {
        val mcpFile     = File(context.filesDir, "mcp_config.json")
        val mcpHttpFile = File(context.filesDir, "mcp_http.json")
        try {
            val mcpServers  = org.json.JSONObject()
            val httpEntries = org.json.JSONArray()

            // HTTP / SSE servers — "sse" is the transport type claude-code 2.1.112 understands
            val httpArr = org.json.JSONArray(prefs.getMcpServersJson())
            for (i in 0 until httpArr.length()) {
                val server = httpArr.getJSONObject(i)
                val name = server.optString("name")
                val url  = server.optString("url")
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    mcpServers.put(name, org.json.JSONObject().apply {
                        put("type", server.optString("transport", "sse"))
                        put("url", url)
                    })
                    // Also add to mcp_http.json for bridge.js agentic client
                    httpEntries.put(org.json.JSONObject().apply {
                        put("name", name)
                        put("url", url)
                    })
                }
            }

            // stdio servers
            val stdioArr = org.json.JSONArray(prefs.getMcpStdioServersJson())
            for (i in 0 until stdioArr.length()) {
                val server  = stdioArr.getJSONObject(i)
                val name    = server.optString("name")
                val command = server.optString("command")
                val argsStr = server.optString("args")
                if (name.isNotEmpty() && command.isNotEmpty()) {
                    val argsArr = org.json.JSONArray()
                    argsStr.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.forEach { argsArr.put(it) }
                    mcpServers.put(name, org.json.JSONObject().apply {
                        put("type", "stdio")
                        put("command", command)
                        put("args", argsArr)
                    })
                }
            }

            if (mcpServers.length() == 0) {
                mcpFile.delete()
                mcpHttpFile.delete()
                return
            }
            mcpFile.writeText(org.json.JSONObject().apply { put("mcpServers", mcpServers) }.toString())
            if (httpEntries.length() > 0) mcpHttpFile.writeText(httpEntries.toString())
            else mcpHttpFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Could not write MCP config", e)
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
            customSystemPrompt = prefs.getCustomSystemPrompt(),
            prefs              = prefs
        )
        writeDeviceContext()
        writeMcpConfig(prefs)
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

    private fun getOrCreateLocalToken(): String {
        val tokenFile = File(context.filesDir, LOCAL_TOKEN_FILE)
        if (tokenFile.exists()) {
            val existing = tokenFile.readText().trim()
            if (existing.isNotEmpty()) return existing
        }
        val token = UUID.randomUUID().toString()
        tokenFile.writeText(token)
        tokenFile.setReadable(false, false)
        tokenFile.setReadable(true, true)
        tokenFile.setWritable(false, false)
        tokenFile.setWritable(true, true)
        return token
    }

    private fun writeConfig(mode: String, apiKey: String, modelId: String, baseUrl: String,
                            providerId: String = "", projectPath: String = "",
                            customSystemPrompt: String = "", prefs: AppPreferences? = null) {
        val isSubscription = mode == AppPreferences.MODE_SUBSCRIPTION
        val effectiveBaseUrl = if (!isSubscription) "http://127.0.0.1:8082" else baseUrl
        // Ollama's OpenAI-compat endpoint is at /v1/chat/completions; users often omit the /v1.
        val normalizedBase = if (providerId == "ollama" && !baseUrl.contains("/v1")) {
            baseUrl.trimEnd('/') + "/v1"
        } else baseUrl
        val providerUrl = if (!isSubscription) normalizedBase else ""
        val provider = Providers.byId(providerId)
        val models = provider?.models ?: emptyList()
        val modelList = JSONArray().apply { models.forEach { put(it.modelId) } }
        // Remap retired model IDs to their stable successors so stored prefs don't break on update.
        val effectiveModelId = RETIRED_MODEL_MAP.getOrDefault(modelId, modelId)
        if (effectiveModelId != modelId && prefs != null) prefs.setModelId(effectiveModelId)
        val localToken = getOrCreateLocalToken()
        val json = JSONObject().apply {
            put("mode",               mode)
            put("apiKey",             apiKey)
            put("modelId",            effectiveModelId)
            put("baseUrl",            effectiveBaseUrl)
            put("providerUrl",        providerUrl)
            put("modelList",          modelList)
            put("projectPath",        projectPath)
            put("customSystemPrompt", customSystemPrompt)
            put("ptyCols",            prefs?.getPtyCols() ?: 220)
            put("ptyRows",            prefs?.getPtyRows() ?: 50)
            put("localToken",         localToken)
        }
        try {
            val configFile = File(context.filesDir, CONFIG_FILE)
            configFile.writeText(json.toString())
            configFile.setReadable(false, false)
            configFile.setReadable(true, true)
            configFile.setWritable(false, false)
            configFile.setWritable(true, true)
        } catch (e: Exception) {
            Log.e(TAG, "Could not write bridge config", e)
        }
    }
}
