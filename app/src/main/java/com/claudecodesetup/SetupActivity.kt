package com.claudecodesetup

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.databinding.ActivitySetupBinding
import com.claudecodesetup.managers.DownloadManager
import com.claudecodesetup.managers.EnvironmentManager
import com.claudecodesetup.managers.SetupOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: AppPreferences
    private lateinit var orchestrator: SetupOrchestrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        orchestrator = SetupOrchestrator(
            this,
            EnvironmentManager(this),
            DownloadManager(),
            prefs
        )

        requestNotificationPermission()
        checkWifiAndBegin()

        binding.btnRetry.setOnClickListener { checkWifiAndBegin() }
        binding.btnDetails.setOnClickListener { toggleDetails() }
    }

    // ─── WiFi gate ────────────────────────────────────────────────────────────

    private fun checkWifiAndBegin() {
        if (!isOnWifi()) {
            showWifiWarning()
        } else {
            beginSetup()
        }
    }

    private fun showWifiWarning() {
        AlertDialog.Builder(this)
            .setTitle("Large download ahead")
            .setMessage(
                "First-time setup needs ~500MB download.\n\n" +
                "Connect to WiFi to avoid data charges?"
            )
            .setPositiveButton("Use WiFi") { _, _ ->
                // Open WiFi settings
                startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            }
            .setNegativeButton("Continue on mobile data") { _, _ ->
                beginSetup()
            }
            .setCancelable(false)
            .show()
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    // ─── Setup execution ──────────────────────────────────────────────────────

    private fun beginSetup() {
        showProgress()
        val startStep = prefs.getSetupStep()

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                orchestrator.run(
                    onStep = { msg ->
                        runOnUiThread {
                            binding.tvStatus.text = msg
                            appendDetail(msg)
                        }
                    },
                    onProgress = { pct ->
                        runOnUiThread {
                            binding.progressBar.progress = pct
                        }
                    },
                    onError = { err ->
                        runOnUiThread { showError(err) }
                    },
                    startStep = startStep
                )
            }

            if (success) {
                showSuccess()
            }
        }
    }

    // ─── UI states ────────────────────────────────────────────────────────────

    private fun showProgress() {
        binding.layoutProgress.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE
        binding.layoutSuccess.visibility = View.GONE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Getting things ready..."
        binding.tvDetails.text = ""
        binding.btnRetry.visibility = View.GONE
    }

    private fun showError(err: SetupOrchestrator.SetupError) {
        binding.layoutProgress.visibility = View.VISIBLE
        binding.layoutError.visibility = View.VISIBLE
        binding.layoutSuccess.visibility = View.GONE
        binding.tvErrorMsg.text = err.friendlyMessage
        binding.btnRetry.visibility = View.VISIBLE

        err.action?.let { action ->
            binding.btnErrorAction.visibility = View.VISIBLE
            binding.btnErrorAction.text = err.actionLabel ?: "Fix this"
            binding.btnErrorAction.setOnClickListener {
                startActivity(Intent(action))
            }
        }
    }

    private fun showSuccess() {
        binding.layoutProgress.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.layoutSuccess.visibility = View.VISIBLE

        binding.btnContinue.setOnClickListener {
            startActivity(Intent(this, LoginFlowActivity::class.java))
            finish()
        }
    }

    private fun toggleDetails() {
        val v = binding.tvDetails
        if (v.visibility == View.VISIBLE) {
            v.visibility = View.GONE
            binding.btnDetails.text = "Show details"
        } else {
            v.visibility = View.VISIBLE
            binding.btnDetails.text = "Hide details"
        }
    }

    private fun appendDetail(msg: String) {
        binding.tvDetails.append("$msg\n")
    }

    // ─── Notification permission (Android 13+) ────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF
                )
            }
        }
    }

    companion object {
        private const val REQ_NOTIF = 42
    }
}
