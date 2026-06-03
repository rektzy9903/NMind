package com.claudecodesetup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.databinding.ActivitySetupBinding
import com.claudecodesetup.managers.NodeBridgeManager
import com.claudecodesetup.managers.UbuntuRootfsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * First-run engine provisioning. A fresh install has no engine; this screen
 * installs it once, in two stages, then proceeds (decisions: before-login /
 * warn-on-mobile-data / retry+log — see project-first-run-provisioning):
 *   Stage 1 (Kotlin):  UbuntuRootfsManager.installRootfs() — download + extract.
 *   Stage 2 (bridge):  drop `provision_requested`; bridge runEngineSetup installs
 *                      Node 22 + claude-code, streaming progress to setup.log.
 * Idempotent: an in-place APK update keeps filesDir, so this runs once.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: AppPreferences
    private lateinit var bridge: NodeBridgeManager
    private lateinit var rootfs: UbuntuRootfsManager

    private var job: Job? = null
    private var meteredConfirmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs  = AppPreferences(this)
        bridge = NodeBridgeManager(this)
        rootfs = UbuntuRootfsManager(this)

        showInitialState()

        binding.btnStartSetup.setOnClickListener { startSetup() }
        binding.btnRetry.setOnClickListener      { startSetup() }
        binding.btnContinue.setOnClickListener   { proceedToNext() }
        binding.btnCopyLog.setOnClickListener    { copyLogToClipboard() }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
    }

    // ─── States ───────────────────────────────────────────────────────────────

    private fun showInitialState() {
        binding.layoutInitial.visibility = View.VISIBLE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutSuccess.visibility = View.GONE
        binding.layoutError.visibility   = View.GONE
    }

    private fun showWaitingState() {
        binding.layoutInitial.visibility = View.GONE
        binding.layoutWaiting.visibility = View.VISIBLE
        binding.layoutSuccess.visibility = View.GONE
        binding.layoutError.visibility   = View.GONE
        binding.progressSetup.progress   = 0
        binding.tvCurrentStep.text       = "Starting…"
        binding.tvTaskLog.text           = ""
    }

    private fun showSuccessState() {
        binding.layoutInitial.visibility = View.GONE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutSuccess.visibility = View.VISIBLE
        binding.layoutError.visibility   = View.GONE
    }

    private fun showErrorState(msg: String) {
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutError.visibility   = View.VISIBLE
        binding.tvErrorMsg.text          = msg
        binding.tvErrorLog.text          = bridge.readSetupLog()
        binding.scrollErrorLog.post { binding.scrollErrorLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyLogToClipboard() {
        val log = bridge.readSetupLog().ifEmpty { binding.tvErrorLog.text.toString() }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Setup Log", log))
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // ─── Provisioning flow ──────────────────────────────────────────────────────

    private fun startSetup() {
        binding.layoutError.visibility = View.GONE
        showWaitingState()
        job?.cancel()
        job = lifecycleScope.launch {
            try {
                // Already provisioned (e.g. in-place update over a working engine)?
                // Skip the whole flow — no re-download, no re-install. SplashActivity
                // normally catches this first, but guard here too in case the user
                // already landed on this screen.
                if (withContext(Dispatchers.IO) { rootfs.isClaudeInstalled() }) {
                    setStep("Engine already installed", 100)
                    onProvisioned()
                    return@launch
                }

                // Boot the bridge (libnode) so its provision watcher polls for the
                // request marker and runEngineSetup can call into the proot guest.
                withContext(Dispatchers.IO) { bridge.startSetup() }
                setStep("Starting engine…", 2)

                // Warn (allow) on metered data — quote the DOWNLOAD size, not disk.
                if (isMetered() && !meteredConfirmed) {
                    if (!confirmMeteredDownload()) { showInitialState(); return@launch }
                    meteredConfirmed = true
                }

                // Fail fast on low storage (extraction needs the headroom).
                if (!rootfs.hasEnoughStorage()) {
                    showErrorState("Not enough free storage. The engine needs about 250 MB free. " +
                        "Free up some space and tap Try again.")
                    return@launch
                }

                // Stage 1 — rootfs download + extract (mapped into the 0–40% band).
                if (!rootfs.isInstalled()) {
                    val res = rootfs.installRootfs { step ->
                        runOnUiThread {
                            val p = if (step.pct < 0) -1 else step.pct * 40 / 100
                            setStep(step.phase, p)
                        }
                    }
                    if (!res.success) {
                        showErrorState("Couldn't prepare the Ubuntu environment:\n${res.message}\n\nTap Try again.")
                        return@launch
                    }
                }
                setStep("Ubuntu ready — installing Claude engine…", 40)

                // Stage 2 — trigger the bridge's runEngineSetup, then poll its
                // setup.log progress + completion markers.
                withContext(Dispatchers.IO) {
                    bridge.clearProvisionMarkers()
                    bridge.requestProvision()
                }
                pollProvision()
            } catch (e: Exception) {
                showErrorState("Setup error: ${e.message}\n\nTap Try again.")
            }
        }
    }

    private suspend fun pollProvision() {
        while (coroutineContext.isActive) {
            val log    = withContext(Dispatchers.IO) { bridge.readSetupLog() }
            applyProvisionLog(log)
            val ok     = withContext(Dispatchers.IO) { bridge.isEngineProvisioned() }
            val failed = withContext(Dispatchers.IO) { bridge.isProvisionFailed() }
            when {
                ok     -> { onProvisioned(); return }
                failed -> { showErrorState("Engine install failed. See the log below.\n\nTap Try again."); return }
            }
            delay(POLL_MS)
        }
    }

    /** Parse the bridge's "[provision] pct=NN TAG msg" lines into the bar + label.
     *  Engine-install progress (pct 0–100) maps into the 40–100% band (Stage 1
     *  owns 0–40). */
    private fun applyProvisionLog(text: String) {
        if (text.isBlank()) return
        binding.tvTaskLog.text = text
        binding.scrollTaskLog.post { binding.scrollTaskLog.fullScroll(View.FOCUS_DOWN) }
        val last = text.lineSequence().lastOrNull { it.contains("[provision]") } ?: return
        Regex("pct=(\\d+)").find(last)?.groupValues?.get(1)?.toIntOrNull()?.let { pct ->
            binding.progressSetup.progress = 40 + pct * 60 / 100
        }
        val msg = last.substringAfter("[provision]")
            .replace(Regex("pct=\\d+"), "")
            .trim()
            .removePrefix("STAGE").removePrefix("DONE").removePrefix("OK")
            .removePrefix("ERR").removePrefix("..").trim()
        if (msg.isNotEmpty()) binding.tvCurrentStep.text = msg.take(60)
    }

    private fun onProvisioned() {
        binding.progressSetup.progress = 100
        prefs.setEngineProvisioned(true)
        prefs.setNodeSetupComplete(true) // legacy gate kept in sync
        val v = bridge.readClaudeVersion()
        if (v.isNotEmpty()) prefs.setInstalledClaudeVersion(v)
        showSuccessState()
    }

    private fun proceedToNext() {
        // Before-login: provisioning done → continue to the normal entry. Home
        // handles an unconfigured provider by routing into the login flow.
        startActivity(Intent(this, com.claudecodesetup.ui.HomeActivity::class.java))
        finish()
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private fun setStep(label: String, pct: Int) {
        binding.tvCurrentStep.text = label.take(60)
        if (pct >= 0) binding.progressSetup.progress = pct
    }

    private fun isMetered(): Boolean = try {
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) { false }

    private suspend fun confirmMeteredDownload(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        AlertDialog.Builder(this)
            .setTitle("Use mobile data?")
            .setMessage("Setting up the AI engine downloads about 60–80 MB (Ubuntu + Node + " +
                "Claude Code). You're on mobile data — continue?")
            .setPositiveButton("Continue") { _, _ -> deferred.complete(true) }
            .setNegativeButton("Cancel")   { _, _ -> deferred.complete(false) }
            .setCancelable(false)
            .show()
        return deferred.await()
    }

    companion object {
        private const val POLL_MS = 1_500L
    }
}
