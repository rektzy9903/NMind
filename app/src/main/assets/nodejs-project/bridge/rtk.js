// bridge/rtk.js — RTK (Request Token Keeper). Extracted verbatim from
// bridge.js (Phase 2 #1 of the bridge split). Self-contained: only JS
// builtins, no outer-scope deps. Exports { compressMessages, formatRtkLog,
// estimateChars } — see CLAUDE.md invariant 81.
// RTK — Request Token Keeper (ported from 9router open-sse/rtk, MIT, decolua)
// Losslessly* compresses tool_result content (git diff/status, grep, find, ls,
// tree, build output, line-numbered dumps) in the OUTGOING Anthropic request,
// BEFORE anthToOai conversion — so it helps both the OAI-converted and the
// api.anthropic.com passthrough paths. Targets messages[] (history), which is
// the only request slice that grows unbounded over a --continue session;
// orthogonal to defer (tools[] schemas) and disabledTools (capability removal).
// Safe-by-design: any filter that throws or grows the text falls back to the
// original — RTK never breaks a request. Always on (mirrors tryOptimize); the
// only escape hatch is cfg.rtk === false. Surfaced in !log as `[RTK] saved …`.
// (* lossy-ish: caps/truncates verbose output; preserves substance + is_error.)
// ─────────────────────────────────────────────────────────────────────────
module.exports = (function () {
    // --- constants (mirror rtk Rust defaults) ---
    const RAW_CAP = 10 * 1024 * 1024;          // 10 MiB hard cap
    const MIN_COMPRESS_SIZE = 500;             // skip tiny blobs
    const DETECT_WINDOW = 1024;                // autodetect peeks first N chars
    const GIT_DIFF_HUNK_MAX_LINES = 100;
    const DEDUP_LINE_MAX = 2000;
    const GREP_PER_FILE_MAX = 10;
    const FIND_PER_DIR_MAX = 10;
    const FIND_TOTAL_DIR_MAX = 20;
    const STATUS_MAX_FILES = 10;
    const STATUS_MAX_UNTRACKED = 10;
    const LS_EXT_SUMMARY_TOP = 5;
    const LS_NOISE_DIRS = ['node_modules', '.git', 'target', '__pycache__',
        '.next', 'dist', 'build', '.venv', 'venv', '.cache', '.idea', '.vscode', '.DS_Store'];
    const TREE_MAX_LINES = 200;
    const SEARCH_LIST_PER_DIR_MAX = 10;
    const SEARCH_LIST_TOTAL_DIR_MAX = 20;
    const SMART_TRUNCATE_HEAD = 120;
    const SMART_TRUNCATE_TAIL = 60;
    const SMART_TRUNCATE_MIN_LINES = 250;
    const READ_NUMBERED_MIN_HIT_RATIO = 0.7;

    // --- filters ---
    function gitDiff(diff, maxLines = 500) {
        const result = [];
        let currentFile = '', added = 0, removed = 0, inHunk = false;
        let hunkShown = 0, hunkSkipped = 0, wasTruncated = false;
        const maxHunkLines = GIT_DIFF_HUNK_MAX_LINES;
        const lines = diff.split('\n');
        outer: for (const line of lines) {
            if (line.startsWith('diff --git')) {
                if (hunkSkipped > 0) { result.push('  ... (' + hunkSkipped + ' lines truncated)'); wasTruncated = true; hunkSkipped = 0; }
                if (currentFile && (added > 0 || removed > 0)) result.push('  +' + added + ' -' + removed);
                const parts = line.split(' b/');
                currentFile = parts.length > 1 ? parts.slice(1).join(' b/') : 'unknown';
                result.push('\n' + currentFile);
                added = 0; removed = 0; inHunk = false; hunkShown = 0;
            } else if (line.startsWith('@@')) {
                if (hunkSkipped > 0) { result.push('  ... (' + hunkSkipped + ' lines truncated)'); wasTruncated = true; hunkSkipped = 0; }
                inHunk = true; hunkShown = 0; result.push('  ' + line);
            } else if (inHunk) {
                if (line.startsWith('+') && !line.startsWith('+++')) {
                    added += 1;
                    if (hunkShown < maxHunkLines) { result.push('  ' + line); hunkShown += 1; } else hunkSkipped += 1;
                } else if (line.startsWith('-') && !line.startsWith('---')) {
                    removed += 1;
                    if (hunkShown < maxHunkLines) { result.push('  ' + line); hunkShown += 1; } else hunkSkipped += 1;
                } else if (hunkShown < maxHunkLines && !line.startsWith('\\')) {
                    if (hunkShown > 0) { result.push('  ' + line); hunkShown += 1; }
                }
            }
            if (result.length >= maxLines) { result.push('\n... (more changes truncated)'); wasTruncated = true; break outer; }
        }
        if (hunkSkipped > 0) { result.push('  ... (' + hunkSkipped + ' lines truncated)'); wasTruncated = true; }
        if (currentFile && (added > 0 || removed > 0)) result.push('  +' + added + ' -' + removed);
        if (wasTruncated) result.push('[diff compacted by rtk]');
        return result.join('\n');
    }
    gitDiff.filterName = 'git-diff';

    function gitStatus(input) {
        const lines = input.split('\n');
        if (lines.length === 0 || (lines.length === 1 && !lines[0].trim())) return 'Clean working tree';
        let branch = '';
        const stagedFiles = [], modifiedFiles = [], untrackedFiles = [];
        let staged = 0, modified = 0, untracked = 0, conflicts = 0;
        for (const raw of lines) {
            if (!raw.trim()) continue;
            const longBranch = raw.match(/^On branch (\S+)/);
            if (longBranch) { branch = longBranch[1]; continue; }
            if (raw.startsWith('##')) { branch = raw.replace(/^##\s*/, ''); continue; }
            if (raw.length >= 3 && /^[ MADRCU?!][ MADRCU?!] /.test(raw)) {
                const x = raw[0], y = raw[1], file = raw.slice(3);
                if (raw.slice(0, 2) === '??') { untracked++; untrackedFiles.push(file); continue; }
                if ('MADRC'.includes(x)) { staged++; stagedFiles.push(file); }
                else if (x === 'U') conflicts++;
                if (y === 'M' || y === 'D') { modified++; modifiedFiles.push(file); }
                continue;
            }
            const longMatch = raw.match(/^\s*(modified|new file|deleted|renamed|both modified):\s+(.+)$/);
            if (longMatch) {
                const kind = longMatch[1], p = longMatch[2].trim();
                if (kind === 'both modified') conflicts++;
                else if (kind === 'modified' || kind === 'deleted') { modified++; modifiedFiles.push(p); }
                else if (kind === 'new file' || kind === 'renamed') { staged++; stagedFiles.push(p); }
                continue;
            }
        }
        let out = '';
        if (branch) out += '* ' + branch + '\n';
        if (staged > 0) {
            out += '+ Staged: ' + staged + ' files\n';
            for (const f of stagedFiles.slice(0, STATUS_MAX_FILES)) out += '   ' + f + '\n';
            if (stagedFiles.length > STATUS_MAX_FILES) out += '   ... +' + (stagedFiles.length - STATUS_MAX_FILES) + ' more\n';
        }
        if (modified > 0) {
            out += '~ Modified: ' + modified + ' files\n';
            for (const f of modifiedFiles.slice(0, STATUS_MAX_FILES)) out += '   ' + f + '\n';
            if (modifiedFiles.length > STATUS_MAX_FILES) out += '   ... +' + (modifiedFiles.length - STATUS_MAX_FILES) + ' more\n';
        }
        if (untracked > 0) {
            out += '? Untracked: ' + untracked + ' files\n';
            for (const f of untrackedFiles.slice(0, STATUS_MAX_UNTRACKED)) out += '   ' + f + '\n';
            if (untrackedFiles.length > STATUS_MAX_UNTRACKED) out += '   ... +' + (untrackedFiles.length - STATUS_MAX_UNTRACKED) + ' more\n';
        }
        if (conflicts > 0) out += 'conflicts: ' + conflicts + ' files\n';
        if (staged === 0 && modified === 0 && untracked === 0 && conflicts === 0) out += 'clean — nothing to commit\n';
        return out.replace(/\n+$/, '');
    }
    gitStatus.filterName = 'git-status';

    function grep(input) {
        const byFile = new Map();
        let total = 0;
        for (const line of input.split('\n')) {
            const first = line.indexOf(':');
            if (first === -1) continue;
            const second = line.indexOf(':', first + 1);
            if (second === -1) continue;
            const file = line.slice(0, first);
            const lineNumStr = line.slice(first + 1, second);
            const content = line.slice(second + 1);
            if (!/^\d+$/.test(lineNumStr)) continue;
            total++;
            if (!byFile.has(file)) byFile.set(file, []);
            byFile.get(file).push([lineNumStr, content]);
        }
        if (total === 0) return input;
        const files = Array.from(byFile.keys()).sort();
        let out = total + ' matches in ' + files.length + 'F:\n\n';
        for (const file of files) {
            const matches = byFile.get(file);
            out += '[file] ' + file + ' (' + matches.length + '):\n';
            for (const pair of matches.slice(0, GREP_PER_FILE_MAX)) out += '  ' + pair[0].padStart(4) + ': ' + pair[1].trim() + '\n';
            if (matches.length > GREP_PER_FILE_MAX) out += '  +' + (matches.length - GREP_PER_FILE_MAX) + '\n';
            out += '\n';
        }
        return out;
    }
    grep.filterName = 'grep';

    function find(input) {
        const lines = input.split('\n').filter(l => l.trim());
        if (lines.length === 0) return input;
        const byDir = new Map();
        for (const p of lines) {
            const lastSlash = p.lastIndexOf('/');
            let dir, basename;
            if (lastSlash === -1) { dir = '.'; basename = p; }
            else { dir = p.slice(0, lastSlash) || '/'; basename = p.slice(lastSlash + 1); }
            if (!byDir.has(dir)) byDir.set(dir, []);
            byDir.get(dir).push(basename);
        }
        const dirs = Array.from(byDir.keys()).sort();
        let out = lines.length + ' files in ' + dirs.length + ' dirs:\n\n';
        for (const dir of dirs.slice(0, FIND_TOTAL_DIR_MAX)) {
            const fs2 = byDir.get(dir);
            out += dir + '/ (' + fs2.length + '):\n';
            for (const f of fs2.slice(0, FIND_PER_DIR_MAX)) out += '  ' + f + '\n';
            if (fs2.length > FIND_PER_DIR_MAX) out += '  +' + (fs2.length - FIND_PER_DIR_MAX) + '\n';
            out += '\n';
        }
        if (dirs.length > FIND_TOTAL_DIR_MAX) out += '+' + (dirs.length - FIND_TOTAL_DIR_MAX) + ' more dirs\n';
        return out;
    }
    find.filterName = 'find';

    const LS_DATE_RE = /\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2}\s+(\d{4}|\d{2}:\d{2})\s+/;
    function humanSize(bytes) {
        if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + 'M';
        if (bytes >= 1024) return (bytes / 1024).toFixed(1) + 'K';
        return bytes + 'B';
    }
    function parseLsLine(line) {
        const m = LS_DATE_RE.exec(line);
        if (!m) return null;
        const name = line.slice(m.index + m[0].length);
        const beforeParts = line.slice(0, m.index).split(/\s+/).filter(Boolean);
        if (beforeParts.length < 4) return null;
        const fileType = beforeParts[0].charAt(0);
        let size = 0;
        for (let i = beforeParts.length - 1; i >= 0; i--) {
            const n = Number(beforeParts[i]);
            if (Number.isInteger(n) && String(n) === beforeParts[i]) { size = n; break; }
        }
        return { fileType, size, name };
    }
    function ls(input) {
        const dirs = [], files = [], byExt = new Map();
        for (const line of input.split('\n')) {
            if (line.startsWith('total ') || line.length === 0) continue;
            const parsed = parseLsLine(line);
            if (!parsed) continue;
            if (parsed.name === '.' || parsed.name === '..') continue;
            if (LS_NOISE_DIRS.includes(parsed.name)) continue;
            if (parsed.fileType === 'd') dirs.push(parsed.name);
            else if (parsed.fileType === '-' || parsed.fileType === 'l') {
                const dot = parsed.name.lastIndexOf('.');
                const ext = dot > 0 ? parsed.name.slice(dot) : 'no ext';
                byExt.set(ext, (byExt.get(ext) || 0) + 1);
                files.push([parsed.name, humanSize(parsed.size)]);
            }
        }
        if (dirs.length === 0 && files.length === 0) return input;
        let out = '';
        for (const d of dirs) out += d + '/\n';
        for (const pair of files) out += pair[0] + '  ' + pair[1] + '\n';
        let summary = '\nSummary: ' + files.length + ' files, ' + dirs.length + ' dirs';
        if (byExt.size > 0) {
            const ext = Array.from(byExt.entries()).sort((a, b) => b[1] - a[1]);
            const parts = ext.slice(0, LS_EXT_SUMMARY_TOP).map(e => e[1] + ' ' + e[0]);
            summary += ' (' + parts.join(', ');
            if (ext.length > LS_EXT_SUMMARY_TOP) summary += ', +' + (ext.length - LS_EXT_SUMMARY_TOP) + ' more';
            summary += ')';
        }
        return out + summary;
    }
    ls.filterName = 'ls';

    function tree(input) {
        const lines = input.split('\n');
        if (lines.length === 0) return input;
        const filtered = [];
        for (const line of lines) {
            if (line.includes('director') && line.includes('file')) continue;
            if (line.trim() === '' && filtered.length === 0) continue;
            filtered.push(line);
        }
        while (filtered.length > 0 && filtered[filtered.length - 1].trim() === '') filtered.pop();
        if (filtered.length > TREE_MAX_LINES) {
            const cut = filtered.length - TREE_MAX_LINES;
            return filtered.slice(0, TREE_MAX_LINES).join('\n') + '\n... +' + cut + ' more lines';
        }
        return filtered.join('\n');
    }
    tree.filterName = 'tree';

    function dedupLog(input) {
        const lines = input.split('\n');
        const out = [];
        let prev = null, runCount = 0, blankStreak = 0;
        const flushRun = () => { if (prev !== null && runCount > 1) out.push('  ... (' + (runCount - 1) + ' duplicate lines)'); };
        for (const line of lines) {
            if (line.trim() === '') {
                if (blankStreak < 1) out.push(line);
                blankStreak += 1; flushRun(); prev = null; runCount = 0; continue;
            }
            blankStreak = 0;
            if (line === prev) { runCount += 1; continue; }
            flushRun(); out.push(line); prev = line; runCount = 1;
            if (out.length >= DEDUP_LINE_MAX) { out.push('... (truncated at ' + DEDUP_LINE_MAX + ' lines)'); return out.join('\n'); }
        }
        flushRun();
        return out.join('\n');
    }
    dedupLog.filterName = 'dedup-log';

    function smartTruncate(input) {
        const lines = input.split('\n');
        if (lines.length < SMART_TRUNCATE_MIN_LINES) return input;
        const head = lines.slice(0, SMART_TRUNCATE_HEAD);
        const tail = lines.slice(lines.length - SMART_TRUNCATE_TAIL);
        const cut = lines.length - head.length - tail.length;
        return head.concat(['... +' + cut + ' lines truncated'], tail).join('\n');
    }
    smartTruncate.filterName = 'smart-truncate';

    const READ_NUMBERED_LINE_RE = /^\s*\d+\|/;
    function readNumbered(input) {
        const lines = input.split('\n');
        if (lines.length < SMART_TRUNCATE_MIN_LINES) return input;
        const head = lines.slice(0, SMART_TRUNCATE_HEAD);
        const tail = lines.slice(lines.length - SMART_TRUNCATE_TAIL);
        const cut = lines.length - head.length - tail.length;
        return head.concat(['... +' + cut + ' lines truncated (file continues)'], tail).join('\n');
    }
    readNumbered.filterName = 'read-numbered';

    const SEARCH_LIST_HEADER_RE = /^Result of search in '[^']*' \(total (\d+) files?\):/;
    function searchList(input) {
        const lines = input.split('\n');
        if (lines.length === 0) return input;
        const header = lines[0] || '';
        const paths = [];
        for (const raw of lines.slice(1)) {
            const t = raw.trim();
            if (!t.startsWith('- ')) continue;
            paths.push(t.slice(2));
        }
        if (paths.length === 0) return input;
        const byDir = new Map();
        for (const p of paths) {
            const slash = p.lastIndexOf('/');
            const dir = slash === -1 ? '.' : (p.slice(0, slash) || '/');
            const name = slash === -1 ? p : p.slice(slash + 1);
            if (!byDir.has(dir)) byDir.set(dir, []);
            byDir.get(dir).push(name);
        }
        const dirs = Array.from(byDir.keys()).sort();
        let out = header + '\n' + paths.length + ' files in ' + dirs.length + ' dirs:\n\n';
        for (const dir of dirs.slice(0, SEARCH_LIST_TOTAL_DIR_MAX)) {
            const names = byDir.get(dir);
            out += dir + '/ (' + names.length + '):\n';
            for (const n of names.slice(0, SEARCH_LIST_PER_DIR_MAX)) out += '  ' + n + '\n';
            if (names.length > SEARCH_LIST_PER_DIR_MAX) out += '  +' + (names.length - SEARCH_LIST_PER_DIR_MAX) + '\n';
            out += '\n';
        }
        if (dirs.length > SEARCH_LIST_TOTAL_DIR_MAX) out += '+' + (dirs.length - SEARCH_LIST_TOTAL_DIR_MAX) + ' more dirs\n';
        return out.replace(/\n+$/, '');
    }
    searchList.filterName = 'search-list';

    const RE_CARGO_ERR_CONT = /^\s*(-->|\||\d+\s*\||=)/;
    const DEPRECATION_KEEP = 3;
    function buildOutput(input) {
        const lines = input.split('\n');
        if (lines.length === 0) return input;
        const errors = [], warnings = [], deprecations = [];
        let summary = null, compilingCount = 0, downloadingCount = 0, inCargoError = false;
        for (const line of lines) {
            const trimmed = line.trim();
            if (inCargoError) {
                if (!trimmed) { inCargoError = false; continue; }
                if (RE_CARGO_ERR_CONT.test(line)) { errors.push(line); continue; }
                inCargoError = false;
            }
            if (!trimmed) continue;
            if (/^npm (ERR!|error)/i.test(trimmed) || /^yarn error/i.test(trimmed)) { errors.push(line); continue; }
            if (/^npm warn deprecated/i.test(trimmed)) { deprecations.push(line); continue; }
            if (/^npm warn/i.test(trimmed) || /^yarn warn/i.test(trimmed)) { warnings.push(line); continue; }
            if (/^error(\[|:)/i.test(trimmed) || trimmed.startsWith('error -->')) { errors.push(line); inCargoError = true; continue; }
            if (/^warning(\[|:)/i.test(trimmed) || trimmed.startsWith('warning -->')) { warnings.push(line); inCargoError = true; continue; }
            if (/^ERROR:/i.test(trimmed)) { errors.push(line); continue; }
            if (/^\[ERROR\]/i.test(trimmed) || /^BUILD FAILED/i.test(trimmed)) { errors.push(line); continue; }
            if (/^\[WARNING\]/i.test(trimmed)) { warnings.push(line); continue; }
            if (/^\s*Compiling\s+\S+/i.test(trimmed)) { compilingCount++; continue; }
            if (/^\s*Downloading\s+\S+/i.test(trimmed) || /^Fetching\s+/i.test(trimmed)) { downloadingCount++; continue; }
            if (/^(added|removed|changed|audited|installed)\s+\d+\s+package/i.test(trimmed) ||
                /^\s*Finished\s+/i.test(trimmed) || /^BUILD SUCCESS/i.test(trimmed) ||
                /^\d+\s+(vulnerabilities|packages?|warnings?|errors?)/i.test(trimmed) ||
                /^Successfully (installed|built)/i.test(trimmed) || /^To address .* issues/i.test(trimmed) ||
                /^Run `npm (audit|fund)`/i.test(trimmed) || /packages are looking for funding/i.test(trimmed)) {
                summary = summary ? summary + '\n' + line : line; continue;
            }
        }
        let out = '';
        for (const d of deprecations.slice(0, DEPRECATION_KEEP)) out += d + '\n';
        if (deprecations.length > DEPRECATION_KEEP) out += '... +' + (deprecations.length - DEPRECATION_KEEP) + ' more deprecated packages\n';
        if (compilingCount > 0) out += 'Compiled ' + compilingCount + ' packages\n';
        if (downloadingCount > 0) out += 'Downloaded ' + downloadingCount + ' packages\n';
        for (const e of errors) out += e + '\n';
        for (const w of warnings.slice(0, 5)) out += w + '\n';
        if (warnings.length > 5) out += '... +' + (warnings.length - 5) + ' more warnings\n';
        if (summary) out += summary + '\n';
        return out.replace(/\n+$/, '') || input;
    }
    buildOutput.filterName = 'build-output';

    // --- autodetect ---
    const RE_GIT_DIFF = /^diff --git /m;
    const RE_GIT_DIFF_HUNK = /^@@ /m;
    const RE_GIT_STATUS = /^On branch |^nothing to commit|^Changes (not |to be )|^Untracked files:/m;
    const RE_PORCELAIN = /^[ MADRCU?!][ MADRCU?!] \S/m;
    const RE_BUILD_OUTPUT = /^(npm (warn|error|ERR!)|yarn (warn|error)|\s*Compiling\s+\S+|\s*Downloading\s+\S+|added \d+ package|\[ERROR\]|BUILD (SUCCESS|FAILED)|\s*Finished\s+|Successfully (installed|built)|ERROR:)/im;
    const RE_TREE_GLYPH = /[├└]──|│  /;
    const RE_LS_ROW = /^[-dlbcps][rwx-]{9}/m;
    const RE_LS_TOTAL = /^total \d+$/m;

    function isGrepLine(line) {
        const first = line.indexOf(':');
        if (first === -1) return false;
        const second = line.indexOf(':', first + 1);
        if (second === -1) return false;
        return /^\d+$/.test(line.slice(first + 1, second));
    }
    function isPathLike(line) {
        const t = line.trim();
        if (t.length === 0) return false;
        if (t.includes(':')) return false;
        return t.startsWith('.') || t.startsWith('/') || t.includes('/');
    }
    function isMostlyPorcelain(head) {
        const lines = head.split('\n').filter(l => l.trim());
        if (lines.length < 3) return false;
        return lines.filter(l => RE_PORCELAIN.test(l)).length / lines.length >= 0.6;
    }
    function isLineNumbered(lines) {
        let hits = 0, nonEmpty = 0;
        for (const l of lines.slice(0, 100)) {
            if (l.length === 0) continue;
            nonEmpty++;
            if (READ_NUMBERED_LINE_RE.test(l)) hits++;
        }
        if (nonEmpty < 5) return false;
        return hits / nonEmpty >= READ_NUMBERED_MIN_HIT_RATIO;
    }
    function countMatches(text, re) {
        const g = new RegExp(re.source, re.flags.includes('g') ? re.flags : re.flags + 'g');
        return (text.match(g) || []).length;
    }
    function autoDetectFilter(text) {
        const head = text.length > DETECT_WINDOW ? text.slice(0, DETECT_WINDOW) : text;
        if (RE_GIT_DIFF.test(head) || RE_GIT_DIFF_HUNK.test(head)) return gitDiff;
        if (RE_GIT_STATUS.test(head)) return gitStatus;
        if (RE_BUILD_OUTPUT.test(head)) return buildOutput;
        if (isMostlyPorcelain(head)) return gitStatus;
        const lines = head.split('\n');
        const nonEmpty = lines.filter(l => l.trim().length > 0);
        if (nonEmpty.slice(0, 5).some(isGrepLine)) return grep;
        if (nonEmpty.length >= 3 && nonEmpty.every(isPathLike)) return find;
        if (RE_TREE_GLYPH.test(head)) return tree;
        if (RE_LS_TOTAL.test(head) || countMatches(head, RE_LS_ROW) >= 3) return ls;
        if (SEARCH_LIST_HEADER_RE.test(head)) return searchList;
        if (lines.length >= SMART_TRUNCATE_MIN_LINES && isLineNumbered(lines)) return readNumbered;
        if (nonEmpty.length >= 5) return dedupLog;
        if (text.split('\n').length >= SMART_TRUNCATE_MIN_LINES) return smartTruncate;
        return null;
    }

    // --- apply (catch-and-passthrough) ---
    function safeApply(fn, text) {
        if (typeof fn !== 'function') return text;
        try {
            const out = fn(text);
            return typeof out === 'string' ? out : text;
        } catch (err) {
            return text;
        }
    }
    function compressText(text, stats) {
        const bytesIn = text.length;
        stats.bytesBefore += bytesIn;
        if (bytesIn < MIN_COMPRESS_SIZE || bytesIn > RAW_CAP) { stats.bytesAfter += bytesIn; return text; }
        const fn = autoDetectFilter(text);
        if (!fn) { stats.bytesAfter += bytesIn; return text; }
        const out = safeApply(fn, text);
        if (!out || out.length === 0 || out.length >= bytesIn) { stats.bytesAfter += bytesIn; return text; }
        stats.bytesAfter += out.length;
        stats.hits.push({ filter: fn.filterName, saved: bytesIn - out.length });
        return out;
    }

    // Estimate total request chars (sys + tools + msgs) — drives the budget gate
    // so RTK only fires when a request is actually near a provider limit. Mirrors
    // the [proxy] size diagnostic's accounting.
    function estimateChars(anthReq) {
        let n = 0;
        const sys = anthReq.system;
        if (typeof sys === 'string') n += sys.length;
        else if (Array.isArray(sys)) for (const b of sys) { if (b && b.text) n += b.text.length; }
        if (Array.isArray(anthReq.tools) && anthReq.tools.length) n += JSON.stringify(anthReq.tools).length;
        for (const m of (anthReq.messages || [])) {
            if (typeof m.content === 'string') { n += m.content.length; continue; }
            for (const b of (m.content || [])) {
                if (b.text) n += b.text.length;
                else if (b.input) n += JSON.stringify(b.input).length;
                else if (b.content) n += (typeof b.content === 'string' ? b.content.length : JSON.stringify(b.content).length);
            }
        }
        return n;
    }

    // --- public: compress tool_result content in an Anthropic request body ---
    // Mutates anthReq.messages in place. Returns stats or null. Preserves
    // is_error tool_results (error traces must survive verbatim).
    // RECENCY-AWARE: never compresses the freshest tool_result — the last message
    // carrying a tool_result is what the model is actively reasoning about THIS
    // turn, so it's left at full fidelity; only OLD context (already moved past)
    // is compressed. On the next turn that result becomes old and gets compressed.
    // Pass {protectLatest:false} to force compress-everything.
    function compressMessages(anthReq, opts) {
        if (!anthReq || !Array.isArray(anthReq.messages)) return null;
        const msgs = anthReq.messages;
        let protectIdx = -1;
        if (!opts || opts.protectLatest !== false) {
            for (let i = msgs.length - 1; i >= 0; i--) {
                const c = msgs[i] && msgs[i].content;
                if (Array.isArray(c) && c.some(b => b && b.type === 'tool_result')) { protectIdx = i; break; }
            }
        }
        const stats = { bytesBefore: 0, bytesAfter: 0, hits: [], protectIdx };
        try {
            for (let i = 0; i < msgs.length; i++) {
                if (i === protectIdx) continue;                  // keep freshest result raw
                const msg = msgs[i];
                if (!msg || !Array.isArray(msg.content)) continue;
                for (const block of msg.content) {
                    if (!block || block.type !== 'tool_result') continue;
                    if (block.is_error === true) continue;
                    if (typeof block.content === 'string') {
                        block.content = compressText(block.content, stats);
                    } else if (Array.isArray(block.content)) {
                        for (const part of block.content) {
                            if (part && part.type === 'text' && typeof part.text === 'string') {
                                part.text = compressText(part.text, stats);
                            }
                        }
                    }
                }
            }
        } catch (e) {
            return null;
        }
        return stats;
    }

    function formatRtkLog(stats) {
        if (!stats || !stats.hits || stats.hits.length === 0) return null;
        const saved = stats.bytesBefore - stats.bytesAfter;
        const pct = stats.bytesBefore > 0 ? ((saved / stats.bytesBefore) * 100).toFixed(1) : '0';
        const filters = Array.from(new Set(stats.hits.map(h => h.filter))).join(',');
        const kept = stats.protectIdx >= 0 ? ' (latest result kept raw)' : '';
        return '[RTK] saved ' + saved + 'c / ' + stats.bytesBefore + 'c (' + pct + '%) via [' + filters + '] hits=' + stats.hits.length + kept;
    }

    return { compressMessages, formatRtkLog, estimateChars };
})();
