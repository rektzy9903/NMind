package com.claudecodesetup.ui.terminal

import android.view.View

/**
 * Renderer-agnostic seam for the 🐧 Ubuntu PTY terminal.
 *
 * Two implementations satisfy this:
 *  - [WebViewUbuntuTerminal] — xterm.js inside the shared WebView (the default;
 *    base64-encodes bytes back across the JS bridge).
 *  - [NativeUbuntuTerminal] — Termux terminal-view (native Canvas), selected
 *    behind AppPreferences.isNativeTerminalEnabled().
 *
 * TerminalActivity holds one of these and talks only to this interface, so a
 * native build ships BESIDE the WebView path and reverts via a flag with no
 * emergency rebuild. The engine below (ClaudeService PTY socket relay →
 * bridge.js attachPtySession → libpty.so forkpty) is shared and unchanged.
 */
interface UbuntuTerminalView {
    /** The native view to host in the layout, or null when this impl renders
     *  inside the shared WebView (nothing to add). */
    val nativeView: View?

    /** Shell → screen. Raw PTY bytes from ClaudeService (length == bytes.size). */
    fun feed(bytes: ByteArray)

    /** Blank the visible buffer (tab switch / session swap). */
    fun clearScreen()

    /** Toolbar A+/A− nudge (sp delta). */
    fun adjustFont(deltaSp: Int)

    /** Send a raw key sequence (toolbar ↑/↓/Esc/Tab/Ctrl chord). */
    fun sendKey(seq: String)

    /** Raise the soft keyboard / focus the terminal. */
    fun focusForKeyboard()

    /** Called when the Ubuntu view becomes visible (fit/focus). */
    fun onShown()

    /** Release resources (activity destroy). */
    fun dispose()
}
