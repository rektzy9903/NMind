# CLAUDE.md — ClaudeCodeSetup

## What this project does

An Android app that runs **Claude Code** (`@anthropic-ai/claude-code`) on an Android phone with zero manual setup. The user installs the APK, picks a provider or logs in with their Claude subscription, and gets a working Claude Code terminal session.

The app ships an embedded Node.js runtime (`libnode.so` via JNI) and a JavaScript bridge (`bridge.js`) that:
1. Downloads and installs `claude-code@2.1.112` from the npm registry on first launch.
2. Starts a local TCP server on port 8083. Each connection spawns one `claude --print` process.
3. For non-Anthropic providers, also starts an Anthropic→OpenAI protocol proxy on port 8082 so Claude Code's Anthropic API calls are forwarded to OpenAI-compatible endpoints (Gemini, OpenRouter, DeepSeek, etc.).

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
    │   ├── HomeActivity.kt / HomeScreen.kt  — glassmorphic main menu
    │   ├── ModelTestActivity/Screen.kt      — test all models, pass/fail/latency
    │   ├── LoginScreens.kt        — Subscription/Malaysia/GeminiRecommend/ProviderList
    │   ├── ApiKeyScreen.kt        — key entry + OkHttp validation per provider
    │   ├── ModelPickerScreen.kt   — 3-col grid, live OpenRouter fetch
    │   └── UiCommon.kt            — AppBackground, glowShadow, font families
    ├── data/
    │   ├── AppPreferences.kt      — EncryptedSharedPreferences wrapper
    │   ├── Providers.kt           — hardcoded Provider/AiModel data
    │   └── ProvidersRepository.kt — loads from asset or remote URL
    ├── managers/
    │   ├── NodeBridgeManager.kt   — starts bridge.js, writes bridge_config.json
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

**Release:** `git tag v1.x.x && git push origin v1.x.x` — `release.yml` builds signed APK. Requires 4 secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

**E2E test:** `OPENROUTER_API_KEY=sk-or-... node .github/scripts/test-full-session.js`

---

## Architecture

### Activity flow
```
SplashActivity (routing only)
    ↓ first run          ↓ provider not set    ↓ ready
SetupActivity        ComposeActivity        HomeActivity
(Node install)       (6-screen flow)            ↓ Chat Box → TerminalActivity
                     sub→malaysia→gemini        ↓ Testing  → ModelTestActivity
                     →providers→key→picker      ↓ Settings → SettingsActivity
                                                  (Change model → ComposeActivity start_at=picker)
```

`SplashActivity` routes on two prefs: `isNodeSetupComplete()` + `isProviderConfigured()`.

### Session model
`ClaudeService` (foreground `LifecycleService`) owns sessions as `LinkedHashMap<Int, ClaudeSession>`. Each session = TCP socket to `127.0.0.1:8083`. Max 4 concurrent sessions.

### PTY mode (always on)
`bridge.js` maintains one persistent interactive `claude` process per session via `libpty-helper.so`. PTY is always enabled (`pty_mode` defaults to `true`). Raw PTY bytes are relayed directly to the terminal WebView. Print mode has been removed entirely.

### Protocol proxy (Anthropic → OpenAI)
HTTP server on port 8082 converts Anthropic Messages API → OpenAI Chat Completions, forwards to provider, converts back (streaming + non-streaming). `ANTHROPIC_BASE_URL=http://127.0.0.1:8082` for all non-Anthropic providers.

### bridge_config.json
Written by `NodeBridgeManager.writeConfig()` before each `startBridge()`. Re-written by `refreshConfig()` on model/provider change in Settings. Includes `modelList` array for 429 fallback. `bridge.js` reads it fresh per message.

### Floating overlay (FloatingOverlayService)
`TYPE_APPLICATION_OVERLAY` window sized to a minimal bounding box (button only when idle, expanded to cover visible menus). `FLAG_NOT_TOUCH_MODAL` passes touches outside the window to the underlying app. `repositionViews()` recalculates the bounding box and updates `overlayParams.x/y/width/height` + child margins (window-relative) on every drag, menu show/hide.

---

## Things to always remember

1. **Never upgrade `claude-code` past v2.1.112** — v2.1.113+ requires glibc (pre-compiled native binaries); Android Bionic is incompatible.

2. **`libnode.so` is not in the repo** — needed at `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/`. CI downloads automatically; local builds need `scripts/download-libnode.sh`.

3. **Node.js can only be started once per process** — `NodeEngine.kt` enforces this with a `started` flag. All retry logic lives inside `bridge.js`.

4. **Bridge config is written before each `startBridge()`** — `bridge_config.json` in `filesDir`. Provider changes take effect on the next message, not immediately.

5. **PTY always on** — one persistent `claude` process per session via `libpty-helper.so`. Raw PTY bytes relayed to the terminal. `--dangerously-skip-permissions` always passed. Print mode is removed. `getPtyMode()` defaults to `true` — no toggle needed.

6. **Signing** — Debug APKs: `com.claudecodesetup.debug`. Release signing reads from `local.properties` (never committed). CI uses GitHub Secrets.

7. **`MODE_GEMINI` is unused at runtime** — only `MODE_SUBSCRIPTION` vs `MODE_PROXY` matters in `ClaudeService`/`NodeBridgeManager`. Gemini is just another proxy-mode provider.

8. **Provider list order in `Providers.ALL`**: `GEMINI, OPENROUTER, DEEPSEEK, KIMI, NVIDIA_NIM, META_LLAMA, OLLAMA` — Gemini is first (recommended default).

9. **`libnode-launcher.so` only works with `-e`** — loading a `.js` file by path silently exits code 1. Only `spawn(LAUNCHER, ['-e', evalCode])` works. `bridge.js` bootstraps cli.js via `import('file://...')` inside the eval string.

10. **`cli.js` must be patched after install** — `patchCliJsForAndroid()` replaces all 23 Unicode property escape regex literals (`/\p{L}/u` etc.) with explicit code-point ranges. Requires full data clear to re-run on an existing install (cache clear is not enough).

11. **`tryOptimize()` must only match short system prompts** — Always keep `if (sys.length > 800) return null;` at the top. Claude Code's real system prompt is ~25 KB and contains words like "title"/"concise" that would otherwise match the housekeeping patterns.

12. **Two layers of `\p{}` patching are needed** — Static literals patched by `patchCliJsForAndroid()` at install time. Dynamic `new RegExp(p, 'u')` calls at runtime caught by a global `RegExp` shim in the eval bootstrap. Both are required. When `[regex-compat]` lines appear in `!log`, add those patterns to `patchCliJsForAndroid()`.

13. **Conversation history managed by claude** — In PTY mode, claude maintains its own context. `!clear` resets `state.history` and sends `thinking-done`/`tokens:0` OSC. Context persists across messages within a session; lost when the persistent proc is killed (idle timeout or socket close with no reconnect).

14. **`Intl` is missing from nodejs-mobile v18.20.4** — No ICU. `intlShim` (module-scope in `bridge.js`) is injected before `import(cli.js)`. Extend the stub if a specific `Intl` method crashes.

15. **`regexpShim` and `intlShim` must be module-scope** — Both are injected into every eval bootstrap and the `!test-cli` handler. Local scope causes `ReferenceError` crashing the Node.js process.

16. **Never use `painterResource(R.mipmap.ic_launcher)` in Compose** — Adaptive icons throw `IllegalArgumentException` on API 26+. Always render via `ContextCompat.getDrawable()` + Android `Canvas` onto a `Bitmap`, then `bitmap.asImageBitmap()`.

17. **OSC thinking sequences are ephemeral — never buffer them** — `ClaudeSession.appendOutput()` strips `thinking-start`/`thinking-done` before storing. Real-time `onOutput` still receives originals. Strip any new OSC state sequences the same way.

18. **`thinking-done` fires on the first parsed JSON event (`system/init`)** — Not on raw bytes. `thinkingDoneSent` flag guards against double-invocation. `stdoutLineBuf` in `runMessage()` handles partial chunks.

19. **`bridge_config.json` includes `modelList`** — Written by `NodeBridgeManager.writeConfig()` from the provider's static model list. Used for 429 fallback in `handleProxyRequest`. OpenRouter live models are not included.

20. **`sendToProvider` has a 7th `on429` callback** — Signature: `sendToProvider(baseUrl, apiKey, oaiReq, stream, res, onBadRequest, on429)`. `lastRateLimitMs` is set by the `on429` handler in `handleProxyRequest`, not inside `sendToProvider`.

21. **`!agentic` state persisted to `filesDir/agentic_state`** — File present = on; absent = off. Initialized at module load. `AGENTIC_FILE` constant at top of `bridge.js`.

22. **Markdown rendering accumulates raw text in `rawAiText`** — Reset only in `startAiBubble()`. `finalizeAiBubble()` checks `hasMarkdownStructures()` and calls `renderMarkdown()` if true. Raw ANSI sequences stripped inside `renderMarkdown()` before parsing.

23. **Background response notification: `CHANNEL_RESPONSE`, `RESPONSE_NOTIF_ID = 1002`** — Debounced 1.5 s in `ClaudeService`. Call `cancelResponseNotification()` from `TerminalActivity` whenever activity becomes visible.

24. **Floating overlay window must be resized on every state change** — `repositionViews()` computes the bounding box of button + visible menus and updates `overlayParams.x/y/width/height` then calls `windowManager.updateViewLayout()`. Child margins are window-relative. Must be called after: button drag, sub-menu show/hide (with GONE set before the call), quick-panel show/hide. `WindowManager.LayoutParams.touchableRegion` is NOT in the public SDK — do not use it.

---

## Known gaps

- **App size** — Both `arm64-v8a` and `armeabi-v7a` `libnode.so` are shipped. AAB bundle config added; use Play Store to serve device ABI only.
- **`ProvidersRepository.REMOTE_URL` wired but empty** — Live provider updates disabled. Enable to push new models without an app update.
- **Sub-agents** — Parallel tool-calling workstreams. Not yet implemented.
- **Per-tab working directory** — All 4 session tabs share the same `shellCwd`. Could allow different project per tab.
- **Custom slash commands** — Claude Code supports `~/.claude/commands/`. Not yet exposed in-app.
- **PTY resize control channel** — Resize commands are sent in-band via `ESC 0xFE` escape sequence intercepted by `pty_helper.c`. A Unix domain socket control channel (like node-pty uses) would be more robust.

---

## Latest changes (Session 19)

### PTY mode — fully fixed and print mode removed

- **`bridge.js`** — Removed `runPrintMessage()` entirely (~120 lines). PTY is now the only session mode. Fixed critical sparse-argv bug in `buildInteractiveEvalCode()`: flags were placed at `argv[4+]` leaving `argv[2]` and `argv[3]` as `undefined` holes — claude-code's arg parser hit those and exited 1. Flags now start at `argv[2]` contiguously. Simplified `isNew` logic and Ctrl+C handler (no longer needs print-mode fallback). Removed dead `resumeId`/`--resume` code, `stdoutBuf`/`initDone`/`initSuppressResult` state fields, and stale `on429CountdownNotify._socket` branch. Fixed null-proc crash in socket `'close'` handler — `state.proc` is null was not guarded.

- **`AppPreferences.kt`** — Added `getPtyMode()`/`setPtyMode()` (were missing, causing compile error). Default is `true` — PTY always on, no settings toggle needed.

- **`NodeBridgeManager.kt`** — Added missing `ptyMode` key to `bridge_config.json`. Previously the Settings PTY toggle had zero effect because `cfg.ptyMode` was always `undefined` in bridge.js.

**Key design decisions:**
- PTY mode is now the only mode. Print mode (`runPrintMessage`) is deleted. `--dangerously-skip-permissions` is always passed so MCP tool permission prompts show interactively in the TUI rather than being silently blocked.
- `buildInteractiveEvalCode()` argv layout: `argv[0]` = launcher, `argv[1]` = CLAUDE_CLI, `argv[2]` = first flag (no gaps). Previously started at `argv[4]` creating a sparse array.

### Local AI (Personal AI) — fixed

- **`PersonalAiScreen.kt`** — Fixed all model download URLs. Qwen official GGUF repos only publish Q8_0, not Q4_K_M — switched Qwen3 models to `lmstudio-community` repos. Phi-4-mini switched from bartowski (401) to `lmstudio-community`. Qwen3-1.5B is gated across every public repo — replaced with **SmolLM2 1.7B** (bartowski, publicly accessible).

- **`ClaudeService.kt`** — Auto-starts llama-server on session connect when `providerId == "local_llama"` and server isn't running. Shows clear error messages if binary is missing or model file not downloaded. No effect on any other provider.

- **`.github/workflows/build.yml`** — Added llama-server build step (with cache). Previously only `release.yml` built `libllamaserver.so`, so debug APKs always showed "not available in this build". Fixed `ANDROID_NDK_HOME` path: use `$ANDROID_SDK_ROOT/ndk/...` inline in `run:` (not `env:` block where `$ANDROID_HOME` expands to empty).

---

## Previous changes (Session 18)

### llama-server local AI (on-device LLM inference)

- **`app/src/main/java/com/claudecodesetup/managers/LlamaServerManager.kt`** (new) — Singleton managing `libllamaserver.so` process. Key methods: `isBinaryAvailable()`, `isServerRunning()`, `startServer(modelId)`, `stopServer()`, `waitUntilReady(timeoutMs)`, `getInstalledModelIds()`, `modelFile(id)`, `deleteModel(id)`. Models stored at `filesDir/models/<id>.gguf`. Server runs at `127.0.0.1:8080`.

- **`app/src/main/java/com/claudecodesetup/data/Providers.kt`** — Added `LOCAL_LLAMA` provider (`id = "local_llama"`, `baseUrl = "http://127.0.0.1:8080/v1"`, `requiresProxy = true`, `requiresApiKey = false`, not in `ALL` list). `byId()` updated to handle `"local_llama"`.

- **`app/src/main/java/com/claudecodesetup/ui/PersonalAiScreen.kt`** — Complete rewrite. Replaced Ollama API calls with llama-server + HuggingFace GGUF direct downloads. New `LocalModel` data class with `downloadUrl`. CATALOG now points to HuggingFace GGUF files (Qwen3 0.6B/1.5B/4B/8B, Llama 3.2 1B/3B, Phi-4 Mini, Llama 3.1 8B). State machine: `binaryAvailable`, `installedIds`, `downloadingId/Progress`, `loadingId/Error`, `activeModelId`, `serverRunning`. Buttons per state: Download → Load → Use/Unload + Delete. Shows "not available in this build" card when binary absent.

- **`app/src/main/java/com/claudecodesetup/ui/ComposeActivity.kt`** — `onModelSelected` now uses `LOCAL_LLAMA` provider and `http://127.0.0.1:8080/v1` (was Ollama + localhost:11434).

- **`app/src/main/java/com/claudecodesetup/services/ClaudeService.kt`** — `onDestroy()` now calls `LlamaServerManager.get(this).stopServer()` to kill the inference process when the service stops.

- **`app/src/main/java/com/claudecodesetup/ui/ApiKeyScreen.kt`** — Added URL reachability check for URL-configurable providers without API key (Ollama remote). Tests `<url>/models` with 3 s timeout; shows error if unreachable. `validateKey()` signature extended with optional `serverUrl` parameter.

- **`scripts/build-llamaserver.sh`** (new) — Builds `llama-server` for `arm64-v8a` using NDK 25.1.8937393. Pins `llama.cpp` tag `b5188`. CMake flags: `GGML_METAL=OFF`, `GGML_CUDA=OFF`, `GGML_VULKAN=OFF`, `LLAMA_BUILD_SERVER=ON`, `BUILD_SHARED_LIBS=OFF`. Strips binary, caches result in `.llama-cache/`. Run `export ANDROID_NDK_HOME=<path> && ./scripts/build-llamaserver.sh`.

- **`.github/workflows/release.yml`** — Added llama-server build step (with `.llama-cache` cache), NDK install, `bundleRelease` step. Now uploads both `app-release.apk` and `app-release.aab` to GitHub Releases.

**Key design decisions:**
- Binary named `libllamaserver.so` in `jniLibs/arm64-v8a/` — Android extracts it to `nativeLibraryDir` with execute permission (same pattern as `libpty-helper.so`)
- Port 8080 for llama-server (distinct from bridge port 8083 and proxy port 8082)
- `libllamaserver.so` is NOT committed — CI builds it from source; local builds need `./scripts/build-llamaserver.sh`
- When binary is absent (default until first CI build with the new step), `PersonalAiScreen` gracefully shows "not available in this build" instead of crashing

### `!pty` command clarification
- **`!pty <cmd>` runs an interactive program** inside a real POSIX PTY (e.g. `!pty bash`, `!pty python3`). This is separate from the session PTY mode.
- **PTY mode for Claude sessions is always on** — `getPtyMode()` defaults to `true`. There is no settings toggle; print mode has been removed entirely.
- **To open an interactive shell**: send `!pty bash` in the chat.

---

## Previous changes (Session 17)

### Local AI (Personal AI / Ollama) — bugs fixed

1. **Remote Ollama URL silently overwritten** — `ComposeActivity.onComplete` called `prefs.setBaseUrl(provider.baseUrl)`, which always wrote the hardcoded default (`http://localhost:11434/v1`) even when the user configured a remote server URL in `ApiKeyScreen`. Fixed: `onComplete` now uses `prefs.getCustomBaseUrlForProvider(provider.id)` for URL-configurable providers, falling back to `provider.baseUrl` only when no custom URL is saved.

2. **`onModelSelected` saved URL without `/v1`** — When tapping "Use" on an installed on-device model, `setCustomBaseUrlForProvider("ollama", "http://localhost:11434")` was saved without the `/v1` path required for Ollama's OpenAI-compat endpoint. Fixed: saves `"http://localhost:11434/v1"`.

3. **Pull stuck in "Downloading…" forever** — If the Ollama pull stream ended without a `"success"` JSON line (connection drop, cancellation), `pullingId` was never cleared and the card stayed frozen with no retry path. Fixed: `pullModel` now tracks a `succeeded` flag; if the stream exits without success or error, it clears `pullingId` and shows "Download interrupted — tap Pull to retry". Also handles Ollama error lines (`"error"` field in response).

### Provider audit — bugs fixed

1. **Ollama: proxy hitting wrong endpoint** — `sendToProvider` appends `/chat/completions` to the stored base URL. Ollama's OpenAI-compat endpoint is at `/v1/chat/completions` but `Providers.OLLAMA.baseUrl` was `http://localhost:11434` (no `/v1`), causing every Ollama request to hit a 404. Fixed in three places: `Providers.kt` default baseUrl → `http://localhost:11434/v1`; `NodeBridgeManager.writeConfig()` auto-appends `/v1` for Ollama if missing (covers existing users with stale saved URL); `ApiKeyScreen` placeholder updated to `http://your-server:11434/v1`.

2. **OpenRouter: stale HTTP-Referer / X-Title headers** — `bridge.js` still sent `HTTP-Referer: https://github.com/rektzy9903/ClaudeCodeSetup` and `X-Title: ClaudeCodeSetup` after the repo rename. OpenRouter uses these for attribution and free model access. Updated to `https://github.com/fahmi304/Nexus-Mind` and `Nexus Mind`.

### Bug fix: live model fetch missing on initial provider setup
- **`ComposeActivity.kt`** — `ProviderListScreen.onSelect` was passing the asset-parsed `Provider` object directly into `selectedProvider`. `ProvidersRepository.parseProvider()` never sets `supportsLiveFetch`, so every provider loaded from `providers.json` had `supportsLiveFetch = false`. This caused `ModelPickerScreen` to skip the live fetch and show only the hardcoded fallback list with no spinner and no refresh button — but only on the **first-run setup flow**. The fix is one line: `selectedProvider = Providers.byId(provider.id) ?: provider`, ensuring the canonical object from `Providers.ALL` (which has `supportsLiveFetch = true`) is always used.
- **Root cause note:** `providers.json` / `parseProvider()` deliberately omits `supportsLiveFetch` (it's a runtime flag, not a remote-config field). The Settings path (`startAt = "picker"`) already used `Providers.byId()` and was never affected.

---

## Latest changes (Session 15)

### Claude.ai OAuth login (subscription users)
- **`app/src/main/java/com/claudecodesetup/ui/ClaudeLoginActivity.kt`** (new file) — Full OAuth 2.0 + PKCE WebView login flow for Claude subscription users. Intercepts redirect at `https://platform.claude.com/oauth/code/callback`, exchanges code at `https://platform.claude.com/v1/oauth/token`, writes credentials to `filesDir/.claude/.credentials.json` as `claudeAiOauth` structure (accessToken, refreshToken, expiresAt, scopes, subscriptionType, rateLimitTier). Three UI phases: `"webview"` → `"exchanging"` → `"error"`. Returns `RESULT_OK` on success, `RESULT_CANCELED` on cancel/failure.
  - CLIENT_ID: `9d1c250a-e61b-44d9-88ed-5944d1962f5e`
  - AUTH_URL: `https://claude.com/cai/oauth/authorize`
  - SCOPES: `org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload`
- **`AndroidManifest.xml`** — Registered `.ui.ClaudeLoginActivity` with `adjustResize` and `Theme.NexusMind`.
- **`ComposeActivity.kt`** — Added `"claude_auth"` screen in the login flow:
  - `"subscription"` → `onYes` now routes to `"claude_auth"` (was `"key"`)
  - Back from `"key"` for Anthropic now returns to `"claude_auth"` (was `"subscription"`)
  - `loginLauncher` (`rememberLauncherForActivityResult`) starts `ClaudeLoginActivity`; on `RESULT_OK` sets `storedKey = ""` and jumps to `"picker"`
  - New `ClaudeAuthScreen` composable: purple "Login with claude.ai" button + dark "Use API key instead" button + back link

### MCP server support
- **`NodeBridgeManager.kt`** — Rewrote `writeMcpConfig()` to combine both HTTP/SSE and stdio servers into a single `mcp_config.json`. Now called from both `startBridge()` and `refreshConfig()`. Deletes the file when no servers are configured.
- **`bridge.js`** — Added `MCP_CONFIG_FILE` constant. Injects `--mcp-config <path>` into claude's argv in both `--print` mode and `buildInteractiveEvalCode()` (PTY mode) when the file exists.

### Parallel model testing
- **`ModelTestScreen.kt`** — `runOrTests()`, `runNvTests()`, `runAllTests()` now fire all requests simultaneously using `coroutineScope { models.mapIndexed { async { } }.awaitAll() }`. All models show `TESTING` state immediately; results update as responses arrive.

### App rename: Claude Code Proxy → Nexus Mind
- `strings.xml`, `ClaudeApp.kt`, `ClaudeService.kt`, `TerminalActivity.kt`, `SettingsActivity.kt`, `ModelPickerScreen.kt`, `BootReceiver.kt`, `themes.xml`, `settings.gradle`, `README.md` — all user-visible "Claude Code" strings changed to "Nexus Mind". Package ID `com.claudecodesetup` intentionally unchanged.

### Repo migration
- Moved from `rektzy9903/ClaudeCodeSetup` → `fahmi304/Nexus-Mind` on GitHub.
- Scrubbed hardcoded API keys from all 149 commits using `git-filter-repo --replace-text`.

## Previous changes (Session 14)

### PTY mode (Phase 2) — persistent session
- **`bridge.js`** — New `buildInteractiveEvalCode()` builds bootstrap eval for interactive mode: `--output-format stream-json` only (no `--print`, no message arg), stdin stays open. New `openPersistentSession()` replaces per-message spawn loop when `ptyMode` is on: spawns one persistent claude process per TCP connection via pty_helper, parses NDJSON events (`system/init` → session ready, `assistant` → stream text/thinking/tool_use, `result` → end-of-turn + token accumulation). Input handler (`persistentDataHandler`) routes: resize (`ESC 0xFE`), Ctrl+C (`\x03` → proc.stdin), `!commands`, `$ shell`, and everything else → `proc.stdin.write(msg + '\n')`. Claude manages its own conversation history — no `buildMessageWithHistory()`, no 50-turn cap. `/cost`, `/compact`, `/doctor`, `/review`, `/clear` all forwarded directly to claude stdin. 60 s timeout sends `\x03`. Socket close → `SIGHUP`. `startBridgeServer()` branches: `cfg.ptyMode ? openPersistentSession() : openTcpBridge()`.

### PTY mode (Phase 1)
- **`pty_helper.c`** — Updated to accept `<cols> <rows>` as first two args (previously `<command>`). Adds `relay_with_resize()` to intercept in-band `ESC 0xFF cols_hi cols_lo rows_hi rows_lo` resize sequences from bridge.js and issue `TIOCSWINSZ` + `SIGWINCH` to child. Fixes termios on slave: `ECHO` off, `ISIG` on (Ctrl+C → SIGINT), `ONLCR` off (no `\n→\r\n` so NDJSON parsing still works).
- **`bridge.js`** — New `PTY_HELPER` constant. New `spawnClaude(evalCode, env, cwd)` replaces direct `spawn(LAUNCHER, ...)` in `runMessage()` — uses pty_helper when `cfg.ptyMode` is true. `normalDataHandler` intercepts `ESC 0xFE` resize signals from the socket and re-encodes as `ESC 0xFF` for pty_helper. `!pty` command updated to pass cols/rows. `MAX_HISTORY` raised 20 → 50.
- **`AppPreferences.kt`** — Added `getPtyMode/setPtyMode`, `getPtyCols/setPtyCols`, `getPtyRows/setPtyRows`.
- **`NodeBridgeManager.kt`** — `writeConfig()` writes `ptyMode`, `ptyCols`, `ptyRows` from prefs into `bridge_config.json`. `refreshConfig()` passes `prefs` to `writeConfig()`.
- **`ClaudeService.kt`** — New `sendResizeAll(cols, rows)` sends 6-byte resize sequence to all active session sockets.
- **`TerminalActivity.kt`** — New `notifyResize(cols, rows)` JS bridge method: saves to prefs + calls `sendResizeAll`.
- **`index.html`** — New `reportTermSize()` measures character grid via DOM probe, calls `Android.notifyResize`. Called on init, `window.resize`, and font size change.
- **`activity_settings.xml` + `SettingsActivity.kt`** — New `switchPtyMode` toggle; `setupPtySwitch()` wires it to prefs + `refreshConfig()`.

## Previous changes (Session 13)

### Overlay bugs fixed
- **Clipboard always empty** — `FloatingOverlayService` has `FLAG_NOT_FOCUSABLE`; Android 10+ blocks clipboard reads from unfocused contexts. Fixed via new `ClipboardHelperActivity` (transparent, reads clipboard, broadcasts `ACTION_CLIPBOARD_READY` / `ACTION_CLIPBOARD_EMPTY` back to service).
- **Screenshot shows cast dialog every time** — `MediaProjectionActivity` now caches the `MediaProjection` object in a companion object after first user approval. Subsequent screenshots skip the dialog. Cache is cleared if the projection becomes invalid.
- **Voice opens Google speech UI** — Both `VoiceInputActivity` (overlay) and `startVoiceInput()` (terminal) replaced `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` with `SpeechRecognizer.startListening()`. Background recognition, no popup.

### Working directory picker
- `ProjectManagerActivity` now registers `ActivityResultContracts.OpenDocumentTree()` and passes an `onPickFolder` callback to `ProjectManagerScreen`. Converts the tree URI (`primary:path`) to a real filesystem path.
- `ProjectManagerScreen` "New Project" dialog: manual path field replaced with a "Browse" button + auto-filled path display.
- **Bug fixed:** `ProjectManagerActivity` now calls `NodeBridgeManager.refreshConfig(prefs)` before starting `TerminalActivity`, so `bridge_config.json` has the correct `projectPath` when the socket connects.
- Active project shows as a green `📂 folder-name` pill in the terminal header (below model name). Tapping it opens Project Manager. Updated on `onResume`.

### Terminal features added
- **Cancel (Ctrl+C)** — `normalDataHandler` in bridge.js stripped all control chars; `\x03` never reached the kill logic. Fixed by checking for `\x03` in the raw socket bytes before line-buffering and immediately calling `current.kill('SIGTERM')`.
- **Image picker fix** — In `--print` mode, bridge.js used to add a text note saying "use agentic mode". Now detects `pending_image.b64` and redirects the message through `runAgentic()` so the image goes via the proxy API (multimodal support).
- **Copy button on AI bubble** — `⎘` button appears on each finalized response (alongside regen). Stores `rawAiText` on the element before it's cleared; calls `Android.copyText()`.
- **Font size A- / A+** — Two toolbar buttons change chat font size (10–22px), persisted in `localStorage`.
- **/compact button** — Toolbar button sends `/compact\r` directly to bridge.
- **Pinch-to-zoom** — Enabled on the terminal WebView (`setSupportZoom`, `builtInZoomControls`, `displayZoomControls=false`).
- **`!help` button** — Pink `?` toolbar button sends `!help\r`, printing the full command cheatsheet inline.
- **Agentic badge** — Purple `⚡ AGENTIC` pill at top-center of terminal. bridge.js sends `9;agentic:on/off` OSC at connection start and on every `!agentic` toggle. Tapping the badge sends `!agentic\r` to toggle.
- **Context window bar** — 2px bar along top edge of terminal grows with token count (200K limit). Orange >120K, red >180K. Token counter text also changes colour.
- **File browser → Attach** — `showFileContent` "Insert as Context" replaced with `🔗 Attach to Claude` which sends `!attach <filepath>\r` to bridge.js.

### Overlay quick prompts (customisable)
- `AppPreferences` gains `getOverlayPrompts()` / `setOverlayPrompts()` backed by `KEY_OVERLAY_PROMPTS`. Defaults are the original 5 prompts.
- `FloatingOverlayService.buildQuickPromptsPanel()` reads from prefs instead of hardcoding.
- `SettingsActivity` gains "Edit overlay quick prompts" button with a multi-line editor dialog and "Reset defaults" neutral button.

## TODO (planned features)

### Security hardening (Session 16 audit)
- [ ] **Local proxy token** — Port 8082 accepts any local connection. Generate a random UUID in `NodeBridgeManager`, write to `bridge_config.json`, inject as `X-Local-Token` header in `ClaudeService`, reject missing/wrong token in `bridge.js`. Prevents other apps on device from piggybacking the API key.
- [ ] **`FLAG_SECURE`** on `ApiKeyScreen` / `ComposeActivity` — prevents API key appearing in Android recents screenshot. One line: `window.setFlags(FLAG_SECURE, FLAG_SECURE)` in the activity.
- [ ] **`allowBackup="false"`** in `AndroidManifest.xml` — stops adb/cloud backup from exporting encrypted prefs. ✅ Already set.
- [ ] **Scrub logs** — grep for `Log.*key`, `Log.*apiKey`, `Log.*baseUrl` and remove any that echo sensitive values.

### Bugs found (audit — still open)

#### Must fix
- [ ] **`SplashActivity` shared-text routing** — routes to `TerminalActivity` without checking `isProviderConfigured()`. If provider not set, terminal opens and shows a connection error with no guidance. Fix: add `&& prefs.isProviderConfigured()` to the `sharedText != null` branch.
- [ ] **`ApiKeyScreen` missing Groq validation** — `buildRequest()` has no `"groq"` case; key validation silently returns `null` (= success) for Groq. Fix: add `"groq" -> builder.url("https://api.groq.com/openai/v1/models").header("Authorization", "Bearer $key").build()`.
- [ ] **`ClaudeLoginActivity` OAuth state not verified** — `state` is generated and sent in the auth URL but the callback never verifies the returned state. Missing CSRF protection.

#### Security
- [ ] **`.credentials.json` file permissions** — written with default permissions (world-readable on rooted devices). Fix: `setReadable(false, false); setReadable(true, true)` after writing. (`bridge_config.json` already hardened.)
- [ ] **Port 8083 (bridge TCP) also needs auth token** — same problem as port 8082. Both ports should validate the local UUID token.

#### Improvements / optimizations
- [ ] **`SetupActivity` thread-per-poll** — spawns a new `Thread { }` every 2 s to call `isBridgeReachable()`. Use a coroutine + `delay(2000)` on `Dispatchers.IO` instead.
- [ ] **`ComposeActivity` back from picker when `startAt="picker"`** — pressing Back goes to the provider list instead of finishing. When `startAt == "picker"`, back should call `finish()`.
- [ ] **MCP stdio `args` parsing ignores quoted arguments** — `argsStr.split("\s+".toRegex())` breaks args with spaces silently.
- [ ] **`ScheduledPrompt` day-of-week filtering missing** — data class has no `days` field; WorkManager fires every day regardless.
- [ ] **`callProxyStreaming` hardcoded model** — dead value `'claude-3-5-sonnet-20241022'`. Replace with `cfg.modelId || 'claude-3-5-sonnet-20241022'`.
### Screenshot / vision feature removal
**Decision pending scope:** The app has a full screenshot pipeline (MediaProjection, overlay quick prompts, `pending_image.b64` in bridge.js) but **no vision-capable models** are used. This is dead weight.

**Files to touch:**
- `FloatingOverlayService` — remove screenshot capture call, remove `MediaProjectionActivity` launch, remove screenshot-dependent quick prompts (`"Summarize what's on my screen"`, `"Fix the error on my screen"`)
- `MediaProjectionActivity.kt` — delete entirely
- `bridge.js` — remove `pending_image.b64` detection and `runAgentic()` image redirect path
- `AndroidManifest.xml` — remove `FOREGROUND_SERVICE_MEDIA_PROJECTION` and `MediaProjectionActivity` registration
- Keep: `!attach <filepath>` for non-image file context, image picker UI (in case vision model added later — or remove that too)

**Scope confirmed by user:** TBD (asked but not answered yet)

### Remote Ollama / Private AI (Oracle Cloud)
Ollama server URL is now configurable via ApiKeyScreen (Session 16). Remaining:
- [ ] Handle HTTPS for remote connections (Oracle Cloud IP or custom domain)
- [ ] In Settings, allow changing the Ollama server URL without going through full provider reset

**User's Oracle Cloud setup steps (prerequisite):**
1. Create Oracle Cloud account at `cloud.oracle.com` (free tier, credit card for identity only)
2. Provision Ampere A1 VM (4 cores, 24GB RAM, Ubuntu 22.04)
3. Open port 11434 in security rules
4. Install Ollama: `curl -fsSL https://ollama.ai/install.sh | sh`
5. Pull a model: `ollama pull llama3.1:8b`
6. In app: Settings → Change Provider → Ollama → enter `http://<oracle-ip>:11434`

**Recommended free models for 24GB RAM:** llama3.1:8b (5GB), qwen2.5:7b (4GB), mistral:7b (4GB), phi3:medium (8GB)

---

## Previous changes (Session 12)

- **Fixed: build fails with `Unresolved reference: touchableRegion`** — `WindowManager.LayoutParams.touchableRegion` is a `@hide` AOSP field absent from the public Android SDK. Replaced the MATCH_PARENT window + hidden-field approach with a dynamically-sized window: `overlayParams` starts at button size, and `repositionViews()` expands it to cover visible menus. Child views use window-relative margins. `FLAG_NOT_TOUCH_MODAL` passes touches outside the window to the underlying app.

## Previous changes (Session 11)

- **Fixed: floating overlay blocks all screen touches** — `FloatingOverlayService` used a full-screen `MATCH_PARENT` window. Attempted fix used `WindowManager.LayoutParams.touchableRegion` (hidden API — broke CI). See session 12 for the correct fix.
- **Trimmed CLAUDE.md** — Removed session progress logs, completed roadmap, capability table, and the verbose "Current status / Done" list. Kept all 23 rules + architecture. File: 56 KB → ~8 KB.
