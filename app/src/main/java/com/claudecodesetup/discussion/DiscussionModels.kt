package com.claudecodesetup.discussion

import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.Provider

/** A model participating in a discussion. */
data class Speaker(
    val provider: Provider,
    val model: AiModel,
    val apiKey: String,
    val baseUrl: String,
    /** Optional per-speaker role (e.g. "Pro", "Con", "Moderator"). Empty = no role. */
    val role: String = ""
) {
    val label: String get() = if (role.isNotEmpty()) "${model.name} (${role})" else model.name
    val id: String get() = "${provider.id}:${model.modelId}"
}

/** Modes change the system prompt scaffolding and how roles are assigned. */
enum class DiscussionMode(val label: String, val tagline: String) {
    ROUNDTABLE("Roundtable", "Open-ended discussion — disagree when warranted."),
    DEBATE   ("Debate",     "Models take sides (For / Against / Moderator)."),
    CRITIQUE ("Critique",   "First proposes, the rest tear it apart."),
    CODE_REVIEW("Code Review", "Critique tuned for code. Verify-by-running still needs the terminal.")
}

/** Status of an in-progress turn for live UI rendering. */
enum class TurnStatus { PENDING, STREAMING, DONE, FAILED, SKIPPED, STOPPED }

/**
 * How the human (the app user) takes part in the discussion.
 *   NONE      — set the topic and watch (the original behavior).
 *   SEAT      — the human is a participant in the turn rotation; the loop pauses
 *               and waits for the human to type each time their slot comes up.
 *   INTERJECT — models auto-debate; the human can drop a comment in at any time
 *               and the following models react to it.
 */
enum class HumanRole { NONE, SEAT, INTERJECT }

/**
 * Pacing for INTERJECT mode — how the loop gives the human room to jump in.
 *   OPEN_FLOOR — after each model turn the loop pauses (floorOpen) until the
 *                human sends a comment or taps Pass. The human is never rushed.
 *   DELAY      — a fixed gap (reactionDelaySec) after each model turn during
 *                which the human can interject; auto-continues if they stay quiet.
 */
enum class Pacing { OPEN_FLOOR, DELAY }

/** How a participant voted in the concluding vote. */
enum class VoteChoice { FOR, AGAINST, UNDECIDED }

/** One participant's final vote that resolves the discussion. */
data class Vote(
    val speakerId: String,
    val speakerLabel: String,
    val choice: VoteChoice,
    val reason: String,
    val isHuman: Boolean = false,
)

/** One speaker's contribution to the transcript. */
data class Turn(
    val speakerId: String,
    val speakerLabel: String,
    val role: String,
    val text: String,
    val status: TurnStatus,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val errorMessage: String? = null,
    /** True for a turn typed by the human participant (rendered as a user bubble). */
    val isHuman: Boolean = false,
)

/** Snapshot of an entire discussion, used by the UI as immutable state. */
data class DiscussionState(
    val topic: String,
    val mode: DiscussionMode,
    val speakers: List<Speaker>,
    val maxTurns: Int,
    val judgeSpeaker: Speaker? = null,
    val turns: List<Turn> = emptyList(),
    val isRunning: Boolean = false,
    val converged: Boolean = false,
    val stoppedReason: String? = null,
    val humanRole: HumanRole = HumanRole.NONE,
    val humanLabel: String = "You",
    /** True when a SEAT-mode discussion is paused waiting for the human to type. */
    val awaitingHuman: Boolean = false,
    // ── Pacing (INTERJECT) ──
    val pacing: Pacing = Pacing.DELAY,
    val reactionDelaySec: Int = 5,
    /** OPEN_FLOOR: true while the loop is paused after a model turn, waiting for
     *  the human to interject or Pass. */
    val floorOpen: Boolean = false,
    // ── Concluding vote ──
    val enableVoting: Boolean = false,
    /** True while model votes are being collected. */
    val votingPhase: Boolean = false,
    /** True when the panel has voted and we're waiting for the human's vote. */
    val awaitingHumanVote: Boolean = false,
    val votes: List<Vote> = emptyList(),
) {
    val votesFor: Int get() = votes.count { it.choice == VoteChoice.FOR }
    val votesAgainst: Int get() = votes.count { it.choice == VoteChoice.AGAINST }
    val votesUndecided: Int get() = votes.count { it.choice == VoteChoice.UNDECIDED }
    val totalPromptTokens: Int get() = turns.sumOf { it.promptTokens }
    val totalCompletionTokens: Int get() = turns.sumOf { it.completionTokens }
    val turnsTaken: Int get() = turns.count { it.status == TurnStatus.DONE || it.status == TurnStatus.FAILED || it.status == TurnStatus.SKIPPED }
}

/** Config produced by the setup screen, consumed by the orchestrator. */
data class DiscussionConfig(
    val topic: String,
    val mode: DiscussionMode,
    val speakers: List<Speaker>,
    val maxTurns: Int = 6,
    val enableJudge: Boolean = false,
    val judgeSpeaker: Speaker? = null,
    val humanRole: HumanRole = HumanRole.NONE,
    val humanLabel: String = "You",
    val pacing: Pacing = Pacing.DELAY,
    val reactionDelaySec: Int = 5,
    val enableVoting: Boolean = false,
)
