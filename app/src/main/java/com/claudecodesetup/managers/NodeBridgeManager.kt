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

        /** Shell-like word split: honours single/double quotes so paths with spaces work. */
        fun shellSplit(s: String): List<String> {
            val result = mutableListOf<String>()
            val buf = StringBuilder()
            var inSingle = false
            var inDouble = false
            for (c in s.trim()) {
                when {
                    inSingle -> if (c == '\'') inSingle = false else buf.append(c)
                    inDouble -> if (c == '"')  inDouble = false else buf.append(c)
                    c == '\'' -> inSingle = true
                    c == '"'  -> inDouble = true
                    c == ' ' || c == '\t' -> { if (buf.isNotEmpty()) { result.add(buf.toString()); buf.clear() } }
                    else -> buf.append(c)
                }
            }
            if (buf.isNotEmpty()) result.add(buf.toString())
            return result
        }
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
                if (!server.optBoolean("enabled", true)) continue
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    // MCP-2: optional headers (auth bearer / API key / etc.). Stored
                    // as a nested JSON object in the per-server config.
                    val headers = server.optJSONObject("headers")
                    mcpServers.put(name, org.json.JSONObject().apply {
                        put("type", server.optString("transport", "sse"))
                        put("url", url)
                    })
                    // mcp_http.json — read by bridge.js for both the agentic client
                    // (callMcpHttpTool) AND the MCP-1 stdio-proxy shim (via env var).
                    httpEntries.put(org.json.JSONObject().apply {
                        put("name", name)
                        put("url", url)
                        if (headers != null && headers.length() > 0) put("headers", headers)
                    })
                }
            }

            // stdio servers
            val stdioEntries = org.json.JSONArray()
            val stdioArr = org.json.JSONArray(prefs.getMcpStdioServersJson())
            for (i in 0 until stdioArr.length()) {
                val server  = stdioArr.getJSONObject(i)
                val name    = server.optString("name")
                val command = server.optString("command")
                val argsStr = server.optString("args")
                if (!server.optBoolean("enabled", true)) continue
                if (name.isNotEmpty() && command.isNotEmpty()) {
                    val argsArr = org.json.JSONArray()
                    shellSplit(argsStr).forEach { argsArr.put(it) }
                    mcpServers.put(name, org.json.JSONObject().apply {
                        put("type", "stdio")
                        put("command", command)
                        put("args", argsArr)
                    })
                    // bridge.js format: { name, command, args[] }
                    stdioEntries.put(org.json.JSONObject().apply {
                        put("name", name)
                        put("command", command)
                        put("args", argsArr)
                    })
                }
            }

            val mcpStdioFile = File(context.filesDir, "mcp_stdio.json")
            if (mcpServers.length() == 0) {
                mcpFile.delete()
                mcpHttpFile.delete()
                mcpStdioFile.delete()
                // Still signal a reload — removing the LAST server must take effect
                // live too, not only after a force-close.
                try { File(context.filesDir, "mcp_reload_requested").createNewFile() } catch (_: Exception) {}
                return
            }
            val mcpTmpFile = File(context.filesDir, "mcp_config.json.tmp")
            mcpTmpFile.writeText(org.json.JSONObject().apply { put("mcpServers", mcpServers) }.toString())
            mcpTmpFile.renameTo(mcpFile)
            if (httpEntries.length() > 0) {
                val mcpHttpTmpFile = File(context.filesDir, "mcp_http.json.tmp")
                mcpHttpTmpFile.writeText(httpEntries.toString())
                mcpHttpTmpFile.renameTo(mcpHttpFile)
            } else mcpHttpFile.delete()
            if (stdioEntries.length() > 0) {
                val mcpStdioTmpFile = File(context.filesDir, "mcp_stdio.json.tmp")
                mcpStdioTmpFile.writeText(stdioEntries.toString())
                mcpStdioTmpFile.renameTo(mcpStdioFile)
            } else mcpStdioFile.delete()
            // MCP-6: drop a marker the bridge.js fs.watch picks up so live
            // sessions soft-reload server set without needing !clear.
            try { File(context.filesDir, "mcp_reload_requested").createNewFile() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Could not write MCP config", e)
        }
    }

    /** Re-write bridge_config.json from current prefs without restarting Node.js.
     *  bridge.js reads the config fresh on every spawn, so the next message picks
     *  up the new model immediately.
     *  If the provider or model changed, writes a history_clear flag so bridge.js
     *  resets --continue on the next spawn (avoids feeding old context to a new model). */
    fun refreshConfig(prefs: AppPreferences) {
        // Detect change against the PREVIOUSLY-WRITTEN config file, not instance
        // fields. NodeBridgeManager is constructed fresh in several places
        // (TerminalActivity.onResume, ProjectManagerActivity, …); instance fields
        // reset to "" each time, so the old code NEVER saw a change → the
        // history_clear_requested marker was never written → the warm proc kept the
        // stale model/provider baked in until a full app restart. The config file
        // survives across instances, so it's the correct source of truth.
        val (prevModel, prevProvider) = readWrittenModelProvider()
        val newModel     = RETIRED_MODEL_MAP.getOrDefault(prefs.getModelId(), prefs.getModelId())
        val newProvider  = prefs.getProviderId()
        lastKnownModel    = newModel
        lastKnownProvider = newProvider

        writeConfig(
            mode               = prefs.getLoginMode(),
            apiKey             = prefs.getApiKey(),
            modelId            = prefs.getModelId(),
            baseUrl            = prefs.getBaseUrl(),
            providerId         = newProvider,
            projectPath        = prefs.getProjectPath(),
            customSystemPrompt = prefs.getCustomSystemPrompt(),
            prefs              = prefs
        )
        writeDeviceContext()
        writeMcpConfig(prefs)

        // Signal bridge to clear --continue history + RESPAWN the warm proc on the
        // next message when model/provider changed (bridge.js runMessage line ~5008
        // kills the warm proc when this marker exists).
        val changed = (prevModel.isNotEmpty() && prevModel != newModel) ||
                      (prevProvider.isNotEmpty() && prevProvider != newProvider)
        if (changed) {
            try { java.io.File(context.filesDir, "history_clear_requested").createNewFile() } catch (_: Exception) {}
        }
    }

    /** Read modelId/providerId from the config file written by the last writeConfig.
     *  Empty pair when the file is missing/unparseable (first run → no change flag). */
    private fun readWrittenModelProvider(): Pair<String, String> = try {
        val f = File(context.filesDir, CONFIG_FILE)
        if (!f.exists()) "" to "" else {
            val o = JSONObject(f.readText())
            o.optString("modelId", "") to o.optString("providerId", "")
        }
    } catch (_: Exception) { "" to "" }

    private var lastKnownModel:    String = ""
    private var lastKnownProvider: String = ""

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

    // ─── First-run engine provisioning (bridge runEngineSetup, file-triggered) ──
    // SetupActivity extracts the rootfs, then drops `provision_requested`; the
    // bridge's watcher runs the Node 22 + claude-code install and writes
    // `engine_provisioned` (= version) or `provision_failed` (= error). Progress
    // streams to setup.log as "[provision] pct=NN TAG msg" (readSetupLog()).
    fun requestProvision() {
        try { File(context.filesDir, "provision_requested").createNewFile() } catch (_: Exception) {}
    }
    fun clearProvisionMarkers() {
        for (n in listOf("engine_provisioned", "provision_failed", "provision_requested"))
            try { File(context.filesDir, n).delete() } catch (_: Exception) {}
    }
    fun isEngineProvisioned(): Boolean = File(context.filesDir, "engine_provisioned").exists()
    fun isProvisionFailed(): Boolean   = File(context.filesDir, "provision_failed").exists()
    /** The claude-code version the bridge detected post-install (empty if absent). */
    fun readClaudeVersion(): String = try {
        File(context.filesDir, "claude_version").readText().trim()
            .ifEmpty { File(context.filesDir, "engine_provisioned").readText().trim() }
    } catch (_: Exception) { "" }

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
            // DEBUG hot-load: if a valid bridge_dev.js exists (downloaded inside
            // node by the `!hotload` terminal command — off the main thread, so no
            // NetworkOnMainThreadException), use it instead of the bundled asset.
            // This makes JS-only iterations need only `git push` + `!hotload` +
            // force-stop/reopen — NO APK rebuild. Sanity-checked so a bad download
            // can't brick the bridge; falls back to the bundled asset otherwise.
            // DEBUG-only — release always ships the audited bundled bridge.js.
            val devBridge = File(context.filesDir, "bridge_dev.js")
            val buildNumRe = Regex("""BRIDGE_BUILD\s*=\s*'b(\d+)""")
            fun bridgeBuildNum(text: String) =
                buildNumRe.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val bundledBuildNum = context.assets.open("nodejs-project/bridge.js")
                .bufferedReader().use { r -> buildNumRe.find(r.readLines().take(30).joinToString("\n"))
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
            val devText = if (com.claudecodesetup.BuildConfig.DEBUG && devBridge.exists() &&
                devBridge.length() > 5000) devBridge.readText() else null
            val devBuildNum = if (devText != null) bridgeBuildNum(devText) else 0
            // Only prefer bridge_dev.js when it is strictly newer than the bundled asset.
            // This prevents a stale hotload (older build number) from shadowing a fixed
            // bundled bridge.js after an in-place APK update.
            val useDev = devText != null && devText.contains("SYS_FENCE") &&
                devBuildNum > bundledBuildNum
            if (useDev) {
                devBridge.copyTo(dest, overwrite = true)
                Log.i(TAG, "bridge.js hot-loaded from bridge_dev.js (b$devBuildNum > bundled b$bundledBuildNum)")
            } else {
                if (devText != null && devBuildNum <= bundledBuildNum)
                    Log.i(TAG, "bridge_dev.js skipped — b$devBuildNum <= bundled b$bundledBuildNum; using bundled")
                context.assets.open("nodejs-project/bridge.js").use { src ->
                    dest.outputStream().use { src.copyTo(it) }
                }
            }
            // MCP-1: also copy the HTTP MCP stdio-proxy shim. bridge.js patchSettings
            // looks for it at filesDir/mcp_http_proxy.js and injects mcpServers entries
            // for each upstream HTTP MCP server. Missing file → HTTP MCP stays
            // agentic-only (current invariant 52 behavior).
            try {
                val proxyDest = File(context.filesDir, "mcp_http_proxy.js")
                context.assets.open("nodejs-project/mcp_http_proxy.js").use { src ->
                    proxyDest.outputStream().use { src.copyTo(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy mcp_http_proxy.js (HTTP MCP in terminal disabled)", e)
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
        // User-disabled tools (Settings → Tools). The proxy strips these from
        // every request so they're never sent — saves tokens AND takes effect,
        // unlike the old permissions.allow approach the '*' wildcard overrode.
        val disabledTools = JSONArray().apply {
            try {
                val arr = JSONArray(prefs?.getDisabledToolsJson() ?: "[]")
                for (i in 0 until arr.length()) put(arr.getString(i))
            } catch (_: Exception) {}
        }
        val json = JSONObject().apply {
            put("mode",               mode)
            put("apiKey",             apiKey)
            put("modelId",            effectiveModelId)
            put("baseUrl",            effectiveBaseUrl)
            put("providerUrl",        providerUrl)
            put("providerId",         providerId)
            put("modelList",          modelList)
            put("projectPath",        projectPath)
            put("customSystemPrompt", customSystemPrompt)
            put("localToken",         localToken)
            put("disabledTools",      disabledTools)
        }
        try {
            val configFile = File(context.filesDir, CONFIG_FILE)
            val tempFile = File(context.filesDir, "$CONFIG_FILE.tmp")
            tempFile.writeText(json.toString())
            tempFile.renameTo(configFile)
            configFile.setReadable(false, false)
            configFile.setReadable(true, true)
            configFile.setWritable(false, false)
            configFile.setWritable(true, true)
        } catch (e: Exception) {
            Log.e(TAG, "Could not write bridge config", e)
        }
    }

}
