/*
 * linkat_shim.c — guest-side LD_PRELOAD shim that makes hardlink() work under
 * the bundled (old Termux 5.1.0) proot on arm64, so `apt install` succeeds.
 *
 * THE PROBLEM (see CLAUDE.md apt/dpkg TODO + `!dpkg-test`):
 *   - Every `apt install` runs dpkg, which backs up /var/lib/dpkg/status via a
 *     hardlink (atomic_file_backup: link(status, status-old)).
 *   - arm64 has NO link(2) syscall — only linkat(2). glibc's link() and
 *     coreutils both issue the linkat syscall.
 *   - The bundled proot's --link2symlink only hooks the link() syscall, which
 *     never fires on arm64, so the real linkat syscall falls through to the
 *     kernel → EPERM on the proot rootfs. dpkg dies, apt is unusable.
 *
 * WHY A USERSPACE SHIM (not "downgrade linkat→link"):
 *   - There is literally no link() syscall on arm64 to downgrade TO, so we
 *     cannot route around proot at the syscall layer. Instead we satisfy the
 *     hardlink request entirely in userspace, BEFORE it becomes a syscall:
 *     we COPY the source file to the destination (an independent snapshot,
 *     which is exactly what dpkg's backup wants). For symlinks we recreate the
 *     symlink. This needs no link/linkat syscall at all.
 *
 * WHY BOTH link() AND linkat() ARE OVERRIDDEN:
 *   - dpkg calls the libc link() wrapper; coreutils (`ln`) calls linkat().
 *   - glibc's link() does NOT call the public `linkat` symbol — it issues the
 *     linkat syscall directly via INLINE_SYSCALL. So overriding only `linkat`
 *     would miss dpkg. We override both public symbols.
 *
 * SAFETY:
 *   - Functionally correct for any caller: two independent files instead of a
 *     shared inode (only the dedup optimization is lost; content is identical).
 *   - Self-neutral on a FUTURE good proot: if proot ever hooks linkat properly
 *     a copy is still a correct result, just not inode-shared. Preloading is
 *     therefore safe to leave on unconditionally.
 *   - Anything we cannot emulate (cross-dir-fd linkat, non-regular/non-symlink
 *     sources) falls back to the real linkat() so behavior is unchanged there.
 *
 * Build (CI, glibc arm64): aarch64-linux-gnu-gcc -shared -fPIC -O2 -o liblinkatshim.so linkat_shim.c -ldl
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

typedef int (*linkat_fn)(int, const char *, int, const char *, int);

static int real_linkat(int ofd, const char *o, int nfd, const char *n, int fl) {
    static linkat_fn p = NULL;
    if (!p) p = (linkat_fn)dlsym(RTLD_NEXT, "linkat");
    if (!p) { errno = ENOSYS; return -1; }
    return p(ofd, o, nfd, n, fl);
}

/* Emulate a hardlink of oldpath -> newpath without any link/linkat syscall.
 * follow != 0  => resolve a symlink at oldpath (AT_SYMLINK_FOLLOW semantics).
 * Returns 0 on success, -1 with errno set (matching link() error contract). */
static int emulate_link(const char *oldpath, const char *newpath, int follow) {
    struct stat st;
    if ((follow ? stat(oldpath, &st) : lstat(oldpath, &st)) != 0)
        return -1;  /* errno from stat (ENOENT, EACCES, …) */

    /* Symlink source, don't-follow: recreate the symlink at newpath. */
    if (S_ISLNK(st.st_mode)) {
        char target[4096];
        ssize_t len = readlink(oldpath, target, sizeof(target) - 1);
        if (len < 0) return -1;
        target[len] = '\0';
        if (symlink(target, newpath) != 0) return -1;  /* EEXIST preserved */
        return 0;
    }

    /* Regular file: copy contents into a fresh newpath (O_EXCL so an existing
     * newpath yields EEXIST, exactly as link() would). */
    if (S_ISREG(st.st_mode)) {
        int in = open(oldpath, O_RDONLY | O_CLOEXEC);
        if (in < 0) return -1;
        int out = open(newpath, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC,
                       st.st_mode & 07777);
        if (out < 0) { int e = errno; close(in); errno = e; return -1; }

        char buf[65536];
        ssize_t r;
        while ((r = read(in, buf, sizeof(buf))) > 0) {
            char *p = buf;
            ssize_t left = r;
            while (left > 0) {
                ssize_t w = write(out, p, (size_t)left);
                if (w < 0) {
                    int e = errno;
                    close(in); close(out); unlink(newpath);
                    errno = e; return -1;
                }
                p += w; left -= w;
            }
        }
        if (r < 0) {
            int e = errno;
            close(in); close(out); unlink(newpath);
            errno = e; return -1;
        }
        close(in);
        if (close(out) != 0) { unlink(newpath); return -1; }
        return 0;
    }

    /* Directories, fifos, devices, sockets: can't safely emulate — let the
     * real syscall decide (will typically EPERM, same as today). */
    errno = EPERM;
    return -1;
}

/* dpkg's path: the libc link() wrapper. */
int link(const char *oldpath, const char *newpath) {
    return emulate_link(oldpath, newpath, 0);
}

/* coreutils (`ln`) and modern tools: linkat(). Emulate only the common
 * same-cwd case; defer anything with real dir fds to the genuine linkat. */
int linkat(int olddirfd, const char *oldpath,
           int newdirfd, const char *newpath, int flags) {
    /* AT_FDCWD on both sides: relative paths resolve against the process cwd
     * for both us and the kernel, so userspace emulation is equivalent. Any
     * real directory fd is deferred to the genuine linkat (rare; unchanged). */
    if (olddirfd == AT_FDCWD && newdirfd == AT_FDCWD && oldpath && newpath)
        return emulate_link(oldpath, newpath, (flags & AT_SYMLINK_FOLLOW) ? 1 : 0);
    return real_linkat(olddirfd, oldpath, newdirfd, newpath, flags);
}
