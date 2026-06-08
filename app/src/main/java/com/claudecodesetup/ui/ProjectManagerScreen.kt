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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.data.AppPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ProjectEntry(val name: String, val path: String, val systemPrompt: String)

/** Line-art folder icon drawn with Compose Canvas. 22×22 dp, stroke = NexusText3. */
@Composable
private fun FolderIcon(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        // Scale factor: viewBox 0 0 22 22 → actual dp size
        val sx = w / 22f
        val sy = h / 22f
        val stroke = Stroke(width = 1.5f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val color = NexusText3

        // Body: M2 5 h6 l2 2.5 H20 v11 H2 z  (folder with tab)
        val body = Path().apply {
            moveTo(2 * sx, 5 * sy)
            lineTo(8 * sx, 5 * sy)
            lineTo(10 * sx, 7.5f * sy)
            lineTo(20 * sx, 7.5f * sy)
            lineTo(20 * sx, 18.5f * sy)
            lineTo(2 * sx, 18.5f * sy)
            close()
        }
        drawPath(body, color, style = stroke)
    }
}

/** Chevron-left icon for back navigation. */
@Composable
private fun ChevronLeftIcon(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.62f, h * 0.2f)
            lineTo(w * 0.3f, h * 0.5f)
            lineTo(w * 0.62f, h * 0.8f)
        }
        drawPath(path, NexusText2, style = stroke)
    }
}

/** Small "+" plus icon for the New button. */
@Composable
private fun PlusIcon(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(14.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.width * 0.38f
        val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = 1.8f)
        drawLine(Color.White, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 1.8f)
    }
}

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

            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Back arrow + title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    ChevronLeftIcon()
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Projects",
                        fontFamily = DmSansFamily,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = NexusText
                    )
                }

                // "+ New project" amber button
                Row(
                    modifier = Modifier
                        .background(NexusAccent, RoundedCornerShape(8.dp))
                        .clickable { showAddDialog = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    PlusIcon()
                    Text(
                        "New project",
                        fontFamily = DmSansFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
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
                        .background(NexusGreenDim, RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0x3310B981), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(NexusGreen, CircleShape)
                    )
                    Column {
                        Text(
                            "ACTIVE", fontFamily = SpaceMonoFamily, fontSize = 7.sp,
                            letterSpacing = 2.sp, color = NexusGreen
                        )
                        Text(
                            activeProject.substringAfterLast('/').ifEmpty { activeProject },
                            fontFamily = JetBrainsMonoFamily, fontSize = 11.sp,
                            color = NexusText2, maxLines = 1
                        )
                    }
                }
            }

            // ── Empty state ──────────────────────────────────────────────────
            if (projects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        // Line-art folder in a square box
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(NexusSurface2, RoundedCornerShape(12.dp))
                                .border(1.dp, NexusBorder, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            FolderIcon()
                        }

                        Text(
                            "No projects yet",
                            fontFamily = DmSansFamily,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NexusText
                        )
                        Text(
                            "Projects keep each task's files,\nhistory and system prompt separate.",
                            fontFamily = DmSansFamily,
                            fontSize = 13.sp,
                            color = NexusText2,
                            textAlign = TextAlign.Center
                        )

                        // CTA button
                        Box(
                            modifier = Modifier
                                .widthIn(max = 220.dp)
                                .fillMaxWidth()
                                .background(NexusAccent, RoundedCornerShape(10.dp))
                                .clickable { showAddDialog = true }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Create your first project",
                                fontFamily = DmSansFamily,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            } else {
                // ── Project list ────────────────────────────────────────────
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
                                .background(NexusSurface, RoundedCornerShape(10.dp))
                                .border(1.dp, NexusBorder, RoundedCornerShape(10.dp))
                                .clickable { onOpenProject(p) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Status dot
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (isActive) NexusGreen else NexusText3,
                                        CircleShape
                                    )
                            )
                            Spacer(Modifier.width(10.dp))

                            // Name + path
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    p.name,
                                    fontFamily = DmSansFamily,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = NexusText
                                )
                                Text(
                                    p.path.ifEmpty { "No path set" },
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.sp,
                                    color = NexusText3,
                                    maxLines = 1
                                )
                                if (p.systemPrompt.isNotEmpty()) {
                                    Text(
                                        p.systemPrompt.take(60) + if (p.systemPrompt.length > 60) "…" else "",
                                        fontFamily = DmSansFamily,
                                        fontSize = 11.sp,
                                        color = NexusText2
                                    )
                                }
                            }

                            // Chevron right + delete
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "›",
                                    fontSize = 20.sp,
                                    color = NexusText3,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                                Text(
                                    "✕",
                                    fontSize = 13.sp,
                                    color = NexusText3,
                                    modifier = Modifier
                                        .clickable { confirmDeleteIndex = i }
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        // ── Add project dialog ──────────────────────────────────────────────
        if (showAddDialog) {
            var newName   by remember { mutableStateOf("") }
            var newPath   by remember { mutableStateOf("") }
            var newPrompt by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                containerColor = NexusOverlay,
                titleContentColor = NexusText,
                textContentColor = NexusText2,
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
                                "Folder", fontSize = 12.sp, color = NexusText2,
                                fontFamily = DmSansFamily
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    newPath.ifEmpty { "No folder selected" },
                                    fontFamily = JetBrainsMonoFamily, fontSize = 10.sp,
                                    color = if (newPath.isEmpty()) NexusText3 else NexusText,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2
                                )
                                Box(
                                    modifier = Modifier
                                        .background(NexusSurface2, RoundedCornerShape(8.dp))
                                        .border(1.dp, NexusBorder, RoundedCornerShape(8.dp))
                                        .clickable {
                                            onPickFolder { path -> newPath = path }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        "Browse", fontFamily = DmSansFamily, fontSize = 12.sp,
                                        color = NexusAccent, fontWeight = FontWeight.Medium
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
                    }) { Text("Create", color = NexusAccent) }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Cancel", color = NexusText2)
                    }
                }
            )
        }

        // ── Confirm delete dialog ───────────────────────────────────────────
        if (confirmDeleteIndex >= 0) {
            AlertDialog(
                onDismissRequest = { confirmDeleteIndex = -1 },
                containerColor = NexusOverlay,
                titleContentColor = NexusText,
                textContentColor = NexusText2,
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
                    }) { Text("Delete", color = NexusRed) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteIndex = -1 }) {
                        Text("Cancel", color = NexusText2)
                    }
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
