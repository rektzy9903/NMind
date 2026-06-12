package com.claudecodesetup.ui.terminal

import android.view.View

/**
 * Renderer seam for the 🐧 Ubuntu PTY terminal.
 *
 * The sole implementation is [NativeUbuntuTerminal] (Termux terminal-view, native
 * Canvas). The earlier xterm.js WebView renderer was removed — chat (💬) is the
 * WebView's only job now. The interface is retained so the activity talks to a
 * small surface and a future renderer could slot in. The engine below (ClaudeService
 * PTY socket relay → bridge.js attachPtySession → libpty.so forkpty) is unchanged.
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
