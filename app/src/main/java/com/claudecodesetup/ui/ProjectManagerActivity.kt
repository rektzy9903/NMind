package com.claudecodesetup.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AppPreferences

class ProjectManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPreferences(this)
        setContent {
            ProjectManagerScreen(
                prefs = prefs,
                onOpenProject = { project ->
                    prefs.setProjectPath(project.path)
                    if (project.systemPrompt.isNotEmpty())
                        prefs.setCustomSystemPrompt(project.systemPrompt)
                    startActivity(Intent(this, TerminalActivity::class.java))
                },
                onBack = { finish() }
            )
        }
    }
}
