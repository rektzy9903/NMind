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

const PORT       = 8083;
const PROXY_PORT = 8082;
const HOST       = '127.0.0.1';

// ─── Eval bootstrap shims ─────────────────────────────────────────────────────
// These are injected as strings into every LAUNCHER -e evalCode bootstrap,
// before import(cli.js). Defined at module scope so both runMessage() and
// the !test-cli diagnostic handler can reference them without a ReferenceError.

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

// Set by openTcpBridge to notify active sessions of 429 countdown events.
// Value: function(delaySeconds) — writes OSC countdown to the active socket.
let on429CountdownNotify = null;

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
let autoApprove = (() => {
    try { return new Set(JSON.parse(fs.readFileSync(CONFIRM_FILE, 'utf8'))); }
    catch(_) { return new Set(); }
})();
const pendingConfirms = new Map(); // confirmId -> resolve fn
let confirmIdSeq = 0;

function saveAutoApprove() {
    try { fs.writeFileSync(CONFIRM_FILE, JSON.stringify([...autoApprove])); } catch(_) {}
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

    } else if (name.startsWith('mcp_')) {
        try {
            const content = await callMcpStdioTool(name, input);
            return { content: content || '(no output)', isError: false, newCwd: cwd };
        } catch(e) { return { content: 'MCP error: ' + e.message, isError: true, newCwd: cwd }; }

    } else {
        return { content: 'Unknown tool: ' + name, isError: true, newCwd: cwd };
    }
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
            model: 'claude-3-5-sonnet-20241022',
            max_tokens: 4096,
            messages,
            tools,
            system: systemPrompt,
            stream: true
        });
        const apiKey = cfg.mode === 'subscription'
            ? (cfg.apiKey || 'sk-ant-key')
            : 'sk-ant-proxy000';
        const req = http.request({
            hostname: HOST, port: PROXY_PORT,
            path: '/v1/messages', method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'x-api-key': apiKey,
                'anthropic-version': '2023-06-01',
                'Content-Length': Buffer.byteLength(body)
            }
        }, res => {
            let buf = '';
            const textBlocks = {}, toolBlocks = {};
            let stopReason = 'end_turn';
            let thinkingSignalled = false;

            res.on('data', chunk => {
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
            res.on('error', reject);
        });

        const tid = setTimeout(() => { req.destroy(); reject(new Error('Proxy stream timeout')); }, 120000);
        req.on('error', e => { clearTimeout(tid); reject(e); });
        req.on('close', () => clearTimeout(tid));
        req.write(body); req.end();
    });
}

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
        const allTools = [...AGENTIC_TOOLS, ...getMcpStdioTools()];
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
        log('[agentic] error: ' + e.message + '\n');
        try { socket.write('\r\n\x1b[31m[agentic error] ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
    }

    signalThinkingDone();
    return { text: assistantText, cwd: currentCwd };
}

// ─── Session persistence ──────────────────────────────────────────────────────
// Saves the last N history entries to filesDir/last_session.json so context
// survives app restarts. Loaded on each new socket connection.

function loadSession() {
    try {
        const data = JSON.parse(fs.readFileSync(SESSION_FILE, 'utf8'));
        // Discard sessions older than 24 hours
        if (Date.now() - (data.ts || 0) > 86400000) return [];
        return Array.isArray(data.history) ? data.history : [];
    } catch(_) { return []; }
}

function saveSession(history) {
    try {
        fs.writeFileSync(SESSION_FILE, JSON.stringify({ ts: Date.now(), history }), 'utf8');
    } catch(_) {}
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

function patchCliJsForAndroid(cliPath) {
    log('Patching cli.js for Android (removing \\p{} regex property escapes)...\n');
    let src;
    try { src = fs.readFileSync(cliPath, 'utf8'); } catch (e) {
        log('Patch skipped: could not read cli.js — ' + e.message + '\n');
        return;
    }
    let n = 0;

    function rep(from, to) {
        if (!src.includes(from)) return;
        while (src.includes(from)) { src = src.replace(from, to); n++; }
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
    const proxy = http.createServer((req, res) => {
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
        // GET /v1/models — Claude Code checks this on startup; return a valid Claude model
        // so it matches the ANTHROPIC_MODEL env var we set (always a claude-* name in proxy mode).
        if (req.method === 'GET' && req.url.startsWith('/v1/models')) {
            const modelId = 'claude-3-5-sonnet-20241022';
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
    };

    // OpenRouter needs attribution headers to unlock free models
    if (targetUrl.hostname.includes('openrouter')) {
        headers['HTTP-Referer'] = 'https://github.com/rektzy9903/ClaudeCodeSetup';
        headers['X-Title']      = 'ClaudeCodeSetup';
    }

    const sysLen = (oaiReq.messages.find(m => m.role === 'system') || {}).content?.length || 0;
    log('[proxy-req] host=' + targetUrl.hostname + ' model=' + oaiReq.model + ' msgs=' + oaiReq.messages.length + ' max_tokens=' + oaiReq.max_tokens + ' stream=' + oaiReq.stream + ' bodyLen=' + body.length + ' syspromptChars=' + sysLen + '\n');

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
        // IMPORTANT: we must use ANTHROPIC_API_KEY (not ANTHROPIC_AUTH_TOKEN) and
        // it must start with 'sk-ant-'. Here's why:
        //
        // 1. claude-code v2.1.112 validates that ANTHROPIC_API_KEY starts with
        //    'sk-ant-' before any network call. A provider key (sk-or-..., AIza...)
        //    fails that check → silent exit code 1 with zero output.
        //
        // 2. ANTHROPIC_AUTH_TOKEN triggers claude-code's OAuth session-validation
        //    path. It calls auth/account endpoints expecting specific JSON fields
        //    (user_id, session data). Our proxy returns {} for unknown endpoints,
        //    which fails the validation → silent exit code 1 with zero output.
        //
        // Solution: use a fake 'sk-ant-' key that passes format check and avoids
        // OAuth. The proxy ignores the Bearer token claude-code sends; it always
        // uses cfg.apiKey to authenticate with the real provider.
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

// Strip ANSI escape codes so captured responses store clean text in history.
function stripAnsi(str) {
    return str.replace(/\x1B\[[0-9;]*[a-zA-Z]/g, '').replace(/\r/g, '').trim();
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

const MCP_STDIO_CONFIG = path.join(FILES_DIR, 'mcp_stdio.json');
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

// Prepend prior conversation turns to the current message so --print mode
// has context across multiple exchanges within the same socket session.
// Also prepends customSystemPrompt if configured.
// History entries: { role: 'user'|'assistant', content: string }
// Capped at MAX_HISTORY messages (bridge.js enforces this on write).
// Base instruction injected into every --print spawn so Claude asks questions
// and checks for tools before proceeding. Short enough to stay under the
// tryOptimize 800-char guard (only applies to housekeeping prompts, not here).
const BASE_ASSISTANT_INSTRUCTION =
    'Before starting any non-trivial task: ' +
    '(1) If intent or setup is unclear, ask 1–2 focused clarifying questions first rather than assuming. ' +
    '(2) Use bash to check whether required tools/packages exist before using them; ' +
    'if something is missing, say what is missing and ask whether to install it. ' +
    '(3) Confirm language/framework choice for any new project before generating files.';

function readDeviceContext() {
    try {
        return JSON.parse(fs.readFileSync(path.join(FILES_DIR, 'device_context.json'), 'utf8'));
    } catch(_) { return null; }
}

function buildMessageWithHistory(message, history) {
    const cfg = readConfig();
    const customPrompt = (cfg.customSystemPrompt || '').trim();
    // Always include base instruction; append custom prompt if set
    let sysPrompt = customPrompt
        ? BASE_ASSISTANT_INSTRUCTION + '\n\n[Custom Instructions]\n' + customPrompt
        : BASE_ASSISTANT_INSTRUCTION;

    // Auto-read CLAUDE.md from project path
    const projectPath = cfg.projectPath || '';
    if (projectPath) {
        const claudeMdPath = path.join(projectPath, 'CLAUDE.md');
        try {
            const stat = fs.statSync(claudeMdPath);
            if (stat.isFile() && stat.size < 60000) {
                sysPrompt += '\n\n[CLAUDE.md — project instructions]\n' + fs.readFileSync(claudeMdPath, 'utf8').slice(0, 15000);
            }
        } catch(_) {}
    }

    // Per-project system prompt: read projects.json, find project whose path matches cwd
    if (!customPrompt) {
        try {
            let activeCwd = '';
            try { activeCwd = fs.readFileSync(CWD_FILE, 'utf8').trim(); } catch(_) {}
            if (activeCwd) {
                const projects = JSON.parse(fs.readFileSync(path.join(FILES_DIR, 'projects.json'), 'utf8'));
                if (Array.isArray(projects)) {
                    const proj = projects.find(p => p.path && activeCwd.startsWith(p.path));
                    if (proj && (proj.systemPrompt || '').trim()) {
                        sysPrompt += '\n\n[Project: ' + proj.name + ']\n' + proj.systemPrompt.trim().slice(0, 5000);
                    }
                }
            }
        } catch(_) {}
    }

    // Inject device context
    const deviceCtx = readDeviceContext();
    if (deviceCtx) {
        sysPrompt += '\n[Device context: ' + deviceCtx.time + ' | Battery: ' + deviceCtx.battery +
            ' | ' + deviceCtx.device + ' | ' + deviceCtx.androidVersion + ']';
    }

    if (!history || history.length === 0) {
        return '[System]\n' + sysPrompt + '\n\n' + message;
    }
    const ctx = history.map(m =>
        (m.role === 'user' ? 'Human' : 'Assistant') + ': ' + m.content
    ).join('\n\n');
    return '[System]\n' + sysPrompt + '\n\n' + ctx + '\n\nHuman: ' + message;
}

const MAX_HISTORY = 50; // max stored messages (25 turns) per session

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

// Spawn claude via libpty-helper.so when ptyMode is on, plain launcher otherwise.
// PTY mode gives claude a real TTY (isTTY=true, correct COLS/ROWS, Ctrl+C via SIGINT).
function spawnClaude(evalCode, env, cwd) {
    const cfg = readConfig();
    if (cfg.ptyMode && fs.existsSync(PTY_HELPER)) {
        const cols = String(cfg.ptyCols || 220);
        const rows = String(cfg.ptyRows || 50);
        return spawn(PTY_HELPER, [cols, rows, LAUNCHER, '-e', evalCode], { env, cwd });
    }
    return spawn(LAUNCHER, ['-e', evalCode], { env, cwd });
}

function runMessage(message, socket, history) {
    const env = buildEnv();
    const cfg = readConfig();

    // Prepend prior turns so --print mode has conversation context
    const fullMessage = buildMessageWithHistory(message, history);

    // Log sanitized config so the user can verify the right key/model/URL is active
    log('[config] mode=' + (cfg.mode || '?') +
        ' key=' + sanitizeKey(cfg.apiKey) +
        ' model=' + (cfg.modelId || '?') +
        ' url=' + (cfg.baseUrl || '?') +
        ' providerUrl=' + (cfg.providerUrl || '(direct)') + '\n');
    log('[spawn] LAUNCHER=' + LAUNCHER + '\n');
    log('[spawn] CLAUDE_CLI_exists=' + fs.existsSync(CLAUDE_CLI) + '\n');
    log('[spawn] message=' + message.slice(0, 80) +
        (history && history.length ? ' [history=' + history.length + ']' : '') + '\n');

    // The launcher binary can only load scripts via -e (inline eval).
    // Loading a script file by path always exits silently with code 1,
    // regardless of whether it's .js, .mjs, or uses --input-type=module.
    //
    // However, dynamic import() works fine inside a -e CJS expression:
    // the event loop stays alive until the import() promise settles.
    //
    // So we pass the entire bootstrap as a -e string: set process.argv,
    // then import cli.js as a file:// URL (which honours "type":"module").
    const cliUrl = 'file://' + CLAUDE_CLI;
    // Use process.on('exit', ...) instead of wrapping process.exit:
    // cli.js overwrites process.exit during its own initialization, which silently
    // swallows our wrapper. The 'exit' event fires at the OS level regardless of
    // who called process.exit, so this always runs.
    // Also write [eval-ok] to stderr immediately so we know the eval string ran.
    const exitLogPath = JSON.stringify(SETUP_LOG);
    // regexpShim and intlShim are module-level constants (defined near the top).
    const evalCode =
        'process.stderr.write("[eval-ok]\\n");' +
        'process.on("exit",function(code){' +
        'try{var fs=require("fs");' +
        'fs.appendFileSync(' + exitLogPath + ',"[exit-event] code="+code+"\\n");}' +
        'catch(_e){}});' +
        // Capture unhandledRejections before cli.js installs its own handler.
        // cli.js's handler calls process.exit(1) silently; ours logs the reason first.
        'process.on("unhandledRejection",function(r){' +
        'try{require("fs").appendFileSync(' + exitLogPath + ',' +
        '"[unhandledRejection] "+String(r&&(r.stack||r.message)||r).slice(0,600)+"\\n");}' +
        'catch(_){}});' +
        regexpShim +
        intlShim +
        'process.argv[1]=' + JSON.stringify(CLAUDE_CLI) + ';' +
        'process.argv[2]="--output-format";' +
        'process.argv[3]="stream-json";' +
        'process.argv[4]="--print";' +
        'process.argv[5]="--verbose";' +
        'process.argv[6]=' + JSON.stringify(fullMessage) + ';' +
        'process.argv.length=7;' +
        'import(' + JSON.stringify(cliUrl) + ')' +
        '.then(function(){' +
        'try{require("fs").appendFileSync(' + exitLogPath + ',"[import-resolved]\\n");}catch(_){}})' +
        '.catch(function(e){process.stderr.write("import-err:"+String(e)+"\\n");process.exit(1)});';

    const child = spawnClaude(evalCode, env, FILES_DIR);
    child.stdin.end();

    // Collect stderr separately so we can include it in error messages
    let stderrBuf = '';
    // Parse --output-format stream-json newline-delimited events.
    // thinking-done fires on the first event (system/init) so the terminal
    // transitions THINKING → RESPONDING before any text arrives.
    let thinkingDoneSent = false;
    let stdoutLineBuf = '';
    child.stdout.on('data', d => {
        stdoutLineBuf += d.toString();
        const lines = stdoutLineBuf.split('\n');
        stdoutLineBuf = lines.pop(); // keep incomplete last chunk

        for (const line of lines) {
            if (!line.trim()) continue;

            if (!thinkingDoneSent) {
                thinkingDoneSent = true;
                try { socket.write('\x1b]9;thinking-done\x07'); } catch (_) {}
            }

            let event;
            try { event = JSON.parse(line); } catch (_) {
                // Non-JSON line — forward raw (shouldn't happen with stream-json)
                try { socket.write(line + '\n'); } catch (_) {}
                continue;
            }

            if (event.type === 'assistant') {
                for (const block of (event.message && event.message.content) || []) {
                    if (block.type === 'text' && block.text) {
                        try { socket.write(block.text); } catch (_) {}
                    } else if (block.type === 'thinking' && block.thinking) {
                        // Extended thinking: send via OSC as base64 so index.html can show collapsible
                        const enc = Buffer.from(block.thinking.slice(0, 3000)).toString('base64');
                        try { socket.write('\x1b]9;think-block:' + enc + '\x07'); } catch (_) {}
                    } else if (block.type === 'tool_use') {
                        const argsRaw = JSON.stringify(block.input || {});
                        const argsPreview = argsRaw.length > 100 ? argsRaw.slice(0, 97) + '…' : argsRaw;
                        try { socket.write('\x1b[36m▶ ' + (block.name || 'tool') + '\x1b[0m ' + argsPreview + '\r\n'); } catch (_) {}
                    }
                }
            }
            // system/init, tool_result, result events consumed silently
        }
    });
    child.stderr.on('data', d => {
        const s = d.toString();
        stderrBuf += s;
        log('[claude-stderr] ' + s.slice(0, 800) + '\n');
        // Strip diagnostic bootstrap lines — log only, don't show in terminal.
        // Users see errors via the close-handler hint; raw eval hooks are noise.
        const out = s.split('\n').filter(l => {
            const t = l.trim();
            if (!t) return false;
            return !t.startsWith('[eval-ok]') && !t.startsWith('[import-resolved]') &&
                   !t.startsWith('[exit-event]')  && !t.startsWith('[regex-compat]') &&
                   !t.startsWith('[intl-shim]')    && !t.startsWith('[unhandledRejection]');
        }).join('\n');
        if (out) { try { socket.write(out); } catch (_) {} }
    });

    child.on('error', err => {
        log('spawn error: ' + err.message + '\n');
        try { socket.write('\r\n\x1b[31mFailed to start claude: ' + err.message + '\x1b[0m\r\n'); } catch (_) {}
    });

    // Attach stderrBuf getter so the close handler can read it
    child._stderrBuf = () => stderrBuf;

    return child;
}

function openTcpBridge() {
    const server = net.createServer(socket => {
        if (!isClaudeInstalled()) {
            socket.write('\r\n\x1b[31mClaude Code not installed — run Setup from the app.\x1b[0m\r\n');
            socket.end();
            return;
        }

        let   inputBuf  = '';
        let   busy      = false;
        let   current   = null;
        let   currentTid = null;  // safety-net timeout handle
        let   history   = loadSession();  // load persisted history from last session
        let   sessionMsgCount = 0;
        let   sessionTokenEstimate = 0;

        // Working dir: prefer last saved cwd, then projectPath from config, then FILES_DIR
        let   shellCwd  = (() => {
            const cfg0 = readConfig();
            if (cfg0.projectPath) {
                try { if (require('fs').statSync(cfg0.projectPath).isDirectory()) return cfg0.projectPath; } catch(_) {}
            }
            try {
                const saved = require('fs').readFileSync(CWD_FILE, 'utf8').trim();
                if (saved && require('fs').statSync(saved).isDirectory()) return saved;
            } catch(_) {}
            return FILES_DIR;
        })();

        let contextBlock = '';   // injected into next message via !context command
        let pendingAttachment = null; // injected into next message via !attach command

        // Register countdown notifier for this socket (replaces any previous one)
        on429CountdownNotify = function(delaySecs) {
            try { socket.write('\x1b]9;rate-limit:' + delaySecs + '\x07'); } catch(_) {}
        };

        const startCfg = readConfig();
        const modeLabel = startCfg.mode === 'subscription' ? 'Anthropic' : (startCfg.providerUrl || 'proxy');
        const agentTag  = agenticEnabled ? '  \x1b[35m[AGENTIC]\x1b[0m' : '';
        const resumed   = history.length > 0 ? '  \x1b[2m(resumed ' + Math.floor(history.length/2) + ' turns)\x1b[0m' : '';
        const agenticHint = !agenticEnabled
            ? '\x1b[2mTip: type \x1b[33m!agentic on\x1b[0m\x1b[2m to let Claude run bash & edit files. Type \x1b[33m!help\x1b[0m\x1b[2m for all commands.\x1b[0m\r\n'
            : '';
        socket.write(
            '\r\n\x1b[32mClaude Code ready.\x1b[0m Type a message or \x1b[33m$ command\x1b[0m to run shell.\r\n' +
            '\x1b[2mProvider: ' + modeLabel +
            '  Model: ' + (startCfg.modelId || 'auto') +
            '  Key: ' + sanitizeKey(startCfg.apiKey) + '\x1b[0m' + agentTag + resumed + '\r\n' +
            '\x1b[2mcwd: ' + shellCwd + '\x1b[0m\r\n' +
            agenticHint + '\r\n'
        );
        // Notify terminal UI of initial agentic state and cwd
        try { socket.write('\x1b]9;agentic:' + (agenticEnabled ? 'on' : 'off') + '\x07'); } catch(_) {}
        try { socket.write('\x1b]9;cwd:' + shellCwd + '\x07'); } catch(_) {}

        // Run launcher self-test once per connection — helps diagnose
        // whether the child Node.js process can actually start on this device.
        testLauncher().then(ok => {
            const msg = ok
                ? '\x1b[2m[diag] launcher OK\x1b[0m\r\n'
                : '\x1b[31m[diag] LAUNCHER FAILED — child Node.js cannot start on this device.\x1b[0m\r\n' +
                  '\x1b[33mSee !log for details. The app may need an update.\x1b[0m\r\n';
            try { socket.write(msg); } catch (_) {}
        });

        // normalDataHandler is named so !pty can re-attach it after a PTY session ends.
        const normalDataHandler = d => {
            // In-band resize: ESC 0xFE cols_hi cols_lo rows_hi rows_lo (6 bytes from TerminalActivity)
            // Re-encode as ESC 0xFF for pty_helper's relay_with_resize().
            if (d.length >= 6 && d[0] === 0x1b && d[1] === 0xfe) {
                if (current) {
                    const resize = Buffer.from([0x1b, 0xff, d[2], d[3], d[4], d[5]]);
                    try { current.stdin.write(resize); } catch(_) {}
                }
                return;
            }
            const raw = d.toString();
            // Ctrl+C (0x03): cancel the running process immediately, don't buffer it
            if (raw.includes('\x03')) {
                if (busy && current) {
                    try { current.kill('SIGTERM'); } catch(_) {}
                    try { socket.write('\r\n\x1b[33m^C — stopped\x1b[0m\r\n'); } catch(_) {}
                    if (currentTid) { clearTimeout(currentTid); currentTid = null; }
                    busy = false; current = null;
                }
                inputBuf = '';
                return;
            }
            inputBuf += raw;

            // Process all complete lines in the buffer
            let nl;
            while ((nl = inputBuf.search(/[\r\n]/)) !== -1) {
                const line = inputBuf.slice(0, nl).replace(/[\x00-\x1f\x7f]/g, '').trim();
                inputBuf   = inputBuf.slice(nl + 1);

                if (!line) continue;

                // Resolve pending install confirmations from the terminal UI
                if (line.startsWith('__confirm__:')) {
                    const parts = line.split(':');
                    const confirmId = parts[1];
                    const choice    = parts[2]; // 'yes' | 'always' | 'no'
                    const resolveFn = pendingConfirms.get(confirmId);
                    if (resolveFn) { pendingConfirms.delete(confirmId); resolveFn(choice); }
                    continue;
                }

                // ── Built-in diagnostic commands ──────────────────────────────
                if (line === '!log' || line.startsWith('!log ')) {
                    try {
                        const count   = parseInt(line.slice(4).trim()) || 80;
                        const logText = fs.readFileSync(SETUP_LOG, 'utf8');
                        const lines   = logText.split('\n');
                        const tail    = lines.slice(-count).join('\n');
                        socket.write('\r\n\x1b[2m── setup.log (last ' + count + ' lines) ──\x1b[0m\r\n' +
                            tail.replace(/\n/g, '\r\n') + '\r\n\x1b[2m──────────────\x1b[0m\r\n');
                    } catch (e) {
                        socket.write('\r\n[log read error: ' + e.message + ']\r\n');
                    }
                    continue;
                }
                if (line === '!test') {
                    socket.write('\r\n\x1b[33mRunning launcher test…\x1b[0m\r\n');
                    testLauncher().then(ok => {
                        try {
                            socket.write(ok
                                ? '\x1b[32m✓ Launcher OK — child Node.js starts correctly.\x1b[0m\r\n'
                                : '\x1b[31m✗ Launcher FAILED — child Node.js cannot start. Check !log.\x1b[0m\r\n');
                        } catch (_) {}
                    });
                    continue;
                }
                if (line === '!ver') {
                    const cfg2 = readConfig();
                    try {
                        socket.write('\r\n\x1b[2mLAUNCHER : ' + LAUNCHER + '\r\n' +
                            'CLAUDE   : ' + CLAUDE_CLI + '\r\n' +
                            'EXISTS   : ' + fs.existsSync(CLAUDE_CLI) + '\r\n' +
                            'mode     : ' + (cfg2.mode || '?') + '\r\n' +
                            'key      : ' + sanitizeKey(cfg2.apiKey) + '\r\n' +
                            'model    : ' + (cfg2.modelId || '?') + '\r\n' +
                            'baseUrl  : ' + (cfg2.baseUrl || '?') + '\r\n' +
                            'provider : ' + (cfg2.providerUrl || '?') + '\x1b[0m\r\n');
                    } catch (_) {}
                    continue;
                }
                if (line === '!clear') {
                    history = [];
                    saveSession([]);
                    try { socket.write('\r\n\x1b[32m✓ Conversation history cleared.\x1b[0m\r\n'); } catch (_) {}
                    continue;
                }
                if (line === '!history') {
                    const turns = Math.floor(history.length / 2);
                    try {
                        socket.write('\r\n\x1b[2mHistory: ' + history.length + ' messages (' + turns + ' turns). ' +
                            'Type !clear to reset.\x1b[0m\r\n');
                    } catch (_) {}
                    continue;
                }
                // !gh-auth [token] — store/show GitHub PAT for git push/clone authentication
                if (line.startsWith('!gh-auth')) {
                    const token = line.slice(8).trim();
                    const tokenFile = path.join(FILES_DIR, '.gh_token');
                    if (!token) {
                        const exists = fs.existsSync(tokenFile);
                        try {
                            socket.write(exists
                                ? '\x1b[32m✓ GitHub token is configured.\x1b[0m Use \x1b[33m!gh-auth <new_token>\x1b[0m to update.\r\n\r\n'
                                : '\x1b[33mUsage: !gh-auth <github_personal_access_token>\x1b[0m\r\n' +
                                  'Create one at github.com/settings/tokens (needs \x1b[1mrepo\x1b[0m scope)\r\n\r\n'
                            );
                        } catch(_) {}
                        continue;
                    }
                    try {
                        fs.writeFileSync(tokenFile, token, { mode: 0o600 });
                        try { socket.write('\x1b[32m✓ GitHub token saved.\x1b[0m\r\n$ git push / $ git clone (private repos) now work.\r\n\r\n'); } catch(_) {}
                        log('[gh-auth] token saved\n');
                    } catch(e) {
                        try { socket.write('\x1b[31m✗ ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
                    }
                    continue;
                }

                // !fetch <url> — fetch URL content and inject as context
                if (line.startsWith('!fetch ')) {
                    const url = line.slice(7).trim();
                    if (!url) {
                        try { socket.write('\x1b[33mUsage: !fetch <url>\x1b[0m\r\n\r\n'); } catch(_) {}
                        continue;
                    }
                    try { socket.write('\x1b[2mFetching ' + url + '…\x1b[0m\r\n'); } catch(_) {}
                    const fetchFn = url.startsWith('https') ? https : http;
                    const req = fetchFn.get(url, { headers: { 'User-Agent': 'ClaudeCodeSetup/1.0' } }, res => {
                        let body = '';
                        res.setEncoding('utf8');
                        res.on('data', c => { if (body.length < 200000) body += c; });
                        res.on('end', () => {
                            // Strip HTML tags for cleaner context
                            const clean = body
                                .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, '')
                                .replace(/<style[^>]*>[\s\S]*?<\/style>/gi, '')
                                .replace(/<[^>]+>/g, ' ')
                                .replace(/\s{3,}/g, '\n')
                                .trim()
                                .slice(0, 30000);
                            contextBlock = '🌐 Content from ' + url + ':\n' + clean + '\n';
                            try {
                                socket.write('\x1b[32m✓ Fetched ' + clean.length + ' chars from ' + url + '\x1b[0m\r\n' +
                                    '\x1b[2mContext will be sent with your next message.\x1b[0m\r\n\r\n');
                            } catch(_) {}
                        });
                        res.on('error', e => {
                            try { socket.write('\x1b[31m✗ Fetch error: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
                        });
                    });
                    req.on('error', e => {
                        try { socket.write('\x1b[31m✗ Fetch error: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
                    });
                    req.setTimeout(15000, () => {
                        req.destroy();
                        try { socket.write('\x1b[31m✗ Fetch timeout (15 s)\x1b[0m\r\n\r\n'); } catch(_) {}
                    });
                    continue;
                }

                // !context [path|clear] — set working context injected into next message
                if (line.startsWith('!context')) {
                    const arg = line.slice(8).trim();
                    if (arg === 'clear' || arg === '') {
                        if (!arg) {
                            // No arg → show current cwd tree as context
                            const ctxPath = shellCwd;
                            try {
                                const entries = fs.readdirSync(ctxPath, { withFileTypes: true });
                                const fileList = entries
                                    .map(e => (e.isDirectory() ? '  📂 ' : '  📄 ') + e.name)
                                    .slice(0, 60).join('\n');
                                let ctx = '📁 ' + ctxPath + '\n' + fileList + '\n\n';
                                // Read small key files automatically
                                const keyFiles = ['README.md', 'CLAUDE.md', 'package.json', '.env.example', 'Makefile'];
                                for (const fn of keyFiles) {
                                    const fp = path.join(ctxPath, fn);
                                    try {
                                        const stat = fs.statSync(fp);
                                        if (stat.isFile() && stat.size < 8192) {
                                            ctx += '── ' + fn + ' ──\n' + fs.readFileSync(fp, 'utf8').slice(0, 3000) + '\n\n';
                                        }
                                    } catch (_) {}
                                }
                                contextBlock = ctx;
                                socket.write('\x1b[32m✓ Context loaded: ' + ctxPath + ' (' + entries.length + ' items)\x1b[0m\r\n' +
                                    '\x1b[2m' + fileList.slice(0, 300).replace(/\n/g, '\r\n') + '\x1b[0m\r\n' +
                                    '\x1b[2mContext will be sent with your next message. Type !context clear to reset.\x1b[0m\r\n\r\n');
                            } catch (e) {
                                socket.write('\x1b[31m✗ Context error: ' + e.message + '\x1b[0m\r\n\r\n');
                            }
                        } else {
                            contextBlock = '';
                            socket.write('\x1b[32m✓ Context cleared.\x1b[0m\r\n\r\n');
                        }
                    } else {
                        // !context <path>
                        const ctxPath = path.isAbsolute(arg) ? arg : path.join(shellCwd, arg);
                        try {
                            const entries = fs.readdirSync(ctxPath, { withFileTypes: true });
                            const fileList = entries
                                .map(e => (e.isDirectory() ? '  📂 ' : '  📄 ') + e.name)
                                .slice(0, 60).join('\n');
                            let ctx = '📁 ' + ctxPath + '\n' + fileList + '\n\n';
                            const keyFiles = ['README.md', 'CLAUDE.md', 'package.json', '.env.example', 'Makefile'];
                            for (const fn of keyFiles) {
                                const fp = path.join(ctxPath, fn);
                                try {
                                    const stat = fs.statSync(fp);
                                    if (stat.isFile() && stat.size < 8192) {
                                        ctx += '── ' + fn + ' ──\n' + fs.readFileSync(fp, 'utf8').slice(0, 3000) + '\n\n';
                                    }
                                } catch (_) {}
                            }
                            contextBlock = ctx;
                            socket.write('\x1b[32m✓ Context loaded: ' + ctxPath + '\x1b[0m\r\n' +
                                '\x1b[2m' + fileList.slice(0, 300).replace(/\n/g, '\r\n') + '\x1b[0m\r\n\r\n');
                        } catch (e) {
                            socket.write('\x1b[31m✗ Context error: ' + e.message + '\x1b[0m\r\n\r\n');
                        }
                    }
                    continue;
                }

                // !agentic [on|off] — toggle agentic tool-calling mode
                if (line.startsWith('!agentic')) {
                    const arg = line.slice(8).trim().toLowerCase();
                    if (arg === 'on')  agenticEnabled = true;
                    else if (arg === 'off') agenticEnabled = false;
                    else agenticEnabled = !agenticEnabled;
                    try {
                        if (agenticEnabled) fs.writeFileSync(AGENTIC_FILE, '1');
                        else try { fs.unlinkSync(AGENTIC_FILE); } catch (_) {}
                    } catch (_) {}
                    try {
                        socket.write(agenticEnabled
                            ? '\x1b[35m[AGENTIC ON]\x1b[0m The AI can now run bash, read/write files, chain tool calls.\r\n' +
                              '\x1b[2mBest with Gemini Flash/Pro or Anthropic subscription. Turn off: !agentic off\x1b[0m\r\n\r\n'
                            : '\x1b[2m[AGENTIC OFF]\x1b[0m Back to standard --print mode.\r\n\r\n'
                        );
                        socket.write('\x1b]9;agentic:' + (agenticEnabled ? 'on' : 'off') + '\x07');
                    } catch(_) {}
                    continue;
                }

                // !pty <command> — run a command inside a real POSIX PTY for interactive use
                // (python3 -i, ruby, vim, ssh, etc.)  Requires the libpty-helper.so native binary.
                if (line.startsWith('!pty ') || line === '!pty') {
                    const ptyCmd = line.slice(5).trim();
                    if (!ptyCmd) {
                        try { socket.write(
                            '\x1b[33mUsage: !pty <command>\x1b[0m\r\n' +
                            'Example: \x1b[33m!pty python3\x1b[0m  or  \x1b[33m!pty ruby\x1b[0m  or  \x1b[33m!pty bash\x1b[0m\r\n' +
                            '\x1b[2mPress Ctrl+D or type exit to end the session.\x1b[0m\r\n\r\n'
                        ); } catch(_) {}
                        continue;
                    }
                    if (!fs.existsSync(PTY_HELPER)) {
                        try { socket.write(
                            '\x1b[31m✗ libpty-helper.so not found.\x1b[0m\r\n' +
                            '\x1b[2mRebuild the app — PTY support requires a native binary bundled with the APK.\x1b[0m\r\n\r\n'
                        ); } catch(_) {}
                        continue;
                    }
                    const ptyCmdParts = ptyCmd.split(/\s+/);
                    const cfg2 = readConfig();
                    const ptyCols = String(cfg2.ptyCols || 220);
                    const ptyRows = String(cfg2.ptyRows || 50);
                    const ptyEnv = Object.assign({}, buildEnv(), { TERM: 'xterm-256color' });
                    try { socket.write(
                        '\x1b[33m[PTY] Starting: ' + ptyCmd + '\x1b[0m\r\n' +
                        '\x1b[2mCtrl+D or "exit" to return to normal mode.\x1b[0m\r\n\r\n'
                    ); } catch(_) {}

                    let ptyProc;
                    try {
                        ptyProc = spawn(PTY_HELPER, [ptyCols, ptyRows, ...ptyCmdParts], { env: ptyEnv, cwd: shellCwd });
                    } catch(e) {
                        try { socket.write('\x1b[31m[PTY] Failed to start: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
                        continue;
                    }

                    // Kill any busy AI session while PTY is active
                    if (busy && current) { try { current.kill('SIGTERM'); } catch(_) {} }

                    // Switch socket into raw PTY relay mode
                    socket.removeAllListeners('data');
                    const relayData = (d) => { try { ptyProc.stdin.write(d); } catch(_) {} };
                    socket.on('data', relayData);

                    ptyProc.stdout.on('data', d => { try { socket.write(d); } catch(_) {} });
                    ptyProc.stderr.on('data', d => { try { socket.write(d); } catch(_) {} });
                    ptyProc.on('error', e => {
                        socket.removeListener('data', relayData);
                        // Restore normal line-based data handler
                        socket.on('data', normalDataHandler);
                        try { socket.write('\r\n\x1b[31m[PTY] Error: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
                    });
                    ptyProc.on('close', code => {
                        socket.removeListener('data', relayData);
                        socket.on('data', normalDataHandler);
                        try { socket.write('\r\n\x1b[33m[PTY] Session ended (exit ' + (code || 0) + ')\x1b[0m\r\n\r\n'); } catch(_) {}
                    });
                    continue;
                }

                // !install-git — install isomorphic-git as a $ git command (pure JS, no native binary)
                if (line === '!install-git') {
                    const gitBin  = path.join(FILES_DIR, 'bin', 'git');
                    const gitJs   = path.join(FILES_DIR, 'bin', 'git.js');
                    const binDir  = path.join(FILES_DIR, 'bin');
                    if (fs.existsSync(gitBin)) {
                        try { socket.write('\x1b[32mgit already installed.\x1b[0m Run \x1b[33m$ git --version\x1b[0m to verify.\r\n\r\n'); } catch(_) {}
                        continue;
                    }
                    try { fs.mkdirSync(binDir, { recursive: true }); } catch(_) {}
                    try { socket.write('\x1b[33mInstalling isomorphic-git… (may take ~30 s on first run)\x1b[0m\r\n'); } catch(_) {}
                    log('[install-git] npm install isomorphic-git\n');

                    const igEnv = buildEnv();
                    const installCmd = 'npm install --prefix ' + JSON.stringify(path.join(FILES_DIR, 'npm-global'))
                        + ' isomorphic-git';
                    const ic = spawn('/system/bin/sh', ['-c', installCmd], { env: igEnv, cwd: FILES_DIR });
                    ic.stdout.on('data', d => { try { socket.write(d); } catch(_) {} });
                    ic.stderr.on('data', d => { try { socket.write('\x1b[2m' + d.toString() + '\x1b[0m'); } catch(_) {} });
                    ic.on('close', code => {
                        if (code !== 0) {
                            try { socket.write('\x1b[31m✗ npm install failed (exit ' + code + ')\x1b[0m\r\n\r\n'); } catch(_) {}
                            return;
                        }
                        // Write git.js — a proper isomorphic-git CLI
                        const igPath = path.join(FILES_DIR, 'npm-global', 'node_modules', 'isomorphic-git');
                        const gitJsContent = [
'#!/usr/bin/env node',
'"use strict";',
'const git  = require(' + JSON.stringify(igPath) + ');',
'const http = require(' + JSON.stringify(igPath + '/http/node') + ');',
'const fs   = require("fs");',
'const path = require("path");',
'const args = process.argv.slice(2);',
'const dir  = process.cwd();',
'const sub  = args[0];',
'if (!sub || sub === "--version") { console.log("git version 2.x (isomorphic-git)"); process.exit(0); }',
'const author = { name: process.env.GIT_AUTHOR_NAME || "user", email: process.env.GIT_AUTHOR_EMAIL || "user@device" };',
'const TOKEN_FILE = ' + JSON.stringify(path.join(FILES_DIR, '.gh_token')) + ';',
'const ghToken = (() => { try { return fs.readFileSync(TOKEN_FILE, "utf8").trim(); } catch(_) { return ""; } })();',
'const onAuth = ghToken ? () => ({ username: "x-token-auth", password: ghToken }) : undefined;',
'const opts = { fs, http, dir, author, ...(onAuth ? { onAuth } : {}) };',
'function run(p) { p.then(r => { if (r !== undefined) console.log(JSON.stringify(r, null, 2)); }).catch(e => { process.stderr.write(e.message + "\\n"); process.exit(1); }); }',
'switch(sub) {',
'  case "init":    run(git.init(opts)); break;',
'  case "clone":   run(git.clone({ ...opts, url: args[1], depth: 1 })); break;',
'  case "status":  run(git.statusMatrix(opts).then(m => { m.forEach(([f,h,w,s]) => { const st = h===0?"?":w===h?"=":"M"; console.log(st + " " + f); }); })); break;',
'  case "add":     run(Promise.all((args.slice(1).length?args.slice(1):["."]).map(f => git.add({ ...opts, filepath: f === "." ? undefined : f })))); break;',
'  case "commit":  { const mi = args.indexOf("-m"); run(git.commit({ ...opts, message: mi>=0 ? args.slice(mi+1).join(" ") : "commit" })); break; }',
'  case "push":    run(git.push({ ...opts, remote: args[1]||"origin", remoteRef: args[2]||undefined })); break;',
'  case "pull":    run(git.pull({ ...opts, remote: args[1]||"origin" })); break;',
'  case "fetch":   run(git.fetch({ ...opts, remote: args[1]||"origin" })); break;',
'  case "log":     run(git.log(opts).then(commits => commits.slice(0,20).forEach(c => console.log(c.oid.slice(0,7) + " " + c.commit.message.split("\\n")[0])))); break;',
'  case "branch":  if(args[1]&&args[1]!=="-l"){run(git.branch({...opts,ref:args[1]}));}else{run(git.currentBranch(opts).then(b=>console.log("* "+b)));} break;',
'  case "checkout":run(git.checkout({...opts,ref:args[args.indexOf("-b")>=0?args[args.indexOf("-b")+1]:args[1]]})); break;',
'  case "diff":    run(git.statusMatrix(opts).then(m=>{m.filter(([,h,w])=>w!==h).forEach(([f])=>console.log("M "+f));})); break;',
'  case "remote":  if(args[1]==="add"){run(git.addRemote({...opts,remote:args[2],url:args[3]}));}else{run(git.listRemotes(opts).then(rs=>rs.forEach(r=>console.log(r.remote+"\t"+r.url))));} break;',
'  case "tag":     if(args[1])run(git.tag({...opts,ref:args[1]}));else run(git.listTags(opts).then(ts=>ts.forEach(t=>console.log(t)))); break;',
'  default: process.stderr.write("git: \'" + sub + "\' is not a git command\\n"); process.exit(1);',
'}',
                        ].join('\n');
                        try {
                            fs.writeFileSync(gitJs, gitJsContent, 'utf8');
                            // Shell wrapper so `git` resolves as a command
                            const wrapper = '#!/system/bin/sh\nexec node ' + JSON.stringify(gitJs) + ' "$@"\n';
                            fs.writeFileSync(gitBin, wrapper, { mode: 0o755 });
                            try { socket.write('\x1b[32m✓ git installed.\x1b[0m\r\n' +
                                              'Try: \x1b[33m$ git --version\x1b[0m  or  \x1b[33m$ git init\x1b[0m\r\n' +
                                              '\x1b[2mPowered by isomorphic-git (pure JS — no native binary needed)\x1b[0m\r\n\r\n'); } catch(_) {}
                        } catch(e) {
                            try { socket.write('\x1b[31m✗ Could not write git: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
                        }
                    });
                    continue;
                }

                if (line === '!update') {
                    try {
                        const pkgDir = path.dirname(CLAUDE_CLI);
                        socket.write('\r\n\x1b[33m⟳ Re-installing claude-code v2.1.112…\x1b[0m\r\n');
                        try { fs.rmSync(pkgDir, { recursive: true, force: true }); } catch (_) {}
                        try { fs.unlinkSync(SETUP_DONE); } catch (_) {}
                        installClaudeCode(ok => {
                            try {
                                socket.write(ok
                                    ? '\x1b[32m✓ Update complete. Your next message will use the fresh install.\x1b[0m\r\n\r\n'
                                    : '\x1b[31m✗ Update failed. Check !log for details.\x1b[0m\r\n\r\n');
                            } catch (_) {}
                        });
                    } catch (e) {
                        try { socket.write('\r\n\x1b[31m✗ Update error: ' + e.message + '\x1b[0m\r\n\r\n'); } catch (_) {}
                    }
                    continue;
                }

                if (line === '!stats') {
                    try {
                        socket.write('\x1b[36m📊 Session stats: ' + sessionMsgCount + ' messages • ~' + sessionTokenEstimate + ' tokens estimated\x1b[0m\r\n');
                    } catch (_) {}
                    continue;
                }

                if (line === '!export') {
                    const ts = new Date().toISOString().replace(/[:.]/g,'-').slice(0,19);
                    const fname = path.join(FILES_DIR, 'session_' + ts + '.md');
                    let md = '# Claude Code Session Export\n\n';
                    history.forEach(h => {
                        md += '## ' + (h.role === 'user' ? 'You' : 'Claude') + '\n\n' + h.content + '\n\n---\n\n';
                    });
                    try { fs.writeFileSync(fname, md, 'utf8'); } catch(_) {}
                    try { socket.write('\x1b[32m✓ Session exported to:\x1b[0m ' + fname + '\r\n'); } catch(_) {}
                    continue;
                }

                if (line.startsWith('!import ')) {
                    const fname = line.slice(8).trim();
                    try {
                        const content = fs.readFileSync(fname, 'utf8');
                        const blocks = content.split(/^## /m).slice(1);
                        const loaded = [];
                        blocks.forEach(b => {
                            const firstLine = b.split('\n')[0].trim();
                            const bodyLines = b.split('\n').slice(2);
                            const end = bodyLines.findIndex(l => l.trim() === '---');
                            const body = bodyLines.slice(0, end >= 0 ? end : undefined).join('\n').trim();
                            if (firstLine === 'You') loaded.push({ role: 'user', content: body });
                            else if (firstLine === 'Claude') loaded.push({ role: 'assistant', content: body });
                        });
                        history = loaded;
                        saveSession(history);
                        socket.write('\x1b[32m✓ Imported ' + loaded.length + ' messages from ' + fname + '\x1b[0m\r\n');
                    } catch(e) {
                        socket.write('\x1b[31m✗ Import failed: ' + e.message + '\x1b[0m\r\n');
                    }
                    continue;
                }

                // ── Slash commands: /init /review /cost /doctor /compact ─────────
                if (line.startsWith('/') && !line.startsWith('$ ')) {
                    const slashFull = line.slice(1).trim();
                    const slashCmd  = slashFull.split(/\s+/)[0].toLowerCase();

                    if (slashCmd === 'cost') {
                        const PRICING = {
                            'claude-3-5-sonnet': { in: 3, out: 15 },
                            'claude-3-haiku':    { in: 0.25, out: 1.25 },
                            'claude-3-opus':     { in: 15, out: 75 },
                            'gemini-1.5-flash':  { in: 0.075, out: 0.30 },
                            'gemini-1.5-pro':    { in: 1.25, out: 5 },
                            'gemini-2.0-flash':  { in: 0.10, out: 0.40 },
                            'gpt-4o':            { in: 2.50, out: 10 },
                            'gpt-4o-mini':       { in: 0.15, out: 0.60 },
                        };
                        const cfg2 = readConfig();
                        const mid  = (cfg2.modelId || '').toLowerCase();
                        const pkey = Object.keys(PRICING).find(k => mid.includes(k));
                        const p    = pkey ? PRICING[pkey] : null;
                        const inTok  = Math.round(sessionTokenEstimate * 0.6);
                        const outTok = Math.round(sessionTokenEstimate * 0.4);
                        const costStr = p
                            ? '$' + ((inTok * p.in + outTok * p.out) / 1e6).toFixed(4)
                            : '(pricing N/A for this provider)';
                        try { socket.write(
                            '\r\n\x1b[36m📊 Session cost\x1b[0m\r\n' +
                            '  Messages  : ' + sessionMsgCount + '\r\n' +
                            '  Tokens est: ~' + sessionTokenEstimate + ' (~' + inTok + ' in, ~' + outTok + ' out)\r\n' +
                            '  History   : ' + history.length + ' messages\r\n' +
                            '  Provider  : ' + (cfg2.providerUrl || cfg2.baseUrl || 'direct') + '\r\n' +
                            '  Model     : ' + (cfg2.modelId || '?') + '\r\n' +
                            '  Est. cost : ' + costStr + '\r\n\r\n'
                        ); } catch(_) {}
                        continue;
                    }

                    if (slashCmd === 'doctor') {
                        const cfg2 = readConfig();
                        const checks = [
                            ['Launcher', fs.existsSync(LAUNCHER) ? '✓ ' + path.basename(LAUNCHER) : '✗ missing: ' + LAUNCHER],
                            ['Claude CLI', fs.existsSync(CLAUDE_CLI) ? '✓ exists' : '✗ not installed (run setup)'],
                            ['Config mode', cfg2.mode || '(not set)'],
                            ['API key', sanitizeKey(cfg2.apiKey)],
                            ['Model', cfg2.modelId || '(not set)'],
                            ['Provider URL', cfg2.providerUrl || '(not set)'],
                            ['cwd', shellCwd],
                            ['BusyBox', fs.existsSync(path.join(BIN_DIR, 'busybox')) ? '✓ installed' : '✗ run !install busybox'],
                            ['git', (() => {
                                const gf = path.join(BIN_DIR, 'git');
                                if (!fs.existsSync(gf)) return '✗ run !install git (native) or !install-git (JS)';
                                try {
                                    const src = fs.readFileSync(gf, 'utf8');
                                    return src.includes('isomorphic') ? '✓ isomorphic-git (JS)' : '✓ native ARM64 binary';
                                } catch(_) { return '✓ installed'; }
                            })()],
                            ['python3', fs.existsSync(path.join(BIN_DIR, 'python3')) ? '✓ installed' : '✗ run !install python3'],
                            ['go',      fs.existsSync(path.join(BIN_DIR, 'go'))      ? '✓ installed' : '✗ run !install go'],
                            ['zig',     fs.existsSync(path.join(BIN_DIR, 'zig'))     ? '✓ installed' : '✗ run !install zig'],
                            ['ssh',     fs.existsSync(path.join(BIN_DIR, 'ssh'))     ? '✓ installed' : '✗ run !install ssh'],
                            ['ruby',    fs.existsSync(path.join(BIN_DIR, 'ruby'))    ? '✓ installed' : '✗ run !install ruby'],
                            ['clang',   fs.existsSync(path.join(BIN_DIR, 'clang'))   ? '✓ installed' : '✗ run !install clang'],
                            ['PTY',     fs.existsSync(PTY_HELPER) ? '✓ available (!pty <cmd>)' : '✗ rebuild app'],
                            ['CLAUDE.md', (() => { const p2 = path.join(shellCwd, 'CLAUDE.md'); return fs.existsSync(p2) ? '✓ found' : '✗ not found (run /init)'; })()],
                        ];
                        let msg = '\r\n\x1b[1m/doctor — Environment\x1b[0m\r\n';
                        for (const [label, val] of checks) {
                            const ok = val.startsWith('✓');
                            msg += '  ' + (ok ? '\x1b[32m' : '\x1b[33m') + label + ':\x1b[0m ' + val + '\r\n';
                        }
                        try { socket.write(msg + '\r\n'); } catch(_) {}
                        continue;
                    }

                    if (slashCmd === 'compact') {
                        try { socket.write('\x1b[33m/compact — forcing context compaction…\x1b[0m\r\n'); } catch(_) {}
                        const COMPACT_THRESHOLD_orig = MAX_HISTORY - 4;
                        // Force compact regardless of threshold by temporarily pretending we're full
                        const fakeHist = history.concat([
                            { role: 'user', content: '__compact__' },
                            { role: 'assistant', content: '__compact__' },
                            { role: 'user', content: '__compact__' },
                            { role: 'assistant', content: '__compact__' },
                        ]);
                        autoCompact(fakeHist, socket).then(compacted => {
                            // Remove the fake entries we added
                            history = compacted.filter(h => h.content !== '__compact__');
                            saveSession(history);
                            try { socket.write('\x1b[32m✓ Compacted. History: ' + history.length + ' messages.\x1b[0m\r\n\r\n'); } catch(_) {}
                        }).catch(() => {
                            try { socket.write('\x1b[31m✗ Compact failed (provider may be offline).\x1b[0m\r\n\r\n'); } catch(_) {}
                        });
                        continue;
                    }

                    if (slashCmd === 'review') {
                        if (busy) { try { socket.write('\x1b[33mBusy — wait for current response.\x1b[0m\r\n'); } catch(_) {} continue; }
                        try { socket.write('\x1b[33mFetching git diff…\x1b[0m\r\n'); } catch(_) {}
                        const diffEnv = buildEnv();
                        const diffCmd = 'git diff --staged 2>/dev/null; git diff HEAD 2>/dev/null | head -300';
                        let diffOut = '';
                        const dc = spawn('/system/bin/sh', ['-c', diffCmd], { env: diffEnv, cwd: shellCwd });
                        dc.stdout.on('data', d => { diffOut += d.toString(); });
                        dc.on('close', () => {
                            if (!diffOut.trim()) {
                                try { socket.write('\x1b[33mNo git changes found.\x1b[0m\r\n\r\n'); } catch(_) {}
                                return;
                            }
                            const revMsg = 'Review the following code diff for quality, bugs, security, and improvements:\n\n```diff\n' + diffOut.slice(0, 8000) + '\n```';
                            try { socket.write('\x1b]9;thinking-start\x07'); } catch(_) {}
                            busy = true; sessionMsgCount++;
                            let rBuf = ''; let rStarted = false;
                            current = runMessage(revMsg, socket, history);
                            current.stdout.once('data', () => { rStarted = true; });
                            current.stdout.on('data', d => { rBuf += d.toString(); });
                            currentTid = setTimeout(() => {
                                if (!busy) return;
                                try { if (current) current.kill('SIGTERM'); } catch(_) {}
                                try { socket.write('\x1b]9;thinking-done\x07\r\n\x1b[31m✗ Review timed out.\x1b[0m\r\n'); } catch(_) {}
                                busy = false; current = null; currentTid = null;
                            }, 90000);
                            current.on('close', code => {
                                if (currentTid) { clearTimeout(currentTid); currentTid = null; }
                                try { socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
                                if (rStarted && (code === 0 || code === null)) {
                                    const reply = stripAnsi(rBuf);
                                    if (reply) {
                                        history.push({ role: 'user', content: revMsg });
                                        history.push({ role: 'assistant', content: reply });
                                        if (history.length > MAX_HISTORY) history = history.slice(-MAX_HISTORY);
                                        saveSession(history);
                                    }
                                }
                                rBuf = ''; busy = false; current = null;
                                try { socket.write('\r\n'); } catch(_) {}
                            });
                        });
                        dc.on('error', () => { try { socket.write('\x1b[31m✗ git not available. Run !install-git first.\x1b[0m\r\n\r\n'); } catch(_) {} });
                        continue;
                    }

                    if (slashCmd === 'init') {
                        if (busy) { try { socket.write('\x1b[33mBusy — wait for current response.\x1b[0m\r\n'); } catch(_) {} continue; }
                        try { socket.write('\x1b[33mScanning project for /init…\x1b[0m\r\n'); } catch(_) {}
                        let projInfo = 'Project directory: ' + shellCwd + '\n\nFiles:\n';
                        const listD = (dir, pfx, depth) => {
                            try {
                                for (const e of fs.readdirSync(dir, { withFileTypes: true }).slice(0, 40)) {
                                    if (e.name.startsWith('.') || e.name === 'node_modules' || e.name === 'build' || e.name === 'dist') continue;
                                    projInfo += pfx + (e.isDirectory() ? '📂 ' : '📄 ') + e.name + '\n';
                                    if (depth < 1 && e.isDirectory()) listD(path.join(dir, e.name), pfx + '  ', depth + 1);
                                }
                            } catch(_) {}
                        };
                        listD(shellCwd, '', 0);
                        for (const fn of ['package.json', 'README.md', 'build.gradle', 'requirements.txt', 'go.mod', 'Makefile', 'Cargo.toml']) {
                            try {
                                const fp2 = path.join(shellCwd, fn);
                                const st = fs.statSync(fp2);
                                if (st.isFile() && st.size < 4000) projInfo += '\n### ' + fn + '\n' + fs.readFileSync(fp2, 'utf8').slice(0, 2000) + '\n';
                            } catch(_) {}
                        }
                        const initMsg =
                            'Analyze this project and write a CLAUDE.md file with these sections:\n' +
                            '1. What this project does (2-3 sentences)\n2. Tech stack / key dependencies\n' +
                            '3. Folder structure overview\n4. How to build/run/test\n' +
                            '5. Key patterns and conventions\n6. Things to always remember (gotchas, constraints)\n\n' +
                            'Project info:\n' + projInfo.slice(0, 6000) + '\n\nReply with ONLY the CLAUDE.md markdown content.';
                        try { socket.write('\x1b]9;thinking-start\x07'); } catch(_) {}
                        busy = true; sessionMsgCount++;
                        let iBuf = ''; let iStarted = false;
                        current = runMessage(initMsg, socket, []);
                        current.stdout.once('data', () => { iStarted = true; });
                        current.stdout.on('data', d => { iBuf += d.toString(); });
                        currentTid = setTimeout(() => {
                            if (!busy) return;
                            try { if (current) current.kill('SIGTERM'); } catch(_) {}
                            try { socket.write('\x1b]9;thinking-done\x07\r\n\x1b[31m✗ /init timed out.\x1b[0m\r\n'); } catch(_) {}
                            busy = false; current = null; currentTid = null;
                        }, 90000);
                        current.on('close', code => {
                            if (currentTid) { clearTimeout(currentTid); currentTid = null; }
                            try { socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
                            if (iStarted && (code === 0 || code === null)) {
                                const reply = stripAnsi(iBuf);
                                if (reply.trim()) {
                                    const cmp = path.join(shellCwd, 'CLAUDE.md');
                                    try {
                                        fs.writeFileSync(cmp, reply.trim() + '\n', 'utf8');
                                        try { socket.write('\x1b[32m✓ CLAUDE.md written:\x1b[0m ' + cmp + '\r\n\r\n'); } catch(_) {}
                                    } catch(e2) {
                                        try { socket.write('\x1b[31m✗ Write failed: ' + e2.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
                                    }
                                }
                            }
                            iBuf = ''; busy = false; current = null;
                            try { socket.write('\r\n'); } catch(_) {}
                        });
                        continue;
                    }

                    // Unknown slash command
                    try { socket.write('\x1b[33mSlash commands:\x1b[0m /init  /review  /cost  /doctor  /compact\r\n\r\n'); } catch(_) {}
                    continue;
                }

                // !install [package] — install binary or npm package from catalog
                if (line === '!install' || line.startsWith('!install ')) {
                    const arg = line.slice(8).trim().toLowerCase();
                    if (!arg) {
                        const entries = Object.entries(PACKAGE_CATALOG);
                        let msg = '\r\n\x1b[1mAvailable packages\x1b[0m\r\n';
                        const binaries = entries.filter(([,v]) => v.type === 'binary');
                        const archives = entries.filter(([,v]) => v.type === 'archive' || v.type === 'termux-debs');
                        const npms = entries.filter(([,v]) => v.type === 'npm');

                        const isInstalled = (k, v) => {
                            if (v.type === 'binary')      return fs.existsSync(path.join(BIN_DIR, v.bin || k));
                            if (v.type === 'archive')     return fs.existsSync(path.join(BIN_DIR, k));
                            if (v.type === 'termux-debs') {
                                const gf = path.join(BIN_DIR, 'git');
                                return fs.existsSync(gf) && !fs.readFileSync(gf,'utf8').includes('isomorphic');
                            }
                            return false;
                        };

                        if (binaries.length) {
                            msg += '\r\n\x1b[36mStatic ARM64 binaries:\x1b[0m\r\n';
                            for (const [k, v] of binaries) {
                                const chk = isInstalled(k, v) ? ' \x1b[32m✓\x1b[0m' : '';
                                msg += '  \x1b[33m!install ' + k + '\x1b[0m' + chk + '  — ' + v.desc + '\r\n';
                            }
                        }
                        if (archives.length) {
                            msg += '\r\n\x1b[36mRuntime packages:\x1b[0m\r\n';
                            for (const [k, v] of archives) {
                                const chk = isInstalled(k, v) ? ' \x1b[32m✓\x1b[0m' : '';
                                msg += '  \x1b[33m!install ' + k + '\x1b[0m' + chk + '  — ' + v.desc + '\r\n';
                            }
                        }
                        if (npms.length) {
                            msg += '\r\n\x1b[36mnpm packages:\x1b[0m\r\n';
                            for (const [k, v] of npms) {
                                msg += '  \x1b[33m!install ' + k + '\x1b[0m  — ' + v.desc + '\r\n';
                            }
                        }
                        msg += '\r\nUsage: \x1b[33m!install <name>\x1b[0m\r\n\r\n';
                        try { socket.write(msg); } catch(_) {}
                    } else {
                        installPackage(arg, socket);
                    }
                    continue;
                }

                // !undo — restore the most recently snapshotted file
                if (line === '!undo') {
                    try {
                        if (!fs.existsSync(UNDO_DIR)) {
                            socket.write('\x1b[33mNo undo snapshots available.\x1b[0m\r\n\r\n');
                            continue;
                        }
                        const snaps = fs.readdirSync(UNDO_DIR).sort().reverse();
                        if (!snaps.length) {
                            socket.write('\x1b[33mNo undo snapshots available.\x1b[0m\r\n\r\n');
                            continue;
                        }
                        const latest = snaps[0];
                        const snapPath = path.join(UNDO_DIR, latest);
                        // Reconstruct original file path from snapshot name (ts_filename)
                        const origName = latest.replace(/^\d+_/, '');
                        // Search for the file in shellCwd tree
                        let targetPath = null;
                        const findFile = (dir, name, depth) => {
                            if (depth > 4 || targetPath) return;
                            try {
                                for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
                                    if (e.name === name && e.isFile()) { targetPath = path.join(dir, name); return; }
                                    if (e.isDirectory() && !e.name.startsWith('.') && !e.name.startsWith('node_modules'))
                                        findFile(path.join(dir, e.name), name, depth + 1);
                                }
                            } catch(_) {}
                        };
                        findFile(shellCwd, origName, 0);
                        if (!targetPath) targetPath = path.join(shellCwd, origName);
                        fs.copyFileSync(snapPath, targetPath);
                        fs.unlinkSync(snapPath);
                        socket.write('\x1b[32m✓ Restored:\x1b[0m ' + targetPath + '\r\n' +
                            '\x1b[2m(from snapshot ' + latest + ')\x1b[0m\r\n' +
                            (snaps.length > 1 ? '\x1b[2m' + (snaps.length - 1) + ' older snapshots remain. Run !undo again to go further back.\x1b[0m\r\n' : '') +
                            '\r\n');
                    } catch(e) {
                        try { socket.write('\x1b[31m✗ Undo failed: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
                    }
                    continue;
                }

                // !mcp-stdio — manage stdio MCP servers
                if (line.startsWith('!mcp-stdio') || line === '!mcp-stdio') {
                    const arg = line.slice(10).trim();
                    if (!arg || arg === 'list') {
                        if (mcpStdioServers.size === 0) {
                            try { socket.write('\x1b[33mNo stdio MCP servers running.\x1b[0m\r\nAdd servers in filesDir/mcp_stdio.json:\r\n[\r\n  { "name": "my-server", "command": "node", "args": ["/path/to/server.js"] }\r\n]\r\nThen run \x1b[33m!mcp-stdio reload\x1b[0m\r\n\r\n'); } catch(_) {}
                        } else {
                            let msg = '\r\n\x1b[1mstdio MCP servers:\x1b[0m\r\n';
                            for (const [name, srv] of mcpStdioServers.entries()) {
                                msg += '  \x1b[32m' + name + '\x1b[0m  ' + srv.tools.length + ' tools\r\n';
                                for (const t of srv.tools) msg += '    \x1b[2m' + t.name + '\x1b[0m — ' + (t.description || '') + '\r\n';
                            }
                            try { socket.write(msg + '\r\n'); } catch(_) {}
                        }
                    } else if (arg === 'reload') {
                        // Kill all running servers and reload config
                        for (const srv of mcpStdioServers.values()) {
                            try { srv.proc && srv.proc.kill(); } catch(_) {}
                        }
                        mcpStdioServers.clear();
                        try { socket.write('\x1b[33mReloading stdio MCP servers…\x1b[0m\r\n'); } catch(_) {}
                        loadMcpStdioServers().then(() => {
                            const n = mcpStdioServers.size;
                            try { socket.write('\x1b[32m✓ Loaded ' + n + ' server(s)\x1b[0m\r\n\r\n'); } catch(_) {}
                        }).catch(e => {
                            try { socket.write('\x1b[31m✗ ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
                        });
                    } else {
                        try { socket.write('\x1b[33mUsage: !mcp-stdio [list|reload]\x1b[0m\r\n\r\n'); } catch(_) {}
                    }
                    continue;
                }

                // !attach <path> — inject document content as context
                if (line.startsWith('!attach ') || line === '!attach') {
                    const filePath = line.slice(8).trim();
                    if (!filePath) {
                        try { socket.write('\x1b[33mUsage: !attach <filepath>\x1b[0m\r\nSupported: .txt .md .csv .json .js .py .kt .html .xml .yaml .yml\r\n\r\n'); } catch(_) {}
                        continue;
                    }
                    const resolvedPath = path.isAbsolute(filePath) ? filePath : path.join(shellCwd, filePath);
                    try {
                        if (!fs.existsSync(resolvedPath)) {
                            try { socket.write('\x1b[31m✗ File not found: ' + resolvedPath + '\x1b[0m\r\n\r\n'); } catch(_) {}
                            continue;
                        }
                        const stat = fs.statSync(resolvedPath);
                        const MAX_ATTACH = 100 * 1024; // 100 KB limit
                        if (stat.size > MAX_ATTACH) {
                            try { socket.write('\x1b[31m✗ File too large (' + Math.round(stat.size/1024) + ' KB). Max 100 KB.\x1b[0m\r\n\r\n'); } catch(_) {}
                            continue;
                        }
                        const ext = path.extname(resolvedPath).toLowerCase();
                        let content = fs.readFileSync(resolvedPath, 'utf8');
                        let formatted;
                        if (ext === '.csv') {
                            // Format CSV as a readable markdown table (up to 50 rows)
                            const lines = content.split('\n').filter(l => l.trim());
                            const rows = lines.slice(0, 51).map(l => l.split(',').map(c => c.trim().replace(/^"|"$/g, '')));
                            if (rows.length > 1) {
                                const header = rows[0];
                                const sep = header.map(() => '---');
                                const tableRows = rows.slice(1, 50);
                                const mdTable = [header, sep, ...tableRows].map(r => '| ' + r.join(' | ') + ' |').join('\n');
                                formatted = '[Attached CSV: ' + path.basename(resolvedPath) + ']\n' + mdTable;
                                if (lines.length > 51) formatted += '\n*(truncated — ' + (lines.length - 51) + ' more rows)*';
                            } else {
                                formatted = '[Attached CSV: ' + path.basename(resolvedPath) + ']\n' + content.slice(0, 8000);
                            }
                        } else {
                            // Plain text / code — inject as fenced block
                            const langHint = { '.js':'js','.ts':'ts','.py':'python','.kt':'kotlin',
                                '.java':'java','.html':'html','.xml':'xml','.json':'json',
                                '.yaml':'yaml','.yml':'yaml','.sh':'bash','.md':'markdown' }[ext] || '';
                            content = content.slice(0, 8000);
                            formatted = '[Attached file: ' + path.basename(resolvedPath) + ']\n```' + langHint + '\n' + content + '\n```';
                        }
                        // Inject as the next pending context prefix (appended to next user message)
                        pendingAttachment = formatted;
                        const sz = Math.round(stat.size / 1024 * 10) / 10;
                        try { socket.write('\x1b[32m✓ Attached:\x1b[0m ' + path.basename(resolvedPath) + ' (' + sz + ' KB)\r\nContent will be included in your next message.\r\n\r\n'); } catch(_) {}
                    } catch(e) {
                        try { socket.write('\x1b[31m✗ Attach failed: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
                    }
                    continue;
                }

                if (line === '!help') {
                    try {
                        // Command column is 26 chars wide (accommodates longest cmd).
                        // Format: '  ' + colored_cmd + \x1b[0m + padding + description
                        socket.write(
                            '\r\n\x1b[1mSlash commands:\x1b[0m\r\n' +
                            '  \x1b[36m/init\x1b[0m                     Scan project, generate CLAUDE.md\r\n' +
                            '  \x1b[36m/review\x1b[0m                   Review staged git diff with AI\r\n' +
                            '  \x1b[36m/cost\x1b[0m                     Token usage and estimated cost\r\n' +
                            '  \x1b[36m/doctor\x1b[0m                   Check environment health\r\n' +
                            '  \x1b[36m/compact\x1b[0m                  Summarize and compact context\r\n' +
                            '\r\n\x1b[1mShell:\x1b[0m\r\n' +
                            '  \x1b[33m$ <command>\x1b[0m               Run any shell command\r\n' +
                            '  \x1b[33m$ cd <dir>\x1b[0m                Change working directory\r\n' +
                            '\r\n\x1b[1mAI conversation:\x1b[0m\r\n' +
                            '  (type normally)              Chat with the AI model\r\n' +
                            '  \x1b[33m!clear\x1b[0m                    Clear conversation history\r\n' +
                            '  \x1b[33m!history\x1b[0m                  Show history turn count\r\n' +
                            '  \x1b[33m!context [path]\x1b[0m           Load dir + key files as context\r\n' +
                            '  \x1b[33m!attach <file>\x1b[0m            Attach doc/CSV as next-message context\r\n' +
                            '  \x1b[33m!fetch <url>\x1b[0m              Fetch URL and inject as context\r\n' +
                            '  \x1b[33m!stats\x1b[0m                    Session stats (same as /cost)\r\n' +
                            '  \x1b[33m!export\x1b[0m                   Export conversation to Markdown\r\n' +
                            '  \x1b[33m!import <file>\x1b[0m            Restore exported conversation\r\n' +
                            '  \x1b[33m!undo\x1b[0m                     Restore most recently overwritten file\r\n' +
                            '\r\n\x1b[1mSetup & tools:\x1b[0m\r\n' +
                            '  \x1b[33m!install [name]\x1b[0m           Install binary or npm pkg (no args = catalog)\r\n' +
                            '  \x1b[33m!pty <command>\x1b[0m            Run command in real PTY (python3, ruby, vim…)\r\n' +
                            '  \x1b[33m!install-git\x1b[0m              Install isomorphic-git for $ git commands\r\n' +
                            '  \x1b[33m!gh-auth <token>\x1b[0m          Save GitHub token for git push/clone\r\n' +
                            '  \x1b[33m!agentic [on|off]\x1b[0m         Toggle agentic tool-calling loop\r\n' +
                            '  \x1b[33m!mcp-stdio [list|reload]\x1b[0m  Manage stdio MCP servers\r\n' +
                            '  \x1b[33m!update\x1b[0m                   Re-install claude-code v2.1.112\r\n' +
                            '  \x1b[33m!log [N]\x1b[0m                  Show last N lines of setup.log\r\n' +
                            '  \x1b[33m!ver / !test / !test-cli\x1b[0m  Diagnostics\r\n' +
                            '  \x1b[33m!help\x1b[0m                     Show this message\r\n\r\n'
                        );
                    } catch (_) {}
                    continue;
                }
                // !test-cli — step-by-step module-loader diagnostic
                // Tests increasingly complex scenarios to find the exact failure point.
                if (line === '!test-cli') {
                    socket.write('\r\n\x1b[33mRunning module-loader diagnostic (5 steps)…\x1b[0m\r\n');
                    const env2 = buildEnv();
                    const cliUrl2 = 'file://' + CLAUDE_CLI;

                    // File-based step: write file, spawn LAUNCHER [filepath]
                    function runFileStep(label, scriptPath, scriptContent, cb) {
                        fs.writeFileSync(scriptPath, scriptContent);
                        let out = '', err = '';
                        let child2;
                        try {
                            child2 = spawn(LAUNCHER, [scriptPath], { env: env2, cwd: FILES_DIR });
                            child2.stdin.end();
                        } catch (e) {
                            socket.write('\x1b[31m  ' + label + ': spawn-err ' + e.message + '\x1b[0m\r\n');
                            cb(); return;
                        }
                        child2.stdout.on('data', d => { out += d.toString(); });
                        child2.stderr.on('data', d => { err += d.toString(); });
                        const tid = setTimeout(() => {
                            try { child2.kill(); } catch (_) {}
                            socket.write('\x1b[31m  ' + label + ': TIMEOUT\x1b[0m\r\n');
                            cb();
                        }, 10000);
                        child2.on('close', code => {
                            clearTimeout(tid);
                            log('[test-cli] ' + label + ' exit=' + code +
                                ' out=' + JSON.stringify(out.slice(0,200)) +
                                ' err=' + JSON.stringify(err.slice(0,300)) + '\n');
                            const mark = code === 0 ? '\x1b[32m✓' : '\x1b[31m✗';
                            let msg2 = mark + ' ' + label + ' exit=' + code + '\x1b[0m';
                            if (out.trim()) msg2 += '  out:' + out.trim().slice(0,80);
                            if (err.trim()) msg2 += '\r\n    \x1b[31merr:' + err.trim().slice(0,200) + '\x1b[0m';
                            socket.write('  ' + msg2 + '\r\n');
                            cb();
                        });
                    }

                    // Eval step: spawn LAUNCHER ['-e', evalCode]
                    function runEvalStep(label, evalCode, cb) {
                        let out = '', err = '';
                        let child2;
                        try {
                            child2 = spawn(LAUNCHER, ['-e', evalCode], { env: env2, cwd: FILES_DIR });
                            child2.stdin.end();
                        } catch (e) {
                            socket.write('\x1b[31m  ' + label + ': spawn-err ' + e.message + '\x1b[0m\r\n');
                            cb(); return;
                        }
                        child2.stdout.on('data', d => { out += d.toString(); });
                        child2.stderr.on('data', d => { err += d.toString(); });
                        const tid = setTimeout(() => {
                            try { child2.kill(); } catch (_) {}
                            socket.write('\x1b[31m  ' + label + ': TIMEOUT\x1b[0m\r\n');
                            cb();
                        }, 30000);
                        child2.on('close', code => {
                            clearTimeout(tid);
                            log('[test-cli] ' + label + ' exit=' + code +
                                ' out=' + JSON.stringify(out.slice(0,200)) +
                                ' err=' + JSON.stringify(err.slice(0,300)) + '\n');
                            const mark = code === 0 ? '\x1b[32m✓' : '\x1b[31m✗';
                            let msg2 = mark + ' ' + label + ' exit=' + code + '\x1b[0m';
                            if (out.trim()) msg2 += '  out:' + out.trim().slice(0,80);
                            if (err.trim()) msg2 += '\r\n    \x1b[31merr:' + err.trim().slice(0,200) + '\x1b[0m';
                            socket.write('  ' + msg2 + '\r\n');
                            cb();
                        });
                    }

                    const t1 = path.join(FILES_DIR, 'diag1.js');
                    const t2 = path.join(FILES_DIR, 'diag2.mjs');
                    const t3 = path.join(FILES_DIR, 'diag3.mjs');

                    // Step 6: net connectivity test — probe port 8082 from inside the child.
                    // If the child process can't connect to the proxy, this will say FAIL.
                    var netTestCode =
                        'var net=require("net");' +
                        'var c=net.connect(' + PROXY_PORT + ',"' + HOST + '",function(){' +
                        'process.stdout.write("net-ok\\n");c.destroy();process.exit(0);});' +
                        'c.on("error",function(e){' +
                        'process.stdout.write("net-fail:"+e.message+"\\n");process.exit(1);});' +
                        'setTimeout(function(){process.stdout.write("net-timeout\\n");process.exit(1);},5000);';

                    runFileStep('[1] CJS .js file', t1,
                        '"use strict"; process.stdout.write("cjs-ok\\n");\n',
                    () => runFileStep('[2] .mjs (no await)', t2,
                        'process.stdout.write("mjs-ok\\n");\n',
                    () => runFileStep('[3] .mjs (top-level await)', t3,
                        'await Promise.resolve();\nprocess.stdout.write("mjs-await-ok\\n");\n',
                    () => runEvalStep('[4] -e import(cli.js) --version',
                        'process.argv[1]=' + JSON.stringify(CLAUDE_CLI) + ';' +
                        'process.argv[2]="--version";process.argv.length=3;' +
                        'import(' + JSON.stringify(cliUrl2) + ')' +
                        '.catch(function(e){process.stderr.write("ERR:"+String(e)+"\\n");process.exit(1)});',
                    () => runEvalStep('[5] -e import(cli.js) --print hello (+RegExp shim)',
                        'process.stderr.write("[eval-ok]\\n");' +
                        'process.on("exit",function(code){' +
                        'try{var fs=require("fs");fs.appendFileSync(' + JSON.stringify(SETUP_LOG) + ',"[exit-event] code="+code+"\\n");}catch(_e){}});' +
                        'process.on("unhandledRejection",function(r){' +
                        'try{require("fs").appendFileSync(' + JSON.stringify(SETUP_LOG) + ',' +
                        '"[unhandledRejection] "+String(r&&(r.stack||r.message)||r).slice(0,600)+"\\n");}catch(_){}});' +
                        regexpShim +
                        intlShim +
                        'process.argv[1]=' + JSON.stringify(CLAUDE_CLI) + ';' +
                        'process.argv[2]="--print";process.argv[3]="hello";process.argv.length=4;' +
                        'import(' + JSON.stringify(cliUrl2) + ')' +
                        '.then(function(){try{require("fs").appendFileSync(' + JSON.stringify(SETUP_LOG) + ',"[import-resolved]\\n");}catch(_){}})' +
                        '.catch(function(e){process.stderr.write("ERR:"+String(e)+"\\n");process.exit(1)});',
                    () => runEvalStep('[6] net: connect to proxy port ' + PROXY_PORT,
                        netTestCode,
                    () => { socket.write('\x1b[33mDone. Check !log for details.\x1b[0m\r\n'); }))))));
                    continue;
                }

                // ── Shell command: $ <command> ────────────────────────────────
                if (line.startsWith('$ ') || line === '$') {
                    const cmd = line.slice(2).trim();
                    if (!cmd) { continue; }

                    // Interrupt any running AI request
                    if (busy) {
                        try { if (current) current.kill('SIGTERM'); } catch(_) {}
                        if (currentTid) { clearTimeout(currentTid); currentTid = null; }
                        busy = false;
                    }

                    // cd is handled in-process — each child spawn has its own cwd
                    if (/^cd(\s|$)/.test(cmd)) {
                        const target = cmd.slice(2).trim() || FILES_DIR;
                        const resolved = path.resolve(shellCwd, target);
                        try {
                            const stat = fs.statSync(resolved);
                            if (stat.isDirectory()) {
                                shellCwd = resolved;
                                try { fs.writeFileSync(CWD_FILE, shellCwd, 'utf8'); } catch(_) {}
                                try { socket.write('\x1b[2m' + shellCwd + '\x1b[0m\r\n\r\n'); } catch(_) {}
                                try { socket.write('\x1b]9;cwd:' + shellCwd + '\x07'); } catch(_) {}
                            } else {
                                try { socket.write('\x1b[31mcd: not a directory: ' + target + '\x1b[0m\r\n\r\n'); } catch(_) {}
                            }
                        } catch(e) {
                            try { socket.write('\x1b[31mcd: ' + target + ': No such file or directory\x1b[0m\r\n\r\n'); } catch(_) {}
                        }
                        continue;
                    }

                    busy = true;
                    try { socket.write('\r\n'); } catch(_) {}
                    const shellEnv = buildEnv();
                    current = spawn('/system/bin/sh', ['-c', cmd], { env: shellEnv, cwd: shellCwd });
                    current.stdout.on('data', d => { try { socket.write(d); } catch(_) {} });
                    current.stderr.on('data', d => {
                        try { socket.write('\x1b[33m' + d.toString() + '\x1b[0m'); } catch(_) {}
                    });
                    current.on('error', e => {
                        try { socket.write('\x1b[31msh: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
                        busy = false; current = null;
                    });
                    current.on('close', code => {
                        if (code !== 0 && code !== null) {
                            try { socket.write('\x1b[2m[exit ' + code + ']\x1b[0m\r\n'); } catch(_) {}
                        }
                        try { socket.write('\r\n'); } catch(_) {}
                        busy = false; current = null;
                    });
                    continue;
                }

                if (busy) {
                    // Interrupt running request
                    try { if (current) current.kill('SIGTERM'); } catch (_) {}
                    if (currentTid) { clearTimeout(currentTid); currentTid = null; }
                    busy = false;
                }

                // ── Guard: abort early if provider is not configured ─────────
                {
                    const cfgCheck = readConfig();
                    if (cfgCheck.mode !== 'subscription' && !cfgCheck.providerUrl) {
                        try {
                            socket.write('\x1b]9;thinking-done\x07\r\n' +
                                '\x1b[31m✗ No provider configured.\x1b[0m\r\n' +
                                'Open \x1b[33mSettings\x1b[0m and enter your API key.\r\n');
                        } catch (_) {}
                        continue;
                    }
                }

                // ── Agentic mode: streaming tool-calling loop via proxy ───────
                if (agenticEnabled) {
                    busy = true;
                    let agMsg = contextBlock ? '[Context]\n' + contextBlock + '\n[Message]\n' + line : line;
                    contextBlock = '';
                    if (pendingAttachment) { agMsg = pendingAttachment + '\n\n' + agMsg; pendingAttachment = null; }
                    // Check for pending image attachment
                    let agPendingImage = null;
                    const imgB64File = path.join(FILES_DIR, 'pending_image.b64');
                    const imgMimeFile = path.join(FILES_DIR, 'pending_image.mime');
                    if (fs.existsSync(imgB64File)) {
                        try {
                            agPendingImage = {
                                b64: fs.readFileSync(imgB64File, 'utf8').trim(),
                                mime: fs.existsSync(imgMimeFile) ? fs.readFileSync(imgMimeFile, 'utf8').trim() : 'image/jpeg'
                            };
                            fs.unlinkSync(imgB64File);
                            if (fs.existsSync(imgMimeFile)) fs.unlinkSync(imgMimeFile);
                        } catch(_) {}
                    }
                    runAgentic(socket, agMsg, history.slice(), shellCwd, agPendingImage).then(async result => {
                        if (result.text) {
                            history.push({ role: 'user',      content: agMsg });
                            history.push({ role: 'assistant', content: result.text });
                            // Auto-compact if approaching MAX_HISTORY
                            history = await autoCompact(history, socket).catch(() => history);
                            if (history.length > MAX_HISTORY) history = history.slice(-MAX_HISTORY);
                            saveSession(history);
                            // Generate follow-up suggestions
                            if (history.length >= 2) {
                                generateSuggestions(socket, history).catch(() => {});
                            }
                        }
                        // Token count for context window indicator
                        if (result.text) sessionTokenEstimate += Math.round(result.text.length / 4);
                        try { socket.write('\x1b]9;tokens:' + sessionTokenEstimate + '\x07'); } catch(_) {}
                        // Propagate cwd changes from AI tool calls back to shell
                        if (result.cwd && result.cwd !== shellCwd) shellCwd = result.cwd;
                        busy = false; current = null;
                    }).catch(() => { busy = false; current = null; });
                    continue;
                }

                // ── Standard --print mode ─────────────────────────────────────
                // Signal terminal HTML to show animated thinking indicator
                try { socket.write('\x1b]9;thinking-start\x07'); } catch (_) {}

                // Track session stats
                sessionMsgCount++;
                sessionTokenEstimate += Math.round(line.length / 4);

                // Check for pending image attachment — images require the proxy API so
                // redirect to runAgentic for this one message even in --print mode.
                const imgB64FilePrint = path.join(FILES_DIR, 'pending_image.b64');
                const imgMimeFilePrint = path.join(FILES_DIR, 'pending_image.mime');
                if (fs.existsSync(imgB64FilePrint)) {
                    try {
                        const agImg = {
                            b64:  fs.readFileSync(imgB64FilePrint, 'utf8').trim(),
                            mime: fs.existsSync(imgMimeFilePrint)
                                ? fs.readFileSync(imgMimeFilePrint, 'utf8').trim()
                                : 'image/jpeg'
                        };
                        fs.unlinkSync(imgB64FilePrint);
                        if (fs.existsSync(imgMimeFilePrint)) fs.unlinkSync(imgMimeFilePrint);
                        busy = true;
                        let agMsg2 = contextBlock ? '[Context]\n' + contextBlock + '\n[Message]\n' + line : line;
                        contextBlock = '';
                        if (pendingAttachment) { agMsg2 = pendingAttachment + '\n\n' + agMsg2; pendingAttachment = null; }
                        runAgentic(socket, agMsg2, history.slice(), shellCwd, agImg).then(async result => {
                            if (result.text) {
                                history.push({ role: 'user',      content: agMsg2 });
                                history.push({ role: 'assistant', content: result.text });
                                history = await autoCompact(history, socket).catch(() => history);
                                if (history.length > MAX_HISTORY) history = history.slice(-MAX_HISTORY);
                                saveSession(history);
                            }
                            if (result.cwd && result.cwd !== shellCwd) shellCwd = result.cwd;
                            busy = false; current = null;
                        }).catch(() => { busy = false; current = null; });
                        continue;
                    } catch(_) {}
                }

                busy = true;
                let responseStarted = false;
                let responseBuf = '';     // capture stdout for history
                let printBase = (contextBlock ? '[Context]\n' + contextBlock + '\n[Message]\n' + line : line);
                contextBlock = '';
                if (pendingAttachment) { printBase = pendingAttachment + '\n\n' + printBase; pendingAttachment = null; }
                current = runMessage(printBase, socket, history);

                // Detect whether any output actually arrived (used for error diagnosis)
                current.stdout.once('data', () => { responseStarted = true; });
                current.stderr.once('data', () => { responseStarted = true; });

                // Buffer stdout so we can save the assistant reply to history
                current.stdout.on('data', d => { responseBuf += d.toString(); });

                // 60 s safety-net: kills the child and shows an actionable error if the
                // provider never responds.  The Android UI adds its own 15 s overlay.
                currentTid = setTimeout(() => {
                    if (!busy) return;
                    try { if (current) current.kill('SIGTERM'); } catch (_) {}
                    try {
                        socket.write(
                            '\x1b]9;thinking-done\x07' +
                            '\r\n\x1b[31m✗ Request timed out after 60 s.\x1b[0m\r\n' +
                            '\x1b[33mThe provider may be slow or down. Type your message again to retry.\x1b[0m\r\n'
                        );
                    } catch (_) {}
                    busy = false; current = null; currentTid = null;
                }, 60000);

                current.on('close', code => {
                    if (currentTid) { clearTimeout(currentTid); currentTid = null; }
                    // Always dismiss the thinking indicator regardless of outcome
                    try { socket.write('\x1b]9;thinking-done\x07'); } catch (_) {}
                    const stderr = current && current._stderrBuf ? current._stderrBuf() : '';
                    busy = false; current = null;

                    // Track response token estimate
                    sessionTokenEstimate += Math.round(responseBuf.length / 4);

                    // Save exchange to history only on a successful response
                    if (responseStarted && (code === 0 || code === null)) {
                        const reply = stripAnsi(responseBuf);
                        if (reply) {
                            history.push({ role: 'user',      content: line });
                            history.push({ role: 'assistant', content: reply });
                            // Auto-compact if approaching MAX_HISTORY
                            autoCompact(history, socket).then(compacted => {
                                history = compacted;
                                if (history.length > MAX_HISTORY) history = history.slice(-MAX_HISTORY);
                                saveSession(history);
                                if (history.length >= 2) {
                                    generateSuggestions(socket, history).catch(() => {});
                                }
                            }).catch(() => {
                                if (history.length > MAX_HISTORY) history = history.slice(-MAX_HISTORY);
                                saveSession(history);
                                if (history.length >= 2) {
                                    generateSuggestions(socket, history).catch(() => {});
                                }
                            });
                        }
                    }
                    responseBuf = '';
                    // Send token count for context window indicator
                    try { socket.write('\x1b]9;tokens:' + sessionTokenEstimate + '\x07'); } catch(_) {}

                    const rateLimited = (Date.now() - lastRateLimitMs) < 15000;
                    if (!responseStarted && rateLimited) {
                        lastRateLimitMs = 0;
                        try {
                            socket.write(
                                '\r\n\x1b[33m⚠ Rate limited by provider (HTTP 429).\x1b[0m\r\n' +
                                '\x1b[2mModel is busy. Wait ~30 s or switch models in Settings.\x1b[0m\r\n'
                            );
                        } catch (_) {}
                    } else if (code !== 0 && code !== null && !responseStarted) {
                        // Log the full stderr so it ends up in setup.log
                        if (stderr) log('[stderr] ' + stderr.trim() + '\n');
                        log('[exit] code=' + code + ' responseStarted=false\n');

                        // Build a human-readable error with as much detail as possible
                        let hint = 'Check your API key in Settings, then try again.';
                        const s = stderr.toLowerCase();
                        if (s.includes('invalid') && (s.includes('key') || s.includes('auth')))
                            hint = 'Your API key appears invalid. Update it in Settings.';
                        else if (s.includes('401') || s.includes('unauthorized'))
                            hint = 'Authentication failed (401). Check your API key in Settings.';
                        else if (s.includes('429') || s.includes('rate limit'))
                            hint = 'Rate limited (429). Wait a moment, then try again.';
                        else if (s.includes('model') && (s.includes('not found') || s.includes('unknown')))
                            hint = 'Model not found. Change the model in Settings.';
                        else if (s.includes('econnrefused') || s.includes('enotfound'))
                            hint = 'Cannot reach provider. Check your internet connection.';

                        const detail = stderr.trim()
                            ? '\r\n\x1b[2m' + stderr.trim().split('\n').slice(-3).join(' | ') + '\x1b[0m'
                            : '';

                        try {
                            socket.write(
                                '\r\n\x1b[31m✗ Claude Code exited (code ' + code + ').\x1b[0m\r\n' +
                                '\x1b[33m' + hint + '\x1b[0m' +
                                detail + '\r\n'
                            );
                        } catch (_) {}
                    }
                    try { socket.write('\r\n'); } catch (_) {}
                });
            }
        }; // end normalDataHandler

        socket.on('data', normalDataHandler);

        socket.on('close', () => {
            if (currentTid) { clearTimeout(currentTid); currentTid = null; }
            try { if (current) current.kill('SIGTERM'); } catch (_) {}
        });
        socket.on('error', () => {
            if (currentTid) { clearTimeout(currentTid); currentTid = null; }
            try { if (current) current.kill('SIGTERM'); } catch (_) {}
        });
    });

    server.on('error', err => {
        process.stderr.write('TCP bridge error: ' + err.message + '\n');
        setTimeout(openTcpBridge, 3000);
    });

    server.listen(PORT, HOST, () => {
        log('Bridge ready on ' + HOST + ':' + PORT + '\n');
    });
}

// ─── Follow-up suggestions ────────────────────────────────────────────────────
// After a successful response, generate 3 follow-up question chips for the user.

async function generateSuggestions(socket, history) {
    const cfg = readConfig();
    if (!cfg.providerUrl && cfg.mode !== 'subscription') return;
    const last2 = history.slice(-4).map(h => (h.role === 'user' ? 'User' : 'AI') + ': ' + (h.content || '').slice(0, 200)).join('\n');
    const body = JSON.stringify({
        model: 'claude-3-5-sonnet-20241022',
        max_tokens: 150,
        messages: [{ role: 'user', content: 'Given this conversation:\n' + last2 + '\n\nGenerate 3 short follow-up questions the USER might want to ask next (max 60 chars each). Make them specific, actionable, and natural. Reply with ONLY a JSON array: ["q1","q2","q3"]' }]
    });
    return new Promise((resolve) => {
        const apiKey = cfg.mode === 'subscription' ? (cfg.apiKey || 'sk-ant-key') : 'sk-ant-proxy000';
        const req = http.request({
            hostname: HOST, port: PROXY_PORT, path: '/v1/messages', method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'x-api-key': apiKey,
                'anthropic-version': '2023-06-01',
                'Content-Length': Buffer.byteLength(body)
            }
        }, res => {
            let data = '';
            res.on('data', c => { data += c; });
            res.on('end', () => {
                try {
                    const resp = JSON.parse(data);
                    const text = (resp.content || []).find(b => b.type === 'text') && resp.content.find(b => b.type === 'text').text || '';
                    const match = text.match(/\[[\s\S]*?\]/);
                    if (match) {
                        const suggestions = JSON.parse(match[0]);
                        if (Array.isArray(suggestions) && suggestions.length > 0) {
                            try { socket.write('\x1b]9;suggestions:' + JSON.stringify(suggestions) + '\x07'); } catch(_) {}
                        }
                    }
                } catch(_) {}
                resolve();
            });
        });
        req.setTimeout(8000, () => { req.destroy(); resolve(); });
        req.on('error', () => resolve());
        req.write(body);
        req.end();
    });
}

// ─── Package installer ────────────────────────────────────────────────────────
// Downloads static ARM64 binaries or npm installs packages, writing to BIN_DIR.

function installPackage(name, socket) {
    const pkg = PACKAGE_CATALOG[name];
    if (!pkg) {
        const names = Object.keys(PACKAGE_CATALOG).join(', ');
        try { socket.write('\x1b[31m✗ Unknown package: ' + name + '\x1b[0m\r\n' +
            '\x1b[2mAvailable: ' + names + '\x1b[0m\r\n\r\n'); } catch(_) {}
        return;
    }

    const binPath = path.join(BIN_DIR, pkg.bin || name);

    if (pkg.type === 'binary') {
        if (fs.existsSync(binPath)) {
            try { socket.write('\x1b[32m✓ ' + name + ' already installed.\x1b[0m Run \x1b[33m$ ' + (pkg.bin || name) + ' --help\x1b[0m to verify.\r\n\r\n'); } catch(_) {}
            return;
        }
        try { fs.mkdirSync(BIN_DIR, { recursive: true }); } catch(_) {}
        try { socket.write('\x1b[33mDownloading ' + name + ' (' + (pkg.size || '?') + ')…\x1b[0m\r\n'); } catch(_) {}
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
                    try { socket.write('\x1b[32m✓ busybox installed.\x1b[0m ' + linked + ' symlinks created.\r\n' +
                        '\x1b[2mNow available: wget, tar, gzip, grep, sed, awk, find, nano, and more.\x1b[0m\r\n\r\n'); } catch(_) {}
                    log('[install] busybox: ' + linked + ' symlinks created\n');
                });
            } else {
                try { socket.write('\x1b[32m✓ ' + name + ' installed.\x1b[0m Run \x1b[33m$ ' + (pkg.bin || name) + ' --help\x1b[0m to verify.\r\n\r\n'); } catch(_) {}
                log('[install] ' + name + ' installed at ' + binPath + '\n');
            }
        }).catch(e => {
            try { fs.unlinkSync(tmpPath); } catch(_) {}
            try { socket.write('\x1b[31m✗ Download failed: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
            log('[install] ' + name + ' download failed: ' + e.message + '\n');
        });

    } else if (pkg.type === 'archive' || pkg.type === 'archive-xz') {
        // ── Generic archive installer (tar.gz or tar.xz) ─────────────────────
        const isXz   = pkg.type === 'archive-xz';
        const distDir = path.join(FILES_DIR, 'opt', name + '-dist');
        const primaryBin = path.join(BIN_DIR, name === 'python3' ? 'python3' : name);
        if (fs.existsSync(primaryBin)) {
            try { socket.write('\x1b[32m✓ ' + name + ' already installed.\x1b[0m Run \x1b[33m$ ' + name + ' --version\x1b[0m\r\n\r\n'); } catch(_) {}
            return;
        }
        const ext  = isXz ? '.tar.xz' : '.tar.gz';
        const archivePath = path.join(FILES_DIR, name + ext);
        const tarPath     = path.join(FILES_DIR, name + '.tar');

        if (isXz && !fs.existsSync(path.join(BIN_DIR, 'busybox'))) {
            try { socket.write('\x1b[31m✗ ' + name + ' requires busybox for .tar.xz.\x1b[0m Run \x1b[33m!install busybox\x1b[0m first.\r\n\r\n'); } catch(_) {}
            return;
        }

        (async () => {
            // Resolve URL dynamically
            let url;
            if (name === 'python3')     { try { socket.write('\x1b[33mResolving latest Python ARM64…\x1b[0m\r\n'); } catch(_) {} url = await resolveLatestPythonUrl(); }
            else if (name === 'go')     { try { socket.write('\x1b[33mResolving latest Go ARM64…\x1b[0m\r\n'); } catch(_) {} url = await resolveLatestGoUrl(); }
            else if (name === 'zig')    { try { socket.write('\x1b[33mResolving latest Zig ARM64…\x1b[0m\r\n'); } catch(_) {} url = await resolveLatestZigUrl(); }
            else if (pkg.url)           { url = pkg.url; }
            else throw new Error('No URL resolver for ' + name);

            try { socket.write('\x1b[33mDownloading ' + name + ' (' + (pkg.size || '') + ')…\x1b[0m\r\n'); } catch(_) {}
            log('[install] ' + name + ' url: ' + url + '\n');
            await downloadFile(url, archivePath);
            fs.mkdirSync(distDir, { recursive: true });

            if (isXz) {
                // busybox tar -Jxf handles xz natively
                try { socket.write('\x1b[33mExtracting ' + name + '… (this may take a minute)\x1b[0m\r\n'); } catch(_) {}
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
                try { socket.write('\x1b[33mDecompressing…\x1b[0m\r\n'); } catch(_) {}
                await new Promise((res, rej) => {
                    const zlib = require('zlib');
                    fs.createReadStream(archivePath).pipe(zlib.createGunzip())
                        .pipe(fs.createWriteStream(tarPath)).on('finish', res).on('error', rej);
                });
                try { fs.unlinkSync(archivePath); } catch(_) {}
                try { socket.write('\x1b[33mExtracting ' + name + '… (this may take a minute)\x1b[0m\r\n'); } catch(_) {}
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
                try { socket.write('\x1b[32m✓ python3 installed.\x1b[0m Run \x1b[33m$ python3 --version\x1b[0m\r\n' +
                    '\x1b[2mCommands: python3, python' + (pip3 ? ', pip3, pip' : '') + '\x1b[0m\r\n\r\n'); } catch(_) {}
            } else if (pkg.post === 'go') {
                const goRoot = path.join(distDir, 'go');
                const goPath = path.join(FILES_DIR, 'go-workspace');
                fs.mkdirSync(goPath, { recursive: true });
                mkWrap('go',    path.join(goRoot, 'bin', 'go'),    { GOROOT: goRoot, GOPATH: goPath, HOME: FILES_DIR });
                mkWrap('gofmt', path.join(goRoot, 'bin', 'gofmt'), { GOROOT: goRoot, GOPATH: goPath, HOME: FILES_DIR });
                try { socket.write('\x1b[32m✓ go installed.\x1b[0m Run \x1b[33m$ go version\x1b[0m\r\n' +
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
                try { socket.write('\x1b[32m✓ zig installed.\x1b[0m Run \x1b[33m$ zig version\x1b[0m\r\n' +
                    '\x1b[2mCommands: zig, zig-cc (C), zig-c++ (C++)\x1b[0m\r\n\r\n'); } catch(_) {}
            }
            log('[install] ' + name + ' installed at ' + distDir + '\n');
        })().catch(e => {
            try { fs.unlinkSync(archivePath); } catch(_) {}
            try { fs.unlinkSync(tarPath); } catch(_) {}
            try { socket.write('\x1b[31m✗ ' + name + ' install failed: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
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
            try { socket.write('\x1b[32m✓ ' + name + ' already installed.\x1b[0m\r\n\r\n'); } catch(_) {}
            return;
        }
        const bb = path.join(BIN_DIR, 'busybox');
        if (!fs.existsSync(bb)) {
            try { socket.write('\x1b[31m✗ busybox required first.\x1b[0m Run \x1b[33m!install busybox\x1b[0m then retry.\r\n\r\n'); } catch(_) {}
            return;
        }
        const bbEnv  = { PATH: BIN_DIR + ':/system/bin:/system/xbin', TMPDIR: FILES_DIR };
        const destDir = path.join(FILES_DIR, pkg.dest);

        (async () => {
            try { socket.write('\x1b[33mFetching Termux package index…\x1b[0m\r\n'); } catch(_) {}
            const pkgs = await resolveTermuxUrls(pkg.packages);

            for (const { name: pkgName, url } of pkgs) {
                try { socket.write('\x1b[33mDownloading ' + pkgName + '…\x1b[0m\r\n'); } catch(_) {}
                const debPath = path.join(FILES_DIR, pkgName + '.deb');
                await downloadFile(url, debPath);
                try { socket.write('\x1b[33mExtracting ' + pkgName + '…\x1b[0m\r\n'); } catch(_) {}
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
                try { socket.write('\x1b[32m✓ git installed.\x1b[0m Run \x1b[33m$ git --version\x1b[0m\r\n' +
                    '\x1b[2mFor HTTPS push/pull: !gh-auth <token>\x1b[0m\r\n\r\n'); } catch(_) {}
            } else if (pkg.post === 'ssh-termux') {
                const sshBinDir = path.join(destDir, 'bin');
                for (const b of ['ssh', 'scp', 'sftp', 'ssh-keygen', 'ssh-add', 'ssh-agent']) {
                    const real = path.join(sshBinDir, b);
                    if (fs.existsSync(real)) mkWrap(b, real);
                }
                try { fs.mkdirSync(path.join(FILES_DIR, '.ssh'), { mode: 0o700 }); } catch(_) {}
                try { socket.write('\x1b[32m✓ ssh installed.\x1b[0m Run \x1b[33m$ ssh -V\x1b[0m\r\n' +
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
                try { socket.write('\x1b[32m✓ ruby installed.\x1b[0m Run \x1b[33m$ ruby --version\x1b[0m\r\n' +
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
                try { socket.write('\x1b[32m✓ clang installed.\x1b[0m Run \x1b[33m$ clang --version\x1b[0m\r\n' +
                    '\x1b[2mCommands: clang, clang++, cc, c++\x1b[0m\r\n\r\n'); } catch(_) {}
            } else {
                // Generic: wrap all binaries found in bin/
                const binDir2 = path.join(destDir, 'bin');
                if (fs.existsSync(binDir2))
                    for (const b of fs.readdirSync(binDir2)) { try { mkWrap(b, path.join(binDir2, b)); } catch(_) {} }
                try { socket.write('\x1b[32m✓ ' + name + ' installed.\x1b[0m\r\n\r\n'); } catch(_) {}
            }
            log('[install] ' + name + ' installed at ' + destDir + '\n');
        })().catch(e => {
            try { socket.write('\x1b[31m✗ ' + name + ' install failed: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {}
            log('[install] ' + name + ' failed: ' + e.message + '\n');
        });

    } else if (pkg.type === 'npm') {
        try { socket.write('\x1b[33mInstalling ' + name + ' via npm…\x1b[0m\r\n'); } catch(_) {}
        const npmCmd = 'npm install --prefix ' + JSON.stringify(NPM_PREFIX) + ' ' + pkg.pkg;
        const child = spawn('/system/bin/sh', ['-c', npmCmd], { env: buildEnv(), cwd: FILES_DIR });
        child.stdout.on('data', d => { try { socket.write(d); } catch(_) {} });
        child.stderr.on('data', d => { try { socket.write('\x1b[2m' + d.toString() + '\x1b[0m'); } catch(_) {} });
        child.on('close', code => {
            if (code !== 0) {
                try { socket.write('\x1b[31m✗ npm install failed (exit ' + code + ')\x1b[0m\r\n\r\n'); } catch(_) {}
            } else {
                try { socket.write('\x1b[32m✓ ' + name + ' installed.\x1b[0m\r\n\r\n'); } catch(_) {}
                log('[install] npm package ' + pkg.pkg + ' installed\n');
            }
        });
    }
}

// ─── Auto-compact (context summarization) ────────────────────────────────────
// When history nears MAX_HISTORY, summarize the oldest half via the proxy and
// replace it with a single compact entry so context never silently degrades.

async function autoCompact(history, socket) {
    const COMPACT_THRESHOLD = MAX_HISTORY - 4;
    if (history.length < COMPACT_THRESHOLD) return history;

    const cfg = readConfig();
    if (!cfg.providerUrl && cfg.mode !== 'subscription') return history;

    const keepTail = history.slice(-6);
    const toSummarize = history.slice(0, -6);
    if (toSummarize.length < 4) return history;

    try { socket.write('\x1b[2m[compacting context…]\x1b[0m\r\n'); } catch(_) {}
    log('[compact] summarizing ' + toSummarize.length + ' messages\n');

    const transcript = toSummarize.map(h =>
        (h.role === 'user' ? 'User' : 'AI') + ': ' + (h.content || '').slice(0, 800)
    ).join('\n\n');

    const body = JSON.stringify({
        model: 'claude-3-5-sonnet-20241022',
        max_tokens: 600,
        messages: [{ role: 'user', content:
            'Summarize this conversation in concise bullet points. Preserve: key decisions, file paths mentioned, code written, problems solved, and any context still relevant:\n\n' +
            transcript + '\n\nReply with just the summary bullets, no preamble.'
        }]
    });

    return new Promise(resolve => {
        const apiKey = cfg.mode === 'subscription' ? (cfg.apiKey || 'sk-ant-key') : 'sk-ant-proxy000';
        const req = http.request({
            hostname: HOST, port: PROXY_PORT, path: '/v1/messages', method: 'POST',
            headers: { 'Content-Type': 'application/json', 'x-api-key': apiKey,
                       'anthropic-version': '2023-06-01', 'Content-Length': Buffer.byteLength(body) }
        }, res => {
            let data = '';
            res.on('data', c => { data += c; });
            res.on('end', () => {
                try {
                    const resp = JSON.parse(data);
                    const text = ((resp.content || []).find(b => b.type === 'text') || {}).text || '';
                    if (text.trim()) {
                        const summaryEntry = { role: 'assistant', content: '[Previous conversation summary]\n' + text.trim() };
                        const compacted = [summaryEntry, ...keepTail];
                        log('[compact] reduced ' + history.length + ' → ' + compacted.length + ' messages\n');
                        try { socket.write('\x1b[2m[context compacted: ' + history.length + ' → ' + compacted.length + ' messages]\x1b[0m\r\n'); } catch(_) {}
                        resolve(compacted);
                    } else {
                        resolve(history);
                    }
                } catch(_) { resolve(history); }
            });
        });
        req.setTimeout(12000, () => { req.destroy(); resolve(history); });
        req.on('error', () => resolve(history));
        req.write(body); req.end();
    });
}

// ─── PTY Phase 2: persistent claude session ───────────────────────────────────

// Build the bootstrap eval string for interactive mode.
// No --print flag, no message in argv — stdin stays open so the user can
// send multiple turns to one persistent process.
function buildInteractiveEvalCode() {
    const cliUrl  = 'file://' + CLAUDE_CLI;
    const exitLog = JSON.stringify(path.join(FILES_DIR, 'session_exit.log'));
    return (
        'process.on("exit",function(c){' +
        'try{require("fs").appendFileSync(' + exitLog + ',"[exit] "+c+"\\n");}catch(_){}}); ' +
        'process.on("unhandledRejection",function(r){' +
        'try{require("fs").appendFileSync(' + exitLog + ',' +
        '"[unhandledRejection] "+String(r&&(r.stack||r.message)||r).slice(0,400)+"\\n");}catch(_){}});' +
        regexpShim +
        intlShim +
        'process.argv[1]=' + JSON.stringify(CLAUDE_CLI) + ';' +
        'process.argv[2]="--output-format";' +
        'process.argv[3]="stream-json";' +
        'process.argv.length=4;' +
        'import(' + JSON.stringify(cliUrl) + ')' +
        '.catch(function(e){process.stderr.write("import-err:"+String(e)+"\\n");process.exit(1);});'
    );
}

// One persistent claude process per TCP connection.
// Claude manages its own history; bridge.js routes !commands locally and
// forwards everything else (messages, /slash commands) to claude stdin.
function openPersistentSession() {
    const server = net.createServer(socket => {
        if (!isClaudeInstalled()) {
            try { socket.write('\r\n\x1b[31mClaude Code not installed — run Setup from the app.\x1b[0m\r\n'); socket.end(); } catch(_) {}
            return;
        }

        const cfg  = readConfig();
        const env  = buildEnv();
        const cwd  = cfg.projectPath || FILES_DIR;
        const cols = String(cfg.ptyCols || 220);
        const rows = String(cfg.ptyRows || 50);

        // Claude Code exits immediately if stdin is not a real TTY, even in
        // interactive mode with --output-format stream-json.  We must use
        // PTY_HELPER so claude sees a proper terminal on stdin.
        // Any prompt chars (">" etc.) that slip into stdout are stripped by the
        // NDJSON parser (raw.indexOf('{') below) before JSON.parse.
        const evalCode = buildInteractiveEvalCode();
        let proc;
        try {
            proc = fs.existsSync(PTY_HELPER)
                ? spawn(PTY_HELPER, [cols, rows, LAUNCHER, '-e', evalCode], { env, cwd })
                : spawn(LAUNCHER, ['-e', evalCode], { env, cwd });
        } catch(e) {
            try { socket.write('\x1b[31m[PTY] Failed to start claude: ' + e.message + '\x1b[0m\r\n'); socket.end(); } catch(_) {}
            return;
        }

        // Per-connection state
        let busy          = false;
        let thinkingDone  = false;
        let stdoutBuf     = '';
        let currentTid    = null;
        let inputBuf      = '';
        let contextBlock  = '';
        let pendingAttach = null;
        let sessionTokens = 0;

        // Show spinner while claude boots; send agentic state and cwd
        const agenticOn = fs.existsSync(AGENTIC_FILE);
        try { socket.write('\x1b]9;agentic:' + (agenticOn ? 'on' : 'off') + '\x07'); } catch(_) {}
        try { socket.write('\x1b]9;cwd:' + cwd + '\x07'); } catch(_) {}
        try { socket.write('\x1b]9;thinking-start\x07'); } catch(_) {}

        // ── NDJSON event parser ───────────────────────────────────────────────
        proc.stdout.on('data', chunk => {
            stdoutBuf += chunk.toString();
            const lines = stdoutBuf.split('\n');
            stdoutBuf = lines.pop();

            for (const raw of lines) {
                if (!raw.trim()) continue;
                // Strip any prompt/decoration chars that may precede the JSON object
                const jsonStart = raw.indexOf('{');
                if (jsonStart < 0) continue;
                let ev;
                try { ev = JSON.parse(raw.slice(jsonStart)); } catch(_) { continue; }

                // system/init fires once at startup — claude is ready
                if (ev.type === 'system' && ev.subtype === 'init') {
                    try { socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
                    try { socket.write('\x1b[2m[PTY] Persistent session ready\x1b[0m\r\n'); } catch(_) {}
                    continue;
                }

                // Signal thinking-done on first event of each turn
                if (busy && !thinkingDone) {
                    thinkingDone = true;
                    try { socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
                }

                if (ev.type === 'assistant') {
                    for (const block of (ev.message && ev.message.content) || []) {
                        if (block.type === 'text' && block.text) {
                            try { socket.write(block.text); } catch(_) {}
                        } else if (block.type === 'thinking' && block.thinking) {
                            const enc = Buffer.from(block.thinking.slice(0, 3000)).toString('base64');
                            try { socket.write('\x1b]9;think-block:' + enc + '\x07'); } catch(_) {}
                        } else if (block.type === 'tool_use') {
                            const preview = block.input ? JSON.stringify(block.input).slice(0, 120) : '';
                            try { socket.write('\x1b[36m▶ ' + (block.name || 'tool') + '\x1b[0m ' + preview + '\r\n'); } catch(_) {}
                        }
                    }
                }

                if (ev.type === 'result') {
                    if (currentTid) { clearTimeout(currentTid); currentTid = null; }
                    busy         = false;
                    thinkingDone = false;
                    const toks   = (ev.usage && ev.usage.output_tokens) || 0;
                    sessionTokens += toks;
                    try { socket.write('\x1b]9;tokens:' + sessionTokens + '\x07'); } catch(_) {}
                }
            }
        });

        proc.stderr.on('data', d => {
            const s = d.toString();
            if (/^\[(eval-ok|import-resolved|exit-event|unhandledRejection|regex-compat|intl-shim)\]/.test(s.trim())) return;
            try { socket.write('\x1b[33m' + s + '\x1b[0m'); } catch(_) {}
        });

        proc.on('error', e => {
            try { socket.write('\x1b[31m[PTY] Process error: ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
        });

        proc.on('close', code => {
            if (currentTid) { clearTimeout(currentTid); currentTid = null; }
            try {
                socket.write('\r\n\x1b[33m[PTY] Session ended (exit ' + (code || 0) + ') — tap Restart to reconnect.\x1b[0m\r\n');
                socket.end();
            } catch(_) {}
        });

        // ── Input handler ─────────────────────────────────────────────────────
        const persistentDataHandler = d => {
            // In-band resize: ESC 0xFE → ESC 0xFF for pty_helper
            if (d.length >= 6 && d[0] === 0x1b && d[1] === 0xfe) {
                const resize = Buffer.from([0x1b, 0xff, d[2], d[3], d[4], d[5]]);
                try { proc.stdin.write(resize); } catch(_) {}
                return;
            }

            const raw = d.toString();

            // Ctrl+C: interrupt
            if (raw.includes('\x03')) {
                if (busy) {
                    try { proc.stdin.write('\x03'); } catch(_) {}
                    try { socket.write('\r\n\x1b[33m^C — interrupted\x1b[0m\r\n'); } catch(_) {}
                    if (currentTid) { clearTimeout(currentTid); currentTid = null; }
                    busy = false;
                }
                inputBuf = '';
                return;
            }

            inputBuf += raw;
            let nl;
            while ((nl = inputBuf.search(/[\r\n]/)) !== -1) {
                const line = inputBuf.slice(0, nl).replace(/[\x00-\x08\x0b-\x1f\x7f]/g, '').trim();
                inputBuf   = inputBuf.slice(nl + 1);
                if (!line) continue;

                if (busy) {
                    try { socket.write('\x1b[33m[busy — please wait]\x1b[0m\r\n'); } catch(_) {}
                    continue;
                }

                // ── bridge-local !commands ────────────────────────────────────
                if (line === '!help') {
                    try { socket.write(
                        '\x1b[1m[PTY — persistent session]\x1b[0m\r\n' +
                        '  \x1b[33m!clear\x1b[0m           Clear claude history\r\n' +
                        '  \x1b[33m!context [path]\x1b[0m  Load file/dir as context\r\n' +
                        '  \x1b[33m!attach <file>\x1b[0m   Attach file to next message\r\n' +
                        '  \x1b[33m!pty <cmd>\x1b[0m       Run interactive program (python3, bash…)\r\n' +
                        '  \x1b[33m!agentic\x1b[0m         Toggle agentic mode\r\n' +
                        '  \x1b[33m!log\x1b[0m             Show bridge log\r\n' +
                        '  /cost  /compact  /doctor  /clear — forwarded to claude directly\r\n' +
                        '  \x1b[2m$ <cmd>  — run shell command\x1b[0m\r\n\r\n'
                    ); } catch(_) {}
                    continue;
                }

                if (line === '!clear') {
                    contextBlock  = '';
                    pendingAttach = null;
                    sessionTokens = 0;
                    try { socket.write('\x1b]9;tokens:0\x07'); } catch(_) {}
                    try { proc.stdin.write('/clear\n'); } catch(_) {}
                    try { socket.write('\x1b[33m[history cleared]\x1b[0m\r\n'); } catch(_) {}
                    continue;
                }

                if (line.startsWith('!log')) {
                    const n = parseInt(line.slice(4).trim()) || 40;
                    try {
                        const logData = fs.readFileSync(SETUP_LOG, 'utf8');
                        socket.write('\x1b[2m' + logData.split('\n').slice(-n).join('\r\n') + '\x1b[0m\r\n');
                    } catch(_) { try { socket.write('[no log]\r\n'); } catch(_) {} }
                    continue;
                }

                if (line.startsWith('!agentic')) {
                    const arg = line.slice(8).trim();
                    const on  = arg === 'on' ? true : arg === 'off' ? false : !fs.existsSync(AGENTIC_FILE);
                    if (on) { try { fs.writeFileSync(AGENTIC_FILE, '1'); } catch(_) {} }
                    else   { try { fs.unlinkSync(AGENTIC_FILE); } catch(_) {} }
                    try { socket.write('\x1b]9;agentic:' + (on ? 'on' : 'off') + '\x07'); } catch(_) {}
                    try { socket.write((on ? '\x1b[35m[AGENTIC ON]\x1b[0m' : '\x1b[2m[AGENTIC OFF]\x1b[0m') + '\r\n'); } catch(_) {}
                    continue;
                }

                if (line.startsWith('!context')) {
                    const p = line.slice(8).trim() || (cfg.projectPath || FILES_DIR);
                    try {
                        const stat = fs.statSync(p);
                        if (stat.isDirectory()) {
                            const tree = fs.readdirSync(p).slice(0, 80).join('\n');
                            contextBlock = '[Directory: ' + p + ']\n' + tree;
                        } else {
                            contextBlock = '[File: ' + p + ']\n' + fs.readFileSync(p, 'utf8').slice(0, 30000);
                        }
                        try { socket.write('\x1b[33m[context loaded: ' + p + ']\x1b[0m\r\n'); } catch(_) {}
                    } catch(e) { try { socket.write('\x1b[31m[!context: ' + e.message + ']\x1b[0m\r\n'); } catch(_) {} }
                    continue;
                }

                if (line.startsWith('!attach')) {
                    const p = line.slice(7).trim();
                    try {
                        pendingAttach = '[Attached: ' + p + ']\n' + fs.readFileSync(p, 'utf8').slice(0, 30000);
                        try { socket.write('\x1b[33m[attached: ' + p + ']\x1b[0m\r\n'); } catch(_) {}
                    } catch(e) { try { socket.write('\x1b[31m[!attach: ' + e.message + ']\x1b[0m\r\n'); } catch(_) {} }
                    continue;
                }

                // !pty <cmd> — relay socket to an interactive PTY subprocess temporarily
                if (line.startsWith('!pty ') || line === '!pty') {
                    const ptyCmd = line.slice(5).trim();
                    if (!ptyCmd) {
                        try { socket.write('\x1b[33mUsage: !pty <command>\x1b[0m\r\n\r\n'); } catch(_) {}
                        continue;
                    }
                    if (!fs.existsSync(PTY_HELPER)) {
                        try { socket.write('\x1b[31m✗ libpty-helper.so not found — rebuild app.\x1b[0m\r\n'); } catch(_) {}
                        continue;
                    }
                    const ptyCmdParts = ptyCmd.split(/\s+/);
                    const ptyCfg2     = readConfig();
                    const ptyEnv2     = Object.assign({}, buildEnv(), { TERM: 'xterm-256color' });
                    let ptyProc2;
                    try {
                        ptyProc2 = spawn(PTY_HELPER,
                            [String(ptyCfg2.ptyCols || 220), String(ptyCfg2.ptyRows || 50), ...ptyCmdParts],
                            { env: ptyEnv2, cwd: cwd });
                    } catch(e) {
                        try { socket.write('\x1b[31m[PTY] Failed: ' + e.message + '\x1b[0m\r\n'); } catch(_) {}
                        continue;
                    }
                    try { socket.write('\x1b[33m[PTY] ' + ptyCmd + ' — Ctrl+D or exit to return\x1b[0m\r\n\r\n'); } catch(_) {}
                    socket.removeListener('data', persistentDataHandler);
                    const relay2 = d2 => { try { ptyProc2.stdin.write(d2); } catch(_) {} };
                    socket.on('data', relay2);
                    ptyProc2.stdout.on('data', d2 => { try { socket.write(d2); } catch(_) {} });
                    ptyProc2.stderr.on('data', d2 => { try { socket.write(d2); } catch(_) {} });
                    const restoreHandler = () => {
                        socket.removeListener('data', relay2);
                        socket.on('data', persistentDataHandler);
                    };
                    ptyProc2.on('error', e => { restoreHandler(); try { socket.write('\x1b[31m[PTY] Error: ' + e.message + '\x1b[0m\r\n\r\n'); } catch(_) {} });
                    ptyProc2.on('close', code => { restoreHandler(); try { socket.write('\r\n\x1b[33m[PTY] Session ended (exit ' + (code || 0) + ')\x1b[0m\r\n\r\n'); } catch(_) {} });
                    continue;
                }

                // ── Shell commands: $ cmd ─────────────────────────────────────
                if (line.startsWith('$ ')) {
                    const cmd = line.slice(2).trim();
                    if (cmd.startsWith('cd ')) {
                        const newDir = cmd.slice(3).trim();
                        try {
                            process.chdir(newDir);
                            try { socket.write('\x1b[2m[cwd: ' + newDir + ']\x1b[0m\r\n'); } catch(_) {}
                        } catch(e) {
                            try { socket.write('\x1b[31m[cd: ' + e.message + ']\x1b[0m\r\n'); } catch(_) {}
                        }
                    } else {
                        busy = true;
                        try { socket.write('\x1b]9;thinking-start\x07'); } catch(_) {}
                        const sh = spawn('/system/bin/sh', ['-c', cmd], { env: buildEnv(), cwd: cwd });
                        sh.stdout.on('data', d => { try { socket.write(d); } catch(_) {} });
                        sh.stderr.on('data', d => { try { socket.write(d); } catch(_) {} });
                        sh.on('close', () => {
                            try { socket.write('\x1b]9;thinking-done\x07'); } catch(_) {}
                            busy         = false;
                            thinkingDone = false;
                        });
                    }
                    continue;
                }

                // ── Forward everything else to claude stdin ────────────────────
                let msg = line;
                if (contextBlock)  { msg = contextBlock  + '\n\n' + msg; contextBlock  = ''; }
                if (pendingAttach) { msg = pendingAttach + '\n\n' + msg; pendingAttach = null; }

                busy         = true;
                thinkingDone = false;
                try { socket.write('\x1b]9;thinking-start\x07'); } catch(_) {}
                try { proc.stdin.write(msg + '\n'); } catch(_) {}

                currentTid = setTimeout(() => {
                    try { proc.stdin.write('\x03'); } catch(_) {}
                    try { socket.write('\x1b]9;thinking-done\x07\r\n\x1b[31m✗ Timed out (60 s).\x1b[0m\r\n'); } catch(_) {}
                    busy = false; thinkingDone = false; currentTid = null;
                }, 60000);
            }
        };

        socket.on('data', persistentDataHandler);
        socket.on('close', () => { try { proc.kill('SIGHUP'); } catch(_) {} });
        socket.on('error', () => { try { proc.kill('SIGHUP'); } catch(_) {} });
    });

    server.on('error', err => {
        process.stderr.write('PTY bridge error: ' + err.message + '\n');
        setTimeout(openPersistentSession, 3000);
    });

    server.listen(PORT, HOST, () => {
        log('PTY Bridge ready on ' + HOST + ':' + PORT + '\n');
    });
}

// Write FILES_DIR/bin/claude and FILES_DIR/bin/node wrappers so that
// sub-agents spawned by claude (via the Task tool) can find and run claude.
// The claude wrapper injects the regexp/intl shims the same way runMessage() does.
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
        const cfg = readConfig();
        if (cfg.ptyMode) {
            openPersistentSession();
        } else {
            openTcpBridge();
        }
        // Load stdio MCP servers after the bridge is up (non-blocking)
        loadMcpStdioServers().catch(e => log('[mcp-stdio] startup error: ' + e.message + '\n'));
    });
}

// ─── Entry point ──────────────────────────────────────────────────────────────

// Ensure custom slash commands directory exists (HOME=FILES_DIR so claude
// reads commands from FILES_DIR/.claude/commands/*.md on every run).
try { fs.mkdirSync(path.join(FILES_DIR, '.claude', 'commands'), { recursive: true }); } catch(_) {}

if (isClaudeInstalled()) {
    log('Claude Code already installed — starting bridge server.\n');
    try { fs.writeFileSync(SETUP_DONE, 'true'); } catch (_) {}
    writeSubagentWrappers();
    startBridgeServer();
} else {
    installLoop();
}
