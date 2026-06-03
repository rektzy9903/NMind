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

    /** User-initiated "keep going" past maxTurns. Adds one more round of turns.
     *  Clears the prior concluding-vote state so the extra round runs cleanly and
     *  is re-voted fresh at the end — otherwise finishLoop appends a second set of
     *  votes to the old ones (duplicate votes, "stuck on voting" UI). */
    fun continueAfterCap() {
        if (loopJob?.isActive == true) return
        val s = _state.value
        // Unlimited (maxTurns <= 0) stays unlimited; a capped run gets one more round.
        val newCap = if (s.maxTurns <= 0) 0 else s.maxTurns + s.speakers.size
        _state.value = s.copy(
            maxTurns = newCap,
            isRunning = true, stoppedReason = null, converged = false,
            votes = emptyList(), awaitingHumanVote = false, votingPhase = false,
            reviewFindings = emptyList(), reviewPhase = false,
        )
        loopJob = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        try {
            while (true) {
                val s = _state.value
                if (!s.isRunning) break
                val completed = s.turns.count { it.status == TurnStatus.DONE }
                // maxTurns <= 0 means unlimited — only convergence / credits / a
                // manual stop ends the debate.
                if (s.maxTurns > 0 && completed >= s.maxTurns) {
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
        // Optional judge. In Debate it delivers a neutral, anonymized VERDICT
        // (picks a winner on the merits); in other modes, a 3-paragraph summary.
        val judge = s.judgeSpeaker
        if (judge != null && s.turns.any { it.status == TurnStatus.DONE }) {
            when (s.mode) {
                DiscussionMode.DEBATE      -> runVerdict()
                DiscussionMode.CODE_REVIEW -> runReviewReport()
                else                       -> runJudgeSummary(judge)
            }
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
        runJudgeTurn(judge, PromptBuilder.buildJudgeMessages(s0.topic, priorDone),
            "Judge — ${judge.model.name}", "Judge")
    }

    /** Debate-only neutral verdict. Prefers a Moderator as the judge (it argued
     *  no side); falls back to the configured judge / first speaker. The prompt
     *  is anonymized so even a participant judges on merit, not identity. */
    private suspend fun runVerdict() {
        val s0 = _state.value
        val priorDone = s0.turns.filter { it.status == TurnStatus.DONE }
        val judge = s0.speakers.firstOrNull { it.role == "Moderator" }
            ?: s0.judgeSpeaker ?: s0.speakers.firstOrNull() ?: return
        runJudgeTurn(judge, PromptBuilder.buildVerdictMessages(s0.topic, priorDone),
            "⚖ Verdict — ${judge.model.name}", "Verdict")
    }

    /** Code-review: one model consolidates the discussion into a numbered
     *  findings list, then every model votes agree/disagree per finding. The
     *  result (findings + agreement counts) lands in state.reviewFindings. */
    private suspend fun runReviewReport() {
        val s0 = _state.value
        val priorDone = s0.turns.filter { it.status == TurnStatus.DONE }
        if (priorDone.none { !it.isHuman }) return
        _state.value = s0.copy(reviewPhase = true)
        // 1) Consolidate findings (single model).
        val extractor = s0.judgeSpeaker ?: s0.speakers.firstOrNull()
        if (extractor == null) { _state.value = _state.value.copy(reviewPhase = false); return }
        val raw = collectText(extractor, PromptBuilder.buildFindingsExtractionMessages(s0.topic, priorDone))
        val parsed = parseFindings(raw)
        if (parsed.isEmpty()) { _state.value = _state.value.copy(reviewPhase = false); return }
        val voters = _state.value.speakers
        val findings = parsed.map { it.copy(totalVoters = voters.size) }
        _state.value = _state.value.copy(reviewFindings = findings)
        // 2) Each model votes agree/disagree per finding.
        val tally = IntArray(findings.size + 1)
        for (sp in voters) {
            val txt = collectText(sp, PromptBuilder.buildFindingsVoteMessages(s0.topic, findings, sp))
            for (idx in parseFindingVotes(txt, findings.size)) tally[idx]++
        }
        val scored = findings.map { it.copy(agreeCount = tally.getOrElse(it.index) { 0 }) }
            .sortedByDescending { it.agreeCount }
        _state.value = _state.value.copy(reviewFindings = scored, reviewPhase = false)
    }

    /** Parse the extractor's numbered "[CATEGORY] desc" lines into findings. */
    private fun parseFindings(text: String): List<ReviewFinding> {
        val re = Regex("""\[(BUG|OPTIMIZATION|DEAD[_ ]?CODE|OTHER)]\s*(.+)""", RegexOption.IGNORE_CASE)
        val out = mutableListOf<ReviewFinding>()
        for (line in text.lineSequence()) {
            val m = re.find(line) ?: continue
            val cat = m.groupValues[1].uppercase().replace(' ', '_')
            val desc = m.groupValues[2].trim().trim('.', ' ')
            if (desc.isEmpty()) continue
            out.add(ReviewFinding(index = out.size + 1, category = cat, text = desc.take(240)))
            if (out.size >= 15) break
        }
        return out
    }

    /** Parse a model's "<n>: AGREE/DISAGREE" lines; returns the AGREE indices. */
    private fun parseFindingVotes(text: String, count: Int): Set<Int> {
        val agree = mutableSetOf<Int>()
        val re = Regex("""(\d{1,2})\s*[:.)\-]\s*(AGREE|YES|DISAGREE|NO)""", RegexOption.IGNORE_CASE)
        for (mm in re.findAll(text)) {
            val n = mm.groupValues[1].toIntOrNull() ?: continue
            if (n !in 1..count) continue
            val v = mm.groupValues[2].uppercase()
            if (v == "AGREE" || v == "YES") agree.add(n)
        }
        return agree
    }

    private suspend fun runJudgeTurn(judge: Speaker, messages: List<ChatMessage>, label: String, role: String) {
        val s0 = _state.value
        val placeholder = Turn(
            speakerId    = judge.id,
            speakerLabel = label,
            role         = role,
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
                        // Small judge models often garble the required 2-line header
                        // (e.g. "ANSWER: WINNER: DEFENCE" + several contradictory
                        // "WINNER:" tokens). Clean it up so the verdict renders sanely.
                        val finalText = if (role == "Verdict") normalizeVerdict(sb.toString()) else sb.toString()
                        updateTurn(idx) { it.copy(text = finalText, status = TurnStatus.DONE,
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

    /**
     * Rebuild a debate verdict into a clean header even when a weak judge model
     * emits a garbled one. Strategy: collect every "WINNER: DEFENCE|OPPOSITION|DRAW"
     * token and take the majority; pull the ANSWER text (stripping any embedded
     * WINNER tokens); keep the explanatory remainder (the "(1)(2)(3)" points).
     * Falls back to the raw text untouched if nothing parseable is found.
     */
    private fun normalizeVerdict(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return raw
        val winnerRe = Regex("""WINNER:\s*(DEFENCE|OPPOSITION|DRAW)""", RegexOption.IGNORE_CASE)
        val winners = winnerRe.findAll(text).map { it.groupValues[1].uppercase() }.toList()
        val winner = winners.groupingBy { it }.eachCount().entries
            .maxWithOrNull(compareBy({ it.value }, { it.key == "DRAW" }))?.key
        // ANSWER text from the first "ANSWER:" capture, with WINNER tokens / pipes scrubbed.
        val answerRaw = Regex("""ANSWER:\s*(.+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1).orEmpty()
        var answer = answerRaw.replace(winnerRe, "").replace("|", " ").trim().trim('-', ' ', '.')
        if (answer.isEmpty()) {
            answer = when (winner) {
                "DEFENCE"    -> "Defence side"
                "OPPOSITION" -> "Opposition side"
                "DRAW"       -> "Draw — no clear winner"
                else         -> return raw   // unparseable — don't make it worse
            }
        }
        // Body = everything from the first numbered point onward (the (1)(2)(3) reasons).
        val bodyStart = text.indexOf("(1)")
        val body = if (bodyStart >= 0) text.substring(bodyStart).trim() else ""
        val sb = StringBuilder()
        sb.append("ANSWER: ").append(answer).append('\n')
        sb.append("WINNER: ").append(winner ?: "DRAW")
        if (body.isNotEmpty()) sb.append("\n\n").append(body)
        return sb.toString()
    }

    private fun updateTurn(index: Int, mutator: (Turn) -> Turn) {
        val cur = _state.value
        if (index !in cur.turns.indices) return
        val newTurns = cur.turns.toMutableList()
        newTurns[index] = mutator(newTurns[index])
        _state.value = cur.copy(turns = newTurns)
    }
}
