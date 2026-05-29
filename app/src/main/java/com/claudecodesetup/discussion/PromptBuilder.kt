package com.claudecodesetup.discussion

/** Adapter-friendly chat message. ProviderClient maps role → provider-specific shape. */
data class ChatMessage(val role: String, val content: String) // role: "system" | "user" | "assistant"

/**
 * Assembles the request a single speaker sees on their turn. The transcript so
 * far is rendered as a single user-role message so every provider (OAI-format
 * + Anthropic API) handles it identically — we don't need to reconstruct an
 * alternating user/assistant chain across multiple speakers, which doesn't map
 * cleanly to any provider's chat protocol.
 *
 * The full transcript IS included every turn — that's what lets speakers
 * correct and argue with each other. Cost grows quadratically; the orchestrator
 * caps turns to keep it bounded.
 */
object PromptBuilder {

    private const val BASE_RULES = (
        "You're in a panel discussion with other AI models. " +
        "Read what the others said. Disagree when you think they're wrong and explain why with specifics. " +
        "Update your own position if a counter-argument convinces you. " +
        "Don't summarize, don't be polite for politeness' sake — but don't manufacture disagreement either. " +
        "If you broadly agree, say so briefly and add one new angle. " +
        "Keep replies focused: 2–6 sentences unless the topic genuinely needs depth."
    )

    fun systemPromptFor(mode: DiscussionMode, speaker: Speaker, speakers: List<Speaker>, isFirst: Boolean, humanLabel: String? = null): String {
        val others = speakers.filter { it.id != speaker.id }
            .joinToString(", ") { it.label }
        val humanLine = if (humanLabel != null)
            "\nA human participant (labeled \"$humanLabel\") is also at the table — read and respond to their points like any other speaker's."
        else ""
        val rosterLine = if (others.isNotEmpty())
            "\nThe other speakers in this discussion: $others.\nYou are: ${speaker.label}.$humanLine\n"
        else "\nYou are: ${speaker.label}.$humanLine\n"

        val modePart = when (mode) {
            DiscussionMode.ROUNDTABLE -> BASE_RULES
            DiscussionMode.DEBATE -> {
                val side = speaker.role.ifEmpty { "Participant" }
                "$BASE_RULES\n\nYour assigned side: $side. " +
                "Argue this position rigorously. If you're the Moderator, stay neutral, " +
                "summarize the disagreement crisply, and ask the sharpest next question."
            }
            DiscussionMode.CRITIQUE -> {
                if (isFirst) "$BASE_RULES\n\nYou go first. Propose a concrete solution / answer / plan in 1–2 paragraphs. " +
                    "Subsequent speakers will critique your proposal."
                else "$BASE_RULES\n\nYou are critiquing the proposal and any prior critiques. " +
                    "Be specific — name flaws, edge cases, missed assumptions. Suggest fixes, not just complaints."
            }
            DiscussionMode.CODE_REVIEW -> {
                if (isFirst) "$BASE_RULES\n\nYou go first. Identify the most likely bug, performance issue, or design problem in " +
                    "the snippet/topic. Cite specific lines or constructs. State what's correct as well as what's wrong."
                else "$BASE_RULES\n\nYou are code-reviewing the snippet/problem in the topic. " +
                    "Focus on correctness, edge cases, complexity, and concrete line references. " +
                    "Push back on claims you can't verify by reading the code; flag where execution would be needed. " +
                    "Prefer specific fixes over vague advice."
            }
        }
        return modePart + rosterLine
    }

    /** Builds the message list for one speaker's turn. */
    fun buildMessages(
        topic: String,
        mode: DiscussionMode,
        speaker: Speaker,
        speakers: List<Speaker>,
        priorTurns: List<Turn>,
        humanLabel: String? = null,
    ): List<ChatMessage> {
        val sys = systemPromptFor(mode, speaker, speakers, isFirst = priorTurns.isEmpty(), humanLabel = humanLabel)
        val transcript = renderTranscript(topic, priorTurns, speaker)
        return listOf(
            ChatMessage("system", sys),
            ChatMessage("user", transcript),
        )
    }

    /**
     * Assigns roles to speakers for modes that use them. Returns a new list of
     * Speakers with `role` populated. The orchestrator calls this once at
     * start; the roles are stable for the duration of the discussion.
     */
    fun assignRoles(mode: DiscussionMode, speakers: List<Speaker>): List<Speaker> = when (mode) {
        DiscussionMode.DEBATE -> when (speakers.size) {
            2 -> listOf(speakers[0].copy(role = "For"), speakers[1].copy(role = "Against"))
            3 -> listOf(speakers[0].copy(role = "For"), speakers[1].copy(role = "Against"), speakers[2].copy(role = "Moderator"))
            4 -> listOf(
                speakers[0].copy(role = "For"), speakers[1].copy(role = "For"),
                speakers[2].copy(role = "Against"), speakers[3].copy(role = "Against"),
            )
            else -> speakers
        }
        DiscussionMode.CRITIQUE, DiscussionMode.CODE_REVIEW -> {
            // First speaker proposes, rest critique. Labels are advisory.
            speakers.mapIndexed { i, s ->
                if (i == 0) s.copy(role = "Proposer") else s.copy(role = "Reviewer")
            }
        }
        DiscussionMode.ROUNDTABLE -> speakers
    }

    private fun renderTranscript(topic: String, priorTurns: List<Turn>, currentSpeaker: Speaker): String {
        val sb = StringBuilder()
        sb.append("## Topic\n").append(topic.trim()).append("\n\n")
        if (priorTurns.isEmpty()) {
            sb.append("(You are the first speaker. Open the discussion.)\n")
        } else {
            sb.append("## Discussion so far\n\n")
            for (t in priorTurns) {
                if (t.status != TurnStatus.DONE) continue
                sb.append("### ").append(t.speakerLabel).append("\n")
                sb.append(t.text.trim()).append("\n\n")
            }
            sb.append("---\n\n")
            sb.append("Now you respond as **").append(currentSpeaker.label).append("**.\n")
        }
        return sb.toString()
    }

    /**
     * Builds the message list for one speaker's concluding vote. The model must
     * commit to FOR / AGAINST / UNDECIDED on the central question in the topic,
     * based on the whole discussion. The first line is the vote token so it
     * parses reliably; the rest is one sentence of reasoning.
     */
    fun buildVoteMessages(topic: String, turns: List<Turn>, speaker: Speaker): List<ChatMessage> {
        val sys = "The discussion is concluding and the panel is now voting. " +
            "Cast your FINAL vote on the central question or proposal stated in the topic. " +
            "Your FIRST line must be EXACTLY one word — FOR, AGAINST, or UNDECIDED. " +
            "Then add ONE sentence of reasoning. Decide based on the whole discussion, " +
            "not just your earlier position — you may change your mind. You are: ${speaker.label}."
        val sb = StringBuilder()
        sb.append("## Topic\n").append(topic.trim()).append("\n\n## Discussion\n\n")
        for (t in turns) {
            if (t.status != TurnStatus.DONE) continue
            sb.append("### ").append(t.speakerLabel).append("\n").append(t.text.trim()).append("\n\n")
        }
        sb.append("---\nYour vote (FOR / AGAINST / UNDECIDED) + one sentence:")
        return listOf(ChatMessage("system", sys), ChatMessage("user", sb.toString()))
    }

    /** Builds the message list for the judge summary at the end. */
    fun buildJudgeMessages(topic: String, turns: List<Turn>): List<ChatMessage> {
        val sys = "You are a neutral judge summarizing a panel discussion between AI models. " +
            "Read the full transcript. In exactly three short paragraphs: " +
            "(1) what the speakers agreed on, (2) where they disagreed and why, " +
            "(3) which argument was strongest and what's left unresolved. " +
            "Don't take a side beyond identifying the strongest argument by merit."
        val sb = StringBuilder()
        sb.append("## Topic\n").append(topic.trim()).append("\n\n## Transcript\n\n")
        for (t in turns) {
            if (t.status != TurnStatus.DONE) continue
            sb.append("### ").append(t.speakerLabel).append("\n")
            sb.append(t.text.trim()).append("\n\n")
        }
        return listOf(
            ChatMessage("system", sys),
            ChatMessage("user", sb.toString()),
        )
    }
}
