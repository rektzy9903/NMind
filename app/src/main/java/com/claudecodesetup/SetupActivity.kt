package com.claudecodesetup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.databinding.ActivitySetupBinding
import com.claudecodesetup.managers.NodeBridgeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: AppPreferences
    private lateinit var bridge: NodeBridgeManager

    private val handler    = Handler(Looper.getMainLooper())
    private var monitoring = false

    // ── Fake progress steps shown while npm install runs ──────────────────────
    // (real log appears in the scroll view; these just advance the bar)
    private val progressSteps = listOf(
        Pair(0L,        5),
        Pair(10_000L,  15),
        Pair(30_000L,  30),
        Pair(60_000L,  50),
        Pair(100_000L, 65),
        Pair(140_000L, 80),
        Pair(180_000L, 90),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs  = AppPreferences(this)
        bridge = NodeBridgeManager(this)

        showInitialState()

        binding.btnStartSetup.setOnClickListener { startSetup() }
        binding.btnRetry.setOnClickListener      { startSetup() }
        binding.btnContinue.setOnClickListener   { proceedToNext() }
        binding.btnCopyLog.setOnClickListener    { copyLogToClipboard() }
    }

    override fun onResume() {
        super.onResume()
        if (binding.layoutWaiting.visibility == View.VISIBLE) startMonitoring()
    }

    override fun onPause() {
        super.onPause()
        stopMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
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
        binding.tvCurrentStep.text       = "Starting Node.js..."
        binding.tvTaskLog.text           = ""
    }

    private fun showSuccessState() {
        stopMonitoring()
        binding.layoutInitial.visibility = View.GONE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutSuccess.visibility = View.VISIBLE
        binding.layoutError.visibility   = View.GONE
    }

    private fun showErrorState(msg: String) {
        stopMonitoring()
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutError.visibility   = View.VISIBLE
        binding.tvErrorMsg.text          = msg
        val log = bridge.readSetupLog()
        binding.tvErrorLog.text = log
        binding.scrollErrorLog.post { binding.scrollErrorLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyLogToClipboard() {
        val log = bridge.readSetupLog().ifEmpty { binding.tvErrorLog.text.toString() }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Setup Log", log))
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // ─── Setup flow ───────────────────────────────────────────────────────────

    private fun startSetup() {
        binding.layoutError.visibility = View.GONE
        showWaitingState()
        scheduleProgressSteps()
        bridge.startSetup()   // starts Node.js → bridge.js → npm install
        startMonitoring()
    }

    // ─── Progress bar steps (time-based visual feedback) ─────────────────────

    private fun scheduleProgressSteps() {
        for ((delayMs, progress) in progressSteps) {
            handler.postDelayed({
                if (binding.layoutWaiting.visibility == View.VISIBLE) {
                    binding.progressSetup.progress = progress
                }
            }, delayMs)
        }
    }

    // ─── Log polling ──────────────────────────────────────────────────────────

    private fun refreshLog() {
        val text = bridge.readSetupLog()
        if (text.isNotEmpty()) {
            binding.tvTaskLog.text = text
            binding.scrollTaskLog.post {
                binding.scrollTaskLog.fullScroll(View.FOCUS_DOWN)
            }
            // Derive step label from last non-blank line
            val lastLine = text.trimEnd().lines().lastOrNull { it.isNotBlank() }
            if (lastLine != null) binding.tvCurrentStep.text = lastLine.take(60)
        }
    }

    // ─── Monitor start/stop ───────────────────────────────────────────────────

    private fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && monitoring) {
                withContext(Dispatchers.Main) { refreshLog() }
                val ready  = bridge.isBridgeReachable()
                val failed = bridge.isSetupFailed()
                withContext(Dispatchers.Main) {
                    when {
                        ready  -> onSetupComplete()
                        failed -> onSetupFailed(
                            "Installation failed. See the log above for details.\nTap Try again to retry."
                        )
                    }
                }
                if (ready || failed) break
                delay(POLL_MS)
            }
        }
    }

    private fun stopMonitoring() {
        monitoring = false
        handler.removeCallbacksAndMessages(null)
    }

    // ─── Completion handlers ──────────────────────────────────────────────────

    private fun onSetupComplete() {
        stopMonitoring()
        binding.progressSetup.progress = 100
        refreshLog()
        prefs.setNodeSetupComplete(true)
        showSuccessState()
    }

    private fun onSetupFailed(msg: String) {
        refreshLog()
        showErrorState(msg)
    }

    private fun proceedToNext() {
        startActivity(Intent(this, com.claudecodesetup.ui.HomeActivity::class.java))
        finish()
    }

    companion object {
        private const val POLL_MS = 2_000L
    }
}
