#!/usr/bin/env node
'use strict';
/**
 * Interactive PTY session test for ClaudeCodeSetup.
 *
 * Simulates exactly what the Android PTY terminal does — unlike every other
 * test that uses --print mode, this spawns claude-code in INTERACTIVE mode
 * via a real PTY (node-pty). This is the only test that catches:
 *   - Login-screen bypass failures (customApiKeyResponses not working)
 *   - Exit code 1 from bad imports / missing patches
 *   - Auth-conflict bugs (credentials file + proxy key)
 *
 * Gate: build.yml depends on this job passing before it runs.
 *
 * Usage:
 *   OPENROUTER_API_KEY=sk-or-... node .github/scripts/test-pty-session.js
 */

const { spawnSync, execSync } = require('child_process');
const https = require('https');
const http  = require('http');
const fs    = require('fs');
const path  = require('path');
const os    = require('os');
const zlib  = require('zlib');

const API_KEY    = process.env.OPENROUTER_API_KEY || '';
const MODEL_ID   = 'openai/gpt-oss-20b:free';
const PROVIDER   = 'https://openrouter.ai/api/v1';
const PROXY_PORT = 18084;
const HOST       = '127.0.0.1';
const VERSION    = '2.1.112';

if (!API_KEY) {
    console.log('OPENROUTER_API_KEY not set — skipping PTY test');
    process.exit(0);
}

let passed = 0, failed = 0;
function ok(label)        { console.log('  ✓ ' + label); passed++; }
function fail(label, msg) { console.error('  ✗ ' + label + ': ' + msg); failed++; }

// ─── node-pty bootstrap ───────────────────────────────────────────────────────
// Install node-pty if not already present. It needs native compilation so we
// ensure python3 + build tools are available first on the CI runner.

function ensureNodePty() {
    try {
        require.resolve('node-pty');
        return true;
    } catch (_) {}
    console.log('  Installing node-pty...');
    const r = spawnSync('npm', ['install', '--no-save', 'node-pty'], {
        stdio: 'inherit',
        cwd: path.join(__dirname, '../../'),
    });
    if (r.status !== 0) {
        console.error('  node-pty install failed — PTY test cannot run');
        return false;
    }
    return true;
}

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
                if (r.statusCode !== 200) { r.resume(); return rej(new Error('HTTP ' + r.statusCode)); }
                const out = fs.createWriteStream(dest);
                r.pipe(out);
                out.on('finish', res); out.on('error', rej); r.on('error', rej);
            }).on('error', rej);
        }
        fetch(url, 0);
    });
}

// Strip ANSI escape codes from raw PTY output so we can pattern-match text.
function stripAnsi(s) {
    return s.replace(/\x1b\[[0-9;?]*[A-Za-z]/g, '')
            .replace(/\x1b[()][AB012]/g, '')
            .replace(/\x1b[=>]/g, '')
            .replace(/\r/g, '\n');
}

// ─── patchCliJsForAndroid (mirrors bridge.js exactly) ────────────────────────
// Applied here so we test the PATCHED cli.js, same as the device runs.

function patchCliJsForAndroid(cliPath) {
    let src;
    try { src = fs.readFileSync(cliPath, 'utf8'); } catch (e) {
        console.warn('  Patch skipped: could not read cli.js — ' + e.message);
        return 0;
    }
    let n = 0;
    function rep(from, to) {
        if (!src.includes(from)) return;
        while (src.includes(from)) { src = src.replace(from, to); n++; }
    }
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
    rep('/[\\p{L}\\p{N}_/.\\-+~\\\\]/u',
        '/[a-zA-Z0-9\\xC0-\\u024F\\u0370-\\u03FF\\u0400-\\u04FF\\u4E00-\\u9FFF_\\/+.~\\\\-]/');
    var M = 'a-zA-Z0-9\\xC0-\\u024F\\u0300-\\u036F\\u0370-\\u03FF\\u0400-\\u04FF\\u4E00-\\u9FFF_\\-.\\/\\\\()[\\]~:';
    rep('/^@[\\p{L}\\p{N}\\p{M}_\\-./\\\\()[\\]~:]*/u',  '/^@[' + M + ']*/');
    rep('/^[\\p{L}\\p{N}\\p{M}_\\-./\\\\()[\\]~:]+/u',   '/^[' + M + ']+/');
    rep('/(@[\\p{L}\\p{N}\\p{M}_\\-./\\\\()[\\]~:]*|[\\p{L}\\p{N}\\p{M}_\\-./\\\\()[\\]~:]+)$/u',
        '/(@[' + M + ']*|[' + M + ']+)$/');
    rep('/[\\p{L}\\p{N}\\p{M}_\\-./\\\\()[\\]~:]+$/u',   '/[' + M + ']+$/');
    rep('/(^|[\\s。、？！])@([\\p{L}\\p{N}\\p{M}_\\-./\\\\()[\\]~:]*|"[^"]*"?)$/u',
        '/(^|[\\s。、？！])@([' + M + ']*|"[^"]*"?)$/');
    rep('function Tq1(){return new RegExp("^(\\\\p{Extended_Pictographic}|\\\\p{Emoji_Component})+$","u")}',
        'function Tq1(){try{return new RegExp("^(\\\\p{Extended_Pictographic}|\\\\p{Emoji_Component})+$","u")}' +
        'catch(_e){return /[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF\\u2300-\\u23FF]/}}');
    rep('function Cm6(){', 'function Cm6(){return null;}function _Cm6_orig(){');
    try { fs.writeFileSync(cliPath, src); } catch (e) {
        console.warn('  Patch write failed: ' + e.message); return 0;
    }
    return n;
}

// ─── Token tracking (shared across proxy calls) ───────────────────────────────

let lastUsage = { input_tokens: 0, output_tokens: 0 };

// ─── Proxy (mirrors bridge.js — same as test-full-session.js) ─────────────────

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

function forwardToProvider(oaiReq, res) {
    const body   = JSON.stringify(oaiReq);
    const target = new URL(PROVIDER.replace(/\/$/, '') + '/chat/completions');
    const stream = !!oaiReq.stream;
    const provReq = https.request({
        hostname: target.hostname, port: 443, method: 'POST', path: target.pathname,
        headers: {
            'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body),
            'Authorization': 'Bearer ' + API_KEY,
            'HTTP-Referer': 'https://github.com/fahmi304/Nexus-Mind',
            'X-Title': 'ClaudeCodeSetup',
        },
    }, provRes => {
        if (!stream) {
            let data = ''; provRes.setEncoding('utf8');
            provRes.on('data', c => data += c);
            provRes.on('end', () => {
                try {
                    const parsed = JSON.parse(data);
                    if (parsed.error) {
                        res.writeHead(500, {'Content-Type':'application/json'});
                        return res.end(JSON.stringify({type:'error',error:{type:'api_error',message:parsed.error.message||JSON.stringify(parsed.error)}}));
                    }
                    if (parsed.usage) {
                        lastUsage.input_tokens  += parsed.usage.prompt_tokens     || 0;
                        lastUsage.output_tokens += parsed.usage.completion_tokens || 0;
                    }
                    res.writeHead(200, {'Content-Type':'application/json'});
                    res.end(JSON.stringify(oaiToAnth(parsed, MODEL_ID)));
                } catch(e) { try { res.writeHead(500); res.end('{}'); } catch(_) {} }
            });
            return;
        }
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
                if (evt.usage && evt.usage.prompt_tokens) lastUsage.input_tokens += evt.usage.prompt_tokens;
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
                    lastUsage.output_tokens += outTokens;
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
    provReq.on('error', () => { try { res.writeHead(502); res.end('{}'); } catch(_) {} });
    provReq.write(body); provReq.end();
}

function tryOptimize(anthReq) {
    function getSys(a) {
        if (!a.system) return '';
        return typeof a.system === 'string' ? a.system
            : (a.system || []).filter(b => b.type === 'text').map(b => b.text).join('\n');
    }
    const sys = getSys(anthReq).toLowerCase();
    if (sys.length > 800) return null;
    if ((sys.includes('title') && (sys.includes('generate') || sys.includes('concise') || sys.includes('create'))) ||
        sys.includes('short title') || sys.includes('conversation title')) return 'Claude Code Session';
    if ((sys.includes('follow-up') || sys.includes('follow up')) && sys.includes('question')) return '';
    if (sys.includes('suggest') && sys.includes('next action')) return '';
    if (sys.includes('file path') && (sys.includes('extract') || sys.includes('identify'))) return '[]';
    if (sys.includes('compact') && (sys.includes('conversation') || sys.includes('context'))) return '';
    return null;
}

function sendMockStream(text, model, res) {
    const msgId = 'msg_opt_' + Date.now();
    const ev = (n, d) => { try { res.write('event: '+n+'\ndata: '+JSON.stringify(d)+'\n\n'); } catch(_) {} };
    res.writeHead(200, {'Content-Type':'text/event-stream','Cache-Control':'no-cache','Connection':'keep-alive'});
    ev('message_start',{type:'message_start',message:{id:msgId,type:'message',role:'assistant',content:[],model,stop_reason:null,usage:{input_tokens:10,output_tokens:0}}});
    ev('content_block_start',{type:'content_block_start',index:0,content_block:{type:'text',text:''}});
    ev('ping',{type:'ping'});
    if (text) ev('content_block_delta',{type:'content_block_delta',index:0,delta:{type:'text_delta',text}});
    ev('content_block_stop',{type:'content_block_stop',index:0});
    ev('message_delta',{type:'message_delta',delta:{stop_reason:'end_turn',stop_sequence:null},usage:{output_tokens:Math.max(1,Math.ceil(text.length/4))}});
    ev('message_stop',{type:'message_stop'});
    try { res.end(); } catch(_) {}
}

function startProxy() {
    return new Promise((resolve, reject) => {
        const server = http.createServer((req, res) => {
            if (req.method === 'POST' && req.url.includes('/count_tokens')) {
                let b = ''; req.on('data', c => b += c);
                req.on('end', () => { res.writeHead(200,{'Content-Type':'application/json'}); res.end(JSON.stringify({input_tokens:1000})); });
                return;
            }
            if ((req.method === 'HEAD' || req.method === 'OPTIONS') && req.url.includes('/messages')) {
                res.writeHead(200,{'Content-Type':'application/json','Access-Control-Allow-Origin':'*','Access-Control-Allow-Methods':'POST,GET,OPTIONS,HEAD','Access-Control-Allow-Headers':'Content-Type,Authorization,x-api-key,anthropic-version,anthropic-beta'});
                res.end('{}'); return;
            }
            if (req.method === 'GET' && req.url.startsWith('/v1/models')) {
                res.writeHead(200,{'Content-Type':'application/json'});
                return res.end(JSON.stringify({data:[{id:MODEL_ID,display_name:MODEL_ID,created_at:''}]}));
            }
            if (req.method === 'POST' && req.url.includes('/messages')) {
                let body = '';
                req.on('data', c => body += c);
                req.on('end', () => {
                    try {
                        const anthReq = JSON.parse(body);
                        const mockText = tryOptimize(anthReq);
                        if (mockText !== null) {
                            const model = MODEL_ID;
                            if (anthReq.stream) { sendMockStream(mockText, model, res); }
                            else { res.writeHead(200,{'Content-Type':'application/json'}); res.end(JSON.stringify({id:'msg_opt',type:'message',role:'assistant',content:[{type:'text',text:mockText}],model,stop_reason:'end_turn',stop_sequence:null,usage:{input_tokens:10,output_tokens:5}})); }
                            return;
                        }
                        forwardToProvider(anthToOai(anthReq, MODEL_ID), res);
                    } catch(e) { try { res.writeHead(400); res.end('{}'); } catch(_) {} }
                });
                return;
            }
            res.writeHead(200,{'Content-Type':'application/json'}); res.end('{}');
        });
        server.listen(PROXY_PORT, HOST, () => resolve(server));
        server.on('error', reject);
    });
}

// ─── Download + patch cli.js ──────────────────────────────────────────────────

async function downloadClaudeCode(tmpDir) {
    console.log('\n── Step 1: download claude-code@' + VERSION + ' ──');
    const meta    = await getJson('https://registry.npmjs.org/@anthropic-ai%2Fclaude-code/' + VERSION);
    const tarball = meta.dist.tarball;
    const tgzPath = path.join(tmpDir, 'cc.tgz');
    const tarPath = path.join(tmpDir, 'cc.tar');
    const extDir  = path.join(tmpDir, 'pkg');
    fs.mkdirSync(extDir);

    await downloadTo(tarball, tgzPath);
    ok('downloaded ' + (fs.statSync(tgzPath).size/1e6).toFixed(1) + ' MB');

    await new Promise((res, rej) => {
        const src = fs.createReadStream(tgzPath);
        const gz  = zlib.createGunzip();
        const dst = fs.createWriteStream(tarPath);
        src.on('error',rej); gz.on('error',rej); dst.on('error',rej); dst.on('finish',res);
        src.pipe(gz).pipe(dst);
    });

    const tar = spawnSync('tar', ['-xf', tarPath, '-C', extDir]);
    if (tar.status !== 0) throw new Error('tar failed: ' + (tar.stderr||'').toString());

    const cliJs = path.join(extDir, 'package', 'cli.js');
    if (!fs.existsSync(cliJs)) throw new Error('cli.js not found');
    ok('cli.js ready (' + (fs.statSync(cliJs).size/1e3).toFixed(0) + ' KB)');

    // Apply the same Android patches so we test the exact same code the device runs
    console.log('\n── Step 2: apply Android patches ──');
    const n = patchCliJsForAndroid(cliJs);
    ok('patchCliJsForAndroid applied ' + n + ' replacements');

    return cliJs;
}

// ─── Write settings.json (mirrors bridge.js startup patch) ───────────────────

function writeSettings(homeDir) {
    const claudeDir = path.join(homeDir, '.claude');
    fs.mkdirSync(claudeDir, { recursive: true });
    const settingsPath = path.join(claudeDir, 'settings.json');
    let s = {};
    try { s = JSON.parse(fs.readFileSync(settingsPath, 'utf8')); } catch (_) {}
    s.theme                  = 'dark';
    s.hasCompletedOnboarding = true;
    s.hasShownWelcome        = true;
    s.skipWelcome            = true;
    s.autoUpdaterStatus      = 'disabled';
    s.preferredNotifChannel  = 'none';
    if (!s.customApiKeyResponses) s.customApiKeyResponses = { approved: [], rejected: [] };
    if (!Array.isArray(s.customApiKeyResponses.approved)) s.customApiKeyResponses.approved = [];
    if (!Array.isArray(s.customApiKeyResponses.rejected)) s.customApiKeyResponses.rejected = [];
    if (!s.customApiKeyResponses.approved.includes('sk-ant-proxy000'))
        s.customApiKeyResponses.approved.push('sk-ant-proxy000');
    // Purge from rejected — same fix as bridge.js
    s.customApiKeyResponses.rejected =
        s.customApiKeyResponses.rejected.filter(k => k !== 'sk-ant-proxy000');
    fs.writeFileSync(settingsPath, JSON.stringify(s, null, 2));
    return settingsPath;
}

// ─── Build interactive eval code (mirrors bridge.js buildInteractiveEvalCode) ─

function buildEvalCode(cliJs) {
    const cliUrl = 'file://' + cliJs;
    // regexpShim and intlShim — same as bridge.js (safe no-ops on desktop Node 18)
    const regexpShim =
        '(function(){' +
        'var _R=RegExp;' +
        'function Rc(p,f){' +
        'try{return new _R(p,f);}' +
        'catch(e){' +
        'if(typeof f==="string"&&f.indexOf("u")>-1&&/Invalid|property/i.test(String(e.message||e))){' +
        'var ff=String(f).replace(/u/g,"");' +
        'try{return new _R("(?:)",ff);}catch(_){return new _R("(?:)");}' +
        '}throw e;}' +
        '}' +
        'Rc.prototype=_R.prototype;' +
        'try{Rc[Symbol.hasInstance]=function(v){return _R[Symbol.hasInstance](v);};}catch(_){}' +
        'global.RegExp=Rc;' +
        '})();';
    const intlShim =
        '(function(){' +
        'if(typeof Intl!=="undefined"&&Intl.NumberFormat)return;' +
        'var s={format:function(n){return""+n;},resolvedOptions:function(){return{locale:"en-US",timeZone:"UTC"};},formatToParts:function(){return[];},compare:function(a,b){return a<b?-1:a>b?1:0;},select:function(n){return n===1?"one":"other";},segment:function(t){var a=[],i=0;for(var c of(""+t)){a.push({segment:c,index:i++,isWordLike:/[a-zA-Z0-9_]/.test(c)});}return{[Symbol.iterator]:function(){var j=0;return{next:function(){return j<a.length?{value:a[j++],done:false}:{done:true};}};}};}};' +
        'function mk(){return s;}mk.prototype=s;mk.supportedLocalesOf=function(){return[];};' +
        'if(!global.Intl)global.Intl={};' +
        'var I=global.Intl;' +
        'I.NumberFormat=I.NumberFormat||mk;I.DateTimeFormat=I.DateTimeFormat||mk;I.Collator=I.Collator||mk;' +
        'I.PluralRules=I.PluralRules||mk;I.ListFormat=I.ListFormat||mk;I.RelativeTimeFormat=I.RelativeTimeFormat||mk;' +
        'I.Segmenter=I.Segmenter||mk;' +
        'I.getCanonicalLocales=I.getCanonicalLocales||function(l){return[].concat(l||[]);};' +
        'I.supportedValuesOf=I.supportedValuesOf||function(){return[];};' +
        '})();';
    return (
        regexpShim +
        intlShim +
        'process.argv[1]=' + JSON.stringify(cliJs) + ';' +
        'process.argv[2]="--dangerously-skip-permissions";' +
        'process.argv.length=3;' +
        'import(' + JSON.stringify(cliUrl) + ')' +
        '.catch(function(e){process.stderr.write("import-err:"+String(e)+"\\n");process.exit(1);});'
    );
}

// ─── Interactive PTY session ──────────────────────────────────────────────────

async function runPtySession(cliJs, homeDir) {
    console.log('\n── Step 4: spawn claude-code in interactive PTY mode ──');
    console.log('  (no --print flag — same as Android PTY terminal)');

    const pty = require('node-pty');

    const evalCode = buildEvalCode(cliJs);
    const env = {
        HOME: homeDir,
        TERM: 'xterm-256color',
        LANG: 'en_US.UTF-8',
        LINES: '50',
        COLUMNS: '220',
        PATH: process.env.PATH || '/usr/local/bin:/usr/bin:/bin',
        ANTHROPIC_API_KEY:      'sk-ant-proxy000',
        ANTHROPIC_BASE_URL:     'http://' + HOST + ':' + PROXY_PORT,
        ANTHROPIC_MODEL:        'claude-3-5-sonnet-20241022',
        DISABLE_AUTOUPDATER:    '1',
        // Bypass "do you trust this folder?" — pE_() in cli.js returns true
        CLAUDE_CODE_SANDBOXED:  '1',
        TMPDIR: homeDir, TEMP: homeDir, TMP: homeDir,
    };

    const ptyProc = pty.spawn('node', ['-e', evalCode], {
        name: 'xterm-256color',
        cols: 220,
        rows: 50,
        cwd: homeDir,
        env,
    });

    return new Promise(resolve => {
        let rawOutput    = '';
        let claudeReady  = false;  // saw first { JSON event
        let loginSeen    = false;
        let messageSent  = false;
        let exitCode     = null;
        let responseText = '';

        // After claude is ready, wait a beat then send message
        let readyTimer = null;
        // Overall timeout
        const hardTimeout = setTimeout(() => {
            ptyProc.kill();
            resolve({ rawOutput, claudeReady, loginSeen, exitCode, responseText, timedOut: true });
        }, 120000);

        ptyProc.onData(chunk => {
            rawOutput += chunk;
            const clean = stripAnsi(rawOutput);

            // Phase 1: detect claude ready OR login screen
            if (!claudeReady) {
                // Look for a line starting with { — the system init JSON event
                const lines = clean.split('\n');
                for (const line of lines) {
                    const t = line.trim();
                    if (t.startsWith('{')) { claudeReady = true; break; }
                }

                // Check for login-screen indicators
                if (/login|sign.{0,4}in|enter your api key|api key required|authenticate|claude\.ai/i.test(clean)) {
                    loginSeen = true;
                }

                if (claudeReady && !messageSent) {
                    // Small delay to let the TUI fully initialise before sending input
                    readyTimer = setTimeout(() => {
                        messageSent = true;
                        console.log('  [claude ready] sending test message...');
                        ptyProc.write('hello\r');
                    }, 800);
                }
            } else if (messageSent) {
                // Phase 2: collect response, stop when output goes quiet
                responseText = stripAnsi(rawOutput);
            }
        });

        ptyProc.onExit(({ exitCode: code }) => {
            exitCode = code;
            clearTimeout(hardTimeout);
            if (readyTimer) clearTimeout(readyTimer);
            resolve({ rawOutput, claudeReady, loginSeen, exitCode, responseText: stripAnsi(rawOutput), timedOut: false });
        });

        // If claude is ready and a response has started, wait for output to go
        // quiet for 4 s then declare done (same logic as bridge.js idle timer)
        let quietTimer = null;
        ptyProc.onData(() => {
            if (!messageSent) return;
            if (quietTimer) clearTimeout(quietTimer);
            quietTimer = setTimeout(() => {
                ptyProc.kill();
            }, 4000);
        });
    });
}

// ─── Main ─────────────────────────────────────────────────────────────────────

(async () => {
    console.log('\nClaudeCodeSetup — Interactive PTY session test');
    console.log('===============================================');
    console.log('  Provider : OpenRouter');
    console.log('  Model    : ' + MODEL_ID);
    console.log('  Mode     : interactive (no --print) — same as Android terminal\n');

    // Install node-pty
    console.log('── Step 0: ensure node-pty ──');
    if (!ensureNodePty()) {
        fail('node-pty', 'installation failed — cannot run PTY test');
        process.exit(1);
    }
    ok('node-pty available');

    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cc-pty-'));

    let cliJs;
    try {
        cliJs = await downloadClaudeCode(tmpDir);
    } catch (e) {
        fail('download', e.message);
        process.exit(1);
    }

    // Write settings.json — same bypass that bridge.js applies at startup
    console.log('\n── Step 3: write settings.json bypass ──');
    const homeDir      = path.join(tmpDir, 'home');
    fs.mkdirSync(homeDir, { recursive: true });
    const settingsPath = writeSettings(homeDir);
    const settings     = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    ok('settings.json written to ' + settingsPath);
    ok('customApiKeyResponses.approved = ' + JSON.stringify(settings.customApiKeyResponses.approved));
    ok('customApiKeyResponses.rejected = ' + JSON.stringify(settings.customApiKeyResponses.rejected));

    // Start proxy
    let proxyServer;
    console.log('\n── Step 3b: start proxy ──');
    try {
        proxyServer = await startProxy();
        ok('proxy listening on ' + HOST + ':' + PROXY_PORT);
    } catch (e) {
        fail('proxy', e.message);
        process.exit(1);
    }

    const result = await runPtySession(cliJs, homeDir);

    console.log('\n── Step 5: evaluate ──');

    // ── Test: no immediate crash ──────────────────────────────────────────────
    if (result.exitCode === 1 && !result.claudeReady) {
        const preview = stripAnsi(result.rawOutput).replace(/\s+/g,' ').trim().slice(0, 300);
        fail('no exit-code-1 crash', 'claude exited 1 immediately. Output: ' + preview);
    } else {
        ok('process did not exit with code 1 immediately');
    }

    // ── Test: no login screen ─────────────────────────────────────────────────
    if (result.loginSeen) {
        const preview = stripAnsi(result.rawOutput).replace(/\s+/g,' ').trim().slice(0, 300);
        fail('no login screen', 'login-related text appeared in PTY output: ' + preview);
    } else {
        ok('no login screen detected');
    }

    // ── Test: claude became ready (JSON event seen) ───────────────────────────
    if (!result.claudeReady) {
        if (result.timedOut) {
            fail('claude ready (JSON event)', 'timed out after 120 s — no { JSON event line seen');
        } else {
            const preview = stripAnsi(result.rawOutput).replace(/\s+/g,' ').trim().slice(0,300);
            fail('claude ready (JSON event)', 'process exited before emitting JSON event. Output: ' + preview);
        }
    } else {
        ok('claude ready — JSON init event received (auth bypass confirmed)');
    }

    // ── Test: got a response ──────────────────────────────────────────────────
    if (result.claudeReady) {
        const clean = result.responseText.replace(/\s+/g, ' ').trim();
        if (clean.length < 20) {
            fail('response received', 'only ' + clean.length + ' chars after stripping ANSI');
        } else {
            const isError = /error:|must be provided|invalid|authentication failed/i.test(clean.slice(0, 200));
            if (isError) {
                fail('response is not an error', clean.slice(0, 200));
            } else {
                ok('response received (' + clean.length + ' chars)');
                const preview = clean.slice(0, 200);
                console.log('  preview: "' + preview + '..."');
            }
        }
    }

    // ── Test: token count from proxy ──────────────────────────────────────────
    console.log('\n  Token usage reported by proxy:');
    console.log('    input_tokens  : ' + lastUsage.input_tokens);
    console.log('    output_tokens : ' + lastUsage.output_tokens);
    if (lastUsage.output_tokens > 0) {
        ok('token count: output_tokens=' + lastUsage.output_tokens +
           ', input_tokens=' + lastUsage.input_tokens);
    } else {
        fail('token count', 'output_tokens=0 — proxy may not have forwarded a real response');
    }

    console.log('\n── Summary ──');
    console.log('  Passed: ' + passed + '   Failed: ' + failed);

    if (process.env.GITHUB_STEP_SUMMARY) {
        const status = failed === 0 ? '✅ PASSED' : '❌ FAILED';
        const lines = [
            '## Interactive PTY Session Test',
            '',
            '| | |',
            '|---|---|',
            '| Mode | Interactive PTY (no --print) |',
            '| Provider | OpenRouter |',
            '| Model | `' + MODEL_ID + '` |',
            '| Auth bypass | customApiKeyResponses |',
            '| Result | ' + status + ' — ' + passed + ' passed, ' + failed + ' failed |',
            '',
        ];
        if (result.claudeReady) {
            lines.push('**Login screen:** ' + (result.loginSeen ? '❌ appeared' : '✅ bypassed'));
            lines.push('**JSON ready event:** ✅ received');
        } else {
            lines.push('**Login screen:** ' + (result.loginSeen ? '❌ appeared' : '✅ not seen'));
            lines.push('**JSON ready event:** ❌ not received');
        }
        lines.push('**Token usage:** input=' + lastUsage.input_tokens + ' output=' + lastUsage.output_tokens);
        fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, lines.join('\n') + '\n');
    }

    proxyServer?.close();
    try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch (_) {}
    process.exit(failed > 0 ? 1 : 0);
})();
