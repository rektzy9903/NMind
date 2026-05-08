package com.claudecodesetup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.databinding.ActivitySetupBinding
import com.claudecodesetup.managers.BridgeManager

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: AppPreferences
    private lateinit var bridge: BridgeManager

    private val pollHandler = Handler(Looper.getMainLooper())
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            Thread {
                val reachable = bridge.isBridgeReachable()
                runOnUiThread {
                    if (reachable) {
                        onBridgeDetected()
                    } else if (polling) {
                        pollHandler.postDelayed(this, POLL_INTERVAL_MS)
                    }
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        bridge = BridgeManager(this)

        requestNotificationPermission()
        showInitialState()

        binding.btnStartSetup.setOnClickListener { startSetup() }
        binding.btnRetry.setOnClickListener { startSetup() }
        binding.btnContinue.setOnClickListener { proceedToNext() }
    }

    override fun onResume() {
        super.onResume()
        // Check bridge when returning from Termux (user may have completed setup)
        if (binding.layoutWaiting.visibility == View.VISIBLE) {
            startPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
    }

    // ─── States ──────────────────────────────────────────────────────────────

    private fun showInitialState() {
        binding.layoutInitial.visibility = View.VISIBLE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutSuccess.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
    }

    private fun showWaitingState() {
        binding.layoutInitial.visibility = View.GONE
        binding.layoutWaiting.visibility = View.VISIBLE
        binding.layoutSuccess.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
    }

    private fun showSuccessState() {
        stopPolling()
        binding.layoutInitial.visibility = View.GONE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutSuccess.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE
    }

    private fun showErrorState(msg: String) {
        stopPolling()
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE
        binding.tvErrorMsg.text = msg
    }

    // ─── Setup flow ───────────────────────────────────────────────────────────

    private fun startSetup() {
        binding.layoutError.visibility = View.GONE
        showWaitingState()

        try {
            val scriptContent = assets.open("setup.sh").bufferedReader().readText()
            bridge.runSetupScript(scriptContent)
            startPolling()
        } catch (e: Exception) {
            showErrorState("Could not start setup: ${e.message}")
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        pollHandler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun onBridgeDetected() {
        stopPolling()
        prefs.setTermuxSetupComplete(true)
        showSuccessState()
    }

    private fun proceedToNext() {
        startActivity(Intent(this, LoginFlowActivity::class.java))
        finish()
    }

    // ─── Notification permission ──────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
