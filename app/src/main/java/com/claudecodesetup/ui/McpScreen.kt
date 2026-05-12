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

@Composable
fun McpScreen(
    prefs: AppPreferences,
    onBack: () -> Unit
) {
    var servers by remember { mutableStateOf(loadMcpServers(prefs)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var confirmDeleteIndex by remember { mutableStateOf(-1) }

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

            // Info text
            Text(
                "MCP servers extend Claude with custom tools (databases, APIs, etc). Add HTTP/SSE server URLs here.",
                fontFamily = DmSansFamily, fontSize = 12.sp,
                color = Color(0xFF6B7280),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )

            if (servers.isEmpty()) {
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
                    items(servers.indices.toList()) { i ->
                        val s = servers[i]
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
                                    s.name, fontFamily = DmSansFamily, fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold, color = Color.White
                                )
                                Text(
                                    s.url, fontFamily = SpaceMonoFamily,
                                    fontSize = 10.sp, color = Color(0xFF6B7280)
                                )
                            }
                            Text(
                                "🗑", fontSize = 18.sp,
                                modifier = Modifier
                                    .clickable { confirmDeleteIndex = i }
                                    .padding(8.dp)
                            )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        // Add server dialog
        if (showAddDialog) {
            var newName by remember { mutableStateOf("") }
            var newUrl by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = {
                    Text("Add MCP Server", fontFamily = DmSansFamily, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = newName, onValueChange = { newName = it },
                            label = { Text("Server name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newUrl, onValueChange = { newUrl = it },
                            label = { Text("URL (e.g. https://my-mcp-server.com/sse)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newName.isNotBlank() && newUrl.isNotBlank()) {
                            val updated = servers + McpServer(newName.trim(), newUrl.trim())
                            saveMcpServers(prefs, updated)
                            servers = updated
                            showAddDialog = false
                        }
                    }) { Text("Add") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                }
            )
        }

        // Confirm delete dialog
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
