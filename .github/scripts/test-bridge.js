#!/usr/bin/env node
'use strict';
/**
 * CI simulation test for ClaudeCodeSetup.
 *
 * Validates two things that have caused chained failures on device:
 *   1. claude-code npm package — pinned version downloads, extracts correctly,
 *      and cli.js is present at the path bridge.js expects.
 *   2. Provider API endpoints — each provider's base URL answers with any HTTP
 *      status (200, 401, 403 all mean "reachable"). Connection failures = broken.
 */

const https  = require('https');
const http   = require('http');
const fs     = require('fs');
const path   = require('path');
const os     = require('os');
const zlib   = require('zlib');
const { spawnSync } = require('child_process');

const PINNED_VERSION = '2.1.112';
const REGISTRY_URL   = `https://registry.npmjs.org/@anthropic-ai%2Fclaude-code/${PINNED_VERSION}`;

const PROVIDERS = [
  { name: 'Gemini',      url: 'https://generativelanguage.googleapis.com/v1beta/openai/models' },
  { name: 'OpenRouter',  url: 'https://openrouter.ai/api/v1/models' },
  { name: 'DeepSeek',    url: 'https://api.deepseek.com/models' },
  { name: 'Kimi',        url: 'https://api.moonshot.ai/v1/models' },
  { name: 'NVIDIA NIM',  url: 'https://integrate.api.nvidia.com/v1/models' },
  { name: 'Meta Llama',  url: 'https://api.llama.com/v1/models' },
  { name: 'Anthropic',   url: 'https://api.anthropic.com/v1/models' },
];

// ─── Helpers ──────────────────────────────────────────────────────────────────

let passed = 0;
let failed = 0;

function ok(label) {
  console.log(`  ✓ ${label}`);
  passed++;
}

function fail(label, reason) {
  console.error(`  ✗ ${label}: ${reason}`);
  failed++;
}

function get(url, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const lib = url.startsWith('https') ? https : http;
    const req = lib.get(url, { headers: { 'User-Agent': 'ClaudeCodeSetup-CI/1.0' } }, res => {
      res.resume();
      resolve(res.statusCode);
    });
    req.setTimeout(timeoutMs, () => { req.destroy(); reject(new Error('timeout')); });
    req.on('error', reject);
  });
}

function getJson(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { headers: { 'Accept': 'application/json' } }, res => {
      if (res.statusCode !== 200) { res.resume(); return reject(new Error(`HTTP ${res.statusCode}`)); }
      let body = '';
      res.setEncoding('utf8');
      res.on('data', c => body += c);
      res.on('end', () => { try { resolve(JSON.parse(body)); } catch (e) { reject(e); } });
      res.on('error', reject);
    });
    req.setTimeout(30000, () => { req.destroy(); reject(new Error('timeout')); });
    req.on('error', reject);
  });
}

function downloadTo(url, dest) {
  return new Promise((resolve, reject) => {
    function fetch(u, hops) {
      if (hops > 5) return reject(new Error('too many redirects'));
      https.get(u, res => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          return fetch(res.headers.location, hops + 1);
        }
        if (res.statusCode !== 200) { res.resume(); return reject(new Error(`HTTP ${res.statusCode}`)); }
        const out = fs.createWriteStream(dest);
        res.pipe(out);
        out.on('finish', resolve);
        out.on('error', reject);
        res.on('error', reject);
      }).on('error', reject);
    }
    fetch(url, 0);
  });
}

// ─── Test 1: npm package structure ────────────────────────────────────────────

async function testNpmPackage() {
  console.log('\n── Test 1: npm package (claude-code@' + PINNED_VERSION + ') ──');

  let meta;
  try {
    meta = await getJson(REGISTRY_URL);
    ok(`registry responded for v${meta.version}`);
  } catch (e) {
    fail('registry fetch', e.message);
    return;
  }

  if (meta.version !== PINNED_VERSION) {
    fail('version match', `got ${meta.version}, expected ${PINNED_VERSION}`);
    return;
  }

  const tarball = meta.dist && meta.dist.tarball;
  if (!tarball) { fail('tarball url', 'missing in registry response'); return; }
  ok(`tarball url present: ${tarball}`);

  const tmpDir  = fs.mkdtempSync(path.join(os.tmpdir(), 'cc-test-'));
  const tgzPath = path.join(tmpDir, 'claude-code.tgz');
  const tarPath = path.join(tmpDir, 'claude-code.tar');
  const extDir  = path.join(tmpDir, 'extracted');
  fs.mkdirSync(extDir);

  try {
    await downloadTo(tarball, tgzPath);
    ok(`downloaded ${(fs.statSync(tgzPath).size / 1e6).toFixed(1)} MB`);
  } catch (e) {
    fail('tarball download', e.message);
    return;
  }

  // Decompress with zlib (mirrors bridge.js approach)
  try {
    await new Promise((res, rej) => {
      const src = fs.createReadStream(tgzPath);
      const gz  = zlib.createGunzip();
      const dst = fs.createWriteStream(tarPath);
      src.on('error', rej); gz.on('error', rej); dst.on('error', rej);
      dst.on('finish', res);
      src.pipe(gz).pipe(dst);
    });
    ok('zlib decompression succeeded');
  } catch (e) {
    fail('zlib decompress', e.message);
    return;
  }

  // Extract with tar -xf (no -z, mirrors bridge.js)
  const tar = spawnSync('tar', ['-xf', tarPath, '-C', extDir]);
  if (tar.status !== 0) {
    fail('tar extract', (tar.stderr || '').toString().trim() || `exit ${tar.status}`);
    return;
  }
  ok('tar -xf succeeded');

  const pkgDir = path.join(extDir, 'package');
  if (!fs.existsSync(pkgDir)) {
    fail('package/ dir', `not found; contents: ${fs.readdirSync(extDir).join(', ')}`);
    return;
  }
  ok('package/ directory present');

  const cliJs = path.join(pkgDir, 'cli.js');
  if (!fs.existsSync(cliJs)) {
    fail('cli.js', `missing; package/ contains: ${fs.readdirSync(pkgDir).join(', ')}`);
    return;
  }
  ok(`cli.js found at package/cli.js (${(fs.statSync(cliJs).size / 1e3).toFixed(0)} KB)`);

  // Clean up
  try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch (_) {}
}

// ─── Test 2: provider API reachability ────────────────────────────────────────

async function testProviders() {
  console.log('\n── Test 2: provider API endpoints ──');

  const results = await Promise.all(PROVIDERS.map(async p => {
    try {
      const status = await get(p.url);
      // 200 = open endpoint, 401/403 = auth required (still reachable), 404 = wrong path
      const reachable = status < 500;
      return { ...p, status, reachable };
    } catch (e) {
      return { ...p, status: null, reachable: false, error: e.message };
    }
  }));

  for (const r of results) {
    if (r.reachable) {
      ok(`${r.name.padEnd(12)} → HTTP ${r.status}`);
    } else {
      fail(`${r.name}`, r.error || `HTTP ${r.status}`);
    }
  }

  return results;
}

// ─── Test 3: Anthropic → OpenAI proxy conversion logic ───────────────────────
// Inlines the same conversion functions from bridge.js to test them in CI
// without needing an Android device or running Node.js bridge.

function anthToOai(a, model) {
    const msgs = [];
    if (a.system) {
        const text = typeof a.system === 'string' ? a.system
            : (a.system || []).filter(b => b.type === 'text').map(b => b.text).join('\n');
        if (text) msgs.push({ role: 'system', content: text });
    }
    for (const m of (a.messages || [])) {
        if (typeof m.content === 'string') {
            msgs.push({ role: m.role, content: m.content });
        } else {
            msgs.push({ role: m.role, content: (m.content || []).filter(b => b.type === 'text').map(b => b.text).join('') });
        }
    }
    const req = { model, messages: msgs, max_tokens: a.max_tokens || 8096, stream: !!a.stream };
    if (a.temperature !== undefined) req.temperature = a.temperature;
    if (a.stop_sequences && a.stop_sequences.length) req.stop = a.stop_sequences;
    return req;
}

function oaiToAnth(oai, model) {
    const choice = (oai.choices || [])[0] || {};
    const text   = (choice.message || {}).content || '';
    const stop   = choice.finish_reason === 'length' ? 'max_tokens' : 'end_turn';
    return {
        id: 'msg_test', type: 'message', role: 'assistant',
        content: [{ type: 'text', text }],
        model, stop_reason: stop, stop_sequence: null,
        usage: {
            input_tokens:  (oai.usage || {}).prompt_tokens    || 0,
            output_tokens: (oai.usage || {}).completion_tokens || 0,
        },
    };
}

function testProxyConversion() {
    console.log('\n── Test 3: proxy Anthropic ↔ OpenAI conversion ──');

    // Request conversion
    const anthReq = {
        model: 'ignored-by-proxy',
        system: 'You are a helpful assistant.',
        messages: [{ role: 'user', content: 'Say hello' }],
        max_tokens: 100, stream: false,
    };
    const oai = anthToOai(anthReq, 'qwen/qwen3-coder:free');

    if (oai.model !== 'qwen/qwen3-coder:free') { fail('model substitution', oai.model); return; }
    ok('model substituted with configured modelId');

    if (!oai.messages.find(m => m.role === 'system')) { fail('system message', 'missing'); return; }
    ok('system prompt moved to messages[0]');

    if (!oai.messages.find(m => m.role === 'user' && m.content === 'Say hello')) { fail('user message', 'missing'); return; }
    ok('user message preserved');

    if (oai.stream !== false) { fail('stream flag', oai.stream); return; }
    ok('stream=false preserved');

    // Response conversion (non-streaming)
    const oaiRes = {
        id: 'chatcmpl-123',
        choices: [{ message: { content: 'Hello there!' }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 15, completion_tokens: 3, total_tokens: 18 },
    };
    const anth = oaiToAnth(oaiRes, 'qwen/qwen3-coder:free');

    if (!anth.content || anth.content[0].text !== 'Hello there!') { fail('response text', JSON.stringify(anth.content)); return; }
    ok('response text extracted correctly');

    if (anth.stop_reason !== 'end_turn') { fail('stop_reason', anth.stop_reason); return; }
    ok('finish_reason "stop" → stop_reason "end_turn"');

    if (anth.usage.input_tokens !== 15 || anth.usage.output_tokens !== 3) { fail('usage tokens', JSON.stringify(anth.usage)); return; }
    ok('token usage mapped correctly');

    // max_tokens finish reason
    const oaiLen = { choices: [{ message: { content: 'cut' }, finish_reason: 'length' }], usage: {} };
    const anthLen = oaiToAnth(oaiLen, 'model');
    if (anthLen.stop_reason !== 'max_tokens') { fail('length→max_tokens', anthLen.stop_reason); return; }
    ok('finish_reason "length" → stop_reason "max_tokens"');
}

// ─── Runner ───────────────────────────────────────────────────────────────────

(async () => {
  console.log('ClaudeCodeSetup — bridge & provider simulation test');
  console.log('====================================================');

  await testNpmPackage();
  const providerResults = await testProviders();

  console.log(`\n── Summary ──`);
  console.log(`  Passed: ${passed}   Failed: ${failed}`);

  // Emit a machine-readable summary for the GitHub Actions step summary
  if (process.env.GITHUB_STEP_SUMMARY) {
    const lines = [
      '## Bridge & Provider Test Results',
      '',
      '### npm package (claude-code@' + PINNED_VERSION + ')',
      failed === 0 ? '✅ Package structure valid — cli.js present' : '❌ Package test failed',
      '',
      '### Provider Endpoints',
      '| Provider | Status | Reachable |',
      '|----------|--------|-----------|',
      ...providerResults.map(r =>
        `| ${r.name} | ${r.status ?? r.error} | ${r.reachable ? '✅' : '❌'} |`
      ),
    ];
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, lines.join('\n') + '\n');
  }

  if (failed > 0) process.exit(1);
})();
