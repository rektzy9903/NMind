// mcp_http_proxy.js — stdio MCP server that proxies a single upstream HTTP MCP server.
//
// MCP-1: lets HTTP/SSE MCP servers work in claude-code's print mode. Previously
// HTTP MCP was agentic-only because claude-code v2.1.112 hangs on Android Bionic
// when --mcp-config or settings.json mcpServers contains http/sse entries
// (connecting to remote endpoints during the spawn health-check). This shim is
// a normal stdio MCP server from claude-code's perspective; the network calls
// happen lazily inside the shim, never during spawn.
//
// One process per upstream — bridge.js patchSettings injects one mcpServers
// entry per row of mcp_http.json. Each shim instance owns exactly one upstream.
//
// Env vars (set by bridge.js):
//   MCP_HTTP_NAME    — display name (used in stderr logs only)
//   MCP_HTTP_URL     — upstream MCP HTTP/SSE endpoint
//   MCP_HTTP_HEADERS — JSON object of extra request headers (e.g. auth bearer)
//
// Tool names are NOT namespaced — each shim presents upstream tools as-is;
// claude-code wraps them under the server's settings.json key, so collisions
// between separate upstreams stay separate by server.
//
// Protocol coverage (MVP): initialize, tools/list, tools/call.
// Resources, prompts, notifications are not forwarded (future work).

const http = require('http');
const https = require('https');

const NAME = process.env.MCP_HTTP_NAME || 'http';
const URL_STR = process.env.MCP_HTTP_URL || '';
let EXTRA_HEADERS = {};
try { EXTRA_HEADERS = JSON.parse(process.env.MCP_HTTP_HEADERS || '{}') || {}; } catch(_) {}

const TIMEOUT_MS = 15000;
const STATE = { sessionId: null, tools: [], initialized: false };

function logErr(msg) {
    try { process.stderr.write('[mcp-http-proxy:' + NAME + '] ' + msg + '\n'); } catch(_) {}
}
function send(obj) {
    try { process.stdout.write(JSON.stringify(obj) + '\n'); } catch(_) {}
}

// POST JSON-RPC to upstream. Mirrors bridge.js mcpHttpPost behavior so SSE
// responses and 202-Accepted notifications both work.
function httpPost(body, useSessionId) {
    return new Promise((resolve, reject) => {
        const bodyStr = JSON.stringify(body);
        let parsed;
        try { parsed = new URL(URL_STR); } catch(e) { return reject(new Error('bad URL: ' + URL_STR)); }
        const isHttps = parsed.protocol === 'https:';
        const mod = isHttps ? https : http;
        const headers = {
            'Content-Type': 'application/json',
            'Accept': 'application/json, text/event-stream',
            'Content-Length': Buffer.byteLength(bodyStr),
        };
        if (useSessionId && STATE.sessionId) headers['mcp-session-id'] = STATE.sessionId;
        for (const k of Object.keys(EXTRA_HEADERS)) headers[k] = EXTRA_HEADERS[k];

        const req = mod.request({
            hostname: parsed.hostname,
            port: parseInt(parsed.port) || (isHttps ? 443 : 80),
            path: parsed.pathname + (parsed.search || ''),
            method: 'POST',
            headers,
        }, res => {
            const sid = res.headers['mcp-session-id'] || null;
            const ct = (res.headers['content-type'] || '').toLowerCase();
            let buf = '';
            res.setEncoding('utf8');
            res.on('data', c => buf += c);
            res.on('end', () => {
                if (res.statusCode === 202) { resolve({ _sid: sid }); return; }
                if (res.statusCode < 200 || res.statusCode >= 300) {
                    return reject(new Error('HTTP ' + res.statusCode + ': ' + buf.slice(0, 160)));
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
                    const rpc = events.find(e => e && e.id !== undefined) || events[0] || {};
                    rpc._sid = sid;
                    resolve(rpc);
                } else {
                    try { const r = JSON.parse(buf); r._sid = sid; resolve(r); }
                    catch(e) { reject(new Error('bad JSON: ' + buf.slice(0, 80))); }
                }
            });
            res.on('error', reject);
        });
        req.setTimeout(TIMEOUT_MS, () => req.destroy(new Error('upstream timeout')));
        req.on('error', reject);
        req.write(bodyStr);
        req.end();
    });
}

// Lazy upstream init — runs on first claude-code request, not at process spawn.
// This is the critical bit: claude-code spawns us during its startup, but no
// network happens until the first MCP RPC arrives, so spawn stays fast.
async function ensureUpstream() {
    if (STATE.initialized) return;
    if (!URL_STR) { STATE.initialized = true; logErr('no URL configured'); return; }
    try {
        const initRes = await httpPost({
            jsonrpc: '2.0', id: 1, method: 'initialize',
            params: {
                protocolVersion: '2025-03-26',
                capabilities: { tools: {} },
                clientInfo: { name: 'nexus-mcp-http-proxy', version: '1.0' },
            },
        }, false);
        if (initRes.error) throw new Error(initRes.error.message || JSON.stringify(initRes.error));
        if (initRes._sid) STATE.sessionId = initRes._sid;
        // fire-and-forget initialized notification
        httpPost({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} }, true).catch(() => {});
        const toolsRes = await httpPost({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }, true);
        if (toolsRes.error) throw new Error(toolsRes.error.message || JSON.stringify(toolsRes.error));
        const tlist = ((toolsRes.result || toolsRes) || {}).tools || [];
        STATE.tools = tlist.map(t => ({
            name: String(t.name || ''),
            description: String(t.description || ''),
            inputSchema: t.inputSchema || { type: 'object', properties: {} },
        }));
        STATE.initialized = true;
        logErr('upstream ready (' + STATE.tools.length + ' tools)');
    } catch(e) {
        STATE.initialized = true; // don't loop on persistent failure
        STATE.tools = [];
        logErr('init failed: ' + e.message);
    }
}

// JSON-RPC dispatcher.
async function handleRequest(req) {
    if (!req || typeof req.method !== 'string') return;
    const isNotif = req.id === undefined || req.id === null;

    if (req.method === 'initialize') {
        if (isNotif) return;
        send({
            jsonrpc: '2.0', id: req.id,
            result: {
                protocolVersion: '2025-03-26',
                capabilities: { tools: {} },
                serverInfo: { name: 'mcp-http-proxy-' + NAME, version: '1.0' },
            },
        });
        return;
    }

    if (req.method === 'notifications/initialized') return;

    if (req.method === 'tools/list') {
        await ensureUpstream();
        if (!isNotif) send({ jsonrpc: '2.0', id: req.id, result: { tools: STATE.tools } });
        return;
    }

    if (req.method === 'tools/call') {
        await ensureUpstream();
        const toolName = req.params && req.params.name;
        const args = (req.params && req.params.arguments) || {};
        if (!toolName) {
            if (!isNotif) send({ jsonrpc: '2.0', id: req.id, error: { code: -32602, message: 'missing tool name' } });
            return;
        }
        try {
            const res = await httpPost({
                jsonrpc: '2.0', id: Date.now() & 0x7fffffff, method: 'tools/call',
                params: { name: toolName, arguments: args },
            }, true);
            if (isNotif) return;
            if (res.error) send({ jsonrpc: '2.0', id: req.id, error: res.error });
            else send({ jsonrpc: '2.0', id: req.id, result: res.result || {} });
        } catch(e) {
            if (!isNotif) send({ jsonrpc: '2.0', id: req.id, error: { code: -32000, message: e.message } });
            logErr('tools/call ' + toolName + ' failed: ' + e.message);
        }
        return;
    }

    if (!isNotif) {
        send({ jsonrpc: '2.0', id: req.id, error: { code: -32601, message: 'method not supported: ' + req.method } });
    }
}

// Line-delimited JSON-RPC over stdin.
let stdinBuf = '';
process.stdin.on('data', d => {
    stdinBuf += d.toString();
    let nl;
    while ((nl = stdinBuf.indexOf('\n')) !== -1) {
        const line = stdinBuf.slice(0, nl).trim();
        stdinBuf = stdinBuf.slice(nl + 1);
        if (!line) continue;
        let req;
        try { req = JSON.parse(line); }
        catch(_) { logErr('bad JSON-RPC line: ' + line.slice(0, 80)); continue; }
        Promise.resolve(handleRequest(req)).catch(e => logErr('handler error: ' + e.message));
    }
});
process.stdin.on('end', () => process.exit(0));
process.stdin.resume();
logErr('shim started, awaiting requests');
