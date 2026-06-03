// pty — allocate a pseudo-terminal, run a program under it (proot → bash), and
// relay bytes between the PTY master and this process's stdin/stdout so a remote
// xterm.js (over the bridge's 8083 socket) drives a REAL interactive shell.
//
// Shipped as libpty.so (a PIE executable, like libfdexec.so / libnode-launcher.so)
// so AGP packages it in lib/<abi>/ and Android extracts it to nativeLibraryDir —
// the only exec-capable path on API 29+.
//
// Why a native helper (P6, "Option 1"): Node.js has no openpty/forkpty, and a real
// terminal program (vim, htop, bash line-editing) needs a controlling TTY, not the
// pipes child_process.spawn gives. We also MUST close the app's inherited fd table
// before exec — same reason as libfdexec: proot ptrace-scans every inherited fd and
// hangs otherwise (the framework leaks WebView / goldfish / mmap'd-apk fds).
//
// In-band resize channel (revived from the dead ESC 0xFE plumbing, CLAUDE.md inv 5e):
// the bridge injects a 6-byte control sequence on stdin —
//     0x1B 0xFE  hiCols loCols  hiRows loRows     (cols = hi<<8|lo, rows = hi<<8|lo)
// which we strip out of the input stream and apply via ioctl(TIOCSWINSZ). It is
// never forwarded to the shell. 0xFE-after-ESC is not a valid ANSI input sequence,
// so reserving it for resize cannot collide with real terminal input.
//
// Usage: libpty.so <program> [args...]
//   e.g. libpty.so <nativeDir>/libproot.so -r <rootfs> ... /bin/bash -l
//   (fd-closing is done here in the child, so chaining libfdexec is unnecessary.)

#include <pty.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <poll.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <errno.h>

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "pty: usage: %s <program> [args...]\n", argv[0]);
        return 2;
    }

    // Initial window size — the bridge sends a real TIOCSWINSZ via ESC 0xFE as soon
    // as xterm.js reports its dimensions; this is just a sane default before then.
    struct winsize ws;
    ws.ws_col = 80; ws.ws_row = 24; ws.ws_xpixel = 0; ws.ws_ypixel = 0;

    int master = -1;
    pid_t pid = forkpty(&master, nullptr, nullptr, &ws);
    if (pid < 0) { perror("pty: forkpty"); return 1; }

    if (pid == 0) {
        // ── child ── login_tty (inside forkpty) already made the slave our
        // controlling terminal and dup'd it to 0/1/2. Close every OTHER inherited
        // fd (incl. the master) so proot doesn't ptrace-scan the app's fd table.
        long maxfd = sysconf(_SC_OPEN_MAX);
        if (maxfd < 0 || maxfd > 4096) maxfd = 4096;   // cap; RLIMIT can be huge
        for (int fd = 3; fd < (int)maxfd; fd++) close(fd);
        execv(argv[1], &argv[1]);                       // argv is NULL-terminated by the OS
        perror("pty: execv");
        _exit(127);
    }

    // ── parent ── relay stdin<->master, stripping the ESC 0xFE resize sequence.
    signal(SIGPIPE, SIG_IGN);

    // Resize-sequence state machine (the 6 bytes can split across reads).
    enum { S_NORMAL, S_ESC, S_FE, S_C1, S_C0, S_R1 } st = S_NORMAL;
    unsigned char rb[4];   // hiCols, loCols, hiRows, loRows

    char buf[8192];
    char out[sizeof(buf) + 2];   // +2: a pending ESC may be flushed ahead of a byte

    struct pollfd fds[2];
    fds[0].fd = STDIN_FILENO; fds[0].events = POLLIN;
    fds[1].fd = master;       fds[1].events = POLLIN;

    for (;;) {
        int n = poll(fds, 2, -1);
        if (n < 0) { if (errno == EINTR) continue; break; }

        // shell output: master -> stdout (-> bridge -> socket -> xterm.js)
        if (fds[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t r = read(master, buf, sizeof(buf));
            if (r <= 0) break;   // shell exited / master closed
            for (ssize_t off = 0; off < r; ) {
                ssize_t w = write(STDOUT_FILENO, buf + off, r - off);
                if (w < 0) { if (errno == EINTR) continue; goto done; }
                off += w;
            }
        }

        // user input: stdin -> master, with ESC 0xFE resize stripped
        if (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t r = read(STDIN_FILENO, buf, sizeof(buf));
            if (r <= 0) break;   // input closed → tear down the session
            ssize_t oi = 0;
            for (ssize_t i = 0; i < r; i++) {
                unsigned char c = (unsigned char)buf[i];
                switch (st) {
                    case S_NORMAL:
                        if (c == 0x1B) st = S_ESC;
                        else out[oi++] = (char)c;
                        break;
                    case S_ESC:
                        if (c == 0xFE) { st = S_FE; }            // begin resize seq
                        else if (c == 0x1B) { out[oi++] = 0x1B; }// ESC ESC: emit one, stay
                        else { out[oi++] = 0x1B; out[oi++] = (char)c; st = S_NORMAL; }
                        break;
                    case S_FE: rb[0] = c; st = S_C1; break;
                    case S_C1: rb[1] = c; st = S_C0; break;
                    case S_C0: rb[2] = c; st = S_R1; break;
                    case S_R1: {
                        rb[3] = c; st = S_NORMAL;
                        struct winsize w2;
                        w2.ws_col = (unsigned short)((rb[0] << 8) | rb[1]);
                        w2.ws_row = (unsigned short)((rb[2] << 8) | rb[3]);
                        w2.ws_xpixel = 0; w2.ws_ypixel = 0;
                        if (w2.ws_col && w2.ws_row) ioctl(master, TIOCSWINSZ, &w2);
                        break;
                    }
                }
            }
            for (ssize_t off = 0; off < oi; ) {
                ssize_t w = write(master, out + off, oi - off);
                if (w < 0) { if (errno == EINTR) continue; goto done; }
                off += w;
            }
        }
    }

done:
    kill(pid, SIGHUP);            // tell the shell its terminal went away
    int status = 0;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return 1;
}
