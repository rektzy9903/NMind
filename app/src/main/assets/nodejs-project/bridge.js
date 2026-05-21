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
const PTY_HELPER = path.join(NATIVE_DIR, 'libpty-helper.so');

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
const AGENTIC_FILE  = path.join(FILES_DIR, 'agentic_state');
const CWD_FILE      = path.join(FILES_DIR, 'last_cwd');
const UNDO_DIR      = path.join(FILES_DIR, '.undo');
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

// ─── Agentic mode ─────────────────────────────────────────────────────────────
// When enabled, user messages go through a direct tool-calling loop instead of
// claude --print. Tools: bash, read_file, write_file, list_dir.

let agenticEnabled = (() => { try { return fs.existsSync(AGENTIC_FILE); } catch (_) { return false; } })();

// ─── Install confirmation ──────────────────────────────────────────────────────
// When the agentic AI tries to run an install command, we pause and ask the user.
// "Yes, don't ask again" saves the package-manager key to disk.
function loadAutoApprove() {
    try {
        const data = JSON.parse(fs.readFileSync(CONFIRM_FILE, 'utf8'));
        if (Array.isArray(data)) return new Set(data);
        if (Array.isArray(data.allow)) return new Set(data.allow);
    } catch(_) {}
    return new Set();
}
let autoApprove = loadAutoApprove();
const pendingConfirms = new Map(); // confirmId -> resolve fn
let confirmIdSeq = 0;

function saveAutoApprove() {
    try {
        let existing = { allow: [], deny: [] };
        try { const d = JSON.parse(fs.readFileSync(CONFIRM_FILE, 'utf8')); if (d && Array.isArray(d.allow)) existing = d; } catch(_) {}
        existing.allow = [...autoApprove];
        fs.writeFileSync(CONFIRM_FILE, JSON.stringify(existing, null, 2));
        try { fs.chmodSync(CONFIRM_FILE, 0o600); } catch(_) {}
    } catch(_) {}
}

// Regex: package-manager install commands that should prompt for confirmation
const INSTALL_CMD_RE = /\b(apt(-get)?|pip[23]?|gem|cargo|brew)\s+install\b|\bnpm\s+(install|i)\s+(--global|-g)\b/i;

function installKeyFromCmd(cmd) {
    const m = cmd.match(/\b(apt(-get)?|pip[23]?|gem|cargo|brew|npm)\b/i);
    return m ? m[1].toLowerCase().replace('apt-get', 'apt') : 'install';
}

function waitForConfirm(socket, id, description) {
    return new Promise(resolve => {
        pendingConfirms.set(id, resolve);
        try { socket.write('\x1b]9;confirm:' + id + ':' + description + '\x07'); } catch(_) {
            pendingConfirms.delete(id);
            resolve('yes');
        }
        // Auto-cancel after 2 minutes if user doesn't respond
        setTimeout(() => {
            if (pendingConfirms.has(id)) { pendingConfirms.delete(id); resolve('no'); }
        }, 120000);
    });
}

const AGENTIC_TOOLS = [
    {
        name: 'bash',
        description: 'Run a shell command on the Android device. Use for file ops, git, npm, etc.',
        input_schema: {
            type: 'object',
            properties: {
                command: { type: 'string', description: 'Shell command to execute' },
                cwd: { type: 'string', description: 'Working directory (optional)' }
            },
            required: ['command']
        }
    },
    {
        name: 'read_file',
        description: 'Read the contents of a file on the device',
        input_schema: {
            type: 'object',
            properties: { path: { type: 'string', description: 'File path (absolute or relative to cwd)' } },
            required: ['path']
        }
    },
    {
        name: 'write_file',
        description: 'Write content to a file (creates or overwrites)',
        input_schema: {
            type: 'object',
            properties: {
                path: { type: 'string', description: 'File path' },
                content: { type: 'string', description: 'Content to write' }
            },
            required: ['path', 'content']
        }
    },
    {
        name: 'list_dir',
        description: 'List files and folders in a directory',
        input_schema: {
            type: 'object',
            properties: { path: { type: 'string', description: 'Directory path' } },
            required: ['path']
        }
    },
    {
        name: 'web_search',
        description: 'Search the web and return results. Use for current events, facts, documentation, prices, etc.',
        input_schema: {
            type: 'object',
            properties: {
                query: { type: 'string', description: 'Search query' },
                max_results: { type: 'number', description: 'Max results to return (default 5)' }
            },
            required: ['query']
        }
    },
    {
        name: 'device_control',
        description: 'Control the Android device: read screen content, tap, type text, open apps, take screenshot.',
        input_schema: {
            type: 'object',
            properties: {
                action: {
                    type: 'string',
                    enum: ['read_screen', 'tap', 'type_text', 'open_app', 'screenshot'],
                    description: 'Action to perform'
                },
                x: { type: 'number', description: 'X coordinate for tap' },
                y: { type: 'number', description: 'Y coordinate for tap' },
                text: { type: 'string', description: 'Text to type' },
                package: { type: 'string', description: 'App package name to open (e.g. com.discord)' }
            },
            required: ['action']
        }
    }
];

// executeTool returns { content, isError, newCwd }
// newCwd is set when the bash command changes the working directory.
async function executeTool(name, input, cwd, socket) {
    const env = buildEnv();

    if (name === 'bash') {
        const cmd = input.command || '';

        // Confirm with user before running install commands
        if (socket && INSTALL_CMD_RE.test(cmd)) {
            const key = installKeyFromCmd(cmd);
            if (!autoApprove.has(key)) {
                const confirmId = 'c' + (++confirmIdSeq);
                const choice = await waitForConfirm(socket, confirmId, cmd.slice(0, 120));
                if (choice === 'always') {
                    autoApprove.add(key);
                    saveAutoApprove();
                } else if (choice === 'no') {
                    return { content: 'Installation cancelled by user.', isError: false, newCwd: cwd };
                }
                // 'yes' or 'always' → fall through and execute
            }
        }

        return new Promise(resolve => {
            const workDir = input.cwd ? path.resolve(cwd, input.cwd) : cwd;
            let out = '', err = '';
            let child;
            const wrappedCmd = cmd + '\n__exit=$?\npwd\nexit $__exit';
            try { child = spawn('/system/bin/sh', ['-c', wrappedCmd], { env, cwd: workDir }); }
            catch(e) { resolve({ content: 'spawn error: ' + e.message, isError: true, newCwd: cwd }); return; }
            const tid = setTimeout(() => {
                try { child.kill(); } catch(_) {}
                resolve({ content: (out + err).trim() + '\n[timeout 30s]', isError: true, newCwd: cwd });
            }, 30000);
            child.stdout.on('data', d => { out += d.toString(); });
            child.stderr.on('data', d => { err += d.toString(); });
            child.on('close', code => {
                clearTimeout(tid);
                const lines = out.trimEnd().split('\n');
                let newCwd = cwd;
                const lastLine = lines[lines.length - 1] || '';
                if (lastLine.startsWith('/') && fs.existsSync(lastLine.trim())) {
                    newCwd = lastLine.trim();
                    out = lines.slice(0, -1).join('\n');
                }
                const combined = (out + (err ? '\nstderr:\n' + err : '')).trim();
                resolve({ content: combined || '[exit ' + code + ']', isError: code !== 0, newCwd });
            });
        });

    } else if (name === 'read_file') {
        try {
            const fp = path.resolve(cwd, input.path);
            const content = fs.readFileSync(fp, 'utf8');
            return { content: content.slice(0, 50000), isError: false, newCwd: cwd };
        } catch(e) { return { content: 'Error: ' + e.message, isError: true, newCwd: cwd }; }

    } else if (name === 'write_file') {
        try {
            const fp = path.resolve(cwd, input.path);
            let diffText = null;
            if (fs.existsSync(fp)) {
                try {
                    const oldContent = fs.readFileSync(fp, 'utf8');
                    diffText = lineDiff(oldContent, input.content);
                    fs.mkdirSync(UNDO_DIR, { recursive: true });
                    const safeName = path.basename(fp).replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 80);
                    const snap = path.join(UNDO_DIR, Date.now() + '_' + safeName);
                    fs.copyFileSync(fp, snap);
                    const snaps = fs.readdirSync(UNDO_DIR).sort();
                    if (snaps.length > 20) {
                        for (const old of snaps.slice(0, snaps.length - 20)) {
                            try { fs.unlinkSync(path.join(UNDO_DIR, old)); } catch(_) {}
                        }
                    }
                } catch(_) {}
            }
            fs.mkdirSync(path.dirname(fp), { recursive: true });
            fs.writeFileSync(fp, input.content, 'utf8');
            return { content: 'Wrote ' + fp, isError: false, newCwd: cwd, diff: diffText };
        } catch(e) { return { content: 'Error: ' + e.message, isError: true, newCwd: cwd }; }

    } else if (name === 'list_dir') {
        try {
            const dp = path.resolve(cwd, input.path);
            const entries = fs.readdirSync(dp, { withFileTypes: true });
            const lines = entries.map(e => (e.isDirectory() ? 'd ' : 'f ') + e.name).join('\n');
            return { content: lines || '(empty)', isError: false, newCwd: cwd };
        } catch(e) { return { content: 'Error: ' + e.message, isError: true, newCwd: cwd }; }

    } else if (name === 'web_search') {
        return webSearch(input.query || '', input.max_results || 5, cwd);

    } else if (name === 'device_control') {
        return deviceControlTool(input, cwd);

    } else if (name.startsWith('mcp_')) {
        try {
            // Try stdio first, then HTTP
            const stdioResult = await callMcpStdioTool(name, input);
            if (!stdioResult.startsWith('MCP tool not found:')) {
                return { content: stdioResult || '(no output)', isError: false, newCwd: cwd };
            }
            const httpResult = await callMcpHttpTool(name, input);
            return { content: httpResult || '(no output)', isError: false, newCwd: cwd };
        } catch(e) { return { content: 'MCP error: ' + e.message, isError: true, newCwd: cwd }; }

    } else {
        return { content: 'Unknown tool: ' + name, isError: true, newCwd: cwd };
    }
}

// ─── Web Search via DuckDuckGo ────────────────────────────────────────────────
function webSearch(query, maxResults, cwd) {
    return new Promise(resolve => {
        if (!query) {
            resolve({ content: 'Error: query is required', isError: true, newCwd: cwd });
            return;
        }
        const encoded = encodeURIComponent(query);
        // DuckDuckGo Instant Answer API — no API key required
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
                        if (!parts.length) {
                            // Fall back to DuckDuckGo HTML search scraping
                            fetchDdgHtml(query, maxResults, cwd, resolve);
                            return;
                        }
                        resolve({ content: parts.join('\n\n'), isError: false, newCwd: cwd });
                    } catch(_) {
                        fetchDdgHtml(query, maxResults, cwd, resolve);
                    }
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
                // Extract result snippets from HTML using simple regex
                const results = [];
                const titleRe  = /<a[^>]+class="result__a"[^>]*>([^<]+)<\/a>/g;
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
                if (!results.length) {
                    resolve({ content: 'No results found for: ' + query, isError: false, newCwd: cwd });
                } else {
                    resolve({ content: 'Search results for "' + query + '":\n\n' + results.join('\n\n'), isError: false, newCwd: cwd });
                }
            });
            res.on('error', e => resolve({ content: 'Search failed: ' + e.message, isError: true, newCwd: cwd }));
        }
    );
    req.on('error', e => resolve({ content: 'Search failed: ' + e.message, isError: true, newCwd: cwd }));
    req.setTimeout(12000, () => { req.destroy(); resolve({ content: 'Search timed out', isError: true, newCwd: cwd }); });
}

// ─── Device Control via HTTP (port 8081) ─────────────────────────────────────
function deviceControlTool(input, cwd) {
    return new Promise(resolve => {
        const body = JSON.stringify(input);
        let localToken = '';
        try { localToken = fs.readFileSync(path.join(FILES_DIR, 'local_token'), 'utf8').trim().slice(0, 200); } catch(_) {}
        const req = http.request({
            hostname: HOST, port: DEVICE_CONTROL_PORT,
            path: '/device', method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(body),
                'x-local-token': localToken
            }
        }, res => {
            let data = '';
            res.on('data', d => { data += d; });
            res.on('end', () => {
                try {
                    const r = JSON.parse(data);
                    resolve({ content: String(r.result || ''), isError: !!r.error, newCwd: cwd });
                } catch(_) {
                    resolve({ content: data || '(empty response)', isError: false, newCwd: cwd });
                }
            });
        });
        req.on('error', e => {
            resolve({
                content: 'Device Control not available: ' + e.message +
                    '\nTo enable: Android Settings → Accessibility → Claude Screen & Device Control → ON',
                isError: true, newCwd: cwd
            });
        });
        req.setTimeout(15000, () => {
            req.destroy();
            resolve({ content: 'Device control request timed out', isError: true, newCwd: cwd });
        });
        req.write(body);
        req.end();
    });
}

// Streaming proxy call — writes text_delta chunks to socket as they arrive.
// Resolves with { content, stop_reason } in Anthropic format after stream ends.
function callProxyStreaming(socket, messages, tools, onThinkingDone) {
    return new Promise((resolve, reject) => {
        const cfg  = readConfig();
        const customPrompt = (cfg.customSystemPrompt || '').trim();
        const systemPrompt = customPrompt
            ? AGENTIC_SYSTEM_PROMPT + '\n\n[Custom Instructions]\n' + customPrompt
            : AGENTIC_SYSTEM_PROMPT;
        const body = JSON.stringify({
            model: cfg.modelId || 'claude-3-5-sonnet-20241022',
            max_tokens: 4096,
            messages,
            tools,
            system: systemPrompt,
            stream: true
        });
        const apiKey = cfg.mode === 'subscription'
            ? (cfg.apiKey || 'sk-ant-key')
            : 'sk-ant-proxy000';
        let localToken = '';
        try { localToken = fs.readFileSync(path.join(FILES_DIR, 'local_token'), 'utf8').trim().slice(0, 200); } catch(_) {}
        // M21: hoisted at Promise scope so req.on('error'/'close') can clear it too
        let idleTimer = null;
        const req = http.request({
            hostname: HOST, port: PROXY_PORT,
            path: '/v1/messages', method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'x-api-key': apiKey,
                'anthropic-version': '2023-06-01',
                'Content-Length': Buffer.byteLength(body),
                'x-local-token': localToken
            }
        }, res => {
            let buf = '';
            const textBlocks = {}, toolBlocks = {};
            let stopReason = 'end_turn';
            let thinkingSignalled = false;

            function resetIdleTimer() {
                clearTimeout(idleTimer);
                idleTimer = setTimeout(() => { req.destroy(new Error('stream idle timeout')); }, 30000);
            }
            // Start the idle timer as soon as the response headers arrive
            resetIdleTimer();

            res.on('data', chunk => {
                resetIdleTimer();
                buf += chunk.toString();
                const lines = buf.split('\n');
                buf = lines.pop();
                for (const line of lines) {
                    if (!line.startsWith('data: ')) continue;
                    const raw = line.slice(6).trim();
                    if (raw === '[DONE]') continue;
                    let evt; try { evt = JSON.parse(raw); } catch(_) { continue; }

                    if (evt.type === 'content_block_start') {
                        const cb = evt.content_block;
                        if (cb.type === 'text')     textBlocks[evt.index] = '';
                        if (cb.type === 'tool_use') toolBlocks[evt.index] = { id: cb.id, name: cb.name, input_json: '' };
                    } else if (evt.type === 'content_block_delta') {
                        const d = evt.delta;
                        if (d.type === 'text_delta' && d.text) {
                            textBlocks[evt.index] = (textBlocks[evt.index] || '') + d.text;
                            if (!thinkingSignalled) {
                                thinkingSignalled = true;
                                if (onThinkingDone) onThinkingDone();
                            }
                            try { socket.write(d.text); } catch(_) {}
                        } else if (d.type === 'input_json_delta' && toolBlocks[evt.index]) {
                            toolBlocks[evt.index].input_json += d.partial_json || '';
                        }
                    } else if (evt.type === 'message_delta' && evt.delta.stop_reason) {
                        stopReason = evt.delta.stop_reason;
                    }
                }
            });

            res.on('end', () => {
                clearTimeout(idleTimer);
                // Rebuild content array in block-index order
                const content = [];
                const allIdx = new Set([...Object.keys(textBlocks), ...Object.keys(toolBlocks)].map(Number));
                for (const idx of Array.from(allIdx).sort((a, b) => a - b)) {
                    if (textBlocks[idx] !== undefined) content.push({ type: 'text', text: textBlocks[idx] });
                    if (toolBlocks[idx]) {
                        let input = {};
                        try { input = JSON.parse(toolBlocks[idx].input_json || '{}'); } catch(_) {}
                        content.push({ type: 'tool_use', id: toolBlocks[idx].id, name: toolBlocks[idx].name, input });
                    }
                }
                resolve({ content, stop_reason: stopReason });
            });
            res.on('error', e => { clearTimeout(idleTimer); reject(e); });
        });

        const tid = setTimeout(() => { req.destroy(); reject(new Error('Proxy stream timeout')); }, 120000);
        req.on('error', e => { clearTimeout(tid); clearTimeout(idleTimer); reject(e); });
        req.on('close', () => { clearTimeout(tid); clearTimeout(idleTimer); });
        req.write(body); req.end();
    });
}

const GUARDIAN_PROMPT =
    'Before executing any destructive or irreversible operation — including deleting files, ' +
    'directories, repos, or branches; force pushing; closing or merging PRs; dropping databases ' +
    'or tables; bulk overwrites; rm -rf; or any remote action that permanently removes something — ' +
    'you MUST:\n' +
    '1. Stop — do not execute yet\n' +
    '2. Tell the user exactly what you are about to do (show the exact command if applicable)\n' +
    '3. Ask "Proceed?" and wait for their reply\n' +
    'Reply meanings:\n' +
    '• No → abort, explain what was skipped, wait for next instruction\n' +
    '• Yes → execute, report what happened, ask fresh again next time\n' +
    '• Always → execute now and skip asking for the rest of this session\n' +
    'Never execute a destructive action without confirmation unless the user said "Always" earlier in this conversation.';

const AGENTIC_SYSTEM_PROMPT =
    'You are an AI assistant running directly on an Android device via Claude Code Setup. ' +
    'You have the following tools available — use them proactively to complete tasks:\n' +
    '• bash(command, cwd?) — run any shell command (git, npm, node, cat, ls, curl, etc.)\n' +
    '• read_file(path) — read a file\'s contents\n' +
    '• write_file(path, content) — create or overwrite a file\n' +
    '• list_dir(path) — list directory contents\n\n' +
    'Working directory: /data/data/com.claudecodesetup/files\n' +
    'git is available via isomorphic-git if !install-git has been run.\n\n' +
    '## IMPORTANT — How to handle requests\n\n' +
    '**Before starting any non-trivial task:**\n' +
    '1. If the request is ambiguous or has important unknowns (language, framework, target platform, ' +
    'existing setup), ask 1–3 focused clarifying questions FIRST. Do not assume and proceed blindly.\n' +
    '2. Use bash to check whether required tools/packages are installed before trying to use them ' +
    '(e.g. `which node`, `which git`, `node -v`, `npm list <pkg>`, `ls /path/to/tool`). ' +
    'If a tool is missing, tell the user what is missing and ask if they want you to install it.\n' +
    '3. For build/project tasks: confirm the language and framework before generating files.\n\n' +
    '**Examples of questions to ask:**\n' +
    '- "What language/framework would you like? (e.g. Node.js, Python, Kotlin)"\n' +
    '- "Do you have X installed? I can check with `which X` or install it for you."\n' +
    '- "Should I create this in your current project folder or a new subfolder?"\n' +
    '- "Do you want me to initialize a git repo for this project?"\n\n' +
    'When editing code, read the file first, make targeted changes, then write it back. ' +
    'Always use tools to do real work rather than just describing steps.';

// runAgentic — streaming agentic loop. Returns final assistant text for history.
// Also returns updated shellCwd (may change if AI ran cd commands).
async function runAgentic(socket, userMessage, history, shellCwd, pendingImage) {
    const MAX_TURNS = 12;
    const messages = history.map(h => ({ role: h.role, content: h.content }));
    // If image is attached, build a multimodal user message
    if (pendingImage) {
        const userContent = [
            { type: 'image', source: { type: 'base64', media_type: pendingImage.mime, data: pendingImage.b64 } },
            { type: 'text', text: userMessage || 'What do you see in this image?' }
        ];
        messages.push({ role: 'user', content: userContent });
    } else {
        messages.push({ role: 'user', content: userMessage });
    }

    try { socket.write('\x1b]9;thinking-start\x07'); } catch(_) {}
    let thinkingDone = false;
    const signalThinkingDone = () => {
        if (!thinkingDone) {
            thinkingDone = true;
            try { socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
        }
    };

    let assistantText = '';
    let currentCwd = shellCwd;

    try {
        const allTools = [...AGENTIC_TOOLS, ...getMcpStdioTools(), ...getMcpHttpTools()];
        for (let turn = 0; turn < MAX_TURNS; turn++) {
            const resp = await callProxyStreaming(socket, messages, allTools, signalThinkingDone);
            if (!resp || !resp.content) throw new Error('Empty response from proxy');

            // Collect this turn's text separately so we can inspect it for questions
            let turnText = '';
            for (const b of resp.content) {
                if (b.type === 'text') { turnText += b.text; assistantText += b.text; }
            }

            messages.push({ role: 'assistant', content: resp.content });

            // If Claude ended with a question or is asking for confirmation, stop the
            // tool loop so the user can answer before the AI proceeds further.
            const trimmed = turnText.trim();
            const isAskingQuestion =
                /[?？]\s*$/.test(trimmed) ||
                /\b(would you (like|prefer|want)|do you (want|have|need|already)|should i (proceed|install|create|use|go ahead)|what (language|framework|tech|stack|do you|would you|type of)|which (one|option|do you|would you|framework|language)|please (confirm|let me know|clarify)|before i (proceed|start|continue|go ahead)|can you (confirm|clarify|share|tell me|let me know)|are you (using|sure|ok with)|have you (installed|set up|already)|shall i|want me to)\b/i.test(trimmed);

            if (isAskingQuestion) {
                log('[agentic] pausing loop — Claude asked a question\n');
                break;
            }

            if (resp.stop_reason === 'end_turn' || resp.stop_reason === 'stop_sequence') break;

            const toolUses = resp.content.filter(b => b.type === 'tool_use');
            if (!toolUses.length) break;

            // Tools run after streaming — show indicator and execute
            signalThinkingDone();
            const toolResults = [];
            for (const tu of toolUses) {
                try { socket.write('\r\n\x1b[36m▶ ' + tu.name + '  ' + JSON.stringify(tu.input) + '\x1b[0m\r\n'); } catch(_) {}
                const result = await executeTool(tu.name, tu.input, currentCwd, socket);
                // Update cwd if bash command changed directory
                if (result.newCwd && result.newCwd !== currentCwd) {
                    currentCwd = result.newCwd;
                    try { socket.write('\x1b[2mcwd: ' + currentCwd + '\x1b[0m\r\n'); } catch(_) {}
                }
                const preview = result.content.slice(0, 2000);
                try { socket.write('\x1b[2m' + preview + (result.content.length > 2000 ? '\n…(truncated)' : '') + '\x1b[0m\r\n'); } catch(_) {}
                // Show code diff for file writes
                if (result.diff && result.diff.trim()) {
                    const diffDisplay = result.diff.split('\n').map(l =>
                        l.startsWith('+') ? '\x1b[32m' + l + '\x1b[0m' :
                        l.startsWith('-') ? '\x1b[31m' + l + '\x1b[0m' : l
                    ).join('\r\n');
                    try { socket.write('\x1b[2m── diff ──\x1b[0m\r\n' + diffDisplay + '\r\n\x1b[2m──────────\x1b[0m\r\n'); } catch(_) {}
                }
                log('[agentic] tool=' + tu.name + ' cwd=' + currentCwd + ' isError=' + result.isError + '\n');
                toolResults.push({
                    type: 'tool_result', tool_use_id: tu.id,
                    content: result.content.slice(0, 8000),
                    is_error: result.isError
                });
            }
            messages.push({ role: 'user', content: toolResults });
            // Show thinking again between tool rounds
            try { socket.write('\x1b]9;thinking-start\x07'); } catch(_) {}
            thinkingDone = false;
        }
    } catch(e) {
        signalThinkingDone();
        log('[agentic] error: ' + e.message + '\n');
        try { socket.write('\r\n\x1b[31m[agentic error] ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
    }

    signalThinkingDone();
    return { text: assistantText, cwd: currentCwd };
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
        // ── Auth gate ──────────────────────────────────────────────────────────
        // Reject any request that does not present valid credentials.
        const tokenHeader = req.headers['x-local-token'] || '';
        const authHeader  = req.headers['authorization'] || '';
        const apiKeyHeader = req.headers['x-api-key'] || '';
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
    try {
        res.writeHead(code, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ type: 'error', error: { type: 'api_error', message: msg } }));
    } catch (_) {}
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

function handleProxyRequest(anthReq, res) {
    const cfg   = readConfig();
    const pUrl  = cfg.providerUrl || '';
    const key   = cfg.apiKey || '';
    const stream = !!anthReq.stream;

    if (!pUrl) return proxyError(res, 500, 'No provider URL in config — check app settings');

    const baseModel = cfg.modelId || anthReq.model || '';
    const modelList = Array.isArray(cfg.modelList) ? cfg.modelList : [];
    const oaiBase   = anthToOai(anthReq, baseModel);
    const hasTools  = !!(oaiBase.tools && oaiBase.tools.length);

    // attempt(modelId, retriesLeft, delayMs)
    // Retries the same model up to 3x with exponential backoff on 429,
    // then falls through to the next model in modelList.
    function attempt(modelId, retriesLeft, delayMs) {
        const oaiReq = Object.assign({}, oaiBase, { model: modelId });

        function retryWithoutTools() {
            log('[proxy] provider rejected tools (HTTP 400) — retrying as plain text request\n');
            const plain = Object.assign({}, oaiReq);
            delete plain.tools;
            delete plain.tool_choice;
            sendToProvider(pUrl, key, plain, stream, res, null, on429);
        }

        function on429() {
            lastRateLimitMs = Date.now();
            if (retriesLeft > 0) {
                log('[proxy] 429 — retrying ' + modelId + ' in ' + delayMs + 's (' + retriesLeft + ' left)\n');
                // Notify active socket of countdown so the thinking timer shows it
                try { if (on429CountdownNotify) on429CountdownNotify(delayMs); } catch(_) {}
                setTimeout(() => attempt(modelId, retriesLeft - 1, delayMs * 2), delayMs * 1000);
            } else {
                const idx  = modelList.indexOf(modelId);
                const next = modelList[idx + 1];
                if (next && next !== modelId) {
                    log('[proxy] 429 exhausted — switching to ' + next + '\n');
                    // M8: reset delayMs to initial value when switching models
                    attempt(next, 2, 2);
                } else {
                    proxyError(res, 429, 'Rate limited. All fallback models exhausted — switch provider in Settings.');
                }
            }
        }

        sendToProvider(pUrl, key, oaiReq, stream, res, hasTools ? retryWithoutTools : null, on429);
    }

    attempt(baseModel, 3, 2);
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
                tool_calls: toolUseBlocks.map(tu => ({
                    id: tu.id || ('call_' + tu.name + '_' + Date.now()),
                    type: 'function',
                    function: { name: tu.name, arguments: JSON.stringify(tu.input || {}) }
                }))
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

function sendToProvider(baseUrl, apiKey, oaiReq, stream, res, onBadRequest, on429) {
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
                        if (provRes.statusCode === 400 && onBadRequest) return onBadRequest();
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
                        if (provRes.statusCode === 402)
                            errMsg += ' — switch model or add credits at openrouter.ai/credits';
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
                    // 400 with tools in request → retry without tools
                    if (provRes.statusCode === 400 && onBadRequest) return onBadRequest();
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
                    } catch (_) {}
                    if (provRes.statusCode === 402)
                        msg += ' — switch to a different model or add credits at openrouter.ai/credits';
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
                        log('[proxy] stream error from provider: ' + JSON.stringify(event.error).slice(0, 200) + '\n');
                        continue;
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
                                tcBlocks[tcIdx] = { id: tc.id, name: (tc.function || {}).name || '', blockIdx };
                                sendEvent('content_block_start', {
                                    type: 'content_block_start', index: blockIdx,
                                    content_block: {
                                        type: 'tool_use', id: tc.id,
                                        name: (tc.function || {}).name || '', input: {}
                                    }
                                });
                                log('[proxy] stream: tool_use block — ' + tcBlocks[tcIdx].name + '\n');
                            }
                            const args = (tc.function || {}).arguments || '';
                            if (args) {
                                sendEvent('content_block_delta', {
                                    type: 'content_block_delta', index: tcBlocks[tcIdx].blockIdx,
                                    delta: { type: 'input_json_delta', partial_json: args }
                                });
                            }
                        }
                    }

                    if (finishCode) {
                        log('[proxy] finish_reason=' + finishCode + ' tokens=' + outTokens + '\n');
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
        }

        provRes.on('error', err => log('Provider response error: ' + err.message + '\n'));
    });

    provReq.on('error', err => proxyError(res, 502, 'Provider unreachable: ' + err.message));
    provReq.setTimeout(120000, () => {
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
        LANG: 'en_US.UTF-8',
        LINES: '50',
        COLUMNS: '160',
        // Bypass the "do you trust this folder?" prompt. pE_() in cli.js returns
        // true immediately when CLAUDE_CODE_SANDBOXED is set, skipping the
        // per-directory hasTrustDialogAccepted check in settings.json entirely.
        CLAUDE_CODE_SANDBOXED: '1',
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
const mcpStdioServers = new Map(); // name → { proc, tools, pendingCbs, msgId, buf }

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
    const srv = { proc: null, tools: [], pendingCbs: new Map(), msgId: 0, buf: '' };
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
        srv.proc.stderr.on('data', d => log('[mcp-stdio:' + entry.name + '] stderr: ' + d.toString().slice(0,200) + '\n'));
        srv.proc.on('exit', code => {
            log('[mcp-stdio:' + entry.name + '] exited code=' + code + '\n');
            mcpStdioServers.delete(entry.name);
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
    } catch(e) {
        log('[mcp-stdio:' + entry.name + '] start failed: ' + e.message + '\n');
        mcpStdioServers.delete(entry.name);
    }
}

function getMcpStdioTools() {
    const tools = [];
    for (const srv of mcpStdioServers.values()) {
        tools.push(...srv.tools);
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
}

// ── HTTP MCP client ────────────────────────────────────────────────────────────
// Speaks MCP JSON-RPC 2.0 over Streamable HTTP (2025-03-26).
// Each entry in filesDir/mcp_http.json: { name, url }

const MCP_HTTP_CONFIG = path.join(FILES_DIR, 'mcp_http.json');
const mcpHttpServers  = new Map(); // name → { url, sessionId, tools }

function mcpHttpPost(url, body, sessionId) {
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
    const srv = { url: entry.url, sessionId: null, tools: [] };
    try {
        const initRes = await mcpHttpPost(entry.url, {
            jsonrpc: '2.0', id: 1, method: 'initialize',
            params: {
                protocolVersion: '2025-03-26',
                capabilities: { tools: {} },
                clientInfo: { name: 'ClaudeCodeSetup', version: '1.0' },
            },
        }, null);
        if (initRes.error) throw new Error(initRes.error.message || JSON.stringify(initRes.error));
        if (initRes._sid) srv.sessionId = initRes._sid;
        // fire-and-forget
        mcpHttpPost(entry.url,
            { jsonrpc: '2.0', method: 'notifications/initialized', params: {} },
            srv.sessionId).catch(() => {});
        const toolsRes = await mcpHttpPost(entry.url,
            { jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }, srv.sessionId);
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
    } catch(e) {
        log('[mcp-http:' + entry.name + '] start failed: ' + e.message + '\n');
    }
}

function getMcpHttpTools() {
    const tools = [];
    for (const srv of mcpHttpServers.values()) tools.push(...srv.tools);
    return tools;
}

async function callMcpHttpTool(toolName, args) {
    for (const [, srv] of mcpHttpServers.entries()) {
        const found = srv.tools.find(t => t.name === toolName);
        if (!found) continue;
        const res = await mcpHttpPost(srv.url, {
            jsonrpc: '2.0', id: Date.now(), method: 'tools/call',
            params: { name: found._mcpTool, arguments: args },
        }, srv.sessionId);
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
            // Inject always-allow/always-deny lists so those tools never prompt
            const approveList = loadApproveList();
            if (approveList.allow.length > 0 || (approveList.deny && approveList.deny.length > 0)) {
                if (!s.permissions) s.permissions = { allow: [], deny: [] };
                if (!Array.isArray(s.permissions.allow)) s.permissions.allow = [];
                if (!Array.isArray(s.permissions.deny)) s.permissions.deny = [];
                for (const t of approveList.allow) {
                    if (!s.permissions.allow.includes(t)) s.permissions.allow.push(t);
                }
                for (const t of (approveList.deny || [])) {
                    if (!s.permissions.deny.includes(t)) s.permissions.deny.push(t);
                }
            }
            fs.writeFileSync(sp, JSON.stringify(s, null, 2));
        } catch(_) {}
    }

    // ── Process a single stream-json event from claude stdout ────────────────
    function handleStreamEvent(evt, state, proc, firstContent, setFirstContent) {
        // On the first JSON event, close the thinking spinner
        if (!state.thinkingDone) {
            state.thinkingDone = true;
            try { if (state.socket) state.socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
        }

        if (evt.type === 'system' && evt.subtype === 'init') return;

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
            const perm = { toolName, toolInput, id: permId, suggestions, autoApproved: true };
            state.pendingPerm = perm;
            // --dangerously-skip-permissions handles tool approval; dialog is informational.
            const permB64 = Buffer.from(JSON.stringify(perm)).toString('base64');
            try { if (state.socket) state.socket.write('\x1b]9;permission:' + permB64 + '\x07'); } catch(_) {}
            return;
        }

        if (evt.type === 'result') {
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
        const exitLog = JSON.stringify(path.join(FILES_DIR, 'session_exit.log'));

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
        //   --dangerously-skip-permissions    → auto-approve all tool use (no stdin wait)
        //   --append-system-prompt            → guardian + custom system prompt
        //   --continue                        → resume last session (preserves history)
        //   <message>                         → the user's message
        const customPrompt = (cfg.customSystemPrompt || '').trim();
        const fullSystemPrompt = customPrompt
            ? GUARDIAN_PROMPT + '\n\n' + customPrompt
            : GUARDIAN_PROMPT;
        let argvCode =
            'process.argv[2]="--output-format";' +
            'process.argv[3]="stream-json";' +
            'process.argv[4]="--print";' +
            'process.argv[5]="--verbose";' +
            'process.argv[6]="--dangerously-skip-permissions";' +
            'process.argv[7]="--append-system-prompt";' +
            'process.argv[8]=' + JSON.stringify(fullSystemPrompt) + ';';
        let argvLen = 9;
        if (state.hasHistory) {
            argvCode += 'process.argv[' + argvLen + ']="--continue";';
            argvLen++;
        }
        argvCode += 'process.argv[' + argvLen + ']=' + JSON.stringify(msg) + ';';
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

        const proc = spawn(LAUNCHER, ['-e', evalCode], { env, cwd: state.cwd });
        // Keep stdin open — needed so permission-prompt answers can be written.
        // claude-code --print exits on its own after the response; stdin EOF not required.
        state.currentProc = proc;
        state.busy = true;
        state.thinkingDone = false;
        state.pendingPerm = null;
        state.lastAiText = '';

        try { if (state.socket) state.socket.write('\x1b]9;thinking-start\x07'); } catch(_) {}

        let lineBuf = '';
        let stderrBuf = '';
        let firstContent = false;

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
                        // Non-JSON line — forward raw wrapped in SYS_FENCE so it routes
                        // to a sys bubble and never pollutes the AI bubble.
                        try { if (state.socket) state.socket.write(SYS_FENCE + line + '\n'); } catch(_) {}
                        continue;
                    }
                    handleStreamEvent(evt, state, proc, firstContent, (fc) => { firstContent = fc; });
                } else if (t.length > 0) {
                    // Plain-text permission prompt (fallback for unexpected formats)
                    // Patterns: "Allow bash?", "Do you want to run...", "[y/n/a]"
                    if (/allow|permission|approve|proceed/i.test(t) && /\?|y\/n|\[y/i.test(t)) {
                        handlePermissionText(t, state, proc);
                    } else {
                        // Non-JSON plain output — SYS_FENCE so it always lands in a sys bubble
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
                try { if (state.socket) state.socket.write(lines.join('\n')); } catch(_) {}
            }
        });

        proc.on('error', e => {
            state.currentProc = null;
            state.busy = false;
            try { if (state.socket) state.socket.write('\x1b]9;thinking-done\x07\x1b[31m[error] ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
        });

        // 120-second hard timeout
        const finishTid = setTimeout(() => {
            try { proc.kill('SIGTERM'); } catch(_) {}
            state.currentProc = null;
            state.busy = false;
            try { if (state.socket) state.socket.write('\x1b]9;thinking-done\x07\r\n\x1b[31m✗ Timed out (120 s)\x1b[0m\r\n'); } catch(_) {}
        }, 120000);

        proc.on('close', code => {
            clearTimeout(finishTid);
            state.currentProc = null;
            state.busy = false;
            state.thinkingDone = false;
            // Always send second thinking-done — closes/finalizes the AI bubble
            try { if (state.socket) state.socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
            if (proc._manualKill) return; // killed by !clear — suppress error message
            if (code !== 0 && !firstContent) {
                const rateLimited = (Date.now() - lastRateLimitMs) < 30000;
                if (rateLimited) {
                    try { if (state.socket) state.socket.write('\x1b[33m⚠ Rate limited — wait 30–60 s then retry, or switch model.\x1b[0m\r\n'); } catch(_) {}
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
                        hint = '\x1b[31m✗ Request timed out (120 s)\x1b[0m\r\n' +
                               '\x1b[2mIf you see 401/auth errors above, check your API key in Settings.\x1b[0m\r\n' +
                               '\x1b[2mPress Ctrl+C next time to cancel early.\x1b[0m\r\n';
                    } else {
                        hint = '\x1b[31m[claude exited ' + code + ']\x1b[0m\r\n';
                        if (errLines) hint += '\x1b[2m' + errLines + '\x1b[0m\r\n';
                        hint += '\x1b[2mType !log for bridge log\x1b[0m\r\n';
                    }
                    try { if (state.socket) state.socket.write(hint); } catch(_) {}
                }
            }
        });
    }

    // ── Input handler ─────────────────────────────────────────────────────────
    function handleInput(d, state) {
        // If a !pty subprocess is active, relay bytes directly to it
        if (state.ptyProc) {
            try { state.ptyProc.stdin.write(d); } catch(_) {}
            return;
        }

        // Ctrl+C: kill the in-flight claude process
        const raw = d.toString();
        if (raw.includes('\x03')) {
            if (state.busy && state.currentProc) {
                try { state.currentProc.kill('SIGTERM'); } catch(_) {}
                try { if (state.socket) state.socket.write('\r\n\x1b[33m^C — interrupted\x1b[0m\r\n'); } catch(_) {}
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
                .replace(/[​‌‍﻿⁠]/g, '')
                .trim();
            state.inputBuf = state.inputBuf.slice(nl + 1);
            if (!line) continue;

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

            // ── Confirm responses (agentic install confirmation) ──────────────────
            if (line.startsWith('!confirm:')) {
                const parts = line.slice(9).split(':');
                const confirmId = parts[0];
                const choice = parts.slice(1).join(':');
                const resolve = pendingConfirms.get(confirmId);
                if (resolve) {
                    pendingConfirms.delete(confirmId);
                    resolve(choice);
                }
                continue;
            }

            // ── Permission responses — bypass busy guard ──────────────────────────
            // Tool already ran (auto-approved on detection to beat claude-code's 3s
            // stdin timeout). These buttons configure FUTURE spawns only.
            if (line.startsWith('!perm-allow') || line.startsWith('!perm-always') || line.startsWith('!perm-deny')) {
                const perm = state.pendingPerm;
                if (!perm) continue;
                if (line.startsWith('!perm-always')) {
                    const list = loadApproveList();
                    if (!list.allow.includes(perm.toolName)) {
                        list.allow.push(perm.toolName);
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
                state.agHistory     = [];
                state.pendingPerm   = null;
                clearSessionState(state.sid);
                clearClaudeSessionFiles();
                try { if (state.socket) state.socket.write('\x1b]9;tokens:0\x07'); } catch(_) {}
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
                    '  \x1b[33m!pty <cmd>\x1b[0m          Run interactive program (bash, python3…)\r\n' +
                    '  \x1b[33m!agentic [on|off]\x1b[0m   Toggle direct agentic loop\r\n' +
                    '  \x1b[33m!undo\x1b[0m               Restore last file written by agentic\r\n' +
                    '  \x1b[33m!undo list\x1b[0m          Show undo snapshot history\r\n' +
                    '  \x1b[33m!log [n]\x1b[0m            Show last n lines of bridge log (default 40)\r\n' +
                    '  \x1b[33m!test-cli\x1b[0m           Run module-loader + proxy diagnostics\r\n' +
                    '  \x1b[33m!help\x1b[0m               Show this help\r\n' +
                    '  \x1b[33m$ <cmd>\x1b[0m             Run a shell command\r\n\r\n'
                ); } catch(_) {}
                continue;
            }

            if (line.startsWith('!log')) {
                const n = parseInt(line.slice(4).trim()) || 40;
                try {
                    const logData = fs.readFileSync(SETUP_LOG, 'utf8');
                    if (state.socket) state.socket.write(SYS_FENCE + '\x1b[2m' + logData.split('\n').slice(-n).join('\r\n') + '\x1b[0m\r\n');
                } catch(_) { try { if (state.socket) state.socket.write(SYS_FENCE + '[no log]\r\n'); } catch(_) {} }
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
                try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m[busy — please wait]\x1b[0m\r\n'); } catch(_) {}
                continue;
            }

            // ── ! commands ────────────────────────────────────────────────────────
            if (line.startsWith('!test-cli')) {
                const sock2 = state.socket;
                try { if (sock2) sock2.write(SYS_FENCE + '\r\n\x1b[33mRunning module-loader diagnostic (6 steps)…\x1b[0m\r\n'); } catch(_) {}
                const env2 = buildEnv();
                const cliUrl2 = 'file://' + CLAUDE_CLI;
                const exitLog2 = JSON.stringify(SETUP_LOG);

                function runEvalStep2(label, evalCode2, cb) {
                    let out = '', err = '';
                    let ch;
                    try { ch = spawn(LAUNCHER, ['-e', evalCode2], { env: env2, cwd: FILES_DIR }); ch.stdin.end(); }
                    catch(e) { try { if (sock2) sock2.write(SYS_FENCE + '\x1b[31m  ' + label + ': spawn-err ' + e.message + '\x1b[0m\r\n'); } catch(_) {} cb(); return; }
                    ch.stdout.on('data', d => { out += d.toString(); });
                    ch.stderr.on('data', d => { err += d.toString(); });
                    const tid = setTimeout(() => { try { ch.kill(); } catch(_) {} try { if (sock2) sock2.write(SYS_FENCE + '\x1b[31m  ' + label + ': TIMEOUT\x1b[0m\r\n'); } catch(_) {} cb(); }, 30000);
                    ch.on('close', code => {
                        clearTimeout(tid);
                        log('[test-cli] ' + label + ' exit=' + code + ' out=' + JSON.stringify(out.slice(0,200)) + ' err=' + JSON.stringify(err.slice(0,300)) + '\n');
                        const mark = code === 0 ? '\x1b[32m✓' : '\x1b[31m✗';
                        let msg2 = mark + ' ' + label + ' exit=' + code + '\x1b[0m';
                        if (out.trim()) msg2 += '  out:' + out.trim().slice(0,80);
                        if (err.trim()) msg2 += '\r\n    \x1b[31merr:' + err.trim().slice(0,200) + '\x1b[0m';
                        try { if (sock2) sock2.write(SYS_FENCE + '  ' + msg2 + '\r\n'); } catch(_) {}
                        cb();
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

            if (line.startsWith('!agentic')) {
                const arg = line.slice(8).trim();
                const on  = arg === 'on' ? true : arg === 'off' ? false : !fs.existsSync(AGENTIC_FILE);
                if (on) { try { fs.writeFileSync(AGENTIC_FILE, '1'); } catch(_) {} }
                else    { try { fs.unlinkSync(AGENTIC_FILE); } catch(_) {} }
                try { if (state.socket) state.socket.write('\x1b]9;agentic:' + (on ? 'on' : 'off') + '\x07'); } catch(_) {}
                try { if (state.socket) state.socket.write(SYS_FENCE + (on ? '\x1b[35m[AGENTIC ON]\x1b[0m' : '\x1b[2m[AGENTIC OFF]\x1b[0m') + '\r\n'); } catch(_) {}
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

            // !undo / !undo list — unified handler (exact-match on "list" arg, case-insensitive)
            if (line === '!undo' || line.startsWith('!undo ')) {
                const undoArg = line.slice(5).trim().toLowerCase();
                if (undoArg === 'list') {
                    try {
                        if (!fs.existsSync(UNDO_DIR)) { try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[2m[no snapshots]\x1b[0m\r\n'); } catch(_) {} continue; }
                        const snaps = fs.readdirSync(UNDO_DIR).sort().reverse().slice(0, 10);
                        if (!snaps.length) { try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[2m[no snapshots]\x1b[0m\r\n'); } catch(_) {} continue; }
                        const list = snaps.map((s, i) => {
                            const ts = parseInt(s) || 0;
                            const age = ts ? Math.round((Date.now() - ts) / 60000) + 'm ago' : '';
                            return '  ' + (i === 0 ? '→ ' : '  ') + s.replace(/^\d+_/, '') + (age ? ' (' + age + ')' : '');
                        }).join('\r\n');
                        try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[2mUndo snapshots (newest first):\x1b[0m\r\n' + list + '\r\n'); } catch(_) {}
                    } catch(e) { try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[!undo list: ' + e.message + ']\x1b[0m\r\n'); } catch(_) {} }
                } else {
                    try {
                        if (!fs.existsSync(UNDO_DIR)) {
                            try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m[no undo snapshots]\x1b[0m\r\n'); } catch(_) {}
                            continue;
                        }
                        const snaps = fs.readdirSync(UNDO_DIR).sort();
                        if (!snaps.length) {
                            try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m[no undo snapshots]\x1b[0m\r\n'); } catch(_) {}
                            continue;
                        }
                        const latest = snaps[snaps.length - 1];
                        const snapPath = path.join(UNDO_DIR, latest);
                        const origName = latest.replace(/^\d+_/, '');
                        const targetPath = path.resolve(state.cwd, origName);
                        fs.copyFileSync(snapPath, targetPath);
                        fs.unlinkSync(snapPath);
                        try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[32m✓ Restored: ' + targetPath + '\x1b[0m\r\n'); } catch(_) {}
                        log('[undo] restored ' + targetPath + ' from ' + snapPath + '\n');
                    } catch(e) {
                        try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[!undo: ' + e.message + ']\x1b[0m\r\n'); } catch(_) {}
                    }
                }
                continue;
            }

            if (line.startsWith('!install')) {
                const pkgName = line.slice(8).trim();
                if (!pkgName) {
                    const names = Object.keys(PACKAGE_CATALOG).join(', ');
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33mAvailable packages:\x1b[0m ' + names + '\r\n\x1b[2mUsage: !install <package>\x1b[0m\r\n'); } catch(_) {}
                } else {
                    installPackage(pkgName, state.socket);
                }
                continue;
            }

            // !pty <cmd> — hand the socket to an interactive PTY subprocess
            if (line.startsWith('!pty ') || line === '!pty') {
                const ptyCmd = line.slice(5).trim();
                if (!ptyCmd) {
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33mUsage: !pty <command>  e.g. !pty bash\x1b[0m\r\n'); } catch(_) {}
                    continue;
                }
                if (!fs.existsSync(PTY_HELPER)) {
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m✗ libpty-helper.so not found.\x1b[0m\r\n'); } catch(_) {}
                    continue;
                }
                const ptyCfg = readConfig();
                const ptyEnv = Object.assign({}, buildEnv(), { TERM: 'xterm-256color' });
                let ptyProc;
                try {
                    ptyProc = spawn(PTY_HELPER,
                        [String(ptyCfg.ptyCols || 220), String(ptyCfg.ptyRows || 50), ...ptyCmd.split(/\s+/)],
                        { env: ptyEnv, cwd: state.cwd });
                } catch(e) {
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[PTY] Failed: ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
                    continue;
                }
                state.ptyProc = ptyProc;
                // Start/end banners use SYS_FENCE so they land in a sys bubble even
                // if chatState is RESPONDING. PTY raw output (stdout/stderr) is NOT
                // fenced — it's intentional terminal passthrough rendered by the ANSI
                // engine, and !pty is never run while the bridge is busy (state.busy).
                try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[33m[PTY] ' + ptyCmd + ' — Ctrl+D or exit to return\x1b[0m\r\n\r\n'); } catch(_) {}
                ptyProc.stdout.on('data', d2 => { try { if (state.socket) state.socket.write(d2); } catch(_) {} });
                ptyProc.stderr.on('data', d2 => { try { if (state.socket) state.socket.write(d2); } catch(_) {} });
                ptyProc.on('close', code2 => {
                    state.ptyProc = null;
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\r\n\x1b[33m[PTY] ' + ptyCmd + ' ended (exit ' + (code2 || 0) + ')\x1b[0m\r\n\r\n'); } catch(_) {}
                });
                ptyProc.on('error', e => {
                    state.ptyProc = null;
                    try { if (state.socket) state.socket.write(SYS_FENCE + '\x1b[31m[PTY] Error: ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
                });
                if (state.socket) state.socket.once('close', () => {
                    try { if (state.ptyProc === ptyProc) { ptyProc.kill(); state.ptyProc = null; } } catch(_) {}
                });
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

            // ── Forward everything else to claude or the agentic loop ──────────
            let msg = line;
            if (state.contextBlock)  { msg = state.contextBlock  + '\n\n' + msg; state.contextBlock  = ''; }
            if (state.pendingAttach) { msg = state.pendingAttach + '\n\n' + msg; state.pendingAttach = null; }

            if (fs.existsSync(AGENTIC_FILE)) {
                state.busy = true;
                if (!state.agHistory) state.agHistory = [];
                state.agHistory.push({ role: 'user', content: msg });
                let pendingImg = null;
                const imgB64Path = path.join(FILES_DIR, 'pending_image.b64');
                const imgMimePath = path.join(FILES_DIR, 'pending_image.mime');
                if (fs.existsSync(imgB64Path) && fs.existsSync(imgMimePath)) {
                    try {
                        pendingImg = {
                            b64: fs.readFileSync(imgB64Path, 'utf8').trim(),
                            mime: fs.readFileSync(imgMimePath, 'utf8').trim() || 'image/jpeg'
                        };
                        // M7: delete AFTER runAgentic completes (in finally block below)
                    } catch(_) {}
                }
                runAgentic(state.socket, msg, state.agHistory.slice(0, -1), state.cwd, pendingImg)
                    .then(result => {
                        if (result.text) state.agHistory.push({ role: 'assistant', content: result.text });
                        if (state.agHistory.length > 40) state.agHistory = state.agHistory.slice(-40);
                        if (result.cwd && result.cwd !== state.cwd) {
                            state.cwd = result.cwd;
                            try { if (state.socket) state.socket.write('\x1b]9;cwd:' + state.cwd + '\x07'); } catch(_) {}
                        }
                        state.busy = false;
                    })
                    .catch(e => {
                        log('[agentic] unhandled: ' + e.message + '\n');
                        state.busy = false;
                    })
                    .finally(() => {
                        // M7: delete pending image files after runAgentic completes
                        try { fs.unlinkSync(imgB64Path); } catch(_) {}
                        try { fs.unlinkSync(imgMimePath); } catch(_) {}
                    });
            } else {
                // Image attached in print mode — route through runAgentic which builds
                // the multimodal content block for the API (proxy or Anthropic direct).
                const imgB64Path  = path.join(FILES_DIR, 'pending_image.b64');
                const imgMimePath = path.join(FILES_DIR, 'pending_image.mime');
                if (fs.existsSync(imgB64Path) && fs.existsSync(imgMimePath)) {
                    let pendingImg = null;
                    try {
                        pendingImg = {
                            b64:  fs.readFileSync(imgB64Path,  'utf8').trim(),
                            mime: fs.readFileSync(imgMimePath, 'utf8').trim() || 'image/jpeg'
                        };
                        // M7: delete AFTER runAgentic completes (in finally block below)
                    } catch(_) {}
                    state.busy = true;
                    if (!state.agHistory) state.agHistory = [];
                    state.agHistory.push({ role: 'user', content: msg });
                    runAgentic(state.socket, msg, state.agHistory.slice(0, -1), state.cwd, pendingImg)
                        .then(result => {
                            if (result.text) state.agHistory.push({ role: 'assistant', content: result.text });
                            if (state.agHistory.length > 40) state.agHistory = state.agHistory.slice(-40);
                            if (result.cwd && result.cwd !== state.cwd) {
                                state.cwd = result.cwd;
                                try { if (state.socket) state.socket.write('\x1b]9;cwd:' + state.cwd + '\x07'); } catch(_) {}
                            }
                            state.busy = false;
                        })
                        .catch(e => {
                            log('[image-agentic] unhandled: ' + e.message + '\n');
                            state.busy = false;
                        })
                        .finally(() => {
                            // M7: delete pending image files after runAgentic completes
                            try { fs.unlinkSync(imgB64Path); } catch(_) {}
                            try { fs.unlinkSync(imgMimePath); } catch(_) {}
                        });
                } else {
                    runMessage(msg, state);
                }
            }
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
                ptyProc: null,
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

        const agOn = fs.existsSync(AGENTIC_FILE);
        try { socket.write('\x1b]9;agentic:' + (agOn ? 'on' : 'off') + '\x07'); } catch(_) {}
        try { socket.write('\x1b]9;cwd:' + state.cwd + '\x07'); } catch(_) {}
        try { socket.write('\x1b]9;tokens:' + state.sessionTokens + '\x07'); } catch(_) {}
        try { socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
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
                if (state.ptyProc) { try { state.ptyProc.kill(); } catch(_) {} state.ptyProc = null; }
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
            let sid = '0';
            if (firstLine.startsWith('SESSION:')) {
                const parts = firstLine.slice(8).split(':');
                sid = parts[0];
                const token = parts[1] || '';
                let expectedToken = '';
                try { expectedToken = fs.readFileSync(path.join(FILES_DIR, 'local_token'), 'utf8').trim().slice(0, 200); } catch(_) {}
                // Reject if token missing/empty OR if presented token does not match.
                // An empty expectedToken must never match anything — reject all.
                if (!expectedToken || token !== expectedToken) {
                    try { rawSocket.write('\r\n\x1b[31mUnauthorized connection rejected.\x1b[0m\r\n'); rawSocket.end(); } catch(_) {}
                    return;
                }
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

// Write FILES_DIR/bin/claude and FILES_DIR/bin/node wrappers so that
// sub-agents spawned by claude (via the Task tool) can find and run claude.
// The claude wrapper injects the regexp/intl shims the same way the PTY session does.
function writeSubagentWrappers() {
    try {
        fs.mkdirSync(BIN_DIR, { recursive: true });

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
