package com.claudecodesetup.ui.terminal

/**
 * Renderer-agnostic seam for the 🐧 Ubuntu PTY terminal.
 *
 * Today the only implementation is [WebViewUbuntuTerminal] (xterm.js inside the
 * shared WebView) — this interface wraps the existing behavior with ZERO
 * functional change so TerminalActivity stops poking `window.*` JS globals
 * directly and talks to one object instead.
 *
 * Why the seam exists: a future native renderer (Termux terminal-emulator +
 * terminal-view) will implement this same interface, and TerminalActivity will
 * pick the impl behind an AppPreferences flag — so a native build can ship
 * BESIDE the WebView path and fall back instantly without an emergency rebuild.
 *
 * The engine below this seam (ClaudeService PTY socket relay → bridge.js
 * attachPtySession → libpty.so forkpty) is UNCHANGED and shared by both.
 *
 * NOTE: [feed] currently takes a base64 string to match exactly what
 * ClaudeService.onPtyOutput delivers today (no engine change this commit). When
 * the native renderer lands, this becomes a raw `ByteArray` path and the base64
 * round-trip is dropped from the service hot loop — both changes in that commit,
 * together, so this signature stays honest until then.
 */
interface UbuntuTerminalView {
    /** Shell → screen. [b64] is base64-encoded raw PTY bytes (as ClaudeService emits). */
    fun feed(b64: String)

    /** Blank the visible buffer (tab switch / session swap). */
    fun clearScreen()
}
