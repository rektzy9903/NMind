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

data class McpServer(val name: String, val url: String)
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
            var newCommand by remember { mutableStateOf("node") }
            var newArgs by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = {
                    Text("Add MCP Server", fontFamily = DmSansFamily, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                value = newUrl, onValueChange = { newUrl = it },
                                label = { Text("URL (e.g. https://mcp.exa.ai/mcp?exaApiKey=…)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
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
                                val updated = servers + McpServer(newName.trim(), newUrl.trim())
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
            McpServer(o.optString("name"), o.optString("url"))
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveMcpServers(prefs: AppPreferences, list: List<McpServer>) {
    val arr = JSONArray()
    list.forEach { s ->
        arr.put(JSONObject().apply {
            put("name", s.name); put("url", s.url)
        })
    }
    prefs.saveMcpServersJson(arr.toString())
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
