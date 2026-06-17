// bridge/format.js — Anthropic<->OpenAI format conversion + Gemini
// thought_signature round-trip (inv 67). Extracted from bridge.js (Phase 2
// #3 of the split). Factory: pass the bridge `log`; returns the converters.
// thoughtSigStore is kept module-internal (shared by the converters).

module.exports = function createFormat(log) {
// ── Gemini thought_signature round-trip ──────────────────────────────────────
// Gemini 3.x models return an opaque `thought_signature` on every function call
// and REQUIRE it echoed back, attached to the same call, on subsequent turns —
// otherwise the request 400s ("Function call is missing a thought_signature in
// functionCall parts"). claude-code's Anthropic-format history only preserves
// {id,name,input}, so the signature is lost on the next turn and tool use breaks
// for the whole conversation. We stash signatures by tool_call id at capture time
// and re-attach them in anthToOai when the same call reappears in history.
const thoughtSigStore = new Map(); // tool_call id → thought_signature
function storeThoughtSig(id, sig) {
    if (!id || !sig) return;
    if (thoughtSigStore.size > 500) { // bound memory; drop oldest
        const first = thoughtSigStore.keys().next().value;
        if (first !== undefined) thoughtSigStore.delete(first);
    }
    thoughtSigStore.set(id, sig);
}
// Defensive: Gemini's OpenAI-compat layer has surfaced the signature in a few
// shapes across versions — scan the documented + likely locations.
function extractThoughtSig(tc, delta, choice) {
    const ec = (o) => o && o.extra_content && o.extra_content.google && o.extra_content.google.thought_signature;
    return (tc && (ec(tc) || tc.thought_signature || tc.thoughtSignature || (tc.function && tc.function.thought_signature)))
        || (delta && ec(delta))
        || (choice && choice.message && ec(choice.message))
        || null;
}

// Convert Anthropic Messages request → OpenAI Chat Completions request
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
            continue;
        }
        const blocks = m.content || [];
        const textBlocks    = blocks.filter(b => b.type === 'text');
        const imageBlocks   = blocks.filter(b => b.type === 'image');
        const toolUseBlocks = blocks.filter(b => b.type === 'tool_use');
        const toolResults   = blocks.filter(b => b.type === 'tool_result');

        if (toolResults.length > 0) {
            // Anthropic user tool_result → OpenAI role:"tool" messages (one per result)
            for (const tr of toolResults) {
                const content = Array.isArray(tr.content)
                    ? tr.content.filter(b => b.type === 'text').map(b => b.text).join('')
                    : String(tr.content || '');
                msgs.push({ role: 'tool', tool_call_id: tr.tool_use_id, content });
            }
            // #4: preserve any text/image that rode along in the same user block
            // instead of dropping it (was: text-only, images silently lost).
            if (imageBlocks.length > 0) {
                const extra = [];
                for (const ib of imageBlocks) {
                    if (ib.source && ib.source.type === 'base64')
                        extra.push({ type: 'image_url', image_url: { url: 'data:' + ib.source.media_type + ';base64,' + ib.source.data } });
                }
                for (const tb of textBlocks) extra.push({ type: 'text', text: tb.text });
                if (extra.length) msgs.push({ role: 'user', content: extra });
            } else if (textBlocks.length > 0) {
                msgs.push({ role: 'user', content: textBlocks.map(b => b.text).join('') });
            }
        } else if (toolUseBlocks.length > 0 && m.role === 'assistant') {
            // Anthropic assistant tool_use → OpenAI tool_calls
            msgs.push({
                role: 'assistant',
                content: textBlocks.map(b => b.text).join('') || null,
                tool_calls: toolUseBlocks.map(tu => {
                    const call = {
                        id: tu.id || ('call_' + tu.name + '_' + Date.now()),
                        type: 'function',
                        function: { name: tu.name, arguments: JSON.stringify(tu.input || {}) }
                    };
                    // Re-attach Gemini thought_signature so 3.x tool turns don't 400.
                    const sig = tu.id && thoughtSigStore.get(tu.id);
                    if (sig) call.extra_content = { google: { thought_signature: sig } };
                    return call;
                })
            });
        } else if (imageBlocks.length > 0) {
            // Message with image content — convert to OpenAI vision format
            const oaiContent = [];
            for (const ib of imageBlocks) {
                if (ib.source && ib.source.type === 'base64') {
                    oaiContent.push({
                        type: 'image_url',
                        image_url: { url: 'data:' + ib.source.media_type + ';base64,' + ib.source.data }
                    });
                }
            }
            for (const tb of textBlocks) {
                oaiContent.push({ type: 'text', text: tb.text });
            }
            if (oaiContent.length > 0) {
                msgs.push({ role: m.role, content: oaiContent });
            }
        } else {
            msgs.push({ role: m.role, content: textBlocks.map(b => b.text).join('') });
        }
    }

    const req = { model, messages: msgs, max_tokens: a.max_tokens || 8192, stream: !!a.stream };
    if (a.temperature !== undefined) req.temperature = a.temperature;
    if (a.stop_sequences && a.stop_sequences.length) req.stop = a.stop_sequences;

    if (a.tools && a.tools.length) {
        req.tools = a.tools.map(t => ({
            type: 'function',
            function: {
                name: t.name,
                description: t.description || '',
                parameters: t.input_schema || { type: 'object', properties: {} }
            }
        }));
        if (a.tool_choice) {
            if (a.tool_choice === 'auto' || a.tool_choice === 'none') {
                req.tool_choice = a.tool_choice;
            } else if (a.tool_choice === 'any') {
                req.tool_choice = 'required';
            } else if (typeof a.tool_choice === 'object' && a.tool_choice.name) {
                req.tool_choice = { type: 'function', function: { name: a.tool_choice.name } };
            }
        }
    }

    return req;
}

// Rewrite an OAI messages array so it carries NO tool-call structure: assistant
// `tool_calls` become a short assistant text note, and `role:"tool"` results
// become `role:"user"` text. Used by the no-tools 400 fallback — a request with
// tool_calls/tool messages but no `tools` declared is rejected by strict OAI
// endpoints (Gemini compat). Returns a NEW array; never mutates the input.
function flattenToolHistory(messages) {
    const out = [];
    for (const m of (messages || [])) {
        if (m.role === 'tool') {
            const body = typeof m.content === 'string' ? m.content : JSON.stringify(m.content || '');
            out.push({ role: 'user', content: '[tool result] ' + body });
        } else if (m.role === 'assistant' && Array.isArray(m.tool_calls) && m.tool_calls.length) {
            const calls = m.tool_calls
                .map(tc => (tc.function && tc.function.name) || 'tool')
                .join(', ');
            const text = (typeof m.content === 'string' && m.content) ? m.content : '';
            out.push({ role: 'assistant', content: (text + '\n[requested tools: ' + calls + ']').trim() });
        } else {
            out.push(m);
        }
    }
    return out;
}

// Strip provider format-leakage from a tool-call function name. gpt-oss/harmony
// models (e.g. openai/gpt-oss-* on NVIDIA NIM) leak channel control tokens into
// the function name — "Bash<|channel|>commentary" instead of "Bash" — which makes
// claude-code reject EVERY tool call with "No such tool available: Bash<|channel|>…".
// Real tool names are strictly [A-Za-z0-9_-] (incl. mcp__ servers), so cut at the
// first harmony token / illegal char. Returns '' only if nothing salvageable.
function cleanToolName(n) {
    if (!n || typeof n !== 'string') return n || '';
    // truncate at the first harmony control token ("<|…")
    let s = n.split('<|')[0];
    // keep only the leading valid tool-name characters
    const m = s.match(/^[A-Za-z0-9_-]+/);
    return m ? m[0] : s.trim();
}

// Convert OpenAI Chat Completions response → Anthropic Messages response
function oaiToAnth(oai, model) {
    const choice = (oai.choices || [])[0] || {};
    const msg    = choice.message || {};
    const content = [];

    if (msg.content) content.push({ type: 'text', text: msg.content });

    // Convert OpenAI tool_calls → Anthropic tool_use blocks
    if (msg.tool_calls && msg.tool_calls.length > 0) {
        for (const tc of msg.tool_calls) {
            let input = {};
            try { input = JSON.parse(tc.function.arguments || '{}'); } catch (_) {}
            content.push({ type: 'tool_use', id: tc.id, name: cleanToolName(tc.function.name), input });
            const sig = extractThoughtSig(tc, null, choice);
            if (sig) storeThoughtSig(tc.id, sig);
        }
        log('[proxy] received ' + msg.tool_calls.length + ' tool_call(s) from provider\n');
    }

    const finish   = choice.finish_reason;
    const stopReason = finish === 'length'     ? 'max_tokens'
                     : finish === 'tool_calls' ? 'tool_use'
                     : 'end_turn';

    return {
        id: 'msg_' + (oai.id || Date.now()),
        type: 'message', role: 'assistant',
        content: content.length ? content : [{ type: 'text', text: '' }],
        model, stop_reason: stopReason, stop_sequence: null,
        usage: {
            input_tokens:  (oai.usage || {}).prompt_tokens    || 0,
            output_tokens: (oai.usage || {}).completion_tokens || 0,
        },
    };
}
    return { anthToOai, oaiToAnth, flattenToolHistory, extractThoughtSig, storeThoughtSig, cleanToolName };
};
