// fdexec — close all inherited file descriptors > 2, then exec a program.
//
// Shipped as libfdexec.so (a PIE executable, like libproot.so / libnode-
// launcher.so) so AGP packages it in lib/<abi>/ and Android extracts it to
// nativeLibraryDir where it is exec-capable (API 29+).
//
// Why this exists: libnode runs INSIDE the Android app process, so any child
// it spawns (proot) inherits hundreds of framework fds (WebView, goldfish_pipe
// / ashmem graphics, mmap'd .apk) that lack FD_CLOEXEC. proot ptrace-processes
// every inherited fd and hangs on the emulator. The obvious fix — closing them
// in an `sh -c` wrapper via `exec N<&-` — is impossible on Android's mksh:
// (a) `done 2>/dev/null` makes mksh hold a saved-stderr fd open across the loop
//     which the loop then closes, aborting the shell; and
// (b) mksh's redirection lexer rejects high multi-digit fd numbers as IO_NUMBER,
//     so `exec 104<&-` is parsed as the command "104" → "inaccessible or not
//     found" → exit 127.
// close(2) in C has none of these problems: it accepts any fd number and a bad
// fd is a harmless EBADF no-op. So we do the close here and execv the target.
//
// Usage: libfdexec.so <program> [args...]
//   e.g. libfdexec.so /…/libproot.so -r /…/ubuntu /usr/bin/cat /etc/os-release

#include <unistd.h>
#include <stdio.h>

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "fdexec: usage: %s <program> [args...]\n", argv[0]);
        return 2;
    }

    // Close every fd above stderr. Bad/unopened fds just return EBADF — ignored.
    long maxfd = sysconf(_SC_OPEN_MAX);
    if (maxfd < 0 || maxfd > 4096) maxfd = 4096;   // cap the scan; RLIMIT can be huge
    for (int fd = 3; fd < (int)maxfd; fd++) {
        close(fd);
    }

    // argv[1..] is already NULL-terminated by the OS; exec the target in place.
    execv(argv[1], &argv[1]);

    // Only reached if exec failed.
    perror("fdexec: execv");
    return 127;
}
