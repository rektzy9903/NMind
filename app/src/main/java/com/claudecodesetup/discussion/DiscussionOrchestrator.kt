package com.claudecodesetup.discussion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Drives a multi-speaker discussion. Owns a single mutable StateFlow that the
 * UI subscribes to; emits a new immutable [DiscussionState] each time a turn's
 * status or text changes.
 *
 * Lifecycle:
 *   - Construct with a CoroutineScope (the activity's lifecycle scope).
 *   - Call [start] to kick off the turn loop.
 *   - Call [stop] to cancel an in-flight stream and freeze the state.
 *   - Call [continueAfterCap] after maxTurns is reached if the user wants
 *     another round (rare; mostly used after early convergence).
 */
class DiscussionOrchestrator(
    private val scope: CoroutineScope,
    private val client: ProviderClient = ProviderClient,
) {
    private val _state = MutableStateFlow(
        DiscussionState(
            topic = "", mode = DiscussionMode.ROUNDTABLE,
            speakers = emptyList(), maxTurns = 6, turns = emptyList(),
            isRunning = false,
        )
    )
    val state: StateFlow<DiscussionState> = _state.asStateFlow()

    private var loopJob: Job? = null

    fun start(config: DiscussionConfig) {
        if (loopJob?.isActive == true) return
        val speakersWithRoles = PromptBuilder.assignRoles(config.mode, config.speakers)
        _state.value = DiscussionState(
            topic       = config.topic,
            mode        = config.mode,
            speakers    = speakersWithRoles,
            maxTurns    = config.maxTurns,
            judgeSpeaker= if (config.enableJudge) config.judgeSpeaker else null,
            turns       = emptyList(),
            isRunning   = true,
        )
        loopJob = scope.launch { runLoop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _state.value = _state.value.copy(
            isRunning = false,
            stoppedReason = _state.value.stoppedReason ?: "stopped by user",
            turns = _state.value.turns.map {
                if (it.status == TurnStatus.STREAMING || it.status == TurnStatus.PENDING)
                    it.copy(status = TurnStatus.STOPPED) else it
            }
        )
    }

    /** User-initiated "keep going" past maxTurns. Adds one more round of turns. */
    fun continueAfterCap() {
        if (loopJob?.isActive == true) return
        val s = _state.value
        _state.value = s.copy(maxTurns = s.maxTurns + s.speakers.size, isRunning = true, stoppedReason = null, converged = false)
        loopJob = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        try {
            while (true) {
                val s = _state.value
                if (!s.isRunning) break
                val completed = s.turns.count { it.status == TurnStatus.DONE }
                if (completed >= s.maxTurns) {
                    finishLoop(reason = "max turns reached")
                    break
                }
                if (ConvergenceDetector.isConverged(s.turns)) {
                    _state.value = s.copy(converged = true)
                    finishLoop(reason = "converged")
                    break
                }
                val nextSpeaker = s.speakers[completed % s.speakers.size]
                val outcome = runOneTurn(nextSpeaker)
                if (outcome == TurnOutcome.OUT_OF_CREDITS) {
                    finishLoop(reason = "out of credits"); break
                }
                // 429: small backoff and skip this speaker for the round
                if (outcome == TurnOutcome.RATE_LIMITED) delay(500)
            }
        } finally {
            loopJob = null
        }
    }

    private enum class TurnOutcome { OK, RATE_LIMITED, OUT_OF_CREDITS, FAILED }

    private suspend fun runOneTurn(speaker: Speaker): TurnOutcome {
        val s0 = _state.value
        val priorDone = s0.turns.filter { it.status == TurnStatus.DONE }
        val messages = PromptBuilder.buildMessages(
            topic = s0.topic, mode = s0.mode, speaker = speaker,
            speakers = s0.speakers, priorTurns = priorDone,
        )
        // Add a placeholder STREAMING turn
        val turnIndex = s0.turns.size
        val placeholder = Turn(
            speakerId    = speaker.id,
            speakerLabel = speaker.label,
            role         = speaker.role,
            text         = "",
            status       = TurnStatus.STREAMING,
        )
        _state.value = s0.copy(turns = s0.turns + placeholder)

        val sb = StringBuilder()
        var outcome = TurnOutcome.OK
        try {
            client.streamChat(speaker, messages).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Delta -> {
                        sb.append(chunk.text)
                        updateTurn(turnIndex) { it.copy(text = sb.toString()) }
                    }
                    is ChatChunk.Done -> {
                        updateTurn(turnIndex) {
                            it.copy(status = TurnStatus.DONE,
                                    promptTokens = chunk.promptTokens,
                                    completionTokens = chunk.completionTokens)
                        }
                    }
                    is ChatChunk.RateLimited -> {
                        outcome = TurnOutcome.RATE_LIMITED
                        updateTurn(turnIndex) {
                            it.copy(status = TurnStatus.SKIPPED, errorMessage = "rate limited — skipped")
                        }
                    }
                    is ChatChunk.OutOfCredits -> {
                        outcome = TurnOutcome.OUT_OF_CREDITS
                        updateTurn(turnIndex) {
                            it.copy(status = TurnStatus.FAILED, errorMessage = chunk.message)
                        }
                    }
                    is ChatChunk.FailedRequest -> {
                        outcome = TurnOutcome.FAILED
                        updateTurn(turnIndex) {
                            it.copy(status = TurnStatus.FAILED, errorMessage = chunk.message)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateTurn(turnIndex) { it.copy(status = TurnStatus.STOPPED) }
            throw e
        } catch (e: Exception) {
            outcome = TurnOutcome.FAILED
            updateTurn(turnIndex) {
                it.copy(status = TurnStatus.FAILED, errorMessage = e.message ?: e.javaClass.simpleName)
            }
        }
        return outcome
    }

    private suspend fun finishLoop(reason: String) {
        val s = _state.value
        // Optional judge summary
        val judge = s.judgeSpeaker
        if (judge != null && s.turns.any { it.status == TurnStatus.DONE }) {
            runJudgeSummary(judge)
        }
        _state.value = _state.value.copy(isRunning = false, stoppedReason = reason)
    }

    private suspend fun runJudgeSummary(judge: Speaker) {
        val s0 = _state.value
        val priorDone = s0.turns.filter { it.status == TurnStatus.DONE }
        val messages = PromptBuilder.buildJudgeMessages(s0.topic, priorDone)
        val placeholder = Turn(
            speakerId    = judge.id,
            speakerLabel = "Judge — ${judge.model.name}",
            role         = "Judge",
            text         = "",
            status       = TurnStatus.STREAMING,
        )
        val idx = s0.turns.size
        _state.value = s0.copy(turns = s0.turns + placeholder)
        val sb = StringBuilder()
        try {
            client.streamChat(judge, messages).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Delta -> {
                        sb.append(chunk.text)
                        updateTurn(idx) { it.copy(text = sb.toString()) }
                    }
                    is ChatChunk.Done -> {
                        updateTurn(idx) { it.copy(status = TurnStatus.DONE,
                            promptTokens = chunk.promptTokens, completionTokens = chunk.completionTokens) }
                    }
                    is ChatChunk.RateLimited,
                    is ChatChunk.OutOfCredits,
                    is ChatChunk.FailedRequest -> {
                        val msg = when (chunk) {
                            is ChatChunk.RateLimited   -> "rate limited"
                            is ChatChunk.OutOfCredits  -> chunk.message
                            is ChatChunk.FailedRequest -> chunk.message
                            else -> ""
                        }
                        updateTurn(idx) { it.copy(status = TurnStatus.FAILED, errorMessage = msg) }
                    }
                }
            }
        } catch (e: Exception) {
            updateTurn(idx) {
                it.copy(status = TurnStatus.FAILED, errorMessage = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun updateTurn(index: Int, mutator: (Turn) -> Turn) {
        val cur = _state.value
        if (index !in cur.turns.indices) return
        val newTurns = cur.turns.toMutableList()
        newTurns[index] = mutator(newTurns[index])
        _state.value = cur.copy(turns = newTurns)
    }
}
