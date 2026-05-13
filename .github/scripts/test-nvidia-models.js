#!/usr/bin/env node
// Simulates the app's ModelTestScreen for NVIDIA NIM:
// 1. Fetch all free models from /v1/models
// 2. Send a minimal chat completion to each one
// 3. Report Pass / Empty / Rate-limit / Fail / Timeout

const https = require('https');
const API_KEY = process.env.NVIDIA_API_KEY || '';
const BASE_URL = 'integrate.api.nvidia.com';
const TIMEOUT_MS = 12000;

if (!API_KEY) {
  console.error('Usage: NVIDIA_API_KEY=nvapi-xxx node test-nvidia-models.js');
  process.exit(1);
}

function request(options, body) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('TIMEOUT')), TIMEOUT_MS);
    const req = https.request(options, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => { clearTimeout(timer); resolve({ code: res.statusCode, body: data }); });
    });
    req.on('error', e => { clearTimeout(timer); reject(e); });
    if (body) req.write(body);
    req.end();
  });
}

async function fetchModels() {
  const res = await request({
    hostname: BASE_URL, path: '/v1/models', method: 'GET',
    headers: { 'Authorization': `Bearer ${API_KEY}`, 'Accept': 'application/json' }
  });
  if (res.code !== 200) throw new Error(`HTTP ${res.code}: ${res.body.slice(0, 200)}`);
  const data = JSON.parse(res.body).data || [];
  const seen = new Set();
  return data.map(m => m.id).filter(id => seen.has(id) ? false : seen.add(id)).sort();
}

async function testModel(modelId) {
  const body = JSON.stringify({
    model: modelId,
    messages: [{ role: 'user', content: 'hi' }],
    max_tokens: 8
  });
  try {
    const res = await request({
      hostname: BASE_URL, path: '/v1/chat/completions', method: 'POST',
      headers: {
        'Authorization': `Bearer ${API_KEY}`,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body)
      }
    }, body);

    if (res.code === 429) return { status: '⚡ Rate-limit', ms: 0 };
    if (res.code === 402) return { status: '💳 Credits required', ms: 0 };
    if (res.code < 200 || res.code >= 300) return { status: `✗ Fail (HTTP ${res.code})`, ms: 0 };

    const json = JSON.parse(res.body);
    const text = json?.choices?.[0]?.message?.content?.trim() ?? '';
    return { status: text ? '✓ Pass' : '∅ Empty', ms: 0 };
  } catch (e) {
    if (e.message === 'TIMEOUT') return { status: '⏱ Timeout', ms: 0 };
    return { status: `✗ Fail (${e.message})`, ms: 0 };
  }
}

(async () => {
  console.log('Fetching NVIDIA NIM model list…\n');
  let models;
  try {
    models = await fetchModels();
  } catch (e) {
    console.error('Failed to fetch models:', e.message);
    process.exit(1);
  }

  console.log(`Found ${models.length} models. Testing each…\n`);
  console.log('─'.repeat(72));

  const results = [];
  for (const modelId of models) {
    const start = Date.now();
    const { status } = await testModel(modelId);
    const ms = Date.now() - start;
    const msStr = ms > 0 ? `${ms}ms` : '';
    const line = `${status.padEnd(22)} ${modelId}${msStr ? '  ' + msStr : ''}`;
    console.log(line);
    results.push({ modelId, status, ms });
  }

  console.log('─'.repeat(72));
  const pass = results.filter(r => r.status.startsWith('✓')).length;
  const fail = results.filter(r => r.status.startsWith('✗')).length;
  const rate = results.filter(r => r.status.startsWith('⚡')).length;
  const credits = results.filter(r => r.status.startsWith('💳')).length;
  const empty = results.filter(r => r.status.startsWith('∅')).length;
  const timeout = results.filter(r => r.status.startsWith('⏱')).length;
  console.log(`\nSummary: ${pass} pass · ${empty} empty · ${rate} rate-limited · ${credits} needs credits · ${fail} fail · ${timeout} timeout`);
})();
