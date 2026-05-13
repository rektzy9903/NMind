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

### No PTY
`bridge.js` spawns `claude --output-format stream-json --print --verbose` per message. Stdout is newline-delimited JSON events; `runMessage()` parses them and forwards text/tool-use lines to the socket.

### Protocol proxy (Anthropic → OpenAI)
HTTP server on port 8082 converts Anthropic Messages API → OpenAI Chat Completions, forwards to provider, converts back (streaming + non-streaming). `ANTHROPIC_BASE_URL=http://127.0.0.1:8082` for all non-Anthropic providers.

### bridge_config.json
Written by `NodeBridgeManager.writeConfig()` before each `startBridge()`. Re-written by `refreshConfig()` on model/provider change in Settings. Includes `modelList` array for 429 fallback. `bridge.js` reads it fresh per message.

### Floating overlay (FloatingOverlayService)
`TYPE_APPLICATION_OVERLAY` full-screen window (`MATCH_PARENT`). Uses `WindowManager.LayoutParams.touchableRegion` (API 29+) to restrict input to the button + any open menus — empty areas pass through to the app. Region is updated via `updateTouchableRegion()` on every drag, show, and hide.

---

## Things to always remember

1. **Never upgrade `claude-code` past v2.1.112** — v2.1.113+ requires glibc (pre-compiled native binaries); Android Bionic is incompatible.

2. **`libnode.so` is not in the repo** — needed at `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/`. CI downloads automatically; local builds need `scripts/download-libnode.sh`.

3. **Node.js can only be started once per process** — `NodeEngine.kt` enforces this with a `started` flag. All retry logic lives inside `bridge.js`.

4. **Bridge config is written before each `startBridge()`** — `bridge_config.json` in `filesDir`. Provider changes take effect on the next message, not immediately.

5. **No PTY** — one `claude --output-format stream-json --print --verbose` process per user message. Ctrl+C sends SIGTERM. Stdout is NDJSON, not raw text.

6. **Signing** — Debug APKs: `com.claudecodesetup.debug`. Release signing reads from `local.properties` (never committed). CI uses GitHub Secrets.

7. **`MODE_GEMINI` is unused at runtime** — only `MODE_SUBSCRIPTION` vs `MODE_PROXY` matters in `ClaudeService`/`NodeBridgeManager`. Gemini is just another proxy-mode provider.

8. **Provider list order in `Providers.ALL`**: `GEMINI, OPENROUTER, DEEPSEEK, KIMI, NVIDIA_NIM, META_LLAMA, OLLAMA` — Gemini is first (recommended default).

9. **`libnode-launcher.so` only works with `-e`** — loading a `.js` file by path silently exits code 1. Only `spawn(LAUNCHER, ['-e', evalCode])` works. `bridge.js` bootstraps cli.js via `import('file://...')` inside the eval string.

10. **`cli.js` must be patched after install** — `patchCliJsForAndroid()` replaces all 23 Unicode property escape regex literals (`/\p{L}/u` etc.) with explicit code-point ranges. Requires full data clear to re-run on an existing install (cache clear is not enough).

11. **`tryOptimize()` must only match short system prompts** — Always keep `if (sys.length > 800) return null;` at the top. Claude Code's real system prompt is ~25 KB and contains words like "title"/"concise" that would otherwise match the housekeeping patterns.

12. **Two layers of `\p{}` patching are needed** — Static literals patched by `patchCliJsForAndroid()` at install time. Dynamic `new RegExp(p, 'u')` calls at runtime caught by a global `RegExp` shim in the eval bootstrap. Both are required. When `[regex-compat]` lines appear in `!log`, add those patterns to `patchCliJsForAndroid()`.

13. **Conversation history = formatted transcript** — `buildMessageWithHistory()` prepends `Human: / Assistant:` turns to each `--print` spawn. Cap: `MAX_HISTORY = 20`. `!clear` resets; context lost when socket closes.

14. **`Intl` is missing from nodejs-mobile v18.20.4** — No ICU. `intlShim` (module-scope in `bridge.js`) is injected before `import(cli.js)`. Extend the stub if a specific `Intl` method crashes.

15. **`regexpShim` and `intlShim` must be module-scope** — Both are injected from `runMessage()` and the `!test-cli` handler. Local scope causes `ReferenceError` in the step 4→5 callback, crashing the Node.js process and restarting the app.

16. **Never use `painterResource(R.mipmap.ic_launcher)` in Compose** — Adaptive icons throw `IllegalArgumentException` on API 26+. Always render via `ContextCompat.getDrawable()` + Android `Canvas` onto a `Bitmap`, then `bitmap.asImageBitmap()`.

17. **OSC thinking sequences are ephemeral — never buffer them** — `ClaudeSession.appendOutput()` strips `thinking-start`/`thinking-done` before storing. Real-time `onOutput` still receives originals. Strip any new OSC state sequences the same way.

18. **`thinking-done` fires on the first parsed JSON event (`system/init`)** — Not on raw bytes. `thinkingDoneSent` flag guards against double-invocation. `stdoutLineBuf` in `runMessage()` handles partial chunks.

19. **`bridge_config.json` includes `modelList`** — Written by `NodeBridgeManager.writeConfig()` from the provider's static model list. Used for 429 fallback in `handleProxyRequest`. OpenRouter live models are not included.

20. **`sendToProvider` has a 7th `on429` callback** — Signature: `sendToProvider(baseUrl, apiKey, oaiReq, stream, res, onBadRequest, on429)`. `lastRateLimitMs` is set by the `on429` handler in `handleProxyRequest`, not inside `sendToProvider`.

21. **`!agentic` state persisted to `filesDir/agentic_state`** — File present = on; absent = off. Initialized at module load. `AGENTIC_FILE` constant at top of `bridge.js`.

22. **Markdown rendering accumulates raw text in `rawAiText`** — Reset only in `startAiBubble()`. `finalizeAiBubble()` checks `hasMarkdownStructures()` and calls `renderMarkdown()` if true. Raw ANSI sequences stripped inside `renderMarkdown()` before parsing.

23. **Background response notification: `CHANNEL_RESPONSE`, `RESPONSE_NOTIF_ID = 1002`** — Debounced 1.5 s in `ClaudeService`. Call `cancelResponseNotification()` from `TerminalActivity` whenever activity becomes visible.

24. **Floating overlay touchableRegion must be updated on every state change** — `updateTouchableRegion()` calls `windowManager.updateViewLayout()`. Must be called after: button drag (`repositionViews`), sub-menu show/hide, quick-panel show/hide. Failing to update leaves the old region active, either blocking taps or missing newly-visible panels.

---

## Known gaps

- **App size** — Both `arm64-v8a` and `armeabi-v7a` `libnode.so` are shipped. Switch to AAB to serve only the device ABI.
- **`ProvidersRepository.REMOTE_URL` wired but empty** — Live provider updates disabled. Enable to push new models without an app update.
- **Sub-agents** — Parallel tool-calling workstreams. Not yet implemented.
- **Interactive PTY mode** — Replace `--print` per-message with a persistent PTY. Fundamental Android limitation; may not be fully achievable.

---

## Latest changes (Session 11)

- **Fixed: floating overlay blocks all screen touches** — `FloatingOverlayService` used a full-screen `MATCH_PARENT` window. `FLAG_NOT_TOUCH_MODAL` only passes events *outside* the window frame — which never existed since the frame was the full screen. Fixed by using `WindowManager.LayoutParams.touchableRegion` (API 29+) to declare only the button and any open menus as interactive. `updateTouchableRegion()` is called on every drag and menu show/hide. (`525651f`)
- **Trimmed CLAUDE.md** — Removed session progress logs, completed roadmap, capability table, and the verbose "Current status / Done" list. Kept all 23 rules + architecture. File: 56 KB → ~8 KB.
