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
function executeTool(name, input, cwd) {
    return new Promise(resolve => {
        const env = buildEnv();
        if (name === 'bash') {
            const cmd = input.command || '';
            const workDir = input.cwd ? path.resolve(cwd, input.cwd) : cwd;
            let out = '', err = '';
            let child;
            // Wrap command: print pwd after execution so we can track cwd changes.
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
                // Extract trailing pwd line to detect cd
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
        } else if (name === 'read_file') {
            try {
                const fp = path.resolve(cwd, input.path);
                const content = fs.readFileSync(fp, 'utf8');
                resolve({ content: content.slice(0, 50000), isError: false, newCwd: cwd });
            } catch(e) { resolve({ content: 'Error: ' + e.message, isError: true, newCwd: cwd }); }
        } else if (name === 'write_file') {
            try {
                const fp = path.resolve(cwd, input.path);
                fs.mkdirSync(path.dirname(fp), { recursive: true });
                fs.writeFileSync(fp, input.content, 'utf8');
                resolve({ content: 'Wrote ' + fp, isError: false, newCwd: cwd });
            } catch(e) { resolve({ content: 'Error: ' + e.message, isError: true, newCwd: cwd }); }
        } else if (name === 'list_dir') {
            try {
                const dp = path.resolve(cwd, input.path);
                const entries = fs.readdirSync(dp, { withFileTypes: true });
                const lines = entries.map(e => (e.isDirectory() ? 'd ' : 'f ') + e.name).join('\n');
                resolve({ content: lines || '(empty)', isError: false, newCwd: cwd });
            } catch(e) { resolve({ content: 'Error: ' + e.message, isError: true, newCwd: cwd }); }
        } else {
            resolve({ content: 'Unknown tool: ' + name, isError: true, newCwd: cwd });
        }
    });
}

// Streaming proxy call — writes text_delta chunks to socket as they arrive.
// Resolves with { content, stop_reason } in Anthropic format after stream ends.
function callProxyStreaming(socket, messages, tools, onThinkingDone) {
    return new Promise((resolve, reject) => {
        const cfg  = readConfig();
        const body = JSON.stringify({
            model: 'claude-3-5-sonnet-20241022',
            max_tokens: 4096,
            messages,
            tools,
            system: AGENTIC_SYSTEM_PROMPT,
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
    'git is available via isomorphic-git if !install-git has been run.\n' +
    'Always use tools to complete tasks rather than just describing how to do them. ' +
    'When editing code, read the file first, make targeted changes, then write it back.';

// runAgentic — streaming agentic loop. Returns final assistant text for history.
// Also returns updated shellCwd (may change if AI ran cd commands).
async function runAgentic(socket, userMessage, history, shellCwd) {
    const MAX_TURNS = 12;
    const messages = history.map(h => ({ role: h.role, content: h.content }));
    messages.push({ role: 'user', content: userMessage });

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
        for (let turn = 0; turn < MAX_TURNS; turn++) {
            const resp = await callProxyStreaming(socket, messages, AGENTIC_TOOLS, signalThinkingDone);
            if (!resp || !resp.content) throw new Error('Empty response from proxy');

            // Collect text for history
            for (const b of resp.content) {
                if (b.type === 'text') assistantText += b.text;
            }

            messages.push({ role: 'assistant', content: resp.content });

            if (resp.stop_reason === 'end_turn' || resp.stop_reason === 'stop_sequence') break;

            const toolUses = resp.content.filter(b => b.type === 'tool_use');
            if (!toolUses.length) break;

            // Tools run after streaming — show indicator and execute
            signalThinkingDone();
            const toolResults = [];
            for (const tu of toolUses) {
                try { socket.write('\r\n\x1b[36m▶ ' + tu.name + '  ' + JSON.stringify(tu.input) + '\x1b[0m\r\n'); } catch(_) {}
                const result = await executeTool(tu.name, tu.input, currentCwd);
                // Update cwd if bash command changed directory
                if (result.newCwd && result.newCwd !== currentCwd) {
                    currentCwd = result.newCwd;
                    try { socket.write('\x1b[2mcwd: ' + currentCwd + '\x1b[0m\r\n'); } catch(_) {}
                }
                const preview = result.content.slice(0, 2000);
                try { socket.write('\x1b[2m' + preview + (result.content.length > 2000 ? '\n…(truncated)' : '') + '\x1b[0m\r\n'); } catch(_) {}
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
                    if (parsed.error) {
                        return proxyError(res, provRes.statusCode || 500,
                            parsed.error.message || JSON.stringify(parsed.error));
                    }
                    if (provRes.statusCode !== 200) {
                        if (provRes.statusCode === 400 && onBadRequest) return onBadRequest();
                        if (provRes.statusCode === 429 && on429) return on429();
                        if (provRes.statusCode === 429) lastRateLimitMs = Date.now();
                        return proxyError(res, provRes.statusCode,
                            'Provider HTTP ' + provRes.statusCode);
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
                        const detail = e.error?.message || e.message;
                        if (detail) msg += ': ' + detail;
                    } catch (_) {}
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
        PATH: (process.env.PATH || '/system/bin:/system/xbin') +
              ':' + path.join(NPM_PREFIX, 'bin') +
              ':' + path.join(FILES_DIR, 'bin'),
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

// Prepend prior conversation turns to the current message so --print mode
// has context across multiple exchanges within the same socket session.
// History entries: { role: 'user'|'assistant', content: string }
// Capped at MAX_HISTORY messages (bridge.js enforces this on write).
function buildMessageWithHistory(message, history) {
    if (!history || history.length === 0) return message;
    const ctx = history.map(m =>
        (m.role === 'user' ? 'Human' : 'Assistant') + ': ' + m.content
    ).join('\n\n');
    return ctx + '\n\nHuman: ' + message;
}

const MAX_HISTORY = 20; // max stored messages (10 turns) per session

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
        'process.argv[2]="--print";' +
        'process.argv[3]=' + JSON.stringify(fullMessage) + ';' +
        'process.argv.length=4;' +
        'import(' + JSON.stringify(cliUrl) + ')' +
        '.then(function(){' +
        'try{require("fs").appendFileSync(' + exitLogPath + ',"[import-resolved]\\n");}catch(_){}})' +
        '.catch(function(e){process.stderr.write("import-err:"+String(e)+"\\n");process.exit(1)});';

    const child = spawn(LAUNCHER, ['-e', evalCode], { env, cwd: FILES_DIR });
    child.stdin.end();

    // Collect stderr separately so we can include it in error messages
    let stderrBuf = '';
    // Send thinking-done BEFORE the first byte of text so the terminal
    // transitions from THINKING → RESPONDING before the text renders.
    // Without this, text arrives while chatState===THINKING and is silently dropped.
    let thinkingDoneSent = false;
    child.stdout.on('data', d => {
        if (!thinkingDoneSent) {
            thinkingDoneSent = true;
            try { socket.write('\x1b]9;thinking-done\x07'); } catch (_) {}
        }
        try { socket.write(d); } catch (_) {}
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
        let   shellCwd  = FILES_DIR;  // working directory for $ shell commands

        const startCfg = readConfig();
        const modeLabel = startCfg.mode === 'subscription' ? 'Anthropic' : (startCfg.providerUrl || 'proxy');
        const agentTag  = agenticEnabled ? '  \x1b[35m[AGENTIC]\x1b[0m' : '';
        const resumed   = history.length > 0 ? '  \x1b[2m(resumed ' + Math.floor(history.length/2) + ' turns)\x1b[0m' : '';
        socket.write(
            '\r\n\x1b[32mClaude Code ready.\x1b[0m Type a message or \x1b[33m$ command\x1b[0m to run shell.\r\n' +
            '\x1b[2mProvider: ' + modeLabel +
            '  Model: ' + (startCfg.modelId || 'auto') +
            '  Key: ' + sanitizeKey(startCfg.apiKey) + '\x1b[0m' + agentTag + resumed + '\r\n\r\n'
        );

        // Run launcher self-test once per connection — helps diagnose
        // whether the child Node.js process can actually start on this device.
        testLauncher().then(ok => {
            const msg = ok
                ? '\x1b[2m[diag] launcher OK\x1b[0m\r\n'
                : '\x1b[31m[diag] LAUNCHER FAILED — child Node.js cannot start on this device.\x1b[0m\r\n' +
                  '\x1b[33mSee !log for details. The app may need an update.\x1b[0m\r\n';
            try { socket.write(msg); } catch (_) {}
        });

        socket.on('data', d => {
            inputBuf += d.toString();

            // Process all complete lines in the buffer
            let nl;
            while ((nl = inputBuf.search(/[\r\n]/)) !== -1) {
                const line = inputBuf.slice(0, nl).replace(/[\x00-\x1f\x7f]/g, '').trim();
                inputBuf   = inputBuf.slice(nl + 1);

                if (!line) continue;

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
                    } catch(_) {}
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

                if (line === '!help') {
                    try {
                        socket.write(
                            '\r\n\x1b[1mShell commands:\x1b[0m\r\n' +
                            '  \x1b[33m$ <command>\x1b[0m   Run any shell command (ls, cat, npm, node, git…)\r\n' +
                            '  \x1b[33m$ cd <dir>\x1b[0m    Change working directory (persists in this session)\r\n' +
                            '  \x1b[33m$ pwd\x1b[0m         Print current working directory\r\n' +
                            '\r\n\x1b[1mAI conversation:\x1b[0m\r\n' +
                            '  Type normally to chat with the AI model\r\n' +
                            '  \x1b[33m!clear\x1b[0m        Clear conversation history\r\n' +
                            '  \x1b[33m!history\x1b[0m      Show number of turns in memory\r\n' +
                            '\r\n\x1b[1mDiagnostics:\x1b[0m\r\n' +
                            '  \x1b[33m!log [N]\x1b[0m      Show last N lines of setup.log (default 80)\r\n' +
                            '  \x1b[33m!ver\x1b[0m          Show version and config info\r\n' +
                            '  \x1b[33m!test\x1b[0m         Test Node.js launcher\r\n' +
                            '  \x1b[33m!test-cli\x1b[0m     Run module-loader diagnostic\r\n' +
                            '  \x1b[33m!install-git\x1b[0m  Install git (isomorphic-git) for $ git commands\r\n' +
                            '  \x1b[33m!gh-auth <tok>\x1b[0m Save GitHub token for git push/clone\r\n' +
                            '  \x1b[33m!agentic [on|off]\x1b[0m Toggle agentic tool-calling mode (persists across restarts)\r\n' +
                            '  \x1b[33m!update\x1b[0m       Re-install claude-code v2.1.112 (no data clear needed)\r\n' +
                            '  \x1b[33m!help\x1b[0m         Show this message\r\n\r\n'
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
                                try { socket.write('\x1b[2m' + shellCwd + '\x1b[0m\r\n\r\n'); } catch(_) {}
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

                // ── Agentic mode: streaming tool-calling loop via proxy ───────
                if (agenticEnabled) {
                    busy = true;
                    const agMsg = line;
                    runAgentic(socket, agMsg, history.slice(), shellCwd).then(result => {
                        if (result.text) {
                            history.push({ role: 'user',      content: agMsg });
                            history.push({ role: 'assistant', content: result.text });
                            if (history.length > MAX_HISTORY) history = history.slice(-MAX_HISTORY);
                            saveSession(history);
                        }
                        // Propagate cwd changes from AI tool calls back to shell
                        if (result.cwd && result.cwd !== shellCwd) shellCwd = result.cwd;
                        busy = false; current = null;
                    }).catch(() => { busy = false; current = null; });
                    continue;
                }

                // ── Standard --print mode ─────────────────────────────────────
                // Signal terminal HTML to show animated thinking indicator
                try { socket.write('\x1b]9;thinking-start\x07'); } catch (_) {}

                busy = true;
                let responseStarted = false;
                let responseBuf = '';     // capture stdout for history
                current = runMessage(line, socket, history);

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

                    // Save exchange to history only on a successful response
                    if (responseStarted && (code === 0 || code === null)) {
                        const reply = stripAnsi(responseBuf);
                        if (reply) {
                            history.push({ role: 'user',      content: line });
                            history.push({ role: 'assistant', content: reply });
                            if (history.length > MAX_HISTORY) history = history.slice(-MAX_HISTORY);
                            saveSession(history);
                        }
                    }
                    responseBuf = '';

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
        });

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

function startBridgeServer() {
    // Start proxy first — port 8082 must be listening before Claude Code spawns,
    // otherwise its first API call gets ECONNREFUSED and it exits immediately.
    startProxyServer(() => openTcpBridge());
}

// ─── Entry point ──────────────────────────────────────────────────────────────

if (isClaudeInstalled()) {
    log('Claude Code already installed — starting bridge server.\n');
    try { fs.writeFileSync(SETUP_DONE, 'true'); } catch (_) {}
    startBridgeServer();
} else {
    installLoop();
}
