package com.claudecodesetup.quickask

import com.claudecodesetup.discussion.Speaker

/** Which side a bubble belongs to in the UI. */
enum class MessageRole { USER, ASSISTANT }

/** Per-message status — drives spinner / error rendering. */
enum class MessageStatus { PENDING, STREAMING, DONE, FAILED, STOPPED }

data class Message(
    val role: MessageRole,
    val text: String,
    val status: MessageStatus,
    val speakerId: String? = null,       // which model produced this (assistant only)
    val speakerLabel: String? = null,    // human-readable model name (assistant only)
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val errorMessage: String? = null,
    /** Local file path of a generated image (image-gen turns only). */
    val imagePath: String? = null,
)

/** Immutable UI snapshot owned by QuickAskViewModel. */
data class QuickAskState(
    val activeSpeaker: Speaker? = null,
    val messages: List<Message> = emptyList(),
    val isStreaming: Boolean = false,
) {
    val totalPromptTokens: Int      get() = messages.sumOf { it.promptTokens }
    val totalCompletionTokens: Int  get() = messages.sumOf { it.completionTokens }
}
