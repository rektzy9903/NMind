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
const BRIDGE_BUILD = 'b8-getcwd-sweep+apifetch';

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
const CONFIG_FILE   = path.join(FILES_DIR, 'bridge_config.json');
const SETUP_LOG     = path.join(FILES_DIR, 'setup.log');
const SETUP_DONE    = path.join(FILES_DIR, 'setup_done');
const SETUP_FAILED  = path.join(FILES_DIR, 'setup_failed');
const SESSION_FILE  = path.join(FILES_DIR, 'last_session.json');
const SESSIONS_DIR  = path.join(FILES_DIR, 'sessions');
const CWD_FILE      = path.join(FILES_DIR, 'last_cwd');
const BIN_DIR       = path.join(FILES_DIR, 'bin');
const CONFIRM_FILE  = path.join(FILES_DIR, 'auto_approve.json');

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
const DEVICE_CONTROL_PORT = 8081;
const HOST               = '127.0.0.1';

// Prefix written before any diagnostic text sent from bridge to socket so that
// index.html termWrite can route it to a sys bubble regardless of chatState.
// Must NOT be used on OSC protocol messages (thinking-start, thinking-done, etc.).
const SYS_FENCE = '\x1b]9;sys-fence\x07';

// ─── Eval bootstrap shims ─────────────────────────────────────────────────────
// These are injected as strings into every LAUNCHER -e evalCode bootstrap,
// before import(cli.js). Defined at module scope so all session types can
// reference them without a ReferenceError.

// Runtime RegExp shim: catches dynamic new RegExp(p,'u') with \p{} patterns
// that nodejs-mobile v18 V8 doesn't support. Logs pattern, falls back to /(?:)/.
const regexpShim =
    '(function(){' +
    'var _R=RegExp,_lp=' + JSON.stringify(SETUP_LOG) + ';' +
    'function Rc(p,f){' +
    'try{return new _R(p,f);}' +
    'catch(e){' +
    'if(typeof f==="string"&&f.indexOf("u")>-1&&' +
    '/Invalid|property/i.test(String(e.message||e))){' +
    'try{require("fs").appendFileSync(_lp,"[regex-compat] "+String(p).slice(0,120)+"\\n");}catch(_){}' +
    'var ff=String(f).replace(/u/g,"");' +
    'try{return new _R("(?:)",ff);}catch(_){return new _R("(?:)");}' +
    '}throw e;}' +
    '}' +
    'Rc.prototype=_R.prototype;' +
    'try{Rc[Symbol.hasInstance]=function(v){return _R[Symbol.hasInstance](v);};}catch(_){}' +
    'global.RegExp=Rc;' +
    '})();';

// Intl shim: nodejs-mobile v18.20.4 is built without ICU so global.Intl is
// undefined. cli.js uses Intl.NumberFormat, DateTimeFormat, Collator, etc.
// All constructors share one stub; new Intl.X() and bare Intl.X() both work.
const intlShim =
    '(function(){' +
    'if(typeof Intl!=="undefined"&&Intl.NumberFormat)return;' +
    'try{require("fs").appendFileSync(' + JSON.stringify(SETUP_LOG) + ',"[intl-shim] installing\\n");}catch(_){}' +
    'var s={format:function(n){return""+n;},resolvedOptions:function(){return{locale:"en-US",timeZone:"UTC"};},formatToParts:function(){return[];},compare:function(a,b){return a<b?-1:a>b?1:0;},select:function(n){return n===1?"one":"other";},segment:function(t){var a=[],i=0;for(var c of(""+t)){a.push({segment:c,index:i++,isWordLike:/[a-zA-Z0-9_]/.test(c)});}return{[Symbol.iterator]:function(){var j=0;return{next:function(){return j<a.length?{value:a[j++],done:false}:{done:true};}};}};},toString:function(){return"[object Intl]";},valueOf:function(){return"[object Intl]";}};' +
    'function mk(){return s;}mk.prototype=s;mk.supportedLocalesOf=function(){return[];};' +
    'if(!global.Intl)global.Intl={};' +
    'var I=global.Intl;' +
    'I.NumberFormat=I.NumberFormat||mk;' +
    'I.DateTimeFormat=I.DateTimeFormat||mk;' +
    'I.Collator=I.Collator||mk;' +
    'I.PluralRules=I.PluralRules||mk;' +
    'I.ListFormat=I.ListFormat||mk;' +
    'I.RelativeTimeFormat=I.RelativeTimeFormat||mk;' +
    'I.Segmenter=I.Segmenter||mk;' +
    'I.getCanonicalLocales=I.getCanonicalLocales||function(l){return[].concat(l||[]);};' +
    'I.supportedValuesOf=I.supportedValuesOf||function(){return[];};' +
    '})();';

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
                const titleRe   = /<a[^>]+class="result__a"[^>]*>([^<]+)<\/a>/g;
                const snippetRe = /<a[^>]+class="result__snippet"[^>]*>([^<]+)<\/a>/g;
                const urlRe     = /<a[^>]+class="result__url"[^>]*>([^<]+)<\/a>/g;
                let tm, sm, um;
                const titles = [], snippets = [], urls = [];
                while ((tm = titleRe.exec(body)) && titles.length < maxResults) titles.push(tm[1].replace(/&amp;/g,'&').replace(/&#x27;/g,"'").trim());
                while ((sm = snippetRe.exec(body)) && snippets.length < maxResults) snippets.push(sm[1].replace(/&amp;/g,'&').replace(/&#x27;/g,"'").trim());
                while ((um = urlRe.exec(body)) && urls.length < maxResults) urls.push(um[1].trim());
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
                return httpsGet(res.headers.location, opts, redirectCount + 1)
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
        '--rootfs=' + rp, '--root-id', '--cwd=/root',
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
    ];
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
function runProotGuest(command, timeoutMs, onData, opts) {
    return new Promise((resolve) => {
        const rp = path.join(FILES_DIR, 'ubuntu');
        const prootLibDir = path.join(FILES_DIR, '.proot-lib');
        try { fs.mkdirSync(prootLibDir, { recursive: true }); } catch (_) {}
        const tl = path.join(prootLibDir, 'libtalloc.so.2');
        try { fs.unlinkSync(tl); } catch (_) {}
        try { fs.symlinkSync(path.join(NATIVE_DIR, 'libtalloc.so'), tl); } catch (_) {}
        try { fs.mkdirSync(path.join(rp, 'tmp'), { recursive: true }); } catch (_) {}
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
            // proot's seccomp-bpf fast-path mishandles the IN-GUEST execve hand-off
            // on some Android kernels (ptrace cancels the execve to re-issue with
            // the loader, but the seccomp event ALSO fires and returns ENOSYS →
            // "Function not implemented"). PROOT_NO_SECCOMP=1 should fall back to
            // pure ptrace. PROOT_ASSUME_NEW_SECCOMP corrects the seccomp/ptrace
            // event ordering if proot auto-detects it wrong. Both overridable per
            // probe via opts.extraEnv to find what actually works on-device.
            PROOT_NO_SECCOMP: '1',
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
        const FDEXEC = path.join(NATIVE_DIR, 'libfdexec.so');
        const prootBin = path.join(NATIVE_DIR, 'libproot.so');
        let ch, out = '', done = false;
        try {
            // cwd = rootfs dir: proot reads the HOST cwd at startup to seed its
            // virtual cwd. If the bridge's inherited host cwd ("/" or the app dir)
            // has no mapping inside the rootfs, proot's getcwd() virtualization
            // returns ENOSYS → node's uv_cwd aborts (hit on npm: process.cwd()).
            // Pointing the host cwd at the rootfs root maps to guest "/", and
            // --cwd=/root then chdirs the guest into /root, so getcwd() works.
            ch = spawn(FDEXEC, [prootBin].concat(prootArgs), { env, cwd: rp });
        } catch (e) { return resolve({ code: null, out: 'spawn threw: ' + e.message }); }
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

// ─── Patch cli.js for Android (no Unicode property escape support) ───────────

// Called on startup when cli.js is already installed: re-patches if any known
// unpatched \p{} literal is still present (happens when bridge.js is updated
// with new patches but cli.js was installed with an older bridge version).
function ensureCliJsPatched() {
    try {
        const src = fs.readFileSync(CLAUDE_CLI, 'utf8');
        if (src.includes('/^\\p{Default_Ignorable_Code_Point}$/u') ||
            src.includes('/\\p{L}/u') ||
            src.includes('/[\\p{L}\\p{N}]/u')) {
            log('cli.js has unpatched \\p{} patterns — re-applying Android patches...\n');
            patchCliJsForAndroid(CLAUDE_CLI);
        }
    } catch (_) {}
}

function patchCliJsForAndroid(cliPath) {
    log('Patching cli.js for Android (removing \\p{} regex property escapes)...\n');
    let src;
    try { src = fs.readFileSync(cliPath, 'utf8'); } catch (e) {
        log('Patch skipped: could not read cli.js — ' + e.message + '\n');
        return;
    }
    let n = 0;

    function rep(from, to) {
        // M22: log failures so they appear in !log output
        if (!src.includes(from)) {
            console.error('[patch] FAILED to find pattern: ' + String(from).slice(0, 80));
            return;
        }
        const parts = src.split(from);
        if (parts.length === 1) return;
        n += parts.length - 1;
        src = parts.join(to);
    }

    // Markdown text-processor (U54 block)
    rep('/^\\p{Default_Ignorable_Code_Point}$/u',
        '/^[\\u00AD\\u034F\\u061C\\u115F\\u1160\\u17B4\\u17B5\\u180B-\\u180F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\u3164\\uFFA0\\uFFF0-\\uFFFB]$/');
    rep('/^[\\p{L}\\p{N}\\p{M}_]$/u',
        '/^[a-zA-Z0-9\\xC0-\\u024F\\u0300-\\u036F\\u0370-\\u03FF\\u0400-\\u04FF\\u4E00-\\u9FFF_]$/');
    rep('/[\\p{L}\\p{N}]/u',
        '/[a-zA-Z0-9\\xC0-\\u024F\\u0370-\\u03FF\\u0400-\\u04FF\\u4E00-\\u9FFF]/');
    var PS = '!"#$%&\'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~\\xA2-\\xBF';
    rep('/[\\p{P}\\p{S}]/u',           '/[' + PS + ']/');
    rep('/[\\s\\p{P}\\p{S}]/u',        '/[\\s' + PS + ']/');
    rep('/[^\\s\\p{P}\\p{S}]/u',       '/[^\\s' + PS + ']/');
    rep('/(?!~)[\\p{P}\\p{S}]/u',      '/(?!~)[' + PS + ']/');
    rep('/(?!~)[\\s\\p{P}\\p{S}]/u',   '/(?!~)[\\s' + PS + ']/');
    rep('/(?:[^\\s\\p{P}\\p{S}]|~)/u', '/(?:[^\\s' + PS + ']|~)/');
    rep('/\\p{L}/u',
        '/[a-zA-Z\\xC0-\\u024F\\u0370-\\u03FF\\u0400-\\u04FF\\u4E00-\\u9FFF]/');
    rep('/[\\p{L}\\p{N}_]/u',
        '/[a-zA-Z0-9\\xC0-\\u024F\\u0370-\\u03FF\\u0400-\\u04FF\\u4E00-\\u9FFF_]/');
    rep('/[\\p{Cf}\\p{Co}\\p{Cn}]/gu',
        '/[\\u00AD\\u034F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF\\uFFFE\\uFFFF]/g');
    rep("/^[\\p{L}\\p{M}\\p{N}_ .&'()+-]+$/u",
        "/^[a-zA-Z0-9\\xC0-\\u024F\\u0300-\\u036F\\u0370-\\u03FF\\u0400-\\u04FF\\u4E00-\\u9FFF_ .&'()+-]+$/");

    // CSS-like syntax highlighter (KE8 block) — / inside char class
    rep('/[\\p{L}\\p{N}_/.\\-+~\\\\]/u',
        '/[a-zA-Z0-9\\xC0-\\u024F\\u0370-\\u03FF\\u0400-\\u04FF\\u4E00-\\u9FFF_\\/+.~\\\\-]/');

    // @ mention regexes (IM7 block) — / and ] inside char class
    var M = 'a-zA-Z0-9\\xC0-\\u024F\\u0300-\\u036F\\u0370-\\u03FF\\u0400-\\u04FF\\u4E00-\\u9FFF_\\-.\\/\\\\()[\\]~:';
    rep('/^@[\\p{L}\\p{N}\\p{M}_\\-./\\\\()[\\]~:]*/u',  '/^@[' + M + ']*/');
    rep('/^[\\p{L}\\p{N}\\p{M}_\\-./\\\\()[\\]~:]+/u',   '/^[' + M + ']+/');
    rep('/(@[\\p{L}\\p{N}\\p{M}_\\-./\\\\()[\\]~:]*|[\\p{L}\\p{N}\\p{M}_\\-./\\\\()[\\]~:]+)$/u',
        '/(@[' + M + ']*|[' + M + ']+)$/');
    rep('/[\\p{L}\\p{N}\\p{M}_\\-./\\\\()[\\]~:]+$/u',   '/[' + M + ']+$/');
    rep('/(^|[\\s。、？！])@([\\p{L}\\p{N}\\p{M}_\\-./\\\\()[\\]~:]*|"[^"]*"?)$/u',
        '/(^|[\\s。、？！])@([' + M + ']*|"[^"]*"?)$/');

    // Emoji detection — new RegExp at runtime, wrap in try-catch
    rep('function Tq1(){return new RegExp("^(\\\\p{Extended_Pictographic}|\\\\p{Emoji_Component})+$","u")}',
        'function Tq1(){try{return new RegExp("^(\\\\p{Extended_Pictographic}|\\\\p{Emoji_Component})+$","u")}' +
        'catch(_e){return /[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF\\u2300-\\u23FF]/}}');

    // Remove the "Welcome to Claude Code" banner that is rendered unconditionally
    // at the top of every TUI layout. The function is unique (s(35) hook, Zq() theme).
    // Replacing it with a no-op removes the banner from all sessions and reconnects.
    rep('function Cm6(){', 'function Cm6(){return null;}function _Cm6_orig(){');

    // Remove "Welcome back!" (shown to returning users instead of the first-run banner)
    rep('"Welcome back!"', '"         "');
    rep("'Welcome back!'", "'         '");

    try { fs.writeFileSync(cliPath, src); } catch (e) {
        log('Patch write failed: ' + e.message + '\n'); return;
    }
    log('Patch complete: ' + n + ' replacements applied to cli.js\n');
}

// ─── Install claude-code directly from npm registry ──────────────────────────

function installClaudeCode(onDone) {
    fs.mkdirSync(NPM_PREFIX, { recursive: true });

    (async () => {
        log('Fetching @anthropic-ai/claude-code package info from npm registry...\n');

        // Pinned to the last Node.js-native version (v2.1.112).
        // v2.1.113+ switched to pre-compiled native binaries that require glibc
        // and are not compatible with Android's Bionic runtime + libnode.so.
        const meta = await fetchJson(
            'https://registry.npmjs.org/@anthropic-ai%2Fclaude-code/2.1.112'
        );

        const tarball = meta.dist && meta.dist.tarball;
        if (!tarball) throw new Error('No tarball URL in registry response');

        const sizeMB = Math.round((meta.dist.unpackedSize || 0) / 1e6);
        log(`Package: v${meta.version}  Size: ~${sizeMB || '?'} MB\n`);
        log(`Downloading from: ${tarball}\n`);

        const tgzPath = path.join(FILES_DIR, 'claude-code.tgz');
        try { fs.unlinkSync(tgzPath); } catch (_) {}

        await downloadFile(tarball, tgzPath);
        log('Download complete. Extracting...\n');

        const destDir = path.join(
            NPM_PREFIX, 'lib', 'node_modules', '@anthropic-ai', 'claude-code'
        );
        // tmpDir sits next to destDir on the same filesystem so renameSync works
        const tmpDir = destDir + '.tmp';

        // Clean up any leftovers from a previous failed attempt
        try { fs.rmSync(destDir, { recursive: true, force: true }); } catch (_) {}
        try { fs.rmSync(tmpDir,  { recursive: true, force: true }); } catch (_) {}
        fs.mkdirSync(tmpDir, { recursive: true });
        fs.mkdirSync(path.dirname(destDir), { recursive: true });

        // Step 1: decompress .tgz → .tar with Node.js zlib.
        // Toybox tar on some Android versions lacks gzip support (-z), so we
        // handle decompression in JS and only use tar for the actual unpack.
        const tarPath = path.join(FILES_DIR, 'claude-code.tar');
        try { fs.unlinkSync(tarPath); } catch (_) {}

        await new Promise((res, rej) => {
            const zlib = require('zlib');
            const src  = fs.createReadStream(tgzPath);
            const gz   = zlib.createGunzip();
            const dst  = fs.createWriteStream(tarPath);
            src.on('error', rej);
            gz.on('error',  rej);
            dst.on('error', rej);
            dst.on('finish', res);
            src.pipe(gz).pipe(dst);
        });

        try { fs.unlinkSync(tgzPath); } catch (_) {}

        // Step 2: unpack the plain .tar (no -z; gzip already done above).
        // npm tarballs always place files under a 'package/' prefix, so we
        // extract to tmpDir then rename that subdirectory to destDir.
        await new Promise((res, rej) => {
            const tar = spawn('/system/bin/tar', ['-xf', tarPath, '-C', tmpDir], {
                env: { PATH: '/system/bin:/system/xbin' }
            });
            tar.stderr.on('data', d => log('tar: ' + d.toString()));
            tar.on('error', err => rej(new Error('tar: ' + err.message)));
            tar.on('close', code => code === 0 ? res() : rej(new Error('tar exit ' + code)));
        });

        try { fs.unlinkSync(tarPath); } catch (_) {}

        const pkgDir = path.join(tmpDir, 'package');
        if (!fs.existsSync(pkgDir)) {
            const found = fs.readdirSync(tmpDir).join(', ');
            throw new Error('package/ not found in tarball; found: [' + found + ']');
        }
        fs.renameSync(pkgDir, destDir);
        try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch (_) {}

        if (!isClaudeInstalled()) {
            throw new Error('cli.js not found after extraction — package layout may have changed');
        }

        // Android's nodejs-mobile v18 build has no Unicode property escape
        // support (\p{...} in regex with /u flag). cli.js uses them throughout
        // its markdown parser, text normalizer, and @ mention detector.
        // Patch every occurrence with equivalent explicit character ranges.
        patchCliJsForAndroid(CLAUDE_CLI);
        log('\n✓ Claude Code installed successfully!\n');
        try { fs.writeFileSync(SETUP_DONE, 'true'); } catch (_) {}
        onDone(true);
    })().catch(err => {
        log('\n✗ Installation failed: ' + err.message + '\n');
        const isNetworkErr = /ECONNREFUSED|ENOTFOUND|ETIMEDOUT|EHOSTUNREACH|ENETUNREACH|HTTP [45]/.test(err.message);
        if (isNetworkErr) {
            log('Network error — check your internet connection and tap "Try again".\n');
        } else {
            log('Tap "Try again" to retry.\n');
        }
        try { fs.writeFileSync(SETUP_FAILED, 'true'); } catch (_) {}
        onDone(false);
    });
}

// ─── Retry loop ───────────────────────────────────────────────────────────────
// Node.js can only be started once per process, so bridge.js must stay alive
// and retry internally. After a failure it polls every 2 s; when Kotlin clears
// the "setup_failed" file (user tapped "Try again") it re-runs the install.

function installLoop() {
    // Fresh log for each attempt so the user sees current progress
    try { fs.writeFileSync(SETUP_LOG, ''); } catch (_) {}
    log('Starting installation (launcher: ' + LAUNCHER + ')\n');

    installClaudeCode(ok => {
        if (ok) {
            writeSubagentWrappers();
            startBridgeServer();
        } else {
            // Kotlin clears setup_failed when the user taps "Try again"
            waitForRetry(installLoop);
        }
    });
}

function waitForRetry(callback) {
    const tick = () => {
        let gone = false;
        try { gone = !fs.existsSync(SETUP_FAILED); } catch (_) {}
        if (gone) {
            log('Retry signal received — starting installation...\n');
            callback();
        } else {
            setTimeout(tick, 2000);
        }
    };
    setTimeout(tick, 2000);
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
            const modelId = mcfg.mode === 'subscription'
                ? (mcfg.modelId || 'claude-3-5-sonnet-20241022')
                : 'claude-3-5-sonnet-20241022';
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
                data: [{ id: modelId, display_name: modelId, created_at: '' }]
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
    // Find the last user message and rewrite its content.
    let idx = -1;
    for (let i = anthReq.messages.length - 1; i >= 0; i--) {
        if (anthReq.messages[i].role === 'user') { idx = i; break; }
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
    // Orchestration/harness tools with no function on a phone terminal — and
    // they are loop drivers for weak OAI models (inv 68). Observed on-device:
    // gpt-oss-120b spiralled on Skill("update-config") ~10x trying to self-grant
    // a Write permission, then timed out (exit 143). AskUserQuestion is the other
    // prime loop driver (weak models "ask permission" for things they can do).
    'Skill', 'Monitor', 'PushNotification', 'RemoteTrigger', 'AskUserQuestion',
]);

function handleProxyRequest(anthReq, res) {
    const cfg   = readConfig();
    const pUrl  = cfg.providerUrl || '';
    const key   = cfg.apiKey || '';
    const stream = !!anthReq.stream;

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
    }

    const baseModel = cfg.modelId || anthReq.model || '';
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
            // Any accompanying text in the same user block (rare)
            if (textBlocks.length > 0)
                msgs.push({ role: 'user', content: textBlocks.map(b => b.text).join('') });
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

    const req = { model, messages: msgs, max_tokens: a.max_tokens || 8096, stream: !!a.stream };
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
            content.push({ type: 'tool_use', id: tc.id, name: tc.function.name, input });
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

function sendToProvider(baseUrl, apiKey, oaiReq, stream, res, onBadRequest, on429, on402, on5xx) {
    let targetUrl;
    try {
        const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
        targetUrl = new URL(base + '/chat/completions');
    } catch (e) {
        return proxyError(res, 500, 'Invalid provider URL: ' + baseUrl);
    }

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
            let buffer      = '';
            let headersSent = false;
            // tool_call index → {id, name, blockIdx} — tracks streaming tool call blocks
            let tcBlocks    = {};
            let nextBlockIdx = 1; // 0 = text block; tool blocks start at 1

            // Idle timer: if OpenRouter sends 200 OK but then stalls sending SSE events,
            // abort after 30 s rather than letting the claude --print 180 s timeout fire.
            function abortStalled() {
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

            function finishStream(stopReason) {
                if (finished) return;
                finished = true;
                sendEvent('content_block_stop', { type: 'content_block_stop', index: 0 });
                for (const tb of Object.values(tcBlocks)) {
                    sendEvent('content_block_stop', { type: 'content_block_stop', index: tb.blockIdx });
                }
                sendEvent('message_delta', {
                    type: 'message_delta',
                    delta: { stop_reason: stopReason || 'end_turn', stop_sequence: null },
                    usage: { output_tokens: outTokens },
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
                                const blockIdx = nextBlockIdx++;
                                tcBlocks[tcIdx] = { id: tc.id, name: (tc.function || {}).name || '', blockIdx, argsAccum: '', sig: null };
                                sendEvent('content_block_start', {
                                    type: 'content_block_start', index: blockIdx,
                                    content_block: {
                                        type: 'tool_use', id: tc.id,
                                        name: (tc.function || {}).name || '', input: {}
                                    }
                                });
                                log('[proxy] stream: tool_use block — ' + tcBlocks[tcIdx].name + '\n');
                            }
                            const sig = extractThoughtSig(tc, delta, choice);
                            if (sig) tcBlocks[tcIdx].sig = sig;
                            const args = (tc.function || {}).arguments || '';
                            if (args) {
                                tcBlocks[tcIdx].argsAccum += args;
                                sendEvent('content_block_delta', {
                                    type: 'content_block_delta', index: tcBlocks[tcIdx].blockIdx,
                                    delta: { type: 'input_json_delta', partial_json: args }
                                });
                            }
                        }
                    }

                    if (finishCode && !finished) {
                        log('[proxy] finish_reason=' + finishCode + ' tokens=' + outTokens + '\n');

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
                                        const assistantMsg = {
                                            role: 'assistant',
                                            content: null,
                                            tool_calls: Object.values(tcBlocks).map(tb => ({
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

        provRes.on('error', err => log('[proxy] provider response error (non-stream): ' + err.message + '\n'));
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
        // claude-code's Bash TOOL aborts with "No suitable shell found … set the
        // SHELL environment variable" when SHELL is unset (Android has no default).
        // /system/bin/sh is a valid POSIX shell (the bridge's own `$` commands use
        // it). Without this, Write/Edit/Read tools work but the Bash tool is dead.
        SHELL: '/system/bin/sh',
        // claude-code v2.1.112 only accepts a shell whose PATH contains "bash"/"zsh"
        // (cli.js NzY filters $SHELL on includes("bash")||includes("zsh")), so plain
        // SHELL=/system/bin/sh is silently dropped → "No suitable shell found". The
        // CLAUDE_CODE_SHELL override is checked first and must also contain "bash";
        // point it at the BIN_DIR/bash symlink (→ /system/bin/sh) written by
        // writeSubagentWrappers(). This is what actually makes the Bash TOOL work.
        CLAUDE_CODE_SHELL: path.join(BIN_DIR, 'bash'),
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
const MCP_SPAWN_CONFIG  = path.join(FILES_DIR, 'mcp_spawn_config.json');

// Build the mcpServers object claude-code consumes via `--mcp-config`. Merges
// real stdio servers (from mcp_config.json) + one stdio shim per HTTP server
// (from mcp_http.json). HTTP servers go through the lazy `mcp_http_proxy.js`
// stdio shim — claude-code only ever spawns a LOCAL stdio process and speaks
// stdio MCP, so there is NO remote SSE connect at spawn time (that was the
// invariant-51 hang). Returns {} when nothing is configured.
//
// NOTE: claude-code does NOT read `mcpServers` from settings.json — MCP must be
// supplied via --mcp-config (or .mcp.json). The old settings.json injection was
// a silent no-op, which is why `[proxy] mcp tools in request: 0` on every turn.
function buildMcpServersObj() {
    const servers = {};
    try {
        if (fs.existsSync(MCP_CONFIG_FILE)) {
            const cfg = JSON.parse(fs.readFileSync(MCP_CONFIG_FILE, 'utf8'));
            for (const [name, srv] of Object.entries((cfg && cfg.mcpServers) || {})) {
                if (srv && srv.type === 'stdio') servers[name] = srv; // raw HTTP/SSE excluded
            }
        }
    } catch (e) { log('[mcp-spawn] stdio read error: ' + e.message + '\n'); }
    try {
        const shimPath = path.join(FILES_DIR, 'mcp_http_proxy.js');
        if (fs.existsSync(MCP_HTTP_CONFIG) && fs.existsSync(shimPath)) {
            const httpCfg = JSON.parse(fs.readFileSync(MCP_HTTP_CONFIG, 'utf8'));
            for (const up of (Array.isArray(httpCfg) ? httpCfg : [])) {
                if (!up || !up.name || !up.url) continue;
                const safeName = String(up.name).replace(/[^a-zA-Z0-9_-]/g, '_');
                servers[safeName] = {
                    type: 'stdio',
                    command: LAUNCHER,
                    args: ['-e',
                        "import('file://" + shimPath + "').catch(function(e){" +
                        "process.stderr.write('[mcp-http-proxy] import failed: '+e.message+'\\n');" +
                        "process.exit(1);" +
                        "});"
                    ],
                    // Full runtime env — NOT just the MCP_HTTP_* vars. claude-code's
                    // MCP SDK (StdioClientTransport) spawns with `env: params.env ??
                    // getDefaultEnvironment()`: when env is supplied it is used VERBATIM
                    // (no parent-env inheritance), and the default set omits
                    // LD_LIBRARY_PATH anyway. Without LD_LIBRARY_PATH=NATIVE_DIR the
                    // shim's libnode-launcher.so can't find libnode.so and dies at exec,
                    // so the server never initializes and 0 tools register. buildEnv()
                    // supplies PATH/HOME/LD_LIBRARY_PATH; the shim ignores the ANTHROPIC_*
                    // vars it doesn't read. (The bridge's own client already does this —
                    // line ~2251 — which is why its [mcp-http:exa] launch succeeds.)
                    env: Object.assign({}, buildEnv(), {
                        MCP_HTTP_NAME: String(up.name),
                        MCP_HTTP_URL: String(up.url),
                        MCP_HTTP_HEADERS: JSON.stringify(up.headers || {}),
                    }),
                };
            }
        }
    } catch (e) { log('[mcp-spawn] http read error: ' + e.message + '\n'); }
    return servers;
}

// Write the --mcp-config file for a spawn; returns its path, or null if no
// servers are configured (so the caller omits the flag entirely).
function writeSpawnMcpConfig() {
    const servers = buildMcpServersObj();
    if (Object.keys(servers).length === 0) {
        try { if (fs.existsSync(MCP_SPAWN_CONFIG)) fs.unlinkSync(MCP_SPAWN_CONFIG); } catch (_) {}
        return null;
    }
    try {
        fs.writeFileSync(MCP_SPAWN_CONFIG, JSON.stringify({ mcpServers: servers }, null, 2));
        log('[mcp-spawn] --mcp-config servers: ' + Object.keys(servers).join(', ') + '\n');
        return MCP_SPAWN_CONFIG;
    } catch (e) {
        log('[mcp-spawn] write error: ' + e.message + '\n');
        return null;
    }
}
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

// Build the bootstrap eval string for interactive mode.
// No --print flag, no message in argv — stdin stays open so the user can
// send multiple turns to one persistent process.
function buildInteractiveEvalCode() {
    const cliUrl  = 'file://' + CLAUDE_CLI;
    const exitLog = JSON.stringify(path.join(FILES_DIR, 'session_exit.log'));
    const hasMcp  = fs.existsSync(MCP_CONFIG_FILE);
    // --output-format stream-json is intentionally omitted: that flag is only
    // valid with --print; in interactive mode claude rejects it and exits 1.
    // We forward raw PTY bytes to the socket (ANSI TUI relay) instead.
    // argv[0] = launcher, argv[1] = CLAUDE_CLI (set below). Claude parses from
    // argv[2] onward — no sparse slots or it will hit undefined and exit 1.
    const argvCode = hasMcp
        ? 'process.argv[2]="--mcp-config";process.argv[3]=' + JSON.stringify(MCP_CONFIG_FILE) +
          ';process.argv[4]="--dangerously-skip-permissions";process.argv.length=5;'
        : 'process.argv[2]="--dangerously-skip-permissions";process.argv.length=3;';
    return (
        'process.on("exit",function(c){' +
        'try{require("fs").appendFileSync(' + exitLog + ',"[exit] "+c+"\\n");}catch(_){}}); ' +
        'process.on("unhandledRejection",function(r){' +
        'try{require("fs").appendFileSync(' + exitLog + ',' +
        '"[unhandledRejection] "+String(r&&(r.stack||r.message)||r).slice(0,400)+"\\n");}catch(_){}});' +
        regexpShim +
        intlShim +
        'process.argv[1]=' + JSON.stringify(CLAUDE_CLI) + ';' +
        argvCode +
        'import(' + JSON.stringify(cliUrl) + ')' +
        '.catch(function(e){process.stderr.write("import-err:"+String(e)+"\\n");process.exit(1);});'
    );
}


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

    // ── Always-allow list (persisted to auto_approve.json) ───────────────────
    function loadApproveList() {
        try { return JSON.parse(fs.readFileSync(CONFIRM_FILE, 'utf8')); } catch(_) {}
        return { allow: [], deny: [] };
    }
    function saveApproveList(list) {
        try {
            fs.writeFileSync(CONFIRM_FILE, JSON.stringify(list, null, 2));
            try { fs.chmodSync(CONFIRM_FILE, 0o600); } catch(_) {}
        } catch(_) {}
    }

    // T2: simple glob (* only) → regex pattern match for Bash command auto-approve.
    // Entries in approveList.allow may be bare tool names ('Bash') OR
    // 'ToolName(pattern)' (e.g. 'Bash(git *)'). Pattern only supports '*' wildcard.
    function patternMatchesCmd(pat, cmd) {
        try {
            const escaped = pat.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*');
            return new RegExp('^' + escaped + '$').test(cmd);
        } catch(_) { return false; }
    }
    function isToolAlreadyAllowed(toolName, toolInput, allowList) {
        // '*' sentinel (set by !approve-tools) = approve every tool silently, no
        // permission card. The Agent/Task carve-out in the callers still forces the
        // sub-agent panel to render, so agent activity stays visible.
        if ((allowList || []).includes('*')) return true;
        if ((allowList || []).includes(toolName)) return true;
        if (toolName === 'Bash') {
            const cmd = ((toolInput && toolInput.command) || '').toString().trim();
            if (cmd) {
                for (const entry of (allowList || [])) {
                    const m = /^Bash\((.*)\)$/.exec(entry);
                    if (m && patternMatchesCmd(m[1], cmd)) return true;
                }
            }
        }
        return false;
    }

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
            if (!s.customApiKeyResponses) s.customApiKeyResponses = { approved: [], rejected: [] };
            if (!Array.isArray(s.customApiKeyResponses.approved)) s.customApiKeyResponses.approved = [];
            if (!s.customApiKeyResponses.approved.includes('sk-ant-proxy000'))
                s.customApiKeyResponses.approved.push('sk-ant-proxy000');
            s.customApiKeyResponses.rejected =
                s.customApiKeyResponses.rejected.filter(k => k !== 'sk-ant-proxy000');
            s.theme = 'dark'; s.hasCompletedOnboarding = true;
            s.hasShownWelcome = true; s.skipWelcome = true;
            // NOTE: dangerouslySkipPermissions is intentionally NOT set here.
            // On Android Bionic / claude-code v2.1.112, setting it (via settings.json
            // or CLI flag) causes the process to hang after the HEAD health-check and
            // never reach POST /v1/messages. Tool approval is handled entirely by
            // permissions.allow: ['*'] below, which works without the hang.
            if (!s.permissions) s.permissions = { allow: [], deny: [] };
            if (!Array.isArray(s.permissions.allow)) s.permissions.allow = [];
            if (!Array.isArray(s.permissions.deny)) s.permissions.deny = [];
            // '*' alone doesn't reliably match all tool types in v2.1.112
            // (WebSearch, WebFetch, MCP tools slip through and trigger permission dialogs).
            // Add explicit patterns so claude-code auto-approves before emitting 9;confirm:.
            const TOOL_ALLOW = ['*', 'WebSearch(*)', 'WebFetch(*)'];
            for (const p of TOOL_ALLOW) {
                if (!s.permissions.allow.includes(p)) s.permissions.allow.push(p);
            }
            // MCP tools: claude-code does NOT support wildcards in MCP permission rules,
            // so 'mcp__*' is silently ignored and every mcp__<server>__<tool> prompted
            // every turn (user had to tick "Always allow" + resend). The valid format is
            // 'mcp__<server>' (server-level — grants ALL of that server's tools). Add one
            // per configured server so e.g. mcp__exa__web_search_exa auto-approves.
            try {
                for (const srvName of Object.keys(buildMcpServersObj())) {
                    const rule = 'mcp__' + srvName;
                    if (!s.permissions.allow.includes(rule)) s.permissions.allow.push(rule);
                }
            } catch (_) {}
            // Workspace boundary: claude-code's Write/Edit/Read tools refuse paths
            // OUTSIDE the working dir + additionalDirectories, independent of
            // permissions.allow:['*']. The app holds MANAGE_EXTERNAL_STORAGE, so the
            // process can write anywhere on shared storage — grant the common storage
            // roots (and the app files dir) as additional workspace dirs so the model
            // can edit user files under /sdcard without hitting the out-of-workspace
            // gate (the gate the model kept hitting then mis-explaining as a "security
            // sandbox"). cwd itself is always allowed; this covers absolute paths.
            if (!Array.isArray(s.permissions.additionalDirectories)) s.permissions.additionalDirectories = [];
            for (const d of ['/sdcard', '/storage/emulated/0', '/storage/self/primary', FILES_DIR]) {
                if (!s.permissions.additionalDirectories.includes(d)) s.permissions.additionalDirectories.push(d);
            }
            // Inject per-tool always-allow and always-deny overrides saved by the user.
            // Bare names (e.g. 'Bash') get both 'Bash' and 'Bash(*)' so v2.1.112 matches them.
            // Pattern entries (e.g. 'Bash(git *)') already self-describe — inject as-is, no suffix.
            const approveList = loadApproveList();
            for (const t of (approveList.allow || [])) {
                if (!s.permissions.allow.includes(t)) s.permissions.allow.push(t);
                if (!/[()]/.test(t)) {
                    const pat = t + '(*)';
                    if (!s.permissions.allow.includes(pat)) s.permissions.allow.push(pat);
                }
            }
            for (const t of (approveList.deny || [])) {
                if (!s.permissions.deny.includes(t)) s.permissions.deny.push(t);
            }
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
            log('[patchSettings] ok — approved=' + JSON.stringify(s.customApiKeyResponses.approved) + '\n');
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
                // tool_use blocks are handled by the isPermEvent check below
            }
            // If this assistant event contains tool_use, fall through to isPermEvent check
            if (!content.some(b => b.type === 'tool_use')) return;
        }

        // claude-code emits a permission_request event for tool calls needing approval.
        // v2.1.112 stream-json may use several field layouts — cover all known variants.
        const isPermEvent =
            evt.type === 'permission_request' ||
            (evt.type === 'tool' && (evt.status === 'pending' || evt.status === 'awaiting_approval')) ||
            evt.type === 'tool_approval_request' ||
            (evt.type === 'assistant' && evt.message && (evt.message.content || []).some(b => b.type === 'tool_use'));
        if (isPermEvent) {
            // Extract tool name + input from whichever field layout is present
            let toolName = evt.tool_name || evt.tool || evt.name || 'tool';
            let toolInput = evt.tool_input || evt.input || {};
            let aiText = '';
            // assistant event: grab text + first tool_use block
            if (evt.type === 'assistant' && evt.message) {
                const blocks = evt.message.content || [];
                const tb = blocks.find(b => b.type === 'tool_use');
                if (tb) { toolName = tb.name || toolName; toolInput = tb.input || toolInput; }
                aiText = blocks.filter(b => b.type === 'text').map(b => b.text || '').join(' ');
            }
            // Extract backtick-quoted commands/paths from surrounding AI text as suggestions
            const suggestions = [];
            const btre = /`([^`\n]{2,80})`/g;
            let m;
            while ((m = btre.exec(aiText + ' ' + (state.lastAiText || ''))) !== null) {
                const s = m[1].trim();
                if (s && !suggestions.includes(s)) suggestions.push(s);
                if (suggestions.length >= 4) break;
            }
            const permId = evt.id || (Date.now() + '-' + Math.random().toString(36).slice(2));
            // Always write y\n immediately — the tool runs regardless of whether dialog shows.
            try { if (proc && proc.stdin && !proc.stdin.destroyed) proc.stdin.write('y\n'); } catch(_) {}
            // Skip the dialog entirely if the user has already said Always Allow for this tool
            // (or a matching Bash pattern, e.g. saved 'Bash(git *)' covers a new 'git push').
            // EXCEPTION: Agent/Task tool always emits the OSC so the sub-agent panel
            // can render. permissions.allow:['*'] matches Task → without this carve-out
            // the panel would never appear because we'd return before sending the OSC.
            const savedApprove = loadApproveList();
            const isAgentTool = (toolName === 'Agent' || toolName === 'Task');
            if (!isAgentTool && isToolAlreadyAllowed(toolName, toolInput, savedApprove.allow)) return;
            const perm = { toolName, toolInput, id: permId, suggestions, autoApproved: true };
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

    // ── Handle a plain-text permission prompt written to stdout ───────────────
    // claude-code may write "Do you want to run bash? [y/n/a]" to stdout when
    // it cannot emit a structured event. Parse it, surface the dialog, wait.
    function handlePermissionText(line, state, proc) {
        // Extract tool name heuristically
        const toolMatch = line.match(/\b(?:run|execute|use|allow)\s+(\w[\w-]*)/i);
        const toolName  = toolMatch ? toolMatch[1] : 'tool';
        try { if (proc && proc.stdin && !proc.stdin.destroyed) proc.stdin.write('y\n'); } catch(_) {}
        // Skip dialog if user already said Always Allow for this tool (incl. Bash patterns).
        // Same Agent/Task carve-out as the structured path — the sub-agent panel needs
        // the OSC even when permissions.allow:['*'] would otherwise suppress it.
        const savedApprove = loadApproveList();
        const isAgentTool = (toolName === 'Agent' || toolName === 'Task');
        if (!isAgentTool && isToolAlreadyAllowed(toolName, { prompt: line }, savedApprove.allow)) return;
        const perm = { toolName, toolInput: { prompt: line }, id: Date.now() + '-txt', autoApproved: true };
        state.pendingPerm = perm;
        const permB64 = Buffer.from(JSON.stringify(perm)).toString('base64');
        try { if (state.socket) state.socket.write('\x1b]9;permission:' + permB64 + '\x07'); } catch(_) {}
    }

    // ── Spawn one claude --print process for a single user message ────────────
    function runMessage(msg, state) {
        const cfg = readConfig();
        patchSettings(cfg);
        const env = buildEnv();
        const cliUrl  = 'file://' + CLAUDE_CLI;
        const exitLog = JSON.stringify(SETUP_LOG);

        // If model/provider changed since last session, clear history so the new
        // model doesn't receive context it never produced.
        const clearFlagPath = path.join(FILES_DIR, 'history_clear_requested');
        if (fs.existsSync(clearFlagPath)) {
            try { fs.unlinkSync(clearFlagPath); } catch(_) {}
            state.hasHistory = false;
            clearSessionState(state.sid);
            clearClaudeSessionFiles();
            try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m[Model changed — history cleared for clean start]\x1b[0m\r\n'); } catch(_) {}
        }

        // argv for print mode — order matters for claude-code v2.1.112 arg parser:
        //   --output-format stream-json       → structured NDJSON output (must come first)
        //   --print                           → non-interactive, exits after response
        //   --verbose                         → required alongside --output-format=stream-json
        //   --continue                        → resume last session (preserves history)
        //   <message>                         → the user's message
        // NOTE: --append-system-prompt is intentionally omitted. It causes claude-code
        // v2.1.112 to hang indefinitely after the HEAD / health-check, never reaching the
        // POST /v1/messages call. Custom instructions are prepended to the message instead.
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
        // Prepend instructions to the message (no --append-system-prompt — that flag causes
        // claude-code v2.1.112 to hang indefinitely on Android after HEAD / health-check).
        const preamble = [STORAGE_NOTE, customPrompt].filter(Boolean).join('\n\n');
        const finalMsg = '[Instructions]\n' + preamble + '\n\n' + msg;
        // --mcp-config supplies MCP servers to claude-code in print mode. claude-code
        // does NOT read mcpServers from settings.json (that injection was a no-op —
        // it's why MCP tools never reached the model). The config here contains only
        // STDIO entries: real stdio servers + the lazy `mcp_http_proxy.js` shim for
        // each HTTP server. No raw HTTP/SSE servers → no remote connect at spawn →
        // no invariant-51 hang (the shim connects upstream lazily on first tool call).
        const spawnMcpCfg = writeSpawnMcpConfig();
        log('[runMessage] mcp_config exists=' + fs.existsSync(MCP_CONFIG_FILE) +
            ' spawn-mcp=' + (spawnMcpCfg ? 'yes' : 'no') + '\n');
        let argvCode =
            'process.argv[2]="--output-format";' +
            'process.argv[3]="stream-json";' +
            'process.argv[4]="--print";';
        let argvLen = 5;
        if (spawnMcpCfg) {
            argvCode += 'process.argv[' + argvLen + ']="--mcp-config";';
            argvLen++;
            argvCode += 'process.argv[' + argvLen + ']=' + JSON.stringify(spawnMcpCfg) + ';';
            argvLen++;
        }
        if (state.hasHistory) {
            argvCode += 'process.argv[' + argvLen + ']="--continue";';
            argvLen++;
        }
        // --verbose MUST be the LAST flag before the positional message. claude-code's
        // --mcp-config is a VARIADIC option: commander keeps consuming every following
        // argv element as an additional config path until it hits the next option-looking
        // token. With --mcp-config <path> directly before the message, the message was
        // swallowed as a 2nd config path → "MCP config file not found: .../[Instructions]"
        // → exit 1 on every MCP-enabled turn. A boolean flag (--verbose) between the
        // variadic and the message terminates the variadic so the message stays positional.
        argvCode += 'process.argv[' + argvLen + ']="--verbose";';
        argvLen++;
        argvCode += 'process.argv[' + argvLen + ']=' + JSON.stringify(finalMsg) + ';';
        argvLen++;
        argvCode += 'process.argv.length=' + argvLen + ';';

        const evalCode =
            'process.stderr.write("[eval-ok]\\n");' +
            'process.on("exit",function(c){' +
            'try{require("fs").appendFileSync(' + exitLog + ',"[print-exit] "+c+"\\n");}catch(_){}});' +
            'process.on("unhandledRejection",function(r){' +
            'try{require("fs").appendFileSync(' + exitLog + ',' +
            '"[unhandledRejection] "+String(r&&(r.stack||r.message)||r).slice(0,400)+"\\n");}catch(_){}});' +
            regexpShim + intlShim +
            'process.argv[1]=' + JSON.stringify(CLAUDE_CLI) + ';' +
            argvCode +
            'import(' + JSON.stringify(cliUrl) + ')' +
            '.then(function(){try{require("fs").appendFileSync(' + exitLog + ',"[import-resolved]\\n");}catch(_){}})' +
            '.catch(function(e){process.stderr.write("import-err:"+String(e)+"\\n");process.exit(1);});';

        // Verify cwd exists — spawn throws synchronously (ENOENT) if cwd is missing.
        const spawnCwd = (state.cwd && fs.existsSync(state.cwd)) ? state.cwd : FILES_DIR;
        // Pre-trust this cwd so claude-code skips the "do you trust this folder?" prompt
        // now that CLAUDE_CODE_SANDBOXED is gone (which also un-sandboxes the Bash tool).
        ensureProjectTrusted(spawnCwd);
        log('[runMessage] spawn claude-code, model=' + (cfg.modelId || '?') + ' provider=' + (cfg.providerId || '?') + ' mode=' + (cfg.mode || '?') + ' baseUrl=' + (cfg.baseUrl || '?') + '\n');
        log('[runMessage] argv: --output-format stream-json --print' + (spawnMcpCfg ? ' --mcp-config ' + spawnMcpCfg : '') + (state.hasHistory ? ' --continue' : '') + ' --verbose <msg>' + '\n');
        let proc;
        try {
            proc = spawn(LAUNCHER, ['-e', evalCode], { env, cwd: spawnCwd });
        } catch(e) {
            log('[runMessage] spawn failed: ' + e.message + '\n');
            try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[spawn error] ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
            return;
        }
        // Close stdin immediately — claude-code --print reads from stdin when it is a pipe
        // and blocks waiting for EOF if stdin stays open. Closing it tells claude-code stdin
        // is empty so it uses the argv message. Tool approval is handled by
        // permissions.allow:['*'] in settings.json; no y/n stdin input is needed.
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

            // ── Permission responses — bypass busy guard ──────────────────────────
            // Tool already ran (auto-approved on detection to beat claude-code's 3s
            // stdin timeout). These buttons configure FUTURE spawns only.
            if (line.startsWith('!perm-allow') || line.startsWith('!perm-always') || line.startsWith('!perm-deny')) {
                const perm = state.pendingPerm;
                if (!perm) continue;
                if (line.startsWith('!perm-always')) {
                    const list = loadApproveList();
                    // T2: optional ':<pattern>' suffix saves a pattern entry like 'Bash(git *)'.
                    // No suffix → legacy behavior: save the bare tool name.
                    let entry = perm.toolName;
                    const colonIdx = line.indexOf(':');
                    if (colonIdx > -1) {
                        const pat = line.slice(colonIdx + 1).trim();
                        if (pat) entry = pat;
                    }
                    if (!list.allow.includes(entry)) {
                        list.allow.push(entry);
                        saveApproveList(list);
                    }
                } else if (line.startsWith('!perm-deny')) {
                    // "Block future" — add to deny list so next spawn never prompts
                    const list = loadApproveList();
                    if (!list.deny.includes(perm.toolName)) {
                        list.deny.push(perm.toolName);
                        saveApproveList(list);
                    }
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m[' + perm.toolName + ' blocked for future sessions]\x1b[0m\r\n'); } catch(_) {}
                }
                // !perm-allow = dismiss dialog — tool already ran, no list change
                state.pendingPerm = null;
                continue;
            }

            // ── !approve-tools — pre-approve ALL tools, silence permission cards ──
            // Adds a '*' sentinel to auto_approve.json. From then on every tool runs
            // without a permission card (isToolAlreadyAllowed short-circuits on '*').
            // Persists across spawns. `!approve-tools off` reverts to per-tool cards.
            if (line.startsWith('!approve-tools')) {
                const arg = line.slice('!approve-tools'.length).trim().toLowerCase();
                const list = loadApproveList();
                if (arg === 'off') {
                    list.allow = (list.allow || []).filter(e => e !== '*');
                    saveApproveList(list);
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m[approve-tools OFF — permission cards will show again]\x1b[0m\r\n'); } catch(_) {}
                } else if (arg === 'status') {
                    const on = (list.allow || []).includes('*');
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[2m[approve-tools is ' + (on ? 'ON' : 'OFF') + ']\x1b[0m\r\n'); } catch(_) {}
                } else {
                    if (!(list.allow || []).includes('*')) {
                        list.allow = list.allow || [];
                        list.allow.push('*');
                        saveApproveList(list);
                    }
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[32m[approve-tools ON — all tools auto-approved, no permission cards (sub-agent panel still shows). Use !approve-tools off to revert]\x1b[0m\r\n'); } catch(_) {}
                }
                continue;
            }

            // ── !clear — interrupt current process and reset session ──────────────
            if (line.startsWith('!clear')) {
                if (state.currentProc) {
                    state.currentProc._manualKill = true;
                    try { state.currentProc.kill('SIGTERM'); } catch(_) {}
                    state.currentProc = null;
                }
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
                    '  \x1b[33m!approve-tools [off]\x1b[0m Auto-approve all tools, hide permission cards\r\n' +
                    '  \x1b[33m!install [pkg]\x1b[0m      Install binary/npm (no arg = list available)\r\n' +
                    '  \x1b[33m!log [n|all|clear]\x1b[0m   Show last n lines (default 100); !log all = full; !log clear = wipe\r\n' +
                    '  \x1b[33m!mcp\x1b[0m                List connected (and failed) MCP servers and tools\r\n' +
                    '  \x1b[33m!mcp-log [name|all]\x1b[0m Show captured stderr from stdio MCP servers (default 50 lines)\r\n' +
                    '  \x1b[33m!mcp-reload\x1b[0m         Apply Settings toggles without restarting the session\r\n' +
                    '  \x1b[33m!test-cli\x1b[0m           Run module-loader + proxy diagnostics\r\n' +
                    '  \x1b[33m!test-msg [text]\x1b[0m    Run exact runMessage path (patchSettings+stdin) — use to diagnose hangs\r\n' +
                    '  \x1b[33m!test-agent\x1b[0m         Probe sub-agent dispatch via the --agents flag (inline JSON)\r\n' +
                    '  \x1b[33m!test-proot\x1b[0m         Probe: does bundled proot exec from nativeLibDir (Ubuntu engine)\r\n' +
                    '  \x1b[33m!test-rootfs\x1b[0m        Probe: run extracted Ubuntu rootfs via proot (cat /etc/os-release)\r\n' +
                    '  \x1b[33m!setup-engine\x1b[0m       Batched P1: boot rootfs → install Node 22 + claude-code → claude --version\r\n' +
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

            // ── !test-msg — run exact runMessage code path with a test message ───
            // Unlike !test-cli step [3], this calls patchSettings + writes y\n to
            // stdin — exactly what a real message does. If this hangs but step [3]
            // works, the issue is in patchSettings or the stdin writes.
            if (line.startsWith('!test-msg')) {
                const testText = line.slice(9).trim() || 'hello';
                try {
                    const tcfg = readConfig();
                    patchSettings(tcfg);
                    const tEnv  = buildEnv();
                    const tCliUrl = 'file://' + CLAUDE_CLI;
                    const tArgv =
                        'process.argv[2]="--output-format";process.argv[3]="stream-json";' +
                        'process.argv[4]="--print";process.argv[5]="--verbose";' +
                        'process.argv[6]=' + JSON.stringify(testText) + ';process.argv.length=7;';
                    const tEval =
                        'process.stderr.write("[eval-ok]\\n");' +
                        regexpShim + intlShim +
                        'process.argv[1]=' + JSON.stringify(CLAUDE_CLI) + ';' +
                        tArgv +
                        'import(' + JSON.stringify(tCliUrl) + ')' +
                        '.catch(function(e){process.stderr.write("ERR:"+String(e)+"\\n");process.exit(1);});';
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m!test-msg: spawning with patchSettings+stdin (30s timeout)…\x1b[0m\r\n'); } catch(_) {}
                    const tch = spawn(LAUNCHER, ['-e', tEval], { env: tEnv, cwd: FILES_DIR });
                    try { tch.stdin.end(); } catch(_) {}
                    let tOut = '', tErr = '', tDone = false;
                    tch.stdout.on('data', d => { tOut += d.toString(); });
                    tch.stderr.on('data', d => { tErr += d.toString(); });
                    const tTid = setTimeout(() => {
                        if (tDone) return;
                        tDone = true;
                        try { tch.kill(); } catch(_) {}
                        try { if (state.socket) state.socket.write(SYS_FENCE +
                            '\x1b[31m!test-msg: TIMEOUT 30s — POST never reached\x1b[0m\r\n' +
                            '\x1b[2mstdout: ' + tOut.slice(0, 300) + '\x1b[0m\r\n' +
                            '\x1b[2mstderr: ' + tErr.slice(0, 300) + '\x1b[0m\r\n'); } catch(_) {}
                    }, 30000);
                    tch.on('close', code => {
                        if (tDone) return;
                        tDone = true;
                        clearTimeout(tTid);
                        const gotResponse = tOut.includes('"type":"result"') || tOut.includes('"type":"assistant"');
                        const mark = gotResponse ? '\x1b[32m✓' : '\x1b[31m✗';
                        const first = tOut.split('\n').find(l => l.trim().startsWith('{')) || '';
                        try { if (state.socket) state.socket.write(SYS_FENCE +
                            mark + ' !test-msg exit=' + code + '\x1b[0m\r\n' +
                            (gotResponse
                                ? '\x1b[32mPOST reached — claude responded!\x1b[0m\r\n' + first.slice(0, 150) + '\r\n'
                                : '\x1b[31mNo response from claude\x1b[0m\r\n' +
                                  '\x1b[2mstdout: ' + tOut.slice(0, 300) + '\x1b[0m\r\n' +
                                  '\x1b[2mstderr: ' + tErr.slice(0, 300) + '\x1b[0m\r\n'
                            )); } catch(_) {}
                    });
                } catch(e) {
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[!test-msg error] ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
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
            if (line.startsWith('!hotload')) {
                const w = (s) => { try { if (state.socket) state.socket.write(SYS_FENCE + s); } catch(_) {} };
                const arg = line.slice('!hotload'.length).trim();
                const devPath = path.join(FILES_DIR, 'bridge_dev.js');
                if (arg === 'reset') {
                    try { fs.unlinkSync(devPath); w('\x1b[32m✓ hot-load reset\x1b[0m\r\n'); }
                    catch(_) { w('\x1b[33m(no dev bridge to reset)\x1b[0m\r\n'); }
                    w('\x1b[2mForce-stop + reopen the app to apply (bundled bridge.js).\x1b[0m\r\n');
                    continue;
                }
                // Fetch via the GitHub *API* contents endpoint, NOT raw.githubusercontent
                // .com. The raw CDN (Fastly) serves ~5-min-stale copies and ignores our
                // ?t= cache-buster, so hotloads kept loading old code. The API serves the
                // branch HEAD (ETag-based), and `Accept: …raw` returns the file verbatim.
                const url = 'https://api.github.com/repos/fahmi304/Nexus-Mind/contents/' +
                            'app/src/main/assets/nodejs-project/bridge.js?ref=feat/custom-agents';
                const tmp = devPath + '.tmp';
                w('\x1b[33m!hotload: fetching latest bridge.js (GitHub API, running build ' + BRIDGE_BUILD + ')…\x1b[0m\r\n');
                (async () => {
                    const res = await httpsGet(url, { headers: {
                        'Accept': 'application/vnd.github.raw',
                        'User-Agent': 'nexus-hotload',
                        'Cache-Control': 'no-cache',
                    }});
                    if (res.statusCode !== 200) { res.resume(); throw new Error('HTTP ' + res.statusCode + ' (API)'); }
                    let txt = ''; res.setEncoding('utf8');
                    await new Promise((rs, rj) => { res.on('data', c => txt += c); res.on('end', rs); res.on('error', rj); });
                    if (txt.length > 5000 && txt.includes('SYS_FENCE')) {
                        const m = txt.match(/BRIDGE_BUILD\s*=\s*'([^']+)'/);
                        const dlBuild = m ? m[1] : '(no stamp)';
                        fs.writeFileSync(devPath, txt);
                        w('\x1b[32m✓ hot-loaded ' + txt.length + ' bytes → build ' + dlBuild + '\x1b[0m\r\n' +
                          (dlBuild === BRIDGE_BUILD
                            ? '\x1b[33m⚠ downloaded build == running build (already current, or you just need to force-stop+reopen)\x1b[0m\r\n'
                            : '') +
                          '\x1b[36mNow FORCE-STOP the app and reopen it. After reopen, run a command — it will show build ' + dlBuild + '.\x1b[0m\r\n');
                    } else {
                        w('\x1b[31m✗ download invalid (size=' + txt.length + ') — kept current\x1b[0m\r\n');
                    }
                })().catch(e => w('\x1b[31m✗ hotload failed: ' + (e && e.message) + '\x1b[0m\r\n'));
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
                // so the seccomp combo that !fix-seccomp found can be applied to the
                // WHOLE install chain WITHOUT a rebuild. ge() threads it into every
                // guest call. (Once confirmed, the winning combo is baked into the
                // runProotGuest base env permanently.)
                const seEnv = {};
                for (const t of line.slice('!setup-engine'.length).trim().split(/\s+/).filter(Boolean)) {
                    if (t.startsWith('ENV=')) { const kv = t.slice(4); const i = kv.indexOf('='); if (i > 0) seEnv[kv.slice(0, i)] = kv.slice(i + 1); }
                }
                const ge = (cmd, t, onData) => runProotGuest(cmd, t, onData, { extraEnv: seEnv });
                if (Object.keys(seEnv).length) w(D + 'env: ' + Object.entries(seEnv).map(([k, v]) => k + '=' + v).join(' ') + X + '\r\n');
                (async () => {
                    const rp = path.join(FILES_DIR, 'ubuntu');
                    if (!fs.existsSync(path.join(rp, 'etc', 'os-release'))) {
                        w(R + '✗ no rootfs at ' + rp + X + '\r\n' +
                          D + 'Extract it first: Settings → 🐞 Ubuntu engine → Install + probe.' + X + '\r\n');
                        return;
                    }
                    // DNS for npm: base rootfs resolv.conf is often a dangling symlink.
                    try { fs.unlinkSync(path.join(rp, 'etc', 'resolv.conf')); } catch(_) {}
                    try { fs.writeFileSync(path.join(rp, 'etc', 'resolv.conf'), 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n'); } catch(_) {}
                    const fail = (label, r) => w(R + '✗ ' + label + ' (code=' + r.code + ')' + X + '\r\n' +
                        D + (r.out.trim().slice(-700) || '(no output)') + X + '\r\n');

                    // 1) boot
                    w(D + '(build ' + BRIDGE_BUILD + ')' + X + '\r\n');
                    w(Y + '[1/6] proot boot…' + X + '\r\n');
                    let r = await ge(['/usr/bin/cat', '/etc/os-release'], 60000);
                    if (!/Ubuntu/i.test(r.out)) { fail('boot failed', r); return; }
                    const pretty = (r.out.match(/PRETTY_NAME="?([^"\n]+)/) || [])[1] || 'Ubuntu';
                    w(G + '✓ booted: ' + pretty + X + '\r\n');

                    // 2) write test
                    w(Y + '[2/6] write test (/root, /tmp)…' + X + '\r\n');
                    r = await ge(['/bin/sh', '-c', 'echo ok > /root/.wtest && echo ok > /tmp/.wtest && cat /root/.wtest /tmp/.wtest && rm -f /root/.wtest /tmp/.wtest'], 30000);
                    if (r.code !== 0) { fail('write test failed', r); return; }
                    w(G + '✓ guest filesystem writable' + X + '\r\n');

                    // 3) Node 22 — reuse if present
                    w(Y + '[3/6] node…' + X + '\r\n');
                    r = await ge(['/opt/node/bin/node', '--version'], 30000);
                    if (r.code !== 0) {
                        const nodeUrl = 'https://nodejs.org/dist/v22.22.3/node-v22.22.3-linux-arm64.tar.gz';
                        const dest = path.join(FILES_DIR, 'node22.tar.gz');
                        w(D + '  downloading Node 22 (.tar.gz ~30 MB)…' + X + '\r\n');
                        try { await downloadFile(nodeUrl, dest); }
                        catch (e) { w(R + '✗ node download failed: ' + e.message + X + '\r\n'); return; }
                        // Decompress the .gz HERE in node (zlib), not in the guest:
                        // `tar -xzf` forks the external `gzip` binary, and that exec
                        // ENOSYS'd on-device. Producing a plain .tar lets the guest
                        // use `tar -xf` (no compression filter, no forked gzip).
                        w(D + '  decompressing (node zlib)…' + X + '\r\n');
                        const tarPath = path.join(FILES_DIR, 'node22.tar');
                        try {
                            const zlib = require('zlib');
                            await new Promise((res, rej) => {
                                fs.createReadStream(dest)
                                  .pipe(zlib.createGunzip())
                                  .pipe(fs.createWriteStream(tarPath))
                                  .on('finish', res).on('error', rej);
                            });
                        } catch (e) { w(R + '✗ gunzip failed: ' + e.message + X + '\r\n'); try { fs.unlinkSync(dest); } catch(_){} return; }
                        try { fs.unlinkSync(dest); } catch(_) {}
                        w(D + '  extracting into /opt/node (tar -xf, no gzip fork)…' + X + '\r\n');
                        r = await ge(['/bin/sh', '-c',
                            'mkdir -p /opt && tar -xf /root/.nexus/node22.tar -C /opt && ' +
                            'rm -rf /opt/node && mv /opt/node-v22.22.3-linux-arm64 /opt/node && ' +
                            '/opt/node/bin/node --version'], 180000);
                        try { fs.unlinkSync(tarPath); } catch(_) {}
                        if (r.code !== 0) { fail('node extract/run failed', r); return; }
                    }
                    w(G + '✓ node ' + r.out.trim() + X + '\r\n');

                    // 3b) getcwd probe — npm aborts with uv_cwd ENOSYS if process.cwd()
                    // fails under proot. Isolate it: pwd (shell) + node process.cwd().
                    w(Y + '[3b/6] getcwd probe…' + X + '\r\n');
                    r = await ge(['/bin/sh', '-c',
                        'echo "pwd=$(pwd 2>&1)"; cd /root; echo "pwd2=$(pwd 2>&1)"; ' +
                        '/opt/node/bin/node -e "try{process.stdout.write(\'node.cwd=\'+process.cwd())}catch(e){process.stdout.write(\'node.cwd ERR \'+e.code+\' \'+e.syscall)}"'], 30000);
                    w(D + '  ' + r.out.trim().replace(/\n/g, ' | ') + X + '\r\n');

                    // 4) npm
                    w(Y + '[4/6] npm…' + X + '\r\n');
                    r = await ge(['/bin/sh', '-c', 'npm --version'], 60000);
                    if (r.code !== 0) { fail('npm failed', r); return; }
                    w(G + '✓ npm ' + r.out.trim() + X + '\r\n');

                    // 5) install claude-code (stream tail so it doesn't look hung)
                    w(Y + '[5/6] npm i -g @anthropic-ai/claude-code (network; can take a few min)…' + X + '\r\n');
                    let lastMark = Date.now();
                    r = await ge(['/bin/sh', '-c',
                        'npm i -g @anthropic-ai/claude-code 2>&1'], 600000,
                        () => { const now = Date.now(); if (now - lastMark > 15000) { lastMark = now; w(D + '  …still installing…' + X + '\r\n'); } });
                    if (r.code !== 0) { fail('npm install failed', r); return; }
                    w(G + '✓ claude-code installed' + X + '\r\n');

                    // 6) claude --version  (the real acceptance)
                    w(Y + '[6/6] claude --version…' + X + '\r\n');
                    r = await ge(['/bin/sh', '-c', 'claude --version'], 60000);
                    if (r.code !== 0) { fail('claude --version failed', r); return; }
                    w(G + '✅ ENGINE READY — ' + r.out.trim() + X + '\r\n' +
                      G + '   P1 COMPLETE: latest claude-code runs on glibc via proot.' + X + '\r\n');
                })().catch(e => w(R + '[!setup-engine error] ' + (e && e.message) + X + '\r\n'));
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
                const tag = 'NEXUS_AGENT_OK_' + Date.now().toString(36);
                const agentsDir = path.join(FILES_DIR, '.claude', 'agents');
                const agentFile = path.join(agentsDir, 'nexus_probe.md');
                // Sentinel file: the sub-agent writes the tag here. This proves the
                // sub-agent actually executed even when claude-code (print mode)
                // doesn't surface the sub-agent's internal reply into parent stdout
                // — so the tag-in-stdout check alone can miss a successful run.
                const sentinelFile = path.join(FILES_DIR, 'nexus_probe_result.txt');
                try { fs.unlinkSync(sentinelFile); } catch(_) {}
                const probePrompt =
                    'You are a diagnostic sub-agent. When invoked, do exactly these two steps:\n' +
                    '1. Use the Write tool to create the file ' + sentinelFile + ' whose entire contents are exactly this string and nothing else: ' + tag + '\n' +
                    '2. Then reply with exactly this string and nothing else: ' + tag + '\n';
                // Inject the agent via the --agents flag (inline JSON) instead of a
                // ~/.claude/agents/*.md file. File discovery is broken in 2.1.112 print
                // mode (CLAUDE.md known gaps); --agents has HIGHER precedence and no file
                // dependency — the same move that rescued MCP (--mcp-config, inv 65a).
                // This probe verifies whether 2.1.112 actually accepts the flag.
                const agentsJson = JSON.stringify({
                    nexus_probe: {
                        description: 'Internal connectivity probe — confirms sub-agent dispatch via --agents.',
                        prompt: probePrompt,
                        tools: ['Write'],
                    }
                });
                const testText = 'Use the Task tool (it may also be named Agent) to dispatch a sub-agent with subagent_type "nexus_probe". ' +
                    'Do NOT use the Bash tool and do NOT run any shell command — "nexus_probe" is a sub-agent, not an executable. ' +
                    'After the sub-agent replies, tell me the exact string it returned.';
                const readSentinel = () => { try { return fs.readFileSync(sentinelFile, 'utf8'); } catch(_) { return ''; } };
                // Pull the Task tool's tool_result (the sub-agent's returned text/error)
                // out of the parent stream-json. This disambiguates a "ran: no": empty =
                // the sub-agent never returned to the parent; "[ERROR] …" = it dispatched
                // but failed (bad subagent_type, Write blocked, etc.); plain text without
                // the tag = it ran but ignored the Write/reply instructions.
                const extractTaskResult = (out) => {
                    let taskId = null, txt = '';
                    for (const ln of out.split('\n')) {
                        const s = ln.trim(); if (!s) continue;
                        let obj; try { obj = JSON.parse(s); } catch(_) { continue; }
                        const content = obj && obj.message && obj.message.content;
                        if (!Array.isArray(content)) continue;
                        for (const b of content) {
                            if (!b) continue;
                            if (!taskId && b.type === 'tool_use' && (b.name === 'Task' || b.name === 'Agent')) {
                                taskId = b.id;
                            } else if (taskId && b.type === 'tool_result' && b.tool_use_id === taskId) {
                                const c = b.content;
                                txt = (typeof c === 'string') ? c
                                    : Array.isArray(c) ? c.map(x => (x && x.text) || '').join('') : '';
                                if (b.is_error) txt = '[ERROR] ' + txt;
                            }
                        }
                    }
                    return txt;
                };
                const cleanup = () => {
                    try { fs.unlinkSync(agentFile); } catch(_) {}
                    try { fs.unlinkSync(sentinelFile); } catch(_) {}
                };
                try {
                    const tcfg = readConfig();
                    patchSettings(tcfg);
                    const tEnv  = buildEnv();
                    const tCliUrl = 'file://' + CLAUDE_CLI;
                    // --agents <json> injected before the message; --verbose kept LAST
                    // before the positional message (inv 65b — a boolean flag must
                    // terminate any preceding value-taking option so the message isn't
                    // swallowed, the bug that bit --mcp-config).
                    const tArgv =
                        'process.argv[2]="--output-format";process.argv[3]="stream-json";' +
                        'process.argv[4]="--print";' +
                        'process.argv[5]="--agents";process.argv[6]=' + JSON.stringify(agentsJson) + ';' +
                        'process.argv[7]="--verbose";' +
                        'process.argv[8]=' + JSON.stringify(testText) + ';process.argv.length=9;';
                    const tEval =
                        'process.stderr.write("[eval-ok]\\n");' +
                        regexpShim + intlShim +
                        'process.argv[1]=' + JSON.stringify(CLAUDE_CLI) + ';' +
                        tArgv +
                        'import(' + JSON.stringify(tCliUrl) + ')' +
                        '.catch(function(e){process.stderr.write("ERR:"+String(e)+"\\n");process.exit(1);});';
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m!test-agent: injecting nexus_probe via --agents flag, spawning claude (90s timeout)…\x1b[0m\r\n'); } catch(_) {}
                    const tch = spawn(LAUNCHER, ['-e', tEval], { env: tEnv, cwd: FILES_DIR });
                    try { tch.stdin.end(); } catch(_) {}
                    let tOut = '', tErr = '', tDone = false;
                    tch.stdout.on('data', d => { tOut += d.toString(); });
                    tch.stderr.on('data', d => { tErr += d.toString(); });
                    const tTid = setTimeout(() => {
                        if (tDone) return;
                        tDone = true;
                        try { tch.kill(); } catch(_) {}
                        cleanup();
                        try { if (state.socket) state.socket.write(SYS_FENCE +
                            '\x1b[31m✗ !test-agent: TIMEOUT 90s\x1b[0m\r\n' +
                            '\x1b[2mstdout (first 400): ' + tOut.slice(0, 400) + '\x1b[0m\r\n' +
                            '\x1b[2mstderr (first 200): ' + tErr.slice(0, 200) + '\x1b[0m\r\n'); } catch(_) {}
                    }, 90000);
                    tch.on('close', code => {
                        if (tDone) return;
                        tDone = true;
                        clearTimeout(tTid);
                        // Probe markers: did Task fire? did the sub-agent actually run?
                        // The sentinel file is the authoritative signal — it can only
                        // exist if the sub-agent executed and used its Write tool, even
                        // when the tag never bubbles up into the parent's stdout.
                        const fileTag   = readSentinel().includes(tag);
                        const taskFired = /"type"\s*:\s*"tool_use"[^}]*"name"\s*:\s*"(Task|Agent)"/.test(tOut);
                        const tagSeen   = tOut.includes(tag);
                        const gotResult = tOut.includes('"type":"result"');
                        cleanup();
                        const ran = tagSeen || fileTag;   // sub-agent definitively executed
                        const mark = (taskFired && ran) ? '\x1b[32m✓' : (taskFired ? '\x1b[33m~' : '\x1b[31m✗');
                        let report = mark + ' !test-agent exit=' + code + '\x1b[0m\r\n';
                        // Go/no-go for the --agents flag itself (commander prints
                        // "error: unknown option '--agents'" + exits non-zero if absent).
                        const flagRejected = /unknown option/.test(tErr) || /unknown option[^\n]*--agents/.test(tOut);
                        report += '  --agents flag:      ' + (flagRejected
                            ? '\x1b[31mREJECTED — not supported by 2.1.112\x1b[0m'
                            : '\x1b[32maccepted\x1b[0m') + '\r\n';
                        report += '  Task tool fired:    ' + (taskFired ? '\x1b[32myes\x1b[0m' : '\x1b[31mno\x1b[0m') + '\r\n';
                        report += '  Sub-agent ran:      ' + (ran ? '\x1b[32myes' + (fileTag ? ' (sentinel file)' : ' (stdout tag)') + '\x1b[0m' : '\x1b[31mno\x1b[0m') + '\r\n';
                        report += '  Tag in stdout:      ' + (tagSeen   ? '\x1b[32myes\x1b[0m' : '\x1b[2mno\x1b[0m') + '\r\n';
                        report += '  Got final result:   ' + (gotResult ? '\x1b[32myes\x1b[0m' : '\x1b[31mno\x1b[0m') + '\r\n';
                        if (taskFired) {
                            const taskResult = extractTaskResult(tOut);
                            report += '  Task returned:      ' + (taskResult
                                ? '\x1b[2m' + taskResult.slice(0, 300).replace(/\r?\n/g, ' ') + '\x1b[0m'
                                : '\x1b[31m(nothing — sub-agent never returned to parent)\x1b[0m') + '\r\n';
                        }
                        if (!taskFired || !ran) {
                            report += '\x1b[2mstdout (last 400): ' + tOut.slice(-400) + '\x1b[0m\r\n';
                            if (tErr) report += '\x1b[2mstderr (first 200): ' + tErr.slice(0, 200) + '\x1b[0m\r\n';
                        }
                        try { if (state.socket) state.socket.write(SYS_FENCE + report); } catch(_) {}
                    });
                } catch(e) {
                    cleanup();
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[!test-agent error] ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
                }
                continue;
            }

            // MCP-6: !mcp-reload — re-read config + start/stop servers without
            // resetting the session. Same path the Kotlin marker-file watcher takes.
            if (line.startsWith('!mcp-reload')) {
                try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[2m[mcp-reload] reloading…\x1b[0m\r\n'); } catch(_) {}
                reloadMcpServers().then(s => {
                    const msg = '[mcp-reload] started ' + s.startedStdio + ' stdio + ' + s.startedHttp + ' http, stopped ' + s.stoppedStdio + ' stdio + ' + s.stoppedHttp + ' http\r\n';
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[32m' + msg + '\x1b[0m'); } catch(_) {}
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
                // MCP-5: include failed servers so users see what's misconfigured.
                for (const [name, info] of mcpFailed.entries()) {
                    out += '  \x1b[31m✗\x1b[0m \x1b[33m' + name + '\x1b[0m \x1b[2m(' + info.type + ', failed)\x1b[0m\r\n';
                    out += '    \x1b[31m' + (info.error || '').slice(0, 200) + '\x1b[0m\r\n';
                }
                if (total === 0 && mcpFailed.size === 0) out += '  \x1b[2m(no MCP servers connected)\x1b[0m\r\n';
                if (mcpFailed.size > 0) out += '\r\n  \x1b[2muse !mcp-log to see captured stderr\x1b[0m\r\n';
                try { if (state.socket) state.socket.write(SYS_FENCE + out); } catch(_) {}
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

            if (line.startsWith('!test-cli')) {
                const sock2 = state.socket;
                try { if (sock2) sock2.write(SYS_FENCE + '\r\n\x1b[33mRunning module-loader diagnostic (4 steps)…\x1b[0m\r\n'); } catch(_) {}
                const env2 = buildEnv();
                const cliUrl2 = 'file://' + CLAUDE_CLI;
                const exitLog2 = JSON.stringify(SETUP_LOG);

                function runEvalStep2(label, evalCode2, cb) {
                    let out = '', err = '';
                    let cbCalled = false;
                    function onceCb() { if (!cbCalled) { cbCalled = true; cb(); } }
                    let ch;
                    try { ch = spawn(LAUNCHER, ['-e', evalCode2], { env: env2, cwd: FILES_DIR }); ch.stdin.end(); }
                    catch(e) { try { if (sock2) sock2.write(SYS_FENCE + '\x1b[31m  ' + label + ': spawn-err ' + e.message + '\x1b[0m\r\n'); } catch(_) {} onceCb(); return; }
                    ch.stdout.on('data', d => { out += d.toString(); });
                    ch.stderr.on('data', d => { err += d.toString(); });
                    const tid = setTimeout(() => { try { ch.kill(); } catch(_) {} try { if (sock2) sock2.write(SYS_FENCE + '\x1b[31m  ' + label + ': TIMEOUT\x1b[0m\r\n'); } catch(_) {} onceCb(); }, 30000);
                    ch.on('close', code => {
                        clearTimeout(tid);
                        log('[test-cli] ' + label + ' exit=' + code + ' out=' + JSON.stringify(out.slice(0,200)) + ' err=' + JSON.stringify(err.slice(0,300)) + '\n');
                        if (!cbCalled) {
                            const mark = code === 0 ? '\x1b[32m✓' : '\x1b[31m✗';
                            let msg2 = mark + ' ' + label + ' exit=' + code + '\x1b[0m';
                            if (out.trim()) msg2 += '  out:' + out.trim().slice(0,80);
                            if (err.trim()) msg2 += '\r\n    \x1b[31merr:' + err.trim().slice(0,200) + '\x1b[0m';
                            try { if (sock2) sock2.write(SYS_FENCE + '  ' + msg2 + '\r\n'); } catch(_) {}
                        }
                        onceCb();
                    });
                }

                const netTestCode =
                    'var net=require("net");var c=net.connect(' + PROXY_PORT + ',"' + HOST + '",function(){' +
                    'process.stdout.write("net-ok\\n");c.destroy();process.exit(0);});' +
                    'c.on("error",function(e){process.stdout.write("net-fail:"+e.message+"\\n");process.exit(1);});' +
                    'setTimeout(function(){process.stdout.write("net-timeout\\n");process.exit(1);},5000);';

                runEvalStep2('[1] node launcher self-test',
                    "process.stdout.write('ok\\n');",
                runEvalStep2.bind(null, '[2] import cli.js --version',
                    'process.argv[1]=' + JSON.stringify(CLAUDE_CLI) + ';process.argv[2]="--version";process.argv.length=3;' +
                    'import(' + JSON.stringify(cliUrl2) + ').catch(function(e){process.stderr.write("ERR:"+String(e)+"\\n");process.exit(1)});',
                runEvalStep2.bind(null, '[3] cli.js --print hello',
                    'process.stderr.write("[eval-ok]\\n");' +
                    'process.on("unhandledRejection",function(r){try{require("fs").appendFileSync(' + exitLog2 + ',"[unhandledRejection] "+String(r&&(r.stack||r.message)||r).slice(0,600)+"\\n");}catch(_){}});' +
                    regexpShim + intlShim +
                    'process.argv[1]=' + JSON.stringify(CLAUDE_CLI) + ';' +
                    'process.argv[2]="--output-format";process.argv[3]="stream-json";' +
                    'process.argv[4]="--print";process.argv[5]="--verbose";' +
                    'process.argv[6]="hello";process.argv.length=7;' +
                    'import(' + JSON.stringify(cliUrl2) + ')' +
                    '.then(function(){try{require("fs").appendFileSync(' + exitLog2 + ',"[import-resolved]\\n");}catch(_){}})' +
                    '.catch(function(e){process.stderr.write("ERR:"+String(e)+"\\n");process.exit(1)});',
                runEvalStep2.bind(null, '[4] net: connect to proxy port ' + PROXY_PORT,
                    netTestCode,
                () => { try { if (sock2) sock2.write(SYS_FENCE + '\x1b[33mDone. Type !log for details.\x1b[0m\r\n'); } catch(_) {} }))));
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

    // ── TCP server ────────────────────────────────────────────────────────────
    const server = net.createServer(rawSocket => {
        if (!isClaudeInstalled()) {
            try { rawSocket.write('\r\n\x1b[31mClaude Code not installed — run Setup from the app.\x1b[0m\r\n'); rawSocket.end(); } catch(_) {}
            return;
        }
        let hdrBuf = '';
        function onHeader(d) {
            hdrBuf += d.toString();
            const nl = hdrBuf.indexOf('\n');
            if (nl === -1) { rawSocket.once('data', onHeader); return; }
            const firstLine = hdrBuf.slice(0, nl).trim();
            const leftover  = hdrBuf.slice(nl + 1);
            hdrBuf = '';
            rawSocket.removeListener('data', onHeader);
            // Require SESSION:<sid>:<token> header — reject anything else.
            if (!firstLine.startsWith('SESSION:')) {
                try { rawSocket.write('\r\n\x1b[31mUnauthorized connection rejected.\x1b[0m\r\n'); rawSocket.end(); } catch(_) {}
                return;
            }
            const parts = firstLine.slice(8).split(':');
            const sid   = parts[0] || '0';
            const token = parts[1] || '';
            let expectedToken = '';
            try { expectedToken = fs.readFileSync(path.join(FILES_DIR, 'local_token'), 'utf8').trim().slice(0, 200); } catch(_) {}
            // Reject if token missing/empty OR if presented token does not match.
            // An empty expectedToken must never match anything — reject all.
            if (!expectedToken || token !== expectedToken) {
                try { rawSocket.write('\r\n\x1b[31mUnauthorized connection rejected.\x1b[0m\r\n'); rawSocket.end(); } catch(_) {}
                return;
            }
            attachSession(sid, rawSocket, leftover);
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

// Write FILES_DIR/bin/claude and FILES_DIR/bin/node wrappers so that
// sub-agents spawned by claude (via the Task tool) can find and run claude.
// The claude wrapper injects the regexp/intl shims the same way the PTY session does.
function writeSubagentWrappers() {
    try {
        fs.mkdirSync(BIN_DIR, { recursive: true });

        // bash symlink → /system/bin/sh. claude-code v2.1.112's shell detection
        // REJECTS any shell whose path does not contain "bash"/"zsh" (it filters
        // $SHELL on `K.includes("bash")||K.includes("zsh")` and only probes the
        // hardcoded /bin/bash, /usr/bin/zsh … which don't exist on Android). So
        // SHELL=/system/bin/sh alone never registers — Bash tool dies with "No
        // suitable shell found". A symlink NAMED bash satisfies the includes()
        // check; it resolves to /system/bin/sh on the /system mount (NOT noexec),
        // so execve succeeds — unlike the wrapper *scripts* below, which live on
        // /data (noexec) and can't be exec'd. CLAUDE_CODE_SHELL (buildEnv) points
        // here. See claude-code cli.js NzY()/o47().
        try {
            const bashLink = path.join(BIN_DIR, 'bash');
            try { fs.unlinkSync(bashLink); } catch(_) {}
            fs.symlinkSync('/system/bin/sh', bashLink);
            log('[shell] bash symlink → /system/bin/sh at ' + bashLink + '\n');
        } catch(e) { log('[shell] bash symlink failed: ' + e.message + '\n'); }

        // node wrapper — lets npm bin scripts find Node.js via our launcher
        const nodePath = path.join(BIN_DIR, 'node');
        const nodeScript = '#!/system/bin/sh\nexec ' + LAUNCHER + ' "$@"\n';
        fs.writeFileSync(nodePath, nodeScript, 'utf8');
        try { fs.chmodSync(nodePath, 0o755); } catch(_) {}

        // claude_eval.js — CJS bootstrap loaded by the claude wrapper via require()
        const evalFilePath = path.join(BIN_DIR, 'claude_eval.js');
        const evalContent =
            '// Auto-generated by bridge.js — do not edit.\n' +
            'process.argv[0] = "node";\n' +
            'process.argv[1] = ' + JSON.stringify(CLAUDE_CLI) + ';\n' +
            // regexpShim and intlShim are module-level constants in bridge.js
            regexpShim + '\n' +
            intlShim + '\n' +
            'import(' + JSON.stringify('file://' + CLAUDE_CLI) + ')\n' +
            '  .catch(function(e) { process.stderr.write("sub-agent-err:" + String(e) + "\\n"); process.exit(1); });\n';
        fs.writeFileSync(evalFilePath, evalContent, 'utf8');

        // claude wrapper — uses LAUNCHER + require(claude_eval.js) to run cli.js
        // The 'dummy' placeholder becomes argv[1] which claude_eval.js overwrites with CLI path.
        const claudePath = path.join(BIN_DIR, 'claude');
        const claudeScript =
            '#!/system/bin/sh\n' +
            'exec ' + LAUNCHER + ' -e "require(' + "'" + evalFilePath + "'" + ')" dummy "$@"\n';
        fs.writeFileSync(claudePath, claudeScript, 'utf8');
        try { fs.chmodSync(claudePath, 0o755); } catch(_) {}

        log('[sub-agents] wrappers written to ' + BIN_DIR + '\n');
    } catch(e) {
        log('[sub-agents] failed to write wrappers: ' + e.message + '\n');
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

// Pre-create/patch claude settings so the theme/onboarding picker never appears
// and the proxy dummy key is always in the approved list.
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
    // Approve the proxy dummy key so claude-code accepts it silently in interactive
    // mode without showing the login selector (VE(key) = key.slice(-20)).
    // Also purge it from rejected — if it ended up there from a previous failed
    // auth attempt or the user dismissed the confirmation dialog, it stays rejected
    // forever unless we explicitly remove it, and rejected takes priority over approved.
    if (!s.customApiKeyResponses) s.customApiKeyResponses = { approved: [], rejected: [] };
    if (!Array.isArray(s.customApiKeyResponses.approved)) s.customApiKeyResponses.approved = [];
    if (!Array.isArray(s.customApiKeyResponses.rejected)) s.customApiKeyResponses.rejected = [];
    if (!s.customApiKeyResponses.approved.includes('sk-ant-proxy000'))
        s.customApiKeyResponses.approved.push('sk-ant-proxy000');
    s.customApiKeyResponses.rejected =
        s.customApiKeyResponses.rejected.filter(k => k !== 'sk-ant-proxy000');
    fs.writeFileSync(claudeSettingsPath, JSON.stringify(s, null, 2));
} catch (_) {}

if (isClaudeInstalled()) {
    log('Claude Code already installed — starting bridge server.\n');
    try { fs.writeFileSync(SETUP_DONE, 'true'); } catch (_) {}
    ensureCliJsPatched();
    writeSubagentWrappers();
    startBridgeServer();
} else {
    installLoop();
}
