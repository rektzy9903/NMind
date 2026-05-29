package com.claudecodesetup.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.discussion.DiscussionConfig
import com.claudecodesetup.discussion.DiscussionOrchestrator

/**
 * Two-screen state machine: setup → live. Tapping "New" while in live mode
 * goes back to setup with the same config pre-filled.
 */
@Composable
fun DiscussionScreen(
    prefs: AppPreferences,
    orchestrator: DiscussionOrchestrator,
    onExit: () -> Unit,
) {
    val state by orchestrator.state.collectAsStateWithLifecycle()
    // "screen" = which sub-screen is shown
    var screen by remember { mutableStateOf("setup") }
    // remember last config so "New discussion" pre-fills it again
    var lastConfig by remember { mutableStateOf(DiscussionPersistence.load(prefs)) }

    when (screen) {
        "setup" -> DiscussionSetupScreen(
            prefs = prefs,
            initialConfig = lastConfig,
            onBack = onExit,
            onStart = { cfg ->
                lastConfig = cfg
                DiscussionPersistence.save(prefs, cfg)
                orchestrator.start(cfg)
                screen = "live"
            },
        )
        "live" -> DiscussionLiveScreen(
            state = state,
            onStop = { orchestrator.stop() },
            onContinue = { orchestrator.continueAfterCap() },
            onSubmitHuman = { text -> orchestrator.submitHumanTurn(text) },
            onNewDiscussion = {
                orchestrator.stop()
                screen = "setup"
            },
            onBack = {
                orchestrator.stop()
                onExit()
            },
        )
    }
}
