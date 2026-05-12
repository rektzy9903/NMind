package com.claudecodesetup.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.claudecodesetup.data.AppPreferences

class McpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPreferences(this)
        setContent {
            McpScreen(prefs = prefs, onBack = { finish() })
        }
    }
}
