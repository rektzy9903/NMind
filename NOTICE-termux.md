# Vendored Termux terminal renderer

`terminal-emulator/` and `terminal-view/` are vendored from
[termux/termux-app](https://github.com/termux/termux-app) (v0.118.0), licensed
under the **Apache License 2.0**.

## What we use
The pure-Java terminal **emulator** (VT100/xterm byte parser) and **view**
(Android `View` renderer + input/IME/scrollback). These replace the xterm.js
WebView renderer for the 🐧 Ubuntu terminal.

## What we removed / changed
- **Deleted** `terminal-emulator/src/main/jni/` (`termux.c`, `Android.mk`) and
  `JNI.java` — Termux's pseudo-terminal **subprocess** layer. We do **not** spawn
  a process here, so **no `libtermux.so` is built or shipped**.
- **Patched** `TerminalSession.java` into "external-IO mode": instead of a JNI
  pty fd, it is fed raw bytes via `appendToEmulator(byte[],int)` from our own PTY
  socket, and forwards user input / resize / finish through a small
  `TerminalSession.ExternalIo` sink. The shell itself lives in the existing
  Nexus engine (`libpty.so` + `bridge.js attachPtySession`), unchanged.
- Module `build.gradle` files stripped of `maven-publish`, native build, and the
  JitPack/NDK plumbing; SDK levels pinned to match the app (compile/target 34,
  min 29), Java 17.
- Removed `src/test` (unit tests).

Apache-2.0 license text: https://www.apache.org/licenses/LICENSE-2.0
Original copyright: the Termux developers.
