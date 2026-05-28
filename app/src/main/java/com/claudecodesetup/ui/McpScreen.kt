package com.claudecodesetup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import com.claudecodesetup.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// MCP-2: headers is a JSON object string (e.g. {"Authorization":"Bearer …"}) so
// it round-trips losslessly with the rest of the server config. Empty string
// (or "{}") means no extra headers — backward compatible with old saved configs.
data class McpServer(val name: String, val url: String, val headers: String = "{}")
data class McpStdioServer(val name: String, val command: String, val args: String)

enum class PingStatus { CHECKING, OK, ERROR }

/** Pings an HTTP MCP server by sending a minimal JSON-RPC initialize request.
 *  Any HTTP response (even 4xx/5xx) means the server is reachable → OK.
 *  Connection failure / timeout → ERROR. */
suspend fun pingMcpServer(url: String): PingStatus = withContext(Dispatchers.IO) {
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 5_000
        conn.readTimeout   = 5_000
        conn.doOutput = true
        conn.outputStream.use { out ->
            out.write("""{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}},"id":1}""".toByteArray())
        }
        val code = conn.responseCode
        conn.disconnect()
        if (code > 0) PingStatus.OK else PingStatus.ERROR
    } catch (_: Exception) {
        PingStatus.ERROR
    }
}

// MCP-8: one-tap presets for the add-server dialog. Each template prefills
// the form. Stdio templates that use `npx` require the user to have run
// `!install node` (or similar) in the terminal at least once. HTTP templates
// usually require an API key embedded in the URL or sent via the headers
// field — the description should make that clear.
data class McpTemplate(
    val label: String,
    val description: String,
    val isStdio: Boolean,
    val nameSuggestion: String,
    val url: String = "",
    val command: String = "",
    val args: String = "",
    val headers: String = ""
)

val MCP_TEMPLATES = listOf(
    McpTemplate(
        label = "Filesystem (stdio)",
        description = "Read/write files under a directory. Edit the path in args.",
        isStdio = true,
        nameSuggestion = "filesystem",
        command = "npx",
        args = "-y @modelcontextprotocol/server-filesystem /sdcard/Download"
    ),
    McpTemplate(
        label = "Fetch (stdio)",
        description = "Fetch arbitrary URLs as a tool. No API key required.",
        isStdio = true,
        nameSuggestion = "fetch",
        command = "npx",
        args = "-y @modelcontextprotocol/server-fetch"
    ),
    McpTemplate(
        label = "GitHub (stdio)",
        description = "GitHub API access. Needs GITHUB_PERSONAL_ACCESS_TOKEN in the process env.",
        isStdio = true,
        nameSuggestion = "github",
        command = "npx",
        args = "-y @modelcontextprotocol/server-github"
    ),
    McpTemplate(
        label = "Brave Search (stdio)",
        description = "Web search via Brave. Needs BRAVE_API_KEY in the process env.",
        isStdio = true,
        nameSuggestion = "brave",
        command = "npx",
        args = "-y @modelcontextprotocol/server-brave-search"
    ),
    McpTemplate(
        label = "Memory (stdio)",
        description = "Persistent key-value memory store across conversations.",
        isStdio = true,
        nameSuggestion = "memory",
        command = "npx",
        args = "-y @modelcontextprotocol/server-memory"
    ),
    McpTemplate(
        label = "Exa (HTTP)",
        description = "AI-native search. Replace YOUR_KEY with your Exa API key.",
        isStdio = false,
        nameSuggestion = "exa",
        url = "https://mcp.exa.ai/mcp?exaApiKey=YOUR_KEY"
    ),
)

// MCP-3 result of a "Test connection" attempt against an HTTP MCP server.
sealed class McpTestResult {
    object Idle : McpTestResult()
    object Testing : McpTestResult()
    data class Success(val tools: List<String>) : McpTestResult()
    data class Failure(val message: String) : McpTestResult()
}

/** MCP-3: full initialize + tools/list against an HTTP MCP endpoint, mirroring
 *  bridge.js `startMcpHttpServer`. Returns the discovered tool names or an
 *  error message. Honors `mcp-session-id` round-trip and SSE event-stream
 *  responses (`text/event-stream`). Header map is applied to every request. */
suspend fun testMcpHttpServer(
    url: String,
    headers: Map<String, String>
): McpTestResult = withContext(Dispatchers.IO) {
    suspend fun post(body: String, sessionId: String?): Pair<String, String?> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        if (sessionId != null) conn.setRequestProperty("mcp-session-id", sessionId)
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        conn.connectTimeout = 8_000
        conn.readTimeout   = 12_000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val sid = conn.getHeaderField("mcp-session-id")
        val ct  = (conn.contentType ?: "").lowercase()
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299 && code != 202) {
            throw java.io.IOException("HTTP $code: ${raw.take(160)}")
        }
        // SSE: extract first data line that parses as JSON-RPC.
        if (ct.contains("text/event-stream")) {
            val rpc = raw.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .firstOrNull { it.isNotEmpty() && it != "[DONE]" }
                ?: "{}"
            return Pair(rpc, sid)
        }
        return Pair(raw, sid)
    }
    try {
        val initBody = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"clientInfo":{"name":"ClaudeCodeSetup","version":"1.0"}}}"""
        val (initRaw, sid) = post(initBody, null)
        val initJson = JSONObject(initRaw)
        if (initJson.has("error")) {
            val err = initJson.optJSONObject("error")
            return@withContext McpTestResult.Failure(err?.optString("message") ?: "initialize failed")
        }
        // fire-and-forget initialized notification — best effort
        try { post("""{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}""", sid) } catch (_: Exception) {}
        val (toolsRaw, _) = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""", sid)
        val toolsJson = JSONObject(toolsRaw)
        if (toolsJson.has("error")) {
            val err = toolsJson.optJSONObject("error")
            return@withContext McpTestResult.Failure(err?.optString("message") ?: "tools/list failed")
        }
        val arr = toolsJson.optJSONObject("result")?.optJSONArray("tools")
            ?: toolsJson.optJSONArray("tools")
        val names = mutableListOf<String>()
        if (arr != null) for (i in 0 until arr.length()) {
            names.add(arr.getJSONObject(i).optString("name"))
        }
        McpTestResult.Success(names)
    } catch (e: Exception) {
        McpTestResult.Failure(e.message ?: e.javaClass.simpleName)
    }
}

@Composable
fun McpScreen(
    prefs: AppPreferences,
    onBack: () -> Unit
) {
    var servers by remember { mutableStateOf(loadMcpServers(prefs)) }
    var stdioServers by remember { mutableStateOf(loadMcpStdioServers(prefs)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var confirmDeleteIndex by remember { mutableStateOf(-1) }
    var confirmDeleteStdioIndex by remember { mutableStateOf(-1) }

    // Ping status for each HTTP server, keyed by server name
    var pingStatus by remember { mutableStateOf<Map<String, PingStatus>>(emptyMap()) }

    // Track in-flight ping jobs so they can be cancelled on disposal
    val pendingJobs = remember { mutableListOf<Job>() }
    DisposableEffect(servers) {
        onDispose { pendingJobs.forEach { it.cancel() }; pendingJobs.clear() }
    }

    // Ping all HTTP servers in parallel whenever the list changes
    LaunchedEffect(servers) {
        pingStatus = servers.associate { it.name to PingStatus.CHECKING }
        servers.forEach { srv ->
            val job = launch {
                val result = pingMcpServer(srv.url)
                pingStatus = pingStatus + (srv.name to result)
            }
            pendingJobs.add(job)
        }
    }

    AppBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "←", fontSize = 20.sp, color = NexusBlue,
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "MCP", fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                            letterSpacing = 3.sp, color = NexusBlue.copy(alpha = 0.70f)
                        )
                        Text(
                            "MCP Servers", fontFamily = DmSansFamily, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold, color = Color.White
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .background(NexusAccent, RoundedCornerShape(10.dp))
                        .clickable { showAddDialog = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        "＋ Add", fontFamily = DmSansFamily, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, color = Color.White
                    )
                }
            }

            Text(
                "MCP servers extend Claude with custom tools. HTTP for remote servers (e.g. Exa, Smithery), stdio for local scripts.",
                fontFamily = DmSansFamily, fontSize = 12.sp,
                color = NexusText3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )

            val allEmpty = servers.isEmpty() && stdioServers.isEmpty()
            if (allEmpty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mcp_plug),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = NexusText3,
                        )
                        Text(
                            "No MCP servers yet", fontFamily = DmSansFamily,
                            fontSize = 16.sp, color = NexusText3
                        )
                        Text(
                            "Tap ＋ Add to configure one", fontFamily = DmSansFamily,
                            fontSize = 13.sp, color = NexusText3
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (servers.isNotEmpty()) {
                        item {
                            Text(
                                "HTTP (REMOTE)", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                                letterSpacing = 2.sp, color = NexusBlue,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                        }
                        items(servers.indices.toList()) { i ->
                            val s = servers[i]
                            val status = pingStatus[s.name] ?: PingStatus.CHECKING
                            McpServerCard(
                                name = s.name, subtitle = s.url,
                                pingStatus = status,
                                onPing = {
                                    pingStatus = pingStatus + (s.name to PingStatus.CHECKING)
                                },
                                onPingResult = { result ->
                                    pingStatus = pingStatus + (s.name to result)
                                },
                                onDelete = { confirmDeleteIndex = i }
                            )
                        }
                    }
                    if (stdioServers.isNotEmpty()) {
                        item {
                            Text(
                                "STDIO (local process)", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                                letterSpacing = 2.sp, color = NexusGreen,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                        }
                        items(stdioServers.indices.toList()) { i ->
                            val s = stdioServers[i]
                            McpServerCard(
                                name = s.name,
                                subtitle = s.command + if (s.args.isNotBlank()) " ${s.args}" else "",
                                onDelete = { confirmDeleteStdioIndex = i }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        // Add server dialog
        if (showAddDialog) {
            var isStdio by remember { mutableStateOf(false) }
            var newName by remember { mutableStateOf("") }
            var newUrl by remember { mutableStateOf("") }
            var newHeaders by remember { mutableStateOf("") }  // MCP-2: HTTP auth/headers
            var newCommand by remember { mutableStateOf("node") }
            var newArgs by remember { mutableStateOf("") }
            // MCP-3: in-dialog connection test state (HTTP only)
            var testResult by remember { mutableStateOf<McpTestResult>(McpTestResult.Idle) }
            val testScope = rememberCoroutineScope()
            // MCP-8: collapsed templates section starts closed; user expands when needed.
            var showTemplates by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = {
                    Text("Add MCP Server", fontFamily = DmSansFamily, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // MCP-8: templates section — collapsed header, expands to a list.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTemplates = !showTemplates }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Templates", fontFamily = DmSansFamily, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NexusAccent
                            )
                            Text(
                                if (showTemplates) "▾ hide" else "▸ show ${MCP_TEMPLATES.size}",
                                fontFamily = SpaceMonoFamily, fontSize = 10.sp,
                                color = NexusText3
                            )
                        }
                        if (showTemplates) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                MCP_TEMPLATES.forEach { tpl ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(NexusSurface2, RoundedCornerShape(8.dp))
                                            .border(1.dp, NexusBorder, RoundedCornerShape(8.dp))
                                            .clickable {
                                                isStdio = tpl.isStdio
                                                if (newName.isBlank()) newName = tpl.nameSuggestion
                                                if (tpl.isStdio) {
                                                    newCommand = tpl.command
                                                    newArgs = tpl.args
                                                } else {
                                                    newUrl = tpl.url
                                                    newHeaders = tpl.headers
                                                }
                                                testResult = McpTestResult.Idle
                                                showTemplates = false
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                tpl.label, fontFamily = DmSansFamily,
                                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                                color = Color.White
                                            )
                                            Text(
                                                tpl.description, fontFamily = DmSansFamily,
                                                fontSize = 10.sp, color = NexusText3
                                            )
                                        }
                                        Text(
                                            "→", fontFamily = SpaceMonoFamily,
                                            fontSize = 14.sp, color = NexusAccent,
                                            modifier = Modifier.padding(start = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                        // Type toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (!isStdio) NexusAccent else NexusSurface2,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { isStdio = false }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("HTTP (Remote)", fontFamily = DmSansFamily, fontSize = 12.sp,
                                    color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (isStdio) NexusGreen else NexusSurface2,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { isStdio = true }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Stdio", fontFamily = DmSansFamily, fontSize = 12.sp,
                                    color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        OutlinedTextField(
                            value = newName, onValueChange = { newName = it },
                            label = { Text("Server name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!isStdio) {
                            OutlinedTextField(
                                value = newUrl,
                                onValueChange = { newUrl = it; testResult = McpTestResult.Idle },
                                label = { Text("URL (e.g. https://mcp.exa.ai/mcp?exaApiKey=…)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            // MCP-2: optional headers — one "Key: Value" per line.
                            OutlinedTextField(
                                value = newHeaders,
                                onValueChange = { newHeaders = it; testResult = McpTestResult.Idle },
                                label = { Text("Headers (optional, one per line)") },
                                placeholder = { Text("Authorization: Bearer xxx\nX-Custom: value") },
                                singleLine = false, minLines = 2, maxLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "For bearer tokens, API keys, or custom headers. Parsed as 'Key: Value' lines.",
                                fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusText3
                            )
                            // MCP-3: Test Connection button + result row
                            val canTest = newUrl.isNotBlank() && testResult !is McpTestResult.Testing
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (canTest) NexusSurface2 else NexusSurface2.copy(alpha = 0.4f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .border(1.dp, NexusBorder, RoundedCornerShape(8.dp))
                                        .clickable(enabled = canTest) {
                                            testResult = McpTestResult.Testing
                                            val url = newUrl.trim()
                                            val hdrs = mutableMapOf<String, String>()
                                            val parsed = parseHeadersInput(newHeaders)
                                            parsed.keys().forEach { k -> hdrs[k] = parsed.optString(k, "") }
                                            testScope.launch {
                                                testResult = testMcpHttpServer(url, hdrs)
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        "Test Connection", fontFamily = DmSansFamily,
                                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (canTest) Color.White else NexusText3
                                    )
                                }
                                when (val r = testResult) {
                                    McpTestResult.Idle -> {}
                                    McpTestResult.Testing -> Text(
                                        "Testing…", fontFamily = SpaceMonoFamily,
                                        fontSize = 11.sp, color = NexusText3
                                    )
                                    is McpTestResult.Success -> Text(
                                        "✓ ${r.tools.size} tool" + if (r.tools.size == 1) "" else "s",
                                        fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                                        color = NexusGreen
                                    )
                                    is McpTestResult.Failure -> Text(
                                        "✗ failed", fontFamily = SpaceMonoFamily,
                                        fontSize = 11.sp, color = Color(0xFFEF4444)
                                    )
                                }
                            }
                            // Detail row under the button — tool list or error
                            when (val r = testResult) {
                                is McpTestResult.Success -> {
                                    if (r.tools.isNotEmpty()) Text(
                                        r.tools.joinToString(", "),
                                        fontFamily = SpaceMonoFamily, fontSize = 10.sp,
                                        color = NexusText2,
                                        maxLines = 4
                                    ) else Text(
                                        "Connected but server reports no tools.",
                                        fontFamily = DmSansFamily, fontSize = 11.sp,
                                        color = NexusText3
                                    )
                                }
                                is McpTestResult.Failure -> Text(
                                    r.message.take(240),
                                    fontFamily = SpaceMonoFamily, fontSize = 10.sp,
                                    color = Color(0xFFEF4444),
                                    maxLines = 4
                                )
                                else -> {}
                            }
                        } else {
                            OutlinedTextField(
                                value = newCommand, onValueChange = { newCommand = it },
                                label = { Text("Command (e.g. node)") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newArgs, onValueChange = { newArgs = it },
                                label = { Text("Args (e.g. /path/to/server.js)") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "The command will run as a child process speaking MCP JSON-RPC over stdin/stdout.",
                                fontFamily = DmSansFamily, fontSize = 11.sp, color = NexusText3
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newName.isNotBlank()) {
                            if (!isStdio && newUrl.isNotBlank()) {
                                // MCP-2: parse headers input into compact JSON for storage.
                                val hdrsJson = parseHeadersInput(newHeaders).toString()
                                val updated = servers + McpServer(newName.trim(), newUrl.trim(), hdrsJson)
                                saveMcpServers(prefs, updated)
                                servers = updated
                                showAddDialog = false
                            } else if (isStdio && newCommand.isNotBlank()) {
                                val updated = stdioServers + McpStdioServer(newName.trim(), newCommand.trim(), newArgs.trim())
                                saveMcpStdioServers(prefs, updated)
                                stdioServers = updated
                                showAddDialog = false
                            }
                        }
                    }) { Text("Add") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                }
            )
        }

        // Confirm delete HTTP server
        if (confirmDeleteIndex >= 0) {
            AlertDialog(
                onDismissRequest = { confirmDeleteIndex = -1 },
                title = { Text("Remove server?", fontFamily = DmSansFamily) },
                text = {
                    Text(
                        "\"${servers[confirmDeleteIndex].name}\" will be removed.",
                        fontFamily = DmSansFamily
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val updated = servers.toMutableList().also { it.removeAt(confirmDeleteIndex) }
                        saveMcpServers(prefs, updated)
                        servers = updated
                        confirmDeleteIndex = -1
                    }) { Text("Remove", color = Color(0xFFEF4444)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteIndex = -1 }) { Text("Cancel") }
                }
            )
        }

        // Confirm delete stdio server
        if (confirmDeleteStdioIndex >= 0) {
            AlertDialog(
                onDismissRequest = { confirmDeleteStdioIndex = -1 },
                title = { Text("Remove server?", fontFamily = DmSansFamily) },
                text = {
                    Text(
                        "\"${stdioServers[confirmDeleteStdioIndex].name}\" will be removed.",
                        fontFamily = DmSansFamily
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val updated = stdioServers.toMutableList().also { it.removeAt(confirmDeleteStdioIndex) }
                        saveMcpStdioServers(prefs, updated)
                        stdioServers = updated
                        confirmDeleteStdioIndex = -1
                    }) { Text("Remove", color = Color(0xFFEF4444)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteStdioIndex = -1 }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun McpServerCard(
    name: String,
    subtitle: String,
    pingStatus: PingStatus? = null,       // null = stdio (no live check)
    onPing: (() -> Unit)? = null,
    onPingResult: ((PingStatus) -> Unit)? = null,
    onDelete: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NexusSurface, RoundedCornerShape(14.dp))
            .border(1.dp, NexusBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    name, fontFamily = DmSansFamily, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, color = Color.White
                )
                // Status dot — only for HTTP servers (pingStatus != null)
                if (pingStatus != null) {
                    val dotColor = when (pingStatus) {
                        PingStatus.OK       -> NexusGreen          // green
                        PingStatus.ERROR    -> Color(0xFFEF4444)  // red
                        PingStatus.CHECKING -> NexusText3          // gray (pulsing would need animation)
                    }
                    val dotLabel = when (pingStatus) {
                        PingStatus.OK       -> "live"
                        PingStatus.ERROR    -> "unreachable"
                        PingStatus.CHECKING -> "checking…"
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dotColor, CircleShape)
                            .clickable {
                                if (pingStatus != PingStatus.CHECKING && onPing != null && onPingResult != null) {
                                    onPing()
                                    coroutineScope.launch {
                                        onPingResult(pingMcpServer(subtitle))
                                    }
                                }
                            }
                    )
                    Text(
                        dotLabel, fontFamily = SpaceMonoFamily,
                        fontSize = 9.sp, color = dotColor
                    )
                }
            }
            Text(
                subtitle, fontFamily = SpaceMonoFamily,
                fontSize = 10.sp, color = NexusText3
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_delete),
            contentDescription = "Delete",
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onDelete)
                .padding(2.dp),
            tint = NexusText3,
        )
    }
}

private fun loadMcpServers(prefs: AppPreferences): List<McpServer> {
    return try {
        val arr = JSONArray(prefs.getMcpServersJson())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            // MCP-2: headers stored as nested object in JSON; serialize back to a
            // compact string for the data class. Old saved configs without the
            // field default to "{}" (no extra headers).
            val headersStr = o.optJSONObject("headers")?.toString() ?: "{}"
            McpServer(o.optString("name"), o.optString("url"), headersStr)
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveMcpServers(prefs: AppPreferences, list: List<McpServer>) {
    val arr = JSONArray()
    list.forEach { s ->
        arr.put(JSONObject().apply {
            put("name", s.name); put("url", s.url)
            // MCP-2: persist headers as a nested JSON object (or omit if empty).
            try {
                val hdrs = JSONObject(s.headers.ifBlank { "{}" })
                if (hdrs.length() > 0) put("headers", hdrs)
            } catch (_: Exception) { /* malformed → skip */ }
        })
    }
    prefs.saveMcpServersJson(arr.toString())
}

// MCP-2: parse "Key: Value" lines from the headers input field into a JSON object.
// Blank lines and lines without ':' are skipped. Trims surrounding whitespace.
internal fun parseHeadersInput(text: String): JSONObject {
    val out = JSONObject()
    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEach
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx <= 0) return@forEach
        val key = trimmed.substring(0, colonIdx).trim()
        val value = trimmed.substring(colonIdx + 1).trim()
        if (key.isNotEmpty()) try { out.put(key, value) } catch (_: Exception) {}
    }
    return out
}

// MCP-2: convert stored JSON-object headers back to display lines for the UI.
internal fun headersJsonToLines(jsonStr: String): String {
    return try {
        val obj = JSONObject(jsonStr)
        buildString {
            obj.keys().forEach { k ->
                if (length > 0) append('\n')
                append(k).append(": ").append(obj.optString(k, ""))
            }
        }
    } catch (_: Exception) { "" }
}

private fun loadMcpStdioServers(prefs: AppPreferences): List<McpStdioServer> {
    return try {
        val arr = JSONArray(prefs.getMcpStdioServersJson())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            McpStdioServer(
                o.optString("name"),
                o.optString("command"),
                o.optString("args")
            )
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveMcpStdioServers(prefs: AppPreferences, list: List<McpStdioServer>) {
    val arr = JSONArray()
    list.forEach { s ->
        arr.put(JSONObject().apply {
            put("name", s.name)
            put("command", s.command)
            put("args", s.args)
        })
    }
    prefs.saveMcpStdioServersJson(arr.toString())
}
