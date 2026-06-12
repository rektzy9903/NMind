package com.claudecodesetup.ui.terminal

import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy

/**
 * WebView (xterm.js) implementation of [UbuntuTerminalView].
 *
 * This is the CURRENT behavior, relocated verbatim from TerminalActivity:
 *  - [feed] prefers the fast "NexusPty" WebMessageListener channel
 *    (replyProxy.postMessage, no per-chunk JS-source compile — the dropped-
 *    keystroke fix, CLAUDE.md inv 78) and falls back to
 *    evaluateJavascript("window.ptyWrite('…')") when the channel is absent.
 *  - [clearScreen] calls window.ptyClear().
 *
 * [replyProxyProvider] is read on every [feed] so it picks up the proxy once the
 * JS side handshakes "ready" (it is null until then) — same late-bind semantics
 * as the inline code it replaces.
 */
class WebViewUbuntuTerminal(
    private val webView: WebView,
    private val replyProxyProvider: () -> JavaScriptReplyProxy?,
) : UbuntuTerminalView {

    override fun feed(b64: String) {
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
}
