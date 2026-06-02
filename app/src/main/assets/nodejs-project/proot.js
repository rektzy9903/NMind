/*
 * proot.js — Ubuntu (proot) engine launcher  [P1 of ubuntu-engine.md]
 *
 * Self-contained helper for launching commands inside a bundled proot Ubuntu
 * rootfs. NOT wired into bridge.js yet (P2 does that) — kept standalone so it
 * can be reviewed and dry-run in isolation while the libnode engine keeps
 * running unchanged.
 *
 * The argv + env below are reverse-engineered from a PROVEN proot-distro login
 * invocation captured on a real device (Termux proot 5.x launching Ubuntu
 * 25.10, claude-code 2.1.160, glibc 2.42). See ubuntu-engine.md §"Proven proot
 * invocation" for the source.
 *
 * ── Native packaging requirement (CI / build.yml, see ubuntu-engine.md) ──
 * Android only extracts files matching /^lib.*\.so$/ from the APK lib dir, and
 * only the native-lib dir is exec-capable on Android 10+ (/data is noexec). So
 * the proot executable + its deps must be shipped as lib*.so names:
 *
 *   jniLibs/<abi>/libproot.so           ← the proot binary (UserLAnd .a10 build:
 *                                          a dynamic PIE, NEEDED libtalloc.so.2 —
 *                                          NOT static. CI patchelf's that NEEDED
 *                                          to libtalloc.so and sets RPATH=$ORIGIN)
 *   jniLibs/<abi>/libtalloc.so          ← proot's only non-system shared dep
 *   jniLibs/<abi>/libproot-loader.so    ← proot's 64-bit loader (static ELF;
 *                                          injected via ptrace, read not exec'd)
 *   jniLibs/<abi>/libproot-loader32.so  ← proot's 32-bit loader (static ELF)
 *
 * The bundled proot has a Termux loader path compiled in, so PROOT_LOADER /
 * PROOT_LOADER_32 MUST be set explicitly (prootEnv() does this); libtalloc.so is
 * resolved by the linker via RPATH=$ORIGIN (or LD_LIBRARY_PATH=nativeDir).
 */

'use strict';

const path = require('path');
const fs   = require('fs');

// NATIVE_DIR = the app's nativeLibraryDir (passed by the launcher as argv[3]'s
// dir, same as bridge.js NATIVE_DIR). Falls back for standalone dry-runs.
function nativeDir() {
    // Explicit override (dry-run/tests) is the directory itself.
    if (process.env.NEXUS_NATIVE_DIR) return process.env.NEXUS_NATIVE_DIR;
    // In production argv[3] is the launcher .so FILE — its dir is the native dir.
    const launcher = process.argv[3] || '';
    return launcher ? path.dirname(launcher) : '/data/app/native';
}

// FILES_DIR = app filesDir (HOME of the bridge). Rootfs lives under it.
function filesDir() {
    return process.env.NEXUS_FILES_DIR || process.env.HOME || '/data/data/com.nexusmind/files';
}

const DEFAULT_FAKE_KERNEL = '6.17.0-PRoot-Distro';

// Resolved bundled-binary paths (lib*.so names per the packaging note above).
function paths() {
    const nd = nativeDir();
    const fd = filesDir();
    const rootfs = path.join(fd, 'ubuntu');           // extracted Ubuntu rootfs
    return {
        proot:    path.join(nd, 'libproot.so'),
        loader:   path.join(nd, 'libproot-loader.so'),
        loader32: path.join(nd, 'libproot-loader32.so'),
        talloc:   path.join(nd, 'libtalloc.so'),       // proot's bundled shared dep
        rootfs,
        l2s:      path.join(rootfs, '.l2s'),           // --link2symlink store
        tmp:      path.join(rootfs, 'tmp'),
        nativeDir: nd,
        filesDir:  fd,
    };
}

/*
 * prootEnv() — environment for the proot PROCESS ITSELF (not the guest).
 * The guest's env is set separately via `/usr/bin/env -i ...` in the argv.
 */
function prootEnv(extra) {
    const p = paths();
    const env = Object.assign({}, process.env, {
        PROOT_LOADER:    p.loader,
        PROOT_LOADER_32: p.loader32,
        PROOT_L2S_DIR:   p.l2s,
        PROOT_TMP_DIR:   p.tmp,
        // Some devices' seccomp filters break proot; proot-distro documents
        // PROOT_NO_SECCOMP=1 as the fallback. Off by default; flip on-device if
        // spawns hang/abort. (ubuntu-engine.md risk list.)
        // PROOT_NO_SECCOMP: '1',
        LD_LIBRARY_PATH: p.nativeDir,
    });
    return Object.assign(env, extra || {});
}

/*
 * buildProotArgv(opts) — the argv to spawn (spawn(prootBin, argv, {env})).
 * Mirrors the proven proot-distro `login` command. `opts`:
 *   cwd        guest cwd (default /root)
 *   binds      extra "host:guest" bind mounts (e.g. filesDir:/root/.nexus, /sdcard:/sdcard)
 *   guestEnv   {KEY:VAL} env inside the guest (HOME, ANTHROPIC_BASE_URL, etc.)
 *   command    array, e.g. ['claude','--version']
 */
function buildProotArgv(opts) {
    opts = opts || {};
    const p = paths();
    const cwd = opts.cwd || '/root';
    const rootfs = p.rootfs;

    const argv = [
        '-L',
        '--kernel-release=' + (opts.kernelRelease || DEFAULT_FAKE_KERNEL),
        '--link2symlink',
        '--kill-on-exit',
        '--rootfs=' + rootfs,
        '--root-id',
        '--cwd=' + cwd,
        // Core device binds (proven set).
        '--bind=/dev',
        '--bind=/dev/urandom:/dev/random',
        '--bind=/proc',
        '--bind=/proc/self/fd:/dev/fd',
        '--bind=/proc/self/fd/0:/dev/stdin',
        '--bind=/proc/self/fd/1:/dev/stdout',
        '--bind=/proc/self/fd/2:/dev/stderr',
        '--bind=/sys',
        // Fake /proc + selinux entries (created by ensureFakeSysData()).
        '--bind=' + rootfs + '/proc/.loadavg:/proc/loadavg',
        '--bind=' + rootfs + '/proc/.stat:/proc/stat',
        '--bind=' + rootfs + '/proc/.uptime:/proc/uptime',
        '--bind=' + rootfs + '/proc/.version:/proc/version',
        '--bind=' + rootfs + '/proc/.vmstat:/proc/vmstat',
        '--bind=' + rootfs + '/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap',
        '--bind=' + rootfs + '/proc/.sysctl_inotify_max_user_watches:/proc/sys/fs/inotify/max_user_watches',
        '--bind=' + rootfs + '/sys/.empty:/sys/fs/selinux',
    ];

    // App-specific binds: share filesDir (configs, mcp config, image-inject)
    // and /sdcard (workspace). Caller supplies these.
    for (const b of (opts.binds || [])) argv.push('--bind=' + b);

    // Guest env via `env -i` (clean env, only what we pass).
    argv.push('/usr/bin/env', '-i');
    const genv = Object.assign({
        HOME: '/root',
        LANG: 'C.UTF-8',
        PATH: '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
        TERM: 'xterm-256color',
        TMPDIR: '/tmp',
    }, opts.guestEnv || {});
    for (const k of Object.keys(genv)) argv.push(k + '=' + genv[k]);

    // The actual command to run inside Ubuntu.
    for (const c of (opts.command || ['/bin/bash'])) argv.push(c);
    return argv;
}

/*
 * ensureFakeSysData(rootfs) — create the fake /proc + /sys/.empty files proot
 * binds over (proot-distro does this; Android restricts the real ones).
 * Stub for P3; real content tables come from proot-distro setup_fake_sysdata().
 */
function ensureFakeSysData(rootfs) {
    const proc = path.join(rootfs, 'proc');
    try { fs.mkdirSync(proc, { recursive: true }); } catch (_) {}
    try { fs.mkdirSync(path.join(rootfs, 'sys', '.empty'), { recursive: true }); } catch (_) {}
    const files = {
        '.loadavg': '0.12 0.07 0.02 2/165 765\n',
        '.stat':    'cpu  1957 0 2877 93280 262 342 254 87 0 0\n',
        '.uptime':  '1500.00 1234.56\n',
        '.version': 'Linux version ' + DEFAULT_FAKE_KERNEL + '\n',
        '.vmstat':  'nr_free_pages 1\n',
        '.sysctl_entry_cap_last_cap': '40\n',
        '.sysctl_inotify_max_user_watches': '524288\n',
    };
    for (const f of Object.keys(files)) {
        const fp = path.join(proc, f);
        try { if (!fs.existsSync(fp)) fs.writeFileSync(fp, files[f]); } catch (_) {}
    }
}

/*
 * prootReady() — are all bundled native files + the rootfs present?
 * Lets bridge.js fall back to the libnode engine if the Ubuntu engine isn't
 * installed yet (P2 dev-flag coexistence).
 */
function prootReady() {
    const p = paths();
    return fs.existsSync(p.proot) && fs.existsSync(p.loader) &&
           fs.existsSync(p.talloc) &&
           fs.existsSync(path.join(p.rootfs, 'usr', 'bin'));
}

module.exports = { paths, prootEnv, buildProotArgv, ensureFakeSysData, prootReady, DEFAULT_FAKE_KERNEL };

// ── Standalone dry-run: `node proot.js --dry-run` prints the argv it would run.
// Lets us eyeball the command without any native files present.
if (require.main === module) {
    if (process.argv.includes('--dry-run')) {
        const argv = buildProotArgv({
            command: ['claude', '--version'],
            binds: [ filesDir() + ':/root/.nexus', '/sdcard:/sdcard' ],
            guestEnv: { ANTHROPIC_BASE_URL: 'http://127.0.0.1:8082', ANTHROPIC_API_KEY: 'sk-ant-proxy000' },
        });
        const p = paths();
        console.log('proot binary : ' + p.proot);
        console.log('PROOT_LOADER : ' + p.loader);
        console.log('rootfs       : ' + p.rootfs);
        console.log('ready        : ' + prootReady());
        console.log('\nspawn argv:\n  ' + p.proot + ' \\\n    ' + argv.join(' \\\n    '));
    } else {
        console.log('proot.js — use --dry-run to preview the launch command');
    }
}
