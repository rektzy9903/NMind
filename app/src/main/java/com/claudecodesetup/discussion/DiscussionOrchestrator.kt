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
            humanRole   = config.humanRole,
            humanLabel  = config.humanLabel,
            pacing      = config.pacing,
            reactionDelaySec = config.reactionDelaySec,
            enableVoting = config.enableVoting,
        )
        loopJob = scope.launch { runLoop() }
    }

    /**
     * Records a turn typed by the human participant and (for SEAT mode) resumes
     * the loop that paused waiting for it. Safe to call any time in INTERJECT
     * mode — the next model turn will see the comment in the transcript.
     */
    fun submitHumanTurn(text: String) {
        val s = _state.value
        if (s.humanRole == HumanRole.NONE || text.isBlank()) return
        val turn = Turn(
            speakerId    = "human",
            speakerLabel = s.humanLabel,
            role         = "",
            text         = text.trim(),
            status       = TurnStatus.DONE,
            isHuman      = true,
        )
        _state.value = s.copy(turns = s.turns + turn, awaitingHuman = false, floorOpen = false)
        // Resume the loop if it paused for us (SEAT slot or OPEN_FLOOR). For a
        // DELAY-window interjection the loop is still active, so this is a no-op
        // and the turn is simply picked up when the delay elapses.
        if (loopJob?.isActive != true && s.isRunning) {
            loopJob = scope.launch { runLoop() }
        }
    }

    /** INTERJECT + OPEN_FLOOR: the human declines to speak this round; continue. */
    fun passFloor() {
        val s = _state.value
        if (!s.floorOpen || loopJob?.isActive == true || !s.isRunning) return
        _state.value = s.copy(floorOpen = false)
        loopJob = scope.launch { runLoop() }
    }

    /** Records the human's vote in the concluding vote and closes the vote. */
    fun submitHumanVote(choice: VoteChoice) {
        val s = _state.value
        if (!s.awaitingHumanVote) return
        val vote = Vote(
            speakerId = "human", speakerLabel = s.humanLabel,
            choice = choice, reason = "", isHuman = true,
        )
        _state.value = s.copy(votes = s.votes + vote, awaitingHumanVote = false)
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _state.value = _state.value.copy(
            isRunning = false,
            awaitingHuman = false,   // hide the human input bar when stopped mid-turn
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
                // Convergence is judged on the models' turns only — a short human
                // interjection like "ok, agreed" must not end the discussion.
                if (ConvergenceDetector.isConverged(s.turns.filter { !it.isHuman })) {
                    _state.value = s.copy(converged = true)
                    finishLoop(reason = "converged")
                    break
                }
                // Pick the next model. SEAT puts the human in the last slot of
                // each round (rotation = models + 1) and pauses there. For
                // INTERJECT/NONE the rotation is models only, indexed by the count
                // of MODEL turns so a human interjection never desyncs the order.
                val nextSpeaker: Speaker
                if (s.humanRole == HumanRole.SEAT) {
                    val rotationSize = s.speakers.size + 1
                    if (completed % rotationSize == s.speakers.size) {
                        _state.value = s.copy(awaitingHuman = true)
                        return  // wait for submitHumanTurn() to relaunch the loop
                    }
                    nextSpeaker = s.speakers[completed % rotationSize]
                } else {
                    val modelTurns = s.turns.count { it.status == TurnStatus.DONE && !it.isHuman }
                    nextSpeaker = s.speakers[modelTurns % s.speakers.size]
                }
                val outcome = runOneTurn(nextSpeaker)
                if (outcome == TurnOutcome.OUT_OF_CREDITS) {
                    finishLoop(reason = "out of credits"); break
                }
                // 429: small backoff and skip this speaker for the round
                if (outcome == TurnOutcome.RATE_LIMITED) delay(500)

                // INTERJECT pacing: give the human a window before the next model.
                if (s.humanRole == HumanRole.INTERJECT && outcome == TurnOutcome.OK) {
                    when (s.pacing) {
                        Pacing.OPEN_FLOOR -> {
                            _state.value = _state.value.copy(floorOpen = true)
                            return  // wait for submitHumanTurn() / passFloor() to resume
                        }
                        Pacing.DELAY -> delay(s.reactionDelaySec.coerceIn(1, 30) * 1000L)
                    }
                }
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
            humanLabel = if (s0.humanRole != HumanRole.NONE) s0.humanLabel else null,
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

        // Concluding vote — the panel's final resolution. Each model commits to
        // FOR / AGAINST / UNDECIDED; if the human is a participant, we then open
        // the human's vote (resolved synchronously via submitHumanVote()).
        if (s.enableVoting && _state.value.turns.any { it.status == TurnStatus.DONE && !it.isHuman }) {
            runModelVotes()
            if (_state.value.humanRole != HumanRole.NONE) {
                _state.value = _state.value.copy(awaitingHumanVote = true)
            }
        }
    }

    private suspend fun runModelVotes() {
        _state.value = _state.value.copy(votingPhase = true)
        val speakers = _state.value.speakers
        for (speaker in speakers) {
            val s0 = _state.value
            val priorDone = s0.turns.filter { it.status == TurnStatus.DONE }
            val text = collectText(speaker, PromptBuilder.buildVoteMessages(s0.topic, priorDone, speaker))
            val (choice, reason) = parseVote(text)
            _state.value = _state.value.copy(
                votes = _state.value.votes + Vote(speaker.id, speaker.label, choice, reason),
            )
        }
        _state.value = _state.value.copy(votingPhase = false)
    }

    /** Collects a full (non-incremental) streamed response into a single string. */
    private suspend fun collectText(speaker: Speaker, messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        try {
            client.streamChat(speaker, messages).collect { chunk ->
                if (chunk is ChatChunk.Delta) sb.append(chunk.text)
            }
        } catch (_: Exception) { /* fall through — parseVote handles empty as UNDECIDED */ }
        return sb.toString()
    }

    /** First word FOR/AGAINST/UNDECIDED wins; reason is the trimmed remainder. */
    private fun parseVote(text: String): Pair<VoteChoice, String> {
        val head = text.trimStart().take(40).uppercase()
        val choice = when {
            Regex("\\bAGAINST\\b").containsMatchIn(head)   -> VoteChoice.AGAINST
            Regex("\\bUNDECIDED\\b").containsMatchIn(head)  -> VoteChoice.UNDECIDED
            Regex("\\bFOR\\b").containsMatchIn(head)        -> VoteChoice.FOR
            else -> VoteChoice.UNDECIDED
        }
        // Reason: drop a leading vote token + punctuation, keep one line.
        val reason = text.trim()
            .replaceFirst(Regex("^(FOR|AGAINST|UNDECIDED)[\\s:.,;-]*", RegexOption.IGNORE_CASE), "")
            .lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(200) ?: ""
        return choice to reason
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
