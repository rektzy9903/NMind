#!/usr/bin/env node
'use strict';
/**
 * Simulation: send "hello claude" through the app's proxy logic
 * to every OpenRouter free model + Kimi models.
 *
 * This reproduces exactly what bridge.js sendToProvider() does:
 *   - Convert Anthropic Messages format → OpenAI Chat Completions
 *   - POST to provider
 *   - Check for a real reply
 *
 * Usage: node test-all-models.js
 */

const https = require('https');
const http  = require('http');

const OR_KEY   = 'OPENROUTER_API_KEY_PLACEHOLDER';
const KIMI_KEY = 'KIMI_API_KEY_PLACEHOLDER';

const OR_BASE   = 'https://openrouter.ai/api/v1';
const KIMI_BASE = 'https://api.moonshot.ai/v1';

const MESSAGE = 'hello claude';

// ─── HTTP helpers ─────────────────────────────────────────────────────────────

function request(urlStr, opts, body) {
    return new Promise((resolve, reject) => {
        const u   = new URL(urlStr);
        const lib = u.protocol === 'https:' ? https : http;
        const req = lib.request({
            hostname: u.hostname,
            port:     u.port || (u.protocol === 'https:' ? 443 : 80),
            path:     u.pathname + (u.search || ''),
            method:   opts.method || 'GET',
            headers:  opts.headers || {},
        }, res => {
            let data = '';
            res.setEncoding('utf8');
            res.on('data', c => { data += c; });
            res.on('end',  () => resolve({ status: res.statusCode, body: data }));
            res.on('error', reject);
        });
        req.setTimeout(30000, () => { req.destroy(); reject(new Error('timeout')); });
        req.on('error', reject);
        if (body) req.write(body);
        req.end();
    });
}

function getJson(url, headers) {
    return request(url, { headers: { Accept: 'application/json', ...headers } })
        .then(r => JSON.parse(r.body));
}

// ─── Fetch OpenRouter free models ─────────────────────────────────────────────

async function getFreeModels() {
    console.log('Fetching OpenRouter model list…');
    const data = await getJson(OR_BASE + '/models', {
        Authorization: 'Bearer ' + OR_KEY,
    });
    const free = (data.data || []).filter(m => {
        const p = m.pricing || {};
        // Free = prompt + completion pricing both 0 (or ":free" suffix)
        return m.id.endsWith(':free') ||
               (String(p.prompt) === '0' && String(p.completion) === '0');
    });
    return free.map(m => m.id).sort();
}

// ─── Test a single model ──────────────────────────────────────────────────────

async function testModel({ provider, baseUrl, apiKey, modelId, extraHeaders }) {
    const payload = JSON.stringify({
        model:      modelId,
        messages:   [{ role: 'user', content: MESSAGE }],
        max_tokens: 128,
        stream:     false,
    });

    const headers = {
        'Content-Type':   'application/json',
        'Content-Length': Buffer.byteLength(payload),
        'Authorization':  'Bearer ' + apiKey,
        ...(extraHeaders || {}),
    };

    let r;
    try {
        r = await request(baseUrl.replace(/\/$/, '') + '/chat/completions', {
            method: 'POST', headers,
        }, payload);
    } catch (e) {
        return { ok: false, reason: 'network: ' + e.message, reply: '' };
    }

    let parsed;
    try { parsed = JSON.parse(r.body); } catch (_) {
        return { ok: false, reason: `HTTP ${r.status} — unparseable body`, reply: '' };
    }

    if (r.status !== 200) {
        const msg = parsed?.error?.message || parsed?.message || r.body.slice(0, 120);
        return { ok: false, reason: `HTTP ${r.status} — ${msg}`, reply: '' };
    }

    const reply = ((parsed.choices || [])[0]?.message?.content || '').trim();
    if (!reply) {
        return { ok: false, reason: 'empty reply', reply: '' };
    }

    return { ok: true, reason: 'OK', reply: reply.replace(/\s+/g, ' ').slice(0, 120) };
}

// ─── Progress display ─────────────────────────────────────────────────────────

const COL_MODEL  = 55;
const COL_STATUS = 10;

function pad(s, n) { return String(s).padEnd(n); }

function printRow(model, status, detail) {
    const icon = status === 'PASS' ? '✓' : status === 'FAIL' ? '✗' : '?';
    const col  = status === 'PASS' ? '\x1b[32m' : '\x1b[31m';
    process.stdout.write(
        `  ${col}${icon}\x1b[0m  ${pad(model, COL_MODEL)}  ${col}${pad(status, COL_STATUS)}\x1b[0m  ${detail}\n`
    );
}

// ─── Main ─────────────────────────────────────────────────────────────────────

(async () => {
    console.log('\n╔══════════════════════════════════════════════════════════════════════╗');
    console.log('║   ClaudeCodeSetup — provider / model simulation                      ║');
    console.log('║   Message: "hello claude"                                            ║');
    console.log('╚══════════════════════════════════════════════════════════════════════╝\n');

    const results = [];

    // ── OpenRouter free models ────────────────────────────────────────────────
    let freeModels = [];
    try {
        freeModels = await getFreeModels();
        console.log(`Found ${freeModels.length} free OpenRouter models.\n`);
    } catch (e) {
        console.error('Failed to fetch model list: ' + e.message);
    }

    if (freeModels.length > 0) {
        console.log('── OpenRouter (free models) ──────────────────────────────────────────');
        console.log(`  ${'Model'.padEnd(COL_MODEL + 3)}  ${'Status'.padEnd(COL_STATUS)}  Detail\n`);

        for (const modelId of freeModels) {
            process.stdout.write(`  ·  ${pad(modelId, COL_MODEL)}  testing…\r`);
            const result = await testModel({
                provider: 'openrouter',
                baseUrl:  OR_BASE,
                apiKey:   OR_KEY,
                modelId,
                extraHeaders: {
                    'HTTP-Referer': 'https://github.com/rektzy9903/ClaudeCodeSetup',
                    'X-Title':      'ClaudeCodeSetup',
                },
            });
            printRow(modelId, result.ok ? 'PASS' : 'FAIL',
                     result.ok ? result.reply : result.reason);
            results.push({ provider: 'OpenRouter', modelId, ...result });
        }
    }

    // ── Kimi models ───────────────────────────────────────────────────────────
    const kimiModels = ['kimi-k2', 'moonshot-v1-8k', 'moonshot-v1-32k'];
    // Also try the OpenRouter-style Kimi model that appears in Providers.kt
    const kimiOrModels = ['moonshotai/kimi-k2.5'];

    console.log('\n── Kimi / Moonshot AI ────────────────────────────────────────────────');
    console.log(`  ${'Model'.padEnd(COL_MODEL + 3)}  ${'Status'.padEnd(COL_STATUS)}  Detail\n`);

    for (const modelId of kimiModels) {
        process.stdout.write(`  ·  ${pad(modelId, COL_MODEL)}  testing…\r`);
        const result = await testModel({
            provider: 'kimi',
            baseUrl:  KIMI_BASE,
            apiKey:   KIMI_KEY,
            modelId,
        });
        printRow(modelId, result.ok ? 'PASS' : 'FAIL',
                 result.ok ? result.reply : result.reason);
        results.push({ provider: 'Kimi', modelId, ...result });
    }

    // Kimi K2.5 via OpenRouter
    for (const modelId of kimiOrModels) {
        process.stdout.write(`  ·  ${pad(modelId, COL_MODEL)}  testing…\r`);
        const result = await testModel({
            provider: 'openrouter',
            baseUrl:  OR_BASE,
            apiKey:   OR_KEY,
            modelId,
            extraHeaders: {
                'HTTP-Referer': 'https://github.com/rektzy9903/ClaudeCodeSetup',
                'X-Title':      'ClaudeCodeSetup',
            },
        });
        printRow(`${modelId} (via OpenRouter)`, result.ok ? 'PASS' : 'FAIL',
                 result.ok ? result.reply : result.reason);
        results.push({ provider: 'OpenRouter', modelId, ...result });
    }

    // ── Summary ───────────────────────────────────────────────────────────────
    const passed = results.filter(r => r.ok);
    const failed = results.filter(r => !r.ok);

    console.log('\n╔══════════════════════════════════════════════════════════════════════╗');
    console.log(`║   RESULTS: ${passed.length} passed  /  ${failed.length} failed  /  ${results.length} total`.padEnd(71) + '║');
    console.log('╚══════════════════════════════════════════════════════════════════════╝');

    if (passed.length > 0) {
        console.log('\n\x1b[32m✓ WORKING models:\x1b[0m');
        for (const r of passed) {
            console.log(`   [${r.provider}]  ${r.modelId}`);
            console.log(`              └─ "${r.reply}"`);
        }
    }

    if (failed.length > 0) {
        console.log('\n\x1b[31m✗ FAILED models:\x1b[0m');
        for (const r of failed) {
            console.log(`   [${r.provider}]  ${r.modelId}  →  ${r.reason}`);
        }
    }

    console.log('');
})();
