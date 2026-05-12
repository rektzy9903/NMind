package com.claudecodesetup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.AppPreferences
import org.json.JSONArray
import org.json.JSONObject

data class McpServer(val name: String, val url: String)
data class McpStdioServer(val name: String, val command: String, val args: String)

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
                        "←", fontSize = 20.sp, color = Color(0xFF60A5FA),
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "MCP", fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                            letterSpacing = 3.sp, color = Color(0xB360A5FA)
                        )
                        Text(
                            "MCP Servers", fontFamily = DmSansFamily, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold, color = Color.White
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .background(Color(0xFF3B82F6), RoundedCornerShape(10.dp))
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
                "MCP servers extend Claude with custom tools. HTTP/SSE for remote servers, stdio for local Node.js scripts.",
                fontFamily = DmSansFamily, fontSize = 12.sp,
                color = Color(0xFF6B7280),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )

            val allEmpty = servers.isEmpty() && stdioServers.isEmpty()
            if (allEmpty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🔌", fontSize = 40.sp)
                        Text(
                            "No MCP servers yet", fontFamily = DmSansFamily,
                            fontSize = 16.sp, color = Color(0xFF6B7280)
                        )
                        Text(
                            "Tap ＋ Add to configure one", fontFamily = DmSansFamily,
                            fontSize = 13.sp, color = Color(0xFF4B5563)
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
                                "HTTP / SSE", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                                letterSpacing = 2.sp, color = Color(0xFF60A5FA),
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                        }
                        items(servers.indices.toList()) { i ->
                            val s = servers[i]
                            McpServerCard(
                                name = s.name, subtitle = s.url,
                                onDelete = { confirmDeleteIndex = i }
                            )
                        }
                    }
                    if (stdioServers.isNotEmpty()) {
                        item {
                            Text(
                                "STDIO (local process)", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                                letterSpacing = 2.sp, color = Color(0xFF34D399),
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
                                        if (!isStdio) Color(0xFF3B82F6) else Color(0x22FFFFFF),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { isStdio = false }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("HTTP / SSE", fontFamily = DmSansFamily, fontSize = 12.sp,
                                    color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (isStdio) Color(0xFF059669) else Color(0x22FFFFFF),
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
                                label = { Text("URL (e.g. https://my-server.com/sse)") },
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
                                fontFamily = DmSansFamily, fontSize = 11.sp, color = Color(0xFF6B7280)
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
private fun McpServerCard(name: String, subtitle: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x0FFFFFFF), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0x17FFFFFF), RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                name, fontFamily = DmSansFamily, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, color = Color.White
            )
            Text(
                subtitle, fontFamily = SpaceMonoFamily,
                fontSize = 10.sp, color = Color(0xFF6B7280)
            )
        }
        Text(
            "🗑", fontSize = 18.sp,
            modifier = Modifier
                .clickable(onClick = onDelete)
                .padding(8.dp)
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
