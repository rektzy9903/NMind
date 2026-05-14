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

data class ProjectEntry(val name: String, val path: String, val systemPrompt: String)

@Composable
fun ProjectManagerScreen(
    prefs: AppPreferences,
    onPickFolder: (onResult: (String) -> Unit) -> Unit,
    onOpenProject: (ProjectEntry) -> Unit,
    onBack: () -> Unit
) {
    var projects by remember { mutableStateOf(loadProjects(prefs)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var confirmDeleteIndex by remember { mutableStateOf(-1) }

    val activeProject = prefs.getProjectPath()

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
                            "PROJECTS", fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                            letterSpacing = 3.sp, color = Color(0xB360A5FA)
                        )
                        Text(
                            "My Projects", fontFamily = DmSansFamily, fontSize = 17.sp,
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
                        "＋ New", fontFamily = DmSansFamily, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, color = Color.White
                    )
                }
            }

            // Active project banner
            if (activeProject.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp)
                        .background(Color(0x1A10B981), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0x3310B981), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("📂", fontSize = 14.sp)
                    Column {
                        Text(
                            "ACTIVE", fontFamily = SpaceMonoFamily, fontSize = 7.sp,
                            letterSpacing = 2.sp, color = Color(0xFF10B981)
                        )
                        Text(
                            activeProject.substringAfterLast('/').ifEmpty { activeProject },
                            fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                            color = Color(0xFF6EE7B7), maxLines = 1
                        )
                    }
                }
            }

            if (projects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("📁", fontSize = 40.sp)
                        Text(
                            "No projects yet", fontFamily = DmSansFamily,
                            fontSize = 16.sp, color = Color(0xFF6B7280)
                        )
                        Text(
                            "Tap ＋ New to add one", fontFamily = DmSansFamily,
                            fontSize = 13.sp, color = Color(0xFF4B5563)
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(projects.indices.toList()) { i ->
                        val p = projects[i]
                        val isActive = p.path == activeProject
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isActive) Color(0x1A10B981) else Color(0x0FFFFFFF),
                                    RoundedCornerShape(14.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isActive) Color(0x3310B981) else Color(0x17FFFFFF),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { onOpenProject(p) }
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
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        p.name, fontFamily = DmSansFamily, fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold, color = Color.White
                                    )
                                    if (isActive) {
                                        Text(
                                            "active", fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                                            color = Color(0xFF10B981),
                                            modifier = Modifier
                                                .background(Color(0x2010B981), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    p.path.ifEmpty { "No path set" }, fontFamily = SpaceMonoFamily,
                                    fontSize = 10.sp, color = Color(0xFF6B7280), maxLines = 1
                                )
                                if (p.systemPrompt.isNotEmpty()) {
                                    Text(
                                        p.systemPrompt.take(60) + if (p.systemPrompt.length > 60) "…" else "",
                                        fontFamily = DmSansFamily, fontSize = 11.sp,
                                        color = Color(0xFF4B5563)
                                    )
                                }
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

        // Add project dialog
        if (showAddDialog) {
            var newName   by remember { mutableStateOf("") }
            var newPath   by remember { mutableStateOf("") }
            var newPrompt by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = {
                    Text("New Project", fontFamily = DmSansFamily, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = newName, onValueChange = { newName = it },
                            label = { Text("Project name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Path row: auto-filled by picker, or editable manually
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Folder", fontSize = 12.sp, color = Color(0xFF9CA3AF),
                                fontFamily = DmSansFamily
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    newPath.ifEmpty { "No folder selected" },
                                    fontFamily = SpaceMonoFamily, fontSize = 10.sp,
                                    color = if (newPath.isEmpty()) Color(0xFF6B7280) else Color(0xFFD1D5DB),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2
                                )
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF374151), RoundedCornerShape(8.dp))
                                        .clickable {
                                            onPickFolder { path -> newPath = path }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        "Browse", fontFamily = DmSansFamily, fontSize = 12.sp,
                                        color = Color(0xFF60A5FA), fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = newPrompt, onValueChange = { newPrompt = it },
                            label = { Text("System prompt (optional)") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newName.isNotBlank()) {
                            val updated = projects + ProjectEntry(
                                newName.trim(), newPath.trim(), newPrompt.trim()
                            )
                            saveProjects(prefs, updated)
                            projects = updated
                            showAddDialog = false
                        }
                    }) { Text("Create") }
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
                title = { Text("Delete project?", fontFamily = DmSansFamily) },
                text = {
                    Text(
                        "\"${projects[confirmDeleteIndex].name}\" will be removed from the list. Files on disk are not deleted.",
                        fontFamily = DmSansFamily
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val updated = projects.toMutableList().also { it.removeAt(confirmDeleteIndex) }
                        saveProjects(prefs, updated)
                        projects = updated
                        confirmDeleteIndex = -1
                    }) { Text("Delete", color = Color(0xFFEF4444)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteIndex = -1 }) { Text("Cancel") }
                }
            )
        }
    }
}

private fun loadProjects(prefs: AppPreferences): List<ProjectEntry> {
    return try {
        val arr = JSONArray(prefs.getProjectsJson())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ProjectEntry(o.optString("name"), o.optString("path"), o.optString("systemPrompt"))
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveProjects(prefs: AppPreferences, list: List<ProjectEntry>) {
    val arr = JSONArray()
    list.forEach { p ->
        arr.put(JSONObject().apply {
            put("name", p.name); put("path", p.path); put("systemPrompt", p.systemPrompt)
        })
    }
    prefs.saveProjectsJson(arr.toString())
}
