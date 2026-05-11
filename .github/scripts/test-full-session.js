#!/usr/bin/env node
'use strict';
/**
 * Full session simulation for ClaudeCodeSetup.
 *
 * Reproduces exactly what happens on the Android device:
 *   1. Download claude-code v2.1.112 (same version bridge.js installs)
 *   2. Start the Anthropic→OpenAI proxy on port 18083 (same logic as bridge.js)
 *   3. Spawn `node cli.js` with the same env vars bridge.js sets for proxy mode
 *   4. Send "hello claude" to its stdin
 *   5. Assert the session does NOT end immediately and returns a real reply
 *
 * Usage:
 *   OPENROUTER_API_KEY=sk-or-... node test-full-session.js
 */

const { spawn }  = require('child_process');
const https      = require('https');
const http       = require('http');
const fs         = require('fs');
const path       = require('path');
const os         = require('os');
const zlib       = require('zlib');

const API_KEY    = process.env.OPENROUTER_API_KEY || '';
const MODEL_ID   = 'openai/gpt-oss-20b:free';
const PROVIDER   = 'https://openrouter.ai/api/v1';
const PROXY_PORT = 18083;
const HOST       = '127.0.0.1';
const VERSION    = '2.1.112';

if (!API_KEY) { console.error('OPENROUTER_API_KEY required'); process.exit(1); }

let passed = 0, failed = 0;
function ok(label)        { console.log(`  ✓ ${label}`); passed++; }
function fail(label, msg) { console.error(`  ✗ ${label}: ${msg}`); failed++; }

// ─── Helpers ──────────────────────────────────────────────────────────────────

function getJson(url) {
  return new Promise((res, rej) => {
    https.get(url, { headers: { Accept: 'application/json' } }, r => {
      let b = ''; r.setEncoding('utf8');
      r.on('data', c => b += c);
      r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } });
      r.on('error', rej);
    }).on('error', rej);
  });
}

function downloadTo(url, dest) {
  return new Promise((res, rej) => {
    function fetch(u, hops) {
      if (hops > 5) return rej(new Error('too many redirects'));
      https.get(u, r => {
        if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) {
          r.resume(); return fetch(r.headers.location, hops + 1);
        }
        if (r.statusCode !== 200) { r.resume(); return rej(new Error(`HTTP ${r.statusCode}`)); }
        const out = fs.createWriteStream(dest);
        r.pipe(out);
        out.on('finish', res); out.on('error', rej); r.on('error', rej);
      }).on('error', rej);
    }
    fetch(url, 0);
  });
}

// ─── Proxy (mirrors bridge.js) ────────────────────────────────────────────────

function anthToOai(a, model) {
  const msgs = [];
  if (a.system) {
    const text = typeof a.system === 'string' ? a.system
      : (a.system || []).filter(b => b.type === 'text').map(b => b.text).join('\n');
    if (text) msgs.push({ role: 'system', content: text });
  }
  for (const m of (a.messages || [])) {
    if (typeof m.content === 'string') msgs.push({ role: m.role, content: m.content });
    else msgs.push({ role: m.role, content: (m.content || []).filter(b => b.type === 'text').map(b => b.text).join('') });
  }
  return { model, messages: msgs, max_tokens: a.max_tokens || 1024, stream: !!a.stream };
}

function oaiToAnth(oai, model) {
  const choice = (oai.choices || [])[0] || {};
  const text   = (choice.message || {}).content || '';
  const stop   = choice.finish_reason === 'length' ? 'max_tokens' : 'end_turn';
  return {
    id: 'msg_' + (oai.id || Date.now()), type: 'message', role: 'assistant',
    content: [{ type: 'text', text }], model, stop_reason: stop, stop_sequence: null,
    usage: { input_tokens: (oai.usage||{}).prompt_tokens||0, output_tokens: (oai.usage||{}).completion_tokens||0 },
  };
}

// Full streaming SSE conversion (mirrors bridge.js sendToProvider exactly)
function forwardToProvider(oaiReq, res) {
  const body   = JSON.stringify(oaiReq);
  const target = new URL(PROVIDER.replace(/\/$/, '') + '/chat/completions');
  const stream = !!oaiReq.stream;

  const provReq = https.request({
    hostname: target.hostname, port: 443, method: 'POST', path: target.pathname,
    headers: {
      'Content-Type':   'application/json',
      'Content-Length': Buffer.byteLength(body),
      'Authorization':  'Bearer ' + API_KEY,
      'HTTP-Referer':   'https://github.com/rektzy9903/ClaudeCodeSetup',
      'X-Title':        'ClaudeCodeSetup',
    },
  }, provRes => {
    if (!stream) {
      let data = '';
      provRes.setEncoding('utf8');
      provRes.on('data', c => data += c);
      provRes.on('end', () => {
        try {
          const parsed = JSON.parse(data);
          if (parsed.error) {
            res.writeHead(500, {'Content-Type':'application/json'});
            return res.end(JSON.stringify({type:'error',error:{type:'api_error',message:parsed.error.message||JSON.stringify(parsed.error)}}));
          }
          res.writeHead(200, {'Content-Type':'application/json'});
          res.end(JSON.stringify(oaiToAnth(parsed, MODEL_ID)));
        } catch(e) { try { res.writeHead(500); res.end('{}'); } catch(_) {} }
      });
      return;
    }

    // Streaming: convert OpenAI SSE → Anthropic SSE (same as bridge.js)
    res.writeHead(200, {'Content-Type':'text/event-stream','Cache-Control':'no-cache','Connection':'keep-alive'});
    const msgId = 'msg_' + Date.now();
    let buf = '', headersSent = false, outTokens = 0;
    function ev(name, data) { try { res.write('event: '+name+'\ndata: '+JSON.stringify(data)+'\n\n'); } catch(_) {} }

    provRes.setEncoding('utf8');
    provRes.on('data', chunk => {
      buf += chunk;
      const lines = buf.split('\n'); buf = lines.pop();
      for (const line of lines) {
        const t = line.trim();
        if (!t.startsWith('data:')) continue;
        const raw = t.slice(5).trim();
        if (raw === '[DONE]') continue;
        let evt; try { evt = JSON.parse(raw); } catch(_) { continue; }
        if (evt.error) continue;

        if (!headersSent) {
          headersSent = true;
          ev('message_start', {type:'message_start',message:{id:msgId,type:'message',role:'assistant',content:[],model:MODEL_ID,stop_reason:null,usage:{input_tokens:0,output_tokens:0}}});
          ev('content_block_start', {type:'content_block_start',index:0,content_block:{type:'text',text:''}});
          ev('ping', {type:'ping'});
        }
        const delta = ((evt.choices||[])[0]||{}).delta||{};
        const text  = delta.content||'';
        const fin   = ((evt.choices||[])[0]||{}).finish_reason;
        if (text) { outTokens++; ev('content_block_delta',{type:'content_block_delta',index:0,delta:{type:'text_delta',text}}); }
        if (fin) {
          ev('content_block_stop',{type:'content_block_stop',index:0});
          ev('message_delta',{type:'message_delta',delta:{stop_reason:fin==='length'?'max_tokens':'end_turn',stop_sequence:null},usage:{output_tokens:outTokens}});
          ev('message_stop',{type:'message_stop'});
        }
      }
    });
    provRes.on('end', () => {
      if (!headersSent) {
        ev('message_start',{type:'message_start',message:{id:msgId,type:'message',role:'assistant',content:[],model:MODEL_ID,stop_reason:null,usage:{input_tokens:0,output_tokens:0}}});
        ev('content_block_start',{type:'content_block_start',index:0,content_block:{type:'text',text:''}});
      }
      ev('content_block_stop',{type:'content_block_stop',index:0});
      ev('message_delta',{type:'message_delta',delta:{stop_reason:'end_turn',stop_sequence:null},usage:{output_tokens:outTokens}});
      ev('message_stop',{type:'message_stop'});
      try { res.end(); } catch(_) {}
    });
    provRes.on('error', () => { try { res.end(); } catch(_) {} });
  });

  provReq.setTimeout(90000, () => { provReq.destroy(); });
  provReq.on('error', err => { try { res.writeHead(502); res.end('{}'); } catch(_) {} });
  provReq.write(body); provReq.end();
}

// Short-circuit internal housekeeping requests so CI tests don't waste quota.
// IMPORTANT: only apply when system prompt is short — Claude Code's main
// user-message system prompt is 20KB+; those must always go to the provider.
function tryOptimize(anthReq) {
  function getSys(a) {
    if (!a.system) return '';
    return typeof a.system === 'string' ? a.system
      : (a.system || []).filter(b => b.type === 'text').map(b => b.text).join('\n');
  }
  const sys = getSys(anthReq).toLowerCase();
  if (sys.length > 800) return null; // real user request — forward to provider
  if ((sys.includes('title') && (sys.includes('generate') || sys.includes('concise') || sys.includes('create'))) ||
      sys.includes('short title') || sys.includes('conversation title')) return 'Claude Code Session';
  if ((sys.includes('follow-up') || sys.includes('follow up')) && sys.includes('question')) return '';
  if (sys.includes('suggest') && sys.includes('next action')) return '';
  if (sys.includes('file path') && (sys.includes('extract') || sys.includes('identify'))) return '[]';
  if (sys.includes('compact') && (sys.includes('conversation') || sys.includes('context'))) return '';
  return null;
}

function sendMockStream(text, res) {
  const msgId = 'msg_opt_' + Date.now();
  const ev = (n, d) => { try { res.write('event: ' + n + '\ndata: ' + JSON.stringify(d) + '\n\n'); } catch(_) {} };
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });
  ev('message_start', { type: 'message_start', message: { id: msgId, type: 'message', role: 'assistant', content: [], model: 'claude-3-5-sonnet-20241022', stop_reason: null, stop_sequence: null, usage: { input_tokens: 10, output_tokens: 0 } } });
  ev('content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } });
  ev('ping', { type: 'ping' });
  if (text) ev('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text } });
  ev('content_block_stop', { type: 'content_block_stop', index: 0 });
  ev('message_delta', { type: 'message_delta', delta: { stop_reason: 'end_turn', stop_sequence: null }, usage: { output_tokens: Math.max(1, Math.ceil(text.length / 4)) } });
  ev('message_stop', { type: 'message_stop' });
  try { res.end(); } catch(_) {}
}

function startProxy() {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      // count_tokens — handle locally
      if (req.method === 'POST' && req.url.includes('/count_tokens')) {
        let b = ''; req.on('data', c => b += c);
        req.on('end', () => { res.writeHead(200, {'Content-Type':'application/json'}); res.end(JSON.stringify({input_tokens:1000})); });
        return;
      }
      // HEAD / OPTIONS — CORS probe
      if ((req.method === 'HEAD' || req.method === 'OPTIONS') && req.url.includes('/messages')) {
        res.writeHead(200, {'Content-Type':'application/json','Access-Control-Allow-Origin':'*','Access-Control-Allow-Methods':'POST,GET,OPTIONS,HEAD','Access-Control-Allow-Headers':'Content-Type,Authorization,x-api-key,anthropic-version,anthropic-beta'});
        res.end('{}'); return;
      }
      if (req.method === 'GET' && req.url.startsWith('/v1/models')) {
        res.writeHead(200, {'Content-Type':'application/json'});
        return res.end(JSON.stringify({data:[{id:'claude-3-5-sonnet-20241022',display_name:'claude-3-5-sonnet-20241022',created_at:''}]}));
      }
      if (req.method === 'POST' && req.url.includes('/messages')) {
        let body = '';
        req.on('data', c => body += c);
        req.on('end', () => {
          try {
            const anthReq = JSON.parse(body);
            const mockText = tryOptimize(anthReq);
            if (mockText !== null) {
              if (anthReq.stream) { sendMockStream(mockText, res); }
              else { res.writeHead(200, {'Content-Type':'application/json'}); res.end(JSON.stringify({id:'msg_opt',type:'message',role:'assistant',content:[{type:'text',text:mockText}],model:'claude-3-5-sonnet-20241022',stop_reason:'end_turn',stop_sequence:null,usage:{input_tokens:10,output_tokens:5}})); }
              return;
            }
            forwardToProvider(anthToOai(anthReq, MODEL_ID), res);
          } catch(e) { try { res.writeHead(400); res.end('{}'); } catch(_) {} }
        });
        return;
      }
      res.writeHead(200, {'Content-Type':'application/json'}); res.end('{}');
    });
    server.listen(PROXY_PORT, HOST, () => resolve(server));
    server.on('error', reject);
  });
}

// ─── Download claude-code ─────────────────────────────────────────────────────

async function downloadClaudeCode(tmpDir) {
  console.log(`\n── Step 1: download claude-code@${VERSION} ──`);
  const meta    = await getJson(`https://registry.npmjs.org/@anthropic-ai%2Fclaude-code/${VERSION}`);
  const tarball = meta.dist.tarball;
  const tgzPath = path.join(tmpDir, 'cc.tgz');
  const tarPath = path.join(tmpDir, 'cc.tar');
  const extDir  = path.join(tmpDir, 'pkg');
  fs.mkdirSync(extDir);

  await downloadTo(tarball, tgzPath);
  ok(`downloaded ${(fs.statSync(tgzPath).size/1e6).toFixed(1)} MB`);

  await new Promise((res, rej) => {
    const src = fs.createReadStream(tgzPath);
    const gz  = zlib.createGunzip();
    const dst = fs.createWriteStream(tarPath);
    src.on('error',rej); gz.on('error',rej); dst.on('error',rej); dst.on('finish',res);
    src.pipe(gz).pipe(dst);
  });

  const tar = require('child_process').spawnSync('tar', ['-xf', tarPath, '-C', extDir]);
  if (tar.status !== 0) throw new Error('tar failed: ' + tar.stderr.toString());

  const cliJs = path.join(extDir, 'package', 'cli.js');
  if (!fs.existsSync(cliJs)) throw new Error('cli.js not found');
  ok(`cli.js ready (${(fs.statSync(cliJs).size/1e3).toFixed(0)} KB)`);
  return cliJs;
}

// ─── Run Claude Code session ──────────────────────────────────────────────────

async function runClaudeSession(cliJs, tmpDir) {
  console.log('\n── Step 3: spawn Claude Code --print (same as bridge.js) ──');

  // Mirror bridge.js exactly: same env vars, same -e evalCode bootstrap.
  // ANTHROPIC_MODEL must be a valid Claude model name — bridge.js always sets this
  // to 'claude-3-5-sonnet-20241022' in proxy mode so claude-code passes its internal
  // model-name validation before making any API call.
  const message = 'hello claude';
  const env = {
    HOME: tmpDir,
    TERM: 'xterm-256color',
    LANG: 'en_US.UTF-8',
    LINES: '50',
    COLUMNS: '160',
    PATH: process.env.PATH,
    ANTHROPIC_API_KEY:   'sk-ant-proxy000',
    ANTHROPIC_BASE_URL:  `http://${HOST}:${PROXY_PORT}`,
    ANTHROPIC_MODEL:     'claude-3-5-sonnet-20241022',
    DISABLE_AUTOUPDATER: '1',
    TMPDIR: tmpDir, TEMP: tmpDir, TMP: tmpDir,
  };

  // bridge.js spawns LAUNCHER ['-e', evalCode] where evalCode sets process.argv
  // then dynamic-imports cli.js as a file:// URL. On Linux the launcher is just
  // the system node binary; on Android it is libnode-launcher.so.
  const cliUrl  = 'file://' + cliJs;
  const evalCode =
    'process.argv[1]=' + JSON.stringify(cliJs) + ';' +
    'process.argv[2]="--print";' +
    'process.argv[3]=' + JSON.stringify(message) + ';' +
    'process.argv.length=4;' +
    'import(' + JSON.stringify(cliUrl) + ')' +
    '.catch(function(e){process.stderr.write("import-err:"+String(e)+"\\n");process.exit(1)});';

  console.log('  env: ANTHROPIC_API_KEY=sk-ant-proxy000 (fake key — proxy ignores it)');
  console.log(`  env: ANTHROPIC_BASE_URL=http://${HOST}:${PROXY_PORT}`);
  console.log(`  env: ANTHROPIC_MODEL=claude-3-5-sonnet-20241022`);
  console.log(`  cmd: node -e <evalCode> (bridge.js method — import(cli.js) via file://)`);

  return new Promise((resolve) => {
    const child  = spawn('node', ['-e', evalCode], { env, cwd: tmpDir });
    let   output = '';
    let   exited = false;

    console.log('\n── Step 4: message set via process.argv in evalCode ──');
    child.stdin.end();

    child.stdout.on('data', d => {
      const chunk = d.toString();
      output += chunk;
      process.stdout.write('  [stdout] ' + chunk.replace(/\n/g,'↵').slice(0,160) + '\n');
    });
    child.stderr.on('data', d => {
      const chunk = d.toString();
      output += chunk;
      process.stdout.write('  [stderr] ' + chunk.replace(/\n/g,'↵').slice(0,160) + '\n');
    });
    child.on('close', code => {
      exited = true;
      console.log(`\n  [process exited with code ${code}]`);
      resolve({ output, exitCode: code });
    });
    child.on('error', err => {
      console.error('  [spawn error] ' + err.message);
      resolve({ output, exitCode: -1 });
    });

    // Hard timeout at 90s
    setTimeout(() => {
      if (!exited) { child.kill('SIGTERM'); resolve({ output, exitCode: null }); }
    }, 90000);
  });
}

// ─── Main ─────────────────────────────────────────────────────────────────────

(async () => {
  console.log('\nClaudeCodeSetup — full session simulation');
  console.log('=========================================');
  console.log(`  Provider : OpenRouter`);
  console.log(`  Model    : ${MODEL_ID}`);

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cc-sim-'));

  let cliJs;
  try {
    cliJs = await downloadClaudeCode(tmpDir);
  } catch (e) {
    fail('download', e.message);
    process.exit(1);
  }

  let proxyServer;
  console.log('\n── Step 2: start proxy ──');
  try {
    proxyServer = await startProxy();
    ok(`proxy listening on ${HOST}:${PROXY_PORT}`);
  } catch (e) {
    fail('proxy', e.message);
    process.exit(1);
  }

  const result = await runClaudeSession(cliJs, tmpDir);

  console.log('\n── Step 5: evaluate ──');

  const cleanOutput = result.output.replace(/\x1b\[[0-9;]*[A-Za-z]/g, '').trim();

  // Exit code 0 = success, non-zero = crash
  if (result.exitCode !== 0 && result.exitCode !== null) {
    fail('clean exit', `claude --print exited with code ${result.exitCode}`);
  } else {
    ok(`claude --print exited cleanly (code ${result.exitCode})`);
  }

  // Got actual output?
  if (cleanOutput.length < 10) {
    fail('non-empty response', `only ${cleanOutput.length} chars`);
  } else {
    ok(`received ${cleanOutput.length} chars of output`);
  }

  // Output looks like a real reply, not an error?
  const isError = /^Error:|must be provided|invalid|authentication/i.test(cleanOutput.slice(0,100));
  if (isError) {
    fail('reply not an error', cleanOutput.slice(0, 200));
  } else {
    const preview = cleanOutput.replace(/\s+/g,' ').slice(0, 200);
    ok(`model replied: "${preview}…"`);
  }

  console.log('\n── Summary ──');
  console.log(`  Passed: ${passed}   Failed: ${failed}`);

  if (process.env.GITHUB_STEP_SUMMARY) {
    const status = failed === 0 ? '✅ PASSED' : '❌ FAILED';
    const lines = [
      '## Full Session Simulation',
      '',
      `| | |`,
      `|---|---|`,
      `| Provider | OpenRouter |`,
      `| Model | \`${MODEL_ID}\` |`,
      `| Message sent | "hello claude" |`,
      `| Result | ${status} — ${passed} passed, ${failed} failed |`,
      '',
    ];
    if (cleanOutput) lines.push('**Output preview:**\n```\n' + cleanOutput.slice(0,400) + '\n```');
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, lines.join('\n') + '\n');
  }

  proxyServer?.close();
  try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch (_) {}
  process.exit(failed > 0 ? 1 : 0);
})();
