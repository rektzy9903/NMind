# Nexus Mind

> Run Claude Code on Android — no terminal, no manual installs, no tech knowledge required.

[![Build APK](https://github.com/fahmi304/Nexus-Mind/actions/workflows/build.yml/badge.svg)](https://github.com/fahmi304/Nexus-Mind/actions/workflows/build.yml)

**[Bahasa Malaysia](#bahasa-malaysia-)**

---

## What is this?

Nexus Mind is an Android app that lets you use [Claude Code](https://claude.ai/code) — Anthropic's AI coding assistant — directly on your phone. Everything is automatic. You never touch a terminal.

### User experience in 3 steps:
1. Install the APK
2. Pick how you want to log in
3. Claude Code is running

---

## Requirements

| Item | Minimum |
|---|---|
| Android version | Android 10 (API 29) or higher |
| Free storage | 1.5 GB |
| RAM | 2 GB recommended |
| Internet | Required for setup (~500 MB) |

**Supported CPU architectures:** arm64-v8a (all modern Android phones), armeabi-v7a (older 32-bit phones)

---

## Install guide (for beginners)

### Step 1 — Download the APK

Download `NexusMind.apk` from the [Releases page](../../releases).

### Step 2 — Allow unknown sources

Android blocks apps from outside the Play Store by default. You need to allow it once.

**Android 10 / 11:**
1. Open **Settings**
2. Go to **Apps** → **Special app access** → **Install unknown apps**
3. Find your browser (e.g. Chrome) and tap it
4. Turn on **Allow from this source**

**Android 12 / 13 / 14:**
1. Open **Settings**
2. Go to **Privacy** → **Special app access** (or search "Install unknown apps")
3. Find your file manager or browser
4. Enable **Allow from this source**

### Step 3 — Install the APK

1. Open your file manager
2. Find the downloaded `NexusMind.apk`
3. Tap it
4. Tap **Install**

### Step 4 — Open the app

Tap **Open** after install, or find **Nexus Mind** in your app drawer.

### Step 5 — Follow the setup wizard

The app will set everything up automatically. Connect to WiFi first — it downloads about 500 MB on first launch.

---

## Choosing a provider

When setup finishes, the app asks how you want to use Claude Code.

### Option A — Claude Subscription
If you have a Claude.ai account (paid or free trial), tap **"Yes — use my account"**. The app opens your browser to log in, then brings you straight to the terminal.

### Option B — API key (all providers supported)

The app includes a protocol proxy that lets Claude Code work with any OpenAI-compatible provider. All providers below are supported out of the box.

| Provider | Free tier | Speed | Malaysia | Sign up |
|---|---|---|---|---|
| **Google Gemini** ⭐ | 1,500 req/day | Fast | Works great | [aistudio.google.com](https://aistudio.google.com) |
| **Groq** | 14,400 req/day | Very fast | Works great | [console.groq.com](https://console.groq.com) |
| OpenRouter | 50 req/day | Fast | Works | [openrouter.ai](https://openrouter.ai) |
| DeepSeek | Pay per token | Fast | Works | [platform.deepseek.com](https://platform.deepseek.com) |
| Kimi (Moonshot) | Limited | Fast | Works | [platform.moonshot.cn](https://platform.moonshot.cn) |
| NVIDIA NIM | 40 req/min | Fast | SMS required | [build.nvidia.com](https://build.nvidia.com/models) |
| Meta Llama API | Limited | Fast | May work | [llama.developer.meta.com](https://llama.developer.meta.com) |
| **Anthropic API** | Pay per token | Fast | Works | [console.anthropic.com](https://console.anthropic.com/settings/api-keys) |
| Ollama (local) | Unlimited | On-device | No internet needed | [ollama.com](https://ollama.com) (PC required) |

**For Malaysian users:** We strongly recommend **Google Gemini** (1,500 free requests/day, Google account only, no SMS) or **Groq** (14,400 free requests/day, fastest inference).

---

## FAQ

**Q: Does the app need root?**
No. It uses Android's app sandbox.

**Q: Does it drain my battery?**
The proxy runs as a foreground service with a wake lock. You'll see a persistent notification "Claude Code is running". Tap Stop in that notification to close everything.

**Q: Can I use it offline?**
Setup requires internet. After setup, you still need internet to talk to the AI (unless using Ollama on a local PC).

**Q: Why is the first setup so large (500 MB)?**
The app downloads a lightweight Linux environment, Node.js, and Claude Code. This only happens once — after that, launches are instant.

**Q: My API key stopped working.**
Check that your provider hasn't hit a rate limit. Tap **Switch** in the terminal to pick a different provider or re-enter your key.

**Q: The app shows "Proxy stopped".**
Tap the **Restart Proxy** option in the ⋮ menu inside the terminal, or restart the app.

---

## Building the APK

### Option A — GitHub Actions (recommended, no PC tools needed)

Every push to `main` automatically builds a debug APK for free using GitHub Actions.

**Steps to get your APK:**

1. Fork or push this repo to GitHub
2. Go to the **Actions** tab in your GitHub repo
3. Click the latest **"Build APK"** workflow run
4. Scroll down to **Artifacts** and download `NexusMind-debug-N`
5. Unzip the download — you'll find `app-debug.apk` inside
6. Transfer it to your Android phone and install

The first build takes ~4 minutes. All future builds are cached and take ~1–2 minutes.

### Option B — Release a signed APK via GitHub

For a proper signed APK that auto-appears in GitHub Releases:

**One-time setup (on any PC with Java):**
```bash
keytool -genkey -v \
  -keystore release.jks \
  -alias claudecode \
  -keyalg RSA -keysize 2048 -validity 10000
# Answer the questions (name, org, etc.)

# Base64-encode the keystore
base64 release.jks   # Linux
# base64 -i release.jks   # Mac
```

**Add 4 secrets to GitHub** (repo → Settings → Secrets → Actions):

| Secret name | Value |
|---|---|
| `KEYSTORE_BASE64` | The base64 string from above |
| `KEYSTORE_PASSWORD` | The store password you chose |
| `KEY_ALIAS` | `claudecode` |
| `KEY_PASSWORD` | The key password you chose |

**Publish a release:**
```bash
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions will build the signed APK and create a Release page with a download link automatically.

### Option C — Local build (requires Android Studio)

```bash
git clone https://github.com/fahmi304/Nexus-Mind
cd Nexus-Mind
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## Architecture

The app ships an embedded Node.js runtime (`libnode.so` via Android JNI — no proot, no Linux container, no Termux). Everything runs natively inside the app's sandbox.

```
┌──────────────────────────────────────────────┐
│                 Android App                  │
│   SplashActivity → SetupActivity             │
│   ComposeActivity (login flow)               │
│   HomeActivity → TerminalActivity            │
├──────────────────────────────────────────────┤
│             ClaudeService (foreground)       │
│   TCP socket sessions  ·  WakeLock           │
├────────────────────┬─────────────────────────┤
│  Node.js (JNI)     │  Protocol Proxy         │
│  bridge.js         │  Anthropic → OpenAI     │
│  port 8083         │  port 8082              │
├────────────────────┴─────────────────────────┤
│   WebView Terminal (hand-rolled ANSI emul.)  │
│   JavaScript ↔ Kotlin @JavascriptInterface   │
└──────────────────────────────────────────────┘
```

**How it works:**
1. On first launch, `bridge.js` downloads `claude-code` from npm (~500 MB, once only)
2. Each message spawns `claude --print --output-format stream-json` — no persistent process
3. For non-Anthropic providers, a protocol proxy converts Anthropic API format → OpenAI format on the fly
4. For Anthropic API keys, requests are forwarded directly (no conversion needed)
5. For Claude subscription users, OAuth 2.0 + PKCE handles authentication

---

## Contributing

Pull requests are welcome. Please open an issue first to discuss major changes.

Bug reports: use the [GitHub issue template](.github/ISSUE_TEMPLATE/bug_report.md).

---

## License

MIT

---

# Bahasa Malaysia 🇲🇾

## Apa itu Nexus Mind?

Nexus Mind ialah aplikasi Android yang membolehkan anda menggunakan Claude Code — pembantu pengkodan AI daripada Anthropic — terus di telefon anda. Semua dilakukan secara automatik. Anda tidak perlu buka terminal langsung.

### Pengalaman pengguna dalam 3 langkah:
1. Pasang APK
2. Pilih cara log masuk
3. Claude Code terus berjalan

---

## Keperluan

| Perkara | Minimum |
|---|---|
| Versi Android | Android 10 atau lebih baharu |
| Ruang storan bebas | 1.5 GB |
| RAM | 2 GB disyorkan |
| Internet | Diperlukan semasa pemasangan (~500 MB) |

---

## Panduan pemasangan (untuk pemula)

### Langkah 1 — Muat turun APK

Muat turun `NexusMind.apk` dari [halaman Releases](../../releases).

### Langkah 2 — Benarkan pemasangan dari sumber luar

Android menghalang aplikasi dari luar Play Store secara lalai. Anda perlu benarkan sekali sahaja.

**Android 10 / 11:**
1. Buka **Tetapan**
2. Pergi ke **Aplikasi** → **Akses aplikasi khas** → **Pasang apl tidak diketahui**
3. Cari pelayar anda (contoh: Chrome) dan ketik
4. Hidupkan **Benarkan dari sumber ini**

**Android 12 / 13 / 14:**
1. Buka **Tetapan**
2. Pergi ke **Privasi** → **Akses aplikasi khas**
3. Cari pengurus fail atau pelayar anda
4. Hidupkan **Benarkan dari sumber ini**

### Langkah 3 — Pasang APK

1. Buka pengurus fail anda
2. Cari `NexusMind.apk` yang dimuat turun
3. Ketik fail tersebut
4. Ketik **Pasang**

### Langkah 4 — Buka aplikasi

Ketik **Buka** selepas pemasangan, atau cari **Nexus Mind** dalam senarai aplikasi.

### Langkah 5 — Ikut panduan persediaan

Aplikasi akan sediakan semua secara automatik. Sambungkan ke WiFi dahulu — ia memuat turun kira-kira 500 MB pada pelancaran pertama.

---

## Memilih penyedia

Apabila persediaan selesai, aplikasi akan tanya bagaimana anda mahu menggunakan Claude Code.

### Pilihan A — Langganan Claude
Jika anda ada akaun Claude.ai (berbayar atau percubaan percuma), ketik **"Ya — guna akaun saya"**.

### Pilihan B — Kunci API (semua penyedia disokong)

| Penyedia | Pelan percuma | Kelajuan | Malaysia | Daftar |
|---|---|---|---|---|
| **Google Gemini** ⭐ | 1,500 req/hari | Pantas | Sangat sesuai | [aistudio.google.com](https://aistudio.google.com) |
| **Groq** | 14,400 req/hari | Sangat pantas | Sangat sesuai | [console.groq.com](https://console.groq.com) |
| OpenRouter | 50 req/hari | Pantas | Boleh guna | [openrouter.ai](https://openrouter.ai) |
| DeepSeek | Bayar per token | Pantas | Boleh guna | [platform.deepseek.com](https://platform.deepseek.com) |
| Kimi (Moonshot) | Terhad | Pantas | Boleh guna | [platform.moonshot.cn](https://platform.moonshot.cn) |
| NVIDIA NIM | 40 req/min | Pantas | Pengesahan SMS | [build.nvidia.com](https://build.nvidia.com/models) |
| Meta Llama API | Terhad | Pantas | Mungkin boleh | [llama.developer.meta.com](https://llama.developer.meta.com) |
| **Anthropic API** | Bayar per token | Pantas | Boleh guna | [console.anthropic.com](https://console.anthropic.com/settings/api-keys) |
| Ollama (lokal) | Tanpa had | Dalam peranti | Tiada internet | [ollama.com](https://ollama.com) (PC diperlukan) |

**Untuk pengguna Malaysia:** Kami sangat mengesyorkan **Google Gemini** (1,500 permintaan percuma sehari, akaun Google sahaja, tanpa SMS) atau **Groq** (14,400 permintaan percuma sehari, inferens terpantas).

---

## Soalan Lazim

**S: Adakah aplikasi ini memerlukan root?**
Tidak. Ia menggunakan sandbox aplikasi Android standard.

**S: Adakah ia menghabiskan bateri?**
Proksi berjalan sebagai perkhidmatan latar depan. Anda akan nampak notifikasi "Claude Code sedang berjalan". Ketik Berhenti dalam notifikasi tersebut untuk tutup semua.

**S: Mengapa pemasangan pertama sangat besar (500 MB)?**
Aplikasi memuat turun persekitaran Linux ringan, Node.js, dan Claude Code. Ini hanya berlaku sekali sahaja — selepas itu, pelancaran adalah serta-merta.

**S: Kunci API saya tidak berfungsi.**
Semak sama ada penyedia anda telah mencapai had kadar. Ketik **Switch** dalam terminal untuk pilih penyedia lain atau masukkan semula kunci anda.

---

## Lesen

MIT
