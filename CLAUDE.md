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
│   │       ├── SplashActivity.kt     — routing: Setup → LoginFlow → Terminal
│   │       ├── SetupActivity.kt      — first-run: starts Node, polls setup.log
│   │       ├── LoginFlowActivity.kt  — provider/API-key wizard (5 screens)
│   │       ├── TerminalActivity.kt   — WebView terminal + session tabs
│   │       ├── SettingsActivity.kt   — change provider, reset, language
│   │       ├── NodeEngine.kt         — Kotlin singleton; wraps JNI nativeStart()
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
SetupActivity         LoginFlowActivity           TerminalActivity
(Node install)        (5-screen wizard)            (sessions + WebView)
                                                         ↕
                                                   SettingsActivity
```

`SplashActivity` always decides the next screen based on two prefs: `isNodeSetupComplete()` and `isProviderConfigured()`.

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

### Known gaps / TODO
- `DownloadManager.kt` exists with resumable download + npm version fetching, but `fetchLatestClaudeVersion()` is not used — version is always the pinned constant. The class is partially unused.
- `ProvidersRepository.REMOTE_URL` is empty — live provider updates are wired but disabled.
- No Android unit or instrumentation tests written.
- `BootReceiver` exists but doesn't automatically restart a session after boot (only starts the service).
- `SettingsActivity` references `DownloadManager.PINNED_CLAUDE_VERSION` for display but the installed version tracking (`getInstalledClaudeVersion`) is never written after install.
- No `CHANGELOG.md` (referenced in `release.yml`).
- `build.gradle` ABI filter is `arm64-v8a, armeabi-v7a` only — x86/x86_64 not supported despite README mentioning them.
- README still has `YOUR_USERNAME` placeholders in GitHub URLs.

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
