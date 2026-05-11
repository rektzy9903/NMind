#!/usr/bin/env node
'use strict';
/**
 * ARM64 Android simulation — mirrors bridge.js exactly.
 *
 * Unlike test-full-session.js, this script:
 *   1. Applies patchCliJsForAndroid() (same as bridge.js)
 *   2. Uses the exact evalCode from bridge.js:
 *      - RegExp shim (catches dynamic \p{} calls)
 *      - unhandledRejection handler (logs the actual crash reason)
 *      - import-resolved hook (confirms ESM load succeeded)
 *      - exit-event hook
 *   3. Reports all diagnostic log lines so we can see what fails
 *
 * Limitation: uses system Node.js (v25), not libnode.so v18.20.4.
 * The \p{} regex works natively on v25, so patches/shim won't trigger —
 * but any other crash will surface via the new diagnostic hooks.
 *
 * Usage:
 *   OPENROUTER_API_KEY=sk-or-... node test-android-sim.js
 */

const { spawn, spawnSync } = require('child_process');
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

if (!API_KEY) { console.error('OPENROUTER_API_KEY required'); process.exit(1); }

let passed = 0, failed = 0;
const ok   = l => { console.log(`  ✓ ${l}`); passed++; };
const fail = (l, m) => { console.error(`  ✗ ${l}: ${m}`); failed++; };

// ─── patchCliJsForAndroid (copied exactly from bridge.js) ────────────────────

function patchCliJsForAndroid(cliPath) {
    console.log('  Patching cli.js for Android (removing \\p{} regex property escapes)...');
    let src = fs.readFileSync(cliPath, 'utf8');
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
    const PS = '!"#$%&\'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~\\xA2-\\xBF';
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
    const M = 'a-zA-Z0-9\\xC0-\\u024F\\u0300-\\u036F\\u0370-\\u03FF\\u0400-\\u04FF\\u4E00-\\u9FFF_\\-.\\/\\\\()[\\]~:';
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

    fs.writeFileSync(cliPath, src);
    console.log(`  Patch complete: ${n} replacements applied`);
}

// ─── Proxy (mirrors bridge.js) ────────────────────────────────────────────────

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

function anthToOai(a, model) {
    const msgs = [];
    if (a.system) {
        const text = typeof a.system === 'string' ? a.system
            : (a.system || []).filter(b => b.type === 'text').map(b => b.text).join('\n');
        if (text) msgs.push({ role: 'system', content: text });
    }
    for (const m of (a.messages || [])) {
        if (typeof m.content === 'string') msgs.push({ role: m.role, content: m.content });
        else msgs.push({ role: m.role, content: (m.content||[]).filter(b=>b.type==='text').map(b=>b.text).join('') });
    }
    return { model, messages: msgs, max_tokens: a.max_tokens || 1024, stream: !!a.stream };
}

function oaiToAnth(oai, model) {
    const choice = (oai.choices||[])[0]||{};
    const text = (choice.message||{}).content||'';
    return { id:'msg_'+Date.now(), type:'message', role:'assistant',
        content:[{type:'text',text}], model, stop_reason:'end_turn', stop_sequence:null,
        usage:{input_tokens:(oai.usage||{}).prompt_tokens||0, output_tokens:(oai.usage||{}).completion_tokens||0} };
}

function sendMockStream(text, res) {
    const msgId = 'msg_opt_' + Date.now();
    const ev = (n, d) => { try { res.write('event: '+n+'\ndata: '+JSON.stringify(d)+'\n\n'); } catch(_){} };
    res.writeHead(200, {'Content-Type':'text/event-stream','Cache-Control':'no-cache'});
    ev('message_start',{type:'message_start',message:{id:msgId,type:'message',role:'assistant',content:[],model:'claude-3-5-sonnet-20241022',stop_reason:null,usage:{input_tokens:10,output_tokens:0}}});
    ev('content_block_start',{type:'content_block_start',index:0,content_block:{type:'text',text:''}});
    ev('ping',{type:'ping'});
    if (text) ev('content_block_delta',{type:'content_block_delta',index:0,delta:{type:'text_delta',text}});
    ev('content_block_stop',{type:'content_block_stop',index:0});
    ev('message_delta',{type:'message_delta',delta:{stop_reason:'end_turn',stop_sequence:null},usage:{output_tokens:1}});
    ev('message_stop',{type:'message_stop'});
    try { res.end(); } catch(_) {}
}

function forwardToProvider(oaiReq, res) {
    const body   = JSON.stringify(oaiReq);
    const target = new URL(PROVIDER.replace(/\/$/, '') + '/chat/completions');
    const stream = !!oaiReq.stream;
    const provReq = https.request({
        hostname: target.hostname, port: 443, method: 'POST', path: target.pathname,
        headers: {
            'Content-Type': 'application/json',
            'Content-Length': Buffer.byteLength(body),
            'Authorization': 'Bearer ' + API_KEY,
            'HTTP-Referer': 'https://github.com/rektzy9903/ClaudeCodeSetup',
            'X-Title': 'ClaudeCodeSetup',
        },
    }, provRes => {
        console.log(`  [proxy→provider] HTTP ${provRes.statusCode}`);
        if (!stream) {
            let data = ''; provRes.setEncoding('utf8');
            provRes.on('data', c => data += c);
            provRes.on('end', () => {
                try {
                    const parsed = JSON.parse(data);
                    if (parsed.error) { res.writeHead(500,{'Content-Type':'application/json'}); return res.end(JSON.stringify({type:'error',error:{type:'api_error',message:parsed.error.message||''}})); }
                    res.writeHead(200,{'Content-Type':'application/json'}); res.end(JSON.stringify(oaiToAnth(parsed, MODEL_ID)));
                } catch(e) { res.writeHead(500); res.end('{}'); }
            });
            return;
        }
        res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache','Connection':'keep-alive'});
        const msgId = 'msg_' + Date.now();
        let buf = '', headersSent = false, outTokens = 0;
        const ev = (n, d) => { try { res.write('event: '+n+'\ndata: '+JSON.stringify(d)+'\n\n'); } catch(_){} };
        provRes.setEncoding('utf8');
        provRes.on('data', chunk => {
            buf += chunk;
            const lines = buf.split('\n'); buf = lines.pop();
            for (const line of lines) {
                const t = line.trim();
                if (!t.startsWith('data:')) continue;
                const raw = t.slice(5).trim(); if (raw==='[DONE]') continue;
                let evt; try { evt=JSON.parse(raw); } catch(_){continue;}
                if (!headersSent) {
                    headersSent = true;
                    ev('message_start',{type:'message_start',message:{id:msgId,type:'message',role:'assistant',content:[],model:MODEL_ID,stop_reason:null,usage:{input_tokens:0,output_tokens:0}}});
                    ev('content_block_start',{type:'content_block_start',index:0,content_block:{type:'text',text:''}});
                    ev('ping',{type:'ping'});
                }
                const delta=((evt.choices||[])[0]||{}).delta||{};
                const text=delta.content||'';
                const fin=((evt.choices||[])[0]||{}).finish_reason;
                if(text){outTokens++;ev('content_block_delta',{type:'content_block_delta',index:0,delta:{type:'text_delta',text}});}
                if(fin){ev('content_block_stop',{type:'content_block_stop',index:0});ev('message_delta',{type:'message_delta',delta:{stop_reason:'end_turn',stop_sequence:null},usage:{output_tokens:outTokens}});ev('message_stop',{type:'message_stop'});}
            }
        });
        provRes.on('end', () => {
            if(!headersSent){ev('message_start',{type:'message_start',message:{id:msgId,type:'message',role:'assistant',content:[],model:MODEL_ID,stop_reason:null,usage:{input_tokens:0,output_tokens:0}}});ev('content_block_start',{type:'content_block_start',index:0,content_block:{type:'text',text:''}});}
            ev('content_block_stop',{type:'content_block_stop',index:0});ev('message_delta',{type:'message_delta',delta:{stop_reason:'end_turn',stop_sequence:null},usage:{output_tokens:outTokens}});ev('message_stop',{type:'message_stop'});
            try { res.end(); } catch(_) {}
        });
    });
    provReq.setTimeout(90000, ()=>provReq.destroy());
    provReq.on('error', ()=>{ try{res.writeHead(502);res.end('{}');}catch(_){} });
    provReq.write(body); provReq.end();
}

function startProxy(logPath) {
    return new Promise((resolve, reject) => {
        const server = http.createServer((req, res) => {
            fs.appendFileSync(logPath, '[proxy] ' + req.method + ' ' + req.url + '\n');
            if (req.method === 'POST' && req.url.includes('/count_tokens')) {
                let b=''; req.on('data',c=>b+=c);
                req.on('end',()=>{ res.writeHead(200,{'Content-Type':'application/json'}); res.end(JSON.stringify({input_tokens:1000})); });
                return;
            }
            if ((req.method==='HEAD'||req.method==='OPTIONS') && req.url.includes('/messages')) {
                res.writeHead(200,{'Content-Type':'application/json','Access-Control-Allow-Origin':'*','Access-Control-Allow-Methods':'POST,GET,OPTIONS,HEAD','Access-Control-Allow-Headers':'Content-Type,Authorization,x-api-key,anthropic-version,anthropic-beta'});
                res.end('{}'); return;
            }
            if (req.method==='GET' && req.url.startsWith('/v1/models')) {
                res.writeHead(200,{'Content-Type':'application/json'});
                return res.end(JSON.stringify({data:[{id:'claude-3-5-sonnet-20241022',display_name:'claude-3-5-sonnet-20241022',created_at:''}]}));
            }
            if (req.method==='POST' && req.url.includes('/messages')) {
                let body=''; req.on('data',c=>body+=c);
                req.on('end',()=>{
                    try {
                        const anthReq=JSON.parse(body);
                        const mock=tryOptimize(anthReq);
                        if(mock!==null){ if(anthReq.stream){sendMockStream(mock,res);}else{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({id:'msg_opt',type:'message',role:'assistant',content:[{type:'text',text:mock}],model:'claude-3-5-sonnet-20241022',stop_reason:'end_turn',stop_sequence:null,usage:{input_tokens:10,output_tokens:5}}));}return;}
                        forwardToProvider(anthToOai(anthReq,MODEL_ID),res);
                    } catch(e){res.writeHead(400);res.end('{}');}
                });
                return;
            }
            res.writeHead(200,{'Content-Type':'application/json'}); res.end('{}');
        });
        server.listen(PROXY_PORT, HOST, () => resolve(server));
        server.on('error', reject);
    });
}

// ─── Download helpers ─────────────────────────────────────────────────────────

function httpsGet(url, hops) {
    hops = hops || 0;
    return new Promise((res, rej) => {
        https.get(url, r => {
            if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) {
                if (hops > 5) return rej(new Error('too many redirects'));
                r.resume(); return httpsGet(r.headers.location, hops+1).then(res).catch(rej);
            }
            res(r);
        }).on('error', rej);
    });
}

function getJson(url) {
    return new Promise(async (res, rej) => {
        try {
            const r = await httpsGet(url);
            if (r.statusCode !== 200) { r.resume(); return rej(new Error('HTTP ' + r.statusCode)); }
            let b=''; r.setEncoding('utf8'); r.on('data',c=>b+=c);
            r.on('end',()=>{ try{res(JSON.parse(b));}catch(e){rej(e);} }); r.on('error',rej);
        } catch(e) { rej(e); }
    });
}

function downloadTo(url, dest) {
    return new Promise(async (res, rej) => {
        try {
            const r = await httpsGet(url);
            if (r.statusCode !== 200) { r.resume(); return rej(new Error('HTTP ' + r.statusCode)); }
            const out = fs.createWriteStream(dest);
            r.pipe(out); out.on('finish', res); out.on('error', rej); r.on('error', rej);
        } catch(e) { rej(e); }
    });
}

// ─── Build exact evalCode from bridge.js ─────────────────────────────────────

function buildEvalCode(cliJs, logPath, message) {
    const cliUrl = 'file://' + cliJs;
    const exitLogPath = JSON.stringify(logPath);

    // RegExp shim — same as bridge.js regexpShim
    const regexpShim =
        '(function(){' +
        'var _R=RegExp,_lp=' + exitLogPath + ';' +
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

    return (
        'process.stderr.write("[eval-ok]\\n");' +
        'process.on("exit",function(code){' +
        'try{var fs=require("fs");' +
        'fs.appendFileSync(' + exitLogPath + ',"[exit-event] code="+code+"\\n");}' +
        'catch(_e){}});' +
        'process.on("unhandledRejection",function(r){' +
        'try{require("fs").appendFileSync(' + exitLogPath + ',' +
        '"[unhandledRejection] "+String(r&&(r.stack||r.message)||r).slice(0,600)+"\\n");}' +
        'catch(_){}});' +
        regexpShim +
        'process.argv[1]=' + JSON.stringify(cliJs) + ';' +
        'process.argv[2]="--print";' +
        'process.argv[3]=' + JSON.stringify(message) + ';' +
        'process.argv.length=4;' +
        'import(' + JSON.stringify(cliUrl) + ')' +
        '.then(function(){' +
        'try{require("fs").appendFileSync(' + exitLogPath + ',"[import-resolved]\\n");}catch(_){}})' +
        '.catch(function(e){process.stderr.write("import-err:"+String(e)+"\\n");process.exit(1)});'
    );
}

// ─── Main ─────────────────────────────────────────────────────────────────────

(async () => {
    console.log('\nClaudeCodeSetup — Android ARM64 simulation (bridge.js exact match)');
    console.log('====================================================================');
    console.log(`  Node.js  : ${process.version} ${process.arch} ${process.platform}`);
    console.log(`  Provider : OpenRouter`);
    console.log(`  Model    : ${MODEL_ID}`);

    const tmpDir  = fs.mkdtempSync(path.join(os.tmpdir(), 'cc-android-sim-'));
    const logPath = path.join(tmpDir, 'sim.log');
    fs.writeFileSync(logPath, '');
    console.log(`  Work dir : ${tmpDir}`);
    console.log(`  Log file : ${logPath}`);

    // ── Step 1: download cli.js v2.1.112 ──────────────────────────────────────
    console.log(`\n── Step 1: download claude-code@${VERSION} ──`);
    let cliJs;
    try {
        const meta    = await getJson(`https://registry.npmjs.org/@anthropic-ai%2Fclaude-code/${VERSION}`);
        const tarball = meta.dist.tarball;
        const tgzPath = path.join(tmpDir, 'cc.tgz');
        const tarPath = path.join(tmpDir, 'cc.tar');
        const extDir  = path.join(tmpDir, 'pkg');
        fs.mkdirSync(extDir);

        process.stdout.write(`  Downloading ${tarball.split('/').pop()}...`);
        await downloadTo(tarball, tgzPath);
        console.log(` ${(fs.statSync(tgzPath).size/1e6).toFixed(1)} MB`);
        ok('download complete');

        await new Promise((res, rej) => {
            const src = fs.createReadStream(tgzPath);
            const gz  = zlib.createGunzip();
            const dst = fs.createWriteStream(tarPath);
            src.on('error',rej); gz.on('error',rej); dst.on('error',rej); dst.on('finish',res);
            src.pipe(gz).pipe(dst);
        });

        const tar = spawnSync('tar', ['-xf', tarPath, '-C', extDir]);
        if (tar.status !== 0) throw new Error('tar: ' + tar.stderr.toString());
        try { fs.unlinkSync(tgzPath); fs.unlinkSync(tarPath); } catch(_) {}

        cliJs = path.join(extDir, 'package', 'cli.js');
        if (!fs.existsSync(cliJs)) throw new Error('cli.js not found after extract');
        ok(`cli.js ready — ${(fs.statSync(cliJs).size/1e3).toFixed(0)} KB`);
    } catch (e) { fail('download', e.message); process.exit(1); }

    // ── Step 2: patch (same as bridge.js patchCliJsForAndroid) ────────────────
    console.log('\n── Step 2: patchCliJsForAndroid ──');
    try { patchCliJsForAndroid(cliJs); ok('patch applied'); }
    catch (e) { fail('patch', e.message); }

    // ── Step 3: start proxy ───────────────────────────────────────────────────
    console.log('\n── Step 3: start proxy ──');
    let proxyServer;
    try { proxyServer = await startProxy(logPath); ok(`proxy on ${HOST}:${PROXY_PORT}`); }
    catch (e) { fail('proxy', e.message); process.exit(1); }

    // ── Step 4: spawn cli.js with bridge.js exact evalCode ────────────────────
    console.log('\n── Step 4: spawn cli.js --print "hello claude" ──');
    const message = 'hello claude';
    const env = {
        HOME:               tmpDir,
        TERM:               'xterm-256color',
        LANG:               'en_US.UTF-8',
        LINES:              '50',
        COLUMNS:            '160',
        PATH:               process.env.PATH,
        ANTHROPIC_API_KEY:  'sk-ant-proxy000',
        ANTHROPIC_BASE_URL: `http://${HOST}:${PROXY_PORT}`,
        ANTHROPIC_MODEL:    'claude-3-5-sonnet-20241022',
        DISABLE_AUTOUPDATER:'1',
        TMPDIR: tmpDir, TEMP: tmpDir, TMP: tmpDir,
    };

    const evalCode = buildEvalCode(cliJs, logPath, message);

    console.log('  launcher  : node (v25, not libnode.so v18 — \\p{} works natively here)');
    console.log(`  bootstrap : evalCode with RegExp shim + unhandledRejection + import-resolved`);
    console.log(`  message   : "${message}"`);

    const result = await new Promise(resolve => {
        const child = spawn('node', ['-e', evalCode], { env, cwd: tmpDir });
        child.stdin.end();
        let out = '', exited = false;

        child.stdout.on('data', d => {
            const s = d.toString();
            out += s;
            fs.appendFileSync(logPath, '[stdout] ' + s.slice(0, 400) + '\n');
            process.stdout.write('  [stdout] ' + s.replace(/\n/g,'↵').slice(0, 200) + '\n');
        });
        child.stderr.on('data', d => {
            const s = d.toString();
            out += s;
            fs.appendFileSync(logPath, '[stderr] ' + s.slice(0, 800) + '\n');
            process.stdout.write('  [stderr] ' + s.replace(/\n/g,'↵').slice(0, 200) + '\n');
        });
        child.on('close', code => {
            exited = true;
            fs.appendFileSync(logPath, '[process-close] code=' + code + '\n');
            console.log(`\n  [process exited with code ${code}]`);
            resolve({ out, exitCode: code });
        });
        child.on('error', err => { resolve({ out, exitCode: -1, spawnErr: err.message }); });
        setTimeout(() => { if (!exited) { child.kill(); resolve({ out, exitCode: null }); } }, 90000);
    });

    // ── Step 5: read diagnostic log ───────────────────────────────────────────
    console.log('\n── Step 5: diagnostic log ──');
    const diagLog = fs.readFileSync(logPath, 'utf8');
    const diagLines = diagLog.split('\n').filter(l => l.trim());
    for (const l of diagLines) {
        const tag = l.startsWith('[proxy]') ? '\x1b[2m' :
                    l.startsWith('[unhandledRejection]') ? '\x1b[31m' :
                    l.startsWith('[import-resolved]') ? '\x1b[32m' :
                    l.startsWith('[exit-event]') ? '\x1b[33m' :
                    l.startsWith('[regex-compat]') ? '\x1b[35m' : '';
        console.log('  ' + tag + l + (tag ? '\x1b[0m' : ''));
    }

    // ── Step 6: assertions ────────────────────────────────────────────────────
    console.log('\n── Step 6: assertions ──');

    if (result.spawnErr) { fail('spawn', result.spawnErr); }

    const hasImportResolved    = diagLog.includes('[import-resolved]');
    const hasUnhandledRejection = diagLog.includes('[unhandledRejection]');
    const hasProxyHit          = diagLog.includes('[proxy] POST /v1/messages');
    const hasRegexCompat       = diagLog.includes('[regex-compat]');
    const exitOk               = result.exitCode === 0;

    if (hasImportResolved) ok('cli.js ESM module loaded (import-resolved)');
    else                   fail('import-resolved missing', 'cli.js failed to load or import() rejected');

    if (hasProxyHit) ok('cli.js reached proxy (POST /v1/messages)');
    else             fail('proxy never hit', 'cli.js exited before making any API call');

    if (hasUnhandledRejection) {
        const reason = diagLog.split('[unhandledRejection]')[1].split('\n')[0].trim();
        fail('unhandledRejection detected', reason.slice(0, 300));
    } else ok('no unhandledRejection');

    if (hasRegexCompat) {
        const patterns = diagLog.split('\n').filter(l => l.includes('[regex-compat]')).join(', ');
        fail('regex-compat triggered (\\p{} runtime failures)', patterns);
    } else ok('no runtime \\p{} regex failures');

    if (exitOk) ok('clean exit (code 0)');
    else        fail('exit code', String(result.exitCode));

    const cleanOut = result.out.replace(/\x1b\[[0-9;]*[A-Za-z]/g, '').trim();
    if (cleanOut.length >= 10) {
        ok(`received ${cleanOut.length} chars — "${cleanOut.replace(/\s+/g,' ').slice(0,150)}"`);
    } else {
        fail('empty output', `only ${cleanOut.length} chars`);
    }

    console.log(`\n── Summary ──`);
    console.log(`  Passed: ${passed}   Failed: ${failed}`);
    console.log(`  Full log: ${logPath}`);

    proxyServer?.close();
    process.exit(failed > 0 ? 1 : 0);
})();
