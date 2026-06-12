package com.claudecodesetup.ui.terminal

import android.util.Base64
import android.view.View
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy

/**
 * WebView (xterm.js) implementation of [UbuntuTerminalView] — the default path.
 *
 * Behaviorally identical to the original inline code in TerminalActivity:
 *  - [feed] base64-encodes the raw bytes (ClaudeService no longer does this) and
 *    prefers the fast "NexusPty" WebMessageListener channel (replyProxy.postMessage,
 *    no per-chunk JS-source compile — the dropped-keystroke fix, CLAUDE.md inv 78),
 *    falling back to evaluateJavascript("window.ptyWrite('…')").
 *  - font / key / focus delegate to the existing JS globals (only invoked by the
 *    native control row; in WebView mode the in-page toolbar drives those itself,
 *    so these are inert here — preserving the unchanged default behavior).
 *
 * [replyProxyProvider] is read on every [feed] so it late-binds once the JS side
 * handshakes "ready" — same semantics as the inline code it replaces.
 */
class WebViewUbuntuTerminal(
    private val webView: WebView,
    private val replyProxyProvider: () -> JavaScriptReplyProxy?,
) : UbuntuTerminalView {

    override val nativeView: View? = null

    override fun feed(bytes: ByteArray) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val proxy = replyProxyProvider()
        if (proxy != null) {
            try {
                proxy.postMessage(b64)
                return
            } catch (_: Exception) {
                // fall through to the evaluateJavascript path
            }
        }
        webView.evaluateJavascript("window.ptyWrite('$b64')", null)
    }

    override fun clearScreen() {
        webView.evaluateJavascript("window.ptyClear&&window.ptyClear()", null)
    }

    override fun adjustFont(deltaSp: Int) {
        webView.evaluateJavascript("window.adjustXtermFont&&window.adjustXtermFont($deltaSp)", null)
    }

    override fun sendKey(seq: String) {
        val esc = seq.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("window.ptyKey&&window.ptyKey('$esc')", null)
    }

    override fun focusForKeyboard() {
        webView.evaluateJavascript("window.__focusTerm&&window.__focusTerm()", null)
    }

    override fun onShown() {}
    override fun dispose() {}
}
