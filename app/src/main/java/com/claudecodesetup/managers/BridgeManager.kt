package com.claudecodesetup.managers

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import java.net.Socket

class BridgeManager(private val context: Context) {

    companion object {
        const val BRIDGE_PORT = 8083
        const val PROXY_PORT = 8082
        const val BRIDGE_HOST = "127.0.0.1"
        private const val TAG = "BridgeManager"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    }

    /** True if com.termux is installed on this device. */
    fun isTermuxInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (_: Exception) { false }

    /** True if a TCP connection to the bridge port succeeds. */
    fun isBridgeReachable(): Boolean = try {
        Socket(BRIDGE_HOST, BRIDGE_PORT).use { true }
    } catch (_: Exception) { false }

    /**
     * Open a new TCP session to the socat bridge.
     * Each connection forks a new claude process inside Ubuntu.
     */
    fun openSession(): Socket? = try {
        Socket(BRIDGE_HOST, BRIDGE_PORT)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open session socket", e)
        null
    }

    /**
     * Start (or restart) the bridge in Termux as a background process.
     * Sends a Termux:RUN_COMMAND intent for ~/.claudebridge.sh with the
     * current provider config as arguments.
     */
    fun startBridge(mode: String, apiKey: String, modelId: String, baseUrl: String) {
        val safe = { s: String -> s.replace("'", "'\\''") }
        val cmd = "~/.claudebridge.sh '${safe(mode)}' '${safe(apiKey)}' '${safe(modelId)}' '${safe(baseUrl)}'"
        sendTermuxCommand(cmd, background = true)
        Log.i(TAG, "startBridge sent (mode=$mode)")
    }

    /**
     * Run the setup script in a visible Termux terminal session.
     * Encodes [scriptContent] as base64 to avoid quoting/escaping issues.
     */
    fun runSetupScript(scriptContent: String) {
        val b64 = Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = "printf '%s' '$b64' | base64 -d > ~/.claudesetup.sh && bash ~/.claudesetup.sh"
        sendTermuxCommand(cmd, background = false)
        Log.i(TAG, "Setup script dispatched to Termux")
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun sendTermuxCommand(command: String, background: Boolean) {
        if (!background) {
            // Bring Termux to the foreground so the terminal session is visible.
            // This also ensures Termux's service is running before we send the
            // RunCommandService intent, which requires Termux to already be up.
            try {
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage(TERMUX_PACKAGE)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launchIntent != null) context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not launch Termux activity", e)
            }
        }
        try {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
                // Must be Int — Termux uses getIntExtra() and ignores a String extra
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", 0)
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Termux command (background=$background)", e)
        }
    }
}
