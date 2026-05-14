package com.claudecodesetup.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.managers.NodeBridgeManager

class ProjectManagerActivity : ComponentActivity() {

    private var pendingFolderCallback: ((String) -> Unit)? = null

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { treeUri ->
            val path = treeUriToPath(treeUri)
            if (path != null) pendingFolderCallback?.invoke(path)
            pendingFolderCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPreferences(this)
        setContent {
            ProjectManagerScreen(
                prefs = prefs,
                onPickFolder = { callback ->
                    pendingFolderCallback = callback
                    folderPicker.launch(null)
                },
                onOpenProject = { project ->
                    prefs.setProjectPath(project.path)
                    if (project.systemPrompt.isNotEmpty())
                        prefs.setCustomSystemPrompt(project.systemPrompt)
                    NodeBridgeManager(this).refreshConfig(prefs)
                    startActivity(Intent(this, TerminalActivity::class.java))
                },
                onBack = { finish() }
            )
        }
    }

    private fun treeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val colon = docId.indexOf(':')
            if (colon < 0) return null
            val volume = docId.substring(0, colon)
            val rel    = docId.substring(colon + 1)
            if (volume.equals("primary", ignoreCase = true)) {
                "/storage/emulated/0/$rel"
            } else {
                "/storage/$volume/$rel"
            }
        } catch (_: Exception) { null }
    }
}
