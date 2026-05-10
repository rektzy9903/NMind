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
const CONFIG_FILE  = path.join(FILES_DIR, 'bridge_config.json');
const SETUP_LOG    = path.join(FILES_DIR, 'setup.log');
const SETUP_DONE   = path.join(FILES_DIR, 'setup_done');
const SETUP_FAILED = path.join(FILES_DIR, 'setup_failed');

const PORT       = 8083;
const PROXY_PORT = 8082;
const HOST       = '127.0.0.1';

// ─── Logging ──────────────────────────────────────────────────────────────────

function log(msg) {
    const line = msg.endsWith('\n') ? msg : msg + '\n';
    try { fs.appendFileSync(SETUP_LOG, line); } catch (_) {}
    process.stdout.write(line);
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
        // POST /v1/messages — main Anthropic chat endpoint
        if (req.method === 'POST' && req.url.startsWith('/v1/messages')) {
            let body = '';
            req.on('data', chunk => { body += chunk; });
            req.on('end', () => {
                try { handleProxyRequest(JSON.parse(body), res); }
                catch (e) { proxyError(res, 400, e.message); }
            });
            req.on('error', e => proxyError(res, 500, e.message));
            return;
        }
        // GET /v1/models — Claude Code checks this on startup; return a fake list
        if (req.method === 'GET' && req.url.startsWith('/v1/models')) {
            const cfg = readConfig();
            const modelId = cfg.modelId || 'claude-3-5-sonnet-20241022';
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

function handleProxyRequest(anthReq, res) {
    const cfg  = readConfig();
    const pUrl = cfg.providerUrl || '';
    const key  = cfg.apiKey || '';
    const model = cfg.modelId || anthReq.model || '';

    if (!pUrl) return proxyError(res, 500, 'No provider URL in config — check app settings');

    const oaiReq = anthToOai(anthReq, model);
    sendToProvider(pUrl, key, oaiReq, !!anthReq.stream, res);
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
        } else {
            const text = (m.content || []).filter(b => b.type === 'text').map(b => b.text).join('');
            msgs.push({ role: m.role, content: text });
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
    const text   = (choice.message || {}).content || '';
    const stop   = choice.finish_reason === 'length' ? 'max_tokens' : 'end_turn';
    return {
        id: 'msg_' + (oai.id || Date.now()),
        type: 'message', role: 'assistant',
        content: [{ type: 'text', text }],
        model, stop_reason: stop, stop_sequence: null,
        usage: {
            input_tokens:  (oai.usage || {}).prompt_tokens    || 0,
            output_tokens: (oai.usage || {}).completion_tokens || 0,
        },
    };
}

function sendToProvider(baseUrl, apiKey, oaiReq, stream, res) {
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
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify(oaiToAnth(parsed, oaiReq.model)));
                } catch (e) {
                    proxyError(res, 500, 'Parse error: ' + e.message);
                }
            });
        } else {
            // Streaming: convert OpenAI SSE → Anthropic SSE on the fly
            res.writeHead(200, {
                'Content-Type':  'text/event-stream',
                'Cache-Control': 'no-cache',
                'Connection':    'keep-alive',
            });

            const msgId = 'msg_' + Date.now();
            let outTokens = 0;
            let buffer    = '';
            let headersSent = false;

            function sendEvent(event, data) {
                try { res.write('event: ' + event + '\ndata: ' + JSON.stringify(data) + '\n\n'); }
                catch (_) {}
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
                    if (event.error) { log('Provider stream error: ' + JSON.stringify(event.error) + '\n'); continue; }

                    if (!headersSent) {
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

                    const delta      = ((event.choices || [])[0] || {}).delta || {};
                    const text       = delta.content || '';
                    const finishCode = ((event.choices || [])[0] || {}).finish_reason;

                    if (text) {
                        outTokens++;
                        sendEvent('content_block_delta', {
                            type: 'content_block_delta', index: 0,
                            delta: { type: 'text_delta', text },
                        });
                    }

                    if (finishCode) {
                        const stopReason = finishCode === 'length' ? 'max_tokens' : 'end_turn';
                        sendEvent('content_block_stop', { type: 'content_block_stop', index: 0 });
                        sendEvent('message_delta', {
                            type: 'message_delta',
                            delta: { stop_reason: stopReason, stop_sequence: null },
                            usage: { output_tokens: outTokens },
                        });
                        sendEvent('message_stop', { type: 'message_stop' });
                    }
                }
            });

            provRes.on('end', () => {
                if (!headersSent) {
                    // Provider sent nothing — emit a minimal valid stream
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
                }
                sendEvent('content_block_stop',  { type: 'content_block_stop', index: 0 });
                sendEvent('message_delta', {
                    type: 'message_delta',
                    delta: { stop_reason: 'end_turn', stop_sequence: null },
                    usage: { output_tokens: outTokens },
                });
                sendEvent('message_stop', { type: 'message_stop' });
                try { res.end(); } catch (_) {}
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
        PATH: process.env.PATH || '/system/bin:/system/xbin',
        LD_LIBRARY_PATH: NATIVE_DIR,
    };
    if (cfg.authToken) {
        env.ANTHROPIC_AUTH_TOKEN = cfg.authToken;
    } else if (cfg.apiKey) {
        env.ANTHROPIC_API_KEY = cfg.apiKey;
    }
    if (cfg.baseUrl)                   env.ANTHROPIC_BASE_URL = cfg.baseUrl;
    if (cfg.modelId && !cfg.authToken) env.ANTHROPIC_MODEL    = cfg.modelId;
    env.CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY = '1';
    env.DISABLE_AUTOUPDATER = '1';
    return env;
}

function runMessage(message, socket) {
    const env   = buildEnv();
    const child = spawn(LAUNCHER, [CLAUDE_CLI, '--print'], { env, cwd: FILES_DIR });

    child.stdin.write(message + '\n');
    child.stdin.end();

    child.stdout.on('data', d => { try { socket.write(d); } catch (_) {} });
    child.stderr.on('data', d => { try { socket.write(d); } catch (_) {} });

    child.on('error', err => {
        try { socket.write('\r\n\x1b[31mError: ' + err.message + '\x1b[0m\r\n'); } catch (_) {}
    });

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

        socket.write('\r\n\x1b[32mClaude Code ready.\x1b[0m Type a message and press Enter.\r\n\r\n');

        socket.on('data', d => {
            inputBuf += d.toString();

            // Process all complete lines in the buffer
            let nl;
            while ((nl = inputBuf.search(/[\r\n]/)) !== -1) {
                const line = inputBuf.slice(0, nl).replace(/[\x00-\x1f\x7f]/g, '').trim();
                inputBuf   = inputBuf.slice(nl + 1);

                if (!line) continue;
                if (busy) {
                    // Interrupt running request
                    try { if (current) current.kill('SIGTERM'); } catch (_) {}
                    if (currentTid) { clearTimeout(currentTid); currentTid = null; }
                    busy = false;
                }

                // Immediate feedback so the user knows the message was received
                try { socket.write('\r\n\x1b[33m⏳ Thinking…\x1b[0m\r\n'); } catch (_) {}

                busy = true;
                let responseStarted = false;
                current = runMessage(line, socket);

                // Detect whether any output actually arrived (used for error diagnosis)
                current.stdout.once('data', () => { responseStarted = true; });
                current.stderr.once('data', () => { responseStarted = true; });

                // 60 s safety-net: kills the child and shows an actionable error if the
                // provider never responds.  The Android UI adds its own 15 s overlay.
                currentTid = setTimeout(() => {
                    if (!busy) return;
                    try { if (current) current.kill('SIGTERM'); } catch (_) {}
                    try {
                        socket.write(
                            '\r\n\x1b[31m✗ Request timed out after 60 s.\x1b[0m\r\n' +
                            '\x1b[33mThe provider may be slow or down. Type your message again to retry.\x1b[0m\r\n'
                        );
                    } catch (_) {}
                    busy = false; current = null; currentTid = null;
                }, 60000);

                current.on('close', code => {
                    if (currentTid) { clearTimeout(currentTid); currentTid = null; }
                    busy = false; current = null;
                    // Show a helpful error only when the process failed silently
                    if (code !== 0 && code !== null && !responseStarted) {
                        try {
                            socket.write(
                                '\r\n\x1b[31m✗ Claude Code exited with error (code ' + code + ').\x1b[0m\r\n' +
                                '\x1b[33mCheck your API key in Settings, then type your message again.\x1b[0m\r\n'
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
