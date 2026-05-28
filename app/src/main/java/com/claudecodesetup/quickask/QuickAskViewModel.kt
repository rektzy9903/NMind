package com.claudecodesetup.quickask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudecodesetup.discussion.ChatChunk
import com.claudecodesetup.discussion.ChatMessage
import com.claudecodesetup.discussion.ProviderClient
import com.claudecodesetup.discussion.Speaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Owns the Quick Ask conversation state. Reusable across screen rotations
 * via Compose's viewModel() — closing the activity drops the state, which
 * matches the MVP "no persistence" policy.
 *
 * Model switching is provider-agnostic: messages are stored as a single
 * provider-neutral list, then converted to [ChatMessage] right before the
 * call, so switching the speaker mid-chat just means the next call uses
 * the new model with the same history.
 */
class QuickAskViewModel(
    private val client: ProviderClient = ProviderClient,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickAskState())
    val state: StateFlow<QuickAskState> = _state.asStateFlow()

    private var streamJob: Job? = null

    fun setSpeaker(s: Speaker?) {
        _state.value = _state.value.copy(activeSpeaker = s)
    }

    fun newChat() {
        streamJob?.cancel()
        streamJob = null
        _state.value = _state.value.copy(messages = emptyList(), isStreaming = false)
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        val msgs = _state.value.messages.toMutableList()
        if (msgs.isNotEmpty() && msgs.last().status == MessageStatus.STREAMING) {
            msgs[msgs.lastIndex] = msgs.last().copy(status = MessageStatus.STOPPED)
        }
        _state.value = _state.value.copy(messages = msgs, isStreaming = false)
    }

    fun send(text: String) {
        if (text.isBlank()) return
        val s = _state.value
        if (s.isStreaming) return
        val speaker = s.activeSpeaker ?: return
        val userMsg = Message(MessageRole.USER, text.trim(), MessageStatus.DONE)
        val placeholder = Message(
            role         = MessageRole.ASSISTANT,
            text         = "",
            status       = MessageStatus.STREAMING,
            speakerId    = speaker.id,
            speakerLabel = speaker.model.name,
        )
        _state.value = s.copy(
            messages = s.messages + userMsg + placeholder,
            isStreaming = true,
        )
        streamJob = viewModelScope.launch { runStream(speaker) }
    }

    private suspend fun runStream(speaker: Speaker) {
        val idx = _state.value.messages.lastIndex
        val chatMessages = buildChatHistory()
        val sb = StringBuilder()
        try {
            client.streamChat(speaker, chatMessages).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Delta -> {
                        sb.append(chunk.text)
                        updateMessage(idx) { it.copy(text = sb.toString()) }
                    }
                    is ChatChunk.Done -> {
                        updateMessage(idx) {
                            it.copy(
                                status           = MessageStatus.DONE,
                                promptTokens     = chunk.promptTokens,
                                completionTokens = chunk.completionTokens,
                            )
                        }
                    }
                    is ChatChunk.RateLimited -> updateMessage(idx) {
                        it.copy(status = MessageStatus.FAILED, errorMessage = "rate limited — try again or pick a different model")
                    }
                    is ChatChunk.OutOfCredits -> updateMessage(idx) {
                        it.copy(status = MessageStatus.FAILED, errorMessage = chunk.message)
                    }
                    is ChatChunk.FailedRequest -> updateMessage(idx) {
                        it.copy(status = MessageStatus.FAILED, errorMessage = chunk.message)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateMessage(idx) { it.copy(status = MessageStatus.STOPPED) }
            throw e
        } catch (e: Exception) {
            updateMessage(idx) {
                it.copy(status = MessageStatus.FAILED, errorMessage = e.message ?: e.javaClass.simpleName)
            }
        } finally {
            _state.value = _state.value.copy(isStreaming = false)
        }
    }

    /** Convert the UI message list into the provider-neutral [ChatMessage] shape.
     *  All assistant turns are sent as role="assistant" regardless of which model
     *  produced them — providers don't need to know multiple models contributed. */
    private fun buildChatHistory(): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        // No system prompt in v1 — keep behavior identical to the provider's default.
        for (m in _state.value.messages) {
            // The freshly-added STREAMING placeholder has empty text; skip it.
            if (m.role == MessageRole.ASSISTANT && m.status == MessageStatus.STREAMING && m.text.isEmpty()) continue
            // Failed or stopped messages contributed nothing useful — skip from history.
            if (m.status == MessageStatus.FAILED || m.status == MessageStatus.STOPPED) continue
            val role = if (m.role == MessageRole.USER) "user" else "assistant"
            out.add(ChatMessage(role, m.text))
        }
        return out
    }

    private fun updateMessage(index: Int, mutator: (Message) -> Message) {
        val cur = _state.value
        if (index !in cur.messages.indices) return
        val list = cur.messages.toMutableList()
        list[index] = mutator(list[index])
        _state.value = cur.copy(messages = list)
    }
}
