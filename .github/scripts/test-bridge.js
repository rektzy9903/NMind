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
