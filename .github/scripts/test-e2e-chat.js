#!/usr/bin/env node
'use strict';
/**
 * End-to-end chat simulation for ClaudeCodeSetup.
 *
 * Spins up the same Anthropic→OpenAI proxy that bridge.js uses,
 * sends "hello claude" through it to OpenRouter, and verifies a
 * non-empty text response comes back.
 *
 * Usage:
 *   OPENROUTER_API_KEY=sk-or-... node test-e2e-chat.js
 */

const http  = require('http');
const https = require('https');

const API_KEY    = process.env.OPENROUTER_API_KEY || '';
const MODEL      = 'openai/gpt-oss-20b:free';
const PROVIDER   = 'https://openrouter.ai/api/v1';
const PROXY_PORT = 18082;  // offset to avoid conflicts
const HOST       = '127.0.0.1';
const TIMEOUT_MS = 90000;  // R1 can be slow on first token

if (!API_KEY) {
  console.error('OPENROUTER_API_KEY env var is required');
  process.exit(1);
}

let passed = 0;
let failed = 0;
function ok(label)        { console.log(`  ✓ ${label}`); passed++; }
function fail(label, msg) { console.error(`  ✗ ${label}: ${msg}`); failed++; }

// ─── Inline proxy logic (mirrors bridge.js) ───────────────────────────────────

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
      msgs.push({ role: m.role, content: (m.content || []).filter(b => b.type === 'text').map(b => b.text).join('') });
    }
  }
  return { model, messages: msgs, max_tokens: a.max_tokens || 512, stream: false };
}

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

function forwardToOpenRouter(oaiReq, res, cb) {
  const body = JSON.stringify(oaiReq);
  const target = new URL(PROVIDER.replace(/\/$/, '') + '/chat/completions');

  const provReq = https.request({
    hostname: target.hostname,
    port: 443,
    method: 'POST',
    path: target.pathname,
    headers: {
      'Content-Type':   'application/json',
      'Content-Length': Buffer.byteLength(body),
      'Authorization':  'Bearer ' + API_KEY,
      'HTTP-Referer':   'https://github.com/rektzy9903/ClaudeCodeSetup',
      'X-Title':        'ClaudeCodeSetup',
    },
  }, provRes => {
    let data = '';
    provRes.setEncoding('utf8');
    provRes.on('data', c => { data += c; });
    provRes.on('end', () => {
      try {
        const parsed = JSON.parse(data);
        if (parsed.error) {
          const msg = parsed.error.message || JSON.stringify(parsed.error);
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: msg }));
          return cb(new Error('Provider error: ' + msg));
        }
        const anthResp = oaiToAnth(parsed, oaiReq.model);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(anthResp));
        cb(null, anthResp);
      } catch (e) {
        res.writeHead(500); res.end('{}');
        cb(e);
      }
    });
  });

  provReq.setTimeout(TIMEOUT_MS, () => { provReq.destroy(); cb(new Error('timeout')); });
  provReq.on('error', cb);
  provReq.write(body);
  provReq.end();
}

// ─── Proxy server ─────────────────────────────────────────────────────────────

function startProxy() {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      let body = '';
      req.on('data', c => { body += c; });
      req.on('end', () => {
        try {
          const anthReq = JSON.parse(body);
          const oaiReq  = anthToOai(anthReq, MODEL);
          forwardToOpenRouter(oaiReq, res, () => {});
        } catch (e) {
          res.writeHead(400); res.end(JSON.stringify({ error: e.message }));
        }
      });
    });
    server.listen(PROXY_PORT, HOST, () => resolve(server));
    server.on('error', reject);
  });
}

// ─── Chat test ────────────────────────────────────────────────────────────────

function sendChatMessage(message) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({
      model:      'claude-sonnet-4-5',  // proxy overrides this with MODEL
      max_tokens: 512,
      stream:     false,
      messages:   [{ role: 'user', content: message }],
    });

    const req = http.request({
      hostname: HOST,
      port: PROXY_PORT,
      method: 'POST',
      path: '/v1/messages',
      headers: {
        'Content-Type':   'application/json',
        'Content-Length': Buffer.byteLength(body),
        'Authorization':  'Bearer freecc',
      },
    }, res => {
      let data = '';
      res.setEncoding('utf8');
      res.on('data', c => { data += c; });
      res.on('end', () => {
        try { resolve({ status: res.statusCode, body: JSON.parse(data) }); }
        catch (e) { reject(new Error('Bad JSON: ' + data.slice(0, 200))); }
      });
    });

    req.setTimeout(TIMEOUT_MS, () => { req.destroy(); reject(new Error('timeout after ' + TIMEOUT_MS + 'ms')); });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

// ─── Runner ───────────────────────────────────────────────────────────────────

(async () => {
  console.log('\nClaudeCodeSetup — end-to-end chat simulation');
  console.log('=============================================');
  console.log(`  Provider : OpenRouter`);
  console.log(`  Model    : ${MODEL}`);
  console.log(`  Message  : "hello claude"\n`);

  let server;
  try {
    server = await startProxy();
    ok(`proxy started on ${HOST}:${PROXY_PORT}`);
  } catch (e) {
    fail('proxy start', e.message);
    process.exit(1);
  }

  let result;
  try {
    console.log('  → sending message (may take up to 90s for free model)…');
    result = await sendChatMessage('hello claude');
    ok(`proxy returned HTTP ${result.status}`);
  } catch (e) {
    fail('chat request', e.message);
    server.close();
    process.exit(1);
  }

  const body = result.body;

  if (result.status !== 200) {
    fail('HTTP 200', `got ${result.status} — ${JSON.stringify(body).slice(0, 300)}`);
  } else {
    ok('HTTP 200');
  }

  if (!body.content || !Array.isArray(body.content) || body.content.length === 0) {
    fail('content array', JSON.stringify(body).slice(0, 300));
  } else {
    ok('response has content array');
  }

  const text = (body.content || [])[0]?.text || '';
  if (!text || text.trim().length === 0) {
    fail('non-empty response text', 'empty string');
  } else {
    ok(`model replied: "${text.slice(0, 120).replace(/\n/g, ' ')}…"`);
  }

  if (body.stop_reason) ok(`stop_reason = ${body.stop_reason}`);
  if (body.usage) ok(`tokens: ${body.usage.input_tokens} in / ${body.usage.output_tokens} out`);

  console.log('\n── Summary ──');
  console.log(`  Passed: ${passed}   Failed: ${failed}`);

  if (process.env.GITHUB_STEP_SUMMARY) {
    const lines = [
      '## End-to-End Chat Simulation',
      '',
      `| | |`,
      `|---|---|`,
      `| Provider | OpenRouter |`,
      `| Model | \`${MODEL}\` |`,
      `| Message | "hello claude" |`,
      `| Status | ${failed === 0 ? '✅ PASSED' : '❌ FAILED'} |`,
      '',
    ];
    if (text) lines.push(`**Model reply:** "${text.slice(0, 300).replace(/\n/g, ' ')}"`);
    require('fs').appendFileSync(process.env.GITHUB_STEP_SUMMARY, lines.join('\n') + '\n');
  }

  server.close();
  process.exit(failed > 0 ? 1 : 0);
})();
