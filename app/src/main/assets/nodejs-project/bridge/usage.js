// bridge/usage.js — Usage meter. Extracted from bridge.js (Phase 2 #2 of the
// bridge split). Factory: pass FILES_DIR; returns { record, load, flush,
// labelFor, today, FILE }. Only deps are fs/path/FILES_DIR — see CLAUDE.md inv 82.
const fs = require('fs');
const path = require('path');

module.exports = function createUsage(FILES_DIR) {
    const USAGE_FILE = path.join(FILES_DIR, 'usage_stats.json');
    let data = null, dirty = false, flushTimer = null;
    function load() {
        if (data) return data;
        try { data = JSON.parse(fs.readFileSync(USAGE_FILE, 'utf8')); } catch (_) { data = {}; }
        if (!data || typeof data !== 'object') data = {};
        if (!data.providers) data.providers = {};
        return data;
    }
    function flush() {
        flushTimer = null;
        if (!dirty) return;
        dirty = false;
        try { fs.writeFileSync(USAGE_FILE, JSON.stringify(data)); } catch (_) {}
    }
    function today() {
        const d = new Date(), z = n => (n < 10 ? '0' + n : '' + n);
        return d.getFullYear() + '-' + z(d.getMonth() + 1) + '-' + z(d.getDate());
    }
    // Stable short provider key from the upstream URL.
    function labelFor(pUrl) {
        const u = String(pUrl || '').toLowerCase();
        if (!u) return 'unknown';
        if (u.includes('codewhisperer')) return 'kiro';
        if (u.includes('api.anthropic.com')) return 'anthropic_api';
        if (u.includes('generativelanguage') || u.includes('gemini')) return 'gemini';
        if (u.includes('groq')) return 'groq';
        if (u.includes('openrouter')) return 'openrouter';
        if (u.includes('deepseek')) return 'deepseek';
        if (u.includes('moonshot')) return 'kimi';
        if (u.includes('dashscope') || u.includes('aliyun')) return 'qwen';
        if (u.includes('mistral')) return 'mistral';
        if (u.includes('nvidia') || u.includes('nim')) return 'nvidia_nim';
        if (u.includes('localhost') || u.includes('127.0.0.1') || u.includes(':11434')) return 'ollama';
        try { return new URL(u).hostname.replace(/^api\./, '').split('.')[0] || 'unknown'; } catch (_) { return 'unknown'; }
    }
    function record(pUrl, model, inTok, outTok, cacheRead, cacheWrite) {
        try {
            const d = load(), prov = labelFor(pUrl), day = today();
            const p = d.providers[prov] || (d.providers[prov] = {});
            const m = p[model || 'unknown'] || (p[model || 'unknown'] = {});
            const e = m[day] || (m[day] = { inTok: 0, outTok: 0, cacheRead: 0, cacheWrite: 0, req: 0 });
            e.inTok += Math.max(0, inTok | 0);
            e.outTok += Math.max(0, outTok | 0);
            e.cacheRead = (e.cacheRead | 0) + Math.max(0, cacheRead | 0);
            e.cacheWrite = (e.cacheWrite | 0) + Math.max(0, cacheWrite | 0);
            e.req += 1;
            dirty = true;
            if (!flushTimer) flushTimer = setTimeout(flush, 4000);
        } catch (_) {}
    }
    return { record, load, flush, labelFor, today, FILE: USAGE_FILE };
};
