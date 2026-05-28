package com.claudecodesetup.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.quickask.QuickAskViewModel

class QuickAskActivity : ComponentActivity() {

    private val vm: QuickAskViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPreferences(this)
        setContent {
            QuickAskScreen(
                prefs = prefs,
                vm = vm,
                onBack = { finish() },
            )
        }
    }
}
