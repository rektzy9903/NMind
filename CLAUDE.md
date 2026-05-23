# CLAUDE.md — ClaudeCodeSetup

## GitHub CLI

Always use `GH_TOKEN=<your-github-pat> gh ...` for all GitHub CLI commands.
Repo: `fahmi304/Nexus-Mind`

**Security note:** Never hardcode the PAT in this file. Set it in your shell environment or pass it inline per command. The previous token committed here has been revoked — generate a new one at https://github.com/settings/tokens with `repo` + `workflow` scopes.

Examples:
```bash
GH_TOKEN=$GITHUB_PAT gh workflow run build.yml --repo fahmi304/Nexus-Mind
GH_TOKEN=$GITHUB_PAT gh run list --repo fahmi304/Nexus-Mind --limit 5
GH_TOKEN=$GITHUB_PAT gh run watch <run-id> --repo fahmi304/Nexus-Mind
```

## What this project does

An Android app that runs **Claude Code** (`@anthropic-ai/claude-code`) on an Android phone with zero manual setup. The user installs the APK, picks a provider or logs in with their Claude subscription, and gets a working Claude Code terminal session.

The app ships an embedded Node.js runtime (`libnode.so` via JNI) and a JavaScript bridge (`bridge.js`) that:
1. Downloads and installs `claude-code@2.1.112` from the npm registry on first launch.
2. Starts a local TCP server on port 8083. Each connection spawns a `claude --print` process per message (print mode).
3. For non-subscription providers, starts an Anthropic→OpenAI protocol proxy on port 8082. OpenAI-compatible providers (Gemini, OpenRouter, DeepSeek, etc.) go through full format conversion. The `ANTHROPIC_API` provider (`api.anthropic.com`) uses **passthrough mode** — no format conversion, request forwarded as-is with real `x-api-key`.

---

## Tech stack

| Layer | What |
|---|---|
| Language | Kotlin (Android), C++17 (JNI glue), JavaScript (bridge) |
| Build system | Gradle (Kotlin DSL via Groovy `build.gradle`) |
| Min SDK | 29 (Android 10) |
| Target/Compile SDK | 34 |
| NDK | 25.1.8937393 |
| Embedded runtime | `libnode.so` from **nodejs-mobile v18.20.4** (arm64-v8a + armeabi-v7a) |
| Claude Code version | **2.1.112** (pinned — last version that runs on Android's Bionic runtime) |
| Key Android libraries | `androidx.security:security-crypto`, `okhttp3`, `kotlinx-coroutines`, `lifecycle-service`, `work-runtime-ktx` |

**Why v2.1.112 is pinned:** v2.1.113+ uses pre-compiled native binaries requiring glibc; Bionic is incompatible. Do not bump without confirming compatibility.

---

## Key files

```
app/src/main/
├── assets/
│   ├── nodejs-project/bridge.js   — Node.js bridge: install + proxy + TCP server
│   ├── providers.json             — bundled provider list fallback
│   └── terminal/index.html        — hand-rolled ANSI terminal emulator (WebView)
├── cpp/node_bridge.cpp            — JNI: calls node::Start() from libnode.so
└── java/com/claudecodesetup/
    ├── ClaudeApp.kt               — Application class, notification channels
    ├── SplashActivity.kt          — routing only: Setup → Compose → Home
    ├── SetupActivity.kt           — first-run Node install, polls setup.log
    ├── TerminalActivity.kt        — WebView terminal + session tabs
    ├── SettingsActivity.kt        — provider/model change, reset, language
    ├── NodeEngine.kt              — Kotlin singleton, wraps JNI nativeStart()
    ├── ui/
    │   ├── ComposeActivity.kt     — 6-screen Compose login flow (start_at intent)
    │   ├── ClaudeLoginActivity.kt — OAuth 2.0 + PKCE WebView login for subscription users
    │   ├── HomeActivity.kt / HomeScreen.kt  — glassmorphic main menu
    │   ├── ModelTestActivity/Screen.kt      — test all models, pass/fail/latency
    │   ├── LoginScreens.kt        — Subscription/Malaysia/GeminiRecommend/ProviderList
    │   ├── ApiKeyScreen.kt        — key entry + OkHttp validation per provider
    │   ├── ModelPickerScreen.kt   — model grid with Free/Paid sections, live fetch for all providers
    │   └── UiCommon.kt            — AppBackground, glowShadow, font families
    ├── data/
    │   ├── AppPreferences.kt      — EncryptedSharedPreferences wrapper
    │   ├── Providers.kt           — hardcoded Provider/AiModel data
    │   └── ProvidersRepository.kt — loads from asset or remote URL; fetchModels() dispatches per provider
    ├── managers/
    │   ├── NodeBridgeManager.kt   — starts bridge.js, writes bridge_config.json
    │   ├── LlamaServerManager.kt  — manages libllamaserver.so process
    │   └── DownloadManager.kt     — resumable OkHttp downloader
    ├── services/
    │   ├── ClaudeService.kt       — foreground service, session lifecycle, TCP sockets
    │   └── FloatingOverlayService.kt — TYPE_APPLICATION_OVERLAY floating button
    └── receivers/BootReceiver.kt  — restores service on device boot
```

---

## Build & test

```bash
# Local build (requires Android SDK + NDK)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

`libnode.so` is **not committed**. CI downloads it automatically. Local builds: run `scripts/download-libnode.sh` or copy from nodejs-mobile v18.20.4 Android release zip.

`libllamaserver.so` is **not committed**. CI builds it from source. Local builds: `export ANDROID_NDK_HOME=<path> && ./scripts/build-llamaserver.sh`.

**Release:** `git tag v1.x.x && git push origin v1.x.x` — `release.yml` builds signed APK + AAB. Requires 4 secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

**E2E test:** `OPENROUTER_API_KEY=sk-or-... node .github/scripts/test-full-session.js`

---

## Architecture

### Activity flow
```
SplashActivity (routing only)
    ↓ first run          ↓ provider not set    ↓ ready
SetupActivity        ComposeActivity        HomeActivity
(Node install)       (6-screen flow)            ↓ Chat Box → TerminalActivity
                     sub→claude_auth→picker     ↓ Testing  → ModelTestActivity
                     sub→key→picker             ↓ Settings → SettingsActivity
                     →providers→key→picker        (Change model → ComposeActivity start_at=picker)
```

`SplashActivity` routes on two prefs: `isNodeSetupComplete()` + `isProviderConfigured()`.

### Session model
`ClaudeService` (foreground `LifecycleService`) owns sessions as `LinkedHashMap<Int, ClaudeSession>`. Each session = TCP socket to `127.0.0.1:8083`. Max 4 concurrent sessions.

### Session mode (print mode always)
Each user message spawns `claude --print --output-format stream-json` (print mode). No persistent proc between messages. `!pty <cmd>` is still available for interactive subprocesses. The `ptyMode` config field and toggle have been removed — they were dead code.

### Protocol proxy (Anthropic → OpenAI)
HTTP server on port 8082 converts Anthropic Messages API → OpenAI Chat Completions, forwards to provider, converts back (streaming + non-streaming). `ANTHROPIC_BASE_URL=http://127.0.0.1:8082` for all non-Anthropic providers. Protected by a UUID `localToken` written to `filesDir/local_token` and validated on every request.

### bridge_config.json
Written by `NodeBridgeManager.writeConfig()` before each `startBridge()`. Re-written by `refreshConfig()` on model/provider change in Settings. Includes `modelList` array for 429 fallback and `localToken` for proxy auth. `bridge.js` reads it fresh per message.

### Floating overlay (FloatingOverlayService)
`TYPE_APPLICATION_OVERLAY` window sized to a minimal bounding box (button only when idle, expanded to cover visible menus). `FLAG_NOT_TOUCH_MODAL` passes touches outside the window to the underlying app. `repositionViews()` recalculates the bounding box and updates `overlayParams.x/y/width/height` + child margins (window-relative) on every drag, menu show/hide.

### Claude.ai OAuth login
`ClaudeLoginActivity` handles OAuth 2.0 + PKCE for subscription users. Writes credentials to `filesDir/.claude/.credentials.json` as `claudeAiOauth` structure. State parameter verified on callback (CSRF protection). File permissions hardened to owner-only.

### Local AI (on-device)
`LlamaServerManager` runs `libllamaserver.so` at `127.0.0.1:8080`. Models downloaded as GGUF from HuggingFace to `filesDir/models/`. `PersonalAiScreen` manages download/load/use lifecycle.

---

## Things to always remember

1. **Never upgrade `claude-code` past v2.1.112** — v2.1.113+ requires glibc (pre-compiled native binaries); Android Bionic is incompatible.

2. **`libnode.so` is not in the repo** — needed at `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/`. CI downloads automatically; local builds need `scripts/download-libnode.sh`.

3. **Node.js can only be started once per process** — `NodeEngine.kt` enforces this with a `started` flag. All retry logic lives inside `bridge.js`.

4. **Bridge config is written before each `startBridge()`** — `bridge_config.json` in `filesDir`. Provider changes take effect on the next message, not immediately.

5. **Print mode always** — each user message spawns `claude --print --output-format stream-json --dangerously-skip-permissions [--continue]`. Process exits after each response. History preserved via `--continue` (claude session files in `~/.claude/projects/`). `!clear` resets `hasHistory = false` so the next spawn starts a fresh session. `!pty <cmd>` still available for interactive subprocesses.

6. **Signing** — Debug APKs: `com.claudecodesetup.debug`. Release signing reads from `local.properties` (never committed). CI uses GitHub Secrets.

7. **`MODE_GEMINI` is unused at runtime** — only `MODE_SUBSCRIPTION` vs `MODE_PROXY` matters in `ClaudeService`/`NodeBridgeManager`. Gemini is just another proxy-mode provider.

8. **Provider list order in `Providers.ALL`**: `GROQ, GEMINI, OPENROUTER, DEEPSEEK, KIMI, NVIDIA_NIM, META_LLAMA, OLLAMA` — Groq is first; Gemini is the recommended default shown second.

9. **`libnode-launcher.so` only works with `-e`** — loading a `.js` file by path silently exits code 1. Only `spawn(LAUNCHER, ['-e', evalCode])` works. `bridge.js` bootstraps cli.js via `import('file://...')` inside the eval string.

10. **`cli.js` must be patched after install** — `patchCliJsForAndroid()` replaces all 23 Unicode property escape regex literals (`/\p{L}/u` etc.) with explicit code-point ranges. Requires full data clear to re-run on an existing install (cache clear is not enough).

11. **`tryOptimize()` must only match short system prompts** — Always keep `if (sys.length > 800) return null;` at the top. Claude Code's real system prompt is ~25 KB and contains words like "title"/"concise" that would otherwise match the housekeeping patterns.

12. **Two layers of `\p{}` patching are needed** — Static literals patched by `patchCliJsForAndroid()` at install time. Dynamic `new RegExp(p, 'u')` calls at runtime caught by a global `RegExp` shim in the eval bootstrap. Both are required. When `[regex-compat]` lines appear in `!log`, add those patterns to `patchCliJsForAndroid()`.

13. **Conversation history managed via `--continue`** — Print mode uses `--continue` flag on subsequent messages to preserve history in claude's own session files (`~/.claude/projects/`). `!clear` sets `hasHistory = false` + clears session state file so next spawn starts fresh. `sessionTokens` is tracked and reset on `!clear`.

14. **`Intl` is missing from nodejs-mobile v18.20.4** — No ICU. `intlShim` (module-scope in `bridge.js`) is injected before `import(cli.js)`. Extend the stub if a specific `Intl` method crashes.

15. **`regexpShim` and `intlShim` must be module-scope** — Both are injected into every eval bootstrap and the `!test-cli` handler. Local scope causes `ReferenceError` crashing the Node.js process.

16. **Never use `painterResource(R.mipmap.ic_launcher)` in Compose** — Adaptive icons throw `IllegalArgumentException` on API 26+. Always render via `ContextCompat.getDrawable()` + Android `Canvas` onto a `Bitmap`, then `bitmap.asImageBitmap()`.

17. **OSC thinking sequences are ephemeral — never buffer them** — `ClaudeSession.appendOutput()` strips `thinking-start`/`thinking-done` before storing. Real-time `onOutput` still receives originals. Strip any new OSC state sequences the same way.

18. **`thinking-done` fires TWICE per PTY turn** — First fires on first PTY output (THINKING → RESPONDING, creates AI bubble). Second fires from the 800 ms output-idle timer in `wireProcEvents()` (RESPONDING → IDLE, finalizes bubble). Both are required; missing the second leaves the bubble open and accumulates the next turn's TUI screen dump into the same bubble.

19. **`bridge_config.json` includes `modelList`** — Written by `NodeBridgeManager.writeConfig()` from the provider's static model list. Used for 429 fallback in `handleProxyRequest`. OpenRouter live models are not included.

20. **`sendToProvider` has a 7th `on429` callback** — Signature: `sendToProvider(baseUrl, apiKey, oaiReq, stream, res, onBadRequest, on429)`. `lastRateLimitMs` is set by the `on429` handler in `handleProxyRequest`, not inside `sendToProvider`.

21. **`!agentic` state persisted to `filesDir/agentic_state`** — File present = on; absent = off. Initialized at module load. `AGENTIC_FILE` constant at top of `bridge.js`.

22. **PTY response extraction uses the `✦` turn marker** — `rawAiText` accumulates the full PTY/TUI stream (ANSI escape sequences + TUI chrome). `finalizeAiBubble()` calls `extractResponseFromPty()` when `✦` is present, which strips all ANSI then slices from the last `✦` to the `❯` input prompt. Only the extracted text is passed to `renderMarkdown()` / displayed. Without this, the full TUI screen dump (banner, chrome, prompt box) would appear in the chat bubble.

23. **Background response notification: `CHANNEL_RESPONSE`, `RESPONSE_NOTIF_ID = 1002`** — Debounced 1.5 s in `ClaudeService`. Call `cancelResponseNotification()` from `TerminalActivity` whenever activity becomes visible.

24. **Floating overlay window must be resized on every state change** — `repositionViews()` computes the bounding box of button + visible menus and updates `overlayParams.x/y/width/height` then calls `windowManager.updateViewLayout()`. Child margins are window-relative. Must be called after: button drag, sub-menu show/hide (with GONE set before the call), quick-panel show/hide. `WindowManager.LayoutParams.touchableRegion` is NOT in the public SDK — do not use it.

25. **Proxy login bypass for proxy mode** — `ANTHROPIC_API_KEY=sk-ant-proxy000` (must start with `sk-ant-` or claude-code rejects it at format-check time). Do **not** set `CLAUDE_CODE_OAUTH_TOKEN` alongside it — setting both triggers an auth-conflict check that shows a warning, the welcome banner, and an interactive login flow (never emits the first JSON event → banner suppression 4 s timeout fires, errors flood the terminal). The `customApiKeyResponses.approved` list in `settings.json` (patched at bridge startup) is what makes claude-code accept `sk-ant-proxy000` silently without showing the login selector. The proxy ignores whatever Bearer token claude-code sends and always uses `cfg.apiKey` for the real provider.

26. **Input path in PTY mode** — Keyboard/IME Enter → `sendRawInput()` → raw PTY (no bubble). Send button → `submitLine()` → chat path (adds bubble + spinner). Toolbar Yes/No → `tbRaw('y\r'/'n\r')` — raw. Never call `submitLine()` for TUI interactions; it finalizes the ANSI renderer mid-TUI causing garbled output.

27. **No startup banner in print mode** — `--print` mode never shows the interactive TUI banner. The `Cm6()` no-op and `"Welcome back!"` patches in `patchCliJsForAndroid()` are still applied (they are harmless and protect against regressions). `settings.json` is still patched before every spawn for the `customApiKeyResponses.approved` list, `theme: dark`, and onboarding flags.

28. **Intermediate TUI renders suppressed during streaming** — `termWrite()` skips `scheduleAiRender()` when the incoming PTY chunk contains full-screen ANSI sequences (`\x1b[H`, `\x1b[2J`, absolute cursor positioning). This prevents garbled TUI chrome from flashing in the AI bubble mid-stream. Only non-TUI chunks (plain text deltas) trigger intermediate renders.

29. **OpenRouter API key validation endpoint** — Use `/api/v1/models` (returns 200 for any valid key). NOT `/api/v1/auth/key` (that endpoint requires a paid-tier key and returns 401 for free-tier keys even when valid).

30. **`9;confirm:` dialog wiring** — `waitForConfirm(socket, id, description)` stores a Promise in `pendingConfirms.get(id)`. Terminal sends `Android.sendConfirm(id, choice)` → Kotlin `sendConfirm` → bridge receives `!confirm:<id>:<choice>
`. Handler in `handleInput` looks up `pendingConfirms.get(confirmId)` and resolves it. Do NOT change this to plain "yes
" — the ID is required.

31. **Permission prompt wiring (Always/Allow/Deny)** — Print-mode permission dialog sends `Android.sendInput('!perm-always
')`, `!perm-allow
`, `!perm-deny
`. These bypass the busy guard in `handleInput` and write `y
`/`n
` to `proc.stdin`. `!perm-always` also saves the tool name to `CONFIRM_FILE` (`auto_approve.json`) as `{allow:[...], deny:[]}` — injected into `settings.json` before the next spawn via `patchSettings()`.

32. **`CONFIRM_FILE` (auto_approve.json) has one unified format** — Always `{allow:[], deny:[]}`. Both the agentic `saveAutoApprove()` and the print-mode `saveApproveList()` write this format. `loadAutoApprove()` handles legacy plain-array format for backwards compat. Never write a plain array to this file.

33. **Image attach pipeline** — `TerminalActivity.onActivityResult` writes `pending_image.b64` + `pending_image.mime` to `filesDir`. Before calling `runAgentic()`, `handleInput` reads and deletes these files, passing them as `pendingImg` object. `pickImage()` in Kotlin gates on model ID keywords (vision, vl, scout, maverick, gemini, claude, gpt-4, llava, llama-4) before opening the gallery — shows a toast and returns early for non-vision models.

34. **Model picker Free/Paid sections** — `ModelPickerScreen` splits the paginated model list by `Cap.FREE in effectiveCaps`. Shows `🆓 FREE` section (green) then `💳 PAID` section (amber) with `SectionHeader` + `ModelSubGrid` composables. Both sections only appear when non-empty. OpenRouter live fetch now returns ALL models (not just free); free ones get `Cap.FREE` added. Groq live-fetched models always get `Cap.FREE` via `isAlwaysFree = true`.

35. **`Providers.ANTHROPIC.supportsLiveFetch = false`** — Subscription users authenticate via OAuth (no API key). Live fetch would call `fetchAnthropicModels("")` → 401. Static model list (Sonnet 4.5, Opus 4.5, Haiku 4.5) is sufficient. ANTHROPIC is not in `Providers.ALL` — proxy users never see it. Only reached via `Providers.byId("anthropic")` in the subscription flow.

36. **`SYS_FENCE` routes bridge output to sys bubble regardless of `chatState`** — `bridge.js` prefixes all diagnostic/shell output with `const SYS_FENCE = '\x1b]9;sys-fence\x07'`. `termWrite()` in `index.html` checks `raw.startsWith(SYS_FENCE_PFX)` first; if matched, content is always routed to the sys bubble and never to the AI bubble. Do NOT add this prefix to OSC protocol messages (`thinking-start`, `thinking-done`, `tokens:`, etc.) — those must reach `handleOSC()` unmodified.

37. **`handleInput` busy-gate** — The busy gate **only blocks plain-text AI messages** while a response is in flight. All `!` commands and `$ cmd` always run immediately regardless of busy state. Special pre-gate handlers (run first before any gate check): `!confirm:`, `!perm-*`, `!clear` (kills currentProc + resets state). Permission auto-approves immediately (writes `y\n` to stdin before 3s timeout); dialog is informational with Always/Block/Dismiss.
    - **`[busy — please wait]`** is also SYS_FENCE'd so it always shows in a sys bubble.

38. **`!cmd` and `$ cmd` show a green cmd bubble in `submitLine()`** — `submitLine()` calls `addCmdBubble(msg)` (green `$` avatar, green-tinted bubble) before `Android.sendInput(msg+'\r')`. Also resets `sysEl=null; sysAnsi=makeAnsiState()` so bridge output appears in a fresh sys bubble below the cmd bubble. This is the v1 "own bubble" behavior. Do NOT route these through `Android.submitMessage()` — that triggers the thinking spinner and sets `sessionBusy`.

39. **Kimi API key validation uses `/v1/users/me`** — NOT `/v1/models`. The `/v1/models` endpoint on `api.moonshot.ai` is public and returns 200 for any key including invalid ones. `/v1/users/me` is auth-gated and correctly returns 401 for bad keys.

40. **`ANTHROPIC_API` provider uses passthrough mode — no OAI conversion** — When `cfg.providerUrl.includes('api.anthropic.com')`, `handleProxyRequest()` calls `sendToAnthropicDirect()` which POSTs the original Anthropic-format body straight to `api.anthropic.com/v1/messages` with `x-api-key: <real key>` and `anthropic-version: 2023-06-01`. All Anthropic-native features (extended thinking, all parameters) are preserved. There are two separate Anthropic providers: `ANTHROPIC` (id=`anthropic`, OAuth subscription, `requiresProxy=false`) and `ANTHROPIC_API` (id=`anthropic_api`, API key, `requiresProxy=true`, in `Providers.ALL`).

41. **MCP `enabled` field in stored JSON** — `NodeBridgeManager.writeMcpConfig()` checks `server.optBoolean("enabled", true)` before including each HTTP/stdio server in `mcp_config.json`. Default is `true` if field absent (backward compatible). `SettingsActivity.onResume()` populates per-server toggle rows (`refreshMcpRows()`); toggling immediately re-writes the JSON pref and calls `writeMcpConfig()`.

42. **claude-code version pinned at 2.1.112 — model access is server-side** — v2.1.113+ packages a 221 MB Node.js SEA binary requiring `/lib/ld-musl-aarch64.so.1` which doesn't exist on Android Bionic. Neither the musl nor glibc arm64 binary can be loaded. The version gap does NOT affect which Claude models are available — Anthropic controls that server-side. To add new models for subscription users: update `Providers.ANTHROPIC.models` static list; no claude-code upgrade needed.

43. **`Providers.ALL` order** — `GROQ, GEMINI, OPENROUTER, ANTHROPIC_API, DEEPSEEK, KIMI, NVIDIA_NIM, META_LLAMA, OLLAMA`. `ANTHROPIC_API` is 4th (after OPENROUTER). `ANTHROPIC` (subscription) and `LOCAL_LLAMA` are NOT in `ALL` — accessed only via `byId()`.

---

## Known gaps

- **App size** — Both `arm64-v8a` and `armeabi-v7a` `libnode.so` are shipped. AAB bundle config added; use Play Store to serve device ABI only.
- **`ProvidersRepository.REMOTE_URL` wired but empty** — Live provider updates disabled. Enable to push new models without an app update.
- **Sub-agents** — Parallel tool-calling workstreams. Not yet implemented.
- **Per-tab working directory** — All 4 session tabs share the same `shellCwd`. Could allow different project per tab.
- **Custom slash commands** — Claude Code supports `~/.claude/commands/`. Not yet exposed in-app.
- **PTY resize control channel** — Resize commands sent in-band via `ESC 0xFE`. A Unix domain socket control channel would be more robust.
- **claude-code version permanently capped at 2.1.112** — v2.1.113+ ships a 221 MB Node.js SEA binary requiring `/lib/ld-musl-aarch64.so.1` (musl) or glibc — neither exists on Android Bionic. New CLI features added after 2.1.112 are unavailable; model access is unaffected (server-side). To unlock newer versions would require bundling the musl dynamic linker or extracting the JS payload from the SEA binary (high risk, Node.js version mismatch likely).

---

## TODO

### Security
- [x] **Port 8083 auth token** — fixed; connections without valid `SESSION:<sid>:<token>` header are now rejected before `attachSession`.
- [x] **`FLAG_SECURE` on `ApiKeyScreen`** — covered; `ApiKeyScreen` is a Compose screen hosted inside `ComposeActivity` which already sets `FLAG_SECURE`.
- [x] **Scrub logs** — verified no `Log.*` calls echo apiKey, baseUrl, or raw key values.

### Bugs
- [x] **`callProxyStreaming` hardcoded model** — fixed; now uses `cfg.modelId || 'claude-3-5-sonnet-20241022'`.
- [x] **PTY AI bubble never finalized** — fixed; 800 ms idle timer in `wireProcEvents()` now sends the second `thinking-done`.
- [x] **PTY bubble shows full TUI screen** — fixed; `extractResponseFromPty()` extracts response after `✦` marker.
- [x] **OpenRouter key validation rejected valid keys** — fixed; endpoint changed to `/api/v1/models`.
- [x] **`9;confirm:` dialog response never delivered** — fixed; `sendConfirm` now sends `!confirm:<id>:<choice>
`; bridge resolves `pendingConfirms` promise.
- [x] **`CONFIRM_FILE` format conflict** — fixed; `saveAutoApprove()` now writes `{allow:[], deny:[]}` object format matching `saveApproveList()`.
- [x] **Image attach pipeline dead** — fixed; `handleInput` reads `pending_image.b64/.mime` from disk before calling `runAgentic()`; `pickImage()` gates on vision capability.
- [x] **`Anthropic.supportsLiveFetch` broke subscription model picker** — fixed; set to `false`.
- [x] **Dead `authToken`/`ptyMode` fields in `bridge_config.json`** — removed from `NodeBridgeManager.writeConfig()` and `AppPreferences`.
- [x] **Dead `showConfirmDialog` `@JavascriptInterface`** — deleted from `TerminalBridge`.
- [x] **"Welcome back!" banner appearing in sessions** — fixed; `patchCliJsForAndroid()` replaces the string.
- [x] **`!cmd` / `$ cmd` output landing in AI bubble** — fixed (commit 6054628); `SYS_FENCE` prefix forces all bridge diagnostic output to sys bubble regardless of `chatState`. `!help` and `!log` moved above busy gate (safe, read-only); `!clear` and `!test-cli` moved below gate. `submitLine()` now shows a green cmd bubble for `!`/`$` input.
- [x] **Kimi API key validation let invalid keys through** — fixed (commit 1e7656b); changed endpoint from `/v1/models` (public) to `/v1/users/me` (auth-gated).
- [x] **`SetupActivity` thread-per-poll** — fixed; replaced with `lifecycleScope.launch(Dispatchers.IO) { while(isActive) { ...; delay(2000L) } }`.
- [x] **`ComposeActivity` back from picker when `startAt="picker"`** — fixed; `(context as? Activity)?.finish()` called when `startAt == "picker"`.
- [x] **MCP stdio `args` parsing** — fixed; `shellSplit()` helper in `NodeBridgeManager` honours single/double quotes so paths with spaces work correctly.
- [x] **`sendToProvider()` streaming missing 30s idle timer** — fixed; OpenRouter could send HTTP 200 + SSE headers then stall body indefinitely, causing 120s timeout. Added same 30s idle timer as `callProxyStreaming()` already had (M21).
- [x] **`!test-cli` reported "6 steps" but only 4 implemented** — fixed; message string corrected to "4 steps".
- [x] **MCP servers had no enable/disable toggle** — fixed; `SettingsActivity` now shows per-server Switch rows populated in `onResume()` via `refreshMcpRows()`; toggle updates `"enabled"` field in stored JSON and calls `writeMcpConfig()` immediately.
- [x] **Subscription model list outdated** — fixed; `Providers.ANTHROPIC.models` updated to include `claude-sonnet-4-6` and `claude-opus-4-7` alongside 4.5 variants.
- [x] **No Anthropic API key provider** — fixed; `ANTHROPIC_API` provider added to `Providers.ALL` with passthrough proxy mode; API key users no longer need to go through OpenRouter.
- [ ] **`ScheduledPrompt` day-of-week filtering missing** — WorkManager fires every day; data class has no `days` field.

### Screenshot / MediaProjection cleanup
Image attach via gallery picker is now functional (vision-capable models only). The MediaProjection screenshot pipeline has been removed.
- [x] `FloatingOverlayService` — dead screenshot capture + `MediaProjectionActivity` launch code removed.
- [x] `MediaProjectionActivity.kt` — deleted entirely.
- [x] `AndroidManifest.xml` — `FOREGROUND_SERVICE_MEDIA_PROJECTION` + `MediaProjectionActivity` removed.
- [x] `bridge.js` `runAgentic()` — no change needed; pendingImage already handled.

### Remote Ollama
- [ ] Handle HTTPS for remote connections (Oracle Cloud IP or custom domain)
- [ ] Allow changing Ollama server URL in Settings without full provider reset

---

## Full App Audit (2026-05-21)

5-agent parallel audit. Findings below are deduplicated and prioritized. Items marked ✅ were verified correct and need no action.

### 🔴 CRITICAL — Fix Before Any Release

- [x] **C1 · `CLAUDE.md:5`** — Hardcoded live GitHub PAT removed; token revocation required on GitHub manually.
- [x] **C2 · `DeviceControlHttpServer.kt`** — `x-local-token` header validation added with constant-time compare. `ClaudeService` passes context on `start()`.
- [x] **C3 · `bridge.js`** — Port 8083 TCP auth: non-`SESSION:` connections rejected immediately; empty token rejects all.
- [x] **C4 · `bridge.js:1654`** — Port 8082 proxy: `x-local-token` or `x-api-key: sk-ant-proxy000` required; all other callers get 401.
- [x] **C5 · `PreviewActivity.kt`** — `allowUniversalAccessFromFileURLs = false` and `allowFileAccessFromFileURLs = false` set.
- [x] **C6 · `SplashActivity.kt`** — `sanitizeSharedText()` strips control chars, caps at 4000 chars, trims.
- [x] **C7 · `TerminalActivity.kt`** — `saveFile()` shows `AlertDialog` for paths outside `filesDir`/`getExternalFilesDir`.
- [x] **C8 · `ComposeActivity.kt`** — `LocalModelsScreen` implemented in `LoginScreens.kt` with URL input + test connection + OkHttp validation.

### 🟠 HIGH — Fix Before Public Distribution

- [x] **H1 · `AppPreferences.kt`** — fallback to plaintext logs CRITICAL via `Log.e` and sets `_isEncrypted = false`; caller can check `isEncrypted`.
- [x] **H2 · `ApiKeyScreen.kt`** — `ComposeActivity` (host activity) sets `FLAG_SECURE`; covers all Compose screens including `ApiKeyScreen`.
- [x] **H3 · `bridge.js`** — `fs.chmodSync(CONFIRM_FILE, 0o600)` added after each write to `auto_approve.json`.
- [~] **H4 · `index.html` (renderMarkdown)** — XSS risk noted. **Decision: skip** — WebView is sandboxed to app; AI output comes from our own API calls. Risk accepted.
- [~] **H5 · `index.html:1561`** — `innerHTML` from ANSI renderer. **Decision: skip** — same rationale as H4; OSC sequences are controlled by bridge.js which we own.
- [x] **H6 · `SetupActivity.kt`** — replaced `Thread {}` poll with `lifecycleScope.launch(Dispatchers.IO) { while(isActive) { …; delay(2000L) } }`.
- [x] **H7 · `NodeBridgeManager.kt`** — `bridge_config.json`, `mcp_config.json`, `mcp_http.json` all written via temp+rename atomically.
- [x] **H8 · `TerminalActivity.kt`** — all service callbacks nulled unconditionally in `onDestroy()` before `unbindService`.
- [x] **H9 · `TerminalActivity.kt`** — `runAndFeedback()` uses `lifecycleScope.launch(Dispatchers.IO)` with `!isDestroyed` guard.
- [x] **H10 · `ModelTestScreen.kt`** — `awaitAll()` wrapped in `try/finally { orTesting = false; nvTesting = false; isTesting = false }`.
- [x] **H11 · `res/xml/network_security_config.xml`** — created; cleartext HTTP only to `127.0.0.1`/`localhost`/`10.0.2.2`; all other domains HTTPS.
- [~] **H12 · `AndroidManifest.xml`** — `MANAGE_EXTERNAL_STORAGE`. **Decision: keep** — terminal needs full filesystem access for `cd`, `ls`, file edits anywhere on device.

### 🟡 MEDIUM — Fix in Next Sprint

- [x] **M1 · `ComposeActivity.kt`** — back from `picker` calls `(context as? Activity)?.finish()` when `startAt == "picker"`.
- [x] **M2 · `index.html`** — `termRestoreSnapshot()` no longer resets `sysAnsi = makeAnsiState()`.
- [x] **M3 · `index.html`** — `extractResponseFromPty()` has 50 k char sanity guard on raw input.
- [x] **M4 · `bridge.js`** — `stripAnsi()` regex extended to cover OSC (`\x1b]…\x07`), DCS/PM/APC, C1 `\x9b` sequences.
- [x] **M5 · `bridge.js`** — Non-JSON stdout lines prefixed with `SYS_FENCE`; never land in AI bubble.
- [x] **M6 · `bridge.js`** — `attachSession()` kills `currentProc`, resets `busy`/`thinkingDone` on reconnect.
- [x] **M7 · `bridge.js`** — `pendingImage` files deleted in `.finally()` after `runAgentic()` completes.
- [x] **M8 · `bridge.js:1507`** — 429 retry `delayMs` not reset when switching to next model in fallback list. Reset to 2 on model switch.
- [x] **M9 · `index.html`** — Font size loaded from `localStorage` without bounds validation. Clamp: `Math.max(10, Math.min(22, parseInt(...) || 14))`.
- [x] **M10 · `index.html:1936`** — Ghost textarea strips all `\n` including pasted multi-line content. Only intercept Enter key, not clipboard paste.
- [x] **M11 · `LlamaServerManager.kt:71`** — `waitUntilReady()` uses `Thread.sleep(500)` (blocking). Convert to `suspend fun` with `delay(500)`.
- [x] **M12 · `TerminalActivity.kt:251-252`** — `writeToTerminal()` has unreachable Unicode branch (copy-paste error; both arms match same char). One should be `' '` (NBSP).
- [x] **M13 · `bridge.js:3003`** — `stderrBuf.slice(-8)` discards first error line (often the real crash). Show first error-keyword line + last 3 lines.
- [x] **M14 · `FloatingOverlayService.kt:604`** — Socket reconnect `repeat(30)` loop can outlive `scope.cancel()`. Add `shouldConnect = false` guard before cancel.
- [x] **M15 · `McpScreen.kt`** — `McpServerCard` ping coroutines not cancelled on composable disposal. Add `DisposableEffect` cleanup.
- [x] **M16 · `index.html:1899`** — `setTimeout(scrollToBottom, 50)` in ghost focus not cleared. Store ID and `clearTimeout` before setting new one.
- [x] **M17 · `index.html:971`** — `thinkingTimerHandle` interval not consistently cleared before new one set. Potential timer accumulation.
- [ ] **M18 · `ScheduledPromptsManager.kt:93`** — Day-of-week filtering not implemented: `days` field missing from data class; WorkManager fires every day regardless.
- [x] **M19 · `DownloadManager.kt:72`** — Partial download file not deleted on failure. Delete in catch block.
- [x] **M20 · `BootReceiver.kt:18`** — No bridge reachability check before auto-starting service post-boot.
- [x] **M21 · `bridge.js:694`** — No idle timeout on `callProxyStreaming`. A stalled response stream (headers received, body stalled) never triggers the 120 s request timeout. Add 30 s idle timer.
- [x] **M22 · `bridge.js:1074`** — `patchCliJsForAndroid()` string-split patches silent on failure. Log all failed patches loudly with `console.error`.
- [x] **M23 · `AndroidManifest.xml`** — `RECORD_AUDIO` declared; no visible runtime permission request. Verify request exists before use.
- [x] **M24 · `TerminalActivity.kt:206`** — `allowFileAccess = true` in terminal WebView with no path restriction. JS can read files outside project directory.
- [x] **M25 · `bridge.js:628`** — `local_token` file read with no size limit. Truncate to max ~200 chars after read.
- [x] **M26 · `McpScreen.kt:74`** — MCP pings run sequentially (5 servers × 5 s = 25 s). Use `launch {}` per server for parallel pinging.
- [x] **M27 · `bridge.js`** — `spawn('/system/bin/sh', ['-c', cmd])` for shell commands. Audit all call sites to ensure `cmd` is never constructed from raw user input.

### 🔵 LOW / IMPROVEMENT — Backlog

- [~] **L1** — Certificate pinning. **Decision: skip** — would break custom API endpoints, corporate proxies, and user-configured mirrors. Risk accepted.
- [x] **L2 · `AndroidManifest.xml`** — `android:requestLegacyExternalStorage="true"` deprecated on target SDK 34. Remove.
- [x] **L3 · `ClaudeApp.kt:23`** — `introPlayed` global boolean read + written non-atomically. Use `AtomicBoolean`.
- [x] **L4 · `FloatingOverlayService.kt:495`** — Dead MediaProjection screenshot pipeline (`requestScreenshot`, `MediaProjectionActivity` reference). Delete per existing TODO.
- [x] **L5 · `HomeActivity.kt:28`** — `ProjectManagerActivity` referenced in nav but does not exist. Remove button or implement screen.
- [x] **L6 · `index.html`** — `bub._rawText` stored directly on DOM node. Use `WeakMap` keyed by element instead.
- [~] **L7 · `app/build.gradle:132`** — `security-crypto:1.1.0-alpha06` is pre-release. Downgrade to stable `1.0.0`.
- [x] **L8 · `ClaudeService.kt:39`** — Silent failure when max 4 sessions reached. Emit error to `onOutput` so user sees feedback.
- [x] **L9 · `ClaudeService.kt:412`** — Foreground notification `setOngoing(true)` non-dismissible. Only keep `ongoing` during bridge startup.
- [x] **L10 · `ClaudeService.kt:387`** — WakeLock hardcoded to 4 h. Renew on activity or use indefinite + manual release.
- [x] **L11 · `NodeBridgeManager.kt:86`** — MCP config writes (`mcp_config.json` + `mcp_http.json`) not atomic. Use temp+rename for both.
- [x] **L12 · `ClaudeService.kt:51`** — Response notification `postDelayed` can fire after service destroyed. Add `instance == null` guard.
- [x] **L13 · `ModelPickerScreen.kt:185`** — `selectedModel` not reset when live-fetch updates `displays`. Add `LaunchedEffect(displays)` to reset selection.
- [x] **L14 · `HomeScreen.kt:46`** — 4 infinite animations in one `rememberInfiniteTransition` trigger recomposition every frame. Consider `drawBehind` canvas approach.
- [x] **L15 · `SettingsActivity.kt:79`** — `refreshConfig()` not wired via `ActivityResult` — only fires on `onPause`. Register `ActivityResultLauncher` for model change flow.
- [x] **L16 · `app/build.gradle:92`** — Compose BOM `2024.02.00` and compiler `1.5.10` are ~1 year old. Update to latest BOM.
- [x] **L17 · `proguard-rules.pro`** — No explicit `keepclasseswithmembernames { native <methods>; }` rule for JNI. Add it.
- [x] **L18 · `scripts/build-llamaserver.sh`** — `libllamaserver.so` built for `arm64-v8a` only. `armeabi-v7a` devices cannot use local AI models.
- [x] **L19 · `release.yml`** — `release.jks` and `local.properties` not deleted after signing. Add `if: always()` cleanup step.
- [x] **L20 · `index.html`** — `Android.notifyResize()` call not wrapped in try/catch. Add `try{}catch(_){}`.

### ✅ Audit — Verified Correct (No Action)

- OAuth PKCE (state param, verifier, redirect URI validation) — correct
- `.credentials.json`, `local_token`, `bridge_config.json` file permissions (`chmod 600`) — correct
- Backup/data extraction rules exclude all sensitive prefs and files — correct
- `ptyMode` / `authToken` dead fields removed from `bridge_config.json` — confirmed
- All notification channels created in `ClaudeApp.onCreate()` before first use — correct
- `DeviceControlHttpServer` daemon thread wakeup on `serverSocket.close()` — correct
- Accessibility service `BIND_ACCESSIBILITY_SERVICE` permission — correctly configured
- `BootReceiver` exported with specific `BOOT_COMPLETED` filter only — correct
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission — already removed
- `MediaProjectionActivity` — not in manifest, already cleaned up
- CI release secrets (keystore, passwords) — correctly scoped, not logged
- `!cmd`/`$ cmd` SYS_FENCE routing — fixed (prior session)
- Prefix command lowercase normalization in bridge + WebView — fixed (prior session)
