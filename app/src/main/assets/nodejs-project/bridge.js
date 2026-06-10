'use strict';

/**
 * bridge.js — runs inside the embedded Node.js runtime (libnode.so via JNI).
 *
 * Responsibilities:
 *   1. First run / retry: download @anthropic-ai/claude-code directly from the
 *      npm registry (uses Node.js built-in https + Android's /system/bin/tar).
 *      After failure, waits for Kotlin to clear the "setup_failed" sentinel file
 *      (happens when the user taps "Try again") then re-runs the install loop.
 *   2. Once installed: open TCP port 8083. Each connection spawns one
 *      node cli.js process via the provided launcher binary.
 *
 * argv layout (set by NodeBridgeManager.kt):
 *   argv[0] = "node"
 *   argv[1] = <path>/bridge.js
 *   argv[2] = <filesDir>
 *   argv[3] = <nativeLibDir>/libnode-launcher.so
 */

// Hot-load build stamp. BUMP THIS STRING on every push that touches bridge.js so
// !hotload can prove which version actually loaded (the GitHub raw CDN serves
// ~5-min-stale copies; this is the ground-truth marker, not the CDN timestamp).
const BRIDGE_BUILD = 'b97-rtk-recency-budget';

const net   = require('net');
const http  = require('http');
const path  = require('path');
const fs    = require('fs');
const https = require('https');
const { spawn } = require('child_process');

// ─── Paths ────────────────────────────────────────────────────────────────────

const FILES_DIR  = process.argv[2] || '/data/data/com.claudecodesetup/files';
const LAUNCHER   = process.argv[3] || process.execPath;
const NATIVE_DIR = path.dirname(LAUNCHER);  // directory holding libnode.so

const NPM_PREFIX  = path.join(FILES_DIR, 'npm-global');
const CLAUDE_CLI  = path.join(
    NPM_PREFIX, 'lib', 'node_modules',
    '@anthropic-ai', 'claude-code', 'cli.js'
);
// Proot Ubuntu engine (P2): claude-code 2.1.160 installed by !setup-engine into
// /opt/node inside the rootfs. cli.js path is GUEST-side (inside proot), fixed by
// npm's default global prefix (/opt/node). proot (claude-code 2.1.160 on glibc) is
// the only engine — the legacy 2.1.112/libnode engine + the engine_mode dev flag
// were deleted in P4 (2026-06-03).
const GUEST_CLI     = '/opt/node/lib/node_modules/@anthropic-ai/claude-code/cli.js';
const GUEST_NODE    = '/opt/node/bin/node';
// The npm-installed launcher symlink (/opt/node/bin/claude → ../lib/node_modules/
// .../cli.js). We invoke THIS by absolute path rather than `node <cli.js>` directly:
// 2.1.160's package layout / global prefix can put cli.js somewhere other than the
// guessed GUEST_CLI path (on-device the literal path MODULE_NOT_FOUND'd), but the
// launcher is what `!setup-engine` step [6] `claude --version` proved works. It's a
// `#!/usr/bin/env node` script — the kernel honours the shebang and PATH (which has
// /opt/node/bin) resolves node. Absolute argv[0] needs no shell, so the user message
// stays a separate argv element with zero quoting risk.
const GUEST_CLAUDE  = '/opt/node/bin/claude';
// Engine is always proot (claude-code 2.1.160 on glibc). The legacy 2.1.112/libnode
// engine + the engine_mode dev flag were deleted in P4; the no-op getEngineMode()
// shim and its always-true call-site guards were removed 2026-06-04 (dead-code clean).
// Tool-deferral toggle — own flag file (bridge_config.json is Kotlin-owned and
// gets overwritten on restart, so bridge-side toggles persist separately, same
// as engine_mode). DEFAULT ON as of defer v2 — proactive pre-selection
// (proactiveToolPick) makes it weak-model-safe, so it saves tokens out of the box
// for every OAI provider (Anthropic passthrough is excluded regardless). Only an
// explicit `!defer off` disables it.
const DEFER_FILE    = path.join(FILES_DIR, 'defer_tools');
function getDeferTools() {
    try { return fs.readFileSync(DEFER_FILE, 'utf8').trim() !== 'off'; }  // file present → on unless 'off'
    catch (_) { return true; }                                            // no file (fresh install) → default ON
}
// Warm-session toggle — ONE long-lived `claude --print --input-format stream-json`
// per chat tab, fed NDJSON over a kept-open stdin, instead of a fresh spawn per
// message. Kills the ~30s cold start (proot boot + node + claude init); proven
// 21.3× faster on the 2nd turn (b47 probe) and verified on-device (b48). Own flag
// file (same reason as defer_tools/engine_mode — bridge_config.json is Kotlin-
// owned). DEFAULT ON (b51) now that it's verified; `!warm off` opts out per tab
// (falls back to the untouched single-shot spawn-per-message path).
const WARM_FILE     = path.join(FILES_DIR, 'warm_session');
function getWarmMode() {
    try { return fs.readFileSync(WARM_FILE, 'utf8').trim() !== 'off'; }
    catch (_) { return true; }
}
const CONFIG_FILE   = path.join(FILES_DIR, 'bridge_config.json');
const SETUP_LOG     = path.join(FILES_DIR, 'setup.log');
const SETUP_DONE    = path.join(FILES_DIR, 'setup_done');
const SETUP_FAILED  = path.join(FILES_DIR, 'setup_failed');
const SESSION_FILE  = path.join(FILES_DIR, 'last_session.json');
const SESSIONS_DIR  = path.join(FILES_DIR, 'sessions');
const CWD_FILE      = path.join(FILES_DIR, 'last_cwd');
const BIN_DIR       = path.join(FILES_DIR, 'bin');
// CONFIRM_FILE (auto_approve.json) removed in P4 — the always-allow list was the
// legacy permission apparatus; proot runs bypassPermissions, no list needed.

// ─── Package catalog ──────────────────────────────────────────────────────────
// Static ARM64 Android binaries and npm packages installable via !install.
// Binary entries: download to BIN_DIR, chmod +x.
// npm entries: npm install --prefix NPM_PREFIX.
const PACKAGE_CATALOG = {
    // ── Static ARM64 binaries ──
    'busybox': {
        desc: 'BusyBox — wget, tar, grep, sed, awk, find, gzip, nano and 300+ Unix tools in one binary',
        size: '~1.1 MB',
        url: 'https://busybox.net/downloads/binaries/latest-stable/busybox-armv8l',
        bin: 'busybox', type: 'binary', post: 'busybox'
    },
    'curl': {
        desc: 'curl — transfer data with URLs (HTTPS, FTP, ...)',
        size: '~3 MB',
        url: 'https://github.com/moparisthebest/static-curl/releases/latest/download/curl-aarch64',
        bin: 'curl', type: 'binary'
    },
    'jq': {
        desc: 'jq — lightweight command-line JSON processor',
        size: '~1 MB',
        url: 'https://github.com/jqlang/jq/releases/latest/download/jq-linux-arm64',
        bin: 'jq', type: 'binary'
    },
    'python3': {
        desc: 'Python 3 — full interpreter + stdlib, ARM64 musl-static (resolves latest release automatically)',
        size: '~30 MB',
        type: 'archive',
        post: 'python3'
    },
    'git': {
        desc: 'git — native ARM64 binary via Termux packages (requires !install busybox first)',
        size: '~12 MB',
        type: 'termux-debs',
        packages: ['libandroid-support', 'libpcre2', 'git'],
        dest: 'opt/git',
        post: 'git-termux'
    },
    'go': {
        desc: 'Go — full toolchain (go build/run/test/get), ARM64 static (resolves latest release automatically)',
        size: '~100 MB',
        type: 'archive',
        post: 'go'
    },
    'zig': {
        desc: 'Zig — systems language + C/C++ compiler toolchain, ARM64 static (resolves latest release automatically)',
        size: '~100 MB',
        type: 'archive-xz',
        post: 'zig'
    },
    'ssh': {
        desc: 'OpenSSH — ssh, scp, sftp, ssh-keygen client tools (ARM64, requires !install busybox)',
        size: '~5 MB',
        type: 'termux-debs',
        packages: ['libandroid-support', 'openssl', 'openssh'],
        dest: 'opt/ssh',
        post: 'ssh-termux'
    },
    'ruby': {
        desc: 'Ruby — full interpreter + gems support (ARM64, requires !install busybox)',
        size: '~20 MB',
        type: 'termux-debs',
        packages: ['libandroid-support', 'openssl', 'libgmp', 'libyaml', 'libffi', 'ncurses', 'readline', 'ruby'],
        dest: 'opt/ruby',
        post: 'ruby-termux'
    },
    'clang': {
        desc: 'Clang/LLVM — C/C++ compiler + linker (ARM64, large ~300 MB, requires !install busybox)',
        size: '~300 MB',
        type: 'termux-debs',
        packages: ['libandroid-support', 'libllvm', 'clang'],
        dest: 'opt/clang',
        post: 'clang-termux'
    },
    // ── npm packages ──
    'serve': {
        desc: 'serve — zero-config static HTTP file server (serve . -p 8080)',
        type: 'npm', pkg: 'serve'
    },
    'http-server': {
        desc: 'http-server — simple HTTP server for any directory',
        type: 'npm', pkg: 'http-server'
    },
    'typescript': {
        desc: 'TypeScript — typed JavaScript compiler (tsc)',
        type: 'npm', pkg: 'typescript'
    },
    'nodemon': {
        desc: 'nodemon — auto-restart Node.js on file changes',
        type: 'npm', pkg: 'nodemon'
    },
    'prettier': {
        desc: 'prettier — opinionated code formatter (JS/TS/HTML/CSS/JSON)',
        type: 'npm', pkg: 'prettier'
    },
    'eslint': {
        desc: 'eslint — JavaScript/TypeScript linter',
        type: 'npm', pkg: 'eslint'
    },
    'pm2': {
        desc: 'pm2 — process manager: run Node apps in background',
        type: 'npm', pkg: 'pm2'
    },
    'express': {
        desc: 'express — fast minimalist web framework for Node.js',
        type: 'npm', pkg: 'express'
    },
    'axios': {
        desc: 'axios — promise-based HTTP client for Node.js',
        type: 'npm', pkg: 'axios'
    },
};

const PORT               = 8083;
const PROXY_PORT         = 8082;
const HOST               = '127.0.0.1';

// Prefix written before any diagnostic text sent from bridge to socket so that
// index.html termWrite can route it to a sys bubble regardless of chatState.
// Must NOT be used on OSC protocol messages (thinking-start, thinking-done, etc.).
const SYS_FENCE = '\x1b]9;sys-fence\x07';

// P4: regexpShim + intlShim (the \p{} RegExp compat + no-ICU Intl stubs injected
// into the legacy libnode eval bootstrap) are DELETED. The proot guest runs real
// glibc node 22, which has full Unicode property-escape regex + ICU — no shims.

// Tracks last provider HTTP 429 timestamp so the TCP close handler can show
// a rate-limit notification even when cli.js exits silently with code 0.
let lastRateLimitMs = 0;

// Broadcasts 429 countdown OSC to all active PTY sessions.
// Assigned once by openPrintSession at startup.
let on429CountdownNotify = null;

// ─── Persistent session store ──────────────────────────────────────────────────
// Keyed by string session ID sent from Kotlin as "SESSION:<id>\n" on connect.
// Sessions survive socket disconnects for PTY_IDLE_MS before the proc is killed.
const activeSessions = new Map();
const PTY_IDLE_MS    = 30 * 60 * 1000; // 30 minutes idle before killing proc

// P6: live Ubuntu PTY shells, keyed by session id (the 🐧 Ubuntu tab). Separate
// from activeSessions (the 💬 Claude print-mode chat). Each entry = { proc, socket,
// idleTimer }. The shell proc survives a socket disconnect (tab switch / brief drop)
// for PTY_IDLE_MS so the shell state (cwd, env, running job) persists on reattach.
const ubuntuPtys = new Map();

// ─── Logging ──────────────────────────────────────────────────────────────────

function log(msg) {
    const line = msg.endsWith('\n') ? msg : msg + '\n';
    try { fs.appendFileSync(SETUP_LOG, line); } catch (_) {}
    process.stdout.write(line);
}


// ─── Web search helper ───────────────────────────────────────────────────────
// Used by the OpenAI→Anthropic proxy's WebSearch interception: when claude-code
// emits a tool_call for WebSearch / web_search, the proxy runs a local
// DuckDuckGo lookup and injects the result instead of forwarding the tool_use.
// Returns { content, isError, newCwd }.
function webSearch(query, maxResults, cwd) {
    return new Promise(resolve => {
        if (!query) {
            resolve({ content: 'Error: query is required', isError: true, newCwd: cwd });
            return;
        }
        const encoded = encodeURIComponent(query);
        const req = https.get(
            'https://api.duckduckgo.com/?q=' + encoded + '&format=json&no_redirect=1&no_html=1&skip_disambig=1',
            { headers: { 'User-Agent': 'ClaudeCodeSetup/1.0 (Android)' } },
            res => {
                let body = '';
                res.setEncoding('utf8');
                res.on('data', c => { body += c; });
                res.on('end', () => {
                    try {
                        const data = JSON.parse(body);
                        const parts = [];
                        if (data.Abstract) parts.push('**' + (data.Heading || query) + '**\n' + data.Abstract);
                        if (data.Answer)   parts.push('Answer: ' + data.Answer);
                        const topics = (data.RelatedTopics || [])
                            .filter(t => t.Text)
                            .slice(0, maxResults)
                            .map(t => '• ' + t.Text + (t.FirstURL ? '\n  ' + t.FirstURL : ''));
                        if (topics.length) parts.push('Related:\n' + topics.join('\n'));
                        if (!parts.length) { fetchDdgHtml(query, maxResults, cwd, resolve); return; }
                        resolve({ content: parts.join('\n\n'), isError: false, newCwd: cwd });
                    } catch(_) { fetchDdgHtml(query, maxResults, cwd, resolve); }
                });
                res.on('error', () => fetchDdgHtml(query, maxResults, cwd, resolve));
            }
        );
        req.on('error', () => fetchDdgHtml(query, maxResults, cwd, resolve));
        req.setTimeout(10000, () => { req.destroy(); fetchDdgHtml(query, maxResults, cwd, resolve); });
    });
}

function fetchDdgHtml(query, maxResults, cwd, resolve) {
    const encoded = encodeURIComponent(query);
    const req = https.get(
        'https://html.duckduckgo.com/html/?q=' + encoded,
        { headers: { 'User-Agent': 'Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36' } },
        res => {
            let body = '';
            res.setEncoding('utf8');
            res.on('data', c => { if (body.length < 200000) body += c; });
            res.on('end', () => {
                const results = [];
                // #9: capture the REAL href from result__a (DDG wraps it as
                // /l/?uddg=<encoded-real-url>) instead of the truncated display URL.
                const linkRe    = /<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/g;
                const snippetRe = /<a[^>]+class="result__snippet"[^>]*>([\s\S]*?)<\/a>/g;
                const decode = s => s.replace(/&amp;/g,'&').replace(/&#x27;/g,"'").replace(/<[^>]+>/g,'').trim();
                const realUrl = href => {
                    try {
                        if (href.indexOf('uddg=') !== -1) {
                            const u = new URL(href, 'https://duckduckgo.com');
                            const dec = u.searchParams.get('uddg');
                            if (dec) return dec;
                        }
                    } catch (_) {}
                    return href.startsWith('//') ? 'https:' + href : href;
                };
                let lm, sm;
                const titles = [], snippets = [], urls = [];
                while ((lm = linkRe.exec(body)) && titles.length < maxResults) { urls.push(realUrl(lm[1])); titles.push(decode(lm[2])); }
                while ((sm = snippetRe.exec(body)) && snippets.length < maxResults) snippets.push(decode(sm[1]));
                for (let i = 0; i < Math.min(titles.length, maxResults); i++) {
                    results.push((titles[i] ? '**' + titles[i] + '**' : '') +
                        (urls[i] ? '\n' + urls[i] : '') +
                        (snippets[i] ? '\n' + snippets[i] : ''));
                }
                if (!results.length) resolve({ content: 'No results found for: ' + query, isError: false, newCwd: cwd });
                else resolve({ content: 'Search results for "' + query + '":\n\n' + results.join('\n\n'), isError: false, newCwd: cwd });
            });
            res.on('error', e => resolve({ content: 'Search failed: ' + e.message, isError: true, newCwd: cwd }));
        }
    );
    req.on('error', e => resolve({ content: 'Search failed: ' + e.message, isError: true, newCwd: cwd }));
    req.setTimeout(12000, () => { req.destroy(); resolve({ content: 'Search timed out', isError: true, newCwd: cwd }); });
}

// ─── Config ───────────────────────────────────────────────────────────────────

function readConfig() {
    try { return JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')); }
    catch (_) { return {}; }
}

function isClaudeInstalled() {
    return fs.existsSync(CLAUDE_CLI);
}

// ─── HTTP helpers ─────────────────────────────────────────────────────────────

function httpsGet(url, opts, redirectCount) {
    redirectCount = redirectCount || 0;
    return new Promise((resolve, reject) => {
        const req = https.get(url, opts || {}, res => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                if (redirectCount > 5) return reject(new Error('Too many redirects'));
                res.resume(); // discard body
                // #10: don't forward Authorization/Cookie to a DIFFERENT host on redirect.
                let nextOpts = opts;
                try {
                    const cur = new URL(url);
                    const nxt = new URL(res.headers.location, url);
                    if (nxt.host !== cur.host && opts && opts.headers) {
                        const h = Object.assign({}, opts.headers);
                        delete h.Authorization; delete h.authorization;
                        delete h.Cookie; delete h.cookie;
                        nextOpts = Object.assign({}, opts, { headers: h });
                    }
                } catch (_) {}
                return httpsGet(res.headers.location, nextOpts, redirectCount + 1)
                    .then(resolve).catch(reject);
            }
            resolve(res);
        });
        req.setTimeout(30000, () => {
            req.destroy();
            reject(new Error('Request timed out (30 s)'));
        });
        req.on('error', reject);
    });
}

function fetchJson(url) {
    return new Promise(async (resolve, reject) => {
        try {
            const res = await httpsGet(url, { headers: { 'Accept': 'application/json' } });
            if (res.statusCode !== 200) {
                res.resume();
                return reject(new Error('HTTP ' + res.statusCode + ' from ' + url));
            }
            let body = '';
            res.setEncoding('utf8');
            res.on('data', c => { body += c; });
            res.on('end', () => {
                try { resolve(JSON.parse(body)); }
                catch (e) { reject(new Error('JSON parse error: ' + e.message)); }
            });
            res.on('error', reject);
        } catch (e) { reject(e); }
    });
}

function downloadFile(url, dest) {
    return new Promise(async (resolve, reject) => {
        try {
            const res = await httpsGet(url);
            if (res.statusCode !== 200) {
                res.resume();
                return reject(new Error('HTTP ' + res.statusCode + ' downloading tarball'));
            }
            const out = fs.createWriteStream(dest);
            let received = 0;
            let lastLogMB = 0;
            res.on('data', chunk => {
                received += chunk.length;
                const mb = Math.floor(received / (1024 * 1024));
                if (mb >= lastLogMB + 5) {
                    lastLogMB = mb;
                    log(`  Downloaded ${mb} MB...\n`);
                }
            });
            res.pipe(out);
            out.on('finish', () => out.close(resolve));
            out.on('error', err => { try { fs.unlinkSync(dest); } catch (_) {} reject(err); });
            res.on('error', err => { try { fs.unlinkSync(dest); } catch (_) {} reject(err); });
        } catch (e) { reject(e); }
    });
}

// ─── Ubuntu-engine proot helpers (shared by !test-rootfs / !setup-engine) ────
// Build the proot argv for a guest command. Binds individual /dev nodes (NOT
// all of /dev — the Android emulator's goldfish/ashmem nodes make proot loop;
// see CLAUDE.md). PATH includes /opt/node/bin so node/npm/claude resolve.
function prootGuestArgv(rp, command, opts) {
    const a = [
        '-L', '--kernel-release=6.17.0-PRoot-Distro',
        '--link2symlink', '--kill-on-exit',
        '--rootfs=' + rp, '--root-id',
        '--bind=/dev/null', '--bind=/dev/zero',
        '--bind=/dev/random', '--bind=/dev/urandom', '--bind=/dev/tty',
        '--bind=/proc',
        // NOTE: proot-distro normally also binds /proc/self/fd → /dev/fd and
        // /proc/self/fd/{0,1,2} → /dev/std{in,out,err}. We DON'T: node spawns
        // proot with PIPE stdio, so /proc/self/fd/{0,1,2} resolve to "pipe:[…]"
        // (not a filesystem path) → proot "can't sanitize binding" + stalls.
        // Those binds are interactive-shell convenience only; the guest inherits
        // fds 0/1/2 directly regardless, so cat/npm/claude all work without them.
        '--bind=' + rp + '/proc/.loadavg:/proc/loadavg',
        '--bind=' + rp + '/proc/.stat:/proc/stat',
        '--bind=' + rp + '/proc/.uptime:/proc/uptime',
        '--bind=' + rp + '/proc/.version:/proc/version',
        '--bind=' + rp + '/proc/.vmstat:/proc/vmstat',
        '--bind=' + rp + '/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap',
        '--bind=' + rp + '/proc/.sysctl_inotify_max_user_watches:/proc/sys/fs/inotify/max_user_watches',
        '--bind=' + rp + '/sys/.empty:/sys/fs/selinux',
        '--bind=' + FILES_DIR + ':/root/.nexus',
        // Engine path: the bridge writes claude-code's settings.json, auto_approve,
        // and --continue session files under FILES_DIR/.claude. Bind it to the guest
        // HOME's /root/.claude so the GUEST claude (HOME=/root) reads the exact same
        // files — no duplicate config, sessions persist across turns. Harmless for
        // probes (just an extra bind). FILES_DIR/.claude is ensured to exist below.
        '--bind=' + path.join(FILES_DIR, '.claude') + ':/root/.claude',
        // claude-code 2.1.160 reads a top-level ~/.claude.json (onboarding/project
        // state) SEPARATE from the .claude/ dir. Missing → it spams stderr
        // "Claude configuration file not found at: /root/.claude.json" (3× per spawn,
        // leaks above the reply). Bind a host file (seeded in prootChild) so it's
        // found and persists on the Android side alongside .claude/.
        '--bind=' + path.join(FILES_DIR, '.claude.json') + ':/root/.claude.json',
    ];
    // ── P3a: workspace cwd + Android storage binds ──────────────────────────
    // Make the guest claude see the SAME filesystem as the legacy Bash tool. We
    // bind the standard Android storage roots into the guest at the IDENTICAL
    // path (host /sdcard → guest /sdcard, etc.) so any absolute path resolves the
    // same inside and outside proot — a file the user `$ cd`'d into or that claude
    // Reads/Writes lands in the real Android location. These mirror
    // permissions.additionalDirectories (inv 62). Only bind paths that exist —
    // proot refuses a non-existent host path ("can't sanitize binding").
    for (const root of ['/sdcard', '/storage/emulated/0', FILES_DIR]) {
        try { if (fs.existsSync(root)) a.push('--bind=' + root + ':' + root); } catch (_) {}
    }
    // Guest working directory: the user's current Android cwd (opts.workspace),
    // bound above, so Read/Write/Edit/Bash operate where the user expects. Falls
    // back to /root (guest HOME) when no workspace is given (probes, no cwd set).
    let guestCwd = '/root';
    if (opts && opts.workspace) {
        try {
            if (fs.existsSync(opts.workspace)) {
                // ensure the exact dir is bound (covered by a root bind above in
                // the common case, but a workspace outside those roots needs its own)
                if (!['/sdcard', '/storage/emulated/0', FILES_DIR].some(r => opts.workspace === r || opts.workspace.startsWith(r + '/')))
                    a.push('--bind=' + opts.workspace + ':' + opts.workspace);
                guestCwd = opts.workspace;
            }
        } catch (_) {}
    }
    a.push('--cwd=' + guestCwd);
    // Exec the guest command DIRECTLY — never via `/usr/bin/env -i`. proot only
    // mishandles execve-REPLACE (a process exec'ing in place); env -i does exactly
    // that, so it ENOSYS'd on EVERY guest call (proven on-device, paste aaI0q:
    // timeout's fork+exec of cat works, env's exec-replace doesn't). The guest's
    // environment (HOME/PATH/…) is supplied via proot's spawn env in runProotGuest
    // (proot propagates its environ to the guest), so no env wrapper is needed.
    // opts.rawExec is now a no-op (kept for call-site compatibility).
    return a.concat(command);
}

// Run a command inside the Ubuntu rootfs via node spawn (NOT ProcessBuilder —
// bionic scrubs LD_LIBRARY_PATH on PB children, see CLAUDE.md). Resolves
// {code, out}. onData (optional) streams combined stdout+stderr as it arrives.
// opts.verbose → proot -v1 (loud per-syscall trace, for diagnosis only).
//
// CRITICAL: proot is launched through an sh wrapper that closes every inherited
// fd >2 first. libnode runs inside the Android app, so the proot child inherits
// hundreds of framework fds (WebView, goldfish/ashmem graphics, mmap'd .apk).
// proot scans /proc/self/fd and ptrace-processes each → hangs on the emulator
// ("access to /dev/goldfish_pipe (fd N) won't be translated"). Closing them
// first gives proot a clean table (just stdio) so it starts instantly.
// Build the proot env + argv and SPAWN the guest, returning the live ChildProcess
// (NOT a promise). Shared by runProotGuest (buffered, for probes) and the proot
// ENGINE path in runMessage (streaming stdout + stdin.end + kill). opts.extraEnv
// merges over the base guest env ('' / null deletes a var); opts.verbose → -vN.
// Throws synchronously if spawn fails (caller wraps in try/catch).
function prootChild(command, opts) {
    opts = opts || {};
    const rp = path.join(FILES_DIR, 'ubuntu');
    const prootLibDir = path.join(FILES_DIR, '.proot-lib');
    try { fs.mkdirSync(prootLibDir, { recursive: true }); } catch (_) {}
    const tl = path.join(prootLibDir, 'libtalloc.so.2');
    try { fs.unlinkSync(tl); } catch (_) {}
    try { fs.symlinkSync(path.join(NATIVE_DIR, 'libtalloc.so'), tl); } catch (_) {}
    try { fs.mkdirSync(path.join(rp, 'tmp'), { recursive: true }); } catch (_) {}
    // Ensure the --bind=FILES_DIR/.claude:/root/.claude target exists (proot
    // refuses to bind a non-existent host path → "can't sanitize binding").
    try { fs.mkdirSync(path.join(FILES_DIR, '.claude'), { recursive: true }); } catch (_) {}
    // Seed ~/.claude.json (bound to /root/.claude.json) if absent so 2.1.160 stops
    // warning. hasCompletedOnboarding suppresses any first-run interactive flow.
    try {
        const cjPath = path.join(FILES_DIR, '.claude.json');
        if (!fs.existsSync(cjPath)) {
            fs.writeFileSync(cjPath, JSON.stringify({ hasCompletedOnboarding: true }) + '\n');
        }
    } catch (_) {}
    const env = Object.assign({}, process.env, {
            LD_LIBRARY_PATH: prootLibDir + ':' + NATIVE_DIR,
            PROOT_LOADER:    path.join(NATIVE_DIR, 'libproot-loader.so'),
            PROOT_LOADER_32: path.join(NATIVE_DIR, 'libproot-loader32.so'),
            PROOT_L2S_DIR:   path.join(rp, '.l2s'),
            PROOT_TMP_DIR:   path.join(rp, 'tmp'),
            // GUEST environment — proot propagates its environ to the guest, so
            // these reach the program directly (no `/usr/bin/env -i` wrapper, which
            // proot can't exec — see prootGuestArgv). HOME/PATH/etc. override any
            // Android values inherited from process.env above.
            HOME:   '/root',
            PATH:   '/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
            LANG:   'C.UTF-8',
            TERM:   'xterm-256color',
            TMPDIR: '/tmp',
            // Node's bundled OpenSSL fopen()s /etc/ssl/openssl.cnf at startup; under
            // proot that path returns ENOSYS ("Function not implemented") → node aborts
            // with an OpenSSL configuration error (seen on npm). /dev/null tells OpenSSL
            // to skip loading any config file (default settings are fine for TLS).
            OPENSSL_CONF: '/dev/null',
            // SECCOMP: leave it ON (do NOT set PROOT_NO_SECCOMP). The on-device
            // !fix-seccomp sweep (build b8) proved seccomp mode-2 "ptrace
            // acceleration (new syscall order)" is REQUIRED here: with
            // PROOT_NO_SECCOMP=1 (pure ptrace) the chdir + getcwd syscalls ENOSYS
            // on this kernel → npm/claude abort with uv_cwd. With seccomp ON,
            // chdir/getcwd translate AND in-guest fork+exec still works (the
            // canary `/bin/sh -c 'cd /root && /bin/pwd'` ran clean). The old
            // worry that seccomp breaks the execve→loader hand-off was actually
            // the env -i exec-replace issue (since removed), not seccomp.
            // Still overridable per probe via opts.extraEnv.
        });
        // Per-probe env overrides (diagnostic): test PROOT_* knobs from the
        // terminal without a rebuild. Set to null/'' to DELETE a base var.
        if (opts && opts.extraEnv) {
            for (const k in opts.extraEnv) {
                const v = opts.extraEnv[k];
                if (v === null || v === '') delete env[k]; else env[k] = String(v);
            }
        }
        // opts.verbose may be a boolean (→ -v1) or a number 0-9 (→ -vN).
        const vlvl = (opts && opts.verbose) ? (opts.verbose === true ? 1 : opts.verbose) : 0;
        const prootArgs = (vlvl ? ['-v', String(vlvl)] : []).concat(prootGuestArgv(rp, command, opts));
        // proot must run with NO inherited fds >2: libnode lives inside the Android
        // app process, so a spawned child inherits hundreds of framework fds
        // (WebView, goldfish_pipe/ashmem, mmap'd .apk) that lack FD_CLOEXEC; proot
        // ptrace-processes each and hangs. We can't close them in an `sh -c`
        // wrapper — Android's mksh aborts the close loop (it steps on its own
        // saved-stderr fd) and its redirection lexer rejects high fd numbers as
        // IO_NUMBER (`exec 104<&-` → tries to run command "104" → exit 127). So we
        // launch proot through libfdexec.so, a tiny native PIE exe that close(2)s
        // every fd >2 (bad fds are a harmless EBADF) then execv's proot. env is
        // inherited across the execv (LD_LIBRARY_PATH, PROOT_LOADER, …).
        // Launcher: normally libfdexec.so (close fds → execv proot). For the P6
        // dual-mode Ubuntu terminal (opts.pty), use libpty.so instead — it ALSO
        // closes inherited fds, but first allocates a controlling TTY via forkpty so
        // the guest bash gets a real terminal (vim/htop/line-editing). Same argv
        // contract: launcher then [prootBin, ...prootArgs]. node's pipe to libpty's
        // stdin/stdout is relayed to the PTY master (+ ESC 0xFE resize).
        const FDEXEC = path.join(NATIVE_DIR, (opts && opts.pty) ? 'libpty.so' : 'libfdexec.so');
        const prootBin = path.join(NATIVE_DIR, 'libproot.so');
        // cwd = rootfs dir: proot reads the HOST cwd at startup to seed its
        // virtual cwd. If the bridge's inherited host cwd ("/" or the app dir)
        // has no mapping inside the rootfs, proot's getcwd() virtualization
        // returns ENOSYS → node's uv_cwd aborts (hit on npm: process.cwd()).
        // Pointing the host cwd at the rootfs root maps to guest "/", and
        // --cwd=/root then chdirs the guest into /root, so getcwd() works.
        return spawn(FDEXEC, [prootBin].concat(prootArgs), { env, cwd: rp });
}

// Buffered convenience wrapper over prootChild — resolves {code, out} when the
// guest exits; onData (optional) streams combined stdout+stderr as it arrives.
function runProotGuest(command, timeoutMs, onData, opts) {
    return new Promise((resolve) => {
        let ch, out = '', done = false;
        try { ch = prootChild(command, opts); }
        catch (e) { return resolve({ code: null, out: 'spawn threw: ' + e.message }); }
        const onChunk = d => { const s = d.toString(); out += s; if (onData) { try { onData(s); } catch (_) {} } };
        ch.stdout.on('data', onChunk);
        ch.stderr.on('data', onChunk);
        ch.on('error', e => { if (done) return; done = true; resolve({ code: null, out: out + '\nspawn error: ' + e.message }); });
        const tid = setTimeout(() => { if (done) return; done = true; try { ch.kill('SIGKILL'); } catch (_) {} resolve({ code: null, out: out + '\n[timeout ' + timeoutMs + 'ms]' }); }, timeoutMs);
        ch.on('close', c => { if (done) return; done = true; clearTimeout(tid); resolve({ code: c, out }); });
    });
}

// Upload a diagnostic blob to a no-auth paste service; resolves the short URL
// (or '' on failure). Used so on-device proot/engine traces can be read in FULL
// off-device — an emulator screenshot crops the right 60% of every trace line,
// which is exactly why the Ubuntu-engine bring-up kept stalling on guesswork.
function uploadDiag(text) {
    return new Promise((resolve) => {
        try {
            const body = Buffer.from(String(text || '').slice(0, 400000), 'utf8');
            const req = https.request({
                hostname: 'paste.rs', path: '/', method: 'POST',
                headers: { 'Content-Type': 'text/plain', 'Content-Length': body.length },
            }, (res) => {
                let d = '';
                res.on('data', c => d += c);
                res.on('end', () => resolve((d || '').trim()));
            });
            req.on('error', () => resolve(''));
            req.setTimeout(15000, () => { try { req.destroy(); } catch (_) {} resolve(''); });
            req.write(body); req.end();
        } catch (_) { resolve(''); }
    });
}

// ─── Shared Ubuntu-engine install chain ──────────────────────────────────────
// The Node 22 + claude-code install/verify chain, factored out of !setup-engine so
// BOTH the terminal command AND the first-run auto-provisioner (SetupActivity) run
// the SAME debugged steps. `emit({level,msg,stage,pct})` reports progress:
//   level: 'stage'|'info'|'ok'|'warn'|'err'|'done'   pct: 0-100 (or null)
// Resolves {ok, version, error}. Idempotent (skips Node download if /opt/node works).
// Assumes the rootfs is ALREADY extracted (Kotlin UbuntuRootfsManager does that —
// node can't xz/tar); fails fast with error:'no-rootfs' if it isn't.
async function runEngineSetup(emit, seEnv) {
    seEnv = seEnv || {};
    const ge = (cmd, t, onData) => runProotGuest(cmd, t, onData, { extraEnv: seEnv });
    const E = (level, msg, stage, pct) => { try { emit({ level, msg, stage, pct }); } catch (_) {} };
    const rp = path.join(FILES_DIR, 'ubuntu');
    if (!fs.existsSync(path.join(rp, 'etc', 'os-release'))) {
        E('err', 'No Ubuntu rootfs found — extract it first.', 'rootfs', 0);
        return { ok: false, error: 'no-rootfs' };
    }
    // DNS for npm: base rootfs resolv.conf is often a dangling symlink.
    try { fs.unlinkSync(path.join(rp, 'etc', 'resolv.conf')); } catch (_) {}
    try { fs.writeFileSync(path.join(rp, 'etc', 'resolv.conf'), 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n'); } catch (_) {}
    const fail = (label, r) => E('err', label + ' (code=' + r.code + ')\n' + (String(r.out || '').trim().slice(-700) || '(no output)'), label, null);

    // 1) boot
    E('stage', 'Booting Ubuntu…', 'boot', 10);
    let r = await ge(['/usr/bin/cat', '/etc/os-release'], 60000);
    if (!/Ubuntu/i.test(r.out)) { fail('Boot failed', r); return { ok: false, error: 'boot' }; }
    const pretty = (r.out.match(/PRETTY_NAME="?([^"\n]+)/) || [])[1] || 'Ubuntu';
    E('ok', 'Booted: ' + pretty, 'boot', 15);

    // 2) write test
    E('stage', 'Checking filesystem…', 'write', 20);
    r = await ge(['/bin/sh', '-c', 'echo ok > /root/.wtest && echo ok > /tmp/.wtest && cat /root/.wtest /tmp/.wtest && rm -f /root/.wtest /tmp/.wtest'], 30000);
    if (r.code !== 0) { fail('Write test failed', r); return { ok: false, error: 'write' }; }
    E('ok', 'Filesystem writable', 'write', 25);

    // 3) Node 22 — reuse if present, else download .tar.gz + gunzip-in-node + extract
    E('stage', 'Installing Node…', 'node', 30);
    r = await ge(['/opt/node/bin/node', '--version'], 30000);
    if (r.code !== 0) {
        const nodeUrl = 'https://nodejs.org/dist/v22.22.3/node-v22.22.3-linux-arm64.tar.gz';
        const dest = path.join(FILES_DIR, 'node22.tar.gz');
        E('info', 'Downloading Node 22 (~30 MB)…', 'node', 35);
        try { await downloadFile(nodeUrl, dest); }
        catch (e) { E('err', 'Node download failed: ' + e.message, 'node', null); return { ok: false, error: 'node-dl' }; }
        E('info', 'Decompressing Node…', 'node', 45);
        const tarPath = path.join(FILES_DIR, 'node22.tar');
        try {
            const zlib = require('zlib');
            await new Promise((res, rej) => {
                fs.createReadStream(dest).pipe(zlib.createGunzip()).pipe(fs.createWriteStream(tarPath))
                  .on('finish', res).on('error', rej);
            });
        } catch (e) { E('err', 'Decompress failed: ' + e.message, 'node', null); try { fs.unlinkSync(dest); } catch (_) {} return { ok: false, error: 'gunzip' }; }
        try { fs.unlinkSync(dest); } catch (_) {}
        E('info', 'Extracting Node…', 'node', 50);
        r = await ge(['/bin/sh', '-c',
            'mkdir -p /opt && tar -xf /root/.nexus/node22.tar -C /opt && ' +
            'rm -rf /opt/node && mv /opt/node-v22.22.3-linux-arm64 /opt/node && ' +
            '/opt/node/bin/node --version'], 180000);
        try { fs.unlinkSync(tarPath); } catch (_) {}
        if (r.code !== 0) { fail('Node extract failed', r); return { ok: false, error: 'node' }; }
    }
    E('ok', 'Node ' + r.out.trim(), 'node', 55);

    // 3b) getcwd probe (diagnostic — npm aborts uv_cwd ENOSYS if cwd fails under proot)
    await ge(['/bin/sh', '-c', 'cd /root; /opt/node/bin/node -e "try{process.stdout.write(\'ok\')}catch(e){process.stdout.write(\'ERR \'+e.code)}"'], 30000);

    // 4) npm
    E('stage', 'Preparing installer…', 'npm', 60);
    r = await ge(['/bin/sh', '-c', 'npm --version'], 60000);
    if (r.code !== 0) { fail('npm failed', r); return { ok: false, error: 'npm' }; }
    E('ok', 'npm ' + r.out.trim(), 'npm', 62);

    // 5) install claude-code (stream tail so it doesn't look hung)
    E('stage', 'Installing Claude Code… (this can take a few minutes)', 'claude', 65);
    let lastMark = Date.now();
    r = await ge(['/bin/sh', '-c', 'npm i -g @anthropic-ai/claude-code 2>&1'], 600000,
        () => { const now = Date.now(); if (now - lastMark > 15000) { lastMark = now; E('info', '…still installing…', 'claude', null); } });
    if (r.code !== 0) { fail('Claude Code install failed', r); return { ok: false, error: 'claude-install' }; }
    E('ok', 'Claude Code installed', 'claude', 90);

    // 6) claude --version  (real acceptance) — persist the REAL guest version so
    // Settings → About shows it instead of the hardcoded constant (closes that TODO).
    E('stage', 'Verifying…', 'verify', 95);
    r = await ge(['/bin/sh', '-c', 'claude --version'], 60000);
    if (r.code !== 0) { fail('claude --version failed', r); return { ok: false, error: 'verify' }; }
    const versionRaw = r.out.trim();
    const version = (versionRaw.match(/[0-9]+\.[0-9]+\.[0-9]+/) || [versionRaw])[0];
    try { fs.writeFileSync(path.join(FILES_DIR, 'claude_version'), version); } catch (_) {}
    E('ok', 'Engine ready — ' + versionRaw, 'verify', 98);

    // 7) reclaim install caches (~400MB of npm/apt downloads + leftover tarballs)
    E('stage', 'Cleaning up…', 'cleanup', 99);
    await ge(['/bin/sh', '-c',
        'npm cache clean --force >/dev/null 2>&1 || true; ' +
        'apt-get clean >/dev/null 2>&1 || true; ' +
        'rm -rf /var/lib/apt/lists/* /tmp/*.tar.* /opt/*.tar.* 2>/dev/null || true'], 120000);
    E('done', 'Engine ready — ' + versionRaw, 'done', 100);
    return { ok: true, version: versionRaw };
}

// ─── Package install helpers ─────────────────────────────────────────────────

// Fetches the latest python-build-standalone musl ARM64 asset URL via GitHub API.
async function resolveLatestPythonUrl() {
    const API = 'https://api.github.com/repos/indygreg/python-build-standalone/releases/latest';
    const res = await httpsGet(API, { headers: { 'User-Agent': 'ClaudeCodeApp/1.0', 'Accept': 'application/vnd.github.v3+json' } });
    const chunks = [];
    await new Promise((resolve, reject) => {
        res.on('data', c => chunks.push(c));
        res.on('end', resolve);
        res.on('error', reject);
    });
    if (res.statusCode !== 200) throw new Error('GitHub API HTTP ' + res.statusCode);
    const data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    const asset = (data.assets || []).find(a =>
        a.name.includes('aarch64-unknown-linux-musl-install_only') && a.name.endsWith('.tar.gz'));
    if (!asset) throw new Error('No aarch64 musl install_only asset in latest python-build-standalone release');
    return asset.browser_download_url;
}

// Fetches the latest Go linux/arm64 tarball URL from go.dev JSON API.
async function resolveLatestGoUrl() {
    const res = await httpsGet('https://go.dev/dl/?mode=json',
        { headers: { 'User-Agent': 'ClaudeCodeApp/1.0' } });
    const chunks = [];
    await new Promise((resolve, reject) => {
        res.on('data', c => chunks.push(c));
        res.on('end', resolve);
        res.on('error', reject);
    });
    if (res.statusCode !== 200) throw new Error('go.dev HTTP ' + res.statusCode);
    const releases = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    const stable = releases.find(r => r.stable);
    if (!stable) throw new Error('No stable Go release found');
    const file = stable.files.find(f => f.os === 'linux' && f.arch === 'arm64' && f.kind === 'archive');
    if (!file) throw new Error('No linux/arm64 archive in Go release');
    return 'https://go.dev/dl/' + file.filename;
}

// Fetches the latest stable Zig linux-aarch64 tarball URL from ziglang.org JSON API.
async function resolveLatestZigUrl() {
    const res = await httpsGet('https://ziglang.org/download/index.json',
        { headers: { 'User-Agent': 'ClaudeCodeApp/1.0' } });
    const chunks = [];
    await new Promise((resolve, reject) => {
        res.on('data', c => chunks.push(c));
        res.on('end', resolve);
        res.on('error', reject);
    });
    if (res.statusCode !== 200) throw new Error('ziglang.org HTTP ' + res.statusCode);
    const index = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    // Pick latest stable (first non-master key)
    const version = Object.keys(index).find(k => k !== 'master');
    if (!version) throw new Error('No stable Zig version found');
    const entry = index[version]['aarch64-linux'];
    if (!entry || !entry.tarball) throw new Error('No aarch64-linux tarball in Zig ' + version);
    return entry.tarball;
}

// Fetches the Termux APT package index and returns download URLs for the named packages.
async function resolveTermuxUrls(packageNames) {
    const BASE = 'https://packages.termux.dev/apt/termux-main/';
    const PKG_URL = BASE + 'dists/stable/main/binary-aarch64/Packages.gz';
    const res = await httpsGet(PKG_URL);
    const chunks = [];
    await new Promise((resolve, reject) => {
        res.on('data', c => chunks.push(c));
        res.on('end', resolve);
        res.on('error', reject);
    });
    if (res.statusCode !== 200) throw new Error('Termux package index HTTP ' + res.statusCode);
    const text = await new Promise((resolve, reject) =>
        require('zlib').gunzip(Buffer.concat(chunks), (err, buf) =>
            err ? reject(err) : resolve(buf.toString('utf8'))));
    const index = {};
    let cur = {};
    for (const line of text.split('\n')) {
        if (line === '') {
            if (cur.Package && cur.Filename) index[cur.Package] = BASE + cur.Filename;
            cur = {};
        } else {
            const m = line.match(/^([^:]+):\s*(.*)/);
            if (m) cur[m[1]] = m[2];
        }
    }
    return packageNames.map(name => {
        if (!index[name]) throw new Error('Package not found in Termux index: ' + name);
        return { name, url: index[name] };
    });
}

// Extracts a Termux .deb file into destDir, stripping the Termux path prefix.
// Requires busybox (bb = path to busybox binary).
async function extractDeb(debPath, destDir, bb, env) {
    const tmpDir = debPath + '-tmp';
    fs.mkdirSync(tmpDir, { recursive: true });
    try {
        await new Promise((res, rej) => {
            const ar = spawn(bb, ['ar', 'x', debPath], { env, cwd: tmpDir });
            ar.on('error', e => rej(new Error('ar: ' + e.message)));
            ar.on('close', code => code === 0 ? res() : rej(new Error('ar exit ' + code)));
        });
        const dataTarName = fs.readdirSync(tmpDir).find(f => f.startsWith('data.tar'));
        if (!dataTarName) throw new Error('No data.tar in ' + path.basename(debPath));
        if (dataTarName.endsWith('.zst'))
            throw new Error(
                'Package uses zstd compression (.zst). zstd is not in busybox.\n' +
                'Try: !install busybox (if not installed) then retry. If still failing,\n' +
                'the Termux package has been recompressed — contact app support.');
        const dataTarPath = path.join(tmpDir, dataTarName);
        fs.mkdirSync(destDir, { recursive: true });
        // Termux path inside deb: ./data/data/com.termux/files/usr/{bin,lib,libexec,...}
        // --strip-components=6 strips: . / data / data / com.termux / files / usr
        await new Promise((res, rej) => {
            const tar = spawn(bb, ['tar', '-xf', dataTarPath, '-C', destDir, '--strip-components=6'],
                { env });
            tar.stderr.on('data', d => log('tar(deb): ' + d));
            tar.on('error', e => rej(new Error('tar: ' + e.message)));
            tar.on('close', code => code === 0 ? res() : rej(new Error('tar exit ' + code)));
        });
    } finally {
        try { fs.rmSync(tmpDir, { recursive: true }); } catch(_) {}
    }
}

// ─── Anthropic → OpenAI proxy (port 8082) ────────────────────────────────────
// Translates Anthropic Messages API calls (what Claude Code emits) into
// OpenAI Chat Completions format for providers like OpenRouter, NVIDIA NIM,
// Meta Llama, and Ollama. Handles both streaming and non-streaming responses.

function startProxyServer(onReady) {
    // Load the shared local_token once at server start for auth checks.
    // Requests are accepted when they carry either:
    //   x-local-token: <localToken>  (any authorised caller that obtained the token)
    //   Authorization: Bearer sk-ant-proxy000  (claude-code running inside this process)
    let proxyLocalToken = '';
    try { proxyLocalToken = fs.readFileSync(path.join(FILES_DIR, 'local_token'), 'utf8').trim().slice(0, 200); } catch(_) {}

    const proxy = http.createServer((req, res) => {
        // Log EVERY request that reaches the proxy so we can diagnose hangs.
        const apiKeyHeader = req.headers['x-api-key'] || '';
        const authHeader   = req.headers['authorization'] || '';
        log('[proxy-in] ' + req.method + ' ' + req.url +
            ' key=' + (apiKeyHeader || authHeader || '(none)').slice(0, 20) + '\n');

        // SDK health check — HEAD / is sent by the Anthropic SDK before each session.
        // Return 200 without auth so it doesn't flood the log with 401 entries.
        if (req.method === 'HEAD' && (req.url === '/' || req.url === '')) {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end();
            return;
        }

        // ── Auth gate ──────────────────────────────────────────────────────────
        // Reject any request that does not present valid credentials.
        const tokenHeader = req.headers['x-local-token'] || '';
        const hasValidToken = proxyLocalToken && tokenHeader === proxyLocalToken;
        // claude-code (Anthropic SDK) sends x-api-key, not Authorization: Bearer
        const hasProxyKey   = authHeader === 'Bearer sk-ant-proxy000'
                           || apiKeyHeader === 'sk-ant-proxy000';
        if (!hasValidToken && !hasProxyKey) {
            log('[proxy] 401 — missing or invalid auth on ' + req.method + ' ' + req.url + '\n');
            res.writeHead(401, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'unauthorized' }));
            return;
        }
        // ── End auth gate ──────────────────────────────────────────────────────

        // Log every incoming request so we can see if cli.js reaches the proxy at all.
        log('[proxy] ' + req.method + ' ' + req.url + '\n');

        // POST /v1/messages/count_tokens — estimate locally, no API call needed
        if (req.method === 'POST' && req.url.includes('/count_tokens')) {
            let body = '';
            req.on('data', chunk => { body += chunk; });
            req.on('end', () => {
                let tokens = 1000;
                try { tokens = estimateTokens(JSON.parse(body)); } catch (_) {}
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ input_tokens: tokens }));
            });
            req.on('error', () => {
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ input_tokens: 1000 }));
            });
            return;
        }

        // HEAD / OPTIONS on any /messages endpoint — CORS probe support
        if ((req.method === 'HEAD' || req.method === 'OPTIONS') && req.url.includes('/messages')) {
            res.writeHead(200, {
                'Content-Type': 'application/json',
                'Access-Control-Allow-Origin':  '*',
                'Access-Control-Allow-Methods': 'POST, GET, OPTIONS, HEAD',
                'Access-Control-Allow-Headers': 'Content-Type, Authorization, x-api-key, anthropic-version, anthropic-beta',
            });
            res.end('{}');
            return;
        }

        // POST /v1/messages — main Anthropic chat endpoint
        if (req.method === 'POST' && req.url.includes('/v1/messages')) {
            let body = '';
            req.on('data', chunk => { body += chunk; });
            req.on('end', () => {
                try {
                    const anthReq = JSON.parse(body);

                    // Short-circuit internal housekeeping requests locally
                    const mockText = tryOptimize(anthReq);
                    if (mockText !== null) {
                        const cfg   = readConfig();
                        const model = cfg.modelId || anthReq.model || '';
                        log('[opt] short-circuit internal request (model=' + (anthReq.model || '?') + ')\n');
                        if (anthReq.stream) {
                            sendMockStream(mockText, model, res);
                        } else {
                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify(mockAnthResponse(mockText, model)));
                        }
                        return;
                    }

                    // Pending image attach (one-shot). Real user request only —
                    // tryOptimize already short-circuited the housekeeping calls.
                    maybeInjectPendingImage(anthReq);

                    handleProxyRequest(anthReq, res);
                } catch (e) { proxyError(res, 400, e.message); }
            });
            req.on('error', e => proxyError(res, 500, e.message));
            return;
        }
        // GET /v1/models — Claude Code validates ANTHROPIC_MODEL against this list on startup.
        // In proxy mode ANTHROPIC_MODEL is always 'claude-3-5-sonnet-20241022', so return that
        // name here regardless of the actual provider model (cfg.modelId).
        // In subscription mode return the real configured model.
        if (req.method === 'GET' && req.url.startsWith('/v1/models')) {
            const mcfg = readConfig();
            const base = mcfg.mode === 'subscription'
                ? (mcfg.modelId || 'claude-3-5-sonnet-20241022')
                : 'claude-3-5-sonnet-20241022';
            // Advertise the provider's modelList ids too, so a dungeon Scout/Council can
            // set ANTHROPIC_MODEL to a real provider model and pass startup validation.
            const ids = [base];
            if (Array.isArray(mcfg.modelList)) mcfg.modelList.forEach(m => { if (m && ids.indexOf(m) === -1) ids.push(m); });
            // Dungeon multi-provider: advertise every "<providerId>::<modelId>" tag so a
            // member spawned on ANY configured provider passes claude-code startup validation.
            loadDungeonProviders().forEach(p => {
                if (!p || !Array.isArray(p.models)) return;
                p.models.forEach(mid => { const tag = p.id + '::' + mid; if (mid && ids.indexOf(tag) === -1) ids.push(tag); });
            });
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
                data: ids.map(id => ({ id, display_name: id, created_at: '' }))
            }));
            return;
        }
        // Any other endpoint — return 200 so Claude Code doesn't crash on startup probes
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end('{}');
    });

    proxy.on('error', err => {
        log('Proxy error: ' + err.message + ' — retrying in 3 s\n');
        setTimeout(() => startProxyServer(onReady), 3000);
    });

    proxy.listen(PROXY_PORT, HOST, () => {
        log('Proxy ready on ' + HOST + ':' + PROXY_PORT + '\n');
        if (onReady) onReady();
    });
}

function proxyError(res, code, msg) {
    log('[proxy-error] ' + code + ' — ' + msg + '\n');
    // Return 400 for all 5xx so claude-code doesn't retry provider errors indefinitely
    const httpCode = (code >= 500 && code < 600) ? 400 : code;
    try {
        res.writeHead(httpCode, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ type: 'error', error: { type: 'api_error', message: msg } }));
    } catch (_) {}
}

// ─── Pending-image injection ──────────────────────────────────────────────────
// When the user attaches an image in the terminal, TerminalActivity writes
// pending_image.b64 + pending_image.mime to filesDir. claude-code itself has
// no idea — it just sends the user's text message to our proxy. Here we
// intercept the outgoing /v1/messages request, find the last user message,
// and rewrite its content to a multimodal Anthropic block. Same shape the
// deleted runAgentic used to build (see f1.md Section 4).
//
// One-shot: files are deleted as soon as they're read, so call 1 of a turn
// gets the image and any tool-use round-trips on the same turn (calls 2/3/4)
// send plain text. The model still has the image in --continue history.
//
// Proxy-mode only: subscription mode bypasses 127.0.0.1:8082 and talks
// directly to api.anthropic.com, so this hook never fires. Picker is gated
// in TerminalActivity.pickImage() to refuse subscription mode.
function maybeInjectPendingImage(anthReq) {
    const b64Path  = path.join(FILES_DIR, 'pending_image.b64');
    const mimePath = path.join(FILES_DIR, 'pending_image.mime');
    if (!fs.existsSync(b64Path) || !fs.existsSync(mimePath)) return;
    let b64, mime;
    try {
        b64  = fs.readFileSync(b64Path, 'utf8').trim();
        mime = fs.readFileSync(mimePath, 'utf8').trim() || 'image/jpeg';
    } catch (e) {
        log('[img-inject] read failed: ' + e.message + '\n');
        try { fs.unlinkSync(b64Path); }  catch(_) {}
        try { fs.unlinkSync(mimePath); } catch(_) {}
        return;
    }
    // Always delete first — one-shot. Even if injection below fails, we
    // don't want a stuck file re-attaching to every future request.
    try { fs.unlinkSync(b64Path); }  catch(_) {}
    try { fs.unlinkSync(mimePath); } catch(_) {}

    if (!b64 || !Array.isArray(anthReq.messages) || anthReq.messages.length === 0) return;
    // Find the last PLAIN user message and rewrite its content.
    // #3: skip tool_result turns — prepending an image onto a tool_result message is
    // (a) dropped by anthToOai (the toolResults branch wins) and (b) an invalid mixed
    // Anthropic block. Fall back to the most recent normal user message instead.
    let idx = -1;
    for (let i = anthReq.messages.length - 1; i >= 0; i--) {
        const mm = anthReq.messages[i];
        if (mm.role !== 'user') continue;
        if (Array.isArray(mm.content) && mm.content.some(b => b && b.type === 'tool_result')) continue;
        idx = i; break;
    }
    if (idx === -1) return;
    const msg = anthReq.messages[idx];
    const imageBlock = { type: 'image', source: { type: 'base64', media_type: mime, data: b64 } };
    if (typeof msg.content === 'string') {
        msg.content = [imageBlock, { type: 'text', text: msg.content || 'What do you see in this image?' }];
    } else if (Array.isArray(msg.content)) {
        msg.content = [imageBlock, ...msg.content];
    } else {
        return;
    }
    log('[img-inject] attached ' + mime + ' (' + b64.length + ' b64 chars) to user msg #' + idx + '\n');
}

// ─── Optimization helpers ─────────────────────────────────────────────────────
// Short-circuit internal claude-code requests (title generation, follow-up
// suggestions, file-path extraction) locally — no API call, no quota spent.
// Mirrors the optimisation_handlers from the original free-claude-code project.

function getSystemText(anthReq) {
    if (!anthReq.system) return '';
    if (typeof anthReq.system === 'string') return anthReq.system;
    return (anthReq.system || []).filter(b => b.type === 'text').map(b => b.text).join('\n');
}

function estimateTokens(anthReq) {
    const sys  = getSystemText(anthReq);
    const msgs = (anthReq.messages || []).map(m =>
        typeof m.content === 'string' ? m.content :
        (m.content || []).filter(b => b.type === 'text').map(b => b.text).join('')
    ).join('');
    return Math.max(1, Math.ceil((sys.length + msgs.length) / 3.5));
}

function mockAnthResponse(text, model) {
    return {
        id: 'msg_opt_' + Date.now(), type: 'message', role: 'assistant',
        content: [{ type: 'text', text }],
        model: model || '',
        stop_reason: 'end_turn', stop_sequence: null,
        usage: { input_tokens: 10, output_tokens: Math.max(1, Math.ceil(text.length / 4)) },
    };
}

function sendMockStream(text, model, res) {
    const msgId = 'msg_opt_' + Date.now();
    const ev = (name, data) => {
        try { res.write('event: ' + name + '\ndata: ' + JSON.stringify(data) + '\n\n'); } catch (_) {}
    };
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });
    ev('message_start', { type: 'message_start', message: { id: msgId, type: 'message', role: 'assistant', content: [], model: model || '', stop_reason: null, usage: { input_tokens: 10, output_tokens: 0 } } });
    ev('content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } });
    ev('ping', { type: 'ping' });
    if (text) ev('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text } });
    ev('content_block_stop',  { type: 'content_block_stop', index: 0 });
    ev('message_delta', { type: 'message_delta', delta: { stop_reason: 'end_turn', stop_sequence: null }, usage: { output_tokens: Math.max(1, Math.ceil(text.length / 4)) } });
    ev('message_stop', { type: 'message_stop' });
    try { res.end(); } catch (_) {}
}

/**
 * Returns a mock reply string if the request is an internal claude-code
 * housekeeping call, or null if it should be forwarded to the provider.
 *
 * IMPORTANT: Only short-circuit when the system prompt is short (<= 800 chars).
 * Claude Code's main user-message system prompt is 20 KB+. Title/followup/
 * file-extraction prompts are brief focused instructions. Matching on the
 * full system prompt caused every real user message to be incorrectly
 * short-circuited (it contains words like "concise" and "title" too).
 */
function tryOptimize(anthReq) {
    // Any haiku-model request is a claude-code internal housekeeping call.
    // In proxy mode the user-facing model is always claude-3-5-sonnet-20241022,
    // so claude-code only picks haiku for title generation / follow-up probes.
    // Short-circuit with empty text so it never reaches the real provider and
    // never produces a confusing AI bubble as the first response in a new session.
    if ((anthReq.model || '').startsWith('claude-haiku')) return '';

    const sys = getSystemText(anthReq).toLowerCase();

    // Guard: never short-circuit a long system prompt — that's the real user message.
    if (sys.length > 800) return null;

    // Title generation — claude-code asks for a short conversation title
    if ((sys.includes('title') && (sys.includes('generate') || sys.includes('concise') || sys.includes('create'))) ||
        sys.includes('short title') || sys.includes('conversation title')) {
        return 'Claude Code Session';
    }

    // Follow-up / suggestion mode
    if ((sys.includes('follow-up') || sys.includes('follow up')) && sys.includes('question')) {
        return '';
    }
    if (sys.includes('suggest') && sys.includes('next action')) {
        return '';
    }

    // File-path extraction
    if (sys.includes('file path') && (sys.includes('extract') || sys.includes('identify'))) {
        return '[]';
    }

    // Conversation compaction
    if (sys.includes('compact') && (sys.includes('conversation') || sys.includes('context'))) {
        return '';
    }

    return null; // forward to provider
}

// Forward Anthropic-format request directly to api.anthropic.com — no format conversion.
// Used when providerUrl is api.anthropic.com (ANTHROPIC_API provider with a real sk-ant- key).
function sendToAnthropicDirect(providerUrl, apiKey, anthReq, stream, res) {
    const https  = require('https');
    const base   = providerUrl.endsWith('/') ? providerUrl.slice(0, -1) : providerUrl;
    const parsed = new URL(base + '/v1/messages');
    const body   = JSON.stringify(anthReq);

    const headers = {
        'content-type':      'application/json',
        'content-length':    Buffer.byteLength(body),
        'x-api-key':         apiKey,
        'anthropic-version': '2023-06-01',
    };
    if (anthReq['anthropic-beta']) headers['anthropic-beta'] = anthReq['anthropic-beta'];

    const provReq = https.request({
        hostname: parsed.hostname,
        port:     parsed.port || 443,
        path:     parsed.pathname + (parsed.search || ''),
        method:   'POST',
        headers,
    }, provRes => {
        const code = provRes.statusCode || 500;
        if (code !== 200) {
            let err = '';
            provRes.on('data', c => { err += c; });
            provRes.on('end', () => proxyError(res, code, err || ('Anthropic API error ' + code)));
            return;
        }
        if (stream) {
            res.writeHead(200, {
                'Content-Type':  'text/event-stream',
                'Cache-Control': 'no-cache',
                'Connection':    'keep-alive',
            });
            let idleTimer = setTimeout(() => {
                log('[anthropic-direct] stream idle 30s — aborting\n');
                try { provRes.destroy(); } catch(_) {}
            }, 30000);
            function resetIdle() {
                clearTimeout(idleTimer);
                idleTimer = setTimeout(() => {
                    log('[anthropic-direct] stream idle 30s — aborting\n');
                    try { provRes.destroy(); } catch(_) {}
                }, 30000);
            }
            provRes.on('data',  chunk => { resetIdle(); res.write(chunk); });
            provRes.on('end',   ()    => { clearTimeout(idleTimer); res.end(); });
            provRes.on('error', ()    => { clearTimeout(idleTimer); });
        } else {
            let data = '';
            provRes.on('data', c => { data += c; });
            provRes.on('end', () => {
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(data);
            });
        }
    });

    provReq.on('error', e => proxyError(res, 500, e.message));
    provReq.write(body);
    provReq.end();
}

// Tools claude-code ships in every request that are dead weight on Android —
// the model would never sensibly call them on a phone, but their JSON schemas
// still ride along on every request and inflate the input-token count (a plain
// "hello" costs ~29K input tokens, ~72KB of which is the 23 tool schemas).
// Stripping them is safe: the model can't call a tool it was never told about,
// and claude-code only acts on tool_use events the model actually returns.
//   Cron*        — job scheduling; Android has no cron
//   *Worktree    — git worktrees (parallel branch checkouts); a desktop workflow
//   ScheduleWakeup — harness loop self-pacing
//   NotebookEdit — Jupyter notebook editing; no notebooks on a phone
const PRUNED_TOOLS = new Set([
    'CronCreate', 'CronDelete', 'CronList',
    'EnterWorktree', 'ExitWorktree',
    'ScheduleWakeup', 'NotebookEdit',
    // Harness/orchestration tools that don't function on this build:
    //   Skill          — no skills configured in the guest → calling it does nothing
    //   Monitor        — watches a backgrounded job; print mode has none across turns
    //   PushNotification / RemoteTrigger — harness APIs with no counterpart here
    //   AskUserQuestion — claude-code --print auto-resolves it with an is_error
    //     ("please choose…"); there's no TTY to collect the answer, so it's
    //     non-functional in headless chat (it WOULD work in the 🐧 interactive tab).
    // NOTE: the old "weak models loop on Skill/AskUserQuestion" (inv 68) fear was an
    // OLD-ENGINE artifact (permission friction). Re-probed on proot 2.1.161 (b54
    // !probe-loop) across kimi-k2.6 / gpt-oss-20b / gpt-oss-120b: NO loop — all ask
    // in plain text, none even call the tools. So these stay pruned for uselessness,
    // NOT loop danger. Interactive ask→answer already works via natural text + warm.
    'Skill', 'Monitor', 'PushNotification', 'RemoteTrigger', 'AskUserQuestion',
]);

// Read-only dungeon scouts (Deep Scout / War Council / Divide) are MARKERS-ONLY —
// they must never edit files (the dungeon writes the per-room library.md itself) and
// weak models (gpt-oss-20b/120b) otherwise burn whole round-trips fumbling Grep's
// required `pattern` + stray Edits (observed in !log). The read-only scout personas
// embed this sentinel; the proxy strips write/grep tools when it sees it. Solo Scout
// and Hero Dispatch do NOT carry it — they legitimately write.
const READONLY_SCOUT_SENTINEL = '[[nexus:readonly-scout]]';
const SCOUT_STRIP_TOOLS = new Set(['Edit', 'Write', 'MultiEdit', 'Grep']);

// ── Tool deferral (proxy-side "lazy load" for OAI providers) ──────────────────
// Anthropic's real tool-search is a SERVER-side feature of api.anthropic.com
// (defer_loading + tool_search_tool); OAI providers (Gemini/OpenRouter/…) have
// no equivalent. So we emulate it in the proxy. CORE_TOOLS are the high-frequency
// file/shell tools always sent in full; everything else is "deferred" — held back
// to cut the ~72KB/≈29.5K-tok tool payload claude-code ships every turn.
//
// EVOLUTION:
//   Phase 1 — send CORE ∪ {tools used in history}; drop the rest.
//   Phase 2 — + a synthetic `tool_search` so the model can discover deferred tools
//             ON DEMAND (model-driven, like Anthropic's — great for STRONG models).
//   v2 (current) — + PROACTIVE intent pre-selection: the proxy reads the user's
//             words and surfaces the implied tools UP FRONT (proactiveToolPick),
//             so WEAK models that never call tool_search still get the right tools.
//             keep = CORE ∪ mcp ∪ used-in-history ∪ proactively-matched; tool_search
//             stays as the strong-model fallback for whatever proactive missed.
// See CLAUDE.md / engine memory.
const CORE_TOOLS = new Set([
    'Bash', 'Read', 'Edit', 'Write', 'Glob', 'Grep',
]);

// The synthetic tool we hand the model in place of the deferred ones (Anthropic
// shape — anthToOai converts it like any other tool). When the model calls it,
// the proxy intercepts (never reaches claude-code), matches the catalog, and
// re-calls the provider WITH the discovered tools added → Level-2 reactive.
const TOOL_SEARCH_DEF = {
    name: 'tool_search',
    description: 'Find and load additional tools you do not currently have. Call this with ' +
        'keywords when the task needs a capability missing from your current tool list — e.g. ' +
        '"web search" / "fetch url" for web access, "task" / "todo" for task tracking, ' +
        '"agent" / "subagent" / "delegate" for dispatching sub-agents, "plan" for plan mode. ' +
        'Returns the matching tools, which immediately become callable.',
    input_schema: {
        type: 'object',
        properties: { query: { type: 'string', description: 'keywords describing the capability you need' } },
        required: ['query'],
    },
};
// Match deferred-tool OAI defs against a free-text query (name + description,
// case-insensitive token overlap). Returns ≤5; if nothing matches, returns the
// first 5 so the model is never left empty-handed.
function matchDeferredTools(query, catalog) {
    const cat = catalog || [];
    const toks = String(query || '').toLowerCase().split(/[^a-z0-9_]+/).filter(t => t.length > 1);
    const hits = cat.filter(c => {
        const hay = ((c.function && c.function.name) + ' ' + (c.function && c.function.description || '')).toLowerCase();
        return toks.some(t => hay.includes(t));
    });
    // On a clean match return ≤5; on NO match return the WHOLE catalog rather than
    // an arbitrary first-5 — better to over-supply for one follow-up call than to
    // steer the model to the wrong tools (e.g. query "deep-research" wouldn't token-
    // match "WebSearch", so first-5 would hand back Agent/Task and never surface it).
    return hits.length ? hits.slice(0, 5) : cat;
}

// ── Defer v2: PROACTIVE intent pre-selection (weak-model friendly) ────────────
// Anthropic's lazy loading is model-driven (Claude calls tool_search). Weak OAI
// models won't do that 3-step dance, so instead the PROXY picks the likely tools
// from the user's own words and sends them UP FRONT — the model just sees a small,
// relevant set and uses it normally, no discovery step. tool_search stays as a
// fallback for whatever proactive selection misses (strong models can still expand).
//
// Curated intent → tool keyword map. Precise (hand-picked synonyms) so it beats raw
// token-overlap on verbose descriptions, which over-matches. Tools NOT listed here
// still get a name-token match (e.g. user literally says "todo") as a backstop.
const TOOL_INTENT_KW = {
    WebSearch:    ['search', 'google', 'look up', 'lookup', 'find online', 'on the web', 'web ', 'internet',
                   'latest', 'news', 'current', 'today', 'recent', 'this year', 'who is', "what's the latest", 'price of', 'release'],
    WebFetch:     ['fetch', 'url', 'http://', 'https://', '.com', '.org', '.io', 'link', 'website', 'web page',
                   'webpage', 'the page', 'open the site', 'read the article', 'scrape', 'from this page'],
    Task:         ['agent', 'subagent', 'sub-agent', 'delegate', 'dispatch', 'spawn', 'in parallel', 'background task', 'orchestrate'],
    TodoWrite:    ['todo', 'to-do', 'task list', 'checklist', 'plan the steps', 'track progress', 'break it down', 'step by step'],
    ExitPlanMode: ['plan mode', 'make a plan', 'planning', 'propose a plan'],
    SlashCommand: ['slash command', '/command', 'run the command'],
    BashOutput:   ['background output', 'job output', 'still running', 'check the output'],
    KillShell:    ['kill', 'stop the process', 'terminate', 'cancel the job'],
};

// claude-code/the harness staples <system-reminder> blocks onto user turns (the
// "task tools haven't been used… consider TaskCreate/TaskUpdate… track progress"
// nudge, env context, etc.). These are NOT user intent and are dense with trigger
// words ("TaskCreate", "task list", "track progress", "plan") that poison
// proactiveToolPick — surfacing ~10 Task/Todo tools the user never asked for and
// burying the tool they DID ask for (e.g. exa) in noise. Strip them before reading.
function stripInjectedReminders(s) {
    return String(s || '').replace(/<system-reminder>[\s\S]*?<\/system-reminder>/gi, ' ').trim();
}

// Most-recent USER intent text (skips tool_result-only turns, which carry no new
// intent). Used to drive proactive tool selection.
function latestUserText(anthReq) {
    const msgs = (anthReq && anthReq.messages) || [];
    for (let i = msgs.length - 1; i >= 0; i--) {
        const m = msgs[i];
        if (!m || m.role !== 'user') continue;
        if (typeof m.content === 'string') {
            const s = stripInjectedReminders(m.content);
            if (s) return s; continue;   // reminder-only → keep looking back
        }
        if (Array.isArray(m.content)) {
            const t = stripInjectedReminders(
                m.content.filter(b => b && b.type === 'text' && b.text).map(b => b.text).join(' '));
            if (t) return t;   // empty after strip → reminder/tool_result-only turn, keep looking back
        }
    }
    return '';
}

// Given the user's text + the tools that WOULD be deferred, return the set of tool
// names to surface up front. Curated keyword map first (precise), then a name-token
// backstop. Generous on purpose — over-supplying one relevant tool costs far less
// than a weak model silently failing because it never discovered the tool it needed.
function proactiveToolPick(userText, deferrable) {
    const text = String(userText || '').toLowerCase();
    const picked = new Set();
    if (!text) return picked;
    for (const t of (deferrable || [])) {
        const name = t.name || '';
        const kws = TOOL_INTENT_KW[name];
        let hit = kws ? kws.some(k => text.includes(k)) : false;
        if (!hit) {
            // CamelCase → word tokens (>2 chars), match the tool's own concept words.
            const nameToks = name.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase().split(/[^a-z0-9]+/).filter(x => x.length > 2);
            hit = nameToks.some(tok => text.includes(tok));
        }
        if (hit) picked.add(name);
    }
    return picked;
}

// Dungeon multi-provider routing. The dungeon Scout/Council picker lets a member
// run on ANY configured provider (not just the active one). It sets ANTHROPIC_MODEL
// to a tagged id "<providerId>::<modelId>"; this resolves that provider's url+key
// from dungeon_providers.json (written by DungeonActivity) and rewrites anthReq.model
// to the bare model id. Per-request + read-only → concurrency-safe across members.
const DUNGEON_PROVIDERS_FILE = path.join(FILES_DIR, 'dungeon_providers.json');
function loadDungeonProviders() {
    try { const a = JSON.parse(fs.readFileSync(DUNGEON_PROVIDERS_FILE, 'utf8')); return Array.isArray(a) ? a : []; }
    catch(_) { return []; }
}
function routeDungeonProvider(anthReq) {
    const m = anthReq && anthReq.model;
    if (!m || typeof m !== 'string') return null;
    const i = m.indexOf('::');
    if (i === -1) return null;
    const provId = m.slice(0, i), realModel = m.slice(i + 2);
    const e = loadDungeonProviders().find(x => x && x.id === provId);
    if (!e || !e.providerUrl) return null;
    anthReq.model = realModel;   // downstream (OAI convert / anthropic-direct) uses the bare id
    log('[proxy] dungeon route → provider=' + provId + ' model=' + realModel + '\n');
    return { providerUrl: e.providerUrl, apiKey: e.apiKey || '', model: realModel };
}

// ─────────────────────────────────────────────────────────────────────────
// RTK — Request Token Keeper (ported from 9router open-sse/rtk, MIT, decolua)
// Losslessly* compresses tool_result content (git diff/status, grep, find, ls,
// tree, build output, line-numbered dumps) in the OUTGOING Anthropic request,
// BEFORE anthToOai conversion — so it helps both the OAI-converted and the
// api.anthropic.com passthrough paths. Targets messages[] (history), which is
// the only request slice that grows unbounded over a --continue session;
// orthogonal to defer (tools[] schemas) and disabledTools (capability removal).
// Safe-by-design: any filter that throws or grows the text falls back to the
// original — RTK never breaks a request. Always on (mirrors tryOptimize); the
// only escape hatch is cfg.rtk === false. Surfaced in !log as `[RTK] saved …`.
// (* lossy-ish: caps/truncates verbose output; preserves substance + is_error.)
// ─────────────────────────────────────────────────────────────────────────
const RTK = (function () {
    // --- constants (mirror rtk Rust defaults) ---
    const RAW_CAP = 10 * 1024 * 1024;          // 10 MiB hard cap
    const MIN_COMPRESS_SIZE = 500;             // skip tiny blobs
    const DETECT_WINDOW = 1024;                // autodetect peeks first N chars
    const GIT_DIFF_HUNK_MAX_LINES = 100;
    const DEDUP_LINE_MAX = 2000;
    const GREP_PER_FILE_MAX = 10;
    const FIND_PER_DIR_MAX = 10;
    const FIND_TOTAL_DIR_MAX = 20;
    const STATUS_MAX_FILES = 10;
    const STATUS_MAX_UNTRACKED = 10;
    const LS_EXT_SUMMARY_TOP = 5;
    const LS_NOISE_DIRS = ['node_modules', '.git', 'target', '__pycache__',
        '.next', 'dist', 'build', '.venv', 'venv', '.cache', '.idea', '.vscode', '.DS_Store'];
    const TREE_MAX_LINES = 200;
    const SEARCH_LIST_PER_DIR_MAX = 10;
    const SEARCH_LIST_TOTAL_DIR_MAX = 20;
    const SMART_TRUNCATE_HEAD = 120;
    const SMART_TRUNCATE_TAIL = 60;
    const SMART_TRUNCATE_MIN_LINES = 250;
    const READ_NUMBERED_MIN_HIT_RATIO = 0.7;

    // --- filters ---
    function gitDiff(diff, maxLines = 500) {
        const result = [];
        let currentFile = '', added = 0, removed = 0, inHunk = false;
        let hunkShown = 0, hunkSkipped = 0, wasTruncated = false;
        const maxHunkLines = GIT_DIFF_HUNK_MAX_LINES;
        const lines = diff.split('\n');
        outer: for (const line of lines) {
            if (line.startsWith('diff --git')) {
                if (hunkSkipped > 0) { result.push('  ... (' + hunkSkipped + ' lines truncated)'); wasTruncated = true; hunkSkipped = 0; }
                if (currentFile && (added > 0 || removed > 0)) result.push('  +' + added + ' -' + removed);
                const parts = line.split(' b/');
                currentFile = parts.length > 1 ? parts.slice(1).join(' b/') : 'unknown';
                result.push('\n' + currentFile);
                added = 0; removed = 0; inHunk = false; hunkShown = 0;
            } else if (line.startsWith('@@')) {
                if (hunkSkipped > 0) { result.push('  ... (' + hunkSkipped + ' lines truncated)'); wasTruncated = true; hunkSkipped = 0; }
                inHunk = true; hunkShown = 0; result.push('  ' + line);
            } else if (inHunk) {
                if (line.startsWith('+') && !line.startsWith('+++')) {
                    added += 1;
                    if (hunkShown < maxHunkLines) { result.push('  ' + line); hunkShown += 1; } else hunkSkipped += 1;
                } else if (line.startsWith('-') && !line.startsWith('---')) {
                    removed += 1;
                    if (hunkShown < maxHunkLines) { result.push('  ' + line); hunkShown += 1; } else hunkSkipped += 1;
                } else if (hunkShown < maxHunkLines && !line.startsWith('\\')) {
                    if (hunkShown > 0) { result.push('  ' + line); hunkShown += 1; }
                }
            }
            if (result.length >= maxLines) { result.push('\n... (more changes truncated)'); wasTruncated = true; break outer; }
        }
        if (hunkSkipped > 0) { result.push('  ... (' + hunkSkipped + ' lines truncated)'); wasTruncated = true; }
        if (currentFile && (added > 0 || removed > 0)) result.push('  +' + added + ' -' + removed);
        if (wasTruncated) result.push('[diff compacted by rtk]');
        return result.join('\n');
    }
    gitDiff.filterName = 'git-diff';

    function gitStatus(input) {
        const lines = input.split('\n');
        if (lines.length === 0 || (lines.length === 1 && !lines[0].trim())) return 'Clean working tree';
        let branch = '';
        const stagedFiles = [], modifiedFiles = [], untrackedFiles = [];
        let staged = 0, modified = 0, untracked = 0, conflicts = 0;
        for (const raw of lines) {
            if (!raw.trim()) continue;
            const longBranch = raw.match(/^On branch (\S+)/);
            if (longBranch) { branch = longBranch[1]; continue; }
            if (raw.startsWith('##')) { branch = raw.replace(/^##\s*/, ''); continue; }
            if (raw.length >= 3 && /^[ MADRCU?!][ MADRCU?!] /.test(raw)) {
                const x = raw[0], y = raw[1], file = raw.slice(3);
                if (raw.slice(0, 2) === '??') { untracked++; untrackedFiles.push(file); continue; }
                if ('MADRC'.includes(x)) { staged++; stagedFiles.push(file); }
                else if (x === 'U') conflicts++;
                if (y === 'M' || y === 'D') { modified++; modifiedFiles.push(file); }
                continue;
            }
            const longMatch = raw.match(/^\s*(modified|new file|deleted|renamed|both modified):\s+(.+)$/);
            if (longMatch) {
                const kind = longMatch[1], p = longMatch[2].trim();
                if (kind === 'both modified') conflicts++;
                else if (kind === 'modified' || kind === 'deleted') { modified++; modifiedFiles.push(p); }
                else if (kind === 'new file' || kind === 'renamed') { staged++; stagedFiles.push(p); }
                continue;
            }
        }
        let out = '';
        if (branch) out += '* ' + branch + '\n';
        if (staged > 0) {
            out += '+ Staged: ' + staged + ' files\n';
            for (const f of stagedFiles.slice(0, STATUS_MAX_FILES)) out += '   ' + f + '\n';
            if (stagedFiles.length > STATUS_MAX_FILES) out += '   ... +' + (stagedFiles.length - STATUS_MAX_FILES) + ' more\n';
        }
        if (modified > 0) {
            out += '~ Modified: ' + modified + ' files\n';
            for (const f of modifiedFiles.slice(0, STATUS_MAX_FILES)) out += '   ' + f + '\n';
            if (modifiedFiles.length > STATUS_MAX_FILES) out += '   ... +' + (modifiedFiles.length - STATUS_MAX_FILES) + ' more\n';
        }
        if (untracked > 0) {
            out += '? Untracked: ' + untracked + ' files\n';
            for (const f of untrackedFiles.slice(0, STATUS_MAX_UNTRACKED)) out += '   ' + f + '\n';
            if (untrackedFiles.length > STATUS_MAX_UNTRACKED) out += '   ... +' + (untrackedFiles.length - STATUS_MAX_UNTRACKED) + ' more\n';
        }
        if (conflicts > 0) out += 'conflicts: ' + conflicts + ' files\n';
        if (staged === 0 && modified === 0 && untracked === 0 && conflicts === 0) out += 'clean — nothing to commit\n';
        return out.replace(/\n+$/, '');
    }
    gitStatus.filterName = 'git-status';

    function grep(input) {
        const byFile = new Map();
        let total = 0;
        for (const line of input.split('\n')) {
            const first = line.indexOf(':');
            if (first === -1) continue;
            const second = line.indexOf(':', first + 1);
            if (second === -1) continue;
            const file = line.slice(0, first);
            const lineNumStr = line.slice(first + 1, second);
            const content = line.slice(second + 1);
            if (!/^\d+$/.test(lineNumStr)) continue;
            total++;
            if (!byFile.has(file)) byFile.set(file, []);
            byFile.get(file).push([lineNumStr, content]);
        }
        if (total === 0) return input;
        const files = Array.from(byFile.keys()).sort();
        let out = total + ' matches in ' + files.length + 'F:\n\n';
        for (const file of files) {
            const matches = byFile.get(file);
            out += '[file] ' + file + ' (' + matches.length + '):\n';
            for (const pair of matches.slice(0, GREP_PER_FILE_MAX)) out += '  ' + pair[0].padStart(4) + ': ' + pair[1].trim() + '\n';
            if (matches.length > GREP_PER_FILE_MAX) out += '  +' + (matches.length - GREP_PER_FILE_MAX) + '\n';
            out += '\n';
        }
        return out;
    }
    grep.filterName = 'grep';

    function find(input) {
        const lines = input.split('\n').filter(l => l.trim());
        if (lines.length === 0) return input;
        const byDir = new Map();
        for (const p of lines) {
            const lastSlash = p.lastIndexOf('/');
            let dir, basename;
            if (lastSlash === -1) { dir = '.'; basename = p; }
            else { dir = p.slice(0, lastSlash) || '/'; basename = p.slice(lastSlash + 1); }
            if (!byDir.has(dir)) byDir.set(dir, []);
            byDir.get(dir).push(basename);
        }
        const dirs = Array.from(byDir.keys()).sort();
        let out = lines.length + ' files in ' + dirs.length + ' dirs:\n\n';
        for (const dir of dirs.slice(0, FIND_TOTAL_DIR_MAX)) {
            const fs2 = byDir.get(dir);
            out += dir + '/ (' + fs2.length + '):\n';
            for (const f of fs2.slice(0, FIND_PER_DIR_MAX)) out += '  ' + f + '\n';
            if (fs2.length > FIND_PER_DIR_MAX) out += '  +' + (fs2.length - FIND_PER_DIR_MAX) + '\n';
            out += '\n';
        }
        if (dirs.length > FIND_TOTAL_DIR_MAX) out += '+' + (dirs.length - FIND_TOTAL_DIR_MAX) + ' more dirs\n';
        return out;
    }
    find.filterName = 'find';

    const LS_DATE_RE = /\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2}\s+(\d{4}|\d{2}:\d{2})\s+/;
    function humanSize(bytes) {
        if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + 'M';
        if (bytes >= 1024) return (bytes / 1024).toFixed(1) + 'K';
        return bytes + 'B';
    }
    function parseLsLine(line) {
        const m = LS_DATE_RE.exec(line);
        if (!m) return null;
        const name = line.slice(m.index + m[0].length);
        const beforeParts = line.slice(0, m.index).split(/\s+/).filter(Boolean);
        if (beforeParts.length < 4) return null;
        const fileType = beforeParts[0].charAt(0);
        let size = 0;
        for (let i = beforeParts.length - 1; i >= 0; i--) {
            const n = Number(beforeParts[i]);
            if (Number.isInteger(n) && String(n) === beforeParts[i]) { size = n; break; }
        }
        return { fileType, size, name };
    }
    function ls(input) {
        const dirs = [], files = [], byExt = new Map();
        for (const line of input.split('\n')) {
            if (line.startsWith('total ') || line.length === 0) continue;
            const parsed = parseLsLine(line);
            if (!parsed) continue;
            if (parsed.name === '.' || parsed.name === '..') continue;
            if (LS_NOISE_DIRS.includes(parsed.name)) continue;
            if (parsed.fileType === 'd') dirs.push(parsed.name);
            else if (parsed.fileType === '-' || parsed.fileType === 'l') {
                const dot = parsed.name.lastIndexOf('.');
                const ext = dot > 0 ? parsed.name.slice(dot) : 'no ext';
                byExt.set(ext, (byExt.get(ext) || 0) + 1);
                files.push([parsed.name, humanSize(parsed.size)]);
            }
        }
        if (dirs.length === 0 && files.length === 0) return input;
        let out = '';
        for (const d of dirs) out += d + '/\n';
        for (const pair of files) out += pair[0] + '  ' + pair[1] + '\n';
        let summary = '\nSummary: ' + files.length + ' files, ' + dirs.length + ' dirs';
        if (byExt.size > 0) {
            const ext = Array.from(byExt.entries()).sort((a, b) => b[1] - a[1]);
            const parts = ext.slice(0, LS_EXT_SUMMARY_TOP).map(e => e[1] + ' ' + e[0]);
            summary += ' (' + parts.join(', ');
            if (ext.length > LS_EXT_SUMMARY_TOP) summary += ', +' + (ext.length - LS_EXT_SUMMARY_TOP) + ' more';
            summary += ')';
        }
        return out + summary;
    }
    ls.filterName = 'ls';

    function tree(input) {
        const lines = input.split('\n');
        if (lines.length === 0) return input;
        const filtered = [];
        for (const line of lines) {
            if (line.includes('director') && line.includes('file')) continue;
            if (line.trim() === '' && filtered.length === 0) continue;
            filtered.push(line);
        }
        while (filtered.length > 0 && filtered[filtered.length - 1].trim() === '') filtered.pop();
        if (filtered.length > TREE_MAX_LINES) {
            const cut = filtered.length - TREE_MAX_LINES;
            return filtered.slice(0, TREE_MAX_LINES).join('\n') + '\n... +' + cut + ' more lines';
        }
        return filtered.join('\n');
    }
    tree.filterName = 'tree';

    function dedupLog(input) {
        const lines = input.split('\n');
        const out = [];
        let prev = null, runCount = 0, blankStreak = 0;
        const flushRun = () => { if (prev !== null && runCount > 1) out.push('  ... (' + (runCount - 1) + ' duplicate lines)'); };
        for (const line of lines) {
            if (line.trim() === '') {
                if (blankStreak < 1) out.push(line);
                blankStreak += 1; flushRun(); prev = null; runCount = 0; continue;
            }
            blankStreak = 0;
            if (line === prev) { runCount += 1; continue; }
            flushRun(); out.push(line); prev = line; runCount = 1;
            if (out.length >= DEDUP_LINE_MAX) { out.push('... (truncated at ' + DEDUP_LINE_MAX + ' lines)'); return out.join('\n'); }
        }
        flushRun();
        return out.join('\n');
    }
    dedupLog.filterName = 'dedup-log';

    function smartTruncate(input) {
        const lines = input.split('\n');
        if (lines.length < SMART_TRUNCATE_MIN_LINES) return input;
        const head = lines.slice(0, SMART_TRUNCATE_HEAD);
        const tail = lines.slice(lines.length - SMART_TRUNCATE_TAIL);
        const cut = lines.length - head.length - tail.length;
        return head.concat(['... +' + cut + ' lines truncated'], tail).join('\n');
    }
    smartTruncate.filterName = 'smart-truncate';

    const READ_NUMBERED_LINE_RE = /^\s*\d+\|/;
    function readNumbered(input) {
        const lines = input.split('\n');
        if (lines.length < SMART_TRUNCATE_MIN_LINES) return input;
        const head = lines.slice(0, SMART_TRUNCATE_HEAD);
        const tail = lines.slice(lines.length - SMART_TRUNCATE_TAIL);
        const cut = lines.length - head.length - tail.length;
        return head.concat(['... +' + cut + ' lines truncated (file continues)'], tail).join('\n');
    }
    readNumbered.filterName = 'read-numbered';

    const SEARCH_LIST_HEADER_RE = /^Result of search in '[^']*' \(total (\d+) files?\):/;
    function searchList(input) {
        const lines = input.split('\n');
        if (lines.length === 0) return input;
        const header = lines[0] || '';
        const paths = [];
        for (const raw of lines.slice(1)) {
            const t = raw.trim();
            if (!t.startsWith('- ')) continue;
            paths.push(t.slice(2));
        }
        if (paths.length === 0) return input;
        const byDir = new Map();
        for (const p of paths) {
            const slash = p.lastIndexOf('/');
            const dir = slash === -1 ? '.' : (p.slice(0, slash) || '/');
            const name = slash === -1 ? p : p.slice(slash + 1);
            if (!byDir.has(dir)) byDir.set(dir, []);
            byDir.get(dir).push(name);
        }
        const dirs = Array.from(byDir.keys()).sort();
        let out = header + '\n' + paths.length + ' files in ' + dirs.length + ' dirs:\n\n';
        for (const dir of dirs.slice(0, SEARCH_LIST_TOTAL_DIR_MAX)) {
            const names = byDir.get(dir);
            out += dir + '/ (' + names.length + '):\n';
            for (const n of names.slice(0, SEARCH_LIST_PER_DIR_MAX)) out += '  ' + n + '\n';
            if (names.length > SEARCH_LIST_PER_DIR_MAX) out += '  +' + (names.length - SEARCH_LIST_PER_DIR_MAX) + '\n';
            out += '\n';
        }
        if (dirs.length > SEARCH_LIST_TOTAL_DIR_MAX) out += '+' + (dirs.length - SEARCH_LIST_TOTAL_DIR_MAX) + ' more dirs\n';
        return out.replace(/\n+$/, '');
    }
    searchList.filterName = 'search-list';

    const RE_CARGO_ERR_CONT = /^\s*(-->|\||\d+\s*\||=)/;
    const DEPRECATION_KEEP = 3;
    function buildOutput(input) {
        const lines = input.split('\n');
        if (lines.length === 0) return input;
        const errors = [], warnings = [], deprecations = [];
        let summary = null, compilingCount = 0, downloadingCount = 0, inCargoError = false;
        for (const line of lines) {
            const trimmed = line.trim();
            if (inCargoError) {
                if (!trimmed) { inCargoError = false; continue; }
                if (RE_CARGO_ERR_CONT.test(line)) { errors.push(line); continue; }
                inCargoError = false;
            }
            if (!trimmed) continue;
            if (/^npm (ERR!|error)/i.test(trimmed) || /^yarn error/i.test(trimmed)) { errors.push(line); continue; }
            if (/^npm warn deprecated/i.test(trimmed)) { deprecations.push(line); continue; }
            if (/^npm warn/i.test(trimmed) || /^yarn warn/i.test(trimmed)) { warnings.push(line); continue; }
            if (/^error(\[|:)/i.test(trimmed) || trimmed.startsWith('error -->')) { errors.push(line); inCargoError = true; continue; }
            if (/^warning(\[|:)/i.test(trimmed) || trimmed.startsWith('warning -->')) { warnings.push(line); inCargoError = true; continue; }
            if (/^ERROR:/i.test(trimmed)) { errors.push(line); continue; }
            if (/^\[ERROR\]/i.test(trimmed) || /^BUILD FAILED/i.test(trimmed)) { errors.push(line); continue; }
            if (/^\[WARNING\]/i.test(trimmed)) { warnings.push(line); continue; }
            if (/^\s*Compiling\s+\S+/i.test(trimmed)) { compilingCount++; continue; }
            if (/^\s*Downloading\s+\S+/i.test(trimmed) || /^Fetching\s+/i.test(trimmed)) { downloadingCount++; continue; }
            if (/^(added|removed|changed|audited|installed)\s+\d+\s+package/i.test(trimmed) ||
                /^\s*Finished\s+/i.test(trimmed) || /^BUILD SUCCESS/i.test(trimmed) ||
                /^\d+\s+(vulnerabilities|packages?|warnings?|errors?)/i.test(trimmed) ||
                /^Successfully (installed|built)/i.test(trimmed) || /^To address .* issues/i.test(trimmed) ||
                /^Run `npm (audit|fund)`/i.test(trimmed) || /packages are looking for funding/i.test(trimmed)) {
                summary = summary ? summary + '\n' + line : line; continue;
            }
        }
        let out = '';
        for (const d of deprecations.slice(0, DEPRECATION_KEEP)) out += d + '\n';
        if (deprecations.length > DEPRECATION_KEEP) out += '... +' + (deprecations.length - DEPRECATION_KEEP) + ' more deprecated packages\n';
        if (compilingCount > 0) out += 'Compiled ' + compilingCount + ' packages\n';
        if (downloadingCount > 0) out += 'Downloaded ' + downloadingCount + ' packages\n';
        for (const e of errors) out += e + '\n';
        for (const w of warnings.slice(0, 5)) out += w + '\n';
        if (warnings.length > 5) out += '... +' + (warnings.length - 5) + ' more warnings\n';
        if (summary) out += summary + '\n';
        return out.replace(/\n+$/, '') || input;
    }
    buildOutput.filterName = 'build-output';

    // --- autodetect ---
    const RE_GIT_DIFF = /^diff --git /m;
    const RE_GIT_DIFF_HUNK = /^@@ /m;
    const RE_GIT_STATUS = /^On branch |^nothing to commit|^Changes (not |to be )|^Untracked files:/m;
    const RE_PORCELAIN = /^[ MADRCU?!][ MADRCU?!] \S/m;
    const RE_BUILD_OUTPUT = /^(npm (warn|error|ERR!)|yarn (warn|error)|\s*Compiling\s+\S+|\s*Downloading\s+\S+|added \d+ package|\[ERROR\]|BUILD (SUCCESS|FAILED)|\s*Finished\s+|Successfully (installed|built)|ERROR:)/im;
    const RE_TREE_GLYPH = /[├└]──|│  /;
    const RE_LS_ROW = /^[-dlbcps][rwx-]{9}/m;
    const RE_LS_TOTAL = /^total \d+$/m;

    function isGrepLine(line) {
        const first = line.indexOf(':');
        if (first === -1) return false;
        const second = line.indexOf(':', first + 1);
        if (second === -1) return false;
        return /^\d+$/.test(line.slice(first + 1, second));
    }
    function isPathLike(line) {
        const t = line.trim();
        if (t.length === 0) return false;
        if (t.includes(':')) return false;
        return t.startsWith('.') || t.startsWith('/') || t.includes('/');
    }
    function isMostlyPorcelain(head) {
        const lines = head.split('\n').filter(l => l.trim());
        if (lines.length < 3) return false;
        return lines.filter(l => RE_PORCELAIN.test(l)).length / lines.length >= 0.6;
    }
    function isLineNumbered(lines) {
        let hits = 0, nonEmpty = 0;
        for (const l of lines.slice(0, 100)) {
            if (l.length === 0) continue;
            nonEmpty++;
            if (READ_NUMBERED_LINE_RE.test(l)) hits++;
        }
        if (nonEmpty < 5) return false;
        return hits / nonEmpty >= READ_NUMBERED_MIN_HIT_RATIO;
    }
    function countMatches(text, re) {
        const g = new RegExp(re.source, re.flags.includes('g') ? re.flags : re.flags + 'g');
        return (text.match(g) || []).length;
    }
    function autoDetectFilter(text) {
        const head = text.length > DETECT_WINDOW ? text.slice(0, DETECT_WINDOW) : text;
        if (RE_GIT_DIFF.test(head) || RE_GIT_DIFF_HUNK.test(head)) return gitDiff;
        if (RE_GIT_STATUS.test(head)) return gitStatus;
        if (RE_BUILD_OUTPUT.test(head)) return buildOutput;
        if (isMostlyPorcelain(head)) return gitStatus;
        const lines = head.split('\n');
        const nonEmpty = lines.filter(l => l.trim().length > 0);
        if (nonEmpty.slice(0, 5).some(isGrepLine)) return grep;
        if (nonEmpty.length >= 3 && nonEmpty.every(isPathLike)) return find;
        if (RE_TREE_GLYPH.test(head)) return tree;
        if (RE_LS_TOTAL.test(head) || countMatches(head, RE_LS_ROW) >= 3) return ls;
        if (SEARCH_LIST_HEADER_RE.test(head)) return searchList;
        if (lines.length >= SMART_TRUNCATE_MIN_LINES && isLineNumbered(lines)) return readNumbered;
        if (nonEmpty.length >= 5) return dedupLog;
        if (text.split('\n').length >= SMART_TRUNCATE_MIN_LINES) return smartTruncate;
        return null;
    }

    // --- apply (catch-and-passthrough) ---
    function safeApply(fn, text) {
        if (typeof fn !== 'function') return text;
        try {
            const out = fn(text);
            return typeof out === 'string' ? out : text;
        } catch (err) {
            return text;
        }
    }
    function compressText(text, stats) {
        const bytesIn = text.length;
        stats.bytesBefore += bytesIn;
        if (bytesIn < MIN_COMPRESS_SIZE || bytesIn > RAW_CAP) { stats.bytesAfter += bytesIn; return text; }
        const fn = autoDetectFilter(text);
        if (!fn) { stats.bytesAfter += bytesIn; return text; }
        const out = safeApply(fn, text);
        if (!out || out.length === 0 || out.length >= bytesIn) { stats.bytesAfter += bytesIn; return text; }
        stats.bytesAfter += out.length;
        stats.hits.push({ filter: fn.filterName, saved: bytesIn - out.length });
        return out;
    }

    // Estimate total request chars (sys + tools + msgs) — drives the budget gate
    // so RTK only fires when a request is actually near a provider limit. Mirrors
    // the [proxy] size diagnostic's accounting.
    function estimateChars(anthReq) {
        let n = 0;
        const sys = anthReq.system;
        if (typeof sys === 'string') n += sys.length;
        else if (Array.isArray(sys)) for (const b of sys) { if (b && b.text) n += b.text.length; }
        if (Array.isArray(anthReq.tools) && anthReq.tools.length) n += JSON.stringify(anthReq.tools).length;
        for (const m of (anthReq.messages || [])) {
            if (typeof m.content === 'string') { n += m.content.length; continue; }
            for (const b of (m.content || [])) {
                if (b.text) n += b.text.length;
                else if (b.input) n += JSON.stringify(b.input).length;
                else if (b.content) n += (typeof b.content === 'string' ? b.content.length : JSON.stringify(b.content).length);
            }
        }
        return n;
    }

    // --- public: compress tool_result content in an Anthropic request body ---
    // Mutates anthReq.messages in place. Returns stats or null. Preserves
    // is_error tool_results (error traces must survive verbatim).
    // RECENCY-AWARE: never compresses the freshest tool_result — the last message
    // carrying a tool_result is what the model is actively reasoning about THIS
    // turn, so it's left at full fidelity; only OLD context (already moved past)
    // is compressed. On the next turn that result becomes old and gets compressed.
    // Pass {protectLatest:false} to force compress-everything.
    function compressMessages(anthReq, opts) {
        if (!anthReq || !Array.isArray(anthReq.messages)) return null;
        const msgs = anthReq.messages;
        let protectIdx = -1;
        if (!opts || opts.protectLatest !== false) {
            for (let i = msgs.length - 1; i >= 0; i--) {
                const c = msgs[i] && msgs[i].content;
                if (Array.isArray(c) && c.some(b => b && b.type === 'tool_result')) { protectIdx = i; break; }
            }
        }
        const stats = { bytesBefore: 0, bytesAfter: 0, hits: [], protectIdx };
        try {
            for (let i = 0; i < msgs.length; i++) {
                if (i === protectIdx) continue;                  // keep freshest result raw
                const msg = msgs[i];
                if (!msg || !Array.isArray(msg.content)) continue;
                for (const block of msg.content) {
                    if (!block || block.type !== 'tool_result') continue;
                    if (block.is_error === true) continue;
                    if (typeof block.content === 'string') {
                        block.content = compressText(block.content, stats);
                    } else if (Array.isArray(block.content)) {
                        for (const part of block.content) {
                            if (part && part.type === 'text' && typeof part.text === 'string') {
                                part.text = compressText(part.text, stats);
                            }
                        }
                    }
                }
            }
        } catch (e) {
            return null;
        }
        return stats;
    }

    function formatRtkLog(stats) {
        if (!stats || !stats.hits || stats.hits.length === 0) return null;
        const saved = stats.bytesBefore - stats.bytesAfter;
        const pct = stats.bytesBefore > 0 ? ((saved / stats.bytesBefore) * 100).toFixed(1) : '0';
        const filters = Array.from(new Set(stats.hits.map(h => h.filter))).join(',');
        const kept = stats.protectIdx >= 0 ? ' (latest result kept raw)' : '';
        return '[RTK] saved ' + saved + 'c / ' + stats.bytesBefore + 'c (' + pct + '%) via [' + filters + '] hits=' + stats.hits.length + kept;
    }

    return { compressMessages, formatRtkLog, estimateChars };
})();
function handleProxyRequest(anthReq, res) {
    const cfg   = readConfig();
    const routed = routeDungeonProvider(anthReq);   // dungeon per-member provider pick (else null)
    const pUrl  = routed ? routed.providerUrl : (cfg.providerUrl || '');
    const key   = routed ? routed.apiKey      : (cfg.apiKey || '');
    const stream = !!anthReq.stream;

    // RTK — compress tool_result content in history before anthToOai / passthrough.
    // Targets messages[] (the only slice that grows over a --continue session),
    // orthogonal to defer (tools[]) + disabledTools. Always on; cfg.rtk===false
    // is the only opt-out (no UI, mirrors tryOptimize). Safe-by-design: bails to
    // the original on any error/size-increase. Runs BEFORE the size diagnostic so
    // [proxy] size reflects the real outgoing bytes. See CLAUDE.md / 9router RTK.
    //
    // Two fidelity guards make this better than upstream (which compresses
    // unconditionally): (1) BUDGET-AWARE — skip RTK entirely when the request is
    // comfortably under the provider limit (lossless until we'd otherwise risk
    // rejection); cfg.rtkThreshold chars, default 40000 (~11K tok). (2) recency-
    // aware lives in compressMessages (freshest tool_result kept raw).
    if (cfg.rtk !== false) {
        const threshold = (typeof cfg.rtkThreshold === 'number' && cfg.rtkThreshold > 0)
            ? cfg.rtkThreshold : 40000;
        const est = RTK.estimateChars(anthReq);
        if (est < threshold) {
            log('[RTK] skipped — under budget (' + est + 'c < ' + threshold + 'c)\n');
        } else {
            const rtkLine = RTK.formatRtkLog(RTK.compressMessages(anthReq));
            log((rtkLine || '[RTK] no compressible tool output (' + est + 'c)') + '\n');
        }
    }

    // Drop tools before anything reads anthReq.tools (size diagnostic below +
    // anthToOai conversion both pick up the pruned list). Two sources merge:
    //   1. PRUNED_TOOLS  — always-useless on Android (hardcoded, never sent)
    //   2. cfg.disabledTools — user's per-tool toggles from Settings.
    // This is the CORRECT layer to control the tool set: stripping a tool here
    // means the model is never told it exists, so it both saves input tokens
    // and actually takes effect. (The old Settings "Tool Permissions" UI wired
    // to permissions.allow did NOT work — the '*' wildcard overrode it and
    // nothing was ever removed from the request. See CLAUDE.md.)
    if (Array.isArray(anthReq.tools) && anthReq.tools.length) {
        const userOff = Array.isArray(cfg.disabledTools) ? cfg.disabledTools : [];
        const before = anthReq.tools.length;
        // Did claude-code actually load any MCP tools? If the count is 0 here, the
        // problem is upstream (shim/discovery), not the proxy. If > 0, they reached
        // the proxy and any later absence is a pruning/conversion issue.
        const mcpNames = anthReq.tools.filter(t => /^mcp__/.test(t.name || '')).map(t => t.name);
        log('[proxy] mcp tools in request: ' + mcpNames.length +
            (mcpNames.length ? ' [' + mcpNames.join(',') + ']' : '') + '\n');
        anthReq.tools = anthReq.tools.filter(t =>
            !PRUNED_TOOLS.has(t.name) && userOff.indexOf(t.name) === -1);
        const dropped = before - anthReq.tools.length;
        if (dropped) log('[proxy] pruned ' + dropped + ' tool(s)' +
            (userOff.length ? ' (' + userOff.length + ' user-disabled)' : '') + '\n');

        // ── Read-only dungeon scout: strip write + grep tools ──────────────────
        // Deep Scout / War Council / Divide are markers-only. Their persona (in the
        // system prompt, via --append-system-prompt) carries READONLY_SCOUT_SENTINEL.
        // Stripping Edit/Write enforces markers-only (the dungeon owns library.md);
        // stripping Grep stops weak models wasting round-trips on its required
        // `pattern` (Read/Glob/Bash cover discovery in a single folder). Hero Dispatch
        // and Solo Scout don't carry the sentinel, so their write tools are untouched.
        const sysText = Array.isArray(anthReq.system)
            ? anthReq.system.map(b => (b && b.text) || '').join('\n')
            : (typeof anthReq.system === 'string' ? anthReq.system : '');
        if (routed && sysText.indexOf(READONLY_SCOUT_SENTINEL) !== -1) {
            const b2 = anthReq.tools.length;
            anthReq.tools = anthReq.tools.filter(t => !SCOUT_STRIP_TOOLS.has(t.name));
            if (anthReq.tools.length !== b2)
                log('[proxy] dungeon scout: read-only → stripped ' + (b2 - anthReq.tools.length) +
                    ' write/grep tool(s) [' + Array.from(SCOUT_STRIP_TOOLS).join(',') + ']\n');
        }
    }

    // ── Tool deferral (Phase 2 — reactive "lazy load" for OAI providers) ──────
    // Gated on !defer on AND OAI providers only (Anthropic passthrough gets native
    // server-side tool search free). We send CORE ∪ {tools used this session} +
    // a synthetic `tool_search` tool, and hold the rest in a catalog. When the
    // model calls tool_search, sendToProvider intercepts it, matches the catalog,
    // and re-calls the provider WITH the discovered tools added — the model never
    // loses access, it just fetches tools on demand (see the interception block).
    // SAFETY: this only SUBTRACTS real tools from the up-front request and ADDS a
    // discovery path; on any failure the interception finishes the stream cleanly,
    // and tool_search calls never leak to claude-code.
    let deferCatalogOai = null;
    if (getDeferTools() && !pUrl.includes('api.anthropic.com')
        && Array.isArray(anthReq.tools) && anthReq.tools.length) {
        // Tools already exercised in history → keep them available (no re-search).
        const usedInHistory = new Set();
        for (const m of (anthReq.messages || [])) {
            if (!Array.isArray(m.content)) continue;
            for (const b of m.content) {
                if (b && b.type === 'tool_use' && b.name) usedInHistory.add(b.name);
            }
        }
        const beforeDefer = anthReq.tools.length;
        // Web-search redirect: when the user clearly wants a WEB search/fetch AND a
        // web tool is on hand (exa MCP or built-in WebSearch/WebFetch), DEFER the
        // local-filesystem search tools (Grep/Glob) for THIS turn. They're in
        // CORE_TOOLS so defer normally keeps them unconditionally — but a weak model
        // sitting in a populated cwd then reinterprets "search how many … 2025" as
        // grep/find the directory and never reaches the web tool (gpt-oss-20b/120b
        // did exactly this). They stay reachable via tool_search if truly needed.
        const uText = latestUserText(anthReq).toLowerCase();
        const webIntent = TOOL_INTENT_KW.WebSearch.concat(TOOL_INTENT_KW.WebFetch)
            .some(k => uText.includes(k));
        const hasWebTool = anthReq.tools.some(t => {
            const n = t.name || '';
            return n === 'WebSearch' || n === 'WebFetch' ||
                (/^mcp__/.test(n) && /(search|fetch|web)/i.test(n));
        });
        const demoteLocalSearch = webIntent && hasWebTool;
        // Force Grep/Glob into the catalog this turn — overrides CORE, history AND
        // proactive keep, so even a warm follow-up whose history already used Grep
        // can't re-surface it while the user is asking for a web search.
        const demoted = (n) => demoteLocalSearch && (n === 'Grep' || n === 'Glob');
        // Defer v2: PROACTIVELY surface the tools the user's own words imply, so a
        // weak model gets them up front (no tool_search dance needed). Computed over
        // only the would-be-deferred tools (core/mcp/used are kept regardless).
        const deferrableNow = anthReq.tools.filter(t => {
            const n = t.name || '';
            return demoted(n) || !(CORE_TOOLS.has(n) || /^mcp__/.test(n) || usedInHistory.has(n));
        });
        const proactive = proactiveToolPick(uText, deferrableNow);
        const deferredAnth = [];
        anthReq.tools = anthReq.tools.filter(t => {
            const n = t.name || '';
            const keep = !demoted(n) &&
                (CORE_TOOLS.has(n) || /^mcp__/.test(n) || usedInHistory.has(n) || proactive.has(n));
            if (!keep) deferredAnth.push(t);
            return keep;
        });
        if (demoteLocalSearch) log('[proxy] defer: web-search intent + web tool — ' +
            'demoted Grep/Glob to catalog so the model uses the web tool, not local search\n');
        if (proactive.size) log('[proxy] defer: proactively surfaced ' + proactive.size +
            ' tool(s) from user intent: ' + Array.from(proactive).join(',') + '\n');
        if (deferredAnth.length) {
            // Build the catalog in OAI function shape (same mapping anthToOai uses)
            // so the interception can splice matched tools straight into the follow-up.
            deferCatalogOai = deferredAnth.map(t => ({
                type: 'function',
                function: {
                    name: t.name,
                    description: t.description || '',
                    parameters: t.input_schema || { type: 'object', properties: {} },
                },
            }));
            // Hand the model the discovery tool in place of the deferred ones.
            anthReq.tools.push(TOOL_SEARCH_DEF);
            log('[proxy] deferred ' + deferredAnth.length + '/' + beforeDefer +
                ' tool(s) → catalog: ' + deferredAnth.map(t => t.name).join(',') +
                (usedInHistory.size ? ' (kept ' + usedInHistory.size + ' from history)' : '') +
                ' + injected tool_search\n');
        }
    }

    // Per-spawn model override (dungeon Scout/Council): honor anthReq.model ONLY when
    // it's explicitly one of the provider's modelList ids. Normal chat sends the fake
    // 'claude-3-5-sonnet-20241022' (not in modelList) → falls back to cfg.modelId, so
    // the regular path is unaffected. The dungeon sets ANTHROPIC_MODEL to a real model
    // id per member; /v1/models advertises those ids so claude-code's startup validation
    // accepts them.
    const reqModel = anthReq.model || '';
    const baseModel = routed ? routed.model
        : (reqModel && Array.isArray(cfg.modelList) && cfg.modelList.indexOf(reqModel) !== -1)
        ? reqModel
        : (cfg.modelId || reqModel || '');
    log('[proxy] request: model=' + (anthReq.model||'?') +
        (baseModel && baseModel !== anthReq.model ? ' → oai:' + baseModel : '') +
        ' stream=' + stream + ' url=' + (pUrl||'MISSING') + '\n');

    // Token-breakdown diagnostic — surfaces *why* a "hello" message hits TPM
    // limits on low-tier providers. Most of the bytes come from claude-code's
    // hardcoded system prompt + the full tool schema list, not the user text.
    try {
        const sysLen = getSystemText(anthReq).length;
        const toolsArr = anthReq.tools || [];
        const toolsJson = toolsArr.length ? JSON.stringify(toolsArr) : '';
        const toolNames = toolsArr.map(t => t.name).join(',');
        const msgs = anthReq.messages || [];
        let msgsLen = 0;
        for (const m of msgs) {
            if (typeof m.content === 'string') { msgsLen += m.content.length; continue; }
            for (const b of (m.content || [])) {
                if (b.text) msgsLen += b.text.length;
                else if (b.input) msgsLen += JSON.stringify(b.input).length;
                else if (b.content) msgsLen += (typeof b.content === 'string' ? b.content.length : JSON.stringify(b.content).length);
            }
        }
        const total = sysLen + toolsJson.length + msgsLen;
        log('[proxy] size: sys=' + sysLen + 'c tools=' + toolsArr.length +
            '(' + toolsJson.length + 'c) msgs=' + msgs.length + '(' + msgsLen + 'c) total=' +
            total + 'c ≈' + Math.ceil(total / 3.5) + 'tok\n');
        if (toolsArr.length) log('[proxy] tool-names: ' + toolNames + '\n');
        // Log incoming tool_result blocks so we can see what a tool actually
        // returned (e.g. mkdir "permission denied" vs success) — the model loops
        // when a tool fails and it can't tell why. Last message only, truncated.
        const lastMsg = msgs[msgs.length - 1];
        if (lastMsg && Array.isArray(lastMsg.content)) {
            for (const b of lastMsg.content) {
                if (b.type !== 'tool_result') continue;
                const txt = Array.isArray(b.content)
                    ? b.content.filter(x => x.type === 'text').map(x => x.text).join(' ')
                    : String(b.content || '');
                const flat = txt.replace(/\s+/g, ' ').slice(0, 300);
                log('[proxy] tool-result' + (b.is_error ? ' ERROR' : '') + ': ' + (flat || '(empty)') + '\n');
            }
        }
    } catch (_) {}

    if (!pUrl) return proxyError(res, 500, 'No provider URL in config — check app settings');

    // Anthropic API key users — forward request as-is (no OAI conversion needed)
    if (pUrl.includes('api.anthropic.com')) {
        return sendToAnthropicDirect(pUrl, key, anthReq, stream, res);
    }
    const modelList  = Array.isArray(cfg.modelList) ? cfg.modelList : [];
    const oaiBase    = anthToOai(anthReq, baseModel);
    // Stash the deferred-tool catalog on the request object; sendToProvider lifts
    // it off (and deletes it) before sending the body, then uses it to resolve any
    // tool_search call the model makes. Non-enumerable would be cleaner but a plain
    // prop is fine since sendToProvider always strips it.
    if (deferCatalogOai && deferCatalogOai.length) oaiBase.__deferCatalog = deferCatalogOai;
    const hasTools   = !!(oaiBase.tools && oaiBase.tools.length);
    const isHfSpace  = pUrl.includes('.hf.space');

    // attempt(modelId, retriesLeft, delayMs, hfRetried)
    // Retries the same model up to 3x with exponential backoff on 429,
    // then falls through to the next model in modelList.
    // hfRetried: HF Space cold-start retry count (max 1, only for .hf.space URLs).
    function attempt(modelId, retriesLeft, delayMs, hfRetried) {
        hfRetried = hfRetried || 0;
        const oaiReq = Object.assign({}, oaiBase, { model: modelId });

        function retryWithoutTools() {
            log('[proxy] provider rejected tools (HTTP 400) — retrying as plain text request\n');
            const plain = Object.assign({}, oaiReq);
            delete plain.tools;
            delete plain.tool_choice;
            // A request with no `tools` but messages that still carry assistant
            // `tool_calls` or `role:"tool"` results is invalid for strict OAI
            // endpoints (Gemini compat) → it 400s again. Flatten that history to
            // plain text so the fallback actually succeeds.
            plain.messages = flattenToolHistory(oaiReq.messages);
            sendToProvider(pUrl, key, plain, stream, res, null, on429, on402, on5xx);
        }

        function on429() {
            lastRateLimitMs = Date.now();
            if (retriesLeft > 0) {
                log('[proxy] 429 — retrying ' + modelId + ' in ' + delayMs + 's (' + retriesLeft + ' left)\n');
                // Notify active socket of countdown so the thinking timer shows it
                try { if (on429CountdownNotify) on429CountdownNotify(delayMs); } catch(_) {}
                setTimeout(() => attempt(modelId, retriesLeft - 1, delayMs * 2, hfRetried), delayMs * 1000);
            } else {
                const idx  = modelList.indexOf(modelId);
                const next = modelList[idx + 1];
                if (next && next !== modelId) {
                    log('[proxy] 429 exhausted — switching to ' + next + '\n');
                    // M8: reset delayMs to initial value when switching models
                    attempt(next, 2, 2, hfRetried);
                } else {
                    proxyError(res, 429, 'Rate limited. All fallback models exhausted — switch provider in Settings.');
                }
            }
        }

        // 402 = insufficient credits for this model — skip directly to next fallback (no retries)
        function on402() {
            const idx  = modelList.indexOf(modelId);
            const next = modelList[idx + 1];
            if (next && next !== modelId) {
                log('[proxy] 402 insufficient credits — switching to ' + next + '\n');
                attempt(next, 2, 2, hfRetried);
            } else {
                proxyError(res, 402, 'Insufficient credits for this model. Switch to a free model or add credits at openrouter.ai/credits');
            }
        }

        // HF Space cold-start: on 500/503, notify user and retry up to 4x (60s total).
        // Free-tier Spaces can take 30–90s to wake from sleep.
        const on5xx = (isHfSpace && hfRetried < 4) ? function() {
            const attempt_n = hfRetried + 1;
            log('[hf-space] 500/503 — Space may be sleeping, retry ' + attempt_n + '/4 in 15s\n');
            for (const s of activeSessions.values()) {
                try { if (s.socket) s.socket.write(SYS_FENCE + '\x1b[33m[HuggingFace Space waking up — retry ' + attempt_n + '/4 in 15s…]\x1b[0m\r\n'); } catch(_) {}
            }
            setTimeout(() => attempt(modelId, retriesLeft, delayMs, hfRetried + 1), 15000);
        } : null;

        sendToProvider(pUrl, key, oaiReq, stream, res, hasTools ? retryWithoutTools : null, on429, on402, on5xx);
    }

    attempt(baseModel, 3, 2, 0);
}

// ── Gemini thought_signature round-trip ──────────────────────────────────────
// Gemini 3.x models return an opaque `thought_signature` on every function call
// and REQUIRE it echoed back, attached to the same call, on subsequent turns —
// otherwise the request 400s ("Function call is missing a thought_signature in
// functionCall parts"). claude-code's Anthropic-format history only preserves
// {id,name,input}, so the signature is lost on the next turn and tool use breaks
// for the whole conversation. We stash signatures by tool_call id at capture time
// and re-attach them in anthToOai when the same call reappears in history.
const thoughtSigStore = new Map(); // tool_call id → thought_signature
function storeThoughtSig(id, sig) {
    if (!id || !sig) return;
    if (thoughtSigStore.size > 500) { // bound memory; drop oldest
        const first = thoughtSigStore.keys().next().value;
        if (first !== undefined) thoughtSigStore.delete(first);
    }
    thoughtSigStore.set(id, sig);
}
// Defensive: Gemini's OpenAI-compat layer has surfaced the signature in a few
// shapes across versions — scan the documented + likely locations.
function extractThoughtSig(tc, delta, choice) {
    const ec = (o) => o && o.extra_content && o.extra_content.google && o.extra_content.google.thought_signature;
    return (tc && (ec(tc) || tc.thought_signature || tc.thoughtSignature || (tc.function && tc.function.thought_signature)))
        || (delta && ec(delta))
        || (choice && choice.message && ec(choice.message))
        || null;
}

// Convert Anthropic Messages request → OpenAI Chat Completions request
function anthToOai(a, model) {
    const msgs = [];

    if (a.system) {
        const text = typeof a.system === 'string'
            ? a.system
            : (a.system || []).filter(b => b.type === 'text').map(b => b.text).join('\n');
        if (text) msgs.push({ role: 'system', content: text });
    }

    for (const m of (a.messages || [])) {
        if (typeof m.content === 'string') {
            msgs.push({ role: m.role, content: m.content });
            continue;
        }
        const blocks = m.content || [];
        const textBlocks    = blocks.filter(b => b.type === 'text');
        const imageBlocks   = blocks.filter(b => b.type === 'image');
        const toolUseBlocks = blocks.filter(b => b.type === 'tool_use');
        const toolResults   = blocks.filter(b => b.type === 'tool_result');

        if (toolResults.length > 0) {
            // Anthropic user tool_result → OpenAI role:"tool" messages (one per result)
            for (const tr of toolResults) {
                const content = Array.isArray(tr.content)
                    ? tr.content.filter(b => b.type === 'text').map(b => b.text).join('')
                    : String(tr.content || '');
                msgs.push({ role: 'tool', tool_call_id: tr.tool_use_id, content });
            }
            // #4: preserve any text/image that rode along in the same user block
            // instead of dropping it (was: text-only, images silently lost).
            if (imageBlocks.length > 0) {
                const extra = [];
                for (const ib of imageBlocks) {
                    if (ib.source && ib.source.type === 'base64')
                        extra.push({ type: 'image_url', image_url: { url: 'data:' + ib.source.media_type + ';base64,' + ib.source.data } });
                }
                for (const tb of textBlocks) extra.push({ type: 'text', text: tb.text });
                if (extra.length) msgs.push({ role: 'user', content: extra });
            } else if (textBlocks.length > 0) {
                msgs.push({ role: 'user', content: textBlocks.map(b => b.text).join('') });
            }
        } else if (toolUseBlocks.length > 0 && m.role === 'assistant') {
            // Anthropic assistant tool_use → OpenAI tool_calls
            msgs.push({
                role: 'assistant',
                content: textBlocks.map(b => b.text).join('') || null,
                tool_calls: toolUseBlocks.map(tu => {
                    const call = {
                        id: tu.id || ('call_' + tu.name + '_' + Date.now()),
                        type: 'function',
                        function: { name: tu.name, arguments: JSON.stringify(tu.input || {}) }
                    };
                    // Re-attach Gemini thought_signature so 3.x tool turns don't 400.
                    const sig = tu.id && thoughtSigStore.get(tu.id);
                    if (sig) call.extra_content = { google: { thought_signature: sig } };
                    return call;
                })
            });
        } else if (imageBlocks.length > 0) {
            // Message with image content — convert to OpenAI vision format
            const oaiContent = [];
            for (const ib of imageBlocks) {
                if (ib.source && ib.source.type === 'base64') {
                    oaiContent.push({
                        type: 'image_url',
                        image_url: { url: 'data:' + ib.source.media_type + ';base64,' + ib.source.data }
                    });
                }
            }
            for (const tb of textBlocks) {
                oaiContent.push({ type: 'text', text: tb.text });
            }
            if (oaiContent.length > 0) {
                msgs.push({ role: m.role, content: oaiContent });
            }
        } else {
            msgs.push({ role: m.role, content: textBlocks.map(b => b.text).join('') });
        }
    }

    const req = { model, messages: msgs, max_tokens: a.max_tokens || 8192, stream: !!a.stream };
    if (a.temperature !== undefined) req.temperature = a.temperature;
    if (a.stop_sequences && a.stop_sequences.length) req.stop = a.stop_sequences;

    if (a.tools && a.tools.length) {
        req.tools = a.tools.map(t => ({
            type: 'function',
            function: {
                name: t.name,
                description: t.description || '',
                parameters: t.input_schema || { type: 'object', properties: {} }
            }
        }));
        if (a.tool_choice) {
            if (a.tool_choice === 'auto' || a.tool_choice === 'none') {
                req.tool_choice = a.tool_choice;
            } else if (a.tool_choice === 'any') {
                req.tool_choice = 'required';
            } else if (typeof a.tool_choice === 'object' && a.tool_choice.name) {
                req.tool_choice = { type: 'function', function: { name: a.tool_choice.name } };
            }
        }
    }

    return req;
}

// Rewrite an OAI messages array so it carries NO tool-call structure: assistant
// `tool_calls` become a short assistant text note, and `role:"tool"` results
// become `role:"user"` text. Used by the no-tools 400 fallback — a request with
// tool_calls/tool messages but no `tools` declared is rejected by strict OAI
// endpoints (Gemini compat). Returns a NEW array; never mutates the input.
function flattenToolHistory(messages) {
    const out = [];
    for (const m of (messages || [])) {
        if (m.role === 'tool') {
            const body = typeof m.content === 'string' ? m.content : JSON.stringify(m.content || '');
            out.push({ role: 'user', content: '[tool result] ' + body });
        } else if (m.role === 'assistant' && Array.isArray(m.tool_calls) && m.tool_calls.length) {
            const calls = m.tool_calls
                .map(tc => (tc.function && tc.function.name) || 'tool')
                .join(', ');
            const text = (typeof m.content === 'string' && m.content) ? m.content : '';
            out.push({ role: 'assistant', content: (text + '\n[requested tools: ' + calls + ']').trim() });
        } else {
            out.push(m);
        }
    }
    return out;
}

// Strip provider format-leakage from a tool-call function name. gpt-oss/harmony
// models (e.g. openai/gpt-oss-* on NVIDIA NIM) leak channel control tokens into
// the function name — "Bash<|channel|>commentary" instead of "Bash" — which makes
// claude-code reject EVERY tool call with "No such tool available: Bash<|channel|>…".
// Real tool names are strictly [A-Za-z0-9_-] (incl. mcp__ servers), so cut at the
// first harmony token / illegal char. Returns '' only if nothing salvageable.
function cleanToolName(n) {
    if (!n || typeof n !== 'string') return n || '';
    // truncate at the first harmony control token ("<|…")
    let s = n.split('<|')[0];
    // keep only the leading valid tool-name characters
    const m = s.match(/^[A-Za-z0-9_-]+/);
    return m ? m[0] : s.trim();
}

// Convert OpenAI Chat Completions response → Anthropic Messages response
function oaiToAnth(oai, model) {
    const choice = (oai.choices || [])[0] || {};
    const msg    = choice.message || {};
    const content = [];

    if (msg.content) content.push({ type: 'text', text: msg.content });

    // Convert OpenAI tool_calls → Anthropic tool_use blocks
    if (msg.tool_calls && msg.tool_calls.length > 0) {
        for (const tc of msg.tool_calls) {
            let input = {};
            try { input = JSON.parse(tc.function.arguments || '{}'); } catch (_) {}
            content.push({ type: 'tool_use', id: tc.id, name: cleanToolName(tc.function.name), input });
            const sig = extractThoughtSig(tc, null, choice);
            if (sig) storeThoughtSig(tc.id, sig);
        }
        log('[proxy] received ' + msg.tool_calls.length + ' tool_call(s) from provider\n');
    }

    const finish   = choice.finish_reason;
    const stopReason = finish === 'length'     ? 'max_tokens'
                     : finish === 'tool_calls' ? 'tool_use'
                     : 'end_turn';

    return {
        id: 'msg_' + (oai.id || Date.now()),
        type: 'message', role: 'assistant',
        content: content.length ? content : [{ type: 'text', text: '' }],
        model, stop_reason: stopReason, stop_sequence: null,
        usage: {
            input_tokens:  (oai.usage || {}).prompt_tokens    || 0,
            output_tokens: (oai.usage || {}).completion_tokens || 0,
        },
    };
}

// One-shot non-streaming POST to the provider's /chat/completions → parsed JSON.
// Used by the tool_search follow-up (and reusable for any internal proxy call).
function postOai(baseUrl, apiKey, reqObj) {
    return new Promise((resolve, reject) => {
        let tgt;
        try {
            const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
            tgt = new URL(base + '/chat/completions');
        } catch (e) { return reject(e); }
        const body = JSON.stringify(Object.assign({}, reqObj, { stream: false }));
        const hdrs = {
            'Content-Type':   'application/json',
            'Content-Length': Buffer.byteLength(body),
            'Authorization':  'Bearer ' + apiKey,
            'User-Agent':     'Mozilla/5.0 (Linux; Android 10) ClaudeCodeSetup/1.0',
            'Accept':         'application/json',
        };
        if (tgt.hostname.includes('openrouter')) {
            hdrs['HTTP-Referer'] = 'https://github.com/fahmi304/Nexus-Mind';
            hdrs['X-Title']      = 'Nexus Mind';
        }
        const lib2 = tgt.protocol === 'https:' ? https : http;
        const r = lib2.request({
            hostname: tgt.hostname,
            port: tgt.port || (tgt.protocol === 'https:' ? 443 : 80),
            method: 'POST',
            path: tgt.pathname + (tgt.search || ''),
            headers: hdrs,
        }, rr => {
            let buf = '';
            rr.setEncoding('utf8');
            rr.on('data', c => { buf += c; });
            rr.on('end', () => {
                try { resolve(JSON.parse(buf)); }
                catch (e) { reject(new Error('postOai parse: ' + e.message + ' body=' + buf.slice(0, 200))); }
            });
            rr.on('error', reject);
        });
        r.setTimeout(60000, () => { r.destroy(); reject(new Error('postOai timeout (60s)')); });
        r.on('error', reject);
        r.write(body);
        r.end();
    });
}

function sendToProvider(baseUrl, apiKey, oaiReq, stream, res, onBadRequest, on429, on402, on5xx) {
    let targetUrl;
    try {
        const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
        targetUrl = new URL(base + '/chat/completions');
    } catch (e) {
        return proxyError(res, 500, 'Invalid provider URL: ' + baseUrl);
    }

    // Lift the deferred-tool catalog off the request (set by handleProxyRequest's
    // defer block) BEFORE serializing — the provider must never see it. The stream
    // handler reads `deferCatalog` to resolve tool_search calls.
    const deferCatalog = oaiReq.__deferCatalog || null;
    if (oaiReq.__deferCatalog) delete oaiReq.__deferCatalog;

    const body    = JSON.stringify(oaiReq);
    const lib     = targetUrl.protocol === 'https:' ? https : http;
    const port    = targetUrl.port || (targetUrl.protocol === 'https:' ? 443 : 80);
    const headers = {
        'Content-Type':   'application/json',
        'Content-Length': Buffer.byteLength(body),
        'Authorization':  'Bearer ' + apiKey,
        'User-Agent':     'Mozilla/5.0 (Linux; Android 10) ClaudeCodeSetup/1.0',
        'Accept':         'application/json',
    };

    // OpenRouter needs attribution headers to unlock free models
    if (targetUrl.hostname.includes('openrouter')) {
        headers['HTTP-Referer'] = 'https://github.com/fahmi304/Nexus-Mind';
        headers['X-Title']      = 'Nexus Mind';
    }

    const provReq = lib.request({
        hostname: targetUrl.hostname,
        port, method: 'POST',
        path: targetUrl.pathname + (targetUrl.search || ''),
        headers,
    }, provRes => {
        if (!stream) {
            // Non-streaming: buffer, convert, reply
            let data = '';
            provRes.setEncoding('utf8');
            provRes.on('data', c => { data += c; });
            provRes.on('end', () => {
                try {
                    const parsed = JSON.parse(data);
                    if (provRes.statusCode !== 200) {
                        if (provRes.statusCode === 400) {
                            const why = (data || '').replace(/[\r\n]+/g, ' ').replace(/<[^>]+>/g, '').trim().slice(0, 400);
                            if (why) log('[provider-400] ' + why + '\n');
                            if (onBadRequest) return onBadRequest();
                        }
                        if (provRes.statusCode === 429 && on429) return on429();
                        if (provRes.statusCode === 429) lastRateLimitMs = Date.now();
                        let errMsg = parsed.error?.message || 'Provider HTTP ' + provRes.statusCode;
                        try {
                            const raw = parsed.error?.metadata?.raw;
                            if (raw) {
                                const inner = JSON.parse(raw);
                                const innerMsg = inner.error?.message || inner.message;
                                if (innerMsg) errMsg = innerMsg;
                            }
                        } catch (_) {}
                        if (provRes.statusCode === 402) {
                            if (on402) return on402();
                            errMsg += ' — switch to a free model or add credits at openrouter.ai/credits';
                        }
                        if ((provRes.statusCode === 500 || provRes.statusCode === 503) && on5xx) return on5xx();
                        return proxyError(res, provRes.statusCode, errMsg);
                    }
                    if (parsed.error) {
                        return proxyError(res, 500,
                            parsed.error.message || JSON.stringify(parsed.error));
                    }
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify(oaiToAnth(parsed, oaiReq.model)));
                } catch (e) {
                    proxyError(res, 500, 'Parse error: ' + e.message);
                }
            });
            // #2: close res if the provider disconnects mid-body, instead of letting
            // claude-code wait out the 120 s request timeout.
            provRes.on('error', err => {
                log('[proxy] provider response error (non-stream): ' + err.message + '\n');
                proxyError(res, 502, 'Provider disconnected: ' + err.message);
            });
        } else {
            // Streaming: surface non-200 errors before writing any headers
            if (provRes.statusCode !== 200) {
                let errBody = '';
                provRes.setEncoding('utf8');
                provRes.on('data', c => { errBody += c; });
                provRes.on('end', () => {
                    // 400 with tools in request → log the provider's actual reason
                    // (Gemini's OAI-compat 400 body says exactly which field it
                    // rejects), then retry without tools.
                    if (provRes.statusCode === 400) {
                        const why = errBody.replace(/[\r\n]+/g, ' ').replace(/<[^>]+>/g, '').trim().slice(0, 400);
                        if (why) log('[provider-400] ' + why + '\n');
                        if (onBadRequest) return onBadRequest();
                    }
                    let msg = 'Provider returned HTTP ' + provRes.statusCode;
                    if (provRes.statusCode === 429) {
                        if (on429) return on429();
                        lastRateLimitMs = Date.now();
                        msg = 'Rate limited (HTTP 429) — wait a moment or switch models in Settings';
                    }
                    else if (provRes.statusCode === 401 || provRes.statusCode === 403)
                        msg = 'Invalid or unauthorised API key (HTTP ' + provRes.statusCode + ') — check Settings';
                    else if (provRes.statusCode >= 500)
                        msg = 'Provider server error (HTTP ' + provRes.statusCode + ') — try again';
                    try {
                        const e = JSON.parse(errBody);
                        // Try to extract the nested upstream error (e.g. OpenRouter wraps Crucible errors)
                        let detail = e.error?.message || e.message || '';
                        try {
                            const raw = e.error?.metadata?.raw;
                            if (raw) {
                                const inner = JSON.parse(raw);
                                const innerMsg = inner.error?.message || inner.message;
                                if (innerMsg) detail = innerMsg;
                            }
                        } catch (_) {}
                        if (detail) msg += ': ' + detail;
                    } catch (_) {
                        // Non-JSON body (e.g. HTML error page from HF Spaces) — log raw excerpt
                        const raw = errBody.replace(/[\r\n]+/g, ' ').replace(/<[^>]+>/g, '').trim().slice(0, 200);
                        if (raw) log('[provider-raw] ' + raw + '\n');
                    }
                    if (provRes.statusCode === 402) {
                        if (on402) return on402();
                        msg += ' — switch to a free model or add credits at openrouter.ai/credits';
                    }
                    if ((provRes.statusCode === 500 || provRes.statusCode === 503) && on5xx) return on5xx();
                    log('Provider error: ' + msg + '\n');
                    proxyError(res, provRes.statusCode, msg);
                });
                provRes.on('error', err => proxyError(res, 502, err.message));
                return;
            }

            // Streaming: convert OpenAI SSE → Anthropic SSE on the fly
            res.writeHead(200, {
                'Content-Type':  'text/event-stream',
                'Cache-Control': 'no-cache',
                'Connection':    'keep-alive',
            });

            const msgId = 'msg_' + Date.now();
            let outTokens   = 0;
            let providerUsage = null; // #7: real token usage from the final SSE chunk, if sent
            let buffer      = '';
            let headersSent = false;
            // tool_call index → {id, name, blockIdx} — tracks streaming tool call blocks
            let tcBlocks    = {};
            let nextBlockIdx = 1; // 0 = text block; tool blocks start at 1

            // Idle timer: if OpenRouter sends 200 OK but then stalls sending SSE events,
            // abort after 30 s rather than letting the claude --print 180 s timeout fire.
            function abortStalled() {
                // A deferral follow-up has its own 60 s postOai timeout and owns
                // finishStream — don't yank the stream out from under it.
                if (deferralPending) return;
                log('[proxy] stream idle timeout (30 s) — aborting stalled provider response\n');
                try { provRes.destroy(); } catch(_) {}
                // Close res directly in case destroy() doesn't fire error event synchronously
                if (headersSent) {
                    finishStream('end_turn');
                } else {
                    proxyError(res, 504, 'Provider stream idle timeout');
                }
            }
            let streamIdleTimer = setTimeout(abortStalled, 30000);
            function resetStreamIdle() {
                clearTimeout(streamIdleTimer);
                streamIdleTimer = setTimeout(abortStalled, 30000);
            }

            function sendEvent(event, data) {
                try { res.write('event: ' + event + '\ndata: ' + JSON.stringify(data) + '\n\n'); }
                catch (_) {}
            }

            let finished = false; // prevents duplicate stop events
            // Set true when an interception (tool_search/WebSearch) launches its
            // async follow-up. The provider's FIRST stream emits 'end' within ms of
            // [DONE] — long before the follow-up's fresh network round-trip resolves.
            // Without this guard, provRes.on('end') / abortStalled would call
            // finishStream() and close claude-code's res BEFORE the follow-up emits
            // its discovered tool_use (symptom: "AI response gone after thinking",
            // firstContent=false). When pending, the async chain owns finishStream.
            let deferralPending = false;

            function finishStream(stopReason) {
                if (finished) return;
                finished = true;
                // #8: stop all timers so a stale 30 s idle / 120 s request timeout can't
                // fire on an already-finished stream.
                clearTimeout(streamIdleTimer);
                try { provReq.setTimeout(0); } catch (_) {}
                sendEvent('content_block_stop', { type: 'content_block_stop', index: 0 });
                for (const tb of Object.values(tcBlocks)) {
                    if (tb.suppressed) continue; // tool_search was never opened — no stop
                    sendEvent('content_block_stop', { type: 'content_block_stop', index: tb.blockIdx });
                }
                // #7: report the provider's real output token count when it sent one;
                // fall back to the streamed-chunk estimate otherwise.
                const outTok = (providerUsage && (providerUsage.completion_tokens || providerUsage.output_tokens)) || outTokens;
                sendEvent('message_delta', {
                    type: 'message_delta',
                    delta: { stop_reason: stopReason || 'end_turn', stop_sequence: null },
                    usage: { output_tokens: outTok },
                });
                sendEvent('message_stop', { type: 'message_stop' });
                try { res.end(); } catch (_) {}
            }

            function ensureOpened() {
                if (headersSent) return;
                headersSent = true;
                sendEvent('message_start', {
                    type: 'message_start',
                    message: { id: msgId, type: 'message', role: 'assistant',
                               content: [], model: oaiReq.model, stop_reason: null,
                               usage: { input_tokens: 0, output_tokens: 0 } },
                });
                sendEvent('content_block_start', {
                    type: 'content_block_start', index: 0,
                    content_block: { type: 'text', text: '' },
                });
                sendEvent('ping', { type: 'ping' });
            }

            provRes.setEncoding('utf8');
            provRes.on('data', chunk => {
                resetStreamIdle();
                buffer += chunk;
                const lines = buffer.split('\n');
                buffer = lines.pop();

                for (const line of lines) {
                    const trimmed = line.trim();
                    if (!trimmed || !trimmed.startsWith('data:')) continue;
                    const raw = trimmed.slice(5).trim();
                    if (raw === '[DONE]') continue;

                    let event;
                    try { event = JSON.parse(raw); } catch (_) { continue; }
                    if (event.error) {
                        const errMsg = event.error.message || JSON.stringify(event.error).slice(0, 200);
                        log('[proxy] stream error from provider: ' + errMsg + '\n');
                        clearTimeout(streamIdleTimer);
                        // Provider sent an error inside the SSE stream — close the stream
                        // immediately with a visible error message. Without this, the proxy
                        // just continues waiting for events that never come, causing a 30 s
                        // idle timeout even for fast providers like Gemini.
                        if (headersSent) {
                            sendEvent('content_block_delta', { type: 'content_block_delta', index: 0,
                                delta: { type: 'text_delta', text: '\n\n⚠ ' + errMsg } });
                            finishStream('end_turn');
                        } else {
                            proxyError(res, 502, errMsg);
                        }
                        return;
                    }

                    ensureOpened();

                    // #7: many OAI providers send a final usage block (often with an
                    // empty choices array when stream_options.include_usage is on).
                    if (event.usage) providerUsage = event.usage;

                    const choice     = (event.choices || [])[0] || {};
                    const delta      = choice.delta || {};
                    const text       = delta.content || '';
                    const finishCode = choice.finish_reason;

                    if (text) {
                        outTokens++;
                        sendEvent('content_block_delta', {
                            type: 'content_block_delta', index: 0,
                            delta: { type: 'text_delta', text },
                        });
                    }

                    // Tool call deltas — convert to Anthropic tool_use content blocks
                    if (delta.tool_calls && delta.tool_calls.length > 0) {
                        for (const tc of delta.tool_calls) {
                            const tcIdx = tc.index !== undefined ? tc.index : 0;
                            if (!tcBlocks[tcIdx]) {
                                const nm = cleanToolName((tc.function || {}).name || '');
                                // SUPPRESS tool_search: it's our synthetic discovery tool —
                                // claude-code never declared it, so it must NOT be streamed
                                // as a tool_use (else "No such tool available: tool_search").
                                // We accumulate its args and resolve it at finish (below).
                                const suppressed = (nm === 'tool_search' && !!deferCatalog);
                                const blockIdx = suppressed ? -1 : nextBlockIdx++;
                                tcBlocks[tcIdx] = { id: tc.id, name: nm, blockIdx, argsAccum: '', sig: null, suppressed };
                                if (!suppressed) {
                                    sendEvent('content_block_start', {
                                        type: 'content_block_start', index: blockIdx,
                                        content_block: {
                                            type: 'tool_use', id: tc.id, name: nm, input: {}
                                        }
                                    });
                                    log('[proxy] stream: tool_use block — ' + nm + '\n');
                                } else {
                                    log('[proxy] stream: tool_search call (suppressed — resolving via catalog)\n');
                                }
                            }
                            const sig = extractThoughtSig(tc, delta, choice);
                            if (sig) tcBlocks[tcIdx].sig = sig;
                            const args = (tc.function || {}).arguments || '';
                            if (args) {
                                tcBlocks[tcIdx].argsAccum += args;
                                if (!tcBlocks[tcIdx].suppressed) {
                                    sendEvent('content_block_delta', {
                                        type: 'content_block_delta', index: tcBlocks[tcIdx].blockIdx,
                                        delta: { type: 'input_json_delta', partial_json: args }
                                    });
                                }
                            }
                        }
                    }

                    if (finishCode && !finished) {
                        log('[proxy] finish_reason=' + finishCode + ' tokens=' + outTokens + '\n');

                        // ── tool_search interception (reactive deferral, Phase 2) ─────────
                        // The model asked for tools it doesn't have. Match the deferred
                        // catalog, then re-call the provider WITH the discovered tools added
                        // and stream that result to claude-code (text or a real tool_use).
                        // claude-code never sees tool_search — it only sees the resolved turn.
                        // NB: gated on the PRESENCE of a (suppressed) tool_search call, NOT on
                        // finishCode — Gemini emits the call with finish_reason='stop', so a
                        // `=== 'tool_calls'` gate misses it entirely. Fires only when no OTHER
                        // (real) tool was called this step, so real tools aren't swallowed.
                        if (deferCatalog) {
                            const tbVals = Object.values(tcBlocks);
                            const searchCall = tbVals.find(b => b.name === 'tool_search');
                            const otherCalls = tbVals.filter(b => b.name !== 'tool_search');
                            if (searchCall && otherCalls.length === 0) {
                                const sb = searchCall;
                                let query = '';
                                try { query = (JSON.parse(sb.argsAccum || '{}') || {}).query || ''; } catch (_) {}
                                const matched = matchDeferredTools(query, deferCatalog);
                                log('[proxy] tool_search query=' + JSON.stringify(query) +
                                    ' → ' + matched.map(m => m.function.name).join(',') + '\n');
                                deferralPending = true; // async chain owns finishStream
                                (async () => {
                                    try {
                                        // Echo the model's tool_search call + a tool result that
                                        // names the loaded tools (keeps OAI tool-call history valid).
                                        const assistantMsg = {
                                            role: 'assistant', content: null,
                                            tool_calls: [{ id: sb.id, type: 'function',
                                                function: { name: 'tool_search', arguments: sb.argsAccum || '{}' } }],
                                        };
                                        const toolMsg = {
                                            role: 'tool', tool_call_id: sb.id,
                                            content: 'Loaded tools: ' + matched.map(m => m.function.name).join(', ') +
                                                '. They are now available — call the one you need.',
                                        };
                                        // Follow-up tools = everything we already sent (incl. tool_search,
                                        // so the history ref stays valid) + the discovered tools, deduped.
                                        const seen = new Set();
                                        const followTools = [];
                                        for (const t of (oaiReq.tools || []).concat(matched)) {
                                            const n = t.function && t.function.name;
                                            if (n && !seen.has(n)) { seen.add(n); followTools.push(t); }
                                        }
                                        const followReq = Object.assign({}, oaiReq, {
                                            messages: [...(oaiReq.messages || []), assistantMsg, toolMsg],
                                            tools: followTools,
                                        });
                                        delete followReq.tool_choice;
                                        const parsed = await postOai(baseUrl, apiKey, followReq);
                                        const anth = oaiToAnth(parsed, oaiReq.model);
                                        // Serialize the resolved message into the live SSE stream.
                                        let emittedTool = false;
                                        for (const block of (anth.content || [])) {
                                            if (block.type === 'text' && block.text) {
                                                outTokens++;
                                                sendEvent('content_block_delta', { type: 'content_block_delta',
                                                    index: 0, delta: { type: 'text_delta', text: block.text } });
                                            } else if (block.type === 'tool_use' && block.name !== 'tool_search') {
                                                const bi = nextBlockIdx++;
                                                sendEvent('content_block_start', { type: 'content_block_start',
                                                    index: bi, content_block: { type: 'tool_use', id: block.id,
                                                        name: block.name, input: {} } });
                                                sendEvent('content_block_delta', { type: 'content_block_delta',
                                                    index: bi, delta: { type: 'input_json_delta',
                                                        partial_json: JSON.stringify(block.input || {}) } });
                                                tcBlocks['fu_' + bi] = { id: block.id, name: block.name,
                                                    blockIdx: bi, argsAccum: JSON.stringify(block.input || {}), sig: null };
                                                emittedTool = true;
                                                log('[proxy] tool_search→discovered call: ' + block.name + '\n');
                                            }
                                        }
                                        finishStream(emittedTool ? 'tool_use' : 'end_turn');
                                    } catch (e) {
                                        log('[proxy] tool_search resolution failed: ' + e.message + '\n');
                                        // Fail safe: tell the user, close cleanly. Never hang.
                                        sendEvent('content_block_delta', { type: 'content_block_delta', index: 0,
                                            delta: { type: 'text_delta',
                                                text: '\n\n⚠ Could not load additional tools — try rephrasing or !defer off.' } });
                                        finishStream('end_turn');
                                    }
                                })();
                                return; // async chain calls finishStream
                            }
                        }

                        // ── WebSearch local execution ────────────────────────────────────
                        // When the provider returns tool_calls for WebSearch or web_search,
                        // execute them locally via DuckDuckGo, inject the tool results, and
                        // make a follow-up non-streaming call. claude-code never sees the
                        // tool_use event — it receives a direct text response instead.
                        if (finishCode === 'tool_calls') {
                            const wsCalls = Object.values(tcBlocks).filter(b =>
                                b.name === 'WebSearch' || b.name === 'web_search');
                            if (wsCalls.length > 0) {
                                log('[proxy] intercepting ' + wsCalls.length + ' WebSearch call(s) — executing locally\n');
                                deferralPending = true; // async chain owns finishStream
                                (async () => {
                                    try {
                                        const results = await Promise.all(wsCalls.map(async tb => {
                                            let query = '';
                                            try { query = (JSON.parse(tb.argsAccum || '{}') || {}).query || ''; } catch(_) {}
                                            log('[proxy] WebSearch local: ' + JSON.stringify(query) + '\n');
                                            if (!query) return { id: tb.id, content: 'No search query provided.' };
                                            const r = await webSearch(query, 5, FILES_DIR);
                                            return { id: tb.id, content: r.content };
                                        }));
                                        // #5: echo ONLY the WebSearch calls — including
                                        // other tool calls here leaves them without a
                                        // matching tool result → strict OAI endpoints 400.
                                        const assistantMsg = {
                                            role: 'assistant',
                                            content: null,
                                            tool_calls: wsCalls.map(tb => ({
                                                id: tb.id, type: 'function',
                                                function: { name: tb.name, arguments: tb.argsAccum || '{}' }
                                            }))
                                        };
                                        const toolMsgs = results.map(r => ({
                                            role: 'tool', tool_call_id: r.id, content: r.content
                                        }));
                                        const followReq = Object.assign({}, oaiReq, {
                                            messages: [...(oaiReq.messages || []), assistantMsg, ...toolMsgs],
                                            stream: false,
                                            tools: undefined,
                                            tool_choice: undefined,
                                        });
                                        const followText = await new Promise((resolve, reject) => {
                                            const followBody = JSON.stringify(followReq);
                                            let tgt;
                                            try {
                                                const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
                                                tgt = new URL(base + '/chat/completions');
                                            } catch(e) { return reject(e); }
                                            const followHeaders = {
                                                'Content-Type':   'application/json',
                                                'Content-Length': Buffer.byteLength(followBody),
                                                'Authorization':  'Bearer ' + apiKey,
                                                'User-Agent':     'Mozilla/5.0 (Linux; Android 10) ClaudeCodeSetup/1.0',
                                                'Accept':         'application/json',
                                            };
                                            if (tgt.hostname.includes('openrouter')) {
                                                followHeaders['HTTP-Referer'] = 'https://github.com/fahmi304/Nexus-Mind';
                                                followHeaders['X-Title']      = 'Nexus Mind';
                                            }
                                            const lib2 = tgt.protocol === 'https:' ? https : http;
                                            const fr = lib2.request({
                                                hostname: tgt.hostname,
                                                port: tgt.port || (tgt.protocol === 'https:' ? 443 : 80),
                                                method: 'POST',
                                                path: tgt.pathname + (tgt.search || ''),
                                                headers: followHeaders,
                                            }, fRes => {
                                                let buf = '';
                                                fRes.setEncoding('utf8');
                                                fRes.on('data', c => { buf += c; });
                                                fRes.on('end', () => {
                                                    try {
                                                        const parsed = JSON.parse(buf);
                                                        const text = ((parsed.choices || [])[0] || {}).message?.content || '';
                                                        resolve(text);
                                                    } catch(e) { resolve(''); }
                                                });
                                                fRes.on('error', reject);
                                            });
                                            fr.setTimeout(60000, () => {
                                                fr.destroy();
                                                reject(new Error('WebSearch follow-up timeout'));
                                            });
                                            fr.on('error', reject);
                                            fr.write(followBody);
                                            fr.end();
                                        });
                                        if (followText) {
                                            outTokens++;
                                            sendEvent('content_block_delta', {
                                                type: 'content_block_delta', index: 0,
                                                delta: { type: 'text_delta', text: followText },
                                            });
                                        }
                                        log('[proxy] WebSearch follow-up complete\n');
                                        finishStream('end_turn');
                                    } catch(e) {
                                        log('[proxy] WebSearch local execution failed: ' + e.message + '\n');
                                        finishStream('end_turn');
                                    }
                                })();
                                return; // async chain calls finishStream when done
                            }
                        }
                        // ── End WebSearch interception ───────────────────────────────────

                        // Log each completed tool call's accumulated arguments so a
                        // malformed/empty call (e.g. Write with no content → file never
                        // lands despite "Write ran ✓") is visible in !log. Truncated.
                        for (const tb of Object.values(tcBlocks)) {
                            const a = (tb.argsAccum || '').replace(/\s+/g, ' ').slice(0, 300);
                            log('[proxy] tool-call: ' + tb.name + ' args=' + (a || '(empty)') + '\n');
                            if (tb.id && tb.sig) {
                                storeThoughtSig(tb.id, tb.sig);
                                log('[proxy] captured thought_signature for ' + tb.name + ' (' + tb.id + ')\n');
                            }
                        }

                        // Model finished but sent no text and no tool calls → inject visible error
                        if (outTokens === 0 && Object.keys(tcBlocks).length === 0) {
                            log('[proxy] outTokens=0 — injecting empty-response error\n');
                            sendEvent('content_block_delta', {
                                type: 'content_block_delta', index: 0,
                                delta: { type: 'text_delta',
                                         text: '⚠ Model returned empty response. It may be busy or overloaded — try again or switch models in Settings.' }
                            });
                        }
                        const stopReason = finishCode === 'tool_calls' ? 'tool_use'
                                         : finishCode === 'length'     ? 'max_tokens'
                                         : 'end_turn';
                        finishStream(stopReason);
                    }
                }
            });

            provRes.on('end', () => {
                clearTimeout(streamIdleTimer);
                // An interception's async follow-up is resolving — it will call
                // finishStream when done. Do NOT finalize here or we close the
                // stream before the discovered tool_use is emitted.
                if (deferralPending) return;
                if (!finished) {
                    // Stream ended without a finish_reason — or provider sent nothing at all.
                    // If no text was produced, inject a visible error so the user gets feedback
                    // instead of an empty bubble.
                    if (outTokens === 0 && Object.keys(tcBlocks).length === 0) {
                        log('[proxy] empty stream (outTokens=0, no tool calls) — injecting error text\n');
                        ensureOpened();
                        const errText = headersSent
                            ? '⚠ Model returned empty response. It may be busy or not support this prompt. Try again or switch models in Settings.'
                            : '⚠ Provider sent no data. Check your API key or try a different model.';
                        sendEvent('content_block_delta', {
                            type: 'content_block_delta', index: 0,
                            delta: { type: 'text_delta', text: errText }
                        });
                    }
                    finishStream('end_turn');
                }
            });
            provRes.on('error', err => {
                clearTimeout(streamIdleTimer);
                log('[proxy] provider response error: ' + err.message + '\n');
                if (headersSent) {
                    finishStream('end_turn');
                } else {
                    proxyError(res, 502, 'Provider disconnected: ' + err.message);
                }
            });
        }
        // (#6: the duplicate catch-all provRes.on('error') was removed — each branch
        //  now registers its own handler that actually closes res.)
    });

    provReq.on('error', err => {
        log('[proxy] provReq error: ' + err.message + '\n');
        proxyError(res, 502, 'Provider unreachable: ' + err.message);
    });
    provReq.setTimeout(120000, () => {
        log('[proxy] provReq timeout (120s) — provider never responded\n');
        provReq.destroy();
        proxyError(res, 504, 'Provider timeout');
    });

    provReq.write(body);
    provReq.end();
}

// ─── TCP bridge server ────────────────────────────────────────────────────────
// Claude Code has no interactive mode without a real PTY. We use --print mode:
// each complete message the user sends spawns one `claude --print` process,
// which reads the message from stdin, calls the API, streams the response back,
// and exits. The socket stays open for the next message.

function buildEnv() {
    const cfg = readConfig();
    const env = {
        HOME: FILES_DIR,
        TERM: 'xterm-256color',
        // The bridge's own `$ cmd` shell commands run via /system/bin/sh. (P4: the
        // CLAUDE_CODE_SHELL → BIN_DIR/bash symlink dance was a legacy 2.1.112/Bionic
        // hack for claude-code's Bash tool; the proot guest sets its own SHELL=/bin/bash,
        // so this value is host-side only now.)
        SHELL: '/system/bin/sh',
        LANG: 'en_US.UTF-8',
        LINES: '50',
        COLUMNS: '160',
        // NOTE (2026-05-30 experiment): CLAUDE_CODE_SANDBOXED='1' was REMOVED here.
        // It was set only to skip the "do you trust this folder?" prompt, but it also
        // appears to switch claude-code's Bash TOOL into workspace-only-writes sandbox
        // mode — Bash writes to /sdcard silently no-op while reporting "✓ Completed"
        // (see Known gaps: Bash tool sandbox). Trust is now granted explicitly per-cwd
        // via ensureProjectTrusted() (writes hasTrustDialogAccepted into ~/.claude.json)
        // before each spawn. REVERT (re-add this line) if the trust dialog reappears and
        // hangs print mode (inv 5b family) — that means ensureProjectTrusted's shape is
        // wrong and the env bypass is still needed.
        // Include npm-global bin and a bundled-binaries dir (for future git/gh bundles)
        // FILES_DIR/bin first so our claude/node wrappers take precedence over npm bin scripts
        PATH: (process.env.PATH || '/system/bin:/system/xbin') +
              ':' + path.join(FILES_DIR, 'bin') +
              ':' + path.join(NPM_PREFIX, 'bin'),
        LD_LIBRARY_PATH: NATIVE_DIR,
    };

    const isSubscription = cfg.mode === 'subscription';

    if (isSubscription) {
        // Direct Anthropic API: real sk-ant-... key, no base URL override
        if (cfg.apiKey) env.ANTHROPIC_API_KEY = cfg.apiKey;
    } else {
        // Proxy mode: all claude-code traffic goes to our local proxy on port 8082.
        //
        // ANTHROPIC_API_KEY must start with 'sk-ant-' — claude-code v2.1.112 validates
        // the format before any network call. The proxy ignores the Bearer token it
        // receives and uses cfg.apiKey (the real provider key) for outbound requests.
        //
        // Do NOT set CLAUDE_CODE_OAUTH_TOKEN alongside this — that triggers an auth
        // conflict check in claude-code which shows a warning, displays the welcome
        // banner, and drops into an interactive login flow. The customApiKeyResponses
        // approval list (patched in settings.json below) is what makes claude-code
        // accept this key silently without showing the login selector.
        env.ANTHROPIC_API_KEY  = 'sk-ant-proxy000';
        env.ANTHROPIC_BASE_URL = cfg.baseUrl || 'http://127.0.0.1:8082';
    }

    // In subscription mode, pass the real model. In proxy mode, claude-code validates
    // ANTHROPIC_MODEL against known Claude model names at startup (before any API call).
    // A provider model ID like "openai/gpt-oss-120b:free" fails that check → silent
    // exit code 1. Use a valid Claude name instead; the proxy substitutes cfg.modelId
    // (the real provider model) when it forwards the request to the provider.
    env.ANTHROPIC_MODEL = isSubscription
        ? (cfg.modelId || 'claude-3-5-sonnet-20241022')
        : 'claude-3-5-sonnet-20241022';

    env.DISABLE_AUTOUPDATER = '1';
    // MCP startup/tool timeouts — the HTTP MCP shim does a lazy upstream init
    // (initialize + tools/list round-trips to a remote server) on claude-code's
    // first tools/list. claude-code's default MCP connect timeout can fire before
    // those remote hops complete, so the server is dropped and its tools never
    // reach the model. Give it generous headroom.
    env.MCP_TIMEOUT      = '30000';
    env.MCP_TOOL_TIMEOUT = '30000';
    // Android has no /tmp — point Node.js temp files to app's files dir
    env.TMPDIR = FILES_DIR;
    env.TEMP   = FILES_DIR;
    env.TMP    = FILES_DIR;
    return env;
}

function sanitizeKey(key) {
    if (!key || key.length < 8) return '(not set)';
    return key.slice(0, 6) + '…' + key.slice(-4);
}

// ─── Per-session disk persistence ─────────────────────────────────────────────
// Saves/restores hasHistory, cwd, sessionTokens so bridge restarts don't lose state.
// File: filesDir/sessions/<sid>.json   TTL: 24 hours

function sessionFilePath(sid) {
    return path.join(SESSIONS_DIR, 'sess_' + String(sid).replace(/[^a-zA-Z0-9_-]/g, '_') + '.json');
}

function loadSessionState(sid) {
    try {
        fs.mkdirSync(SESSIONS_DIR, { recursive: true });
        const p = sessionFilePath(sid);
        if (!fs.existsSync(p)) return null;
        const data = JSON.parse(fs.readFileSync(p, 'utf8'));
        // 24-hour TTL
        if (!data.savedAt || (Date.now() - data.savedAt) > 86400000) {
            try { fs.unlinkSync(p); } catch(_) {}
            return null;
        }
        return data;
    } catch(_) { return null; }
}

function saveSessionState(sid, state) {
    try {
        fs.mkdirSync(SESSIONS_DIR, { recursive: true });
        const data = {
            savedAt: Date.now(),
            hasHistory: state.hasHistory,
            cwd: state.cwd,
            sessionTokens: state.sessionTokens || 0,
        };
        fs.writeFileSync(sessionFilePath(sid), JSON.stringify(data));
    } catch(_) {}
}

function clearSessionState(sid) {
    try { fs.unlinkSync(sessionFilePath(sid)); } catch(_) {}
}

// Wipe claude-code's own session files so --continue can't resurrect old history.
// Called on !clear and whenever model/provider changes.
function clearClaudeSessionFiles() {
    try {
        const dir = path.join(FILES_DIR, '.claude', 'projects');
        if (!fs.existsSync(dir)) { log('[clear] no claude projects dir to clear\n'); return; }
        const entries = fs.readdirSync(dir);
        for (const entry of entries) {
            try { fs.rmSync(path.join(dir, entry), { recursive: true, force: true }); } catch(_) {}
        }
        log('[clear] wiped ' + entries.length + ' claude session(s) from ' + dir + '\n');
    } catch(e) { log('[clear] clearClaudeSessionFiles error: ' + e.message + '\n'); }
}

// Strip ANSI escape codes so captured responses store clean text in history.
// Also strips OSC sequences (\x1b]...\x07), DCS/PM/APC/SOS/ST sequences, and C1 CSI.
function stripAnsi(str) {
    return str.replace(/\x1B\[[0-9;]*[a-zA-Z]|\x1b\][^\x07]*\x07|\x1b[PX^_].*?\x1b\\|\x9b[0-9;]*[a-zA-Z]/g, '').replace(/\r/g, '').trim();
}

// Simple line-diff for code diff visualization (shows +/- lines between old and new text)
function lineDiff(oldText, newText) {
    const o = (oldText || '').split('\n');
    const n = (newText || '').split('\n');
    const result = [];
    let oi = 0, ni = 0;
    while (oi < o.length || ni < n.length) {
        const ol = o[oi], nl = n[ni];
        if (oi < o.length && ni < n.length && ol === nl) { oi++; ni++; }
        else if (oi < o.length && (ni >= n.length || ol !== nl)) {
            result.push('-' + ol); oi++;
            if (ni < n.length && nl !== ol) { result.push('+' + nl); ni++; }
        } else {
            result.push('+' + nl); ni++;
        }
    }
    return result.filter(l => l[0] === '+' || l[0] === '-').slice(0, 60).join('\n');
}

// ── stdio MCP client ──────────────────────────────────────────────────────────
// Manages child processes that speak MCP JSON-RPC 2.0 over stdin/stdout.
// Each server entry in filesDir/mcp_stdio.json: { name, command, args[] }
// Tools discovered via `tools/list` are injected into the agentic tool list.

const MCP_STDIO_CONFIG  = path.join(FILES_DIR, 'mcp_stdio.json');
const MCP_CONFIG_FILE   = path.join(FILES_DIR, 'mcp_config.json');

// P4: buildMcpServersObj() + MCP_SPAWN_CONFIG (the legacy stdio-shim --mcp-config
// builder for the libnode engine) DELETED — proot uses native HTTP MCP
// (writeProotMcpConfig). Keeping it would have wired the inv-51 Bionic shim path.

// Write the --mcp-config file for a spawn; returns its path, or null if no
// servers are configured (so the caller omits the flag entirely).
// P4: writeSpawnMcpConfig() (the legacy stdio-shim --mcp-config for the libnode
// engine) was DELETED — the proot engine uses native HTTP MCP via writeProotMcpConfig.

// P3b — write a NATIVE HTTP mcp-config for the PROOT guest. On 2.1.160/glibc the
// inv-51 spawn-hang on type:http servers is gone (probed b26–b28), so we hand
// claude-code the HTTP servers directly (no stdio shim needed — that was the
// Bionic workaround). The file is written under FILES_DIR/.claude, which is bound
// to the guest's /root/.claude, so we return the GUEST-visible path. Returns null
// when no HTTP MCP server is configured (caller omits --mcp-config).
//
// NOTE: native HTTP servers register as "pending" and connect ASYNC — the model
// must call WaitForMcpServers before the tools appear (b28). runMessage pairs this
// with an --append-system-prompt nudge so the model knows to wait.
const PROOT_MCP_GUEST_PATH = '/root/.claude/mcp_guest_config.json';
function writeProotMcpConfig() {
    try {
        let httpEntries = [];
        try { if (fs.existsSync(MCP_HTTP_CONFIG)) httpEntries = JSON.parse(fs.readFileSync(MCP_HTTP_CONFIG, 'utf8')) || []; } catch (_) {}
        httpEntries = (Array.isArray(httpEntries) ? httpEntries : [])
            .filter(e => e && e.name && e.url && e.enabled !== false);
        const hostPath = path.join(FILES_DIR, '.claude', 'mcp_guest_config.json');
        if (!httpEntries.length) {
            try { if (fs.existsSync(hostPath)) fs.unlinkSync(hostPath); } catch (_) {}
            return null;
        }
        const mcpServers = {};
        for (const up of httpEntries) {
            const safe = String(up.name).replace(/[^a-zA-Z0-9_-]/g, '_');
            mcpServers[safe] = { type: 'http', url: String(up.url), headers: up.headers || {} };
        }
        try { fs.mkdirSync(path.join(FILES_DIR, '.claude'), { recursive: true }); } catch (_) {}
        fs.writeFileSync(hostPath, JSON.stringify({ mcpServers }, null, 2));
        log('[mcp-proot] --mcp-config (native http) servers: ' + Object.keys(mcpServers).join(', ') + '\n');
        return PROOT_MCP_GUEST_PATH;
    } catch (e) {
        log('[mcp-proot] writeProotMcpConfig error: ' + e.message + '\n');
        return null;
    }
}
// Nudge appended to the system prompt when proot MCP is active — native HTTP MCP
// servers connect async (pending at turn start), so a model that wants an mcp__
// tool must wait for the connection first (b28).
const PROOT_MCP_SYS_NUDGE =
    'Some tools are provided by MCP servers that connect a moment after this turn ' +
    'begins (they appear as tools named mcp__<server>__<tool>). If you intend to use ' +
    'any MCP tool and it is not yet listed, FIRST call the WaitForMcpServers tool and ' +
    'wait until it reports ready, then use the MCP tool.';

const mcpStdioServers = new Map(); // name → { proc, tools, pendingCbs, msgId, buf }
// MCP-7: per-server restart counters for crash auto-reconnect. Cleared after
// 5 min of stable uptime (see setTimeout in startMcpStdioServer success path).
const mcpStdioRestartAttempts = new Map(); // name → integer
const MCP_MAX_RESTARTS = 5;

function scheduleMcpStdioRestart(entry) {
    const attempts = (mcpStdioRestartAttempts.get(entry.name) || 0) + 1;
    mcpStdioRestartAttempts.set(entry.name, attempts);
    if (attempts > MCP_MAX_RESTARTS) {
        log('[mcp-stdio:' + entry.name + '] giving up after ' + MCP_MAX_RESTARTS + ' restart attempts\n');
        mcpFailed.set(entry.name, { type: 'stdio', error: 'crashed ' + MCP_MAX_RESTARTS + 'x — auto-restart abandoned' });
        broadcastMcpReady();
        return;
    }
    const delayMs = Math.min(30000, 1000 * Math.pow(2, attempts - 1)); // 1s,2s,4s,8s,16s
    log('[mcp-stdio:' + entry.name + '] restart attempt ' + attempts + '/' + MCP_MAX_RESTARTS + ' in ' + delayMs + 'ms\n');
    setTimeout(() => {
        // Skip if the entry was removed in the meantime by a reload.
        let entries = [];
        try { if (fs.existsSync(MCP_STDIO_CONFIG)) entries = JSON.parse(fs.readFileSync(MCP_STDIO_CONFIG, 'utf8')) || []; } catch (_) {}
        const stillWanted = Array.isArray(entries) && entries.some(e => e && e.name === entry.name);
        if (!stillWanted) {
            log('[mcp-stdio:' + entry.name + '] no longer in config, skipping restart\n');
            mcpStdioRestartAttempts.delete(entry.name);
            return;
        }
        startMcpStdioServer(entry)
            .then(() => broadcastMcpReady())
            .catch(e => log('[mcp-stdio:' + entry.name + '] restart error: ' + e.message + '\n'));
    }, delayMs);
}

function mcpSend(srv, method, params) {
    return new Promise((resolve, reject) => {
        if (!srv.proc || srv.proc.exitCode !== null) return reject(new Error('MCP process not running'));
        const id = ++srv.msgId;
        srv.pendingCbs.set(id, { resolve, reject });
        const msg = JSON.stringify({ jsonrpc: '2.0', id, method, params: params || {} });
        try { srv.proc.stdin.write(msg + '\n'); } catch(e) { reject(e); }
        setTimeout(() => {
            if (srv.pendingCbs.has(id)) {
                srv.pendingCbs.delete(id);
                reject(new Error('MCP timeout: ' + method));
            }
        }, 10000);
    });
}

function mcpHandleData(srv, chunk) {
    srv.buf += chunk.toString();
    const lines = srv.buf.split('\n');
    srv.buf = lines.pop(); // keep incomplete last line
    for (const line of lines) {
        if (!line.trim()) continue;
        try {
            const msg = JSON.parse(line);
            if (msg.id !== undefined && srv.pendingCbs.has(msg.id)) {
                const cb = srv.pendingCbs.get(msg.id);
                srv.pendingCbs.delete(msg.id);
                if (msg.error) cb.reject(new Error(msg.error.message || JSON.stringify(msg.error)));
                else cb.resolve(msg.result);
            }
        } catch(_) {}
    }
}

async function startMcpStdioServer(entry) {
    if (mcpStdioServers.has(entry.name)) return; // already running
    // MCP-5: keep last N stderr lines per server, line-split so multi-line
    // stack traces stay grouped. Surfaced via !mcp-log.
    const srv = { proc: null, tools: [], pendingCbs: new Map(), msgId: 0, buf: '', stderrLines: [], stderrBuf: '' };
    try {
        const args = entry.args || [];
        const cmd = entry.command;
        // Resolve relative command paths
        const resolvedCmd = path.isAbsolute(cmd) ? cmd :
            (fs.existsSync(path.join(BIN_DIR, cmd)) ? path.join(BIN_DIR, cmd) : cmd);
        srv.proc = spawn(resolvedCmd, args, {
            env: Object.assign({}, buildEnv()),
            stdio: ['pipe', 'pipe', 'pipe']
        });
        srv.proc.stdout.on('data', d => mcpHandleData(srv, d));
        // MCP-5: buffer until newline, then push to in-memory ring + setup.log
        srv.proc.stderr.on('data', d => {
            srv.stderrBuf += d.toString();
            let nl;
            while ((nl = srv.stderrBuf.indexOf('\n')) >= 0) {
                const ln = srv.stderrBuf.slice(0, nl).replace(/\r$/, '');
                srv.stderrBuf = srv.stderrBuf.slice(nl + 1);
                if (ln.length === 0) continue;
                srv.stderrLines.push(ln);
                if (srv.stderrLines.length > 200) srv.stderrLines.shift();
                log('[mcp-stdio:' + entry.name + '] stderr: ' + ln + '\n');
            }
        });
        srv.proc.on('exit', code => {
            log('[mcp-stdio:' + entry.name + '] exited code=' + code + '\n');
            mcpStdioServers.delete(entry.name);
            // MCP-7: auto-reconnect on unexpected exit. Skip when reload/stop
            // marked the kill as intentional, or when the initial spawn failed
            // (the catch block below handles that path separately).
            if (!srv._intentionalKill && srv._startedAt) {
                scheduleMcpStdioRestart(entry);
            }
        });
        mcpStdioServers.set(entry.name, srv);
        // MCP handshake
        await mcpSend(srv, 'initialize', {
            protocolVersion: '2024-11-05',
            capabilities: { tools: {} },
            clientInfo: { name: 'ClaudeCodeSetup', version: '1.0' }
        });
        srv.proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} }) + '\n');
        // Discover tools
        const toolsResult = await mcpSend(srv, 'tools/list', {});
        srv.tools = (toolsResult.tools || []).map(t => ({
            name: 'mcp_' + entry.name + '_' + t.name,
            description: (t.description || '') + ' [MCP:' + entry.name + ']',
            input_schema: t.inputSchema || { type: 'object', properties: {} },
            _mcpServer: entry.name,
            _mcpTool: t.name
        }));
        log('[mcp-stdio:' + entry.name + '] ready, ' + srv.tools.length + ' tools\n');
        srv._startedAt = Date.now(); // MCP-7: marker for "actually came up", gates restart
        mcpFailed.delete(entry.name); // MCP-4
        // MCP-7: reset restart counter after 5 min of stable uptime.
        setTimeout(() => {
            const cur = mcpStdioServers.get(entry.name);
            if (cur && cur._startedAt === srv._startedAt) mcpStdioRestartAttempts.delete(entry.name);
        }, 5 * 60 * 1000);
    } catch(e) {
        log('[mcp-stdio:' + entry.name + '] start failed: ' + e.message + '\n');
        mcpStdioServers.delete(entry.name);
        mcpFailed.set(entry.name, { type: 'stdio', error: e.message || String(e) }); // MCP-4
    }
}

function getMcpStdioTools() {
    const tools = [];
    for (const [name, srv] of mcpStdioServers.entries()) {
        // MCP-9: drop tools the user disabled for this server.
        for (const t of srv.tools) {
            if (!isToolDisabled(name, t._mcpTool)) tools.push(t);
        }
    }
    return tools;
}

async function callMcpStdioTool(toolName, args) {
    // toolName is like "mcp_<server>_<tool>"
    for (const [name, srv] of mcpStdioServers.entries()) {
        const found = srv.tools.find(t => t.name === toolName);
        if (found) {
            const result = await mcpSend(srv, 'tools/call', { name: found._mcpTool, arguments: args });
            const content = result.content || [];
            return content.map(c => c.text || JSON.stringify(c)).join('\n');
        }
    }
    return 'MCP tool not found: ' + toolName;
}

async function loadMcpStdioServers() {
    try {
        if (!fs.existsSync(MCP_STDIO_CONFIG)) return;
        const entries = JSON.parse(fs.readFileSync(MCP_STDIO_CONFIG, 'utf8'));
        for (const entry of (Array.isArray(entries) ? entries : [])) {
            if (entry.name && entry.command) {
                await startMcpStdioServer(entry).catch(e => log('[mcp-stdio] ' + e.message + '\n'));
            }
        }
    } catch(e) {
        log('[mcp-stdio] loadMcpStdioServers error: ' + e.message + '\n');
    }
    broadcastMcpReady();
}

// ── HTTP MCP client ────────────────────────────────────────────────────────────
// Speaks MCP JSON-RPC 2.0 over Streamable HTTP (2025-03-26).
// Each entry in filesDir/mcp_http.json: { name, url }

const MCP_HTTP_CONFIG = path.join(FILES_DIR, 'mcp_http.json');
const mcpHttpServers  = new Map(); // name → { url, sessionId, tools }
const mcpFailed       = new Map(); // MCP-4: name → { type: 'http'|'stdio', error: string }
let mcpReadyInfo = null; // cached MCP status broadcast to sessions on attach

function buildMcpPayload() {
    const servers = [];
    for (const [name, srv] of mcpHttpServers.entries()) {
        servers.push({ name, type: 'http', status: 'connected', tools: srv.tools.map(t => ({ name: t._mcpTool, description: (t.description || '').split(' [MCP:')[0] })) });
    }
    for (const [name, srv] of mcpStdioServers.entries()) {
        servers.push({ name, type: 'stdio', status: 'connected', tools: srv.tools.map(t => ({ name: t._mcpTool, description: (t.description || '').split(' [MCP:')[0] })) });
    }
    // MCP-4: include configured-but-failed servers so the chip can show N/M.
    for (const [name, info] of mcpFailed.entries()) {
        servers.push({ name, type: info.type, status: 'failed', error: info.error, tools: [] });
    }
    return servers;
}

function broadcastMcpReady() {
    const servers = buildMcpPayload();
    mcpReadyInfo = servers;
    writeMcpToolCache(); // MCP-9
    if (servers.length === 0) return;
    const b64 = Buffer.from(JSON.stringify(servers)).toString('base64');
    const osc = '\x1b]9;mcp-ready:' + b64 + '\x07';
    for (const state of activeSessions.values()) {
        if (state.socket) try { state.socket.write(osc); } catch(_) {}
    }
}

function mcpHttpPost(url, body, sessionId, extraHeaders) {
    return new Promise((resolve, reject) => {
        const bodyStr = JSON.stringify(body);
        const parsed  = new URL(url);
        const isHttps = parsed.protocol === 'https:';
        const mod     = isHttps ? https : http;
        const headers = {
            'Content-Type':   'application/json',
            'Accept':         'application/json, text/event-stream',
            'Content-Length': Buffer.byteLength(bodyStr),
        };
        if (sessionId) headers['mcp-session-id'] = sessionId;
        // MCP-2: per-server auth headers from mcp_http.json
        if (extraHeaders && typeof extraHeaders === 'object') {
            for (const k of Object.keys(extraHeaders)) headers[k] = extraHeaders[k];
        }
        const req = mod.request({
            hostname: parsed.hostname,
            port:     parseInt(parsed.port) || (isHttps ? 443 : 80),
            path:     parsed.pathname + (parsed.search || ''),
            method:   'POST',
            headers,
        }, res => {
            const sid = res.headers['mcp-session-id'] || null;
            const ct  = (res.headers['content-type'] || '').toLowerCase();
            let buf = '';
            res.setEncoding('utf8');
            res.on('data', c => buf += c);
            res.on('end', () => {
                if (res.statusCode === 202) { resolve({ _sid: sid }); return; }
                if (res.statusCode < 200 || res.statusCode >= 300) {
                    return reject(new Error('HTTP ' + res.statusCode + ': ' + buf.slice(0, 120)));
                }
                if (ct.includes('text/event-stream')) {
                    const events = [];
                    for (const line of buf.split('\n')) {
                        const t = line.trim();
                        if (!t.startsWith('data:')) continue;
                        const raw = t.slice(5).trim();
                        if (!raw || raw === '[DONE]') continue;
                        try { events.push(JSON.parse(raw)); } catch(_) {}
                    }
                    const rpc = events.find(e => e.id !== undefined) || events[0] || {};
                    rpc._sid = sid;
                    resolve(rpc);
                } else {
                    try { const r = JSON.parse(buf); r._sid = sid; resolve(r); }
                    catch(e) { reject(new Error('MCP HTTP bad JSON: ' + buf.slice(0, 60))); }
                }
            });
            res.on('error', reject);
        });
        req.setTimeout(15000, () => req.destroy(new Error('MCP HTTP timeout')));
        req.on('error', reject);
        req.write(bodyStr);
        req.end();
    });
}

async function startMcpHttpServer(entry) {
    if (mcpHttpServers.has(entry.name)) return;
    const hdrs = entry.headers || {};  // MCP-2
    const srv = { url: entry.url, sessionId: null, tools: [], headers: hdrs };
    try {
        const initRes = await mcpHttpPost(entry.url, {
            jsonrpc: '2.0', id: 1, method: 'initialize',
            params: {
                protocolVersion: '2025-03-26',
                capabilities: { tools: {} },
                clientInfo: { name: 'ClaudeCodeSetup', version: '1.0' },
            },
        }, null, hdrs);
        if (initRes.error) throw new Error(initRes.error.message || JSON.stringify(initRes.error));
        if (initRes._sid) srv.sessionId = initRes._sid;
        // fire-and-forget
        mcpHttpPost(entry.url,
            { jsonrpc: '2.0', method: 'notifications/initialized', params: {} },
            srv.sessionId, hdrs).catch(() => {});
        const toolsRes = await mcpHttpPost(entry.url,
            { jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }, srv.sessionId, hdrs);
        if (toolsRes.error) throw new Error(toolsRes.error.message || JSON.stringify(toolsRes.error));
        const tlist = (toolsRes.result || toolsRes).tools || [];
        srv.tools = tlist.map(t => ({
            name: 'mcp_' + entry.name + '_' + t.name,
            description: (t.description || '') + ' [MCP:' + entry.name + ']',
            input_schema: t.inputSchema || { type: 'object', properties: {} },
            _mcpServer: entry.name,
            _mcpTool: t.name,
        }));
        mcpHttpServers.set(entry.name, srv);
        log('[mcp-http:' + entry.name + '] ready, ' + srv.tools.length + ' tools\n');
        mcpFailed.delete(entry.name); // MCP-4
    } catch(e) {
        log('[mcp-http:' + entry.name + '] start failed: ' + e.message + '\n');
        mcpFailed.set(entry.name, { type: 'http', error: e.message || String(e) }); // MCP-4
    }
}

function getMcpHttpTools() {
    const tools = [];
    for (const [name, srv] of mcpHttpServers.entries()) {
        // MCP-9: drop tools the user disabled for this server.
        for (const t of srv.tools) {
            if (!isToolDisabled(name, t._mcpTool)) tools.push(t);
        }
    }
    return tools;
}

async function callMcpHttpTool(toolName, args) {
    for (const [, srv] of mcpHttpServers.entries()) {
        const found = srv.tools.find(t => t.name === toolName);
        if (!found) continue;
        const res = await mcpHttpPost(srv.url, {
            jsonrpc: '2.0', id: Date.now(), method: 'tools/call',
            params: { name: found._mcpTool, arguments: args },
        }, srv.sessionId, srv.headers);  // MCP-2: auth headers
        if (res.error) throw new Error(res.error.message || JSON.stringify(res.error));
        const result = res.result || res;
        return (result.content || []).map(c => c.text || JSON.stringify(c)).join('\n');
    }
    return 'MCP HTTP tool not found: ' + toolName;
}

async function loadMcpHttpServers() {
    try {
        if (!fs.existsSync(MCP_HTTP_CONFIG)) return;
        const entries = JSON.parse(fs.readFileSync(MCP_HTTP_CONFIG, 'utf8'));
        for (const entry of (Array.isArray(entries) ? entries : [])) {
            if (entry.name && entry.url) {
                await startMcpHttpServer(entry).catch(e => log('[mcp-http] ' + e.message + '\n'));
            }
        }
    } catch(e) {
        log('[mcp-http] loadMcpHttpServers error: ' + e.message + '\n');
    }
    broadcastMcpReady();
}

// MCP-6: soft-reload MCP servers without resetting the terminal session.
// Reads current mcp_stdio.json / mcp_http.json, stops servers no longer
// configured (or now disabled), starts newly-enabled ones, broadcasts the
// updated mcp-ready payload. Returns a short status string for !mcp-reload
// and the marker-file watcher.
async function reloadMcpServers() {
    const summary = { stoppedStdio: 0, startedStdio: 0, stoppedHttp: 0, startedHttp: 0 };
    // Load fresh entries from disk
    let stdioEntries = [];
    try { if (fs.existsSync(MCP_STDIO_CONFIG)) stdioEntries = JSON.parse(fs.readFileSync(MCP_STDIO_CONFIG, 'utf8')) || []; } catch (_) {}
    let httpEntries = [];
    try { if (fs.existsSync(MCP_HTTP_CONFIG)) httpEntries = JSON.parse(fs.readFileSync(MCP_HTTP_CONFIG, 'utf8')) || []; } catch (_) {}
    if (!Array.isArray(stdioEntries)) stdioEntries = [];
    if (!Array.isArray(httpEntries))  httpEntries  = [];

    const wantStdio = new Set(stdioEntries.map(e => e.name).filter(Boolean));
    const wantHttp  = new Set(httpEntries.map(e => e.name).filter(Boolean));

    // Stop stdio servers no longer wanted
    for (const [name, srv] of [...mcpStdioServers.entries()]) {
        if (!wantStdio.has(name)) {
            srv._intentionalKill = true; // MCP-7: tell exit handler not to auto-restart
            try { srv.proc && srv.proc.kill('SIGTERM'); } catch (_) {}
            mcpStdioServers.delete(name);
            mcpFailed.delete(name);
            mcpStdioRestartAttempts.delete(name); // MCP-7
            summary.stoppedStdio++;
            log('[mcp-reload] stopped stdio:' + name + '\n');
        }
    }
    // Stop http servers no longer wanted (just drop from map — no proc to kill)
    for (const name of [...mcpHttpServers.keys()]) {
        if (!wantHttp.has(name)) {
            mcpHttpServers.delete(name);
            mcpFailed.delete(name);
            summary.stoppedHttp++;
            log('[mcp-reload] stopped http:' + name + '\n');
        }
    }
    // Also drop failed entries no longer wanted at all (so the chip count drops)
    for (const name of [...mcpFailed.keys()]) {
        if (!wantStdio.has(name) && !wantHttp.has(name)) mcpFailed.delete(name);
    }
    // Start newly-wanted stdio servers
    for (const entry of stdioEntries) {
        if (!entry.name || !entry.command) continue;
        if (mcpStdioServers.has(entry.name)) continue;
        await startMcpStdioServer(entry).catch(e => log('[mcp-reload] stdio:' + entry.name + ' ' + e.message + '\n'));
        summary.startedStdio++;
    }
    // Start newly-wanted http servers
    for (const entry of httpEntries) {
        if (!entry.name || !entry.url) continue;
        if (mcpHttpServers.has(entry.name)) continue;
        await startMcpHttpServer(entry).catch(e => log('[mcp-reload] http:' + entry.name + ' ' + e.message + '\n'));
        summary.startedHttp++;
    }
    broadcastMcpReady();
    return summary;
}

// Render the connected/failed MCP server + tool listing (shared by !mcp and the
// post-reload report of !mcp-reload, so a reload visibly shows what it loaded).
function buildMcpListing() {
    let out = '\x1b[1m[MCP servers]\x1b[0m\r\n';
    let total = 0;
    for (const [name, srv] of mcpHttpServers.entries()) {
        out += '  \x1b[32m●\x1b[0m \x1b[33m' + name + '\x1b[0m \x1b[2m(http, ' + srv.tools.length + ' tools)\x1b[0m\r\n';
        for (const t of srv.tools) out += '    \x1b[2m· ' + t._mcpTool + '\x1b[0m\r\n';
        total += srv.tools.length;
    }
    for (const [name, srv] of mcpStdioServers.entries()) {
        out += '  \x1b[32m●\x1b[0m \x1b[33m' + name + '\x1b[0m \x1b[2m(stdio, ' + srv.tools.length + ' tools)\x1b[0m\r\n';
        for (const t of srv.tools) out += '    \x1b[2m· ' + t._mcpTool + '\x1b[0m\r\n';
        total += srv.tools.length;
    }
    for (const [name, info] of mcpFailed.entries()) {
        out += '  \x1b[31m✗\x1b[0m \x1b[33m' + name + '\x1b[0m \x1b[2m(' + info.type + ', failed)\x1b[0m\r\n';
        out += '    \x1b[31m' + (info.error || '').slice(0, 200) + '\x1b[0m\r\n';
    }
    if (total === 0 && mcpFailed.size === 0) out += '  \x1b[2m(no MCP servers connected)\x1b[0m\r\n';
    if (mcpFailed.size > 0) out += '\r\n  \x1b[2muse !mcp-log to see captured stderr\x1b[0m\r\n';
    return out;
}

// MCP-9: per-server tool whitelist. The UI writes
//   filesDir/mcp_disabled_tools.json → { "<serverName>": ["tool1","tool2", …] }
// listing tools the user has switched OFF. We filter these out of the
// agentic tool list (getMcpStdioTools / getMcpHttpTools) and also push
// them into settings.json's permissions.deny so claude-code rejects
// calls in print mode. Note: claude-code still SEES the tools (they're
// listed by the upstream server) but the deny list short-circuits any
// tool_use before it runs.
const MCP_DISABLED_TOOLS_FILE = path.join(FILES_DIR, 'mcp_disabled_tools.json');
function loadDisabledTools() {
    try {
        if (!fs.existsSync(MCP_DISABLED_TOOLS_FILE)) return {};
        const obj = JSON.parse(fs.readFileSync(MCP_DISABLED_TOOLS_FILE, 'utf8'));
        return (obj && typeof obj === 'object') ? obj : {};
    } catch (_) { return {}; }
}
function isToolDisabled(serverName, toolName) {
    const map = loadDisabledTools();
    const list = map[serverName];
    return Array.isArray(list) && list.includes(toolName);
}

// MCP-9: write a snapshot of every tool we've discovered so the Android UI
// can render a checklist without having to re-do MCP handshakes. Schema:
//   { servers: [ { name, type, tools: [name, …] }, … ] }
const MCP_TOOL_CACHE_FILE = path.join(FILES_DIR, 'mcp_tool_cache.json');
function writeMcpToolCache() {
    try {
        const servers = [];
        for (const [name, srv] of mcpHttpServers.entries()) {
            servers.push({ name, type: 'http', tools: srv.tools.map(t => t._mcpTool) });
        }
        for (const [name, srv] of mcpStdioServers.entries()) {
            servers.push({ name, type: 'stdio', tools: srv.tools.map(t => t._mcpTool) });
        }
        const tmp = MCP_TOOL_CACHE_FILE + '.tmp';
        fs.writeFileSync(tmp, JSON.stringify({ servers }, null, 2));
        fs.renameSync(tmp, MCP_TOOL_CACHE_FILE);
    } catch (e) { log('[mcp] tool cache write failed: ' + e.message + '\n'); }
}

// MCP-6: watch for a marker file written by Kotlin (SettingsActivity) when
// the user toggles a server. fs.watch is cheap and supported by nodejs-mobile.
const MCP_RELOAD_MARKER = path.join(FILES_DIR, 'mcp_reload_requested');
try {
    fs.watch(FILES_DIR, (ev, fname) => {
        if (fname !== 'mcp_reload_requested') return;
        if (!fs.existsSync(MCP_RELOAD_MARKER)) return;
        try { fs.unlinkSync(MCP_RELOAD_MARKER); } catch (_) {}
        reloadMcpServers()
            .then(s => log('[mcp-reload] done: ' + JSON.stringify(s) + '\n'))
            .catch(e => log('[mcp-reload] error: ' + e.message + '\n'));
    });
} catch (e) {
    log('[mcp-reload] fs.watch failed: ' + e.message + '\n');
}
/**
 * Run a quick launcher self-test. Spawns LAUNCHER with a trivial JS one-liner
 * and returns a promise that resolves to true (ok) or false (broken).
 * The result and any output are written to setup.log.
 */
function testLauncher() {
    return new Promise(resolve => {
        const env = {
            HOME: FILES_DIR,
            PATH: process.env.PATH || '/system/bin:/system/xbin',
            LD_LIBRARY_PATH: NATIVE_DIR,
            LANG: 'en_US.UTF-8',
        };
        log('[launcher-test] spawning: ' + LAUNCHER + ' -e "process.stdout.write(\'ok\')" \n');
        let out = '', err = '';
        let child;
        try {
            child = spawn(LAUNCHER, ['-e', "process.stdout.write('ok')"], { env, cwd: FILES_DIR });
        } catch (e) {
            log('[launcher-test] spawn threw: ' + e.message + '\n');
            return resolve(false);
        }
        child.stdout.on('data', d => { out += d.toString(); });
        child.stderr.on('data', d => { err += d.toString(); });
        child.on('error', e => {
            log('[launcher-test] error event: ' + e.message + '\n');
            resolve(false);
        });
        child.on('close', code => {
            log('[launcher-test] exit=' + code + ' stdout=' + JSON.stringify(out) + ' stderr=' + JSON.stringify(err.slice(0, 200)) + '\n');
            resolve(code === 0 && out.includes('ok'));
        });
        setTimeout(() => {
            try { child.kill(); } catch (_) {}
            log('[launcher-test] timed out\n');
            resolve(false);
        }, 8000);
    });
}




// ─── Package installer ────────────────────────────────────────────────────────
// Downloads static ARM64 binaries or npm installs packages, writing to BIN_DIR.

function installPackage(name, socket) {
    // All output from installPackage must be SYS_FENCE'd so it routes to a sys
    // bubble even when chatState === 'RESPONDING' on the JavaScript side.
    const sw = msg => { try { socket.write(SYS_FENCE + msg); } catch(_) {} };
    const pkg = PACKAGE_CATALOG[name];
    if (!pkg) {
        const names = Object.keys(PACKAGE_CATALOG).join(', ');
        try { sw('\x1b[31m✗ Unknown package: ' + name + '\x1b[0m\r\n' +
            '\x1b[2mAvailable: ' + names + '\x1b[0m\r\n\r\n'); } catch(_) {}
        return;
    }

    const binPath = path.join(BIN_DIR, pkg.bin || name);

    if (pkg.type === 'binary') {
        if (fs.existsSync(binPath)) {
            try { sw('\x1b[32m✓ ' + name + ' already installed.\x1b[0m Run \x1b[33m$ ' + (pkg.bin || name) + ' --help\x1b[0m to verify.\r\n\r\n'); } catch(_) {}
            return;
        }
        try { fs.mkdirSync(BIN_DIR, { recursive: true }); } catch(_) {}
        try { sw('\x1b[33mDownloading ' + name + ' (' + (pkg.size || '?') + ')…\x1b[0m\r\n'); } catch(_) {}
        log('[install] downloading ' + name + ' from ' + pkg.url + '\n');

        const tmpPath = binPath + '.tmp';
        downloadFile(pkg.url, tmpPath).then(() => {
            try { fs.renameSync(tmpPath, binPath); } catch(e) {
                try { fs.unlinkSync(tmpPath); } catch(_) {}
                throw e;
            }
            // chmod +x
            try { fs.chmodSync(binPath, 0o755); } catch(_) {}
            // BusyBox post-install: create symlinks for all applets
            if (pkg.post === 'busybox') {
                const child = spawn(binPath, ['--list'], { env: buildEnv(), cwd: BIN_DIR });
                let appletList = '';
                child.stdout.on('data', d => { appletList += d.toString(); });
                child.on('close', () => {
                    const applets = appletList.split('\n').map(l => l.trim()).filter(Boolean);
                    let linked = 0;
                    for (const applet of applets) {
                        const link = path.join(BIN_DIR, applet);
                        if (!fs.existsSync(link)) {
                            try { fs.symlinkSync(binPath, link); linked++; } catch(_) {}
                        }
                    }
                    try { sw('\x1b[32m✓ busybox installed.\x1b[0m ' + linked + ' symlinks created.\r\n' +
                        '\x1b[2mNow available: wget, tar, gzip, grep, sed, awk, find, nano, and more.\x1b[0m\r\n\r\n'); } catch(_) {}
                    log('[install] busybox: ' + linked + ' symlinks created\n');
                });
            } else {
                try { sw('\x1b[32m✓ ' + name + ' installed.\x1b[0m Run \x1b[33m$ ' + (pkg.bin || name) + ' --help\x1b[0m to verify.\r\n\r\n'); } catch(_) {}
                log('[install] ' + name + ' installed at ' + binPath + '\n');
            }
        }).catch(e => {
            try { fs.unlinkSync(tmpPath); } catch(_) {}
            try { sw('\x1b[31m✗ Download failed: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
            log('[install] ' + name + ' download failed: ' + e.message + '\n');
        });

    } else if (pkg.type === 'archive' || pkg.type === 'archive-xz') {
        // ── Generic archive installer (tar.gz or tar.xz) ─────────────────────
        const isXz   = pkg.type === 'archive-xz';
        const distDir = path.join(FILES_DIR, 'opt', name + '-dist');
        const primaryBin = path.join(BIN_DIR, name === 'python3' ? 'python3' : name);
        if (fs.existsSync(primaryBin)) {
            try { sw('\x1b[32m✓ ' + name + ' already installed.\x1b[0m Run \x1b[33m$ ' + name + ' --version\x1b[0m\r\n\r\n'); } catch(_) {}
            return;
        }
        const ext  = isXz ? '.tar.xz' : '.tar.gz';
        const archivePath = path.join(FILES_DIR, name + ext);
        const tarPath     = path.join(FILES_DIR, name + '.tar');

        if (isXz && !fs.existsSync(path.join(BIN_DIR, 'busybox'))) {
            try { sw('\x1b[31m✗ ' + name + ' requires busybox for .tar.xz.\x1b[0m Run \x1b[33m!install busybox\x1b[0m first.\r\n\r\n'); } catch(_) {}
            return;
        }

        (async () => {
            // Resolve URL dynamically
            let url;
            if (name === 'python3')     { try { sw('\x1b[33mResolving latest Python ARM64…\x1b[0m\r\n'); } catch(_) {} url = await resolveLatestPythonUrl(); }
            else if (name === 'go')     { try { sw('\x1b[33mResolving latest Go ARM64…\x1b[0m\r\n'); } catch(_) {} url = await resolveLatestGoUrl(); }
            else if (name === 'zig')    { try { sw('\x1b[33mResolving latest Zig ARM64…\x1b[0m\r\n'); } catch(_) {} url = await resolveLatestZigUrl(); }
            else if (pkg.url)           { url = pkg.url; }
            else throw new Error('No URL resolver for ' + name);

            try { sw('\x1b[33mDownloading ' + name + ' (' + (pkg.size || '') + ')…\x1b[0m\r\n'); } catch(_) {}
            log('[install] ' + name + ' url: ' + url + '\n');
            await downloadFile(url, archivePath);
            fs.mkdirSync(distDir, { recursive: true });

            if (isXz) {
                // busybox tar -Jxf handles xz natively
                try { sw('\x1b[33mExtracting ' + name + '… (this may take a minute)\x1b[0m\r\n'); } catch(_) {}
                const bb = path.join(BIN_DIR, 'busybox');
                await new Promise((res, rej) => {
                    const tar = spawn(bb, ['tar', '-Jxf', archivePath, '-C', distDir],
                        { env: { PATH: BIN_DIR + ':/system/bin:/system/xbin', TMPDIR: FILES_DIR } });
                    tar.stderr.on('data', d => log('tar(xz): ' + d));
                    tar.on('error', e => rej(new Error('tar: ' + e.message)));
                    tar.on('close', code => code === 0 ? res() : rej(new Error('tar exit ' + code)));
                });
                try { fs.unlinkSync(archivePath); } catch(_) {}
            } else {
                // Decompress gz in Node.js (toybox tar may lack -z), then system tar
                try { sw('\x1b[33mDecompressing…\x1b[0m\r\n'); } catch(_) {}
                await new Promise((res, rej) => {
                    const zlib = require('zlib');
                    fs.createReadStream(archivePath).pipe(zlib.createGunzip())
                        .pipe(fs.createWriteStream(tarPath)).on('finish', res).on('error', rej);
                });
                try { fs.unlinkSync(archivePath); } catch(_) {}
                try { sw('\x1b[33mExtracting ' + name + '… (this may take a minute)\x1b[0m\r\n'); } catch(_) {}
                await new Promise((res, rej) => {
                    const tar = spawn('/system/bin/tar', ['-xf', tarPath, '-C', distDir],
                        { env: { PATH: '/system/bin:/system/xbin' } });
                    tar.stderr.on('data', d => log('tar: ' + d));
                    tar.on('error', e => rej(new Error('/system/bin/tar: ' + e.message)));
                    tar.on('close', code => code === 0 ? res() : rej(new Error('tar exit ' + code)));
                });
                try { fs.unlinkSync(tarPath); } catch(_) {}
            }

            // Helper: write a shell wrapper into BIN_DIR
            const mkWrap = (wrapName, execPath, envPairs) => {
                let s = '#!/system/bin/sh\n';
                if (envPairs) for (const [k, v] of Object.entries(envPairs))
                    s += 'export ' + k + '="' + v + '"\n';
                s += 'exec "' + execPath + '" "$@"\n';
                fs.writeFileSync(path.join(BIN_DIR, wrapName), s);
                fs.chmodSync(path.join(BIN_DIR, wrapName), 0o755);
            };

            if (pkg.post === 'python3') {
                const pyBinDir = path.join(distDir, 'python', 'bin');
                const pyHome   = path.join(distDir, 'python');
                const bins     = fs.readdirSync(pyBinDir);
                const realPy   = bins.find(f => /^python3\.\d+$/.test(f)) || 'python3';
                const realPyPath = path.join(pyBinDir, realPy);
                mkWrap('python3', realPyPath, { PYTHONHOME: pyHome });
                mkWrap('python',  realPyPath, { PYTHONHOME: pyHome });
                const pip3 = bins.find(f => /^pip3(\.\d+)?$/.test(f));
                if (pip3) {
                    mkWrap('pip3', path.join(pyBinDir, pip3), { PYTHONHOME: pyHome });
                    mkWrap('pip',  path.join(pyBinDir, pip3), { PYTHONHOME: pyHome });
                }
                try { sw('\x1b[32m✓ python3 installed.\x1b[0m Run \x1b[33m$ python3 --version\x1b[0m\r\n' +
                    '\x1b[2mCommands: python3, python' + (pip3 ? ', pip3, pip' : '') + '\x1b[0m\r\n\r\n'); } catch(_) {}
            } else if (pkg.post === 'go') {
                const goRoot = path.join(distDir, 'go');
                const goPath = path.join(FILES_DIR, 'go-workspace');
                fs.mkdirSync(goPath, { recursive: true });
                mkWrap('go',    path.join(goRoot, 'bin', 'go'),    { GOROOT: goRoot, GOPATH: goPath, HOME: FILES_DIR });
                mkWrap('gofmt', path.join(goRoot, 'bin', 'gofmt'), { GOROOT: goRoot, GOPATH: goPath, HOME: FILES_DIR });
                try { sw('\x1b[32m✓ go installed.\x1b[0m Run \x1b[33m$ go version\x1b[0m\r\n' +
                    '\x1b[2mCommands: go, gofmt  |  GOPATH: ' + goPath + '\x1b[0m\r\n\r\n'); } catch(_) {}
            } else if (pkg.post === 'zig') {
                // Zig extracts to zig-linux-aarch64-VERSION/ inside distDir
                const zigSubDir = fs.readdirSync(distDir).find(e => e.startsWith('zig-'));
                if (!zigSubDir) throw new Error('Could not find zig sub-directory after extraction');
                const zigBin = path.join(distDir, zigSubDir, 'zig');
                mkWrap('zig', zigBin);
                // Expose 'zig cc' and 'zig c++' as convenient aliases
                const ccW = '#!/system/bin/sh\nexec "' + zigBin + '" cc "$@"\n';
                const cppW = '#!/system/bin/sh\nexec "' + zigBin + '" c++ "$@"\n';
                fs.writeFileSync(path.join(BIN_DIR, 'zig-cc'),  ccW);  fs.chmodSync(path.join(BIN_DIR, 'zig-cc'),  0o755);
                fs.writeFileSync(path.join(BIN_DIR, 'zig-c++'), cppW); fs.chmodSync(path.join(BIN_DIR, 'zig-c++'), 0o755);
                try { sw('\x1b[32m✓ zig installed.\x1b[0m Run \x1b[33m$ zig version\x1b[0m\r\n' +
                    '\x1b[2mCommands: zig, zig-cc (C), zig-c++ (C++)\x1b[0m\r\n\r\n'); } catch(_) {}
            }
            log('[install] ' + name + ' installed at ' + distDir + '\n');
        })().catch(e => {
            try { fs.unlinkSync(archivePath); } catch(_) {}
            try { fs.unlinkSync(tarPath); } catch(_) {}
            try { sw('\x1b[31m✗ ' + name + ' install failed: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
            log('[install] ' + name + ' failed: ' + e.message + '\n');
        });

    } else if (pkg.type === 'termux-debs') {
        // ── Generic Termux .deb installer ────────────────────────────────────
        // "already installed" check: primary binary is first entry in pkg.packages
        // (the actual package name) or the catalog key itself.
        const primaryBin = path.join(BIN_DIR, name === 'git' ? 'git' : name);
        const isGit = name === 'git';
        const alreadyOk = fs.existsSync(primaryBin) &&
            (!isGit || !fs.readFileSync(primaryBin, 'utf8').includes('isomorphic'));
        if (alreadyOk) {
            try { sw('\x1b[32m✓ ' + name + ' already installed.\x1b[0m\r\n\r\n'); } catch(_) {}
            return;
        }
        const bb = path.join(BIN_DIR, 'busybox');
        if (!fs.existsSync(bb)) {
            try { sw('\x1b[31m✗ busybox required first.\x1b[0m Run \x1b[33m!install busybox\x1b[0m then retry.\r\n\r\n'); } catch(_) {}
            return;
        }
        const bbEnv  = { PATH: BIN_DIR + ':/system/bin:/system/xbin', TMPDIR: FILES_DIR };
        const destDir = path.join(FILES_DIR, pkg.dest);

        (async () => {
            try { sw('\x1b[33mFetching Termux package index…\x1b[0m\r\n'); } catch(_) {}
            const pkgs = await resolveTermuxUrls(pkg.packages);

            for (const { name: pkgName, url } of pkgs) {
                try { sw('\x1b[33mDownloading ' + pkgName + '…\x1b[0m\r\n'); } catch(_) {}
                const debPath = path.join(FILES_DIR, pkgName + '.deb');
                await downloadFile(url, debPath);
                try { sw('\x1b[33mExtracting ' + pkgName + '…\x1b[0m\r\n'); } catch(_) {}
                await extractDeb(debPath, destDir, bb, bbEnv);
                try { fs.unlinkSync(debPath); } catch(_) {}
            }

            const libDir = path.join(destDir, 'lib');
            const mkWrap = (wrapName, realBin, extra) => {
                let s = '#!/system/bin/sh\nexport LD_LIBRARY_PATH="' + libDir + ':${LD_LIBRARY_PATH:-}"\n';
                if (extra) s += extra;
                s += 'exec "' + realBin + '" "$@"\n';
                fs.writeFileSync(path.join(BIN_DIR, wrapName), s);
                fs.chmodSync(path.join(BIN_DIR, wrapName), 0o755);
            };

            if (pkg.post === 'git-termux') {
                mkWrap('git', path.join(destDir, 'bin', 'git'),
                    'export GIT_EXEC_PATH="' + path.join(destDir, 'libexec', 'git-core') + '"\n' +
                    'export GIT_TEMPLATE_DIR="' + path.join(destDir, 'share', 'git-core', 'templates') + '"\n');
                try { sw('\x1b[32m✓ git installed.\x1b[0m Run \x1b[33m$ git --version\x1b[0m\r\n' +
                    '\x1b[2mFor HTTPS auth: git config credential.helper store\x1b[0m\r\n\r\n'); } catch(_) {}
            } else if (pkg.post === 'ssh-termux') {
                const sshBinDir = path.join(destDir, 'bin');
                for (const b of ['ssh', 'scp', 'sftp', 'ssh-keygen', 'ssh-add', 'ssh-agent']) {
                    const real = path.join(sshBinDir, b);
                    if (fs.existsSync(real)) mkWrap(b, real);
                }
                try { fs.mkdirSync(path.join(FILES_DIR, '.ssh'), { mode: 0o700 }); } catch(_) {}
                try { sw('\x1b[32m✓ ssh installed.\x1b[0m Run \x1b[33m$ ssh -V\x1b[0m\r\n' +
                    '\x1b[2mCommands: ssh, scp, sftp, ssh-keygen\x1b[0m\r\n\r\n'); } catch(_) {}
            } else if (pkg.post === 'ruby-termux') {
                const rubyBinDir = path.join(destDir, 'bin');
                const rubyBins   = fs.existsSync(rubyBinDir) ? fs.readdirSync(rubyBinDir) : [];
                const rubyExec   = rubyBins.find(f => /^ruby\d/.test(f)) || 'ruby';
                mkWrap('ruby', path.join(rubyBinDir, rubyExec));
                for (const b of ['irb', 'gem', 'rake', 'erb']) {
                    const real = path.join(rubyBinDir, b);
                    if (fs.existsSync(real)) mkWrap(b, real);
                }
                try { sw('\x1b[32m✓ ruby installed.\x1b[0m Run \x1b[33m$ ruby --version\x1b[0m\r\n' +
                    '\x1b[2mCommands: ruby, irb, gem, rake\x1b[0m\r\n\r\n'); } catch(_) {}
            } else if (pkg.post === 'clang-termux') {
                const clangBinDir = path.join(destDir, 'bin');
                const clangBins   = fs.existsSync(clangBinDir) ? fs.readdirSync(clangBinDir) : [];
                for (const b of clangBins) {
                    try { mkWrap(b, path.join(clangBinDir, b)); } catch(_) {}
                }
                // cc / c++ aliases
                const clangWrap = path.join(BIN_DIR, 'clang');
                const clangppWrap = path.join(BIN_DIR, 'clang++');
                if (fs.existsSync(clangWrap))   { try { fs.symlinkSync(clangWrap,   path.join(BIN_DIR, 'cc'));  } catch(_) {} }
                if (fs.existsSync(clangppWrap)) { try { fs.symlinkSync(clangppWrap, path.join(BIN_DIR, 'c++')); } catch(_) {} }
                try { sw('\x1b[32m✓ clang installed.\x1b[0m Run \x1b[33m$ clang --version\x1b[0m\r\n' +
                    '\x1b[2mCommands: clang, clang++, cc, c++\x1b[0m\r\n\r\n'); } catch(_) {}
            } else {
                // Generic: wrap all binaries found in bin/
                const binDir2 = path.join(destDir, 'bin');
                if (fs.existsSync(binDir2))
                    for (const b of fs.readdirSync(binDir2)) { try { mkWrap(b, path.join(binDir2, b)); } catch(_) {} }
                try { sw('\x1b[32m✓ ' + name + ' installed.\x1b[0m\r\n\r\n'); } catch(_) {}
            }
            log('[install] ' + name + ' installed at ' + destDir + '\n');
        })().catch(e => {
            try { sw('\x1b[31m✗ ' + name + ' install failed: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
            log('[install] ' + name + ' failed: ' + e.message + '\n');
        });

    } else if (pkg.type === 'npm') {
        try { sw('\x1b[33mInstalling ' + name + ' via npm…\x1b[0m\r\n'); } catch(_) {}
        const npmCmd = 'npm install --prefix ' + JSON.stringify(NPM_PREFIX) + ' ' + pkg.pkg;
        const child = spawn('/system/bin/sh', ['-c', npmCmd], { env: buildEnv(), cwd: FILES_DIR });
        child.stdout.on('data', d => { try { sw(d); } catch(_) {} });
        child.stderr.on('data', d => { try { sw('\x1b[2m' + d.toString() + '\x1b[0m'); } catch(_) {} });
        child.on('close', code => {
            if (code !== 0) {
                try { sw('\x1b[31m✗ npm install failed (exit ' + code + ')\x1b[0m\r\n\r\n'); } catch(_) {}
            } else {
                try { sw('\x1b[32m✓ ' + name + ' installed.\x1b[0m\r\n\r\n'); } catch(_) {}
                log('[install] npm package ' + pkg.pkg + ' installed\n');
            }
        });
    }
}

// ─── PTY Phase 2: persistent claude session ───────────────────────────────────

// P4: buildInteractiveEvalCode() was the PTY/interactive-mode bootstrap for the
// legacy libnode engine — DELETED (PTY was removed 2026-06-01, inv 5d; the legacy
// engine itself is gone). proot spawns claude directly inside the guest.


// ─── Print-mode session server ────────────────────────────────────────────────
// Each user message spawns one `claude --print --output-format stream-json`
// process. The process reads the message from argv, calls the API (via our
// proxy for non-Anthropic providers), streams NDJSON events, then exits cleanly.
// --continue resumes the most-recent claude session so history is preserved.
// --dangerously-skip-permissions auto-approves all tool use — no TUI prompts.
function openPrintSession() {

    on429CountdownNotify = function(delaySecs) {
        for (const s of activeSessions.values()) {
            if (s.socket) try { s.socket.write('\x1b]9;rate-limit:' + delaySecs + '\x07'); } catch(_) {}
        }
    };

    // P4: the always-allow list (auto_approve.json) + Bash pattern matcher were the
    // legacy permission apparatus — DELETED. proot runs with
    // --dangerously-skip-permissions + IS_SANDBOX (bypassPermissions), so every tool
    // auto-runs and there is no card to suppress. (Re-audit probe #5, b24.)

    // ── Patch settings.json ───────────────────────────────────────────────────
    function patchSettings(cfg) {
        if (cfg.mode !== 'subscription') {
            try { fs.unlinkSync(path.join(FILES_DIR, '.claude', '.credentials.json')); } catch(_) {}
        }
        try {
            const claudeDir = path.join(FILES_DIR, '.claude');
            try { fs.mkdirSync(claudeDir, { recursive: true }); } catch(_) {}
            const sp = path.join(claudeDir, 'settings.json');
            let s = {};
            try { s = JSON.parse(fs.readFileSync(sp, 'utf8')); } catch(_) {}
            s.theme = 'dark'; s.hasCompletedOnboarding = true;
            s.hasShownWelcome = true; s.skipWelcome = true;
            // P4: the proot engine runs claude-code 2.1.160 with
            // --dangerously-skip-permissions + IS_SANDBOX (→ bypassPermissions mode),
            // so the entire legacy permission apparatus is GONE (re-audit probe #5,
            // b24): no customApiKeyResponses (the proxy itself accepts sk-ant-proxy000
            // — the guest doesn't need the approval list) and no permissions.allow:['*']
            // (bypassPermissions auto-runs every tool, incl. MCP). We keep ONLY the
            // workspace boundary (additionalDirectories, inv 62 — a SEPARATE check from
            // allow/deny) + the user's disabled-tool denies (secondary to the
            // proxy-layer tool strip, inv 60). Stale legacy keys are actively removed
            // so a pre-P4 install's settings.json gets cleaned on the next turn.
            if (!s.permissions || typeof s.permissions !== 'object') s.permissions = {};
            if (!Array.isArray(s.permissions.additionalDirectories)) s.permissions.additionalDirectories = [];
            for (const d of ['/sdcard', '/storage/emulated/0', '/storage/self/primary', '/root', FILES_DIR]) {
                if (!s.permissions.additionalDirectories.includes(d)) s.permissions.additionalDirectories.push(d);
            }
            if (!Array.isArray(s.permissions.deny)) s.permissions.deny = [];
            // --print doesn't need customApiKeyResponses (b24). The interactive TUI
            // doesn't use it either — it auths via ANTHROPIC_AUTH_TOKEN (gateway mode,
            // set in attachPtySession), NOT a custom API key. So keep deleting it.
            delete s.customApiKeyResponses;
            delete s.permissions.allow;       // bypassPermissions makes it moot
            // MCP-9: tools the user disabled per-server become explicit deny entries.
            // claude-code still sees them in tools/list (no way to hide upstream),
            // but rejects calls before they execute.
            try {
                const disabled = loadDisabledTools();
                for (const [srvName, list] of Object.entries(disabled)) {
                    if (!Array.isArray(list)) continue;
                    for (const toolName of list) {
                        const denyPat = 'mcp__' + srvName + '__' + toolName;
                        if (!s.permissions.deny.includes(denyPat)) s.permissions.deny.push(denyPat);
                    }
                }
            } catch (_) {}
            // MCP servers are NOT configured via settings.json — claude-code ignores
            // mcpServers here. They're supplied at spawn time via --mcp-config
            // (writeSpawnMcpConfig / buildMcpServersObj). Strip any stale key so we
            // never ship a conflicting/duplicate server list in settings.json.
            delete s.mcpServers;

            fs.writeFileSync(sp, JSON.stringify(s, null, 2));
            log('[patchSettings] ok (proot/bypassPermissions; no allow-list, deny=' + s.permissions.deny.length + ')\n');
        } catch(e) { log('[patchSettings] ERROR: ' + e.message + '\n'); }
    }

    // ── Process a single stream-json event from claude stdout ────────────────
    function handleStreamEvent(evt, state, proc, firstContent, setFirstContent, resultReceived, setResultReceived) {
        // On the first JSON event, close the thinking spinner
        if (!state.thinkingDone) {
            state.thinkingDone = true;
            log('[stream] first event type=' + evt.type + (evt.subtype ? '/' + evt.subtype : '') + '\n');
            try { if (state.socket) state.socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
        }

        if (evt.type === 'system' && evt.subtype === 'init') return;

        // Suppress housekeeping assistant events that arrive after the result event.
        // claude-code makes secondary API calls (title generation, follow-up suggestions)
        // after the main response; those responses must never appear as AI bubbles.
        if (resultReceived && evt.type === 'assistant') return;

        if (evt.type === 'assistant') {
            const content = (evt.message && evt.message.content) || [];
            for (const block of content) {
                if (block.type === 'text' && block.text) {
                    if (!firstContent) setFirstContent(true);
                    state.lastAiText = (state.lastAiText || '') + block.text;
                    if (state.lastAiText.length > 2000) state.lastAiText = state.lastAiText.slice(-2000);
                    try { if (state.socket) state.socket.write(block.text); } catch(_) {}
                }
                // tool_use blocks are handled by the isToolEvent check below
            }
            // If this assistant event contains tool_use, fall through to isToolEvent check
            if (!content.some(b => b.type === 'tool_use')) return;
        }

        // Tool-use detection. P4: proot auto-runs every tool
        // (--dangerously-skip-permissions + IS_SANDBOX) so there is NO permission card
        // and no y\n stdin dance — both were legacy 2.1.112 scars. The ONLY thing we
        // still surface is the Agent/Task sub-agent panel (inv 44), which renders from
        // the `permission:` OSC. Every other tool just runs silently.
        const isToolEvent =
            evt.type === 'permission_request' ||
            (evt.type === 'tool' && (evt.status === 'pending' || evt.status === 'awaiting_approval')) ||
            evt.type === 'tool_approval_request' ||
            (evt.type === 'assistant' && evt.message && (evt.message.content || []).some(b => b.type === 'tool_use'));
        if (isToolEvent) {
            let toolName = evt.tool_name || evt.tool || evt.name || 'tool';
            let toolInput = evt.tool_input || evt.input || {};
            if (evt.type === 'assistant' && evt.message) {
                const tb = (evt.message.content || []).find(b => b.type === 'tool_use');
                if (tb) { toolName = tb.name || toolName; toolInput = tb.input || toolInput; }
            }
            if (toolName !== 'Agent' && toolName !== 'Task') return;
            const permId = evt.id || (Date.now() + '-' + Math.random().toString(36).slice(2));
            const perm = { toolName, toolInput, id: permId, autoApproved: true };
            state.pendingPerm = perm;
            const permB64 = Buffer.from(JSON.stringify(perm)).toString('base64');
            try { if (state.socket) state.socket.write('\x1b]9;permission:' + permB64 + '\x07'); } catch(_) {}
            return;
        }

        if (evt.type === 'result') {
            // Prevent subsequent housekeeping API call responses from showing as AI bubbles
            setResultReceived(true);
            // Mark session has history so --continue is used on next message
            if (!evt.is_error) {
                state.hasHistory = true;
                saveSessionState(state.sid, state);
            }
            // Update token counter from result or nested usage object
            const u = evt.usage || {};
            const toks = (evt.total_input_tokens || 0) + (evt.total_output_tokens || 0) ||
                         (u.input_tokens || 0) + (u.output_tokens || 0);
            if (toks > 0) {
                state.sessionTokens = (state.sessionTokens || 0) + toks;
                try { if (state.socket) state.socket.write('\x1b]9;tokens:' + state.sessionTokens + '\x07'); } catch(_) {}
            }
        }
    }

    // ── Plain-text permission prompt fallback ─────────────────────────────────
    // P4: proot runs bypassPermissions, so claude-code 2.1.160 never emits a
    // plain-text "Do you want to run …? [y/n]" prompt. If one ever appears it's
    // unexpected — just log it (the tool already ran or will run).
    function handlePermissionText(line, state, proc) {
        log('[perm-text] unexpected on proot: ' + line.slice(0, 120) + '\n');
    }

    // ── Warm-session path (getWarmMode) — ONE persistent claude per tab ───────
    // The OPPOSITE of single-shot inv 5c: stdin stays OPEN and each turn is one
    // NDJSON user line. The proc loops reading stdin (proven by !test-persistent),
    // so turn 2+ skip the ~30s cold start. History lives IN the proc (no
    // --continue). Config baked at spawn (model env, --append-system-prompt,
    // --mcp-config, cwd) → respawn when any of those change, on !clear, on
    // model/provider change, and on crash. Single-shot path is untouched (fallback).
    const WARM_TURN_TIMEOUT_MS = 180000;

    // Build the guest env for a warm claude proc (mirrors the single-shot block).
    function warmGuestEnv() {
        const benv = buildEnv();
        const e = {
            ANTHROPIC_API_KEY:   benv.ANTHROPIC_API_KEY,
            ANTHROPIC_MODEL:     benv.ANTHROPIC_MODEL,
            DISABLE_AUTOUPDATER: '1',
            MCP_TIMEOUT:         '30000',
            MCP_TOOL_TIMEOUT:    '30000',
            SHELL:               '/bin/bash',
            IS_SANDBOX:          '1',
        };
        if (benv.ANTHROPIC_BASE_URL) e.ANTHROPIC_BASE_URL = benv.ANTHROPIC_BASE_URL;
        return e;
    }

    // The system-prompt string baked into a warm proc for a given config — used
    // both at spawn and to detect when a respawn is needed.
    function warmSysFor(appendSys, prootMcpCfg) {
        return [appendSys, prootMcpCfg ? PROOT_MCP_SYS_NUDGE : ''].filter(Boolean).join('\n\n');
    }

    // Kill the warm proc (if any) so the next message respawns with fresh config.
    function killWarmProc(state, why) {
        const p = state.warmProc;
        if (!p) return;
        log('[warm] killing warm proc (' + (why || '') + ')\n');
        try { p._manualKill = true; p.kill('SIGTERM'); } catch(_) {}
        state.warmProc = null;
    }

    // Finalize the in-flight warm turn: flip to idle, clear the timer, optionally
    // print an error. Deduped — both the `result` event and proc `close` can call it.
    function finishWarmTurn(state, proc, errText) {
        const tc = state.turn;
        if (!tc || tc.done) return;
        tc.done = true;
        if (tc.tid) { clearTimeout(tc.tid); tc.tid = null; }
        if (state.currentProc === proc) state.currentProc = null;
        state.busy = false;
        state.thinkingDone = false;
        try { if (state.socket) state.socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
        if (errText) { try { if (state.socket) state.socket.write(SYS_FENCE + errText); } catch(_) {} }
    }

    // Spawn the persistent claude proc and attach its (once-only) stream handlers.
    // Handlers dispatch into the CURRENT turn context (state.turn), which runWarmTurn
    // replaces per message. stdin is NOT closed.
    function spawnWarmProc(state, appendSys, prootMcpCfg, spawnCwd) {
        const guestArgv = [GUEST_CLAUDE,
            '--input-format', 'stream-json',
            '--output-format', 'stream-json',
            '--print',
            '--dangerously-skip-permissions'];
        if (prootMcpCfg) guestArgv.push('--mcp-config', prootMcpCfg);
        const sys = warmSysFor(appendSys, prootMcpCfg);
        if (sys) guestArgv.push('--append-system-prompt', sys);
        // NO --continue: the warm proc keeps history in-process.
        guestArgv.push('--verbose');
        const proc = prootChild(guestArgv, { extraEnv: warmGuestEnv(), workspace: spawnCwd });
        proc._warm    = true;
        proc._warmCwd = spawnCwd;
        proc._warmMcp = prootMcpCfg || '';
        proc._warmSys = sys;
        log('[warm] spawned warm proc cwd=' + spawnCwd + ' mcp=' + (prootMcpCfg ? 'yes' : 'no') + '\n');

        let lineBuf = '';
        proc.stdout.on('data', chunk => {
            lineBuf += chunk.toString();
            const lines = lineBuf.split('\n');
            lineBuf = lines.pop();
            for (const ln of lines) {
                const t = ln.trim();
                if (t.startsWith('{')) {
                    let evt;
                    try { evt = JSON.parse(t); }
                    catch(_) {
                        log('[warm-stdout] ' + t.slice(0, 200) + '\n');
                        try { if (state.socket) state.socket.write(SYS_FENCE + ln + '\n'); } catch(_) {}
                        continue;
                    }
                    const tc = state.turn || {};
                    handleStreamEvent(evt, state, proc,
                        tc.firstContent, fc => { tc.firstContent = fc; },
                        tc.resultReceived, rr => { tc.resultReceived = rr; });
                    if (evt.type === 'result') finishWarmTurn(state, proc);
                } else if (t.length > 0) {
                    log('[warm-plain] ' + t.slice(0, 200) + '\n');
                    try { if (state.socket) state.socket.write(SYS_FENCE + ln + '\n'); } catch(_) {}
                }
            }
        });
        proc.stderr.on('data', d => {
            const s = d.toString();
            if (state.turn) state.turn.stderrBuf = (state.turn.stderrBuf || '') + s;
            log('[warm-stderr] ' + s);
            const lines = s.split('\n').filter(l => {
                const x = l.trim();
                return x && !/^Warning: no stdin data received/.test(x);
            });
            if (lines.length) { try { if (state.socket) state.socket.write(SYS_FENCE + lines.join('\n')); } catch(_) {} }
        });
        proc.on('error', e => {
            log('[warm] proc error: ' + e.message + '\n');
            if (state.warmProc === proc) state.warmProc = null;
            finishWarmTurn(state, proc, '\x1b[31m[warm engine error] ' + e.message + '\x1b[0m\r\n');
        });
        proc.on('close', code => {
            log('[warm] proc closed code=' + code + '\n');
            if (state.warmProc === proc) state.warmProc = null;
            // Proc died mid-turn (crash / kill) → finalize so the UI unsticks; the
            // next message respawns. Suppress the error line on intentional kills.
            if (state.currentProc === proc) {
                finishWarmTurn(state, proc, (proc._manualKill || proc._ctrlCKill) ? null :
                    '\x1b[31m[warm proc exited ' + code + ' — next message restarts it]\x1b[0m\r\n');
            }
        });
        state.warmProc = proc;
        return proc;
    }

    // Run one user turn on the warm proc (spawning/respawning as needed).
    function runWarmTurn(state, finalMsg, appendSys, prootMcpCfg, spawnCwd) {
        let proc = state.warmProc;
        // Respawn if any baked-in config changed (cwd / MCP set / system prompt).
        if (proc && (proc._warmCwd !== spawnCwd ||
                     proc._warmMcp !== (prootMcpCfg || '') ||
                     proc._warmSys !== warmSysFor(appendSys, prootMcpCfg))) {
            killWarmProc(state, 'config changed');
            proc = null;
        }
        if (!proc) {
            try { proc = spawnWarmProc(state, appendSys, prootMcpCfg, spawnCwd); }
            catch (e) {
                log('[warm] spawn failed: ' + e.message + '\n');
                try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[warm spawn error] ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
                return;
            }
        }
        // Fresh per-turn context.
        state.turn = { firstContent: false, resultReceived: false, stderrBuf: '', done: false, tid: null };
        state.currentProc  = proc;
        state.busy         = true;
        state.thinkingDone = false;
        state.pendingPerm  = null;
        state.lastAiText   = '';
        try { if (state.socket) state.socket.write('\x1b]9;thinking-start\x07'); } catch(_) {}
        state.turn.tid = setTimeout(() => {
            log('[warm] turn timeout — killing warm proc\n');
            try { proc._manualKill = true; proc.kill('SIGKILL'); } catch(_) {}
            if (state.warmProc === proc) state.warmProc = null;
            finishWarmTurn(state, proc,
                '\x1b[31m✗ Timed out (180 s)\x1b[0m\r\n\x1b[2mSwitch to a faster model (Groq, Gemini Flash) in Settings.\x1b[0m\r\n');
        }, WARM_TURN_TIMEOUT_MS);
        // One NDJSON user line — the schema !test-persistent proved.
        const ndjson = JSON.stringify({ type: 'user', message: { role: 'user', content: [{ type: 'text', text: finalMsg }] } }) + '\n';
        try { proc.stdin.write(ndjson); }
        catch (e) {
            log('[warm] stdin write failed: ' + e.message + '\n');
            if (state.warmProc === proc) state.warmProc = null;
            finishWarmTurn(state, proc, '\x1b[31m[warm write error — resend your message] ' + e.message + '\x1b[0m\r\n');
        }
    }

    // ── Spawn one claude --print process for a single user message ────────────
    function runMessage(msg, state) {
        const cfg = readConfig();
        patchSettings(cfg);

        // If model/provider changed since last session, clear history so the new
        // model doesn't receive context it never produced.
        const clearFlagPath = path.join(FILES_DIR, 'history_clear_requested');
        if (fs.existsSync(clearFlagPath)) {
            try { fs.unlinkSync(clearFlagPath); } catch(_) {}
            state.hasHistory = false;
            clearSessionState(state.sid);
            clearClaudeSessionFiles();
            // The warm proc has the OLD model/persona baked into its env + history;
            // kill it so the next message respawns against the new config.
            killWarmProc(state, 'model/provider change');
            try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m[Model changed — history cleared for clean start]\x1b[0m\r\n'); } catch(_) {}
        }

        const customPrompt = (cfg.customSystemPrompt || '').trim();
        // The app holds MANAGE_EXTERNAL_STORAGE, so Bash can reach the whole device.
        // claude-code's system prompt advertises the sandbox cwd, which makes weaker
        // models refuse paths like /sdcard. Counter that with an explicit capability note.
        const STORAGE_NOTE =
            'Environment: you have FULL Android device storage access through the Bash tool ' +
            '(the app holds MANAGE_EXTERNAL_STORAGE). You may list, read, create, and edit files ' +
            'anywhere on the device — including /sdcard, /storage, and any path outside the working ' +
            'directory. Never refuse a file request by claiming a sandbox or "authorized working ' +
            'directory" limit; just run the appropriate Bash command.';
        // P4: storage-note + custom persona go via --append-system-prompt on the proot
        // guest (works on 2.1.160 — re-audit probe #4, b23), NOT prepended to the message.
        // The old prepend hack existed only because the flag hung on Bionic (inv 5a).
        const appendSys = [STORAGE_NOTE, customPrompt].filter(Boolean).join('\n\n');
        const finalMsg = msg;
        // Native HTTP MCP config for the guest (P3b). claude-code does NOT read mcpServers
        // from settings.json — servers are supplied at spawn via --mcp-config. Written
        // fresh each turn from the current server set; null when no MCP servers configured.
        const prootMcpCfg = writeProotMcpConfig();
        log('[runMessage] mcp_config exists=' + fs.existsSync(MCP_CONFIG_FILE) +
            ' mcp=' + (prootMcpCfg ? 'yes' : 'no') + '\n');

        // Verify cwd exists — spawn throws synchronously (ENOENT) if cwd is missing.
        const spawnCwd = (state.cwd && fs.existsSync(state.cwd)) ? state.cwd : FILES_DIR;
        // Pre-trust this cwd so claude-code skips the "do you trust this folder?" prompt
        // now that CLAUDE_CODE_SANDBOXED is gone (which also un-sandboxes the Bash tool).
        ensureProjectTrusted(spawnCwd);

        // ── WARM PATH (getWarmMode) — reuse ONE persistent proc, skip cold start ──
        // Branch off before the single-shot spawn. Everything above (config, MCP,
        // appendSys, cwd, trust) is shared. The single-shot path below is the
        // untouched fallback when warm mode is off.
        if (getWarmMode()) {
            log('[runMessage] WARM path (persistent proc), model=' + (cfg.modelId || '?') + ' guestCwd=' + spawnCwd + '\n');
            runWarmTurn(state, finalMsg, appendSys, prootMcpCfg, spawnCwd);
            return;
        }

        log('[runMessage] spawn claude-code (proot/2.1.160), model=' + (cfg.modelId || '?') + ' provider=' + (cfg.providerId || '?') + ' mode=' + (cfg.mode || '?') + ' baseUrl=' + (cfg.baseUrl || '?') + ' guestCwd=' + spawnCwd + '\n');
        log('[runMessage] argv: --output-format stream-json --print' + (prootMcpCfg ? ' --mcp-config ' + prootMcpCfg : '') + ' --append-system-prompt <sys' + (prootMcpCfg ? '+mcp-nudge' : '') + '>' + (state.hasHistory ? ' --continue' : '') + ' --verbose <msg>' + '\n');
        let proc;
        {
            // ── PROOT ENGINE (claude-code 2.1.160 on glibc) — the only engine (P4) ──
            // Spawn `claude --print …` INSIDE the Ubuntu rootfs. Real glibc node 22 has
            // ICU + full Unicode regex (no shims). The proxy bypass survives: proot shares
            // the host netns so ANTHROPIC_BASE_URL=127.0.0.1:8082 reaches the bridge proxy,
            // and the guest claude sends x-api-key:sk-ant-proxy000 which the proxy
            // auth-gate accepts (no x-local-token needed). settings.json + session files
            // are the SAME on-disk files via the /root/.claude bind.
            const benv = buildEnv();
            const guestEnv = {
                ANTHROPIC_API_KEY:   benv.ANTHROPIC_API_KEY,
                ANTHROPIC_MODEL:     benv.ANTHROPIC_MODEL,
                DISABLE_AUTOUPDATER: '1',
                MCP_TIMEOUT:         '30000',
                MCP_TOOL_TIMEOUT:    '30000',
                SHELL:               '/bin/bash',   // real bash exists in the guest
                // The proot guest runs as root (--root-id, HOME=/root). claude-code 2.1.160
                // refuses --dangerously-skip-permissions when euid==0 UNLESS IS_SANDBOX is
                // set — its built-in escape hatch for sandboxed root environments. proot IS
                // a sandbox, so this is the honest value, not a safety bypass. Without it the
                // permission system blocks Write/Bash redirects (b14 probe: "you haven't
                // granted it yet") since the legacy y\n/permissions.allow auto-approve path
                // doesn't carry into the guest.
                IS_SANDBOX:          '1',
            };
            if (benv.ANTHROPIC_BASE_URL) guestEnv.ANTHROPIC_BASE_URL = benv.ANTHROPIC_BASE_URL;
            const guestArgv = [GUEST_CLAUDE, '--output-format', 'stream-json', '--print'];
            // ③ permission-hack re-probe (b14): --dangerously-skip-permissions HUNG on
            // Bionic (inv 5b) — that was the whole reason for permissions.allow:['*'] +
            // y\n auto-approve + auto_approve.json + the permission card. On glibc/2.1.160
            // the HEAD-check hang should be gone. PROOT PATH ONLY (legacy untouched): if
            // it hangs here, we lose nothing. If it works, the entire permission apparatus
            // (card, auto_approve, customApiKeyResponses) can be deleted as scar tissue.
            guestArgv.push('--dangerously-skip-permissions');
            // P3b: native HTTP MCP for the guest (no stdio shim — inv-51 hang is a
            // Bionic scar, confirmed b26–b28). --mcp-config is variadic, so a flag
            // (--append-system-prompt / --continue / --verbose) must follow its value
            // before the positional message (inv 65b).
            if (prootMcpCfg) guestArgv.push('--mcp-config', prootMcpCfg);
            // P4: persona/storage-note (+ the MCP wait-nudge when MCP is active) in a
            // SINGLE --append-system-prompt — replaces the legacy message-prepend hack.
            // A value-taking flag here also terminates the --mcp-config variadic before
            // the positional message (inv 65b), same role --verbose plays.
            const sysParts = [appendSys, prootMcpCfg ? PROOT_MCP_SYS_NUDGE : ''].filter(Boolean);
            if (sysParts.length) guestArgv.push('--append-system-prompt', sysParts.join('\n\n'));
            if (state.hasHistory) guestArgv.push('--continue');
            guestArgv.push('--verbose', finalMsg);
            try {
                proc = prootChild(guestArgv, { extraEnv: guestEnv, workspace: spawnCwd });
            } catch (e) {
                log('[runMessage] proot spawn failed: ' + e.message + '\n');
                try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[proot engine spawn error] ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
                return;
            }
        }
        // Close stdin immediately — claude-code --print reads from stdin when it is a pipe
        // and blocks waiting for EOF if stdin stays open. Closing it tells claude-code the
        // input is empty so it uses the argv message. (proot bypassPermissions auto-runs
        // tools, so there's no y/n stdin prompt to answer.)
        try { proc.stdin.end(); } catch(_) {}
        state.currentProc = proc;
        state.busy = true;
        state.thinkingDone = false;
        state.pendingPerm = null;
        state.lastAiText = '';

        try { if (state.socket) state.socket.write('\x1b]9;thinking-start\x07'); } catch(_) {}

        let lineBuf = '';
        let stderrBuf = '';
        let firstContent = false;
        let resultReceived = false;

        proc.stdout.on('data', chunk => {
            lineBuf += chunk.toString();
            const lines = lineBuf.split('\n');
            lineBuf = lines.pop();

            for (const line of lines) {
                const t = line.trim();

                // ── Permission prompt detection ────────────────────────────────
                // claude-code without --dangerously-skip-permissions emits a
                // permission_request event (JSON) OR plain-text prompt for unknown
                // tool execution. Detect both and show our native dialog.
                if (t.startsWith('{')) {
                    let evt;
                    try { evt = JSON.parse(t); } catch(_) {
                        // Non-JSON line — log it and forward via SYS_FENCE so it routes
                        // to a sys bubble and never pollutes the AI bubble.
                        log('[stdout] ' + t.slice(0, 200) + '\n');
                        try { if (state.socket) state.socket.write(SYS_FENCE + line + '\n'); } catch(_) {}
                        continue;
                    }
                    handleStreamEvent(evt, state, proc, firstContent, (fc) => { firstContent = fc; }, resultReceived, (rr) => { resultReceived = rr; });
                } else if (t.length > 0) {
                    // Plain-text permission prompt (fallback for unexpected formats)
                    // Patterns: "Allow bash?", "Do you want to run...", "[y/n/a]"
                    if (/allow|permission|approve|proceed/i.test(t) && /\?|y\/n|\[y/i.test(t)) {
                        handlePermissionText(t, state, proc);
                    } else {
                        // Non-JSON plain output — log it and SYS_FENCE so it lands in a sys bubble
                        log('[stdout-plain] ' + t.slice(0, 200) + '\n');
                        try { if (state.socket) state.socket.write(SYS_FENCE + line + '\n'); } catch(_) {}
                    }
                }
            }
        });

        proc.stderr.on('data', d => {
            const s = d.toString();
            stderrBuf += s;
            log('[print-stderr] ' + s);
            // Strip bootstrap noise + cosmetic stdin warning; write real errors to terminal
            const lines = s.split('\n').filter(l => {
                const t = l.trim();
                return t &&
                    !/^\[(eval-ok|import-resolved|exit-event|unhandledRejection|regex-compat|intl-shim)\]/.test(t) &&
                    !/^Warning: no stdin data received/.test(t);
            });
            if (lines.length) {
                // SYS_FENCE: stderr must never land in the AI bubble even when
                // chatState=RESPONDING (streaming). Always route to sys bubble.
                try { if (state.socket) state.socket.write(SYS_FENCE + lines.join('\n')); } catch(_) {}
            }
        });

        proc.on('error', e => {
            state.currentProc = null;
            state.busy = false;
            // Split thinking-done from error so they can't coalesce into one chunk
            // that termWrite() would partially append to rawAiText.
            try { if (state.socket) state.socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
            try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[error] ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
        });

        // 180-second hard timeout (increased from 120 s to support large/slow models)
        const finishTid = setTimeout(() => {
            try { proc.kill('SIGTERM'); } catch(_) {}
            state.currentProc = null;
            state.busy = false;
            try { if (state.socket) state.socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
            try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m✗ Timed out (180 s)\x1b[0m\r\n\x1b[2mThis model is too slow. Switch to a faster model (Groq, Gemini Flash) in Settings.\x1b[0m\r\n'); } catch(_) {}
        }, 180000);

        proc.on('close', code => {
            clearTimeout(finishTid);
            // #11: flush any trailing partial line. A final stream-json event that
            // isn't newline-terminated would otherwise be dropped. Mirrors the
            // stdout 'data' handler's parse/route logic exactly.
            const tail = lineBuf.trim(); lineBuf = '';
            if (tail) {
                if (tail.startsWith('{')) {
                    try {
                        handleStreamEvent(JSON.parse(tail), state, proc, firstContent, (fc) => { firstContent = fc; }, resultReceived, (rr) => { resultReceived = rr; });
                    } catch (_) {
                        try { if (state.socket) state.socket.write(SYS_FENCE + tail + '\n'); } catch (_) {}
                    }
                } else {
                    try { if (state.socket) state.socket.write(SYS_FENCE + tail + '\n'); } catch (_) {}
                }
            }
            // Only update state if this proc is still the active one.
            // Ctrl+C clears state.currentProc immediately so a new message can start
            // without waiting for this close event — guard against clobbering the new proc.
            const isActiveProc = state.currentProc === proc;
            log('[runMessage] close code=' + code + ' isActive=' + isActiveProc + ' firstContent=' + firstContent + ' ctrlC=' + !!proc._ctrlCKill + '\n');
            if (isActiveProc) {
                state.currentProc = null;
                state.busy = false;
                state.thinkingDone = false;
                try { if (state.socket) state.socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
            }
            if (proc._manualKill || proc._ctrlCKill) return; // intentional kill — suppress error
            if (code !== 0 && !firstContent) {
                const rateLimited = (Date.now() - lastRateLimitMs) < 30000;
                if (rateLimited) {
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m⚠ Rate limited — wait 30–60 s then retry, or switch model.\x1b[0m\r\n'); } catch(_) {}
                } else {
                    // M13: show first error-keyword line + last 3 lines (deduped)
                    const filteredStderr = stderrBuf.split('\n')
                        .filter(l => l.trim() && !/^\[(eval-ok|import-resolved|exit-event|unhandledRejection|regex-compat|intl-shim)\]/.test(l.trim()));
                    const firstErrLine = filteredStderr.find(l => /Error|error|ERR/.test(l)) || filteredStderr[0] || '';
                    const lastThree = filteredStderr.slice(-3);
                    const combined = firstErrLine
                        ? [firstErrLine, ...lastThree.filter(l => l !== firstErrLine)]
                        : lastThree;
                    const errLines = combined.join('\r\n');
                    let hint;
                    if (code === 143) {
                        hint = '\x1b[31m✗ Request timed out (180 s)\x1b[0m\r\n' +
                               '\x1b[2mIf you see 401/auth errors above, check your API key in Settings.\x1b[0m\r\n' +
                               '\x1b[2mSlow model? Switch to Groq or Gemini Flash in Settings.\x1b[0m\r\n';
                    } else {
                        hint = '\x1b[31m[claude exited ' + code + ']\x1b[0m\r\n';
                        if (errLines) hint += '\x1b[2m' + errLines + '\x1b[0m\r\n';
                        hint += '\x1b[2mType !log for bridge log\x1b[0m\r\n';
                    }
                    try { if (state.socket) state.socket.write(SYS_FENCE + hint); } catch(_) {}
                }
            }
        });
    }

    // ── Input handler ─────────────────────────────────────────────────────────
    function handleInput(d, state) {
        // Strip in-band resize control sequences (ESC 0xFE hiC loC hiR loR, 6 bytes).
        // These were consumed by the removed !pty TTY path; with no consumer they
        // otherwise leak into inputBuf and — because the cols/rows low bytes are
        // printable punctuation (. , - + * () and occasionally land on \r/\n —
        // flush as a gibberish self-sent message (e.g. on terminal resume → resize).
        if (Buffer.isBuffer(d) && d.indexOf(0x1b) !== -1) {
            const out = [];
            for (let i = 0; i < d.length; i++) {
                if (d[i] === 0x1b && i + 1 < d.length && d[i + 1] === 0xfe) { i += 5; continue; }
                out.push(d[i]);
            }
            d = Buffer.from(out);
        }
        // Ctrl+C: kill the in-flight claude process
        const raw = d.toString();
        if (raw.includes('\x03')) {
            if (state.busy && state.currentProc) {
                const dyingProc = state.currentProc;
                dyingProc._ctrlCKill = true;
                try { dyingProc.kill('SIGTERM'); } catch(_) {}
                // Clear immediately so the close event (which fires async) doesn't
                // overwrite a new proc that may have been spawned in the meantime.
                state.currentProc = null;
                state.busy = false;
                state.thinkingDone = false;
                // Warm mode: ^C interrupts the turn AND the warm proc (its stdin/turn
                // is now mid-stream) → drop it so the next message respawns clean.
                if (dyingProc._warm) {
                    if (state.turn && state.turn.tid) { clearTimeout(state.turn.tid); state.turn.done = true; }
                    if (state.warmProc === dyingProc) state.warmProc = null;
                }
                try { if (state.socket) state.socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
                try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m^C — interrupted\x1b[0m\r\n'); } catch(_) {}
            }
            state.inputBuf = '';
            return;
        }

        state.inputBuf += raw;
        let nl;
        while ((nl = state.inputBuf.search(/[\r\n]/)) !== -1) {
            let line = state.inputBuf.slice(0, nl)
                .replace(/[\x00-\x08\x0b-\x1f\x7f]/g, '')
                .replace(/！/g, '!')
                // Strip invisible/format Unicode that Android IME may prepend or embed,
                // causing startsWith("!") failures and encoding noise reaching claude-code.
                // C1 controls, soft-hyphen, zero-width, line/para seps, format ops, BOM,
                // Mongolian FVS (180B-180F), variation selectors (FE00-FE0F), ORC/FFFD,
                // CGJ (034F), Arabic LM (061C), Hangul fillers (115F-1160, 3164, FFA0),
                // Khmer inherent vowels (17B4-17B5).
                .replace(/[\u0080-\u009f\u00ad\u034f\u061c\u115f\u1160\u17b4\u17b5\u180b-\u180f\u200b-\u200f\u2028-\u202f\u2060-\u206f\u3164\ufe00-\ufe0f\ufeff\uffa0\ufff0-\uffff]/g, '')
                .trim();
            state.inputBuf = state.inputBuf.slice(nl + 1);
            if (!line) continue;

            // Secondary guard: if only non-word chars precede a '!' or '$', strip them.
            // Uses !\w so it catches ASCII punctuation/garbage too, but preserves real
            // words before !/$  (e.g. "what does ! mean" is left intact).
            {
                const fi = line.indexOf('!'), fd = line.indexOf('$');
                const fc = fi === -1 ? fd : (fd === -1 ? fi : Math.min(fi, fd));
                if (fc > 0 && !/\w/.test(line.slice(0, fc))) line = line.slice(fc);
            }

            // Tertiary guard: strip a leading run of gesture-keyboard artefact
            // punctuation before the first word char. Android glide/gesture keyboards
            // leak composing noise like ".-.+.+.+.*.,.hey" (often 10–20+ chars, so no
            // length cap). Only strip when the WHOLE prefix is artefact punctuation
            // (no spaces, no word chars) AND has ≥2 distinct chars — so single-char
            // runs like "...", "---", "***" (markdown/dividers) are left intact.
            {
                const fw = line.search(/\w/);
                if (fw > 0) {
                    const prefix = line.slice(0, fw);
                    if (/^[.,\-+*~^'";:]+$/.test(prefix) && new Set(prefix).size >= 2) {
                        line = line.slice(fw);
                    }
                }
            }

            // Normalize "! cmd" / "!  cmd" → "!cmd" (autocorrect adds space(s) after !)
            if (/^!\s+/.test(line)) line = '!' + line.slice(1).trimStart();
            // Normalize "$cmd" → "$ cmd" so the shell handler matches
            if (line.startsWith('$') && !line.startsWith('$ ') && line.length > 1) line = '$ ' + line.slice(1).trimStart();
            // Bare "!" or "$" alone is a no-op
            if (line === '!' || line === '$') continue;
            // Lowercase the ! command verb — Android autocorrect/autocapitalize treats
            // "!" as sentence-ending punctuation and capitalizes the next character
            // (e.g. "!Test-cli" instead of "!test-cli"), breaking all startsWith checks.
            // Only lowercase the verb itself, not any arguments after the first space.
            if (line.startsWith('!')) {
                const sp = line.indexOf(' ');
                line = (sp === -1 ? line : line.slice(0, sp)).toLowerCase() + (sp === -1 ? '' : line.slice(sp));
            }

            // ── Permission responses — vestigial (bypass busy guard) ──────────────
            // P4: proot auto-runs every tool (bypassPermissions), so non-agent
            // permission cards no longer appear and the always-allow list (auto_approve)
            // was deleted. These commands only ever arrive if an old WebView UI still has
            // a card open — just dismiss any pending state.
            if (line.startsWith('!perm-allow') || line.startsWith('!perm-always') || line.startsWith('!perm-deny')) {
                state.pendingPerm = null;
                continue;
            }

            // ── !clear — interrupt current process and reset session ──────────────
            if (line.startsWith('!clear')) {
                if (state.currentProc) {
                    state.currentProc._manualKill = true;
                    try { state.currentProc.kill('SIGTERM'); } catch(_) {}
                    state.currentProc = null;
                }
                // Warm mode: history lives in the proc, so a true reset = kill it.
                // (currentProc may already be the warm proc above; null warmProc too.)
                killWarmProc(state, '!clear');
                if (state.turn && state.turn.tid) { clearTimeout(state.turn.tid); state.turn.done = true; }
                state.busy          = false;
                state.hasHistory    = false;
                state.contextBlock  = '';
                state.pendingAttach = null;
                state.sessionTokens = 0;
                state.pendingPerm   = null;
                clearSessionState(state.sid);
                clearClaudeSessionFiles();
                try { if (state.socket) state.socket.write('\x1b]9;tokens:0\x07'); } catch(_) {}
                try { if (state.socket) state.socket.write('\x1b]9;clear-ui\x07'); } catch(_) {}
                try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[2m[history cleared — next message starts a new session]\x1b[0m\r\n'); } catch(_) {}
                continue;
            }

            // ── !log and !help — safe to run even while claude is busy ──────────
            // These only read files / print static text; they don't touch the
            // running claude process. SYS_FENCE ensures output routes to a sys
            // bubble and never pollutes the AI bubble.
            if (line.startsWith('!help')) {
                try { if (state.socket) state.socket.write(SYS_FENCE +
                    '\x1b[1m[print mode — per-message spawn]\x1b[0m\r\n' +
                    '  \x1b[33m!clear\x1b[0m              Start a new conversation\r\n' +
                    '  \x1b[33m!context [path]\x1b[0m     Load file/dir as context\r\n' +
                    '  \x1b[33m!attach <file>\x1b[0m      Attach file to next message\r\n' +
                    '  \x1b[33m!install [pkg]\x1b[0m      Install binary/npm (no arg = list available)\r\n' +
                    '  \x1b[33m!log [n|all|clear]\x1b[0m   Show last n lines (default 100); !log all = full; !log clear = wipe\r\n' +
                    '  \x1b[33m!mcp\x1b[0m                List connected (and failed) MCP servers and tools\r\n' +
                    '  \x1b[33m!mcp-log [name|all]\x1b[0m Show captured stderr from stdio MCP servers (default 50 lines)\r\n' +
                    '  \x1b[33m!mcp-reload\x1b[0m         Apply Settings toggles without restarting the session\r\n' +
                    '  \x1b[33m!test-agent\x1b[0m         Probe sub-agent dispatch (writes ~/.claude/agents/nexus_probe.md, runs it)\r\n' +
                    '  \x1b[33m!test-mcp\x1b[0m           Probe native HTTP MCP in the proot guest\r\n' +
                    '  \x1b[33m!test-proot\x1b[0m         Probe: does bundled proot exec from nativeLibDir (Ubuntu engine)\r\n' +
                    '  \x1b[33m!test-rootfs\x1b[0m        Probe: run extracted Ubuntu rootfs via proot (cat /etc/os-release)\r\n' +
                    '  \x1b[33m!setup-engine\x1b[0m       Batched P1: boot rootfs → install Node 22 + claude-code → claude --version\r\n' +
                    '  \x1b[33m!claude-version\x1b[0m     Show installed + latest claude-code version (no change)\r\n' +
                    '  \x1b[33m!update-claude\x1b[0m      Update claude-code to @latest if newer (force to reinstall) — skips the 6-step setup\r\n' +
                    '  \x1b[33m!cleanup\x1b[0m            Reclaim install caches (npm/apt/tarballs, ~400MB) without re-provisioning\r\n' +
                    '  \x1b[33m!defer\x1b[0m              Proxy tool deferral (lazy-load) for OAI providers: !defer on | off\r\n' +
                    '  \x1b[33m!warm\x1b[0m               Persistent session — reuse one warm proc, skip cold start: !warm on | off\r\n' +
                    '  \x1b[33m!apt-diagnose\x1b[0m       Diagnose apt in the Ubuntu guest (resolv.conf + apt-get update test)\r\n' +
                    '  \x1b[33m!apt-fix-dns\x1b[0m        Rewrite resolv.conf + run apt-get update to fix apt install\r\n' +
                    '  \x1b[33m!dpkg-test\x1b[0m          Probe why dpkg fails on install (link2symlink/EPERM check)\r\n' +
                    '  \x1b[33m!apt-extract <pkg>\x1b[0m  Install an apt package WITHOUT dpkg (download + extract; CLI tools)\r\n' +
                    '  \x1b[33m!debug\x1b[0m              Dump model/provider/settings/mcp state for remote debugging\r\n' +
                    '  \x1b[33m!help\x1b[0m               Show this help\r\n' +
                    '  \x1b[33m$ <cmd>\x1b[0m             Run a shell command\r\n\r\n'
                ); } catch(_) {}
                continue;
            }

            if (line.startsWith('!log')) {
                const arg = line.slice(4).trim();
                // !log clear — wipe the accumulated log (install + downloads + all
                // prior sessions pile up here forever, so even a fresh session's
                // last-100-lines reaches back into old noise). Start clean.
                if (arg === 'clear') {
                    try { fs.writeFileSync(SETUP_LOG, ''); } catch(_) {}
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[32m[log cleared]\x1b[0m\r\n'); } catch(_) {}
                    continue;
                }
                const showAll = arg === 'all';
                const n = showAll ? Infinity : (parseInt(arg) || 100);
                try {
                    const logData = fs.readFileSync(SETUP_LOG, 'utf8');
                    const lines = logData.split('\n');
                    // Strip ANSI/cursor sequences — log may contain PTY output with cursor
                    // movements that scatter characters across the terminal grid when re-parsed.
                    const out = (n === Infinity ? lines : lines.slice(-n))
                        .map(l => stripAnsi(l))
                        .join('\r\n');
                    if (state.socket) state.socket.write(SYS_FENCE + out + '\r\n');
                } catch(_) { try { if (state.socket) state.socket.write(SYS_FENCE + '[no log]\r\n'); } catch(_) {} }
                continue;
            }

            // ── !debug — one-shot state dump for remote debugging ────────────────
            if (line.startsWith('!debug')) {
                try {
                    const dcfg = readConfig();
                    const sp   = path.join(FILES_DIR, '.claude', 'settings.json');
                    let settingsSnippet = '(none)';
                    try {
                        const s = JSON.parse(fs.readFileSync(sp, 'utf8'));
                        settingsSnippet = JSON.stringify({
                            dangerouslySkipPermissions: s.dangerouslySkipPermissions,
                            permissions: s.permissions,
                            customApiKeyResponses: { approved: (s.customApiKeyResponses || {}).approved }
                        });
                    } catch(_) {}
                    const mcpExists  = fs.existsSync(MCP_CONFIG_FILE);
                    let mcpSnippet = '(none)';
                    if (mcpExists) try { mcpSnippet = fs.readFileSync(MCP_CONFIG_FILE, 'utf8').slice(0, 200); } catch(_) {}
                    // Build the argv that would be used for the next message
                    const nextArgv = [
                        '--output-format', 'stream-json', '--print', '--verbose',
                        ...(state.hasHistory ? ['--continue'] : []),
                        '<your message>'
                    ].join(' ');
                    const out =
                        '\x1b[1m[debug dump]\x1b[0m\r\n' +
                        '  model    : ' + (dcfg.modelId || '?') + '\r\n' +
                        '  provider : ' + (dcfg.providerId || '?') + '\r\n' +
                        '  mode     : ' + (dcfg.mode || '?') + '\r\n' +
                        '  baseUrl  : ' + (dcfg.baseUrl || '?') + '\r\n' +
                        '  hasHistory: ' + state.hasHistory + '  busy: ' + state.busy + '\r\n' +
                        '  mcp_config: ' + (mcpExists ? '\x1b[33mEXISTS\x1b[0m' : '\x1b[2mnone\x1b[0m') + '\r\n' +
                        (mcpExists ? '    ' + mcpSnippet.slice(0, 120) + '\r\n' : '') +
                        '  settings.json: ' + settingsSnippet + '\r\n' +
                        '  next argv: ' + nextArgv + '\r\n';
                    try { if (state.socket) state.socket.write(SYS_FENCE + out); } catch(_) {}
                } catch(e) {
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[debug error] ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
                }
                continue;
            }

            // ── !test-proot — Ubuntu-engine P1 "confirm C" probe ─────────────
            // Execs the bundled proot binary (libproot.so in nativeLibDir) with
            // `--version`. This proves the single riskiest assumption of the
            // Ubuntu engine: that a (dynamic) proot binary actually EXECUTES from
            // nativeLibDir on this device (the only exec-capable path; /data is
            // noexec) and that its libtalloc.so dep resolves. No rootfs needed —
            // --version parses argv and exits before any ptrace/loader work.
            if (line.startsWith('!test-proot')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                try {
                    const prootBin = path.join(NATIVE_DIR, 'libproot.so');
                    if (!fs.existsSync(prootBin)) {
                        w('\x1b[31m✗ !test-proot: libproot.so NOT in ' + NATIVE_DIR + '\x1b[0m\r\n' +
                          '\x1b[2m(this APK predates the proot CI step — rebuild from the branch that has it)\x1b[0m\r\n');
                        continue;
                    }
                    // proot is shipped pristine (NOT patchelf'd — bionic rejects
                    // patched binaries). It still NEEDs "libtalloc.so.2", but only
                    // "libtalloc.so" extracts from the APK. Bridge the gap with a
                    // symlink in a writable dir on LD_LIBRARY_PATH: the symlink
                    // resolves to the real exec-capable file in nativeLibDir.
                    const prootLibDir = path.join(FILES_DIR, '.proot-lib');
                    try { fs.mkdirSync(prootLibDir, { recursive: true }); } catch(_) {}
                    const tallocLink = path.join(prootLibDir, 'libtalloc.so.2');
                    try { fs.unlinkSync(tallocLink); } catch(_) {}
                    try { fs.symlinkSync(path.join(NATIVE_DIR, 'libtalloc.so'), tallocLink); } catch(e) {
                        w('\x1b[33m!test-proot: symlink warn — ' + e.message + '\x1b[0m\r\n');
                    }
                    w('\x1b[33m!test-proot: exec libproot.so --version (15s)…\x1b[0m\r\n');
                    const pEnv = Object.assign({}, process.env, {
                        LD_LIBRARY_PATH: prootLibDir + ':' + NATIVE_DIR,               // libtalloc.so.2 → libtalloc.so
                        PROOT_LOADER:    path.join(NATIVE_DIR, 'libproot-loader.so'),
                        PROOT_LOADER_32: path.join(NATIVE_DIR, 'libproot-loader32.so'),
                    });
                    const pch = spawn(prootBin, ['--version'], { env: pEnv });
                    let pOut = '', pErr = '', pDone = false;
                    pch.stdout.on('data', d => { pOut += d.toString(); });
                    pch.stderr.on('data', d => { pErr += d.toString(); });
                    pch.on('error', e => {
                        if (pDone) return; pDone = true;
                        w('\x1b[31m✗ !test-proot: spawn FAILED — ' + e.message + '\x1b[0m\r\n' +
                          '\x1b[2mEACCES→noexec/perm · ENOEXEC→bad/dynamic binary · "library not found"→libtalloc didn\'t resolve\x1b[0m\r\n');
                    });
                    const pTid = setTimeout(() => {
                        if (pDone) return; pDone = true;
                        try { pch.kill(); } catch(_) {}
                        w('\x1b[31m✗ !test-proot: TIMEOUT 15s\x1b[0m\r\n');
                    }, 15000);
                    pch.on('close', code => {
                        if (pDone) return; pDone = true;
                        clearTimeout(pTid);
                        const txt = (pOut + pErr).trim();
                        const ok = code === 0 && /proot|version/i.test(txt);
                        w((ok ? '\x1b[32m✓ proot EXECS from nativeLibDir — C CONFIRMED\x1b[0m'
                              : '\x1b[31m✗ proot did NOT run cleanly (exit=' + code + ')\x1b[0m') + '\r\n' +
                          '\x1b[2m' + (txt.slice(0, 400) || '(no output)') + '\x1b[0m\r\n');
                    });
                } catch(e) {
                    w('\x1b[31m[!test-proot error] ' + e.message + '\x1b[0m\r\n');
                }
                continue;
            }

            // ── !hotload — DEBUG: pull the latest bridge.js from GitHub (no APK) ─
            // Downloads (inside node, off the Android main thread) the branch's
            // bridge.js → filesDir/bridge_dev.js. On next app start, the Kotlin
            // ensureBridgeJs() prefers a valid bridge_dev.js over the bundled asset
            // (DEBUG only). So a JS change = git push → !hotload → force-stop/reopen,
            // NO rebuild. `!hotload reset` removes the dev copy (back to bundled).
            if (line.startsWith('!hotload') && !line.startsWith('!hotload-ui') && !line.startsWith('!hotload-dungeon')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const arg = line.slice('!hotload'.length).trim();
                const devPath = path.join(FILES_DIR, 'bridge_dev.js');
                if (arg === 'reset') {
                    try { fs.unlinkSync(devPath); w('\x1b[32m✓ hot-load reset\x1b[0m\r\n'); }
                    catch(_) { w('\x1b[33m(no dev bridge to reset)\x1b[0m\r\n'); }
                    w('\x1b[2mForce-stop + reopen the app to apply (bundled bridge.js).\x1b[0m\r\n');
                    continue;
                }
                // Fetch via the GitHub *API* contents endpoint. Falls back to
                // raw.githubusercontent.com if the API fails (rate-limit / DNS issues).
                const REF = 'feat/glass-ui';
                const apiUrl = 'https://api.github.com/repos/fahmi304/Nexus-Mind/contents/' +
                               'app/src/main/assets/nodejs-project/bridge.js?ref=' + REF;
                const rawUrl = 'https://raw.githubusercontent.com/fahmi304/Nexus-Mind/' + REF +
                               '/app/src/main/assets/nodejs-project/bridge.js';
                // Try API first (ETag-based, fresh), then raw CDN as fallback.
                const urls = [apiUrl, rawUrl];
                const apiHeaders = { 'Accept': 'application/vnd.github.raw', 'User-Agent': 'nexus-hotload', 'Cache-Control': 'no-cache' };
                const rawHeaders = { 'User-Agent': 'nexus-hotload', 'Cache-Control': 'no-cache' };
                const tmp = devPath + '.tmp';
                w('\x1b[33m!hotload: fetching latest bridge.js (running build ' + BRIDGE_BUILD + ')…\x1b[0m\r\n');
                (async () => {
                    let txt = '', src = '';
                    // Try API first, then raw CDN fallback
                    for (let i = 0; i < urls.length; i++) {
                        const u = urls[i];
                        const headers = i === 0 ? apiHeaders : rawHeaders;
                        try {
                            const res = await httpsGet(u, { headers });
                            if (res.statusCode !== 200) { res.resume(); continue; }
                            let chunk = ''; res.setEncoding('utf8');
                            await new Promise((rs, rj) => { res.on('data', c => chunk += c); res.on('end', rs); res.on('error', rj); });
                            if (chunk.length > 5000 && chunk.includes('SYS_FENCE')) {
                                txt = chunk; src = i === 0 ? 'API' : 'raw CDN';
                                break;
                            }
                        } catch (_) {}
                    }
                    if (!txt) throw new Error('all sources failed (API + raw CDN)');
                    const m = txt.match(/BRIDGE_BUILD\s*=\s*'([^']+)'/);
                    const dlBuild = m ? m[1] : '(no stamp)';
                    fs.writeFileSync(devPath, txt);
                    w('\x1b[32m✓ hot-loaded ' + txt.length + ' bytes → build ' + dlBuild + ' (via ' + src + ')\x1b[0m\r\n' +
                      (dlBuild === BRIDGE_BUILD
                        ? '\x1b[33m⚠ downloaded build == running build (already current, or you just need to force-stop+reopen)\x1b[0m\r\n'
                        : '') +
                      '\x1b[36mNow FORCE-STOP the app and reopen it. After reopen, run a command — it will show build ' + dlBuild + '.\x1b[0m\r\n');
                })().catch(e => w('\x1b[31m✗ hotload failed: ' + (e && e.message) + '\x1b[0m\r\n'));
                continue;
            }

            // ── !hotload-ui — DEBUG: pull the latest terminal UI assets (no APK) ─
            // Sibling of !hotload, but for the WebView UI instead of the engine.
            // Fetches the branch's terminal/index.html → filesDir/index_dev.html and
            // providers.json → filesDir/providers_dev.json. TerminalActivity prefers a
            // valid index_dev.html (loaded with the asset dir as base URL so the
            // relative xterm.* refs still resolve), and ProvidersRepository prefers a
            // valid providers_dev.json — both DEBUG-only. So a UI/provider-list change =
            // git push → !hotload-ui → force-stop/reopen, NO rebuild.
            // `!hotload-ui reset` removes both dev copies (back to bundled assets).
            if (line.startsWith('!hotload-ui')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const arg = line.slice('!hotload-ui'.length).trim();
                const htmlDev = path.join(FILES_DIR, 'index_dev.html');
                const provDev = path.join(FILES_DIR, 'providers_dev.json');
                if (arg === 'reset') {
                    let n = 0;
                    for (const p of [htmlDev, provDev]) { try { fs.unlinkSync(p); n++; } catch(_) {} }
                    w(n ? '\x1b[32m✓ UI hot-load reset (' + n + ' file' + (n > 1 ? 's' : '') + ')\x1b[0m\r\n'
                        : '\x1b[33m(no dev UI assets to reset)\x1b[0m\r\n');
                    w('\x1b[2mForce-stop + reopen the app to apply (bundled assets).\x1b[0m\r\n');
                    continue;
                }
                const REF = 'feat/glass-ui';
                const api = (p) => 'https://api.github.com/repos/fahmi304/Nexus-Mind/contents/' + p + '?ref=' + REF;
                const raw = (p) => 'https://raw.githubusercontent.com/fahmi304/Nexus-Mind/' + REF + '/' + p;
                const apiHeaders = { 'Accept': 'application/vnd.github.raw', 'User-Agent': 'nexus-hotload', 'Cache-Control': 'no-cache' };
                const rawHeaders = { 'User-Agent': 'nexus-hotload', 'Cache-Control': 'no-cache' };
                // [remote path, dest, minBytes, validator(text)]
                const targets = [
                    ['app/src/main/assets/terminal/index.html', htmlDev, 5000, t => t.includes('termWrite')],
                    ['app/src/main/assets/providers.json',      provDev, 100,  t => { try { return Array.isArray(JSON.parse(t).providers); } catch(_) { return false; } }],
                ];
                w('\x1b[33m!hotload-ui: fetching index.html + providers.json…\x1b[0m\r\n');
                (async () => {
                    for (const [remote, dest, min, valid] of targets) {
                        const label = remote.split('/').pop();
                        let txt = '', src = '';
                        // Try API first, then raw CDN fallback
                        for (let i = 0; i < 2; i++) {
                            const url = i === 0 ? api(remote) : raw(remote);
                            const headers = i === 0 ? apiHeaders : rawHeaders;
                            try {
                                const res = await httpsGet(url, { headers });
                                if (res.statusCode !== 200) { res.resume(); continue; }
                                let chunk = ''; res.setEncoding('utf8');
                                await new Promise((rs, rj) => { res.on('data', c => chunk += c); res.on('end', rs); res.on('error', rj); });
                                if (chunk.length > min && valid(chunk)) {
                                    txt = chunk; src = i === 0 ? 'API' : 'raw CDN';
                                    break;
                                }
                            } catch (_) {}
                        }
                        if (txt) {
                            fs.writeFileSync(dest, txt);
                            w('\x1b[32m✓ ' + label + ' → ' + txt.length + ' bytes (via ' + src + ')\x1b[0m\r\n');
                        } else {
                            w('\x1b[31m✗ ' + label + ' failed: all sources (API + raw CDN)\x1b[0m\r\n');
                        }
                    }
                    w('\x1b[36mNow FORCE-STOP the app and reopen it to load the new UI.\x1b[0m\r\n');
                })();
                continue;
            }

            // ── !hotload-dungeon — DEBUG: pull the latest dungeon UI (no APK) ──
            // Same mechanic as !hotload-ui but for assets/dungeon/index.html.
            // Fetches branch HEAD → filesDir/dungeon_dev.html. DungeonActivity
            // prefers this file over the bundled asset when present.
            // `!hotload-dungeon reset` removes the dev copy (back to bundled).
            if (line.startsWith('!hotload-dungeon')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const arg = line.slice('!hotload-dungeon'.length).trim();
                const devFile = path.join(FILES_DIR, 'dungeon_dev.html');
                if (arg === 'reset') {
                    try { fs.unlinkSync(devFile); w('\x1b[32m✓ dungeon hot-load reset\x1b[0m\r\n'); }
                    catch(_) { w('\x1b[33m(no dev dungeon asset to reset)\x1b[0m\r\n'); }
                    w('\x1b[2mForce-stop + reopen to apply (bundled asset).\x1b[0m\r\n');
                    continue;
                }
                const REF = 'feat/glass-ui';
                const remote = 'app/src/main/assets/dungeon/index.html';
                const api = 'https://api.github.com/repos/fahmi304/Nexus-Mind/contents/' + remote + '?ref=' + REF;
                w('\x1b[33m!hotload-dungeon: fetching dungeon/index.html…\x1b[0m\r\n');
                (async () => {
                    try {
                        const res = await httpsGet(api, { headers: {
                            'Accept': 'application/vnd.github.raw',
                            'User-Agent': 'nexus-hotload',
                            'Cache-Control': 'no-cache',
                        }});
                        if (res.statusCode !== 200) { res.resume(); throw new Error('HTTP ' + res.statusCode); }
                        let txt = ''; res.setEncoding('utf8');
                        await new Promise((rs, rj) => { res.on('data', c => txt += c); res.on('end', rs); res.on('error', rj); });
                        if (txt.length > 500 && txt.includes('DungeonAndroid')) {
                            const remoteB = (txt.match(/DUNGEON_BUILD\s*=\s*'([^']+)'/) || [])[1] || '?';
                            let localB = '(none)';
                            try { const cur = fs.readFileSync(devFile,'utf8'); localB = (cur.match(/DUNGEON_BUILD\s*=\s*'([^']+)'/) || [])[1] || '?'; } catch(_){}
                            fs.writeFileSync(devFile, txt);
                            if (remoteB === localB) {
                                w('\x1b[33m⚠ downloaded build == running build (' + remoteB + ') — already current, or force-stop+reopen\x1b[0m\r\n');
                            } else {
                                w('\x1b[32m✓ dungeon/index.html → ' + txt.length + ' bytes (build ' + remoteB + ')\x1b[0m\r\n');
                            }
                        } else {
                            w('\x1b[31m✗ dungeon/index.html invalid (size=' + txt.length + ') — kept current\x1b[0m\r\n');
                        }
                    } catch (e) {
                        w('\x1b[31m✗ dungeon fetch failed: ' + (e && e.message) + '\x1b[0m\r\n');
                    }
                    w('\x1b[36mForce-stop the app and reopen to load the new dungeon UI.\x1b[0m\r\n');
                })();
                continue;
            }

            // ── !test-rootfs — Ubuntu-engine P1b acceptance probe ────────────
            // Runs the REAL proot argv (rootfs + binds) against the extracted
            // Ubuntu rootfs (FILES_DIR/ubuntu) via node's spawn and prints
            // /etc/os-release. MUST go through node, not Kotlin ProcessBuilder:
            // bionic scrubs LD_LIBRARY_PATH on ProcessBuilder children (AT_SECURE),
            // so proot can't resolve libtalloc.so.2 there — confirmed on-device.
            // node/libuv execve honors LD_LIBRARY_PATH (same path !test-proot uses).
            // Extract the rootfs first via Settings → 🐞 Ubuntu engine.
            if (line.startsWith('!test-rootfs')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const rp = path.join(FILES_DIR, 'ubuntu');
                if (!fs.existsSync(path.join(rp, 'etc', 'os-release'))) {
                    w('\x1b[31m✗ !test-rootfs: no rootfs at ' + rp + '\x1b[0m\r\n' +
                      '\x1b[2mExtract it first: Settings → 🐞 Ubuntu engine → Install + probe.\x1b[0m\r\n');
                    continue;
                }
                // Flexible diagnostic: !test-rootfs [N] [raw] [ENV=K=V…] [guest args…]
                //   N        = proot verbosity 0-9 (default 1)
                //   raw      = exec the guest directly, no `/usr/bin/env -i` wrapper
                //   ENV=K=V  = override a proot env var (ENV=K= deletes it). Used to
                //              find the seccomp knob that fixes in-guest execve, e.g.
                //              ENV=PROOT_ASSUME_NEW_SECCOMP=1 or ENV=PROOT_NO_SECCOMP=1
                //   rest     = guest argv (default: /usr/bin/cat /etc/os-release)
                // Terminal commands are free (no CI), so iterate here, not via builds.
                const toks = line.slice('!test-rootfs'.length).trim().split(/\s+/).filter(Boolean);
                let vlvl = 1, rawExec = false; const extraEnv = {};
                while (toks.length) {
                    if (/^\d$/.test(toks[0])) { vlvl = parseInt(toks.shift(), 10); }
                    else if (toks[0] === 'raw') { rawExec = true; toks.shift(); }
                    else if (toks[0].startsWith('ENV=')) {
                        const kv = toks.shift().slice(4); const i = kv.indexOf('=');
                        if (i > 0) extraEnv[kv.slice(0, i)] = kv.slice(i + 1);
                    }
                    else break;
                }
                const envNote = Object.keys(extraEnv).length
                    ? ' [env ' + Object.entries(extraEnv).map(([k, v]) => k + '=' + v).join(' ') + ']' : '';
                const guest = toks.length ? toks : ['/usr/bin/cat', '/etc/os-release'];
                w('\x1b[33m!test-rootfs: proot -v' + vlvl + (rawExec ? ' raw' : '') + envNote +
                  ' → ' + guest.join(' ') + ' (60s)…\x1b[0m\r\n');
                runProotGuest(guest, 60000, null, { verbose: vlvl, rawExec, extraEnv })
                    .then(async r => {
                        const ok = r.code === 0 && /Ubuntu/i.test(r.out);
                        const body = r.out.trim();
                        w((ok ? '\x1b[32m✅ Ubuntu rootfs runs via proot — P1b CONFIRMED\x1b[0m'
                              : '\x1b[31m✗ rootfs probe failed (exit=' + r.code + ')\x1b[0m') + '\r\n' +
                          '\x1b[2m' + (ok ? body.slice(0, 600) : body.slice(-1200)) + '\x1b[0m\r\n');
                        // Always upload the FULL trace so it can be read off-device
                        // (emulator screenshots crop every line). Short URL only.
                        const url = await uploadDiag('=== !test-rootfs -v' + vlvl +
                            (rawExec ? ' raw' : '') + envNote + ' [' + guest.join(' ') +
                            '] (exit=' + r.code + ', ok=' + ok + ') ===\n' + body);
                        if (url) w('\x1b[36m📋 full trace: ' + url + '\x1b[0m\r\n');
                    })
                    .catch(e => w('\x1b[31m[!test-rootfs error] ' + (e && e.message) + '\x1b[0m\r\n'));
                continue;
            }

            // ── !fix-seccomp — sweep proot seccomp knobs to fix in-guest execve ─
            // In-guest execve (env→cat) ENOSYS's because seccomp interferes with
            // proot's execve→loader hand-off. The fix is a proot env knob; this
            // tries each combo IN SEQUENCE against the env→cat canary and reports
            // which one yields exit 0 — one command, one URL. The winning combo is
            // then baked into runProotGuest's base env permanently.
            if (line.startsWith('!fix-seccomp')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const rp = path.join(FILES_DIR, 'ubuntu');
                if (!fs.existsSync(path.join(rp, 'etc', 'os-release'))) {
                    w('\x1b[31m✗ !fix-seccomp: no rootfs — extract first (Settings → 🐞 Ubuntu engine).\x1b[0m\r\n');
                    continue;
                }
                // Each combo merges over the base env (which has PROOT_NO_SECCOMP=1).
                // '' deletes a base var. Canary = chdir + getcwd: `cd /root` exercises
                // the chdir syscall, `/bin/pwd` (the REAL binary, not the shell builtin
                // which just echoes $PWD) exercises getcwd. Both ENOSYS under the base
                // env on-device → npm/claude can't start (uv_cwd). We want a combo where
                // this prints "/root" at exit 0.
                const combos = [
                    { tag: '1 base (NO_SECCOMP=1)',                     env: {} },
                    { tag: '2 seccomp ON (NO_SECCOMP unset)',          env: { PROOT_NO_SECCOMP: '' } },
                    { tag: '3 seccomp ON + ASSUME_NEW=1',              env: { PROOT_NO_SECCOMP: '', PROOT_ASSUME_NEW_SECCOMP: '1' } },
                    { tag: '4 NO_SECCOMP=1 + FORCE_KOMPAT=1',          env: { PROOT_FORCE_KOMPAT: '1' } },
                    { tag: '5 seccomp ON + FORCE_KOMPAT=1',            env: { PROOT_NO_SECCOMP: '', PROOT_FORCE_KOMPAT: '1' } },
                    { tag: '6 NO_SECCOMP=1 + ASSUME_NEW=1',            env: { PROOT_ASSUME_NEW_SECCOMP: '1' } },
                ];
                w('\x1b[2m(build ' + BRIDGE_BUILD + ')\x1b[0m\r\n');
                (async () => {
                    let combined = '', winner = '';
                    for (const c of combos) {
                        w('\x1b[33m!fix-seccomp: ' + c.tag + ' …\x1b[0m\r\n');
                        let r;
                        try { r = await runProotGuest(['/bin/sh', '-c', 'cd /root && /bin/pwd'], 25000, null, { verbose: 1, extraEnv: c.env }); }
                        catch (e) { r = { code: 'EXC', out: 'exception: ' + (e && e.message) }; }
                        const ok = r.code === 0 && /\/root/.test(r.out);
                        if (ok && !winner) winner = c.tag;
                        w('  ' + (ok ? '\x1b[32m✓ chdir+getcwd OK → ' + r.out.trim() + '\x1b[0m'
                                     : '\x1b[31m✗ exit=' + r.code + ' ' + r.out.trim().slice(0, 80) + '\x1b[0m') + '\r\n');
                        combined += '\n\n===== COMBO ' + c.tag + ' (exit=' + r.code + ', ok=' + ok +
                                    ') env=' + JSON.stringify(c.env) + ' =====\n' + r.out.trim();
                    }
                    w(winner ? '\x1b[32m🎯 WINNER: ' + winner + '\x1b[0m\r\n'
                             : '\x1b[31m✗ no combo worked — see trace\x1b[0m\r\n');
                    const url = await uploadDiag('=== !fix-seccomp sweep (winner: ' + (winner || 'NONE') + ') ===' + combined);
                    if (url) w('\x1b[36m📋 full trace: ' + url + '\x1b[0m\r\n');
                })();
                continue;
            }

            // ── !test-loader — one-shot loader-hang triage (3 probes, 1 URL) ──
            // The v1.3.4 trace showed proot reaches guest launch then hangs in the
            // loader injection. This runs the 3 isolating probes back-to-back at
            // -v9 and uploads ONE combined trace, so the user types a single
            // command and pastes a single URL:
            //   A  env-wrapped cat   (the real engine path)
            //   B  raw cat           (no /usr/bin/env -i → isolates double-exec)
            //   C  raw /bin/true     (simplest guest → isolates loader vs program)
            if (line.startsWith('!test-loader')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const rp = path.join(FILES_DIR, 'ubuntu');
                if (!fs.existsSync(path.join(rp, 'etc', 'os-release'))) {
                    w('\x1b[31m✗ !test-loader: no rootfs — extract first (Settings → 🐞 Ubuntu engine).\x1b[0m\r\n');
                    continue;
                }
                (async () => {
                    const probes = [
                        { tag: 'A env-wrapped cat', cmd: ['/usr/bin/cat', '/etc/os-release'], opts: { verbose: 9 }, t: 30000 },
                        { tag: 'B raw cat',         cmd: ['/bin/cat', '/etc/os-release'],     opts: { verbose: 9, rawExec: true }, t: 30000 },
                        { tag: 'C raw /bin/true',   cmd: ['/bin/true'],                       opts: { verbose: 9, rawExec: true }, t: 30000 },
                    ];
                    let combined = '';
                    for (const p of probes) {
                        w('\x1b[33m!test-loader: ' + p.tag + ' (≤' + (p.t/1000) + 's)…\x1b[0m\r\n');
                        let r;
                        try { r = await runProotGuest(p.cmd, p.t, null, p.opts); }
                        catch (e) { r = { code: 'EXC', out: 'exception: ' + (e && e.message) }; }
                        const hung = /\[timeout/.test(r.out);
                        w('  ' + (r.code === 0 ? '\x1b[32m✓ exit 0\x1b[0m'
                                : hung ? '\x1b[31m✗ HUNG\x1b[0m'
                                : '\x1b[31m✗ exit=' + r.code + '\x1b[0m') + '\r\n');
                        combined += '\n\n========== PROBE ' + p.tag + ' (exit=' + r.code +
                                    ') ==========\n' + r.out.trim();
                    }
                    const url = await uploadDiag('=== !test-loader (3 probes @ -v9) ===' + combined);
                    if (url) w('\x1b[36m📋 full trace (all 3): ' + url + '\x1b[0m\r\n');
                    else     w('\x1b[31m(trace upload failed — paste site unreachable)\x1b[0m\r\n');
                })();
                continue;
            }

            // ── !defer — proxy-side tool deferral toggle (lazy-load for OAI) ──
            // Writes filesDir/defer_tools. Read fresh per proxy request. OAI
            // providers only (Anthropic passthrough gets native search free).
            // PHASE 1: deferred tools are dropped (kept = core ∪ history-used);
            // PHASE 2 will add on-demand discovery via a tool_search round-trip.
            if (line.startsWith('!defer')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const arg = line.slice('!defer'.length).trim().toLowerCase();
                if (arg === 'on' || arg === 'off') {
                    try { fs.writeFileSync(DEFER_FILE, arg); } catch(_) {}
                    w('\x1b[32m✓ tool deferral → ' + arg + '\x1b[0m\r\n' +
                      '\x1b[2m' + (arg === 'on'
                        ? 'Phase 1: non-core tools dropped (kept: core + tools used this session). Watch [proxy] deferred/size in !log.'
                        : 'All tools sent every turn (default).') +
                      ' Takes effect on your next message.\x1b[0m\r\n');
                } else {
                    w('\x1b[33mtool deferral = ' + (getDeferTools() ? 'on' : 'off') + '\x1b[0m\r\n' +
                      '\x1b[2mUsage: !defer on | !defer off   core=' + Array.from(CORE_TOOLS).join(',') + '\x1b[0m\r\n');
                }
                continue;
            }

            // ── !warm — persistent-session toggle (one warm proc per tab) ────
            // Writes filesDir/warm_session. ON = reuse ONE `claude --print
            // --input-format stream-json` per tab, fed NDJSON over a kept-open
            // stdin → no per-message cold start (b47 probe: 32s→1.5s). Respawns on
            // !clear / model change / config change / crash. OFF = fresh spawn per
            // message (the proven default). Pre-gate-safe: runs while idle only
            // (a toggle mid-turn would race the active proc), so leave it post-gate.
            if (line.startsWith('!warm')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const arg = line.slice('!warm'.length).trim().toLowerCase();
                if (arg === 'on' || arg === 'off') {
                    try { fs.writeFileSync(WARM_FILE, arg); } catch(_) {}
                    // Turning OFF: drop any live warm proc so the next message cold-spawns.
                    if (arg === 'off') killWarmProc(state, '!warm off');
                    w('\x1b[32m✓ warm session → ' + arg + '\x1b[0m\r\n' +
                      '\x1b[2m' + (arg === 'on'
                        ? 'One persistent claude per tab (default) — 1st message still cold (~30s), then warm (~1-2s). Respawns on !clear / model change.'
                        : 'Opted out — fresh spawn per message (cold every time).') +
                      ' Takes effect on your next message.\x1b[0m\r\n');
                } else {
                    w('\x1b[33mwarm session = ' + (getWarmMode() ? 'on' : 'off') +
                      (state.warmProc ? ' (proc alive)' : '') + '\x1b[0m\r\n' +
                      '\x1b[2mUsage: !warm on | !warm off\x1b[0m\r\n');
                }
                continue;
            }

            // ── !setup-engine — batched P1 bring-up (one build, many stages) ─
            // Runs the whole Ubuntu-engine acceptance chain inside the rootfs and
            // reports each stage, so we don't burn a CI build per check:
            //   1 proot boots the rootfs (os-release)
            //   2 write test (/root + /tmp writable in guest)
            //   3 Node 22: reuse if present, else download .tar.gz + extract to /opt/node
            //   4 npm --version
            //   5 npm i -g @anthropic-ai/claude-code  (network via shared netns)
            //   6 claude --version  ← full P1 acceptance
            // Idempotent: re-running skips node download if /opt/node already works.
            if (line.startsWith('!setup-engine')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G = '\x1b[32m', R = '\x1b[31m', Y = '\x1b[33m', D = '\x1b[2m', X = '\x1b[0m';
                // !setup-engine [ENV=K=V…] — same proot env overrides as !test-rootfs,
                // so a diagnostic seccomp combo can be applied to the WHOLE install
                // chain WITHOUT a rebuild. Threaded into runEngineSetup's guest calls.
                const seEnv = {};
                for (const t of line.slice('!setup-engine'.length).trim().split(/\s+/).filter(Boolean)) {
                    if (t.startsWith('ENV=')) { const kv = t.slice(4); const i = kv.indexOf('='); if (i > 0) seEnv[kv.slice(0, i)] = kv.slice(i + 1); }
                }
                if (Object.keys(seEnv).length) w(D + 'env: ' + Object.entries(seEnv).map(([k, v]) => k + '=' + v).join(' ') + X + '\r\n');
                w(D + '(build ' + BRIDGE_BUILD + ')' + X + '\r\n');
                // Run the SHARED install chain; map its progress events to the terminal's
                // colored ✓/✗/» output (the auto-provisioner maps the same events to
                // setup.log instead). Identical steps either way.
                const emit = ({ level, msg, stage, pct }) => {
                    if (level === 'ok')        w(G + '✓ ' + msg + X + '\r\n');
                    else if (level === 'done') w(G + '✅ ' + msg + X + '\r\n' + G + '   latest claude-code runs on glibc via proot.' + X + '\r\n');
                    else if (level === 'err')  w(R + '✗ ' + msg + X + '\r\n');
                    else if (level === 'warn') w(Y + '⚠ ' + msg + X + '\r\n');
                    else if (level === 'stage') w(Y + '» ' + msg + (pct != null ? D + '  (' + pct + '%)' + X : '') + X + '\r\n');
                    else                       w(D + '  ' + msg + X + '\r\n');
                };
                runEngineSetup(emit, seEnv).catch(e => w(R + '[!setup-engine error] ' + (e && e.message) + X + '\r\n'));
                continue;
            }

            // ── !claude-version / !update-claude — light engine update (no re-provision) ─
            // !setup-engine runs all 6 stages (boot/write/node/npm/install/verify). Once
            // the rootfs + Node + npm exist, updating claude-code only needs the last two,
            // so these skip stages 1-4 and just talk to the already-installed npm:
            //   !claude-version        → installed (claude --version) + latest (npm view); no change
            //   !update-claude         → update to @latest IF newer, re-verify, persist version
            //   !update-claude force   → reinstall @latest even if already current
            // Requires a prior !setup-engine (needs /opt/node + npm). Hotloadable (bridge.js).
            // (These are Nexus-chat `!` commands; in the 🐧 Ubuntu PTY tab run the raw
            //  `npm i -g @anthropic-ai/claude-code@latest` / `claude --version` instead.)
            if (line.startsWith('!claude-ver') || line.startsWith('!update-claude') || line.startsWith('!claude-update')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G = '\x1b[32m', R = '\x1b[31m', Y = '\x1b[33m', D = '\x1b[2m', X = '\x1b[0m';
                const isCheckOnly = line.startsWith('!claude-ver');
                const force = /\bforce\b/.test(line);
                const rp = path.join(FILES_DIR, 'ubuntu');
                if (!fs.existsSync(path.join(rp, 'etc', 'os-release'))) {
                    w(R + '✗ no Ubuntu rootfs — run !setup-engine first.' + X + '\r\n'); continue;
                }
                const verRe = /[0-9]+\.[0-9]+\.[0-9]+/;
                (async () => {
                    w(Y + '» checking installed version…' + X + '\r\n');
                    let r = await runProotGuest(['/bin/sh', '-c', 'claude --version 2>&1'], 60000);
                    const cur = (String(r.out || '').match(verRe) || [])[0] || null;
                    if (cur) w(G + '✓ installed: ' + cur + X + '\r\n');
                    else     w(Y + '⚠ claude not installed yet — run !setup-engine.' + X + '\r\n');

                    w(Y + '» checking latest on npm…' + X + '\r\n');
                    r = await runProotGuest(['/bin/sh', '-c', 'npm view @anthropic-ai/claude-code version 2>&1'], 60000);
                    const latest = (String(r.out || '').match(verRe) || [])[0] || null;
                    if (!latest) { w(R + '✗ could not read latest from npm (offline?):' + X + '\r\n' + D + String(r.out || '').trim().slice(-300) + X + '\r\n'); return; }
                    w(G + '✓ latest on npm: ' + latest + X + '\r\n');

                    const upToDate = cur && cur === latest;
                    if (isCheckOnly) {
                        w(upToDate ? G + '✓ up to date.' + X + '\r\n'
                                   : Y + '⇪ update available: ' + (cur || '(none)') + ' → ' + latest + '   run \x1b[1m!update-claude\x1b[0m' + Y + ' to install.' + X + '\r\n');
                        return;
                    }
                    if (upToDate && !force) { w(G + '✓ already on the latest (' + latest + '). Use \x1b[1m!update-claude force\x1b[0m' + G + ' to reinstall.' + X + '\r\n'); return; }

                    w(Y + '» installing @latest… (a minute or two)' + X + '\r\n');
                    let lastMark = Date.now();
                    r = await runProotGuest(['/bin/sh', '-c', 'npm i -g @anthropic-ai/claude-code@latest 2>&1'], 600000,
                        () => { const now = Date.now(); if (now - lastMark > 15000) { lastMark = now; w(D + '  …still installing…' + X + '\r\n'); } });
                    if (r.code !== 0) { w(R + '✗ install failed (code=' + r.code + ')' + X + '\r\n' + D + String(r.out || '').trim().slice(-500) + X + '\r\n'); return; }

                    r = await runProotGuest(['/bin/sh', '-c', 'claude --version 2>&1'], 60000);
                    const now = (String(r.out || '').match(verRe) || [])[0];
                    if (!now) { w(R + '✗ installed but claude --version failed' + X + '\r\n'); return; }
                    try { fs.writeFileSync(path.join(FILES_DIR, 'claude_version'), now); } catch (_) {}
                    w(G + '✅ updated ' + (cur ? cur + ' →' : 'to') + ' ' + now + X + '\r\n');
                })().catch(e => w(R + '[update-claude error] ' + (e && e.message) + X + '\r\n'));
                continue;
            }

            // ── !cleanup — reclaim install caches without re-provisioning ────
            // npm/apt keep their downloaded package copies after install (npm
            // ~/.npm/_cacache, apt /var/cache + lists) + leftover .tar.* — ~400MB of
            // dead weight on a phone. Dropping them is safe (a re-install re-downloads).
            if (line.startsWith('!cleanup')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const rp = path.join(FILES_DIR, 'ubuntu');
                if (!fs.existsSync(path.join(rp, 'etc', 'os-release'))) {
                    w('\x1b[31m✗ no Ubuntu rootfs — nothing to clean\x1b[0m\r\n'); continue;
                }
                w('\x1b[33m[cleanup] freeing npm + apt caches + leftover tarballs… (~30s)\x1b[0m\r\n');
                // HOST-side: the Ubuntu rootfs .tar.xz lives in the app cacheDir (a
                // sibling of FILES_DIR, OUTSIDE the guest, so the guest cleanup below
                // can't reach it) + any stray node tarball in FILES_DIR. Delete here.
                try {
                    const hostJunk = [
                        path.join(FILES_DIR, '..', 'cache', 'ubuntu-rootfs.tar.xz'),
                        path.join(FILES_DIR, 'node22.tar'),
                    ];
                    let hostFreed = 0;
                    for (const f of hostJunk) {
                        try { const st = fs.statSync(f); fs.unlinkSync(f); hostFreed += st.size; } catch(_) {}
                    }
                    if (hostFreed) w('\x1b[2mhost tarballs: freed ' + (hostFreed / 1048576 | 0) + 'M\x1b[0m\r\n');
                } catch(_) {}
                (async () => {
                    const script =
                        'echo "freeing (current sizes):"; ' +
                        'du -sh /root/.npm /var/cache/apt /var/lib/apt/lists 2>/dev/null; ' +
                        'npm cache clean --force >/dev/null 2>&1 || true; ' +
                        'apt-get clean >/dev/null 2>&1 || true; ' +
                        'rm -rf /var/lib/apt/lists/* /tmp/*.tar.* /opt/*.tar.* 2>/dev/null || true; ' +
                        'echo "biggest dirs:"; du -sh /opt /usr /root /var 2>/dev/null | sort -rh | head -6';
                    const r = await runProotGuest(['/bin/sh', '-c', script], 180000);
                    w('\x1b[2m' + ((r.out || '').trim() || '(no output)') + '\x1b[0m\r\n');
                    w(r.code === 0 ? '\x1b[32m✓ cleanup done. The rest is the real engine (rootfs+node+claude), not junk.\x1b[0m\r\n'
                                   : '\x1b[31m✗ cleanup exit ' + r.code + '\x1b[0m\r\n');
                })().catch(e => w('\x1b[31m[cleanup error] ' + e.message + '\x1b[0m\r\n'));
                continue;
            }

            // ── !test-agent — end-to-end sub-agent dispatch probe ────────────
            // Writes a throwaway agent definition to ~/.claude/agents/, asks
            // claude to invoke it via the Task tool, and reports whether:
            //   (a) Task tool fired (event with type=tool_use, name=Agent or Task)
            //   (b) Sub-agent returned a unique tag we embedded in its prompt
            // 90s timeout — sub-agents dispatch a child claude session, which
            // means a second API call cycle on top of the parent's.
            if (line.startsWith('!test-agent')) {
                // ── PROOT branch — re-probe FILE-based custom-agent discovery on
                // claude-code 2.1.160 (the feat/custom-agents premise). On 2.1.112
                // print mode this returned "Agent type 'nexus_probe' not found.
                // Available agents: Explore, Plan, …" — a hard version limit (Known
                // gaps / [[project-subagent-status]]). We write a REAL agent .md into
                // the guest's /root/.claude/agents/ (via the FILES_DIR/.claude bind),
                // NOT the --agents flag, because file discovery is exactly what we're
                // testing. If 2.1.160 finds it → branch unblocked.
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G='\x1b[32m', Y='\x1b[33m', R='\x1b[31m', D='\x1b[2m', X='\x1b[0m';
                const pTag = 'NEXUS_AGENT_OK_' + Date.now().toString(36);
                const pAgentsDir = path.join(FILES_DIR, '.claude', 'agents');
                const pAgentFile = path.join(pAgentsDir, 'nexus_probe.md');
                const pSentinelHost  = path.join(FILES_DIR, '.claude', 'nexus_probe_result.txt');
                const pSentinelGuest = '/root/.claude/nexus_probe_result.txt';
                try { fs.unlinkSync(pSentinelHost); } catch(_) {}
                const pCleanup = () => {
                    try { fs.unlinkSync(pAgentFile); } catch(_) {}
                    try { fs.unlinkSync(pSentinelHost); } catch(_) {}
                };
                try {
                    fs.mkdirSync(pAgentsDir, { recursive: true });
                    // Valid claude-code agent definition (YAML frontmatter + body).
                    fs.writeFileSync(pAgentFile,
                        '---\n' +
                        'name: nexus_probe\n' +
                        'description: Internal connectivity probe — confirms FILE-based custom sub-agent discovery on 2.1.160.\n' +
                        'tools: Write\n' +
                        '---\n' +
                        'You are a diagnostic sub-agent. When invoked, do exactly these two steps and nothing else:\n' +
                        '1. Use the Write tool to create the file ' + pSentinelGuest + ' whose entire contents are exactly: ' + pTag + '\n' +
                        '2. Reply with exactly this string and nothing else: ' + pTag + '\n');
                } catch (e) {
                    w(R + '✗ !test-agent (proot): could not write agent file: ' + e.message + X + '\r\n');
                    continue;
                }
                const pTestText = 'Use the Task tool (it may also be named Agent) to dispatch a sub-agent with subagent_type "nexus_probe". ' +
                    'Do NOT use the Bash tool and do NOT run any shell command — "nexus_probe" is a sub-agent, not an executable. ' +
                    'After the sub-agent replies, tell me the exact string it returned.';
                // Same guest env/argv as the runMessage proot branch (IS_SANDBOX=1 +
                // skip-permissions so the sub-agent's Write isn't permission-gated).
                const pBenv = buildEnv();
                try { patchSettings(readConfig()); } catch(_) {}
                const pGuestEnv = {
                    ANTHROPIC_API_KEY:   pBenv.ANTHROPIC_API_KEY,
                    ANTHROPIC_MODEL:     pBenv.ANTHROPIC_MODEL,
                    DISABLE_AUTOUPDATER: '1',
                    SHELL:               '/bin/bash',
                    IS_SANDBOX:          '1',
                };
                if (pBenv.ANTHROPIC_BASE_URL) pGuestEnv.ANTHROPIC_BASE_URL = pBenv.ANTHROPIC_BASE_URL;
                const pArgv = [GUEST_CLAUDE, '--output-format', 'stream-json', '--print',
                               '--dangerously-skip-permissions', '--verbose', pTestText];
                if (getDeferTools()) w(Y + '⚠ tool deferral is ON — Agent/Task is in the defer catalog, so it gets stripped from the request and this probe will read a false "Task did not fire". Run !defer off first.' + X + '\r\n');
                w(Y + '!test-agent (proot/2.1.160): wrote ~/.claude/agents/nexus_probe.md, dispatching (120s)…' + X + '\r\n');
                runProotGuest(pArgv, 120000, null, { extraEnv: pGuestEnv, workspace: FILES_DIR })
                  .then(r => {
                    const out = r.out || '';
                    const fileTag   = (() => { try { return fs.readFileSync(pSentinelHost,'utf8').includes(pTag); } catch(_) { return false; } })();
                    const taskFired = /"type"\s*:\s*"tool_use"[^}]*"name"\s*:\s*"(Task|Agent)"/.test(out);
                    const notFound  = /not found\.?\s*Available agents/i.test(out) || /Agent type ['"]?nexus_probe['"]? not found/i.test(out);
                    const tagSeen   = out.includes(pTag);
                    const gotResult = out.includes('"type":"result"');
                    const ran       = tagSeen || fileTag;
                    // The headline verdict: was the FILE-defined agent discovered?
                    const discovered = taskFired && !notFound && (ran || gotResult);
                    pCleanup();
                    const mark = discovered ? (G+'✓') : (taskFired ? (Y+'~') : (R+'✗'));
                    let rep = mark + ' !test-agent (proot) exit=' + r.code + X + '\r\n';
                    rep += '  Custom agent discovered: ' + (discovered ? G+'YES — 2.1.160 loads ~/.claude/agents/*.md! Branch unblocked.'+X
                            : notFound ? R+'NO — still "not found" (version limit persists on 2.1.160)'+X
                            : Y+'inconclusive (Task did not fire — model ignored the tool)'+X) + '\r\n';
                    rep += '  Task tool fired:    ' + (taskFired ? G+'yes'+X : R+'no'+X) + '\r\n';
                    rep += '  "not found" error:  ' + (notFound ? R+'yes'+X : G+'no'+X) + '\r\n';
                    rep += '  Sub-agent ran:      ' + (ran ? G+'yes'+(fileTag?' (sentinel file)':' (stdout tag)')+X : D+'no'+X) + '\r\n';
                    rep += '  Got final result:   ' + (gotResult ? G+'yes'+X : R+'no'+X) + '\r\n';
                    if (!discovered) rep += D+'stdout (last 500): ' + out.slice(-500).replace(/\r?\n/g,' ') + X + '\r\n';
                    w(rep);
                  })
                  .catch(e => { pCleanup(); w(R + '[!test-agent proot error] ' + (e && e.message) + X + '\r\n'); });
                continue;
            }

            // ── !test-slash — re-probe custom slash-command loading on 2.1.160 ──
            // Invariant re-audit probe #2 (②). On 2.1.112 print mode, custom
            // ~/.claude/commands/*.md were NOT expanded — claude passed "/foo"
            // through as literal prompt text (Known gaps). Re-test on proot/2.1.160:
            // write a real command file into the guest /root/.claude/commands/ (via
            // the FILES_DIR/.claude bind) whose body asks the model to emit a unique
            // tag, then send "/nexustest". If 2.1.160 expands it, the tag comes back;
            // if not, the model treats "/nexustest" as a literal path.
            if (line.startsWith('!test-slash')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G='\x1b[32m', Y='\x1b[33m', R='\x1b[31m', D='\x1b[2m', X='\x1b[0m';
                const sTag = 'NEXUS_SLASH_OK_' + Date.now().toString(36);
                const sCmdDir  = path.join(FILES_DIR, '.claude', 'commands');
                const sCmdFile = path.join(sCmdDir, 'nexustest.md');
                const sCleanup = () => { try { fs.unlinkSync(sCmdFile); } catch(_) {} };
                try {
                    fs.mkdirSync(sCmdDir, { recursive: true });
                    fs.writeFileSync(sCmdFile,
                        'Reply with exactly this string and nothing else, no punctuation: ' + sTag + '\n');
                } catch (e) {
                    w(R + '✗ !test-slash: could not write command file: ' + e.message + X + '\r\n');
                    continue;
                }
                const sBenv = buildEnv();
                try { patchSettings(readConfig()); } catch(_) {}
                const sGuestEnv = {
                    ANTHROPIC_API_KEY:   sBenv.ANTHROPIC_API_KEY,
                    ANTHROPIC_MODEL:     sBenv.ANTHROPIC_MODEL,
                    DISABLE_AUTOUPDATER: '1',
                    SHELL:               '/bin/bash',
                    IS_SANDBOX:          '1',
                };
                if (sBenv.ANTHROPIC_BASE_URL) sGuestEnv.ANTHROPIC_BASE_URL = sBenv.ANTHROPIC_BASE_URL;
                const sArgv = [GUEST_CLAUDE, '--output-format', 'stream-json', '--print',
                               '--dangerously-skip-permissions', '--verbose', '/nexustest'];
                w(Y + '!test-slash (proot/2.1.160): wrote ~/.claude/commands/nexustest.md, sending "/nexustest" (90s)…' + X + '\r\n');
                runProotGuest(sArgv, 90000, null, { extraEnv: sGuestEnv, workspace: FILES_DIR })
                  .then(r => {
                    const out = r.out || '';
                    const tagSeen   = out.includes(sTag);
                    // Literal pass-through signals: claude treated /nexustest as a path/cmd.
                    const literal   = /\/nexustest/.test(out) && !tagSeen;
                    const gotResult = out.includes('"type":"result"');
                    sCleanup();
                    const expanded  = tagSeen;
                    const mark = expanded ? (G+'✓') : (R+'✗');
                    let rep = mark + ' !test-slash (proot) exit=' + r.code + X + '\r\n';
                    rep += '  Slash command expanded: ' + (expanded
                            ? G+'YES — 2.1.160 loads ~/.claude/commands/*.md! (was a 2.1.112 limit)'+X
                            : literal ? R+'NO — "/nexustest" passed through as literal text (limit persists)'+X
                            : Y+'inconclusive (tag absent, no literal echo — check stdout below)'+X) + '\r\n';
                    rep += '  Tag returned:       ' + (tagSeen ? G+'yes'+X : R+'no'+X) + '\r\n';
                    rep += '  Got final result:   ' + (gotResult ? G+'yes'+X : R+'no'+X) + '\r\n';
                    if (!expanded) rep += D+'stdout (last 500): ' + out.slice(-500).replace(/\r?\n/g,' ') + X + '\r\n';
                    w(rep);
                  })
                  .catch(e => { sCleanup(); w(R + '[!test-slash proot error] ' + (e && e.message) + X + '\r\n'); });
                continue;
            }

            // ── !test-append — re-probe --append-system-prompt on 2.1.160 ──────
            // Invariant re-audit probe #4 (①). On Bionic/2.1.112 this flag HUNG
            // every spawn (inv 5a): claude reached the HEAD/health-check then never
            // POSTed /v1/messages → all spawns exit 143 after the 180s timeout. That
            // hang is the sole reason custom personas are PREPENDED to the message
            // text instead. Re-test on glibc/2.1.160: spawn the guest with
            // --append-system-prompt and a trivial message; if it returns promptly
            // (not a timeout) the flag works → drop the prepend hack on the proot path.
            if (line.startsWith('!test-append')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G='\x1b[32m', Y='\x1b[33m', R='\x1b[31m', D='\x1b[2m', X='\x1b[0m';
                const aBenv = buildEnv();
                try { patchSettings(readConfig()); } catch(_) {}
                const aGuestEnv = {
                    ANTHROPIC_API_KEY:   aBenv.ANTHROPIC_API_KEY,
                    ANTHROPIC_MODEL:     aBenv.ANTHROPIC_MODEL,
                    DISABLE_AUTOUPDATER: '1',
                    SHELL:               '/bin/bash',
                    IS_SANDBOX:          '1',
                };
                if (aBenv.ANTHROPIC_BASE_URL) aGuestEnv.ANTHROPIC_BASE_URL = aBenv.ANTHROPIC_BASE_URL;
                const sysInject = 'You must end every single reply with the exact word BANANA on its own.';
                const aArgv = [GUEST_CLAUDE, '--output-format', 'stream-json', '--print',
                               '--dangerously-skip-permissions',
                               '--append-system-prompt', sysInject,
                               '--verbose', 'Say hello in one short sentence.'];
                const t0 = Date.now();
                w(Y + '!test-append (proot/2.1.160): spawning with --append-system-prompt (90s; Bionic hung here → 180s timeout)…' + X + '\r\n');
                runProotGuest(aArgv, 90000, null, { extraEnv: aGuestEnv, workspace: FILES_DIR })
                  .then(r => {
                    const out = r.out || '';
                    const ms = Date.now() - t0;
                    const timedOut  = /\[timeout \d+ms\]/.test(out) || r.code === null;
                    const gotResult = out.includes('"type":"result"');
                    const bananaSeen= /BANANA/.test(out);
                    const flagRejected = /unknown option[^\n]*append-system-prompt/i.test(out);
                    const works = !timedOut && gotResult && !flagRejected;
                    const mark = works ? (G+'✓') : (R+'✗');
                    let rep = mark + ' !test-append (proot) exit=' + r.code + ' in ' + ms + 'ms' + X + '\r\n';
                    rep += '  --append-system-prompt: ' + (flagRejected ? R+'REJECTED (unknown option)'+X
                            : works ? G+'WORKS — no hang. Drop the prepend-persona hack on proot.'+X
                            : timedOut ? R+'HANG (timed out, same as Bionic — keep prepend hack)'+X
                            : Y+'inconclusive (no result, not a clean timeout)'+X) + '\r\n';
                    rep += '  Completed (no hang): ' + (!timedOut ? G+'yes'+X : R+'no — TIMED OUT'+X) + '\r\n';
                    rep += '  Got final result:   ' + (gotResult ? G+'yes'+X : R+'no'+X) + '\r\n';
                    rep += '  System prompt honored (BANANA): ' + (bananaSeen ? G+'yes'+X : D+'no (flag may still work; weak model)'+X) + '\r\n';
                    if (!works) rep += D+'stdout (last 500): ' + out.slice(-500).replace(/\r?\n/g,' ') + X + '\r\n';
                    w(rep);
                  })
                  .catch(e => { w(R + '[!test-append proot error] ' + (e && e.message) + X + '\r\n'); });
                continue;
            }

            // ── !scout-pack — DungeonPRD P0: does Repomix run on the proot guest? ──
            // Repomix (npx repomix --compress) is the proposed "Cartographer" pre-pass
            // for Scouts/Council: the BRIDGE packs a room's folder into one compact,
            // Tree-sitter-compressed map (~70% fewer tokens) BEFORE any model spawns, so
            // a Scout reads one artifact instead of crawling the tree (kills the inv-58
            // discovery/TPM friction). This probe is the gate for that whole feature:
            // build a tiny throwaway src tree in the guest, `npx -y repomix --compress`
            // it, and confirm (a) node/npx are present, (b) repomix installs+runs under
            // proot, (c) it emits a parseable Total Tokens count (the pre-flight budget
            // signal), (d) a non-empty map file is produced. First run downloads repomix
            // from npm → generous 240s timeout + needs network (same path that npm-installs
            // claude-code, so it should work). No model call, no provider — pure tooling.
            if (line.startsWith('!scout-pack')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G='\x1b[32m', Y='\x1b[33m', R='\x1b[31m', D='\x1b[2m', X='\x1b[0m';
                const pbCmd = [
                    'set +e',
                    'echo "::node:: $(node --version 2>&1 | head -1)"',
                    'echo "::npx:: $(npx --version 2>&1 | head -1)"',
                    'D=/tmp/scout_probe; rm -rf "$D"; mkdir -p "$D/sub"',
                    'printf "export function add(a,b){ return a+b }\\n// helper note\\nexport const PI = 3.14\\n" > "$D/a.js"',
                    'printf "class Foo {\\n  bar(){ return 1 }\\n  baz(x){ return x * 2 }\\n}\\n" > "$D/sub/b.js"',
                    'cd "$D"',
                    'echo "::run:: invoking repomix (first run downloads from npm)"',
                    'npx -y repomix --compress --style xml -o /tmp/scout_map.xml . 2>&1 | tail -40',
                    'echo "::mapsize:: $(wc -c < /tmp/scout_map.xml 2>/dev/null || echo 0)"',
                ].join('\n');
                const t0 = Date.now();
                w(Y + '!scout-pack (DungeonPRD P0): probing Repomix on the guest — first run npm-installs it, up to 240s…' + X + '\r\n');
                runProotGuest(['/bin/bash','-lc', pbCmd], 240000, null, { workspace: FILES_DIR })
                  .then(r => {
                    const out = r.out || '';
                    const ms = Date.now() - t0;
                    const nodeV   = (out.match(/::node:: (v[\d.]+)/) || [])[1] || '';
                    const npxV    = (out.match(/::npx:: ([\d.]+)/) || [])[1] || '';
                    const tokens  = (out.match(/Total Tokens:?\s*([\d,]+)/i) || [])[1] || '';
                    const files   = (out.match(/Total Files:?\s*(\d+)/i) || [])[1] || '';
                    const mapSize = parseInt((out.match(/::mapsize:: (\d+)/) || [])[1] || '0', 10);
                    const timedOut= /\[timeout \d+ms\]/.test(out) || r.code === null;
                    const ran     = mapSize > 0 || !!tokens;
                    const works   = !timedOut && !!nodeV && ran;
                    const mark = works ? (G+'✓') : (R+'✗');
                    let rep = mark + ' !scout-pack (proot) exit=' + r.code + ' in ' + ms + 'ms' + X + '\r\n';
                    rep += '  node present:    ' + (nodeV ? G+nodeV+X : R+'no — guest node missing!'+X) + '\r\n';
                    rep += '  npx present:     ' + (npxV ? G+npxV+X : R+'no'+X) + '\r\n';
                    rep += '  repomix ran:     ' + (ran ? G+'yes'+X : (timedOut ? R+'no — TIMED OUT (npm fetch failed? no network?)'+X : R+'no'+X)) + '\r\n';
                    rep += '  Total Tokens:    ' + (tokens ? G+tokens+X+D+' (the pre-flight budget signal — parseable ✓)'+X : Y+'not found in output'+X) + '\r\n';
                    rep += '  Total Files:     ' + (files ? G+files+X : D+'—'+X) + '\r\n';
                    rep += '  compressed map:  ' + (mapSize > 0 ? G+mapSize+' bytes'+X : R+'0 bytes — no file produced'+X) + '\r\n';
                    rep += '  verdict: ' + (works ? G+'GREEN — Repomix works on guest. P1 (packRoom pre-pass) is unblocked.'+X
                                          : timedOut ? R+'TIMED OUT — first-run npm fetch too slow or no network. Re-run on wifi.'+X
                                          : R+'BLOCKED — see output below. P1 needs another packer (bundled rg/semgrep) or apt-install repomix.'+X) + '\r\n';
                    if (!works) rep += D+'stdout (last 700): ' + out.slice(-700).replace(/\r?\n/g,' ') + X + '\r\n';
                    w(rep);
                  })
                  .catch(e => { w(R + '[!scout-pack proot error] ' + (e && e.message) + X + '\r\n'); });
                continue;
            }

            // ── !scout-hounds — DungeonPRD P3: do deterministic scanners run on the guest? ──
            // P3 "Hounds" = run rg/semgrep (or plain grep as the floor) over a room BEFORE
            // any model spawns, turning obvious issues into ZERO-TOKEN monsters. This is the
            // strongest weak-model lever (see feedback-help-weak-models): even a model that
            // audits nothing still gets the easy bugs caught for it, and it patches the
            // dead-Grep gap. This probe gates the feature: confirm (a) which scanners exist
            // on the proot guest, (b) rg is apt-installable if missing, (c) grep is always
            // there as the floor, (d) the chosen scanner emits parseable file:line hits on a
            // planted-bug tree. No model, no provider — pure tooling.
            if (line.startsWith('!scout-hounds')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G='\x1b[32m', Y='\x1b[33m', R='\x1b[31m', D='\x1b[2m', X='\x1b[0m';
                const hbCmd = [
                    'set +e',
                    'echo "::rg:: $(rg --version 2>/dev/null | head -1)"',
                    'echo "::semgrep:: $(semgrep --version 2>/dev/null | head -1)"',
                    'echo "::grep:: $(grep --version 2>/dev/null | head -1)"',
                    'if ! command -v rg >/dev/null 2>&1; then echo "::rg-install:: apt-get install ripgrep"; apt-get install -y ripgrep >/dev/null 2>&1; echo "::rg-after:: $(rg --version 2>/dev/null | head -1)"; fi',
                    'D=/tmp/hound_probe; rm -rf "$D"; mkdir -p "$D"',
                    'printf "const password = \\"hunter2\\"\\neval(userInput)\\n// TODO: fix this\\n" > "$D/bad.js"',
                    'cd "$D"',
                    'SCAN=$(command -v rg >/dev/null 2>&1 && echo rg || echo grep)',
                    'echo "::scanner:: $SCAN"',
                    'echo "::hits-start::"',
                    'if [ "$SCAN" = rg ]; then rg -n --no-heading -e "eval\\(" -e "password|secret|token" -e "TODO|FIXME|HACK" . 2>&1; else grep -rnE "eval\\(|password|secret|token|TODO|FIXME|HACK" . 2>&1; fi',
                    'echo "::hits-end::"',
                ].join('\n');
                const t0 = Date.now();
                w(Y + '!scout-hounds (DungeonPRD P3): probing deterministic scanners on the guest (apt-installs ripgrep if missing, up to 180s)…' + X + '\r\n');
                runProotGuest(['/bin/bash','-lc', hbCmd], 180000, null, { workspace: FILES_DIR })
                  .then(r => {
                    const out = r.out || '';
                    const ms = Date.now() - t0;
                    const rgV     = (out.match(/::rg:: (ripgrep [\d.]+)/) || out.match(/::rg-after:: (ripgrep [\d.]+)/) || [])[1] || '';
                    const semV    = (out.match(/::semgrep:: ([\d.]+)/) || [])[1] || '';
                    const grepV   = (out.match(/::grep:: (grep [^\r\n]+)/) || [])[1] || '';
                    const scanner = (out.match(/::scanner:: (\w+)/) || [])[1] || '';
                    const triedInstall = /::rg-install::/.test(out);
                    const hitsBlock = (out.match(/::hits-start::([\s\S]*?)::hits-end::/) || [])[1] || '';
                    const hits = (hitsBlock.match(/bad\.js:\d+:/g) || []).length;
                    const timedOut= /\[timeout \d+ms\]/.test(out) || r.code === null;
                    const works   = !timedOut && !!scanner && hits >= 1;
                    const mark = works ? (G+'✓') : (R+'✗');
                    let rep = mark + ' !scout-hounds (proot) exit=' + r.code + ' in ' + ms + 'ms' + X + '\r\n';
                    rep += '  ripgrep (rg):    ' + (rgV ? G+rgV+X+(triedInstall?D+' (apt-installed just now)'+X:'') : Y+'not present'+(triedInstall?' — apt install failed':'')+X) + '\r\n';
                    rep += '  semgrep:         ' + (semV ? G+semV+X : D+'not present (optional — pip install semgrep; heavy on phone)'+X) + '\r\n';
                    rep += '  grep (floor):    ' + (grepV ? G+grepV+X : R+'MISSING?!'+X) + '\r\n';
                    rep += '  chosen scanner:  ' + (scanner ? G+scanner+X+D+(scanner==='rg'?' (fast)':' (floor fallback)')+X : R+'none'+X) + '\r\n';
                    rep += '  planted hits:    ' + (hits ? G+hits+'/3 found'+X+D+' (parseable file:line ✓ → zero-token monsters feasible)'+X : R+hits+'/3 — scanner produced no parseable hits'+X) + '\r\n';
                    rep += '  verdict: ' + (works ? G+'GREEN — deterministic hounds work on guest ('+scanner+'). P3 (pre-spawn scan → 0-token monsters) is unblocked.'+X
                                          : timedOut ? R+'TIMED OUT — apt fetch too slow / no network. Re-run on wifi (grep floor needs no install).'+X
                                          : R+'BLOCKED — see output below.'+X) + '\r\n';
                    if (!works) rep += D+'stdout (last 700): ' + out.slice(-700).replace(/\r?\n/g,' ') + X + '\r\n';
                    w(rep);
                  })
                  .catch(e => { w(R + '[!scout-hounds proot error] ' + (e && e.message) + X + '\r\n'); });
                continue;
            }

            // ── !apt-diagnose — diagnose apt/DNS issues in the proot guest ────────
            if (line.startsWith('!apt-diagnose')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G='\x1b[32m', Y='\x1b[33m', R='\x1b[31m', D='\x1b[2m', X='\x1b[0m';
                const adCmd = [
                    'set +e',
                    'echo "::resolv::"',
                    'cat /etc/resolv.conf 2>&1',
                    'echo "::resolv-stat::"',
                    'ls -la /etc/resolv.conf 2>&1',
                    'echo "::apt-lists::"',
                    'ls /var/lib/apt/lists/ 2>&1 | head -5',
                    'echo "::apt-update::"',
                    'apt-get update -qq 2>&1 | tail -5',
                    'echo "::apt-install::"',
                    'apt-get install -y --no-install-recommends rsync 2>&1 | tail -10',
                    'echo "::done::"',
                ].join('\n');
                w(Y + '!apt-diagnose: checking resolv.conf + apt on the guest (up to 120s)…' + X + '\r\n');
                runProotGuest(['/bin/bash','-lc', adCmd], 120000)
                  .then(r => {
                    const out = r.out || '';
                    const resolv = (out.match(/::resolv::\n([\s\S]*?)::resolv-stat::/) || [])[1] || '';
                    const resolvStat = (out.match(/::resolv-stat::\n([\s\S]*?)::apt-lists::/) || [])[1] || '';
                    const aptLists = (out.match(/::apt-lists::\n([\s\S]*?)::apt-update::/) || [])[1] || '';
                    const aptUpdate = (out.match(/::apt-update::\n([\s\S]*?)::apt-install::/) || [])[1] || '';
                    const aptInstall = (out.match(/::apt-install::\n([\s\S]*?)::done::/) || [])[1] || '';
                    const timedOut = /\[timeout \d+ms\]/.test(out) || r.code === null;

                    let rep = '';
                    rep += D+'── resolv.conf ──'+X+'\r\n'+resolv.trim()+'\r\n';
                    rep += D+'── resolv.conf stat ──'+X+'\r\n'+resolvStat.trim()+'\r\n';
                    rep += D+'── apt lists (first 5) ──'+X+'\r\n'+aptLists.trim()+'\r\n';
                    rep += D+'── apt-get update (tail) ──'+X+'\r\n'+aptUpdate.trim()+'\r\n';
                    rep += D+'apt-get install rsync (tail) ──'+X+'\r\n';
                    if (timedOut) rep += R+'TIMED OUT (120s) — network issue or repo unreachable'+X+'\r\n';
                    else rep += aptInstall.trim()+'\r\n';
                    rep += '\r\n' + D+'Quick fix if resolv.conf is broken: run !apt-fix-dns'+X+'\r\n';
                    w(rep);
                  })
                  .catch(e => { w(R + '[!apt-diagnose error] ' + (e && e.message) + X + '\r\n'); });
                continue;
            }

            // ── !apt-fix-dns — force-write resolv.conf + run apt-get update ────
            if (line.startsWith('!apt-fix-dns')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G='\x1b[32m', Y='\x1b[33m', R='\x1b[31m', D='\x1b[2m', X='\x1b[0m';
                const rp = path.join(FILES_DIR, 'ubuntu');
                const resolvPath = path.join(rp, 'etc', 'resolv.conf');
                try {
                    try { fs.unlinkSync(resolvPath); } catch (_) {}
                    fs.writeFileSync(resolvPath, 'nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 216.146.35.35\n');
                    w(G+'resolv.conf rewritten'+X+'\r\n');
                } catch (e) {
                    w(R+'write failed: '+e.message+X+'\r\n');
                    continue;
                }
                // Fix resolv.conf AND the Ubuntu mirror. THREE compounding problems
                // were diagnosed on-device (see /sdcard/design/possibleaptsolution.md +
                // screenshots 2026-06-10):
                //   1. arm64 packages live on ports.ubuntu.com/ubuntu-ports — NOT on
                //      archive.ubuntu.com / old-releases.ubuntu.com/ubuntu (those host
                //      only amd64/i386) → "binary-arm64/Packages 404 Not Found".
                //   2. Some ISPs (e.g. CelcomDigi MY) 302-hijack ALL http:// Ubuntu
                //      mirror traffic to a captive page → "Redirection loop" / no
                //      Release file. Use HTTPS so the ISP can't see the path.
                //   3. The minimal rootfs has no ca-certificates → HTTPS TLS verify
                //      fails ("certificate is NOT trusted"). Chicken-and-egg: disable
                //      TLS peer verify via apt.conf so the FIRST update works, then
                //      install ca-certificates from that update.
                // [trusted=yes] also skips the GPG repo-signature check.
                // 4. CRITICAL: the sources.list SUITE must match the rootfs's actual
                //    Ubuntu release. The rootfs is NOT necessarily jammy — pointing a
                //    newer base (e.g. lunar/mantic, perl-base 5.36) at jammy makes
                //    every install pull jammy-versioned deps that conflict with the
                //    installed newer ones ("perl-base Breaks perl"). So detect
                //    VERSION_CODENAME from /etc/os-release and probe which mirror
                //    serves it: supported releases → ports.ubuntu.com/ubuntu-ports,
                //    EOL ones → old-releases.ubuntu.com/ubuntu (which keeps arm64).
                //    (ca-certificates is NOT installed here — that needs dpkg, which
                //    EPERMs on this old proot; see !dpkg-test / !apt-extract.)
                const fixCmd = [
                    'set +e',
                    '. /etc/os-release 2>/dev/null || true',
                    'CN="${VERSION_CODENAME:-jammy}"',
                    'echo "::release:: ${PRETTY_NAME:-Ubuntu} codename=$CN"',
                    'mkdir -p /etc/apt/apt.conf.d',
                    'cat > /etc/apt/apt.conf.d/99nexus-https << "CFGEOF"\n' +
                    'Acquire::https::Verify-Peer "false";\n' +
                    'Acquire::https::Verify-Host "false";\n' +
                    'CFGEOF',
                    'H=/usr/lib/apt/apt-helper',
                    'MIRROR=""',
                    'for U in "https://ports.ubuntu.com/ubuntu-ports" "https://old-releases.ubuntu.com/ubuntu"; do',
                    '  rm -f /tmp/.nexrel 2>/dev/null',
                    '  if "$H" download-file "$U/dists/$CN/Release" /tmp/.nexrel >/dev/null 2>&1 && [ -s /tmp/.nexrel ]; then MIRROR="$U"; echo "mirror OK: $U"; break; else echo "mirror miss: $U"; fi',
                    'done',
                    'if [ -z "$MIRROR" ]; then echo "::update::"; echo "NO working mirror for codename=$CN"; echo "::update-done::"; exit 3; fi',
                    '{ echo "deb [trusted=yes] $MIRROR $CN main restricted universe multiverse"; echo "deb [trusted=yes] $MIRROR $CN-updates main restricted universe multiverse"; echo "deb [trusted=yes] $MIRROR $CN-security main restricted universe multiverse"; } > /etc/apt/sources.list',
                    'echo "::sources::"; cat /etc/apt/sources.list',
                    'rm -f /var/lib/apt/lists/partial/* 2>/dev/null',
                    'apt-get clean 2>/dev/null',
                    'echo "::update::"',
                    'apt-get update 2>&1 | tail -8; APT_EC=$?',
                    'echo "::update-done::"; exit $APT_EC',
                ].join('\n');
                w(Y+'!apt-fix-dns: detecting release + probing mirror + apt-get update (up to 120s)…'+X+'\r\n');
                runProotGuest(['/bin/bash','-lc', fixCmd], 120000)
                  .then(r => {
                    const out = r.out || '';
                    const timedOut = /\[timeout \d+ms\]/.test(out) || r.code === null;
                    const rel = (out.match(/::release::([^\n]*)/) || [])[1] || '';
                    const src = (out.match(/::sources::\n([\s\S]*?)::update::/) || [])[1] || '';
                    const upd = (out.match(/::update::\n([\s\S]*?)::update-done::/) || [])[1] || '';
                    let rep = '';
                    if (rel) rep += D+'release:'+X+rel+'\r\n';
                    if (src) rep += D+'sources.list:'+X+'\r\n'+src.trim()+'\r\n';
                    rep += D+'apt-get update:'+X+'\r\n'+(upd.trim()||out.trim())+'\r\n';
                    w(rep);
                    if (timedOut) w(R+'TIMED OUT — check network connectivity'+X+'\r\n');
                    else if (r.code === 0) w(G+'✓ apt-get update succeeded for this release — now: !apt-extract <pkg> (real apt install needs a newer proot, see !dpkg-test)'+X+'\r\n');
                    else w(R+'✗ apt-get update exit='+r.code+' (see errors above)'+X+'\r\n');
                  })
                  .catch(e => { w(R+'[!apt-fix-dns error] '+e.message+X+'\r\n'); });
                continue;
            }

            // ── !dpkg-test — diagnose the dpkg "status-old: Operation not permitted"
            // failure. apt-get update works now, but EVERY apt install runs dpkg,
            // which backs up /var/lib/dpkg/status via link() (atomic_file_backup).
            // arm64 has NO link syscall (only linkat); if the bundled proot's
            // --link2symlink only hooks link(), the linkat() falls through → EPERM.
            // This probe creates a hardlink directly and reports whether proot
            // turned it into a SYMLINK (l2s working) or it EPERM'd (l2s gap).
            if (line.startsWith('!dpkg-test')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G='\x1b[32m', Y='\x1b[33m', R='\x1b[31m', D='\x1b[2m', X='\x1b[0m';
                const probe = [
                    'set +e',
                    'cd /var/lib/dpkg 2>/dev/null || cd /tmp',
                    'rm -f .l2s-probe 2>/dev/null',
                    'echo "::link::"',
                    // try hardlinking an existing file; capture errno text
                    'ln status .l2s-probe 2>&1; echo "ln-rc=$?"',
                    'echo "::probe-stat::"',
                    'ls -la .l2s-probe 2>&1',          // 'l' first char ⇒ symlink ⇒ l2s working
                    'rm -f .l2s-probe 2>/dev/null',
                    'echo "::dpkg-ver::"',
                    'dpkg --version 2>&1 | head -1',
                    'echo "::done::"',
                ].join('\n');
                w(Y+'!dpkg-test: probing hardlink/link2symlink on the guest (up to 30s)…'+X+'\r\n');
                runProotGuest(['/bin/bash','-lc', probe], 30000)
                  .then(r => {
                    const out = r.out || '';
                    const lnBlk   = (out.match(/::link::\n([\s\S]*?)::probe-stat::/) || [])[1] || '';
                    const statBlk = (out.match(/::probe-stat::\n([\s\S]*?)::dpkg-ver::/) || [])[1] || '';
                    const verBlk  = (out.match(/::dpkg-ver::\n([\s\S]*?)::done::/) || [])[1] || '';
                    const lnOk = /ln-rc=0/.test(lnBlk);
                    const isSymlink = /^l/.test(statBlk.trim());        // ls -la shows 'l…' for symlink
                    let rep = '';
                    rep += D+'── ln status .l2s-probe ──'+X+'\r\n'+lnBlk.trim()+'\r\n';
                    rep += D+'── ls -la .l2s-probe ──'+X+'\r\n'+statBlk.trim()+'\r\n';
                    rep += D+'── dpkg --version ──'+X+'\r\n'+verBlk.trim()+'\r\n';
                    rep += '\r\n';
                    if (lnOk && isSymlink)
                        rep += G+'✓ link2symlink WORKS (hardlink became a symlink) — dpkg backup should not EPERM. The earlier failure may be elsewhere; retry: apt-get install -y ca-certificates'+X+'\r\n';
                    else if (lnOk)
                        rep += Y+'~ hardlink succeeded as a REAL link — fs supports it; dpkg should work. Retry the install.'+X+'\r\n';
                    else
                        rep += R+'✗ hardlink FAILED (link2symlink not intercepting linkat on arm64). This is why dpkg EPERMs on status-old. Needs a newer proot binary (rebuild) OR a guest-side workaround.'+X+'\r\n';
                    w(rep);
                  })
                  .catch(e => { w(R+'[!dpkg-test error] '+(e&&e.message)+X+'\r\n'); });
                continue;
            }

            // ── !apt-extract <pkg…> — install apt packages WITHOUT dpkg ─────────
            // Workaround for the proot link2symlink/linkat gap that makes normal
            // `apt install` fail at dpkg's status-old hardlink backup (see !dpkg-test).
            // apt-get -d only DOWNLOADS .debs (no unpack/configure → no dpkg, no
            // hardlink); dpkg-deb -x is the pure archive extractor (no status DB).
            // So the package's FILES land in the rootfs and CLI binaries work
            // immediately. Caveat: maintainer scripts (postinst) do NOT run, so
            // services / generated config / alternatives aren't set up — fine for
            // plain CLI tools (git, ripgrep, jq, build tools), not for daemons.
            if (line.startsWith('!apt-extract')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G='\x1b[32m', Y='\x1b[33m', R='\x1b[31m', D='\x1b[2m', X='\x1b[0m';
                const raw = line.slice('!apt-extract'.length).trim();
                if (!raw) { w(R+'usage: !apt-extract <pkg> [pkg2 …]   e.g. !apt-extract ripgrep'+X+'\r\n'); continue; }
                const safe = raw.replace(/[^a-zA-Z0-9 ._+-]/g, '').trim();  // shell-safe pkg names
                if (!safe) { w(R+'!apt-extract: no valid package names'+X+'\r\n'); continue; }
                const cmd = [
                    'set +e',
                    // Un-wedge dpkg: a prior failed install (hardlink EPERM) leaves
                    // numbered journal files in /var/lib/dpkg/updates/, which makes
                    // apt refuse everything with "dpkg was interrupted, run
                    // dpkg --configure -a" — but that re-hits the same EPERM. The
                    // journal is for an install that never completed, so discarding
                    // it is safe and is the only thing apt's interrupted-check reads.
                    'rm -f /var/lib/dpkg/updates/* 2>/dev/null',
                    // some apt audits expect status-old to exist; create via COPY
                    // (not link — that EPERMs) so nothing else trips.
                    '[ -f /var/lib/dpkg/status-old ] || cp -a /var/lib/dpkg/status /var/lib/dpkg/status-old 2>/dev/null',
                    'apt-get clean 2>/dev/null',                 // archives = ONLY this request's debs
                    'echo "::download::"',
                    'apt-get install -y -d ' + safe + ' 2>&1 | tail -12; DL=$?',
                    'echo "::extract::"',
                    'cd /var/cache/apt/archives 2>/dev/null || { echo "no archives dir"; exit 1; }',
                    'n=0; for f in *.deb; do [ -f "$f" ] || continue; if dpkg-deb -x "$f" / 2>/dev/null; then n=$((n+1)); fi; done',
                    'echo "extracted $n package file(s) into the rootfs"',
                    'ldconfig 2>/dev/null',
                    'echo "::verify::"',
                    'for p in ' + safe + '; do if command -v "$p" >/dev/null 2>&1; then echo "  $p -> $(command -v "$p")"; else echo "  $p (installed, but no same-named binary on PATH — check the package)"; fi; done',
                    'echo "::done::"; exit $DL',
                ].join('\n');
                w(Y+'!apt-extract: download-only + dpkg-deb -x (bypasses the dpkg hardlink bug) — up to 180s…'+X+'\r\n');
                runProotGuest(['/bin/bash','-lc', cmd], 180000)
                  .then(r => {
                    const out = r.out || '';
                    const dl = (out.match(/::download::\n([\s\S]*?)::extract::/) || [])[1] || '';
                    const ex = (out.match(/::extract::\n([\s\S]*?)::verify::/) || [])[1] || '';
                    const vf = (out.match(/::verify::\n([\s\S]*?)::done::/) || [])[1] || '';
                    const timedOut = /\[timeout \d+ms\]/.test(out) || r.code === null;
                    let rep = '';
                    rep += D+'── download ──'+X+'\r\n'+dl.trim()+'\r\n';
                    rep += D+'── extract ──'+X+'\r\n'+ex.trim()+'\r\n';
                    rep += D+'── on PATH ──'+X+'\r\n'+vf.trim()+'\r\n';
                    if (timedOut) rep += R+'TIMED OUT — big download? retry, or use !install for curated tools'+X+'\r\n';
                    else if (r.code === 0) rep += G+'✓ done (no maintainer scripts ran — CLI tools work; daemons/config may need more)'+X+'\r\n';
                    else rep += R+'✗ download exit='+r.code+' — package not found or net error (try !apt-fix-dns first)'+X+'\r\n';
                    w(rep);
                  })
                  .catch(e => { w(R+'[!apt-extract error] '+(e&&e.message)+X+'\r\n'); });
                continue;
            }

            // ── !test-noperms — re-probe the permission apparatus on 2.1.160 ────
            // Invariant re-audit probe #5 (③, inv 25/31/32/71). On Bionic/2.1.112
            // --dangerously-skip-permissions HUNG (inv 5b), so tool approval was done
            // entirely via settings.json permissions.allow:['*'] + customApiKeyResponses
            // .approved (so sk-ant-proxy000 is accepted without the login selector) +
            // auto_approve.json + the permission card. Now that skip-permissions WORKS
            // on proot (b15) the whole stack may be redundant. Test: write a MINIMAL
            // settings.json with NEITHER permissions.allow NOR customApiKeyResponses
            // (keep only theme/onboarding + additionalDirectories — the separate
            // workspace boundary, inv 62), spawn a Write-tool task relying solely on
            // --dangerously-skip-permissions + IS_SANDBOX, then RESTORE settings.
            //   tool ran + no login hang → the apparatus is deletable scar (P4).
            if (line.startsWith('!test-noperms')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G='\x1b[32m', Y='\x1b[33m', R='\x1b[31m', D='\x1b[2m', X='\x1b[0m';
                const claudeDir = path.join(FILES_DIR, '.claude');
                const sp = path.join(claudeDir, 'settings.json');
                const npSentinelHost  = path.join(claudeDir, 'noperm_probe.txt');
                const npSentinelGuest = '/root/.claude/noperm_probe.txt';
                let backup = null;
                const restore = () => {
                    try {
                        if (backup === null) { try { fs.unlinkSync(sp); } catch(_) {} }
                        else fs.writeFileSync(sp, backup);
                    } catch(_) {}
                    try { fs.unlinkSync(npSentinelHost); } catch(_) {}
                };
                try {
                    try { fs.mkdirSync(claudeDir, { recursive: true }); } catch(_) {}
                    try { backup = fs.readFileSync(sp, 'utf8'); } catch(_) { backup = null; }
                    try { fs.unlinkSync(npSentinelHost); } catch(_) {}
                    // MINIMAL settings — no permissions.allow, no customApiKeyResponses.
                    const minimal = {
                        theme: 'dark', hasCompletedOnboarding: true,
                        hasShownWelcome: true, skipWelcome: true,
                        permissions: { additionalDirectories: ['/root', '/sdcard', FILES_DIR] },
                    };
                    fs.writeFileSync(sp, JSON.stringify(minimal, null, 2));
                } catch (e) {
                    restore();
                    w(R + '✗ !test-noperms: could not stage settings: ' + e.message + X + '\r\n');
                    continue;
                }
                const npBenv = buildEnv();
                const npGuestEnv = {
                    ANTHROPIC_API_KEY:   npBenv.ANTHROPIC_API_KEY,
                    ANTHROPIC_MODEL:     npBenv.ANTHROPIC_MODEL,
                    DISABLE_AUTOUPDATER: '1',
                    SHELL:               '/bin/bash',
                    IS_SANDBOX:          '1',
                };
                if (npBenv.ANTHROPIC_BASE_URL) npGuestEnv.ANTHROPIC_BASE_URL = npBenv.ANTHROPIC_BASE_URL;
                const npMsg = 'Use the Write tool to create the file ' + npSentinelGuest +
                              ' with the exact contents PERMOK and nothing else. Then reply with OK.';
                const npArgv = [GUEST_CLAUDE, '--output-format', 'stream-json', '--print',
                                '--dangerously-skip-permissions', '--verbose', npMsg];
                w(Y + '!test-noperms (proot/2.1.160): settings stripped (no permissions.allow, no customApiKeyResponses), Write task (90s)…' + X + '\r\n');
                runProotGuest(npArgv, 90000, null, { extraEnv: npGuestEnv, workspace: FILES_DIR })
                  .then(r => {
                    const out = r.out || '';
                    const fileOk    = (() => { try { return fs.readFileSync(npSentinelHost,'utf8').includes('PERMOK'); } catch(_) { return false; } })();
                    const timedOut  = /\[timeout \d+ms\]/.test(out) || r.code === null;
                    const gotResult = out.includes('"type":"result"');
                    const writeFired= /"type"\s*:\s*"tool_use"[^}]*"name"\s*:\s*"Write"/.test(out);
                    // Login/key-rejection signals (the customApiKeyResponses purpose).
                    const loginHang = /Invalid API key|login|Please run|select.*account|API Key|authentication/i.test(out) && !gotResult;
                    restore();
                    const works = fileOk && !timedOut && !loginHang;
                    const mark = works ? (G+'✓') : (R+'✗');
                    let rep = mark + ' !test-noperms (proot) exit=' + r.code + X + '\r\n';
                    rep += '  Apparatus needed? ' + (works
                            ? G+'NO — tool ran + key accepted with permissions.allow AND customApiKeyResponses removed. Deletable scar (P4).'+X
                            : loginHang ? R+'customApiKeyResponses still needed (login/key prompt without it)'+X
                            : !fileOk ? R+'permissions.allow may still be needed (Write did not land)'+X
                            : Y+'inconclusive — see stdout'+X) + '\r\n';
                    rep += '  Write tool fired:   ' + (writeFired ? G+'yes'+X : R+'no'+X) + '\r\n';
                    rep += '  File written (PERMOK): ' + (fileOk ? G+'yes'+X : R+'no'+X) + '\r\n';
                    rep += '  Completed (no hang): ' + (!timedOut ? G+'yes'+X : R+'no — TIMED OUT'+X) + '\r\n';
                    rep += '  Got final result:   ' + (gotResult ? G+'yes'+X : R+'no'+X) + '\r\n';
                    rep += D+'  (settings.json restored)'+X+'\r\n';
                    if (!works) rep += D+'stdout (last 500): ' + out.slice(-500).replace(/\r?\n/g,' ') + X + '\r\n';
                    w(rep);
                  })
                  .catch(e => { restore(); w(R + '[!test-noperms proot error] ' + (e && e.message) + X + '\r\n'); });
                continue;
            }

            // ── !test-shell — re-probe the Bash tool's shell requirement on 2.1.160 ──
            // Invariant re-audit probe #6 (①, inv 70). On Bionic, claude-code filters
            // shell candidates by $SHELL containing "bash"/"zsh" then probes hardcoded
            // /bin/bash etc. (none exist on Android) → "No suitable shell found" on every
            // Bash-tool call. The legacy fix = a BIN_DIR/bash symlink → /system/bin/sh +
            // CLAUDE_CODE_SHELL. The proot guest has a REAL /bin/bash and we set SHELL=
            // /bin/bash, so the symlink + CLAUDE_CODE_SHELL dance should be unnecessary.
            // Test: spawn the guest WITHOUT CLAUDE_CODE_SHELL, run a Bash-tool task; if
            // the command's output comes back (no "No suitable shell"), drop the hack on proot.
            if (line.startsWith('!test-shell')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G='\x1b[32m', Y='\x1b[33m', R='\x1b[31m', D='\x1b[2m', X='\x1b[0m';
                const shTag = 'SHELLOK_' + Date.now().toString(36);
                const shBenv = buildEnv();
                try { patchSettings(readConfig()); } catch(_) {}
                const shGuestEnv = {
                    ANTHROPIC_API_KEY:   shBenv.ANTHROPIC_API_KEY,
                    ANTHROPIC_MODEL:     shBenv.ANTHROPIC_MODEL,
                    DISABLE_AUTOUPDATER: '1',
                    SHELL:               '/bin/bash',  // real bash in the guest
                    IS_SANDBOX:          '1',
                    // Deliberately NOT setting CLAUDE_CODE_SHELL — that's the legacy hack.
                };
                if (shBenv.ANTHROPIC_BASE_URL) shGuestEnv.ANTHROPIC_BASE_URL = shBenv.ANTHROPIC_BASE_URL;
                const shMsg = 'Use the Bash tool to run exactly this command: echo ' + shTag +
                              ' . Then tell me its output.';
                const shArgv = [GUEST_CLAUDE, '--output-format', 'stream-json', '--print',
                                '--dangerously-skip-permissions', '--verbose', shMsg];
                w(Y + '!test-shell (proot/2.1.160): Bash-tool task, no CLAUDE_CODE_SHELL (90s)…' + X + '\r\n');
                runProotGuest(shArgv, 90000, null, { extraEnv: shGuestEnv, workspace: FILES_DIR })
                  .then(r => {
                    const out = r.out || '';
                    const bashFired = /"type"\s*:\s*"tool_use"[^}]*"name"\s*:\s*"Bash"/.test(out);
                    const noShell   = /No suitable shell found|Posix shell environment/i.test(out);
                    const tagRan    = out.includes(shTag) && bashFired; // tag echoed back via tool_result
                    const gotResult = out.includes('"type":"result"');
                    const works = bashFired && !noShell && tagRan;
                    const mark = works ? (G+'✓') : (bashFired ? (Y+'~') : (R+'✗'));
                    let rep = mark + ' !test-shell (proot) exit=' + r.code + X + '\r\n';
                    rep += '  CLAUDE_CODE_SHELL/symlink needed? ' + (works
                            ? G+'NO — Bash tool ran with just SHELL=/bin/bash. Drop the symlink+CLAUDE_CODE_SHELL hack on proot.'+X
                            : noShell ? R+'YES — "No suitable shell found" (keep the hack)'+X
                            : !bashFired ? Y+'inconclusive (model never called Bash)'+X
                            : Y+'inconclusive — see stdout'+X) + '\r\n';
                    rep += '  Bash tool fired:    ' + (bashFired ? G+'yes'+X : R+'no'+X) + '\r\n';
                    rep += '  "No suitable shell": ' + (noShell ? R+'yes'+X : G+'no'+X) + '\r\n';
                    rep += '  Command output seen: ' + (tagRan ? G+'yes'+X : D+'no'+X) + '\r\n';
                    rep += '  Got final result:   ' + (gotResult ? G+'yes'+X : R+'no'+X) + '\r\n';
                    if (!works) rep += D+'stdout (last 500): ' + out.slice(-500).replace(/\r?\n/g,' ') + X + '\r\n';
                    w(rep);
                  })
                  .catch(e => { w(R + '[!test-shell proot error] ' + (e && e.message) + X + '\r\n'); });
                continue;
            }

            // (!test-persistent probe removed b51 — the warm session it validated
            //  shipped as the default in runMessage's warm path. git history has it.)

            // ── !test-mcp — P3b: probe NATIVE HTTP MCP in the proot guest ───────
            // The legacy path wraps each HTTP MCP server (e.g. Exa) in a stdio shim
            // (mcp_http_proxy.js via libnode-launcher) because 2.1.112 HUNG on a
            // native type:http/sse server connecting at spawn (inv 51). That shim is
            // all Android-side (LAUNCHER, NATIVE_DIR, FILES_DIR paths) and can't run
            // in the guest — hence `mcp tools in request: 0` on proot. The guest has
            // real node + real network, so native HTTP MCP should work directly on
            // 2.1.160. Test: write a NATIVE {type:"http",url,headers} mcp-config into
            // the guest (/root/.claude via bind), spawn with --mcp-config, and check
            // (a) no spawn hang (inv 51 gone) (b) mcp__<server>__* tools register.
            if (line.startsWith('!test-mcp')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const G='\x1b[32m', Y='\x1b[33m', R='\x1b[31m', D='\x1b[2m', X='\x1b[0m';
                let httpEntries = [];
                try { if (fs.existsSync(MCP_HTTP_CONFIG)) httpEntries = JSON.parse(fs.readFileSync(MCP_HTTP_CONFIG,'utf8')) || []; } catch(_) {}
                httpEntries = (Array.isArray(httpEntries) ? httpEntries : []).filter(e => e && e.name && e.url);
                if (!httpEntries.length) {
                    w(Y + '!test-mcp: no HTTP MCP server configured. Add one (e.g. Exa) in Settings → MCP first, then retry.' + X + '\r\n');
                    continue;
                }
                // Build a NATIVE http mcp-config (no shim).
                const mcpServers = {};
                for (const up of httpEntries) {
                    const safe = String(up.name).replace(/[^a-zA-Z0-9_-]/g, '_');
                    mcpServers[safe] = { type: 'http', url: String(up.url), headers: up.headers || {} };
                }
                const guestCfgHost  = path.join(FILES_DIR, '.claude', 'mcp_test_guest.json');
                const guestCfgGuest = '/root/.claude/mcp_test_guest.json';
                try {
                    fs.mkdirSync(path.join(FILES_DIR, '.claude'), { recursive: true });
                    fs.writeFileSync(guestCfgHost, JSON.stringify({ mcpServers }, null, 2));
                } catch (e) { w(R + '✗ !test-mcp: could not write guest mcp-config: ' + e.message + X + '\r\n'); continue; }
                const srvNames = Object.keys(mcpServers);
                const mBenv = buildEnv();
                try { patchSettings(readConfig()); } catch(_) {}
                const mGuestEnv = {
                    ANTHROPIC_API_KEY:   mBenv.ANTHROPIC_API_KEY,
                    ANTHROPIC_MODEL:     mBenv.ANTHROPIC_MODEL,
                    DISABLE_AUTOUPDATER: '1',
                    SHELL:               '/bin/bash',
                    IS_SANDBOX:          '1',
                    MCP_TIMEOUT:         '30000',
                    MCP_TOOL_TIMEOUT:    '30000',
                };
                if (mBenv.ANTHROPIC_BASE_URL) mGuestEnv.ANTHROPIC_BASE_URL = mBenv.ANTHROPIC_BASE_URL;
                // inv 65b: --mcp-config is variadic — keep --verbose between it and the message.
                // Native HTTP MCP registers the server as "pending" at init and connects
                // ASYNC — a fast model answers before the handshake finishes (b27). claude-code
                // ships WaitForMcpServers exactly for this; instruct the model to call it first
                // so we learn whether native HTTP works *given time* vs is genuinely stuck.
                const mMsg = 'First, call the WaitForMcpServers tool and wait until all MCP servers ' +
                             'have finished connecting. Then list the names of every tool you have whose ' +
                             'name begins with "mcp__", one per line. If after waiting you still have none, ' +
                             'reply exactly: NO_MCP_TOOLS.';
                const mArgv = [GUEST_CLAUDE, '--output-format', 'stream-json', '--print',
                               '--dangerously-skip-permissions',
                               '--mcp-config', guestCfgGuest, '--verbose', mMsg];
                w(Y + '!test-mcp (proot/2.1.160): native HTTP MCP [' + srvNames.join(', ') + '], spawning (120s; inv-51 hung here on 2.1.112)…' + X + '\r\n');
                runProotGuest(mArgv, 120000, null, { extraEnv: mGuestEnv, workspace: FILES_DIR })
                  .then(async r => {
                    const out = r.out || '';
                    const timedOut   = /\[timeout \d+ms\]/.test(out) || r.code === null;
                    const gotResult  = out.includes('"type":"result"');
                    const mcpToolSeen= /mcp__/.test(out);
                    // Find the system/init event (first stream-json line) — it lists
                    // connected mcp_servers + the full tools array. Dump it verbatim.
                    let initEvt = null, connected = false, failed = false, initTools = 0;
                    for (const ln of out.split('\n')) {
                        const s = ln.trim(); if (!s.startsWith('{')) continue;
                        let o; try { o = JSON.parse(s); } catch(_) { continue; }
                        if (o.type === 'system' && o.subtype === 'init' && !initEvt) initEvt = o;
                        const arr = o && o.mcp_servers;
                        if (Array.isArray(arr)) for (const sv of arr) {
                            if (/connect/i.test(sv.status||'')) connected = true;
                            if (/fail|error|closed/i.test(sv.status||'')) failed = true;
                        }
                    }
                    if (initEvt && Array.isArray(initEvt.tools)) initTools = initEvt.tools.filter(t => /^mcp__/.test(String(t))).length;
                    const works = !timedOut && (connected || mcpToolSeen || initTools > 0);
                    // Lines mentioning mcp/connection errors (stderr is folded into out).
                    const mcpLines = out.split('\n').filter(l => /mcp|MCP|ECONN|ENOTFOUND|fetch failed|connect|401|403|timeout/i.test(l)).slice(0, 12);
                    try { fs.unlinkSync(guestCfgHost); } catch(_) {}
                    const mark = works ? (G+'✓') : (R+'✗');
                    let rep = mark + ' !test-mcp (proot) exit=' + r.code + X + '\r\n';
                    rep += '  Native HTTP MCP works? ' + (works
                            ? G+'YES — wire --mcp-config into proot runMessage (no shim).'+X
                            : timedOut ? R+'NO — spawn HUNG (inv-51 still bites; need stdio shim in-guest)'+X
                            : failed ? R+'server FAILED to connect (check URL/headers/key)'+X
                            : Y+'inconclusive — see init event + mcp lines below'+X) + '\r\n';
                    rep += '  Server connected:   ' + (connected ? G+'yes'+X : (failed ? R+'no (failed)'+X : D+'unknown'+X)) + '\r\n';
                    rep += '  mcp__ tools in init: ' + (initTools>0 ? G+String(initTools)+X : R+'0'+X) + '\r\n';
                    rep += '  mcp__ seen anywhere: ' + (mcpToolSeen ? G+'yes'+X : R+'no'+X) + '\r\n';
                    rep += '  Completed (no hang): ' + (!timedOut ? G+'yes'+X : R+'no'+X) + '\r\n';
                    // The init event is the authoritative connection record — show it.
                    if (initEvt) {
                        rep += D+'init.mcp_servers: ' + JSON.stringify(initEvt.mcp_servers || '(field absent)') + X + '\r\n';
                        rep += D+'init.tools: ' + JSON.stringify(initEvt.tools || []).slice(0, 400) + X + '\r\n';
                    } else {
                        rep += R+'  (no system/init event found in output!)'+X+'\r\n';
                    }
                    if (mcpLines.length) rep += D+'mcp/conn lines:\r\n  ' + mcpLines.join('\r\n  ').slice(0, 800) + X + '\r\n';
                    w(rep);
                    // Upload full trace so we can read it off-device (Appetize crops).
                    try {
                        const url = await uploadDiag('=== !test-mcp guest trace (exit=' + r.code + ') ===\nargv: ' + JSON.stringify(mArgv) + '\nconfig: ' + JSON.stringify({mcpServers}) + '\n\n' + out);
                        if (url) w(G + 'full trace: ' + url + X + '\r\n');
                    } catch(_) {}
                  })
                  .catch(e => { try { fs.unlinkSync(guestCfgHost); } catch(_) {} w(R + '[!test-mcp proot error] ' + (e && e.message) + X + '\r\n'); });
                continue;
            }

            // MCP-6: !mcp-reload — re-read config + start/stop servers without
            // resetting the session. Same path the Kotlin marker-file watcher takes.
            if (line.startsWith('!mcp-reload')) {
                try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[2m[mcp-reload] reloading…\x1b[0m\r\n'); } catch(_) {}
                reloadMcpServers().then(s => {
                    const msg = '[mcp-reload] started ' + s.startedStdio + ' stdio + ' + s.startedHttp + ' http, stopped ' + s.stoppedStdio + ' stdio + ' + s.stoppedHttp + ' http\r\n';
                    // Show the resulting server/tool set so the user SEES what's now live
                    // (the old handler printed only counts → looked like nothing happened).
                    // claude-code picks these up on the next message — no force-close needed.
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[32m' + msg + '\x1b[0m' + buildMcpListing() +
                        '\x1b[2mActive on your next message — no restart needed.\x1b[0m\r\n'); } catch(_) {}
                }).catch(e => {
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[mcp-reload] error: ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
                });
                continue;
            }

            // MCP-5: !mcp-log [name] — show buffered stderr lines from stdio MCP
            // servers. With no name → all servers; with name → that server only.
            // Last 50 lines per server unless `all` is passed.
            if (line.startsWith('!mcp-log')) {
                const arg = line.slice('!mcp-log'.length).trim();
                const showAll = arg === 'all';
                const wantName = (arg && !showAll) ? arg : null;
                let out = '\x1b[1m[MCP stderr]\x1b[0m\r\n';
                let any = false;
                for (const [name, srv] of mcpStdioServers.entries()) {
                    if (wantName && name !== wantName) continue;
                    const lines = showAll ? srv.stderrLines : srv.stderrLines.slice(-50);
                    if (lines.length === 0) continue;
                    any = true;
                    out += '  \x1b[33m' + name + '\x1b[0m \x1b[2m(' + lines.length + ' lines)\x1b[0m\r\n';
                    for (const ln of lines) out += '    \x1b[2m' + stripAnsi(ln) + '\x1b[0m\r\n';
                }
                // Also show failed servers' last error (no stderr to buffer if it never started).
                for (const [name, info] of mcpFailed.entries()) {
                    if (wantName && name !== wantName) continue;
                    any = true;
                    out += '  \x1b[31m✗ ' + name + '\x1b[0m \x1b[2m(' + info.type + ', failed)\x1b[0m\r\n';
                    out += '    \x1b[31m' + (info.error || '').slice(0, 400) + '\x1b[0m\r\n';
                }
                if (!any) out += '  \x1b[2m(no stderr captured)\x1b[0m\r\n';
                try { if (state.socket) state.socket.write(SYS_FENCE + out); } catch(_) {}
                continue;
            }

            if (line.startsWith('!mcp')) {
                try { if (state.socket) state.socket.write(SYS_FENCE + buildMcpListing()); } catch(_) {}
                continue;
            }

            // ── Shell commands: $ cmd — run even while claude is busy ────────────
            // Output wrapped in SYS_FENCE so it always routes to a sys bubble,
            // never into the AI bubble even when chatState is RESPONDING.
            if (line.startsWith('$ ')) {
                const cmd = line.slice(2).trim();
                if (cmd.startsWith('cd ')) {
                    const newDir = cmd.slice(3).trim();
                    try {
                        process.chdir(newDir);
                        state.cwd = newDir;
                        saveSessionState(state.sid, state);
                        try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[2m[cwd: ' + newDir + ']\x1b[0m\r\n'); } catch(_) {}
                        try { if (state.socket) state.socket.write('\x1b]9;cwd:' + newDir + '\x07'); } catch(_) {}
                    } catch(e) { try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[cd: ' + e.message + ']\x1b[0m\r\n'); } catch(_) {} }
                } else {
                    const wasBusy = state.busy;
                    if (!wasBusy) state.busy = true;
                    const sh = spawn('/system/bin/sh', ['-c', cmd], { env: buildEnv(), cwd: state.cwd });
                    sh.stdout.on('data', d2 => { try { if (state.socket) state.socket.write(SYS_FENCE + d2.toString()); } catch(_) {} });
                    sh.stderr.on('data', d2 => { try { if (state.socket) state.socket.write(SYS_FENCE + d2.toString()); } catch(_) {} });
                    const shTid = setTimeout(() => {
                        try { sh.kill('SIGTERM'); } catch(_) {}
                        try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[$ cmd timed out after 30 s]\x1b[0m\r\n'); } catch(_) {}
                        if (!wasBusy) state.busy = false;
                    }, 30000);
                    sh.on('close', () => {
                        clearTimeout(shTid);
                        if (!wasBusy) state.busy = false;
                    });
                }
                continue;
            }

            // ── busy gate — only block new AI messages while a response is in flight ──
            // ! commands and $ shell commands always fall through regardless of busy state.
            if (state.busy && !line.startsWith('!') && !line.startsWith('$')) {
                log('[input] BLOCKED (busy) msg=' + line.slice(0, 80) + '\n');
                try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m[busy — please wait]\x1b[0m\r\n'); } catch(_) {}
                continue;
            }
            // Log every non-command line that reaches this point (passed gate)
            if (!line.startsWith('!') && !line.startsWith('$')) {
                log('[input] msg=' + line.slice(0, 80) + ' busy=' + state.busy + ' hasHistory=' + state.hasHistory + '\n');
            }

            // ── ! commands ────────────────────────────────────────────────────────
            // F1: !agentic was removed — the standalone agentic loop is gone.
            // Show a one-line note so users with muscle memory know the deal.
            if (line.startsWith('!agentic')) {
                try { if (state.socket) state.socket.write(SYS_FENCE +
                    '\x1b[33m[!agentic removed — use the terminal as normal; claude-code handles tools natively]\x1b[0m\r\n'); } catch(_) {}
                continue;
            }

            if (line.startsWith('!context')) {
                const p = line.slice(8).trim() || (readConfig().projectPath || FILES_DIR);
                try {
                    const st = fs.statSync(p);
                    state.contextBlock = st.isDirectory()
                        ? '[Directory: ' + p + ']\n' + fs.readdirSync(p).slice(0, 80).join('\n')
                        : '[File: ' + p + ']\n' + fs.readFileSync(p, 'utf8').slice(0, 30000);
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m[context loaded: ' + p + ']\x1b[0m\r\n'); } catch(_) {}
                } catch(e) { try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[!context: ' + e.message + ']\x1b[0m\r\n'); } catch(_) {} }
                continue;
            }

            if (line.startsWith('!attach')) {
                const p = line.slice(7).trim();
                try {
                    state.pendingAttach = '[Attached: ' + p + ']\n' + fs.readFileSync(p, 'utf8').slice(0, 30000);
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m[attached: ' + p + ']\x1b[0m\r\n'); } catch(_) {}
                } catch(e) { try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[!attach: ' + e.message + ']\x1b[0m\r\n'); } catch(_) {} }
                continue;
            }

            if (line.startsWith('!install')) {
                const pkgName = line.slice(8).trim();
                if (!pkgName) {
                    const entries = Object.entries(PACKAGE_CATALOG);
                    const isNpm = (m) => m.type === 'npm';
                    const tools = entries.filter(([, m]) => !isNpm(m));
                    const npms  = entries.filter(([,  m]) =>  isNpm(m));
                    const nameW = Math.max(...entries.map(([n]) => n.length));
                    // first clause of the desc (before the em-dash) = the short label
                    const shortDesc = (m) => {
                        const d = (m.desc || '').split('—')[1] || (m.desc || '');
                        return d.trim().split(/[.,(]/)[0].trim();
                    };
                    const row = ([n, m]) => {
                        const pad = n + ' '.repeat(nameW - n.length);
                        const sz  = m.size ? ' \x1b[2m(' + m.size + ')\x1b[0m' : '';
                        return '  \x1b[36m' + pad + '\x1b[0m  ' + shortDesc(m) + sz + '\r\n';
                    };
                    let out = SYS_FENCE + '\x1b[1;33mAvailable packages\x1b[0m\r\n';
                    out += '\x1b[2m─ tools ─────────────────────────\x1b[0m\r\n';
                    for (const e of tools) out += row(e);
                    out += '\x1b[2m─ npm ───────────────────────────\x1b[0m\r\n';
                    for (const e of npms) out += row(e);
                    out += '\x1b[2mUsage: !install <package>\x1b[0m\r\n';
                    try { if (state.socket) state.socket.write(out); } catch(_) {}
                } else {
                    installPackage(pkgName, state.socket);
                }
                continue;
            }

            // ── Catch-all: unrecognized ! command — NEVER send to AI ────────────
            // Any ! text that reached this point matched none of the known handlers.
            // Without this guard, autocorrect variants ("!Test-cli"), typos, or
            // future unknown commands would fall through to runMessage() and reach AI.
            if (line.startsWith('!')) {
                try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m[unknown command: ' + line.slice(0, 40) + ']\x1b[0m — type \x1b[33m!help\x1b[0m for commands\r\n'); } catch(_) {}
                continue;
            }

            // ── Forward everything else to claude-code (print mode) ──────────────
            // Image attach (post-F1): if pending_image.b64/.mime exist, the
            // proxy's maybeInjectPendingImage() rewrites the outgoing
            // /v1/messages body to include a multimodal content block. Files
            // are deleted there (one-shot), so no cleanup needed here.
            let msg = line;
            if (state.contextBlock)  { msg = state.contextBlock  + '\n\n' + msg; state.contextBlock  = ''; }
            if (state.pendingAttach) { msg = state.pendingAttach + '\n\n' + msg; state.pendingAttach = null; }
            runMessage(msg, state);
        }
    }

    // ── Attach a socket to a session (create if new) ──────────────────────────
    function attachSession(sid, socket, leftover) {
        let state = activeSessions.get(sid);

        if (!state) {
            const cfg = readConfig();
            // Restore persisted state if within 24-hour TTL
            const saved = loadSessionState(sid);
            state = {
                socket: null,
                busy: false, thinkingDone: false,
                currentProc: null,
                pendingPerm: null,
                inputBuf: '', contextBlock: '', pendingAttach: null,
                sessionTokens: saved ? (saved.sessionTokens || 0) : 0,
                // Project mode: restore hasHistory from disk so history persists across restarts.
                // Normal chat: always start fresh — don't carry conversation across app restarts.
                hasHistory: (cfg.projectPath && saved) ? !!saved.hasHistory : false,
                cwd: (saved && saved.cwd) ? saved.cwd : (cfg.projectPath || FILES_DIR),
                sid
            };
            if (saved) log('[session:' + sid + '] restored from disk (hasHistory=' + state.hasHistory + ' cwd=' + state.cwd + ' projectMode=' + !!cfg.projectPath + ')\n');
            activeSessions.set(sid, state);
        } else {
            // M6: on reconnect, kill any stale process from a previous connection so
            // the new connection starts idle and doesn't inherit a dangling busy state.
            if (state.currentProc) {
                try { state.currentProc.kill('SIGTERM'); } catch(_) {}
                state.currentProc = null;
            }
            state.busy = false;
            state.thinkingDone = false;
        }

        state.socket = socket;

        try { socket.write('\x1b]9;cwd:' + state.cwd + '\x07'); } catch(_) {}
        try { socket.write('\x1b]9;tokens:' + state.sessionTokens + '\x07'); } catch(_) {}
        try { socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
        if (mcpReadyInfo && mcpReadyInfo.length > 0) {
            try { socket.write('\x1b]9;mcp-ready:' + Buffer.from(JSON.stringify(mcpReadyInfo)).toString('base64') + '\x07'); } catch(_) {}
        }
        if (state.busy) try { socket.write('\x1b]9;thinking-start\x07'); } catch(_) {}

        // Show project mode banner once per session attach (guard via _projectBannerShown)
        const sessionCfg = readConfig();
        if (sessionCfg.projectPath && !state._projectBannerShown) {
            state._projectBannerShown = true;
            const projName = sessionCfg.projectPath.split('/').filter(Boolean).pop() || sessionCfg.projectPath;
            const histMsg = state.hasHistory
                ? '\x1b[32mHistory restored — use \x1b[1m!clear\x1b[22m to start fresh\x1b[0m'
                : '\x1b[90mNew project session\x1b[0m';
            try {
                socket.write(
                    SYS_FENCE +
                    '\x1b[36m📂 Project: \x1b[1m' + projName + '\x1b[0m\x1b[36m  \x1b[2m' + sessionCfg.projectPath + '\x1b[0m\r\n' +
                    histMsg + '\r\n'
                );
            } catch(_) {}
        }

        socket.on('data', d => handleInput(d, state));

        if (leftover && leftover.length > 0) {
            handleInput(Buffer.from(leftover, 'binary'), state);
        }

        socket.on('close', () => {
            if (state.socket === socket) {
                state.socket = null;
                // In print mode there is no persistent proc to keep alive between messages;
                // the session state (hasHistory, cwd, etc.) is kept in activeSessions so
                // reconnecting (tab switch, brief disconnect) picks up the same context.
            }
        });
        socket.on('error', () => { try { socket.destroy(); } catch(_) {} });
    }

    // ── Attach a socket to a live Ubuntu PTY shell (P6) ───────────────────────
    // Raw byte relay — NO chat parsing, NO print mode. socket bytes → bash stdin
    // (incl. the ESC 0xFE resize sequence, which libpty strips + applies via
    // TIOCSWINSZ); bash output (via the PTY master) → socket → xterm.js. The shell
    // proc persists across socket disconnects (tab switch) for PTY_IDLE_MS.
    function attachPtySession(sid, socket, leftover) {
        let entry = ubuntuPtys.get(sid);
        const alive = entry && entry.proc && !entry.proc.killed && entry.proc.exitCode === null;
        if (alive) {
            // Reattach to the existing shell — detach the old socket, keep the proc.
            if (entry.idleTimer) { clearTimeout(entry.idleTimer); entry.idleTimer = null; }
            entry.socket = socket;
        } else {
            // Spawn a fresh interactive login shell inside the proot guest.
            const cfg = readConfig();
            let workspace = FILES_DIR;
            try { const c = fs.readFileSync(CWD_FILE, 'utf8').trim(); if (c && fs.existsSync(c)) workspace = c; } catch(_) {}
            if (cfg.projectPath && fs.existsSync(cfg.projectPath)) workspace = cfg.projectPath;
            // Cosmetic: kill the "groups: cannot find name for group ID <N>" spam at
            // shell login. Android runs us with supplementary GIDs (3003=inet, etc.)
            // the guest's /etc/group has no names for → glibc warns. Seed entries for
            // OUR actual GIDs (process.getgroups()) so the lookup resolves silently.
            try {
                const gids = (typeof process.getgroups === 'function') ? process.getgroups() : [];
                if (gids.length) {
                    const groupPath = path.join(FILES_DIR, 'ubuntu', 'etc', 'group');
                    let gtxt = ''; try { gtxt = fs.readFileSync(groupPath, 'utf8'); } catch(_) {}
                    let add = '';
                    for (const gid of gids) {
                        if (!new RegExp('^[^:]*:[^:]*:' + gid + ':', 'm').test(gtxt)) add += 'aid_' + gid + ':x:' + gid + ':\n';
                    }
                    if (add) fs.appendFileSync(groupPath, add);
                }
            } catch (e) { log('[ubuntu-pty] /etc/group seed: ' + e.message + '\n'); }
            // Fix apt/DNS + mirror: the rootfs's /etc/resolv.conf is often a
            // dangling symlink (points to /system/etc/resolv.conf which doesn't
            // exist inside proot). The default sources.list uses mirrors.kernel.org
            // which is dead ("does not have a Release file"). Rewrite BOTH so apt
            // works out of the box — no bootstrap script needed.
            try {
                const rp = path.join(FILES_DIR, 'ubuntu');
                const resolvPath = path.join(rp, 'etc', 'resolv.conf');
                try { fs.unlinkSync(resolvPath); } catch (_) {}
                fs.writeFileSync(resolvPath, 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n');
                // Point apt at the CORRECT arm64 mirror over HTTPS, matching the
                // rootfs's ACTUAL Ubuntu release. Problems (see !apt-fix-dns comment +
                // /sdcard/design/possibleaptsolution.md): arm64 packages are on
                // ports.ubuntu.com/ubuntu-ports for SUPPORTED releases but move to
                // old-releases.ubuntu.com/ubuntu when EOL; archive/old-releases/ubuntu
                // host amd64 too but EOL ones include arm64. Some ISPs 302-hijack
                // http:// mirror traffic (use HTTPS); the minimal rootfs has no
                // ca-certificates (disable TLS peer-verify). And the SUITE must match
                // the rootfs codename — a jammy sources.list on a lunar/mantic base
                // makes every install conflict (perl-base 5.36 vs jammy 5.34).
                try {
                    const aptCfgDir = path.join(rp, 'etc', 'apt', 'apt.conf.d');
                    try { fs.mkdirSync(aptCfgDir, { recursive: true }); } catch (_) {}
                    fs.writeFileSync(path.join(aptCfgDir, '99nexus-https'),
                        'Acquire::https::Verify-Peer "false";\nAcquire::https::Verify-Host "false";\n');
                } catch (_) {}
                // Detect the codename from the rootfs's /etc/os-release.
                let codename = '';
                try {
                    const osr = fs.readFileSync(path.join(rp, 'etc', 'os-release'), 'utf8');
                    codename = (osr.match(/^VERSION_CODENAME=([a-z]+)/m) || [])[1] || '';
                } catch (_) {}
                if (!codename) codename = 'jammy';  // last-resort default
                // Currently-supported Ubuntu releases live on ports (arm64); EOL ones
                // are archived on old-releases.ubuntu.com/ubuntu (which keeps arm64).
                const supportedCodenames = new Set(['focal','jammy','noble','oracular','plucky','questing']);
                const mirror = supportedCodenames.has(codename)
                    ? 'https://ports.ubuntu.com/ubuntu-ports'
                    : 'https://old-releases.ubuntu.com/ubuntu';
                const srcPath = path.join(rp, 'etc', 'apt', 'sources.list');
                const goodSources =
                    'deb [trusted=yes] ' + mirror + ' ' + codename + ' main restricted universe multiverse\n' +
                    'deb [trusted=yes] ' + mirror + ' ' + codename + '-updates main restricted universe multiverse\n' +
                    'deb [trusted=yes] ' + mirror + ' ' + codename + '-security main restricted universe multiverse\n';
                let curSrc = '';
                try { curSrc = fs.readFileSync(srcPath, 'utf8'); } catch (_) {}
                // Re-heal if: not our mirror, plain http, dead mirrors, OR the suite
                // doesn't match the detected codename (the jammy-on-newer-base bug).
                if (!curSrc.includes(mirror) || curSrc.includes('http://') ||
                    curSrc.includes('mirrors.kernel.org') || curSrc.includes('mirrors.ubuntu.com') ||
                    !curSrc.includes(' ' + codename + ' ')) {
                    fs.writeFileSync(srcPath, goodSources);
                    log('[ubuntu-pty] sources.list rewritten (→ ' + mirror + ' ' + codename + ', arm64)\n');
                }
            } catch (e) { log('[ubuntu-pty] resolv/apt seed: ' + e.message + '\n'); }
            // apt/apt-get SHIM so the INTERACTIVE claude (and the user) can `apt
            // install` despite the old proot's dpkg-hardlink EPERM (see !dpkg-test).
            // !apt-extract is a bridge command claude can't call; this shim shadows
            // the real tools at /usr/local/bin (on PATH before /usr/bin, line ~555)
            // so any `apt install X` / `apt-get install X` is transparently served
            // via download-only + dpkg-deb -x. Every other subcommand (update, list,
            // search, …) passes straight through to the real binary. Escape hatch:
            // NEXUS_APT_PASSTHROUGH=1 forces the real apt.
            try {
                const localBin = path.join(rp, 'usr', 'local', 'bin');
                try { fs.mkdirSync(localBin, { recursive: true }); } catch (_) {}
                const aptShim = [
                    '#!/bin/bash',
                    '# Nexus apt shim — install via extract (proot dpkg cannot hardlink).',
                    'self="$(basename "$0")"',
                    'REAL="/usr/bin/$self"',
                    '[ -x "$REAL" ] || REAL="/usr/bin/apt-get"',
                    'if [ "${NEXUS_APT_PASSTHROUGH:-0}" = "1" ]; then exec "$REAL" "$@"; fi',
                    'sub=""',
                    'for a in "$@"; do case "$a" in -*) ;; *) sub="$a"; break;; esac; done',
                    'if [ "$sub" != "install" ] && [ "$sub" != "reinstall" ]; then exec "$REAL" "$@"; fi',
                    'pkgs=""; seen=0',
                    'for a in "$@"; do',
                    '  if [ "$seen" = "1" ]; then case "$a" in -*) ;; *) pkgs="$pkgs $a";; esac',
                    '  elif [ "$a" = "install" ] || [ "$a" = "reinstall" ]; then seen=1; fi',
                    'done',
                    '[ -n "$pkgs" ] || exec "$REAL" "$@"',
                    'echo "[nexus-apt] proot dpkg cannot hardlink; installing via extract:$pkgs" >&2',
                    'rm -f /var/lib/dpkg/updates/* 2>/dev/null',
                    '/usr/bin/apt-get clean >/dev/null 2>&1',
                    'if ! /usr/bin/apt-get install -y -d $pkgs; then echo "[nexus-apt] download failed — try !apt-fix-dns" >&2; exit 1; fi',
                    'cd /var/cache/apt/archives 2>/dev/null || { echo "[nexus-apt] no archives dir" >&2; exit 1; }',
                    'n=0',
                    'for f in *.deb; do [ -f "$f" ] || continue; if dpkg-deb -x "$f" / 2>/dev/null; then n=$((n+1)); fi; done',
                    'ldconfig 2>/dev/null',
                    'echo "[nexus-apt] extracted $n package file(s) into the rootfs (no maintainer scripts ran)" >&2',
                    'exit 0',
                    '',
                ].join('\n');
                for (const nm of ['apt', 'apt-get']) {
                    const p = path.join(localBin, nm);
                    fs.writeFileSync(p, aptShim);
                    try { fs.chmodSync(p, 0o755); } catch (_) {}
                }
                log('[ubuntu-pty] apt/apt-get shim installed (dpkg-less install via extract)\n');
            } catch (e) { log('[ubuntu-pty] apt shim: ' + e.message + '\n'); }
            // MCP for the INTERACTIVE claude: the user types bare `claude`, which gets
            // NO --mcp-config (only print-mode's runMessage passes it), so it never sees
            // Exa/HTTP MCP. Fix: (1) (re)generate the guest MCP config from the current
            // Settings (writeProotMcpConfig → /root/.claude/mcp_guest_config.json), and
            // (2) drop a `claude` shell wrapper in /root/.bash_profile that injects
            // --mcp-config when that file exists. --mcp-config is appended AFTER "$@" so
            // the variadic option (inv 65b) can't swallow a positional prompt.
            try {
                writeProotMcpConfig(); // writes FILES_DIR/.claude/mcp_guest_config.json (HTTP servers) or removes it
                const bp = path.join(FILES_DIR, 'ubuntu', 'root', '.bash_profile');
                const wrapper =
                    '# Nexus: load defaults, then wrap `claude` to pick up MCP servers + preview nudge.\n' +
                    '[ -f ~/.bashrc ] && . ~/.bashrc\n' +
                    '# One-time apt bootstrap: if package lists are empty, update in background.\n' +
                    'if [ -f /tmp/apt_bootstrap.sh ]; then nohup /tmp/apt_bootstrap.sh >/dev/null 2>&1 & fi\n' +
                    '_NEXUS_SYS="After creating or editing HTML/CSS/JS files, always run: python3 -m http.server 5173 &\\nThis starts the preview server — the user can tap the Preview button in the app to see the result live."\n' +
                    'claude() {\n' +
                    '  if [ -f /root/.claude/mcp_guest_config.json ]; then\n' +
                    '    command /opt/node/bin/claude "$@" --append-system-prompt "$_NEXUS_SYS" --mcp-config /root/.claude/mcp_guest_config.json\n' +
                    '  else\n' +
                    '    command /opt/node/bin/claude "$@" --append-system-prompt "$_NEXUS_SYS"\n' +
                    '  fi\n' +
                    '}\n';
                fs.writeFileSync(bp, wrapper);
            } catch (e) { log('[ubuntu-pty] mcp wrapper: ' + e.message + '\n'); }
            // Interactive TUI auth — GATEWAY MODE (ref: Alishahryar1/free-claude-code).
            // The b38/b40 customApiKeyResponses seeding was the WRONG approach: setting
            // ANTHROPIC_API_KEY makes the interactive `claude` treat it as a "custom API
            // key" → "Detected a custom API key…" prompt → "Not logged in · Run /login"
            // and it refuses to send (the seed also EACCES'd). The fix: set
            // ANTHROPIC_AUTH_TOKEN instead — claude then runs in GATEWAY mode, just
            // sends `Authorization: Bearer <token>` to ANTHROPIC_BASE_URL and considers
            // itself authed, no login wall, no key prompt. Our proxy already accepts
            // `Bearer sk-ant-proxy000` (handleProxyRequest authHeader check). Do NOT set
            // ANTHROPIC_API_KEY here (it re-triggers the custom-key path).
            // CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY makes /model list via the gateway.
            const benv = buildEnv();
            const ptyEnv = { IS_SANDBOX: '1', DISABLE_AUTOUPDATER: '1', MCP_TIMEOUT: '30000', MCP_TOOL_TIMEOUT: '30000' };
            if (benv.ANTHROPIC_BASE_URL) {
                ptyEnv.ANTHROPIC_BASE_URL  = benv.ANTHROPIC_BASE_URL;
                ptyEnv.ANTHROPIC_AUTH_TOKEN = 'sk-ant-proxy000';
                ptyEnv.CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY = '1';
            }
            if (benv.ANTHROPIC_MODEL) ptyEnv.ANTHROPIC_MODEL = benv.ANTHROPIC_MODEL;
            let proc;
            try {
                proc = prootChild(['/bin/bash', '-li'], { pty: true, workspace, extraEnv: ptyEnv });
            } catch (e) {
                log('[ubuntu-pty] spawn failed: ' + e.message + '\n');
                try { socket.write('\r\n\x1b[31m[ubuntu shell spawn failed: ' + e.message + ']\x1b[0m\r\n'); } catch(_) {}
                return;
            }
            entry = { proc, socket, idleTimer: null };
            ubuntuPtys.set(sid, entry);
            proc.stdout.on('data', d => {
                const e2 = ubuntuPtys.get(sid);
                if (!e2 || !e2.socket) return;
                // Strip NUL bytes — meaningless in terminal output (xterm ignores them)
                // but the PTY/proot startup emits a few (the "^@" junk before the first
                // prompt). Cheap: only rebuild the buffer when a NUL is actually present.
                const out = (d.indexOf(0) !== -1) ? Buffer.from(d.filter(b => b !== 0)) : d;
                try { e2.socket.write(out); } catch(_) {}
            });
            proc.stderr.on('data', d => log('[ubuntu-pty] ' + d.toString().slice(0, 200)));
            proc.on('close', code => {
                const e2 = ubuntuPtys.get(sid);
                if (e2 && e2.socket) {
                    try { e2.socket.write('\r\n\x1b[2m[ubuntu shell exited (' + code + ') — switch back to start a new one]\x1b[0m\r\n'); } catch(_) {}
                }
                ubuntuPtys.delete(sid);
            });
            log('[ubuntu-pty] started sid=' + sid + ' cwd=' + workspace + '\n');
        }
        // Raw input relay: socket → bash stdin (libpty intercepts ESC 0xFE resize).
        socket.on('data', d => {
            const e2 = ubuntuPtys.get(sid);
            if (e2 && e2.proc && e2.proc.stdin.writable) { try { e2.proc.stdin.write(d); } catch(_) {} }
        });
        if (leftover && leftover.length) {
            try { if (entry.proc.stdin.writable) entry.proc.stdin.write(Buffer.from(leftover, 'binary')); } catch(_) {}
        }
        socket.on('close', () => {
            const e2 = ubuntuPtys.get(sid);
            if (e2 && e2.socket === socket) {
                e2.socket = null;
                // Keep the shell alive briefly for reattach; kill it if idle too long.
                e2.idleTimer = setTimeout(() => {
                    const e3 = ubuntuPtys.get(sid);
                    if (e3 && !e3.socket) { try { e3.proc.kill('SIGHUP'); } catch(_) {} ubuntuPtys.delete(sid); }
                }, PTY_IDLE_MS);
            }
        });
        socket.on('error', () => { try { socket.destroy(); } catch(_) {} });
    }

    // ── Dungeon dispatch (Scout / Dispatch / War Council) ───────────────────────
    // Mode 'dungeon': one JSON request line, then run claude --print spawn(s) with
    // cwd=room + an auditor/persona --append-system-prompt. The guest writes findings
    // into each room's library.md (the dungeon re-scans to render monsters). Status is
    // streamed back as JSON lines → DungeonActivity → window.onDungeonEvent.

    // ── DungeonPRD P1: Cartographer pre-pass (packRoom) ─────────────────────────
    // Pack a room's folder into ONE Tree-sitter-compressed map (~70% token cut)
    // BEFORE the Scout/Council models spawn, so each model reads one artifact
    // instead of crawling the tree (kills the inv-58 discovery/TPM friction —
    // proven viable by the !scout-pack P0 probe, b72). FILE-MODE: the map is written
    // to a guest /tmp path (clean scratch, NOT into the project — never pollute the
    // tree being audited); the Scout's task tells the model to Read it first. The
    // token count is captured for the P2 pre-flight gate (not used yet in P1).
    // Best-effort: any pack failure resolves { ok:false } and the caller silently
    // degrades to the old crawl-the-tree behavior (never blocks a dispatch).
    // repomix is cached in the rootfs after the first run, so a real room packs in
    // seconds — the one-time ~30MB npm fetch already happened during P0.
    function pathHash(s) { let h = 5381; for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) >>> 0; return h.toString(36); }
    function packRoom(targetCwd) {
        return new Promise((resolve) => {
            if (!targetCwd) return resolve({ ok:false, err:'no cwd' });
            const mapPath = '/tmp/nexus-scout-' + pathHash(String(targetCwd)) + '.xml';
            // --compress = Tree-sitter signature extraction. Leave it LOUD (no --quiet)
            // so the "Total Tokens" summary line survives for the P2 gate; tail to bound.
            // Pass 3 — filter engine: strip noise before packing so Claude's attention
            // goes to real code, not lockfiles/dist/vendor/sourcemaps.
            // Tests and manifests are KEPT — they reveal coverage gaps and dep CVEs.
            const FILTER_IGNORE = [
                'package-lock.json','yarn.lock','pnpm-lock.yaml','*.lock',
                'dist/**','build/**','.next/**','out/**','.nuxt/**','_output/**',
                '*.min.js','*.min.css','*.min.mjs',
                '*.map','*.d.ts',
                'vendor/**','third_party/**',
                '__pycache__/**','*.pyc','*.pyo',
                '.gradle/**','*.class','*.jar',
                'coverage/**','.nyc_output/**','storybook-static/**',
                '*.pb.go','*_generated.go','*_gen.go',
                'migrations/**/*.sql',   // keep schema files, skip raw migration blobs
            ].join(',');
            const cmd = [
                'set +e',
                'rm -f ' + mapPath,
                'npx -y repomix --compress --style xml --ignore ' + JSON.stringify(FILTER_IGNORE) + ' -o ' + mapPath + ' . 2>&1 | tail -25',
                'echo "::mapsize:: $(wc -c < ' + mapPath + ' 2>/dev/null || echo 0)"',
            ].join('\n');
            runProotGuest(['/bin/bash', '-lc', cmd], 120000, null, { workspace: targetCwd })
              .then(r => {
                const out = r.out || '';
                const tokens  = parseInt(((out.match(/Total Tokens:?\s*([\d,]+)/i) || [])[1] || '0').replace(/,/g,''), 10) || 0;
                const mapSize = parseInt((out.match(/::mapsize:: (\d+)/) || [])[1] || '0', 10);
                if (mapSize > 0) resolve({ ok:true, mapPath, tokens, bytes:mapSize });
                else resolve({ ok:false, mapPath, err: /\[timeout/.test(out) ? 'pack timed out' : 'pack produced no map' });
              })
              .catch(e => resolve({ ok:false, err: (e && e.message) || 'pack error' }));
        });
    }

    // ── DungeonPRD P2: pre-flight budget gate ───────────────────────────────────
    // The map exists to let EVERY model — especially weak / low-TPM ones — audit
    // without crawling the tree. So the gate is NEVER about denying a model the map;
    // it only picks the CHEAPEST way to deliver one that packed OK:
    //   ≤ INLINE_MAX  → inline the whole map into the prompt (zero Read round-trips —
    //                   the friendliest path for weak models: no tool dance, just read).
    //   >  INLINE_MAX  → file mode: model Reads the /tmp map (P1). Compressed, so still
    //                   far lighter than crawling; claude-code's Read paginates it.
    //   >  BIG          → still file mode, but the task tells the model to Read it in
    //                   sections (offset/limit) so a huge map can't blow one request.
    // A model is only ever left to crawl when packing actually FAILED. All tunable;
    // model-agnostic by design (no per-model branching).
    const P2_INLINE_MAX_TOKENS = 4000;
    const P2_BIG_MAP_TOKENS    = 20000;        // above → add "Read it in sections" hint
    const P2_INLINE_MAX_BYTES  = 60000;        // hard cap on inlined chars regardless
    function pickMapTier(tokens) {
        if (tokens && tokens > 0 && tokens <= P2_INLINE_MAX_TOKENS) return 'inline';
        return 'file';   // every successful pack still reaches the model — never crawl-abandoned
    }
    // Read back a (small) guest file for inlining. repomix is cached → this is a fast
    // `head -c` with no npx. Best-effort: empty string on any failure → caller falls
    // back to file mode.
    function readGuestFile(path, maxBytes) {
        return new Promise((resolve) => {
            runProotGuest(['/bin/bash', '-lc', 'head -c ' + (maxBytes || 60000) + ' ' + path + ' 2>/dev/null'], 30000, null, {})
              .then(r => resolve(r.out || '')).catch(() => resolve(''));
        });
    }

    // ── DungeonPRD P3: deterministic Hounds ─────────────────────────────────────
    // Scan a room (rg if present, else grep — always on the guest; !scout-hounds proved
    // 3/3 on grep) BEFORE any model spawns, so obvious issues become ZERO-TOKEN monsters
    // that appear no matter what the model does (the strongest weak-model lever — see
    // feedback-help-weak-models). Conservative, high-signal patterns ONLY (no monster
    // flood); findings are also injected into the scout task as candidates to VERIFY.
    // Floor-first: the baseline never depends on an installable tool. Best-effort.
    const HOUND_CAP = 40;
    function houndRoom(targetCwd) {
        return new Promise((resolve) => {
            if (!targetCwd) return resolve({ ok:false, findings:[], total:0, scanner:'' });
            const EXC = '--exclude-dir=.git --exclude-dir=node_modules --exclude-dir=build --exclude-dir=.gradle --exclude-dir=dist --exclude-dir=vendor --exclude-dir=.idea --exclude-dir=.next';
            const SECRET = 'password|passwd|secret|api[_-]?key|apikey|access[_-]?key|private[_-]?key|client[_-]?secret|auth[_-]?token';
            const DANGER = '\\beval\\(|child_process|os\\.system\\(|subprocess\\.(call|run|Popen)|Runtime\\.getRuntime\\(\\)\\.exec|\\bexec\\(';
            const MARK   = '\\b(TODO|FIXME|HACK|XXX)\\b';
            const cmd = [
                'set +e',
                'SCAN=$(command -v rg >/dev/null 2>&1 && echo rg || echo grep)',
                'echo "::scanner:: $SCAN"',
                'echo "::sec::"',
                'if [ "$SCAN" = rg ]; then rg -ni --no-heading -e "(' + SECRET + ')[[:space:]]*[=:]" . 2>/dev/null | head -' + HOUND_CAP + '; else grep -rniE ' + EXC + ' "(' + SECRET + ')[[:space:]]*[=:]" . 2>/dev/null | head -' + HOUND_CAP + '; fi',
                'echo "::danger::"',
                'if [ "$SCAN" = rg ]; then rg -n --no-heading -e "' + DANGER + '" . 2>/dev/null | head -' + HOUND_CAP + '; else grep -rnE ' + EXC + ' "' + DANGER + '" . 2>/dev/null | head -' + HOUND_CAP + '; fi',
                'echo "::marker::"',
                'if [ "$SCAN" = rg ]; then rg -n --no-heading -e "' + MARK + '" . 2>/dev/null | head -' + HOUND_CAP + '; else grep -rnE ' + EXC + ' "' + MARK + '" . 2>/dev/null | head -' + HOUND_CAP + '; fi',
                'echo "::end::"',
            ].join('\n');
            runProotGuest(['/bin/bash','-lc', cmd], 60000, null, { workspace: targetCwd })
              .then(r => {
                const out = r.out || '';
                const scanner = (out.match(/::scanner:: (\w+)/) || [])[1] || 'grep';
                const seg = (a, b) => { const m = out.match(new RegExp('::' + a + '::([\\s\\S]*?)::' + b + '::')); return m ? m[1] : ''; };
                const parse = (block, sev, mk) => {
                    const fnd = [];
                    String(block || '').split('\n').forEach(ln => {
                        if (/^Binary file/i.test(ln)) return;
                        const m = ln.match(/^\.?\/?(.+?):(\d+):(.*)$/);
                        if (!m) return;
                        const file = m[1].replace(/^\.\//, ''), lno = m[2], text = m[3].trim().slice(0, 80);
                        fnd.push({ file: file + ':' + lno, sev: sev, title: mk(text) });
                    });
                    return fnd;
                };
                let findings = []
                    .concat(parse(seg('sec', 'danger'),    'major', () => 'Possible hardcoded secret/credential'))
                    .concat(parse(seg('danger', 'marker'), 'major', t => 'Dangerous call: ' + (t.match(/eval|exec|child_process|os\.system|subprocess|getRuntime/i) || ['call'])[0]))
                    .concat(parse(seg('marker', 'end'),    'minor', t => ((t.match(/\b(TODO|FIXME|HACK|XXX)\b/i) || [])[1] || 'TODO').toUpperCase() + ' marker'));
                const total = findings.length;
                if (findings.length > HOUND_CAP) findings = findings.slice(0, HOUND_CAP);
                resolve({ ok: true, scanner, findings, total });
              })
              .catch(() => resolve({ ok:false, findings:[], total:0, scanner:'' }));
        });
    }

    const DEFAULT_SCOUT_TASK =
        'Audit this folder for REAL issues only, then write ./library.md. ' +
        'For each genuine bug also print a line `🪲BUG <file:line> <critical|major|minor> <title>` to stdout.';
    function scoutPersona(cwd) {
        const today = new Date().toISOString().slice(0, 10);
        return [
            'You are a Dungeon Scout auditing ONE folder of a software project.',
            'Your room (working directory): ' + cwd,
            '',
            'GOAL: find REAL problems only — bugs, crashes, security risks, broken logic, dead code.',
            'Do NOT invent issues. If the room is clean, write an empty Open Bugs list and say so.',
            'Use Read / Grep / Bash to inspect files. Be concise.',
            '',
            'You MUST create or update ./library.md in THIS folder, in EXACTLY this format:',
            '',
            '## 📖 Overview',
            '<one paragraph: what this folder does>',
            '',
            '## 🐛 Open Bugs',
            '- [ ] **<short title>** · `<file:line>` · <critical|major|minor> · logged ' + today + ' · src=scout',
            '',
            '## ✅ Resolved',
            '<preserve any existing resolved items>',
            '',
            'If ./library.md already exists: keep its Resolved section + overview, and reconcile Open Bugs',
            '(drop the ones that are now fixed, keep/add the ones still present).',
            '',
            'For EACH real bug ALSO print to stdout: `🪲BUG <file:line> <critical|major|minor> <title>`.',
            'BEFORE writing library.md, print ONE structured summary line to stdout:',
            '`📊REPORT PURPOSE=<one sentence> DANGER=<low|medium|high|critical> COMPLEXITY=<1-5> DEPENDS_ON=<folders> EXPOSES_TO=<folders>`',
            '(DEPENDS_ON and EXPOSES_TO are comma-separated folder names this room imports from / is imported by; use none if unknown.)',
            'When the library.md is written, stop.'
        ].join('\n');
    }
    // War Council member: audits the WHOLE project but writes NO files — only emits
    // markers (the tribunal consolidates + the dungeon writes the voted library.md).
    const COUNCIL_AUDIT_TASK =
        'Audit this WHOLE project for REAL issues. Do NOT write or edit ANY file. ' +
        'For each genuine bug print exactly one line: `🪲BUG <file:line> <critical|major|minor> <title>`.';
    // Deep Scout: ONE room (folder) per spawn, markers only — the dungeon writes the
    // per-room library.md + parent pointer. Ignore sub-folders (scouted separately).
    const DEEP_SCOUT_TASK =
        'Audit ONLY the files directly in THIS folder for REAL issues. Ignore sub-folders — they are scouted separately. ' +
        'Do NOT write or edit ANY file. For each genuine bug print exactly one line: `🪲BUG <file:line> <critical|major|minor> <title>`.';
    function deepScoutPersona(cwd) {
        return [
            'You are a Dungeon Scout auditing ONE folder (room) of a software project.',
            'Your room (working directory): ' + cwd,
            'GOAL: find REAL problems in THIS folder only — bugs, crashes, security risks, broken logic.',
            'Do NOT recurse into sub-folders (those are separate rooms, scouted on their own).',
            'Do NOT invent issues. Use Read / Glob / Bash to inspect. Be concise.',
            'Do NOT create or edit any file.',
            'Print ONE summary line: `📊REPORT PURPOSE=<one sentence> DANGER=<low|medium|high|critical> COMPLEXITY=<1-5> DEPENDS_ON=<folders> EXPOSES_TO=<folders>`',
            'Then for each real bug: `🪲BUG <file:line> <sev> <title>`',
            'When done, stop.',
            READONLY_SCOUT_SENTINEL
        ].join('\n');
    }
    function councilPersona() {
        return [
            'You are one member of a War Council auditing a software project.',
            'GOAL: independently find REAL problems — bugs, crashes, security risks, broken logic.',
            'Be rigorous and skeptical; do NOT invent issues to look productive.',
            'Use Read / Glob / Bash to inspect code. Cover as much of the project as you can.',
            'IMPORTANT: do NOT create or edit any file (no library.md). Your only output is markers.',
            'For EACH real bug print exactly: `🪲BUG <file:line> <critical|major|minor> <title>`.',
            'Also print ONE summary line: `📊REPORT PURPOSE=<one sentence> DANGER=<low|medium|high|critical> COMPLEXITY=<1-5> DEPENDS_ON=<folders> EXPOSES_TO=<folders>`',
            'When done, stop.',
            READONLY_SCOUT_SENTINEL
        ].join('\n');
    }
    // War Council Round 2 (deliberation): a finder defends its contested findings, then
    // the non-finders re-vote on the evidence + argument.
    const ARG_PERSONA = [
        'You are a code auditor defending bug findings you reported in this project.',
        'For EACH bug listed (id :: title :: file), READ the referenced file and write ONE short,',
        'concrete sentence arguing WHY it is a real bug — name the mechanism and the impact.',
        'No hedging, no preamble, no markdown. Do NOT write or edit any file.',
        'Output exactly one line per bug: 🗣ARG <id> <one sentence>.'
    ].join('\n');
    const VOTE_PERSONA = [
        'You are a code auditor on a review council. A fellow auditor flagged bugs you did not report,',
        'each with a short argument. For EACH (id :: title :: file :: argument), READ the file and judge',
        'the argument on the evidence:',
        '  for       — the argument is sound; it IS a real bug',
        '  against   — the argument is wrong / the code is actually fine',
        '  undecided — you cannot confirm either way',
        'Be evidence-based, not deferential. Do NOT write or edit any file.',
        'Output exactly one line per bug: 🗳VOTE <id> for|against|undecided.'
    ].join('\n');
    function attachDungeonSession(sid, socket, leftover) {
        let reqBuf = leftover ? Buffer.from(leftover, 'binary').toString() : '';
        let started = false;
        const procs = new Set();
        function send(obj) { try { socket.write(JSON.stringify(obj) + '\n'); } catch(_) {} }
        function killAll() { for (const p of procs) { try { p.kill('SIGTERM'); } catch(_) {} } procs.clear(); }
        socket.on('close', killAll);
        socket.on('error', () => { try { socket.destroy(); } catch(_) {} killAll(); });

        function gotRequest(reqLine) {
            if (started) return; started = true;
            let r; try { r = JSON.parse(reqLine); } catch (e) { send({ t:'error', msg:'bad request' }); try{socket.end();}catch(_){} return; }
            const op   = r.op || 'scout';
            // Quick query: return the provider's model list for the Scout/Council picker.
            if (op === 'models') {
                let list = [], current = '';
                try { const c = JSON.parse(fs.readFileSync(path.join(FILES_DIR,'bridge_config.json'),'utf8')); list = c.modelList || []; current = c.modelId || ''; } catch(_){}
                send({ t:'models', list:list, current:current });
                try { socket.end(); } catch(_){}
                return;
            }
            // ── Graph scan (Twin Lens Pass 0): run madge on the project root, return dep graph ──
            if (op === 'graph-scan') {
                send({ t:'graph-start' });
                const scanPath = r.cwd || cwd;
                // Use npx madge inside proot so it runs under the guest Node, same env as Claude.
                // --no-spinner keeps stdout clean (only JSON). 2>/dev/null drops madge's own warnings.
                const madgeCmd = 'cd ' + JSON.stringify(scanPath) +
                    ' && npx --yes madge --json --no-spinner . 2>/dev/null';
                let graphOut = '', errOut = '';
                let child;
                try { child = prootChild(['/bin/bash', '-c', madgeCmd], { workspace: scanPath }); }
                catch(e) { send({ t:'graph-error', err:'spawn: ' + e.message }); try{socket.end();}catch(_){} return; }
                procs.add(child);
                child.stdout.on('data', d => { graphOut += d.toString(); });
                child.stderr.on('data', d => { errOut += d.toString().slice(0, 200); });
                child.on('close', () => {
                    procs.delete(child);
                    try {
                        // madge emits the JSON dep map to stdout, strip any leading junk lines
                        const jsonStart = graphOut.indexOf('{');
                        const clean = jsonStart >= 0 ? graphOut.slice(jsonStart) : graphOut;
                        const graph = JSON.parse(clean.trim());
                        send({ t:'graph-data', graph:graph });
                    } catch(e) {
                        send({ t:'graph-error', err:'parse: ' + e.message + ' | raw: ' + graphOut.slice(0,120) });
                    }
                    try { socket.end(); } catch(_) {}
                });
                return;
            }
            // ── Hero pre-pass (GraphRAG-style): git log + 1 LLM call on specific file ──
            // Called by the dungeon BEFORE hero dispatch when the bug has a specific file.
            // Pass A (git history) + Pass C (semantic 1-shot) run in parallel; result returned
            // as {t:'prepass-data'} and the dungeon then fires the actual dispatch with heroContext.
            if (op === 'hero-prepass') {
                send({ t: 'prepass-start' });
                const ppFile = r.file || '';
                const ppBugTitle = r.bugTitle || '';
                const ppLine = r.line ? String(r.line) : '';
                const ppCwd = r.cwd || FILES_DIR;
                const ppBenv = buildEnv();

                (async () => {
                    // Pass A: git history for the specific file — free, no tokens
                    const gitPromise = ppFile
                        ? runProotGuest(['/bin/bash', '-c',
                              'git -C ' + JSON.stringify(ppCwd) + ' log --oneline -n 8 -- ' +
                              JSON.stringify(ppFile) + ' 2>/dev/null'], 20000, null)
                          .then(r2 => (r2.out || '').trim().slice(0, 600)).catch(() => '')
                        : Promise.resolve('');

                    // Pass C: 1 LLM call reading only the specific file — semantic understanding
                    const llmPromise = new Promise((resolve) => {
                        const ppTask = [
                            ppFile ? 'Read the file: ' + ppFile : 'Read the main file in this folder.',
                            'Answer each question concisely (one sentence each):',
                            '',
                            'PURPOSE: What does this file do in one sentence?',
                            ppLine
                                ? ('FUNCTION: What does the code near line ' + ppLine + ' do? What does it accept and return?')
                                : 'FUNCTION: What is the main function or class and what does it do?',
                            'CONTRACT: What do callers of this code rely on (return shape, side-effects, invariants)?',
                            'CAUSE: Given the bug "' + ppBugTitle + '", what is the single most likely root cause?',
                            '',
                            'Output ONLY these 4 labeled lines, nothing else:',
                            'PURPOSE: <answer>',
                            'FUNCTION: <answer>',
                            'CONTRACT: <answer>',
                            'CAUSE: <answer>',
                        ].join('\n');
                        const ppGuestEnv = {
                            ANTHROPIC_API_KEY: ppBenv.ANTHROPIC_API_KEY,
                            ANTHROPIC_MODEL: ppBenv.ANTHROPIC_MODEL,
                            DISABLE_AUTOUPDATER: '1', MCP_TIMEOUT: '30000', MCP_TOOL_TIMEOUT: '30000',
                            SHELL: '/bin/bash', IS_SANDBOX: '1',
                        };
                        if (ppBenv.ANTHROPIC_BASE_URL) ppGuestEnv.ANTHROPIC_BASE_URL = ppBenv.ANTHROPIC_BASE_URL;
                        const ppArgv = [GUEST_CLAUDE, '--output-format', 'stream-json', '--print',
                            '--dangerously-skip-permissions', '--verbose', ppTask];
                        let ppProc;
                        try { ppProc = prootChild(ppArgv, { extraEnv: ppGuestEnv, workspace: ppCwd }); }
                        catch(e) { resolve({ error: e.message }); return; }
                        procs.add(ppProc);
                        try { ppProc.stdin.end(); } catch(_) {}
                        let ppBuf = '';
                        ppProc.stdout.on('data', d => { ppBuf += d.toString(); });
                        ppProc.on('close', () => {
                            procs.delete(ppProc);
                            let allText = '';
                            ppBuf.split('\n').forEach(ln => {
                                if (!ln.trim()) return;
                                try {
                                    const ev2 = JSON.parse(ln);
                                    if (ev2.type === 'assistant' && ev2.message && Array.isArray(ev2.message.content))
                                        ev2.message.content.forEach(c => { if (c.type === 'text' && c.text) allText += c.text + '\n'; });
                                } catch(_) { allText += ln + '\n'; }
                            });
                            const field = (key) => { const m = allText.match(new RegExp(key + ':\\s*(.+)', 'i')); return m ? m[1].trim() : ''; };
                            resolve({
                                purpose: field('PURPOSE'),
                                functionSummary: field('FUNCTION'),
                                contract: field('CONTRACT'),
                                suspectedCause: field('CAUSE'),
                            });
                        });
                    });

                    const [recentChanges, semantic] = await Promise.all([gitPromise, llmPromise]);
                    send({ t: 'prepass-data',
                        purpose: semantic.purpose || '',
                        functionSummary: semantic.functionSummary || '',
                        contract: semantic.contract || '',
                        suspectedCause: semantic.suspectedCause || '',
                        recentChanges: recentChanges,
                    });
                    try { socket.end(); } catch(_) {}
                })().catch(e => {
                    send({ t: 'prepass-error', err: (e && e.message) || 'prepass failed' });
                    try { socket.end(); } catch(_) {}
                });
                return;
            }
            const cwd  = r.cwd || FILES_DIR;
            // Divide mode: each member audits its OWN assigned room (per-member cwd+model).
            const assignments = Array.isArray(r.assignments) ? r.assignments.filter(a => a && a.cwd) : [];
            const isDivide = (op === 'council') && (r.mode === 'divide') && assignments.length > 0;
            const isCouncil = (op === 'council') || (r.mode === 'council') || isDivide;
            const isDeep = (op === 'scout') && (r.mode === 'deep');   // one room per spawn, markers only
            // chosen models: council uses r.models[] (one per member); solo/dispatch uses r.model
            const chosen = Array.isArray(r.models) ? r.models.filter(Boolean) : [];
            let models = [];
            try { const cfg = JSON.parse(fs.readFileSync(path.join(FILES_DIR,'bridge_config.json'),'utf8')); models = (cfg.modelList||[]).slice(); } catch(_){}
            if (chosen.length) models = chosen.slice();
            const members = isDivide ? Math.min(4, assignments.length)
                : isCouncil ? (chosen.length ? Math.min(4, chosen.length) : Math.max(2, Math.min(4, parseInt(r.members,10) || 2)))
                : 1;
            const benv = buildEnv();

            // ── War Council Round 2: deliberation ──
            // Sub-majority findings (2/4, 1/4) didn't reach independent consensus. Each finder
            // argues its findings (Pass 1); the non-finders read the argument + re-vote (Pass 2).
            // The dungeon re-tallies (FOR raises support) and re-tiers; true stalemates fall to
            // the King in Round 3. Cost is bounded at ~2× members spawns (batched per member),
            // independent of the number of findings, and only contested findings get here.
            if (op === 'deliberate') {
                const dfindings = Array.isArray(r.findings) ? r.findings : [];
                const dmodels   = Array.isArray(r.models) ? r.models : [];
                send({ t:'delib-start', count: dfindings.length });
                const mkEnv = (model) => {
                    const e = {
                        ANTHROPIC_API_KEY: benv.ANTHROPIC_API_KEY,
                        ANTHROPIC_MODEL:   model || benv.ANTHROPIC_MODEL,
                        DISABLE_AUTOUPDATER:'1', MCP_TIMEOUT:'30000', MCP_TOOL_TIMEOUT:'30000',
                        SHELL:'/bin/bash', IS_SANDBOX:'1',
                    };
                    if (benv.ANTHROPIC_BASE_URL) e.ANTHROPIC_BASE_URL = benv.ANTHROPIC_BASE_URL;
                    return e;
                };
                // One guest spawn; each assistant text block is fed to onText. ALWAYS resolves —
                // on close OR on a hard timeout (a hung spawn must never stall the whole round).
                const DELIB_SPAWN_MS = 90000;
                const runSpawn = (model, persona, task, onText) => new Promise((resolve) => {
                    const argv = [GUEST_CLAUDE, '--output-format','stream-json','--print',
                        '--dangerously-skip-permissions','--append-system-prompt', persona, '--verbose', task];
                    let p;
                    try { p = prootChild(argv, { extraEnv: mkEnv(model), workspace: cwd }); }
                    catch(e){ resolve(); return; }
                    procs.add(p); try { p.stdin.end(); } catch(_){}
                    let done=false;
                    const finish=()=>{ if(done) return; done=true; clearTimeout(to); procs.delete(p); resolve(); };
                    const to=setTimeout(()=>{ log('[delib] spawn timeout, killing\n'); try{ p.kill('SIGTERM'); }catch(_){} finish(); }, DELIB_SPAWN_MS);
                    let buf='';
                    p.stdout.on('data', d => {
                        buf += d.toString(); let nl;
                        while ((nl = buf.indexOf('\n')) !== -1) {
                            const line = buf.slice(0,nl); buf = buf.slice(nl+1); if (!line.trim()) continue;
                            try { const ev = JSON.parse(line); if (ev.type==='assistant' && ev.message && Array.isArray(ev.message.content)) ev.message.content.forEach(c => { if (c.type==='text' && c.text) onText(c.text); }); }
                            catch(_) { onText(line); }
                        }
                    });
                    p.stderr.on('data', d => log('[delib] ' + d.toString().slice(0,120) + '\n'));
                    p.on('close', finish);
                    p.on('error', finish);
                });
                (async () => {
                    // Pass 1 — each finder argues its contested findings (members run in parallel).
                    const byFinder = {};
                    dfindings.forEach(f => { const k = f.finder; (byFinder[k]=byFinder[k]||[]).push(f); });
                    const argMap = {};
                    await Promise.all(Object.keys(byFinder).map(k => {
                        const grp = byFinder[k], model = dmodels[k] || '';
                        const listTxt = grp.map(f => f.id + ' :: ' + f.title + ' :: ' + (f.file || '?')).join('\n');
                        const task = 'Defend these bug(s) you reported in this project:\n\n' + listTxt;
                        return runSpawn(model, ARG_PERSONA, task, txt => {
                            const re = /🗣ARG\s+(\S+)\s+(.+)/g; let m;
                            while ((m = re.exec(txt))) {
                                const id = m[1], a = (m[2]||'').trim();
                                if (a && !argMap[id]) { argMap[id] = a; send({ t:'delib-arg', id:id, member:Number(k), text:a.slice(0,200) }); }
                            }
                        });
                    }));
                    // Pass 2 — each non-finder re-votes with the argument in hand (members run in parallel).
                    const byVoter = {};
                    dfindings.forEach(f => (f.voters||[]).forEach(v => { (byVoter[v]=byVoter[v]||[]).push(f); }));
                    await Promise.all(Object.keys(byVoter).map(v => {
                        const grp = byVoter[v], model = dmodels[v] || '';
                        const listTxt = grp.map(f => f.id + ' :: ' + f.title + ' :: ' + (f.file || '?') + ' :: ' + (argMap[f.id] || '(no argument given)')).join('\n');
                        const task = 'A fellow council member argues these findings are real bugs. Judge each on the evidence:\n\n' + listTxt;
                        return runSpawn(model, VOTE_PERSONA, task, txt => {
                            const re = /🗳VOTE\s+(\S+)\s+(for|against|undecided)/gi; let m;
                            while ((m = re.exec(txt))) send({ t:'delib-vote', id:m[1], member:Number(v), verdict:m[2].toLowerCase() });
                        });
                    }));
                    send({ t:'delib-done' });
                    try { socket.end(); } catch(_){}
                })().catch(e => { send({ t:'delib-done', error:(e&&e.message)||'deliberation failed' }); try{socket.end();}catch(_){} });
                return;
            }

            const DIVIDE_AUDIT_TASK =
                'Audit THIS folder (your assigned area) for REAL issues. Do NOT write or edit ANY file. ' +
                'For each genuine bug print exactly one line: `🪲BUG <file:line> <critical|major|minor> <title>`.';
            const persona = (op === 'dispatch' && r.persona) ? r.persona
                          : isDeep ? deepScoutPersona(cwd)
                          : isCouncil ? councilPersona()
                          : scoutPersona(cwd);
            const task = r.task || (op === 'dispatch' ? 'Do the assigned work in this folder.'
                          : isDeep ? DEEP_SCOUT_TASK
                          : isDivide ? DIVIDE_AUDIT_TASK
                          : isCouncil ? COUNCIL_AUDIT_TASK
                          : DEFAULT_SCOUT_TASK);
            log('[dungeon] op=' + op + (isDivide ? ' (divide)' : isDeep ? ' (deep)' : '') + ' members=' + members + ' cwd=' + cwd + '\n');

            let doneCount = 0;
            const memberDone = [];                 // once-only guard per member
            const memberErr = {};                  // idx → captured death cause (429 / error / etc.)
            const cwdToMap = {};                   // P1: room cwd → packRoom result (map for the Scout to Read)
            const cwdToHound = {};                  // P3: room cwd → houndRoom result (deterministic candidates)
            // Per-member watchdogs so ONE hung/stuck member can't block all-done (→ no
            // King's Verdict / vote scene ever). STALL = max silence after warm-up; ABS = hard cap.
            const MEMBER_STALL_MS = 120000;        // 2 min of no stdout → assume hung
            const MEMBER_ABS_MS   = 360000;        // 6 min total → hard stop
            const memberTimers = {};               // idx → { stall, abs, proc }
            function clearMemberTimers(idx) {
                const t = memberTimers[idx]; if (!t) return;
                clearTimeout(t.stall); clearTimeout(t.abs); delete memberTimers[idx];
            }
            // Pull a readable cause out of error text (stderr / result / API error lines).
            function noteErr(idx, text) {
                if (!text || memberErr[idx]) return;
                const s = String(text);
                let m = s.match(/\b(429|529|503|502|500|401|403)\b/);
                if (m) { memberErr[idx] = m[1] + (/rate.?limit|overload|quota/i.test(s) ? ' — rate limited' : m[1]==='401'||m[1]==='403' ? ' — auth rejected' : ' error'); return; }
                m = s.match(/(rate.?limit|overloaded|quota exceeded|too many requests|insufficient|unauthor\w*|forbidden|context length|api error[^\n]{0,40})/i);
                if (m) { memberErr[idx] = m[0].replace(/\s+/g,' ').trim().slice(0,70); return; }
            }
            function finishMember(idx, code, reason) {
                if (memberDone[idx]) return;        // guard: close + timeout can both fire
                memberDone[idx] = true;
                clearMemberTimers(idx);
                const ok = (code === 0);
                const why = ok ? '' : (reason || memberErr[idx] || (code === -2 ? 'no response — timed out' : 'fell silent (exit ' + code + ')'));
                send({ t:'done', member:idx, code:code, ok:ok, reason:why });
                if (++doneCount >= members) { send({ t:'all-done' }); try { socket.end(); } catch(_){} }
            }
            function killMember(idx, why) {
                const t = memberTimers[idx];
                log('[dungeon] m' + idx + ' ' + why + ' → force-finishing\n');
                if (t && t.proc) { try { t.proc.kill('SIGTERM'); } catch(_){} }
                finishMember(idx, -2);             // reason falls back to memberErr (e.g. 429) or "no response — timed out"
            }
            function scanBugs(text, idx) {
                const re = /🪲BUG\s+(\S+)\s+(critical|major|minor)\s+(.+)/gi; let m;
                while ((m = re.exec(text))) send({ t:'bug', member:idx, file:m[1], sev:m[2].toLowerCase(), title:m[3].trim().slice(0,120) });
            }
            // Pass 6: parse 📊REPORT structured line emitted by the scout persona
            function scanReport(text, idx) {
                const m = text.match(/📊REPORT\s+(.+)/);
                if (!m) return;
                const raw = m[1];
                function field(key) {
                    const fm = raw.match(new RegExp(key + '=([^\s]+(?:\s+[^A-Z=\s][^=\s]*)*)', 'i'));
                    return fm ? fm[1].replace(/[,\s]+$/, '').trim() : '';
                }
                const purpose    = field('PURPOSE');
                const danger     = (field('DANGER') || 'low').toLowerCase();
                const complexity = parseInt(field('COMPLEXITY'), 10) || 1;
                const dependsOn  = field('DEPENDS_ON').split(',').map(x => x.trim()).filter(Boolean);
                const exposesTo  = field('EXPOSES_TO').split(',').map(x => x.trim()).filter(Boolean);
                send({ t:'report', member:idx, purpose, danger, complexity, dependsOn, exposesTo });
            }
            function runMember(idx) {
                // divide → per-member assigned room+model; council → models[idx]; solo/dispatch → r.model
                const memberCwd = isDivide ? (assignments[idx].cwd || cwd) : cwd;
                let memberModel = benv.ANTHROPIC_MODEL;
                if (isDivide) memberModel = assignments[idx].model || benv.ANTHROPIC_MODEL;
                else if (isCouncil && models.length) memberModel = models[idx % models.length];
                else if (r.model) memberModel = r.model;
                const guestEnv = {
                    ANTHROPIC_API_KEY:   benv.ANTHROPIC_API_KEY,
                    ANTHROPIC_MODEL:     memberModel,
                    DISABLE_AUTOUPDATER: '1', MCP_TIMEOUT: '30000', MCP_TOOL_TIMEOUT: '30000',
                    SHELL: '/bin/bash', IS_SANDBOX: '1',
                };
                if (benv.ANTHROPIC_BASE_URL) guestEnv.ANTHROPIC_BASE_URL = benv.ANTHROPIC_BASE_URL;
                // Cartographer map (P1 packed it, P2 chose how to deliver it). Always give
                // the model the map when it packed OK — inline for small (no Read needed,
                // weak-model friendly), else as a file to Read. Degrades to the plain task
                // (crawl) only when packing FAILED / non-audit op.
                let memberTask = task;
                const mp = cwdToMap[memberCwd];
                if (mp && mp.ok && mp.tier === 'inline' && mp.content) {
                    memberTask = 'Below is a COMPRESSED STRUCTURAL MAP of this entire folder (every file with ' +
                        'its signatures and key code, Tree-sitter compressed). Use it to jump straight to ' +
                        'suspicious spots — do NOT run ls/glob or crawl the tree. Read only the specific source ' +
                        'files you need to confirm a real issue, then:\n\n' + task +
                        '\n\n===== FOLDER MAP START =====\n' + mp.content + '\n===== FOLDER MAP END =====\n';
                } else if (mp && mp.ok) {
                    const bigHint = (mp.tokens > P2_BIG_MAP_TOKENS)
                        ? ' The map is large — Read it in sections (use the Read tool\'s offset/limit) rather ' +
                          'than all at once, so you never pull the whole thing into one request.'
                        : '';
                    memberTask = 'A COMPRESSED STRUCTURAL MAP of this entire folder (every file with its ' +
                        'signatures and key code, Tree-sitter compressed) has already been generated at ' +
                        mp.mapPath + '. Read THAT ONE FILE FIRST to learn the layout — do NOT run ls/glob ' +
                        'or crawl the directory tree to discover files.' + bigHint + ' Use the map to jump straight ' +
                        'to suspicious spots, Read only those specific source files to confirm a real issue, then:\n\n' + task;
                }
                // P3: a deterministic pre-scan already flagged candidates — hand them to the
                // model to VERIFY (helps weak models get a real head-start; false positives
                // are theirs to drop). Prepended so it sits ahead of the audit instructions.
                const hd = cwdToHound[memberCwd];
                if (hd && hd.findings && hd.findings.length) {
                    const list = hd.findings.slice(0, 25).map(f => '- ' + f.file + ' · ' + f.sev + ' · ' + f.title).join('\n');
                    memberTask = 'A deterministic pre-scan (' + (hd.scanner || 'grep') + ') flagged these CANDIDATE issues ' +
                        'in this folder. VERIFY each against the actual source — some are false positives, keep only the ' +
                        'real ones — and ALSO find problems a regex scan cannot (logic errors, races, bad error handling, ' +
                        'security flaws):\n' + list + '\n\n' + memberTask;
                }
                // Twin Lens Pass 4: inject graph context (centrality, cycles, blast radius)
                // when the dungeon has already computed a graph for this project.
                // The two lenses COMBINE to support each other: the graph says WHERE to
                // look (priority by centrality/cycles/blast), the repomix map says WHAT is
                // there. When both are present, explicitly tell the scout to use the graph
                // ranking to navigate the map — that's why it's called "Twin Lens".
                if (r.graphContext) {
                    const mapBridge = (mp && mp.ok)
                        ? ' Use this ranking to decide WHERE to look first in the structural map below: ' +
                          'start with the highest-centrality, cyclic, and high-blast-radius files, then ' +
                          'use the map to jump straight to their code.'
                        : '';
                    memberTask = 'DEPENDENCY GRAPH CONTEXT (pre-computed, use to prioritise your audit):\n' +
                        r.graphContext + mapBridge + '\n\n' + memberTask;
                }
                // Hero briefing: inject semantic pre-pass context for dispatch ops.
                if (r.heroContext && op === 'dispatch') {
                    memberTask = 'HERO BRIEFING (pre-computed intelligence — target your fix here):\n' +
                        r.heroContext + '\n\n' + memberTask;
                }
                const argv = [GUEST_CLAUDE, '--output-format', 'stream-json', '--print',
                    '--dangerously-skip-permissions', '--append-system-prompt', persona, '--verbose', memberTask];
                let proc;
                try { proc = prootChild(argv, { extraEnv: guestEnv, workspace: memberCwd }); }
                catch (e) { send({ t:'start', member:idx, error:e.message }); finishMember(idx, -1, 'failed to start: ' + (e.message||'').slice(0,50)); return; }
                procs.add(proc);
                try { proc.stdin.end(); } catch(_){}
                send({ t:'start', member:idx, model: guestEnv.ANTHROPIC_MODEL || '', cwd: memberCwd });
                // arm watchdogs: stall (reset on every stdout) + absolute cap
                memberTimers[idx] = { proc: proc,
                    stall: setTimeout(() => killMember(idx, 'stalled (no output ' + (MEMBER_STALL_MS/1000) + 's)'), MEMBER_STALL_MS),
                    abs:   setTimeout(() => killMember(idx, 'exceeded ' + (MEMBER_ABS_MS/1000) + 's cap'), MEMBER_ABS_MS) };
                let lineBuf = '';
                proc.stdout.on('data', d => {
                    const tm = memberTimers[idx];       // bump the stall timer — member is alive
                    if (tm) { clearTimeout(tm.stall); tm.stall = setTimeout(() => killMember(idx, 'stalled (no output ' + (MEMBER_STALL_MS/1000) + 's)'), MEMBER_STALL_MS); }
                    lineBuf += d.toString();
                    let nl;
                    while ((nl = lineBuf.indexOf('\n')) !== -1) {
                        const line = lineBuf.slice(0, nl); lineBuf = lineBuf.slice(nl + 1);
                        if (!line.trim()) continue;
                        try {
                            const ev = JSON.parse(line);
                            if (ev.type === 'assistant' && ev.message && Array.isArray(ev.message.content)) {
                                ev.message.content.forEach(c => {
                                    if (c.type === 'tool_use') {
                                        const ti = c.input || {};
                                        const tp = ti.file_path || ti.path || ti.notebook_path || ti.pattern || '';
                                        send({ t:'tool', member:idx, name:c.name, path: (typeof tp === 'string' ? tp : '') });
                                    }
                                    if (c.type === 'text' && c.text) { scanBugs(c.text, idx); scanReport(c.text, idx); if (/api error|rate.?limit|\b429\b|overloaded/i.test(c.text)) noteErr(idx, c.text); }
                                });
                            } else if (ev.type === 'result') {
                                if (ev.is_error || (ev.subtype && ev.subtype !== 'success')) noteErr(idx, (ev.subtype || 'error') + ' ' + (typeof ev.result === 'string' ? ev.result : ''));
                                send({ t:'result', member:idx });
                            }
                        } catch(_) { scanBugs(line, idx); scanReport(line, idx); }
                    }
                });
                proc.stderr.on('data', d => { const s = d.toString(); log('[dungeon] m' + idx + ' ' + s.slice(0,160) + '\n'); noteErr(idx, s); });
                proc.on('close', code => { procs.delete(proc); finishMember(idx, code, null); });
            }
            send({ t:'dispatch-start', op:op, members:members, cwd:cwd });
            // ── P1 Cartographer pre-pass: pack each DISTINCT room cwd once, then spawn ──
            // Audit ops only (scout incl. deep + council incl. divide). 'dispatch' (a hero
            // doing fix work) and 'judge' keep their existing flows — no map. Packs run
            // sequentially (repomix is cached → fast; avoids 4 concurrent npx). A failed
            // pack just leaves cwdToMap[cwd] unset → that member crawls as before.
            const doPack = (op === 'scout' || op === 'council');
            if (!doPack) { for (let i = 0; i < members; i++) runMember(i); return; }
            const packCwds = isDivide
                ? Array.from(new Set(assignments.slice(0, members).map(a => a.cwd || cwd)))
                : [cwd];
            send({ t:'packing', rooms: packCwds.length });
            // P3: emit zero-token hound monsters only where the frontend writes monsters
            // from markers AND there's no consensus vote to inflate — plain Deep Scout and
            // Divide. Council (consensus/roam, flagged r.roam) gets the hound findings via
            // task injection instead, so grep never counts as "a model that voted".
            const emitHoundMarkers = (isDeep || isDivide) && !r.roam;
            (async () => {
                for (const pc of packCwds) {
                    const res = await packRoom(pc);
                    if (res.ok) {
                        res.tier = pickMapTier(res.tokens);
                        if (res.tier === 'inline') {
                            res.content = await readGuestFile(res.mapPath, P2_INLINE_MAX_BYTES);
                            if (!res.content) res.tier = 'file';   // couldn't read back → Read the file instead
                        }
                    }
                    cwdToMap[pc] = res;
                    send({ t:'packed', cwd:pc, ok:res.ok, tokens:res.tokens || 0, bytes:res.bytes || 0, tier:res.tier || '', err:res.err || '' });
                    log('[dungeon] pack ' + pc + ' → ' + (res.ok ? ('ok ~' + res.tokens + 'tok [' + (res.tier||'') + '], ' + res.bytes + 'b')
                        : ('FAILED: ' + res.err + ' — degrading to crawl')) + '\n');
                    // ── P3 hound scan (deterministic, zero model tokens) ──
                    const hres = await houndRoom(pc);
                    cwdToHound[pc] = hres;
                    if (hres.ok && hres.findings.length) {
                        if (emitHoundMarkers) {
                            const mem = isDivide ? Math.max(0, assignments.findIndex(a => (a.cwd || cwd) === pc)) : 0;
                            hres.findings.forEach(f => send({ t:'bug', member:mem, file:f.file, sev:f.sev, title:f.title, src:'hound' }));
                        }
                        send({ t:'hounds', cwd:pc, count:hres.findings.length, total:hres.total, scanner:hres.scanner, markers:emitHoundMarkers });
                        log('[dungeon] hounds ' + pc + ' → ' + hres.findings.length + (hres.total > hres.findings.length ? ('/' + hres.total) : '') +
                            ' [' + hres.scanner + ']' + (emitHoundMarkers ? ' (markers)' : ' (task-only)') + '\n');
                    }
                }
                for (let i = 0; i < members; i++) runMember(i);
            })();
        }

        const nl0 = reqBuf.indexOf('\n');
        if (nl0 !== -1) gotRequest(reqBuf.slice(0, nl0));
        else socket.on('data', d => {
            reqBuf += d.toString();
            const nl = reqBuf.indexOf('\n');
            if (nl !== -1) gotRequest(reqBuf.slice(0, nl));
        });
    }

    // ── TCP server ────────────────────────────────────────────────────────────
    const server = net.createServer(rawSocket => {
        let hdrBuf = '';
        function onHeader(d) {
            hdrBuf += d.toString();
            const nl = hdrBuf.indexOf('\n');
            if (nl === -1) { rawSocket.once('data', onHeader); return; }
            const firstLine = hdrBuf.slice(0, nl).trim();
            const leftover  = hdrBuf.slice(nl + 1);
            hdrBuf = '';
            rawSocket.removeListener('data', onHeader);
            // Require SESSION:<sid>:<token>[:<mode>] header — reject anything else.
            // mode (optional, P6): 'ubuntu' → live PTY shell; default/absent → chat.
            if (!firstLine.startsWith('SESSION:')) {
                log('[reject] no SESSION: prefix; firstLine="' + firstLine.slice(0, 40) + '"\n');
                try { rawSocket.write('\r\n\x1b[31mUnauthorized connection rejected.\x1b[0m\r\n'); rawSocket.end(); } catch(_) {}
                return;
            }
            const parts = firstLine.slice(8).split(':');
            const sid   = parts[0] || '0';
            const token = parts[1] || '';
            const mode  = parts[2] || 'chat';
            let expectedToken = '';
            let tokenErr = '';
            try { expectedToken = fs.readFileSync(path.join(FILES_DIR, 'local_token'), 'utf8').trim().slice(0, 200); } catch(e) { tokenErr = e.message; }
            // Reject if token missing/empty OR if presented token does not match.
            // An empty expectedToken must never match anything — reject all.
            if (!expectedToken || token !== expectedToken) {
                // Diagnostic (b38): pinpoint WHY a PTY/chat socket is rejected — empty
                // expected (read race/perm), empty received (Kotlin read fail), or true
                // mismatch. Logs lengths + 6-char prefixes only (never the full secret).
                log('[reject] mode=' + mode + ' sid=' + sid +
                    ' recvLen=' + token.length + ' recv6=' + token.slice(0, 6) +
                    ' expLen=' + expectedToken.length + ' exp6=' + expectedToken.slice(0, 6) +
                    (tokenErr ? ' readErr=' + tokenErr : '') + '\n');
                try { rawSocket.write('\r\n\x1b[31mUnauthorized connection rejected.\x1b[0m\r\n'); rawSocket.end(); } catch(_) {}
                return;
            }
            if (mode === 'ubuntu')       attachPtySession(sid, rawSocket, leftover);
            else if (mode === 'dungeon') attachDungeonSession(sid, rawSocket, leftover);
            else                         attachSession(sid, rawSocket, leftover);
        }
        rawSocket.once('data', onHeader);
    });

    server.on('error', err => {
        process.stderr.write('Print bridge error: ' + err.message + '\n');
        setTimeout(openPrintSession, 3000);
    });

    server.listen(PORT, HOST, () => {
        log('Print Bridge ready on ' + HOST + ':' + PORT + '\n');
    });
}

// Mark a project directory as trusted in ~/.claude.json so claude-code skips the
// "do you trust this folder?" prompt without CLAUDE_CODE_SANDBOXED (which also
// sandboxed the Bash tool — see Known gaps). claude-code keys trust per absolute
// cwd under projects[cwd]. Merges into the existing file so other claude-code
// state (history flags, etc.) is preserved. Best-effort: any failure is logged,
// not fatal (worst case the trust prompt reappears, which the log will show).
function ensureProjectTrusted(cwd) {
    try {
        const cjPath = path.join(FILES_DIR, '.claude.json');
        let cj = {};
        try { cj = JSON.parse(fs.readFileSync(cjPath, 'utf8')) || {}; } catch (_) {}
        if (typeof cj !== 'object' || cj === null) cj = {};
        if (!cj.projects || typeof cj.projects !== 'object') cj.projects = {};
        for (const dir of [cwd, FILES_DIR]) {
            const p = cj.projects[dir] || {};
            p.hasTrustDialogAccepted = true;
            p.hasCompletedProjectOnboarding = true;
            cj.projects[dir] = p;
        }
        // Top-level onboarding flags some claude-code builds also gate on.
        cj.hasCompletedOnboarding = true;
        cj.bypassPermissionsModeAccepted = true;
        fs.writeFileSync(cjPath, JSON.stringify(cj));
    } catch (e) {
        log('[trust] ensureProjectTrusted failed: ' + e.message + '\n');
    }
}

function startBridgeServer() {
    // Start proxy first — port 8082 must be listening before Claude Code spawns,
    // otherwise its first API call gets ECONNREFUSED and it exits immediately.
    startProxyServer(() => {
        openPrintSession();
        // Load MCP servers after the bridge is up (non-blocking)
        loadMcpStdioServers().catch(e => log('[mcp-stdio] startup error: ' + e.message + '\n'));
        loadMcpHttpServers().catch(e => log('[mcp-http] startup error: ' + e.message + '\n'));
    });
}

// ─── Entry point ──────────────────────────────────────────────────────────────

// Ensure custom slash commands directory exists (HOME=FILES_DIR so claude
// reads commands from FILES_DIR/.claude/commands/*.md on every run).
try { fs.mkdirSync(path.join(FILES_DIR, '.claude', 'commands'), { recursive: true }); } catch(_) {}

// Pre-create/patch claude settings so the theme/onboarding picker never appears.
// P4: the customApiKeyResponses approved-list seeding is GONE — it was the legacy
// interactive-login workaround, and patchSettings() now actively deletes the key
// every turn (the proxy itself accepts sk-ant-proxy000; the guest never logs in).
const claudeSettingsPath = path.join(FILES_DIR, '.claude', 'settings.json');
try {
    fs.mkdirSync(path.join(FILES_DIR, '.claude'), { recursive: true });
    let s = {};
    try { s = JSON.parse(fs.readFileSync(claudeSettingsPath, 'utf8')); } catch (_) {}
    s.theme                  = 'dark'; // always force — conditional keeps broken values
    s.hasCompletedOnboarding = true;
    s.hasShownWelcome        = true;
    s.skipWelcome            = true;
    s.autoUpdaterStatus      = 'disabled';
    s.preferredNotifChannel  = s.preferredNotifChannel || 'none';
    fs.writeFileSync(claudeSettingsPath, JSON.stringify(s, null, 2));
} catch (_) {}

// P4: the legacy 2.1.112 libnode install path is DELETED. The proot engine ships
// its own claude-code in the Ubuntu rootfs (provisioned by !setup-engine /
// UbuntuRootfsManager), so the bridge always just starts its servers — no
// libnode-side cli.js install check, no \p{} patching, no sub-agent wrappers.
// Auto-detect the REAL installed claude-code version → filesDir/claude_version, so
// Settings → About shows the actual version (and follows npm upgrades) instead of a
// hardcoded constant. Reads the guest's package.json directly — no guest spawn.
try {
    const ccCandidates = [
        path.join(FILES_DIR, 'ubuntu', 'opt', 'node', 'lib', 'node_modules', '@anthropic-ai', 'claude-code', 'package.json'),
        path.join(FILES_DIR, 'ubuntu', 'usr', 'lib', 'node_modules', '@anthropic-ai', 'claude-code', 'package.json'),
        path.join(FILES_DIR, 'ubuntu', 'usr', 'local', 'lib', 'node_modules', '@anthropic-ai', 'claude-code', 'package.json'),
    ];
    for (const c of ccCandidates) {
        try {
            const pkg = JSON.parse(fs.readFileSync(c, 'utf8'));
            if (pkg && pkg.version) {
                fs.writeFileSync(path.join(FILES_DIR, 'claude_version'), String(pkg.version).trim());
                log('[version] claude-code ' + pkg.version + ' (from ' + c + ')\n');
                break;
            }
        } catch (_) {}
    }
} catch (_) {}

log('Starting bridge server (proot engine).\n');
try { fs.writeFileSync(SETUP_DONE, 'true'); } catch (_) {}
startBridgeServer();

// ─── First-run auto-provisioning watcher ──────────────────────────────────────
// Kotlin (SetupActivity) extracts the Ubuntu rootfs (it owns xz/tar; node can't),
// then drops a `provision_requested` marker. We run the SHARED install chain
// (runEngineSetup) and report progress to setup.log (SetupActivity polls it) +
// completion markers `engine_provisioned` / `provision_failed` for its gate.
// Same debugged chain as !setup-engine, just file-triggered instead of typed.
const PROVISION_REQ  = path.join(FILES_DIR, 'provision_requested');
const PROVISION_OK   = path.join(FILES_DIR, 'engine_provisioned');
const PROVISION_FAIL = path.join(FILES_DIR, 'provision_failed');
let _provisioning = false;
function checkProvisionRequest() {
    if (_provisioning) return;
    if (!fs.existsSync(PROVISION_REQ)) return;
    _provisioning = true;
    try { fs.unlinkSync(PROVISION_REQ); } catch (_) {}
    try { fs.unlinkSync(PROVISION_FAIL); } catch (_) {}
    // setup.log is SetupActivity's progress channel — reset it for a clean run.
    try { fs.writeFileSync(SETUP_LOG, ''); } catch (_) {}
    const plog = (s) => { try { fs.appendFileSync(SETUP_LOG, s + '\n'); } catch (_) {} };
    // Structured lines SetupActivity parses: "[provision] pct=NN <TAG> <msg>".
    const emit = ({ level, msg, stage, pct }) => {
        const tag = level === 'err' ? 'ERR' : level === 'ok' ? 'OK'
                  : level === 'done' ? 'DONE' : level === 'stage' ? 'STAGE' : '..';
        plog('[provision]' + (pct != null ? ' pct=' + pct : '') + ' ' + tag + ' ' + String(msg).replace(/\n/g, ' '));
    };
    log('[provision] auto-provisioning requested — running runEngineSetup\n');
    Promise.resolve()
        .then(() => runEngineSetup(emit, {}))
        .then(res => {
            if (res && res.ok) {
                try { fs.writeFileSync(PROVISION_OK, res.version || 'ok'); } catch (_) {}
                plog('[provision] COMPLETE ' + (res.version || ''));
            } else {
                try { fs.writeFileSync(PROVISION_FAIL, (res && res.error) || 'failed'); } catch (_) {}
                plog('[provision] FAILED ' + ((res && res.error) || ''));
            }
        })
        .catch(e => {
            try { fs.writeFileSync(PROVISION_FAIL, String(e && e.message)); } catch (_) {}
            plog('[provision] FAILED ' + (e && e.message));
        })
        .finally(() => { _provisioning = false; });
}
setInterval(checkProvisionRequest, 2000);
