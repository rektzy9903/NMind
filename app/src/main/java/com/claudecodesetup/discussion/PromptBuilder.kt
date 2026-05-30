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
            DiscussionMode.ROUNDTABLE -> BASE_RULES +
                "\n\nThis is an open, unbiased discussion — you hold no fixed side. " +
                "If another speaker changes your mind, say so directly and explain exactly what convinced you. " +
                "Don't cling to your first take just to stay consistent — reasoning honestly matters more than looking right."
            DiscussionMode.DEBATE -> {
                val side = speaker.role.ifEmpty { "Participant" }
                val stance = when (side) {
                    "Defence" ->
                        "You take the AFFIRMATIVE side. If the topic is a yes/no question or a proposal, " +
                        "argue FOR it. If the topic is an either/or choice (\"X or Y\"), you champion the " +
                        "FIRST option named and must commit to it the whole debate."
                    "Opposition" ->
                        "You take the OPPOSING side. If the topic is a yes/no question or a proposal, " +
                        "argue AGAINST it. If the topic is an either/or choice (\"X or Y\"), you champion the " +
                        "SECOND option named and must commit to it the whole debate."
                    "Moderator" ->
                        "You are the Moderator. Stay neutral, summarize the disagreement crisply, " +
                        "and ask the sharpest next question."
                    else -> "Argue your assigned position rigorously."
                }
                "$BASE_RULES\n\nYour assigned side: $side. $stance " +
                "Pick your side and defend it — never reply \"both\", \"it depends\", or refuse to choose."
            }
            DiscussionMode.CRITIQUE -> {
                val critiqueRules = (
                    "You're on a review panel with other AI models. " +
                    "The SUBJECT under review is the material in the topic above — a claim, fact, plan, argument, or piece of writing the user presented. " +
                    "Your job is to critique THAT material. Do NOT debate the other panelists and do NOT invent a fresh proposal of your own. " +
                    "Read prior critiques so you don't repeat them and can build on or correct them — but keep every point aimed at the topic itself. " +
                    "Be specific: name concrete flaws, hidden assumptions, missing cases, factual errors, weak evidence — and acknowledge what's genuinely solid. " +
                    "Suggest fixes, not just complaints. Keep it focused: 2–6 sentences unless depth is warranted."
                )
                if (isFirst) "$critiqueRules\n\nYou open the critique. Assess the topic material directly — its strongest and weakest points — so the panel has a sharp starting point."
                else "$critiqueRules\n\nContinue the critique. Add flaws or defenses the earlier reviewers missed, and correct any point of theirs that misreads the topic. Stay on the topic material — don't drift into arguing with the other reviewers."
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
        // If the user picked sides in setup (all roles set), honor them; else
        // fall back to the index-based default.
        DiscussionMode.DEBATE ->
            if (speakers.size in 2..4 && speakers.all { it.role.isNotEmpty() }) speakers
            else defaultDebateRoles(speakers)
        DiscussionMode.CRITIQUE, DiscussionMode.CODE_REVIEW -> {
            // First speaker proposes, rest critique. Labels are advisory.
            speakers.mapIndexed { i, s ->
                if (i == 0) s.copy(role = "Lead") else s.copy(role = "Reviewer")
            }
        }
        DiscussionMode.ROUNDTABLE -> speakers
    }

    /** Default side assignment for Debate when the user hasn't chosen: first
     *  speaker(s) Defence ("For"), rest Opposition ("Against"), with a Moderator
     *  for a 3-way. Used as the setup-screen default and the start() fallback. */
    fun defaultDebateRoles(speakers: List<Speaker>): List<Speaker> = when (speakers.size) {
        2 -> listOf(speakers[0].copy(role = "Defence"), speakers[1].copy(role = "Opposition"))
        3 -> listOf(speakers[0].copy(role = "Defence"), speakers[1].copy(role = "Opposition"), speakers[2].copy(role = "Moderator"))
        4 -> listOf(
            speakers[0].copy(role = "Defence"), speakers[1].copy(role = "Defence"),
            speakers[2].copy(role = "Opposition"), speakers[3].copy(role = "Opposition"),
        )
        else -> speakers
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

    /**
     * Debate verdict by a neutral judge. The transcript is anonymized to
     * "Side A (Defence)" / "Side B (Opposition)" — model names stripped — so the
     * judge can't recognize and side with its own earlier arguments. The judge
     * must commit to a WINNER on the merits, not on confidence/tone/length.
     */
    fun buildVerdictMessages(topic: String, turns: List<Turn>): List<ChatMessage> {
        val sys = "You are a strict, neutral adjudicator of a debate. You have no stake in either side. " +
            "Judge ONLY on the merit and rigor of the arguments — ignore confidence, tone, and length. " +
            "The sides are anonymized: \"Side A (Defence)\" argues FOR the topic / the FIRST option, " +
            "\"Side B (Opposition)\" argues AGAINST the topic / the SECOND option. " +
            "You MUST reach a concrete verdict — never answer \"both\", \"it depends\", or compare endlessly without deciding.\n" +
            "Output EXACTLY this shape:\n" +
            "Line 1 — ANSWER: <directly resolve the topic's question — name the single winning option, " +
            "or YES/NO for a proposal>\n" +
            "Line 2 — one of:  WINNER: DEFENCE  |  WINNER: OPPOSITION  |  WINNER: DRAW\n" +
            "Then three short points: (1) the decisive argument, (2) why the winning answer is best on the merits, " +
            "(3) the losing side's key weakness. Be concise and impartial."
        val sb = StringBuilder()
        sb.append("## Topic\n").append(topic.trim()).append("\n\n## Arguments (anonymized)\n\n")
        for (t in turns) {
            if (t.status != TurnStatus.DONE || t.isHuman) continue
            val side = when (t.role) {
                "Defence"    -> "Side A (Defence)"
                "Opposition" -> "Side B (Opposition)"
                "Moderator"  -> "Moderator (neutral)"
                else         -> "Participant"
            }
            sb.append("### ").append(side).append("\n").append(t.text.trim()).append("\n\n")
        }
        sb.append("---\nDeliver your verdict. Line 1 must start with \"ANSWER:\" and name the single winner:")
        return listOf(ChatMessage("system", sys), ChatMessage("user", sb.toString()))
    }

    /**
     * Code-review: consolidate the whole discussion into a single numbered,
     * categorized findings list. Strict one-finding-per-line format so the
     * orchestrator can parse it back into ReviewFinding objects.
     */
    fun buildFindingsExtractionMessages(topic: String, turns: List<Turn>): List<ChatMessage> {
        val sys = "You are consolidating a multi-model code review into ONE findings list. " +
            "Merge duplicates, drop vague or unverifiable comments, keep only concrete, actionable findings. " +
            "Output ONLY the findings — one per line — in EXACTLY this format:\n" +
            "<n>. [CATEGORY] description (line/location if known) — suggested fix\n" +
            "CATEGORY is one of: BUG, OPTIMIZATION, DEAD_CODE, OTHER. Number from 1. " +
            "No preamble, no headings, no closing remarks. Max 15 findings."
        val sb = StringBuilder()
        sb.append("## Code / topic under review\n").append(topic.trim()).append("\n\n## Review discussion\n\n")
        for (t in turns) {
            if (t.status != TurnStatus.DONE || t.isHuman) continue
            sb.append("### ").append(t.speakerLabel).append("\n").append(t.text.trim()).append("\n\n")
        }
        sb.append("---\nConsolidated findings (numbered, one per line):")
        return listOf(ChatMessage("system", sys), ChatMessage("user", sb.toString()))
    }

    /**
     * Code-review: ask one model to agree/disagree with each consolidated
     * finding. One line per finding: "<n>: AGREE" or "<n>: DISAGREE".
     */
    fun buildFindingsVoteMessages(topic: String, findings: List<ReviewFinding>, speaker: Speaker): List<ChatMessage> {
        val sys = "You are validating a consolidated list of code-review findings. " +
            "For EACH finding, decide whether it is a real, correct, worth-fixing issue given the code. " +
            "Reply with one line per finding in EXACTLY this format: \"<number>: AGREE\" or \"<number>: DISAGREE\". " +
            "AGREE means the finding is valid and worth acting on. Output nothing else. You are: ${speaker.label}."
        val sb = StringBuilder()
        sb.append("## Code / topic\n").append(topic.trim()).append("\n\n## Findings\n")
        for (f in findings) sb.append(f.index).append(". [").append(f.category).append("] ").append(f.text).append("\n")
        sb.append("\nYour votes (one \"<number>: AGREE|DISAGREE\" per line):")
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
