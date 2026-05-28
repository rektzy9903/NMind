# Quick Ask — Native Multi-Provider Chat

A plain chat screen — one model, streaming back, full history per turn. The twist: you can swap the model mid-conversation across any provider you've configured. No terminal, no claude-code, no Node bridge.

If you just want to use it, skip to [Quick start](#quick-start). If you're extending or building on top of it, read top to bottom.

---

## 1. What it is

Think ChatGPT.com or claude.ai — but provider-agnostic, with zero plumbing. Calls every provider directly via HTTPS using the `ProviderClient.kt` originally built for Discussion.

It's deliberately small:
- **One chat session.** No tabs (that's the terminal's job).
- **No tools.** No code execution, no file reads, no web search, no MCP.
- **No persistent history.** Closing the activity loses the chat. Only the last-used model is remembered.
- **No system prompt customization.** Models use whatever default behavior the provider ships with.

The strategic point is that **claude-code becomes one of three first-class entry points** instead of the whole app:
- **Chat Box** (claude-code) for tools and real agentic work
- **Discussion** for multi-model debate
- **Quick Ask** for normal one-on-one chat

---

## 2. Quick start

1. Make sure at least one provider has an API key configured (Home → Setting → Provider, or via the first-launch login flow).
2. Home → tap the **Quick Ask** tile.
3. Tap the model pill at the top → pick any model from any provider with a key.
4. Type a message → **Send**.
5. While streaming, **Stop** cancels cleanly. Mid-stream the partial response stays in the bubble as STOPPED.
6. Tap the pill again to switch model. The next reply uses the new model but sees the full prior chat.
7. **New** in the header wipes the chat. Provider/model selection is kept.

---

## 3. How a turn happens

```
1. User types text → tap Send.
2. ViewModel.send(text):
     - Appends a USER message (status=DONE) to state.messages.
     - Appends a placeholder ASSISTANT message (status=STREAMING).
     - Sets isStreaming=true, launches streamJob.
3. buildChatHistory():
     - Iterates state.messages, converts each to ChatMessage(role, content).
     - Role mapping: user → "user", assistant → "assistant".
     - The freshly-added empty STREAMING placeholder is skipped.
     - FAILED and STOPPED messages are also skipped — they didn't contribute
       useful content.
4. ProviderClient.streamChat(activeSpeaker, chatHistory) → Flow<ChatChunk>.
     - Branches by provider.id: anthropic_api → /messages; others →
       /chat/completions. Same code path Discussion uses.
5. Each Delta appends to sb and updates the placeholder text.
6. Done(promptTokens, completionTokens) flips status=DONE with token
   counts; isStreaming → false in the finally block.
```

The whole history is sent every turn — that's how the model maintains context. There's no `--continue` equivalent; each provider call is stateless and includes everything prior.

---

## 4. Model switching mid-chat

`setSpeaker(newSpeaker)` just swaps the `activeSpeaker` field on the state. It does NOT clear the history. So:

```
Turn 1: User → Sonnet 4.6 — "Explain monads"
Turn 1: Sonnet → User: "<3 paragraphs on monads>"
[user taps the pill, picks Llama-3.3-70B]
Turn 2: User → Llama: "Is the explanation above accurate?"
```

The Llama call gets the FULL prior history (user prompt + Sonnet's response + new user prompt) and answers in context. The Llama bubble's speaker label shows "Llama 3.3 70B" so you can see who said what.

The provider doesn't know multiple models were involved — all prior assistant messages are sent under role="assistant" without attribution. This is intentional: providers don't have a way to represent multi-author conversations, and trying to encode it (e.g. with "Speaker: " prefixes) confuses some models.

---

## 5. Error handling

`ProviderClient` translates HTTP outcomes into typed `ChatChunk` terminals:

| HTTP | Chunk | Bubble result |
|---|---|---|
| 200 + SSE | `Delta` × N then `Done(p, c)` | Status → DONE, tokens shown |
| 429 | `RateLimited` | Status → FAILED, "rate limited — try again or pick a different model" |
| 402 | `OutOfCredits(msg)` | Status → FAILED, message shown |
| 4xx / 5xx | `FailedRequest(msg)` | Status → FAILED, trimmed error |
| Cancellation | (caught) | Status → STOPPED |

A FAILED or STOPPED bubble's text is preserved in the UI but its content is **excluded from the next turn's history** (see `buildChatHistory()` in `QuickAskViewModel.kt`). This avoids feeding the model a half-finished response from a prior failed attempt.

---

## 6. Files

```
app/src/main/java/com/claudecodesetup/
├── quickask/                            ← pure logic, no UI
│   ├── QuickAskModels.kt
│   └── QuickAskViewModel.kt
└── ui/
    ├── QuickAskActivity.kt
    ├── QuickAskScreen.kt
    ├── QuickAskModelPicker.kt
    └── QuickAskPersistence.kt
```

Plus four small edits:
- `HomeScreen.kt` — `onQuickAsk` callback + Quick Ask menu card + `QuickAskIcon` (lightning bolt in a chat bubble).
- `HomeActivity.kt` — wires the callback to `QuickAskActivity`.
- `AppPreferences.kt` — `getQuickAskLastSpeaker / saveQuickAskLastSpeaker` slot.
- `AndroidManifest.xml` — `<activity android:name=".ui.QuickAskActivity">`.

**Total:** ~590 LOC across 4 new files + 4 edits — basically what the plan estimated (the estimate held because `ProviderClient` was already in place from Discussion).

---

## 7. Persistence

Only the **last-used speaker** is persisted: a single string `"<providerId>:<modelId>"` in `AppPreferences`. On next launch, `QuickAskPersistence.loadSpeaker(prefs)` re-resolves it (re-reads the API key + base URL from `AppPreferences`) so:

- Rotating an API key → next launch silently drops the saved speaker if the new key isn't configured.
- Deleting a provider → same.

The **conversation itself** is NOT persisted. Closing the activity wipes the chat. This is deliberate for v1:
- Avoids storing potentially sensitive transcripts.
- Sidesteps history-list UX (which would need a separate screen).
- Easy to add later if usage data justifies it — see the "extending it" section below.

The `viewModels()` delegate means rotation and short backgrounding keep the chat alive; only `finish()` or process death drops it.

---

## 8. Provider support

Same matrix as Discussion: every provider in `Providers.ALL` that has an API key configured AND speaks either OpenAI `/chat/completions` or Anthropic `/messages`.

- ✅ Groq, Gemini (OAI-compatible endpoint), OpenRouter, Anthropic API, DeepSeek, Kimi, NVIDIA NIM, Meta Llama, Ollama
- ❌ Anthropic subscription (OAuth) — would need to route through claude-code's proxy; skipped for v1
- ❌ Local Llama (on-device GGUF) — not yet wired through `ProviderClient`

If the model picker is empty when you open Quick Ask, you have no providers configured with an API key. The picker shows a hint pointing back to the login flow.

---

## 9. What's deliberately out of scope for v1

- Tool use, file I/O, code execution, MCP.
- Persistent transcripts / history list.
- Multi-tab / multi-session.
- System prompt or persona customization.
- Image attachments (some providers support them via `ProviderClient`, but the UI doesn't expose it yet).
- Markdown rendering inside bubbles (text is shown plain — same as the terminal sys-bubble).
- Regenerate-last-response button.
- Export / share conversation.

Most of these are easy to bolt on later. Keeping v1 small forces something testable to ship.

---

## 10. Costs

Each turn sends the FULL prior conversation as input. Token cost grows linearly with conversation length (not quadratically like Discussion, because there's only one speaker per turn instead of all-prior-speakers).

The model pill shows running totals (`↑prompt ↓completion`). On 429 the bubble is marked FAILED but the conversation stays usable — switch model or retry. On 402 the bubble shows the credit-limit message; switch to a free model and continue.

---

## 11. Extending it

**Add markdown rendering:** the terminal's `index.html renderMarkdown` is web-only. For Compose, use an existing Compose-Markdown library or write a small parser. ~150 LOC. Touch `QuickAskBubble.kt` only.

**Add image attachments:** `ProviderClient` would need a new `ChatMessage` variant carrying `(text, imageBase64, mime)`. OAI providers accept multimodal under `content: [{type:"text"|"image_url", …}]`. Anthropic uses `content: [{type:"text"}, {type:"image", source}]`. Plumb a small attachment picker into the input row. ~250 LOC.

**Persist transcripts (history list):** mirror Discussion's `Persistence` pattern but include the full message list. Add a `QuickAskHistoryScreen` listing recent chats. ~300 LOC.

**Regenerate response:** `QuickAskViewModel.regenerate()` drops the last assistant message, re-calls `streamChat` with the truncated history. ~30 LOC.

**Custom system prompt per chat:** add a system message slot in `QuickAskState`, surface in setup or as a small icon next to the pill. `buildChatHistory` already supports a system message — just prepend it. ~80 LOC.

---

## 12. Why this exists (the strategic part)

Before Quick Ask, the app was framed as "an Android Claude Code terminal." Useful niche, narrow audience. Anyone who didn't want a terminal had nothing to do.

After Quick Ask, the framing is "a multi-provider AI app where one of the features is a terminal." Same code, different positioning. The terminal stays for users who want tools and agentic work; everyone else gets a clean chat screen that competes with the standalone provider apps without the per-provider account juggling.

The validation: `ProviderClient.kt` works equally well powering 2–4-speaker debates (Discussion) and one-speaker conversations (Quick Ask). The layer-2 pattern (Kotlin → ProviderClient → provider, bypassing bridge.js) is now exercised by two distinct features. Future non-terminal features (e.g. an offline note-summarizer, a code-explain widget, a paste-and-translate utility) can be built directly on top of this layer without touching the bridge — small, fast, easy to test.

Once another feature ships on top of `ProviderClient`, F1 (move `runAgentic()` + HTTP MCP orchestration from bridge.js to Kotlin) becomes the natural cleanup — bridge.js shrinks to "claude-code launcher + proxy + local TCP" with no duplicate agentic engine.
