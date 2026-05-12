# CLAUDE.md — ClaudeCodeSetup

## What this project does

An Android app that runs **Claude Code** (`@anthropic-ai/claude-code`) on an Android phone with zero manual setup. The user installs the APK, picks a provider or logs in with their Claude subscription, and gets a working Claude Code terminal session.

The app ships an embedded Node.js runtime (`libnode.so` via JNI) and a JavaScript bridge (`bridge.js`) that:
1. Downloads and installs `claude-code@2.1.112` from the npm registry on first launch.
2. Starts a local TCP server on port 8083. Each connection spawns one `claude --print` process.
3. For non-Anthropic providers, also starts an Anthropic→OpenAI protocol proxy on port 8082 so Claude Code's Anthropic API calls are forwarded to OpenAI-compatible endpoints (Gemini, OpenRouter, DeepSeek, etc.).

---

## Tech stack and key dependencies

| Layer | What |
|---|---|
| Language | Kotlin (Android), C++17 (JNI glue), JavaScript (bridge) |
| Build system | Gradle (Kotlin DSL via Groovy `build.gradle`) |
| Min SDK | 29 (Android 10) |
| Target/Compile SDK | 34 |
| NDK | 25.1.8937393 |
| Embedded runtime | `libnode.so` from **nodejs-mobile v18.20.4** (arm64-v8a + armeabi-v7a) |
| Claude Code version | **2.1.112** (pinned — last version that runs on Android's Bionic runtime) |
| Key Android libraries | `androidx.security:security-crypto` (EncryptedSharedPreferences for API keys), `okhttp3`, `kotlinx-coroutines`, `androidx.lifecycle:lifecycle-service`, `androidx.work:work-runtime-ktx` |
| View binding | Enabled (`viewBinding true`) |

**Why v2.1.112 is pinned:** v2.1.113+ switched to pre-compiled native binaries requiring glibc; Bionic (Android's libc) is incompatible. Do not bump this unless you have confirmed compatibility.

---

## Folder structure overview

```
ClaudeCodeSetup/
├── app/
│   ├── build.gradle              — app-level Gradle config, signing, NDK
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── assets/
│   │   │   ├── nodejs-project/
│   │   │   │   ├── bridge.js     — the main Node.js bridge (install + proxy + TCP server)
│   │   │   │   └── package.json
│   │   │   ├── providers.json    — bundled provider list (fallback for ProvidersRepository)
│   │   │   └── terminal/
│   │   │       └── index.html    — ANSI terminal emulator rendered in WebView
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt
│   │   │   ├── node_bridge.cpp   — JNI: calls node::Start() from libnode.so
│   │   │   └── node_launcher.cpp — optional launcher helper
│   │   └── java/com/claudecodesetup/
│   │       ├── ClaudeApp.kt          — Application class, notification channels
│   │       ├── SplashActivity.kt     — routing: Setup → ComposeActivity → Terminal
│   │       ├── SetupActivity.kt      — first-run: starts Node, polls setup.log
│   │       ├── TerminalActivity.kt   — WebView terminal + session tabs
│   │       ├── SettingsActivity.kt   — change provider, reset, language
│   │       ├── NodeEngine.kt         — Kotlin singleton; wraps JNI nativeStart()
│   │       ├── ui/
│   │       │   ├── ComposeActivity.kt    — hosts full Compose login flow; supports start_at intent extra
│   │       │   ├── HomeActivity.kt       — entry point after setup; hosts HomeScreen composable
│   │       │   ├── HomeScreen.kt         — glassmorphic main menu: Chat Box, Testing Response, Setting cards
│   │       │   ├── ModelTestActivity.kt  — reads prefs, hosts ModelTestScreen composable
│   │       │   ├── ModelTestScreen.kt    — tests all provider models via OkHttp; shows pass/fail/latency
│   │       │   ├── LoginScreens.kt       — SubscriptionScreen, MalaysiaScreen, GeminiRecommendScreen, ProviderListScreen
│   │       │   ├── ApiKeyScreen.kt       — glassmorphic key entry + OkHttp validation per provider
│   │       │   ├── ModelPickerScreen.kt  — 3-col grid picker; live fetch for OpenRouter
│   │       │   └── UiCommon.kt           — AppBackground, glowShadow, DmSansFamily, SpaceMonoFamily
│   │       ├── data/
│   │       │   ├── AppPreferences.kt     — EncryptedSharedPreferences wrapper
│   │       │   ├── Providers.kt          — hardcoded Provider/AiModel data classes
│   │       │   └── ProvidersRepository.kt — loads providers from asset (or remote URL)
│   │       ├── managers/
│   │       │   ├── NodeBridgeManager.kt  — starts bridge.js, writes bridge_config.json
│   │       │   └── DownloadManager.kt    — resumable OkHttp downloader + npm version check
│   │       ├── services/
│   │       │   └── ClaudeService.kt      — foreground service, session lifecycle, TCP sockets
│   │       └── receivers/
│   │           └── BootReceiver.kt       — restores service on device boot
├── .github/
│   ├── scripts/
│   │   ├── test-full-session.js  — end-to-end simulation (Node, proxy, claude --print)
│   │   ├── test-bridge.js        — bridge unit test
│   │   └── test-e2e-chat.js      — chat flow test
│   └── workflows/
│       ├── build.yml             — builds debug APK on every push to main
│       ├── release.yml           — builds signed APK and creates GitHub Release on version tags
│       ├── test-full-session.yml — runs test-full-session.js (triggered on bridge.js changes)
│       ├── test-e2e-chat.yml
│       ├── test.yml
│       └── auto-fix.yml
├── build.gradle                  — root Gradle config
├── settings.gradle
├── gradle.properties
├── local.properties              — sdk.dir + signing config (never committed)
├── scripts/
│   └── download-libnode.sh       — manual helper to fetch libnode.so
└── README.md
```

---

## How to run, build, and test

### Build (CI — recommended)

Every push to `main` triggers `build.yml` and produces a debug APK artifact in GitHub Actions (~4 min first build, ~1–2 min with cache). Download from the Actions → Artifacts tab.

### Build locally

```bash
# Requires Android Studio or the Android SDK + NDK installed
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

`libnode.so` is **not committed** — the CI workflow downloads it from the official nodejs-mobile release. For a local build you either need to run `scripts/download-libnode.sh` or copy `app/src/main/jniLibs/arm64-v8a/libnode.so` (and `armeabi-v7a`) manually from the nodejs-mobile v18.20.4 Android release zip.

### Release (signed APK)

Push a version tag:
```bash
git tag v1.2.0
git push origin v1.2.0
```
`release.yml` builds a signed APK and creates a GitHub Release. Requires 4 GitHub repo secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

### Tests

```bash
# End-to-end session simulation (requires OPENROUTER_API_KEY)
OPENROUTER_API_KEY=sk-or-... node .github/scripts/test-full-session.js
```

The test downloads `claude-code@2.1.112`, starts the proxy, spawns `claude --print`, sends "hello claude", and asserts a real reply comes back. It runs automatically in CI on changes to `bridge.js` or `test-full-session.js`.

No Android unit tests exist yet. Instrumentation tests are wired up but empty (Espresso dependency present, no test files written).

---

## Architecture and key patterns

### Activity flow

```
SplashActivity (routing only)
    ↓  first run               ↓  provider not set    ↓  ready
SetupActivity         ComposeActivity              HomeActivity (main menu)
(Node install)        (6-screen Compose flow)          ↓ Chat Box
                        subscription → malaysia     TerminalActivity
                        → gemini_recommend          (sessions + WebView)
                        → providers → key → picker       ↕
                                                    SettingsActivity
                                                    (Change model → ComposeActivity start_at=picker)
                                                    ↓ Testing Response
                                                    ModelTestActivity
```

`SplashActivity` always decides the next screen based on two prefs: `isNodeSetupComplete()` and `isProviderConfigured()`. When provider is configured it routes to `HomeActivity` (main menu). `LoginFlowActivity` has been deleted — `ComposeActivity` is the sole login flow entry point.

### Session model

`ClaudeService` (foreground `LifecycleService`) owns all sessions as a `LinkedHashMap<Int, ClaudeSession>`. Each session is a TCP socket to `127.0.0.1:8083`. `TerminalActivity` binds to the service and sets three lambda callbacks (`onOutput`, `onSessionAdded`, `onSessionEnded`). Max 4 concurrent sessions.

### No PTY — `--print` mode

The app does **not** use a PTY (pseudo-terminal). `bridge.js` spawns `claude --print` per message: the user's input goes to stdin, the response streams back on stdout/stderr, then the process exits. The terminal shows a local echo (`❯ <text>`) before sending.

### Protocol proxy (Anthropic → OpenAI)

`bridge.js` runs a local HTTP server on port 8082. It converts Anthropic Messages API requests to OpenAI Chat Completions format, forwards them to the selected provider, and converts the response back (supporting both streaming SSE and non-streaming). OpenRouter gets attribution headers (`HTTP-Referer`, `X-Title`).

For Anthropic subscription mode, `ANTHROPIC_BASE_URL` is left unset (direct API); for all other providers it points to `http://127.0.0.1:8082`.

### Config file (bridge_config.json)

`NodeBridgeManager.writeConfig()` writes a JSON file to `filesDir/bridge_config.json` before each bridge start. `bridge.js` reads it via `readConfig()` before each spawn, so provider changes take effect for new sessions without restarting Node.js.

### Encrypted prefs

API keys are stored in `EncryptedSharedPreferences` (AES-256-GCM) with a fallback to plain `SharedPreferences` if the EncryptedSharedPreferences initialization fails.

### WebView terminal

`terminal/index.html` is a hand-rolled ANSI terminal emulator (no xterm.js). It parses ANSI escape codes in JavaScript and renders them as `<span>` elements with CSS classes. `TerminalActivity.writeToTerminal()` calls `window.termWrite(json)` via `evaluateJavascript`. The Kotlin→JS bridge manually escapes special characters to avoid JSON injection.

### Provider data

Providers are defined in two places:
1. **`Providers.kt`** — hardcoded fallback (always available).
2. **`assets/providers.json`** — parsed by `ProvidersRepository` at runtime (can be overridden by a remote URL set in `REMOTE_URL` constant; currently empty string = always use bundled asset).

---

## Current status

### Done
- Full end-to-end flow: install → provider selection → working Claude Code terminal.
- `--print` mode works correctly (no PTY required).
- Anthropic→OpenAI proxy with both streaming and non-streaming support.
- Multi-session support (up to 4 tabs).
- Foreground service with wake lock, persistent notification with Stop action.
- EncryptedSharedPreferences for API key storage.
- API key validation at login time for all major providers.
- Retry loop in `bridge.js` for failed npm installs (polls sentinel file).
- GitHub Actions: debug APK build, signed release, E2E test.
- Providers: Gemini (recommended for Malaysia), OpenRouter, DeepSeek, Kimi, NVIDIA NIM, Meta Llama, Ollama, Anthropic subscription.
- Malaysian user UX path (Gemini fast-track recommendation).
- Language support: English + Bahasa Malaysia (strings partially translated).
- Diagnostic commands in terminal: `!log` (last 80 lines of setup.log), `!test` (launcher self-test), `!ver` (config dump), `!test-cli` (5-step module-loader diagnostic).
- Verbose stderr logging and actionable error hints on claude exit code 1.
- **Fixed: proxy mode silent exit code 1 (ANTHROPIC_MODEL)** — `ANTHROPIC_MODEL` now always uses a valid Claude model name (`claude-3-5-sonnet-20241022`) in proxy mode. A provider model ID like `openai/gpt-oss-120b:free` failed claude-code's internal model name validation before any network call, causing a silent exit.
- **Fixed: launcher cannot load script files by path** — `libnode-launcher.so` silently exits with code 1 when given any `.js`, `.mjs`, or `--input-type=module` argument. Only `-e` (inline eval) works. `bridge.js` now spawns `LAUNCHER ['-e', evalCode]` where `evalCode` sets `process.argv` then calls `import('file://...cli.js')`. Dynamic `import()` from a CJS `-e` context works correctly — the event loop stays alive until the ESM module loads.
- **Fixed: Unicode property escape SyntaxError** — Android's nodejs-mobile v18.20.4 V8 build has no `\p{...}` regex property escape support. `cli.js` uses 23 such patterns (`\p{L}`, `\p{N}`, `\p{P}`, `\p{S}`, `\p{M}`, `\p{Default_Ignorable_Code_Point}`, etc.) in its markdown parser, text normalizer, and @ mention detector. A `SyntaxError` during module parse caused silent exit code 1 with zero output. Fixed by `patchCliJsForAndroid()` in `bridge.js`, which runs once after install and replaces all 23 occurrences with equivalent explicit Unicode code-point ranges (`\xC0-ɏ`, `Ͱ-Ͽ`, `一-鿿`, etc.).
- **Fixed: tryOptimize incorrectly short-circuiting real user messages** — `tryOptimize()` in `bridge.js` is meant to intercept lightweight internal housekeeping calls (title generation, follow-up suggestions, file-path extraction) to save API quota. However, Claude Code v2.1.112's main system prompt is ~25 KB and contains words like "concise" and "title", which caused the title-generation pattern to match on **every** real user message. The function was returning `'Claude Code Session'` instead of forwarding the user's message to the provider. Fixed by adding `if (sys.length > 800) return null;` as an early guard — optimization only applies to the short focused housekeeping prompts; the full Claude Code system prompt always passes through to the real provider.
- **Full end-to-end simulation confirmed working** — local simulation (`test-full-session.js`) using OpenRouter `openai/gpt-oss-20b:free` passes 6/6: exit code 0, real reply received ("Hi! How can I help you today?"). Simulation uses the exact bridge.js spawn method (`-e evalCode` + `import(file://cli.js)`) and env vars (`ANTHROPIC_API_KEY=sk-ant-proxy000`, `ANTHROPIC_MODEL=claude-3-5-sonnet-20241022`, `ANTHROPIC_BASE_URL=http://127.0.0.1:<proxy>`).
- **Working OpenRouter free models confirmed** (as of 2026-05-11): `openai/gpt-oss-120b:free`, `openai/gpt-oss-20b:free`, `minimax/minimax-m2.5:free`, `nvidia/nemotron-3-super-120b-a12b:free`, `nvidia/nemotron-nano-12b-v2-vl:free`, `nvidia/nemotron-nano-9b-v2:free`, `baidu/cobuddy:free`, `baidu/qianfan-ocr-fast:free`, `moonshotai/kimi-k2.5` (via OpenRouter). Kimi direct API (moonshot.ai) has no balance. Many `:free` models rate-limit aggressively (429); cli.js retries automatically.
- **Diagnosed: dynamic `new RegExp(\p{})` failures in --print mode** — On-device diagnostics (Redmi 10, Android arm64) revealed: `[eval-ok]` fires, networking works (net-ok), but cli.js exits code 1 before making **any** proxy request (no `[proxy]` lines in log). Root cause: `patchCliJsForAndroid()` patches static regex literals at install time, but cli.js also has dynamic `new RegExp(pattern, 'u')` calls inside function bodies that only execute during `--print` initialization (not `--version`). These throw "Invalid property name" on Android V8, propagate as unhandled rejections, and cli.js's own handler calls exit(1) silently. The diagnostic framework added: proxy logs all requests, `[eval-ok]`/`[exit-event]` hooks, `!test-cli` step 6 (TCP net probe to proxy port).
- **Fixed: runtime RegExp shim in evalCode** — Added a `global.RegExp` wrapper shim to the eval bootstrap (installed before `import(cli.js)`). When any `new RegExp(p, '...u...')` fails with a Unicode property escape error, the shim: (1) appends `[regex-compat] <pattern>` to setup.log so the pattern can be added to `patchCliJsForAndroid()` permanently, (2) returns `/(?:)/` as a safe fallback so cli.js initialization continues. Simulation: 6/6 passed with real reply after this fix.
- **Fixed: `Intl is not defined` crash** — nodejs-mobile v18.20.4 is built without ICU, so `global.Intl` is undefined. cli.js at line 557 uses `new Intl.*` (NumberFormat, DateTimeFormat, Collator, etc.) during `--print` initialization, throwing `ReferenceError: Intl is not defined`. Fixed by injecting `intlShim` into the eval bootstrap before `import(cli.js)`. The shim installs a minimal stub for all `Intl.*` constructors. Defined at module scope alongside `regexpShim`.
- **Fixed: `!test-cli` step 5 crashes Android app** — `regexpShim` was declared as `const` inside `runMessage()` (local scope) but referenced at line 1173 inside the `!test-cli` handler (inside `openTcpBridge()`, a different scope). When step 4's `child.on('close')` callback ran and tried to build step 5's eval string, it threw `ReferenceError: regexpShim is not defined`. This uncaught exception crashed the Node.js process → Android killed and restarted the app. Fixed by moving both `regexpShim` and `intlShim` to module scope.
- **Added: rate limit notification** — When the provider returns HTTP 429, a module-level `lastRateLimitMs` timestamp is set. If the child exits with no output and a recent 429 was seen (within 15 s), the terminal shows `⚠ Rate limited by provider (HTTP 429). Model is busy. Wait ~30 s or switch models in Settings.` — distinguishing rate limits from app bugs.

- **Added: conversation history injection (Option B)** — Per-socket `history[]` array accumulates `{role, content}` pairs. `buildMessageWithHistory()` prepends prior turns as a formatted transcript (`Human: … / Assistant: …`) to each new `--print` spawn. Capped at 20 messages (10 turns). New terminal commands: `!clear` resets history, `!history` shows count. Rollback: `git checkout 2332466 -- bridge.js` or see `CHANGES.md`.

- **Replaced app icon with Convergence Gate design** — New icon is a dark-purple geometric diamond gate with 6 colored peripheral nodes (orange/green/blue/purple/red/cyan) connected by dashed lines to a central white glow. Generated all density PNGs (mdpi 48px → xxxhdpi 192px) + round variants via a pure-Python pixel renderer (`/tmp/render_icon.py`, `struct`/`zlib`/`math` only — no native image libs available on ARM64 PRoot). `ic_launcher_foreground.xml` replaced with Android VectorDrawable version using `aapt:attr` radial/linear gradients (requires API 26+, minSdk is 29). `ic_launcher_background.xml` updated to `#090714`. Master SVG saved at `app/src/main/assets/icons/app_icon.svg`.

- **Added: full Jetpack Compose login flow — replaces LoginFlowActivity** — `LoginFlowActivity.kt`, `activity_login_flow.xml`, `item_provider.xml`, `provider_registry.html` all deleted. `ComposeActivity` now hosts a 6-screen flow with `var screen by remember` state navigation (no NavController). Compose BOM `2024.02.00`, Kotlin Compose compiler `1.5.10`.
  - **LoginScreens.kt**: `SubscriptionScreen`, `MalaysiaScreen`, `GeminiRecommendScreen`, `ProviderListScreen`, `ProviderCard`, `FlowButton`, `FlowOutlineButton`. Glassmorphic card design consistent across all screens.
  - **ApiKeyScreen**: glassmorphic card on radial gradient background. IDLE/LOADING/SUCCESS/ERROR state machine. `animateColorAsState` border + `BlurMaskFilter` glow ring. Real OkHttp validation per provider (Anthropic, Gemini, OpenRouter, DeepSeek, Kimi, NVIDIA, Meta). Gradient CTA button (`#3B82F6→#6366F1`). Handles Ollama (no key required).
  - **ModelPickerScreen**: driven by `provider.models` (real data). `LazyRow` filter chips (animated). `LazyVerticalGrid` 3-column, 9 per page. Per-card: emoji, speed bar, badge pill, token count, glow on selection. For OpenRouter: auto-fetches live free models via `ProvidersRepository.fetchOpenRouterFreeModels()` on entry + "↻ Refresh" button in header.
  - **UiCommon.kt**: `AppBackground` composable (radial gradient + 3 decorative glow blobs). `glowShadow` Modifier extension via `drawBehind` + `BlurMaskFilter`. `DmSansFamily` + `SpaceMonoFamily` via `GoogleFont.Provider`. `font_certs.xml` added to `res/values/` (required by `GoogleFont.Provider` — not in any Maven dep, must be in project resources).
  - **`start_at` intent extra**: `ComposeActivity` accepts `intent.getStringExtra("start_at")` to jump directly to any screen. Used by `SettingsActivity` "Change model" button which passes `start_at=picker`; the picker pre-loads the current provider + API key from prefs.

- **Fixed: chat UI — empty bubble, avatar position, text colors** — Three bugs in `terminal/index.html`:
  1. **Empty sys bubble**: `termWrite` previously called `getSysBubble()` (DOM creation) before `processAnsiBytes()`. OSC sequences like `thinking-start` change `chatState` inside `processAnsiBytes`, but the empty DOM element was already created. Fix: process ANSI bytes first; only create a sys bubble if `chatState` is still `IDLE` and there is visible text in `sysAnsi.lines`. `sysAnsi` reset moved from `getSysBubble()` to `submitLine()`.
  2. **Avatar on wrong side**: `.user-row` uses `flex-direction: row-reverse` (first child = rightmost). DOM was `[bub, av]` → avatar landed left of bubble. Fixed to `[av, bub]` → avatar rightmost, bubble to its left.
  3. **Input text color**: `#input-wrap color` was `#e0d8ff` (bluish-white); changed to `#ffffff`.

- **Fixed: logos in terminal header and setup screen** — `activity_terminal.xml` and `activity_setup.xml` were using `@drawable/ic_terminal` (old icon). Both updated to `@mipmap/ic_launcher` (the new Convergence Gate app icon). Terminal header tint removed; size adjusted to 28dp.

- **Fixed: bluish-white text colors in Compose UI** — `Color(0xFFF1F5F9)` (Tailwind slate-100) and `Color(0xFFE5E7EB)` (gray-200) appeared as the primary text color on dark backgrounds, rendering as slightly blue-tinted white (hard to read). All occurrences in `ApiKeyScreen.kt`, `LoginScreens.kt`, and `ModelPickerScreen.kt` replaced with `Color.White`.

- **Fixed: Settings "Change model" button** — Previously showed an in-app dialog with a live OpenRouter model fetch. Now navigates to `ComposeActivity` with `start_at=picker`, which loads the current provider/key from prefs and shows the full Compose model picker (works for all providers, not just OpenRouter). Visibility condition changed from `providerId == "openrouter"` to `provider.models.size > 1`. Dead code (`startModelRefresh`, `showModelPickerDialog`) removed from `SettingsActivity`.

- **Added: glassmorphic HomeScreen (main menu)** — `HomeScreen.kt` + `HomeActivity.kt` added. Animated dark-purple background with 3 floating orbs (purple/navy/cyan) and a dot-grid canvas overlay. App icon rendered via Bitmap canvas (required for adaptive icons — `painterResource(R.mipmap.ic_launcher)` crashes on API 26+ adaptive icons). Pulsing green "All systems online" status dot. Three staggered `AnimatedVisibility` menu cards: **Chat Box** → `TerminalActivity`, **Testing Response** → `ModelTestActivity`, **Setting** → `SettingsActivity`. `SplashActivity` now routes to `HomeActivity` (instead of directly to `TerminalActivity`) when provider is configured.

- **Added: ModelTestScreen** — `ModelTestScreen.kt` + `ModelTestActivity.kt`. Tests all models for the active provider by sending `{"messages":[{"role":"user","content":"hi"}],"max_tokens":8}` via OkHttp. Shows per-model status: PASS / EMPTY / RATE_LIMITED (429) / FAIL / TIMEOUT with color-coded indicators and latency in ms. Sequential tests with 300ms gap between models. Gemini uses its own `generativelanguage.googleapis.com` endpoint; all others use OpenAI-compat `/chat/completions`. "Test All" button with disabled state while running.

- **Added: `$` shell prefix in terminal** — Typing `$ <command>` (or just `$` alone) runs the command directly in `/system/bin/sh` instead of sending it to the AI. `cd` is handled in-process to update `shellCwd` (persists across commands in the same session). stdout goes to terminal directly; stderr in yellow. Shell env inherits `PATH`, `HOME`, `TMPDIR`, `FILES_DIR`. Busy sessions are interrupted (SIGTERM) before a new shell command runs.

- **Added: `!help` command** — Lists all built-in terminal commands (`!log`, `!test`, `!ver`, `!test-cli`, `!clear`, `!history`, `!help`, `$ <cmd>`).

- **Fixed: stacking empty boxes on app restart** — When `TerminalActivity` reconnects to a running `ClaudeService`, it replays the session buffer. The buffer contained `thinking-start`/`thinking-done` OSC sequences from prior AI responses; replaying them into a fresh WebView created N empty bubbles (N = number of previous responses). Fixed in `ClaudeSession.appendOutput()`: strips `\x1b]9;thinking-start\x07` and `\x1b]9;thinking-done\x07` before storing in the buffer. The real-time `onOutput` callback still receives the original data, so live thinking indicators still work.

- **Fixed: empty AI response bubbles (root cause)** — `thinking-done` was only sent after the child process exited (after all stdout). The terminal discards data while `chatState === THINKING`. Fix: in `runMessage`, send `\x1b]9;thinking-done\x07` on the **first stdout byte** (before forwarding it) using a `thinkingDoneSent` flag. Also guarded `handleOSC thinking-done` in `index.html` against double-invocation to prevent creating a second empty bubble.

- **Fixed: proxy tool forwarding causing empty responses** — The proxy was forwarding Claude Code's tool definitions (bash, file read/write, etc.) to free OpenAI-compat models. Most free models either don't support tools or return an empty `content` when tools are present. Removed tool forwarding from `anthToOai()` — only the user message content is forwarded. Free models respond correctly without tool definitions.

### Known gaps / TODO
- `DownloadManager.kt` exists with resumable download + npm version fetching, but `fetchLatestClaudeVersion()` is not used — version is always the pinned constant. The class is partially unused.
- `ProvidersRepository.REMOTE_URL` is empty — live provider updates are wired but disabled.
- No Android unit or instrumentation tests written.
- `BootReceiver` exists but doesn't automatically restart a session after boot (only starts the service).
- `SettingsActivity` references `DownloadManager.PINNED_CLAUDE_VERSION` for display but the installed version tracking (`getInstalledClaudeVersion`) is never written after install.
- No `CHANGELOG.md` (referenced in `release.yml`).
- `build.gradle` ABI filter is `arm64-v8a, armeabi-v7a` only — x86/x86_64 not supported despite README mentioning them.
- README still has `YOUR_USERNAME` placeholders in GitHub URLs.
- **Models have no internet access** — providers are stateless LLMs; no web search or tool use is wired. In proxy mode (all non-Anthropic providers), Claude Code tool calls are not forwarded and may be silently dropped.
- **No persistent memory across sessions** — `history[]` in `bridge.js` is per-socket only; lost on socket close, app restart, or model switch. Cross-session memory (save/load `session_memory.json` in `filesDir`) is not yet implemented.
- **No agentic/shell execution** — the app uses `claude --print` only. Claude Code's agentic loop (file read/write, shell commands, git, etc.) does not run. Enabling it would require switching to interactive PTY mode and bundling ARM64 binaries (`git`, `gh`, etc.).
- **`!install-git` not yet implemented** — `$ git` commands work if git is installed, but there is no in-app installer for a static ARM64 git binary. Planned: download a pre-built ARM64 git binary to `filesDir/bin/` and prepend it to PATH.
- **Proxy models cannot execute tools autonomously** — In proxy mode, Claude Code tool calls (bash, file read/write, etc.) are stripped before forwarding to the provider. The model can converse but cannot autonomously run shell commands or interact with GitHub. Full agentic tool forwarding would require: (1) forwarding tool definitions to the model, (2) executing tool results in the bridge shell, (3) sending results back as tool_result messages in a loop.

---

## Things to always remember

1. **Never upgrade `claude-code` past v2.1.112** without confirming the new version works on Android's Bionic libc. Starting from v2.1.113 it uses pre-compiled native binaries (glibc-only).

2. **`libnode.so` is not in the repo.** The build needs it at `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libnode.so` (from nodejs-mobile v18.20.4). CI downloads it automatically; local builds require a manual step.

3. **Node.js can only be started once per process** (hard constraint from libnode.so). `NodeEngine.kt` enforces this with a `started` flag. Any retry logic must happen inside `bridge.js` itself via the `waitForRetry` polling loop.

4. **Bridge config is written before each `startBridge()` call** (`bridge_config.json` in `filesDir`). `bridge.js` reads it fresh per message, so changing the provider mid-session affects the next message only.

5. **No PTY.** The app uses `claude --print` (one process per user message). There is no interactive readline, no shell, no PTY allocation. Ctrl+C sends SIGTERM to the current child process. Arrow keys and Tab are sent as escape sequences but their effect depends on what Claude Code does with them in `--print` mode.

6. **Signing.** Debug APKs use `.debug` suffix (`com.claudecodesetup.debug`). Release signing config reads from `local.properties` (never committed). CI uses GitHub Secrets.

7. **`AppPreferences.MODE_GEMINI` exists** as a constant but the actual routing uses `MODE_PROXY` for all non-Anthropic providers. Gemini is just another proxy-mode provider. The `MODE_GEMINI` constant appears unused in `ClaudeService`/`NodeBridgeManager` — only `MODE_SUBSCRIPTION` vs `MODE_PROXY` matters for behavior.

8. **Provider list order in `Providers.ALL`**: `GEMINI, OPENROUTER, DEEPSEEK, KIMI, NVIDIA_NIM, META_LLAMA, OLLAMA` — Gemini is first (recommended default).

9. **`libnode-launcher.so` can only run scripts via `-e`** — loading a script file by path (positional arg, `--input-type=module`, or any file extension) silently exits with code 1 on Android. Only `spawn(LAUNCHER, ['-e', code])` works. `bridge.js` uses this to bootstrap cli.js via `import('file://...')` inside the eval string.

10. **`cli.js` must be patched after install** — `patchCliJsForAndroid()` in `bridge.js` replaces all 23 Unicode property escape regex literals (`/\p{L}/u` etc.) with explicit code-point ranges, because nodejs-mobile v18.20.4's V8 build doesn't support them. **If you clear only the app cache (not data), the patch won't re-run.** Full data clear → reinstall is needed to re-apply the patch to a fresh cli.js download.

11. **`tryOptimize()` must only match short system prompts** — The function checks the system prompt text for patterns like "title"+"generate" to short-circuit housekeeping calls. Claude Code's real user-message system prompt is ~25 KB and also contains these words. Always keep the `if (sys.length > 800) return null;` guard at the top of `tryOptimize()`. Removing it causes every real user message to be answered with "Claude Code Session" instead of being forwarded to the provider.

12. **Two layers of `\p{}` patching are needed** — Static regex literals (compiled at parse time) are patched by `patchCliJsForAndroid()` at install time. Dynamic `new RegExp(pattern, 'u')` calls (inside function bodies, only executed at runtime in `--print` mode) are caught by a global `RegExp` shim injected into the eval bootstrap. Both layers are required: the static patch prevents a parse-time SyntaxError during `import()`, and the shim prevents runtime crashes during `--print` initialization. When `[regex-compat]` lines appear in `!log` after a device test, add those patterns to `patchCliJsForAndroid()` and remove the shim fallback for them.

13. **Conversation history is injected as a formatted transcript** — `buildMessageWithHistory()` prepends prior turns in `Human: / Assistant:` format to each `--print` spawn. The model sees them as part of the user message, not as true multi-turn API messages. This works but uses more tokens per turn. Cap is `MAX_HISTORY = 20` messages. `!clear` resets it; context is lost when the socket closes.

14. **`Intl` is missing from nodejs-mobile v18.20.4** — The build has no ICU, so `global.Intl` is undefined. An `intlShim` stub (defined at module scope in `bridge.js`) is injected into every eval bootstrap before `import(cli.js)`. If you see `[intl-shim] installing` in `!log`, the shim is running. If a specific `Intl` method crashes at runtime, extend the stub in `intlShim`.

15. **`regexpShim` and `intlShim` must be module-scope** — They are injected as strings into eval bootstraps from both `runMessage()` and the `!test-cli` diagnostic handler. If they were local to `runMessage()`, the `!test-cli` step 5 callback would throw `ReferenceError: regexpShim is not defined` the moment step 4 completes, crashing the Node.js process and restarting the Android app.

16. **Never use `painterResource(R.mipmap.ic_launcher)` in Compose** — On API 26+ devices, `ic_launcher` resolves to an `<adaptive-icon>` XML which is not a rasterized asset. `painterResource()` throws `IllegalArgumentException`. Always render adaptive icons via `ContextCompat.getDrawable()` + Android `Canvas` onto a `Bitmap`, then use `bitmap.asImageBitmap()`.

17. **OSC thinking sequences are ephemeral — never buffer them** — `\x1b]9;thinking-start\x07` and `\x1b]9;thinking-done\x07` are UI state signals only. `ClaudeSession.appendOutput()` strips them before storing so they are never replayed to a reconnecting `TerminalActivity`. The real-time `onOutput` callback receives the original data (live thinking indicator still works). If you add new OSC state sequences, strip them in `appendOutput` too.

18. **`thinking-done` must fire before the first stdout byte** — In `runMessage()`, a `thinkingDoneSent` flag sends `\x1b]9;thinking-done\x07` on the first data event of `child.stdout`. This transitions `chatState` from THINKING → RESPONDING before any text arrives. Without this, all model output is discarded (terminal ignores data while THINKING). The `handleOSC thinking-done` in `index.html` is guarded against double-invocation with `if(chatState!=='RESPONDING')`.
