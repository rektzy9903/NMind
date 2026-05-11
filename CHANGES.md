# Change Log

## 2026-05-11 — Option B: Conversation history injection

### Rollback commit (state before this change)
```
git checkout 2332466 -- app/src/main/assets/nodejs-project/bridge.js
```

### What changed

**File:** `app/src/main/assets/nodejs-project/bridge.js`

Per-session conversation history so `--print` mode remembers prior turns.

| Added | Purpose |
|---|---|
| `stripAnsi(str)` | Cleans ANSI codes from captured stdout before storing in history |
| `buildMessageWithHistory(message, history)` | Prepends prior turns to each new message |
| `MAX_HISTORY = 20` | Max stored messages (10 turns) per session |
| `history[]` per socket | Accumulates `{role, content}` pairs for the session |
| `responseBuf` per message | Buffers stdout to extract the assistant reply |
| `!clear` terminal command | Resets conversation history for the current session |
| `!history` terminal command | Shows how many messages are stored |
| `runMessage(message, socket, history)` | Added `history` parameter |

### How it works

1. User sends a message → `buildMessageWithHistory()` prepends prior turns as:
   ```
   Human: <turn 1>
   
   Assistant: <turn 2>
   
   Human: <current message>
   ```
2. This combined text is passed as `process.argv[3]` to `claude --print`
3. After a successful response, the user message and assistant reply are saved to `history[]`
4. History is capped at `MAX_HISTORY` (20 msgs / 10 turns) to limit token growth
5. History resets when the socket disconnects (session ends) or user types `!clear`

### Known trade-offs

- History is formatted text, not true Anthropic multi-turn messages — the model
  sees it as one large user message with a conversation transcript embedded
- Token usage grows with history length (but stays bounded by MAX_HISTORY)
- Context is lost when the session tab is closed
- Very long replies may use a lot of tokens in subsequent turns

### How to fully revert to plain --print (no history)

```bash
git checkout 2332466 -- app/src/main/assets/nodejs-project/bridge.js
```

Or manually:
1. Remove `stripAnsi()`, `buildMessageWithHistory()`, `MAX_HISTORY`
2. Remove `history = []`, `responseBuf = ''` from socket state
3. Remove history-save block in close handler
4. Remove `!clear` / `!history` commands
5. Change `runMessage(line, socket, history)` → `runMessage(line, socket)`
6. Change `runMessage(message, socket, history)` signature → `(message, socket)`
7. Remove `const fullMessage = buildMessageWithHistory(...)` line
8. Change `process.argv[3]=' + JSON.stringify(fullMessage)` → `JSON.stringify(message)`

---

## 2026-05-11 — Intl shim, regexpShim scope fix, rate-limit notification

**Commit:** `2332466`

| Fix | Root cause |
|---|---|
| `Intl is not defined` | nodejs-mobile v18.20.4 has no ICU; cli.js crashes at line 557 on every `--print` spawn |
| `!test-cli` crashes app | `regexpShim` was `const` inside `runMessage()` but used in `!test-cli` step 5 callback (different scope) → `ReferenceError` → Node.js crash → Android restarts |
| Rate-limit notification | No distinction between rate-limit silence and app bug silence |

### Rollback
```bash
git checkout HEAD~1 -- app/src/main/assets/nodejs-project/bridge.js CLAUDE.md
```
