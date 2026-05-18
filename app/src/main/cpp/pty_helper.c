/*
 * pty_helper — allocates a POSIX PTY and relays stdin/stdout between the
 * caller (bridge.js via spawn) and the child process.  Named libpty-helper.so
 * so Android AGP packages it in lib/<abi>/ and API 29+ can execute it.
 *
 * Usage: pty_helper <cols> <rows> <command> [args...]
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

extern char **environ;

#define BUF 4096

static int    g_master   = -1;
static pid_t  g_child_pid = -1;

static void write_all(int fd, const char *buf, ssize_t n) {
    ssize_t written = 0;
    while (written < n) {
        ssize_t w = write(fd, buf + written, (size_t)(n - written));
        if (w <= 0) return;
        written += w;
    }
}

/* Relay bytes from stdin to the PTY master, intercepting the 6-byte in-band
 * resize sequence ESC 0xFF cols_hi cols_lo rows_hi rows_lo.  All other bytes
 * are forwarded verbatim.  The sequence is produced by bridge.js when the
 * Android WebView reports a window resize. */
static void relay_with_resize(int master, const char *buf, ssize_t n) {
    ssize_t i = 0;
    while (i < n) {
        if (i + 5 < n
            && (unsigned char)buf[i]   == 0x1b
            && (unsigned char)buf[i+1] == 0xff) {
            struct winsize ws;
            ws.ws_col    = ((unsigned char)buf[i+2] << 8) | (unsigned char)buf[i+3];
            ws.ws_row    = ((unsigned char)buf[i+4] << 8) | (unsigned char)buf[i+5];
            ws.ws_xpixel = 0; ws.ws_ypixel = 0;
            ioctl(master, TIOCSWINSZ, &ws);
            if (g_child_pid > 0) kill(-g_child_pid, SIGWINCH);
            i += 6;
            continue;
        }
        /* Find end of non-resize run */
        ssize_t j = i + 1;
        while (j < n) {
            if ((unsigned char)buf[j] == 0x1b && j + 1 < n && (unsigned char)buf[j+1] == 0xff)
                break;
            j++;
        }
        write_all(master, buf + i, j - i);
        i = j;
    }
}

int main(int argc, char *argv[]) {
    if (argc < 4) {
        fprintf(stderr, "usage: pty-helper <cols> <rows> <command> [args...]\n");
        return 1;
    }

    int cols = atoi(argv[1]);
    int rows = atoi(argv[2]);
    if (cols <= 0) cols = 220;
    if (rows <= 0) rows = 50;

    g_master = posix_openpt(O_RDWR | O_NOCTTY);
    if (g_master < 0) { perror("posix_openpt"); return 1; }
    if (grantpt(g_master) < 0)  { perror("grantpt");  return 1; }
    if (unlockpt(g_master) < 0) { perror("unlockpt"); return 1; }

    /* ptsname is not thread-safe but fine for single-threaded use here */
    char *sname = ptsname(g_master);
    if (!sname) { perror("ptsname"); return 1; }
    char slave_name[256];
    strncpy(slave_name, sname, sizeof(slave_name) - 1);
    slave_name[sizeof(slave_name) - 1] = '\0';

    /* Set PTY window size from the cols/rows args */
    struct winsize ws;
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    ws.ws_xpixel = 0; ws.ws_ypixel = 0;
    ioctl(g_master, TIOCSWINSZ, &ws);

    /* Configure termios on slave from parent before spawning.
     * posix_spawn cannot do ioctl/tcsetattr in the child, so we apply
     * settings here — they persist on the PTY device node. */
    {
        int slave_cfg = open(slave_name, O_RDWR | O_NOCTTY);
        if (slave_cfg >= 0) {
            struct termios t;
            if (tcgetattr(slave_cfg, &t) == 0) {
                t.c_lflag &= ~ECHO;   /* no input echo */
                t.c_lflag |=  ISIG;   /* keep Ctrl+C → SIGINT */
                t.c_oflag &= ~ONLCR;  /* no NL→CRNL so NDJSON isn't mangled */
                tcsetattr(slave_cfg, TCSANOW, &t);
            }
            close(slave_cfg);
        }
    }

    /* Use posix_spawn instead of fork+execvp.  Android's SELinux policy
     * blocks the fork→exec chain from a process that is already a child of
     * Node.js, but posix_spawn uses clone() internally (same path as libuv)
     * and is permitted.  POSIX_SPAWN_SETSID creates a new session so that
     * opening the slave PTY (via addopen) makes it the controlling terminal
     * automatically — no TIOCSCTTY needed. */
    posix_spawn_file_actions_t fa;
    posix_spawnattr_t sa;
    posix_spawn_file_actions_init(&fa);
    posix_spawnattr_init(&sa);

    /* Open slave as stdin; being the first terminal open in a new session
     * makes it the controlling terminal without an explicit TIOCSCTTY. */
    posix_spawn_file_actions_addopen(&fa, STDIN_FILENO, slave_name, O_RDWR, 0);
    posix_spawn_file_actions_adddup2(&fa, STDIN_FILENO, STDOUT_FILENO);
    posix_spawn_file_actions_adddup2(&fa, STDIN_FILENO, STDERR_FILENO);
    posix_spawn_file_actions_addclose(&fa, g_master);

    posix_spawnattr_setflags(&sa, POSIX_SPAWN_SETSID);

    pid_t pid;
    int spawn_ret = posix_spawn(&pid, argv[3], &fa, &sa, argv + 3, environ);
    posix_spawn_file_actions_destroy(&fa);
    posix_spawnattr_destroy(&sa);

    if (spawn_ret != 0) {
        errno = spawn_ret;
        perror("posix_spawn");
        return 1;
    }

    /* ── parent: relay stdin <-> master ── */
    g_child_pid = pid;

    char buf[BUF];
    struct pollfd fds[2];
    int status = 0;

    for (;;) {
        fds[0].fd = STDIN_FILENO; fds[0].events = POLLIN;
        fds[1].fd = g_master;     fds[1].events = POLLIN;

        int r = poll(fds, 2, 200);
        if (r < 0 && errno == EINTR) continue;

        if (r > 0) {
            if (fds[0].revents & POLLIN) {
                ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
                if (n <= 0) break;
                relay_with_resize(g_master, buf, n);
            }
            if (fds[1].revents & POLLIN) {
                ssize_t n = read(g_master, buf, sizeof(buf));
                if (n <= 0) break;
                write_all(STDOUT_FILENO, buf, n);
            }
        }

        /* non-blocking check whether child exited */
        pid_t ret = waitpid(pid, &status, WNOHANG);
        if (ret == pid) goto done;
    }

    waitpid(pid, &status, 0);
done:
    close(g_master);
    g_master = -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 1;
}
