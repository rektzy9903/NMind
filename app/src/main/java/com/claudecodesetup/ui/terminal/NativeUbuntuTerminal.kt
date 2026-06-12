package com.claudecodesetup.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

/**
 * Native (Termux terminal-view) implementation of [UbuntuTerminalView].
 *
 * Reuses the vendored, de-JNI'd Termux renderer: a [TerminalView] backed by a
 * [TerminalSession] patched into "external-IO mode" (no local pty fd). Bytes from
 * our PTY socket are fed into the emulator via [TerminalSession.appendToEmulator];
 * user input / resize are forwarded back out through [TerminalSession.ExternalIo]
 * to ClaudeService (sendPty / resizePty) — the same engine the WebView path uses.
 *
 * One class implements all three interfaces ([UbuntuTerminalView],
 * [TerminalViewClient], [TerminalSessionClient]); the two client interfaces share
 * identical log* signatures so a single impl satisfies both.
 *
 * @param sendPty   raw user bytes → ClaudeService.sendPty(activeSid, …)
 * @param resizePty grid size → ClaudeService.resizePty(activeSid, cols, rows)
 */
class NativeUbuntuTerminal(
    private val context: Context,
    private val sendPty: (ByteArray) -> Unit,
    private val resizePty: (cols: Int, rows: Int) -> Unit,
) : UbuntuTerminalView, TerminalViewClient, TerminalSessionClient {

    private val density = context.resources.displayMetrics.density
    private val minPx = (8f * density).roundToInt()
    private val maxPx = (24f * density).roundToInt()
    private var textPx = (13f * density).roundToInt()

    // Bytes that arrive before the emulator exists (it is created on first layout
    // via TerminalView.updateSize → onEmulatorSet). Buffered, then flushed once
    // ready so the bash prompt is never lost on a fast open. All on the UI thread.
    private val pending = ArrayList<ByteArray>()
    private var pendingBytes = 0
    private val PENDING_CAP = 256 * 1024

    private val externalIo = object : TerminalSession.ExternalIo {
        override fun onInput(data: ByteArray, offset: Int, count: Int) {
            val out = if (offset == 0 && count == data.size) data
                      else data.copyOfRange(offset, offset + count)
            sendPty(out)
        }
        override fun onResize(columns: Int, rows: Int) = resizePty(columns, rows)
        override fun onFinish() { /* the guest shell lifecycle is managed by bridge.js */ }
    }

    // shellPath/cwd/args/env are IGNORED in external-IO mode (no JNI subprocess);
    // transcriptRows drives scrollback.
    private val session = TerminalSession(
        "/bin/sh", "/", emptyArray(), emptyArray(), 2000, this
    ).also { it.setExternalIo(externalIo) }

    private val terminalView = TerminalView(context, null).also { tv ->
        tv.setTerminalViewClient(this)
        tv.setTextSize(textPx)            // creates the renderer (required before attach)
        tv.setTypeface(Typeface.MONOSPACE)
        tv.keepScreenOn = true
        tv.attachSession(session)          // emulator initializes on first layout
    }

    // ── UbuntuTerminalView ────────────────────────────────────────────────────

    override val nativeView: View get() = terminalView

    override fun feed(bytes: ByteArray) {
        val em = session.emulator
        if (em == null) {
            if (pendingBytes < PENDING_CAP) { pending.add(bytes); pendingBytes += bytes.size }
            return
        }
        if (pending.isNotEmpty()) flushPending()
        session.appendToEmulator(bytes, bytes.size)
    }

    private fun flushPending() {
        if (session.emulator == null || pending.isEmpty()) return
        for (b in pending) session.appendToEmulator(b, b.size)
        pending.clear(); pendingBytes = 0
    }

    override fun clearScreen() {
        if (session.emulator == null) return
        val clr = "[H[2J[3J".toByteArray(Charsets.UTF_8)
        session.appendToEmulator(clr, clr.size)
    }

    override fun adjustFont(deltaSp: Int) {
        val step = (deltaSp * density).roundToInt().let { if (it == 0) deltaSp else it }
        textPx = (textPx + step).coerceIn(minPx, maxPx)
        terminalView.setTextSize(textPx)
    }

    override fun sendKey(seq: String) {
        session.write(seq)              // TerminalOutput.write(String) → our ExternalIo
        terminalView.requestFocus()
    }

    override fun focusForKeyboard() {
        terminalView.requestFocus()
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) {}
    }

    override fun onShown() {
        terminalView.onScreenUpdated()
        terminalView.post { flushPending(); terminalView.requestFocus() }
    }

    override fun dispose() {
        try { session.finishIfRunning() } catch (_: Exception) {}
    }

    // ── TerminalViewClient ────────────────────────────────────────────────────

    override fun onScale(scale: Float): Float = scale          // ignore pinch-zoom
    override fun onSingleTapUp(e: MotionEvent) { focusForKeyboard() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true  // best IME behavior in a terminal
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false            // Ctrl handled by the native key row (sends \x03 etc.)
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() { flushPending() }            // emulator just became available

    // ── TerminalSessionClient ──────────────────────────────────────────────────

    override fun onTextChanged(changedSession: TerminalSession) { terminalView.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
        } catch (_: Exception) {}
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrEmpty()) this.session.write(text)
        } catch (_: Exception) {}
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null

    // ── shared log* (satisfies both client interfaces) ──────────────────────────

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message ?: "", e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    companion object { private const val TAG = "NativeUbuntuTerminal" }
}
