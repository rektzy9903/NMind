package com.claudecodesetup.managers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.util.Log
import com.claudecodesetup.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates the 15-step first-run silent setup.
 * Calls [onStep] with human-readable status text.
 * Calls [onProgress] with 0-100 overall progress.
 * Calls [onError] with friendly error and optional recovery action.
 */
class SetupOrchestrator(
    private val context: Context,
    private val envManager: EnvironmentManager,
    private val downloader: DownloadManager,
    private val prefs: AppPreferences
) {
    private val TAG = "SetupOrchestrator"

    data class SetupError(
        val friendlyMessage: String,
        val action: String? = null,
        val actionLabel: String? = null,
        val recoverable: Boolean = true
    )

    companion object {
        private val STEP_WEIGHTS = intArrayOf(2, 2, 1, 2, 10, 8, 20, 5, 3, 5, 25, 10, 2, 3, 2)

        /** Returns cumulative progress (0–100) at the start of [step] for pre-seeding the bar. */
        fun progressForStep(step: Int): Int =
            STEP_WEIGHTS.take(step.coerceIn(0, STEP_WEIGHTS.size)).sum()
    }

    // Step weights for progress calculation (total = 100)
    private val stepWeights = STEP_WEIGHTS

    suspend fun run(
        onStep: (String) -> Unit,
        onProgress: (Int) -> Unit,
        onError: (SetupError) -> Unit,
        startStep: Int = 0
    ): Boolean {
        // Shadow as mutable so self-heal can reset it before overall is calculated.
        @Suppress("NAME_SHADOWING")
        var startStep = startStep

        // Ensure base directories always exist — cheap and idempotent.
        envManager.filesDir.mkdirs()
        envManager.homeDir.mkdirs()
        envManager.tmpDir.mkdirs()

        // Self-heal: if bash disappeared after setup progressed past step 4 (e.g.
        // OS cleared app files, re-install edge case, EncryptedSharedPreferences
        // inconsistency), fall back and re-extract the bootstrap before any
        // bash-dependent step tries to run.
        if (startStep > 4 && !envManager.isBootstrapped()) {
            Log.w(TAG, "Bootstrap missing at step $startStep — resetting to step 4")
            prefs.setSetupStep(4)
            startStep = 4
        }

        var overall = stepWeights.take(startStep).sum()

        fun stepProgress(step: Int, pct: Int) {
            val base = stepWeights.take(step).sum()
            overall = base + (stepWeights[step] * pct / 100)
            onProgress(overall)
        }

        // Step 0: Check internet
        if (startStep <= 0) {
            onStep("Checking internet connection...")
            if (!hasInternet()) {
                onError(SetupError("Please connect to the internet to complete setup"))
                return false
            }
            prefs.setSetupStep(1)
            stepProgress(0, 100)
        }

        // Step 1: Check storage (need 1GB)
        if (startStep <= 1) {
            onStep("Checking available storage...")
            if (!hasEnoughStorage(1_073_741_824L)) {
                onError(SetupError(
                    "Please free up at least 1GB of storage before continuing",
                    "android.settings.INTERNAL_STORAGE_SETTINGS",
                    "Open Storage Settings"
                ))
                return false
            }
            prefs.setSetupStep(2)
            stepProgress(1, 100)
        }

        // Step 2: WiFi check (informational, handled by caller)
        // Guard ensures setSetupStep and stepProgress don't run when resuming
        // from a later step, which would corrupt the saved resume point and
        // make the progress bar jump backwards.
        if (startStep <= 2) {
            prefs.setSetupStep(3)
            stepProgress(2, 100)
        }

        // Step 3: Detect CPU architecture
        if (startStep <= 3) {
            onStep("Detecting your device...")
            val arch = envManager.detectArch()
            prefs.setDetectedArch(arch)
            Log.i(TAG, "Detected arch: $arch")
            prefs.setSetupStep(4)
            stepProgress(3, 100)
        }

        val arch = prefs.getDetectedArch().ifEmpty { "arm64" }

        // Step 4: Initialize / extract Termux bootstrap
        if (startStep <= 4) {
            onStep("Initializing Linux environment...")
            if (!envManager.isBootstrapped()) {
                val bootstrapArch = envManager.bootstrapArchString(arch)
                val bootstrapUrl = "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-$bootstrapArch.zip"
                val bootstrapFile = File(context.filesDir, "bootstrap.zip")

                val dlResult = downloader.download(bootstrapUrl, bootstrapFile) { pct ->
                    stepProgress(4, (pct * 0.7).toInt())
                }
                if (!dlResult.success) {
                    onError(SetupError("Download failed: ${dlResult.error ?: "unknown"}. Check your connection and try again."))
                    return false
                }

                val extracted = envManager.extractBootstrap(bootstrapFile) { pct ->
                    stepProgress(4, 70 + (pct * 0.3).toInt())
                }
                if (!extracted) {
                    onError(SetupError("Setup failed during extraction. Try restarting the app."))
                    return false
                }
                bootstrapFile.delete()
            }
            prefs.setSetupStep(5)
            stepProgress(4, 100)
        }

        // Step 5: Install proot-distro
        if (startStep <= 5) {
            onStep("Installing system tools...")
            val result = envManager.runInTermux(
                "pkg install -y proot proot-distro curl python",
                timeoutSeconds = 300
            )
            if (result.exitCode != 0) {
                Log.w(TAG, "proot-distro install stderr: ${result.stderr}")
            }
            prefs.setSetupStep(6)
            stepProgress(5, 100)
        }

        // Step 6: Install Ubuntu via proot-distro
        // This downloads ~300-700 MB and can take several minutes.
        // A background heartbeat thread updates the status message every 15 s
        // so the user sees real progress instead of a frozen screen.
        if (startStep <= 6) {
            if (!envManager.isUbuntuInstalled()) {
                onStep("Setting up Linux (this takes 2-3 minutes)...")

                val heartbeat = Thread {
                    var secs = 0
                    try {
                        while (true) {
                            Thread.sleep(15_000)
                            secs += 15
                            onStep("Setting up Linux... (${secs}s elapsed, please wait)")
                        }
                    } catch (_: InterruptedException) {}
                }.also { it.isDaemon = true; it.start() }

                val result = envManager.runInTermux(
                    "proot-distro install ubuntu",
                    timeoutSeconds = 900 // 15 minutes max for large downloads
                )
                heartbeat.interrupt()
                heartbeat.join(1000)

                if (result.exitCode != 0 && !envManager.isUbuntuInstalled()) {
                    val detail = result.stderr.take(200).ifBlank { result.stdout.take(200) }.trim()
                    onError(SetupError(
                        "Could not install Linux environment." +
                        if (detail.isNotBlank()) "\n\n$detail" else "\n\nCheck internet and try again."
                    ))
                    return false
                }
            }
            prefs.setSetupStep(7)
            stepProgress(6, 100)
        }

        // Step 7: Download Node.js
        if (startStep <= 7) {
            if (!envManager.isNodeInstalled(arch)) {
                val nodeArch = envManager.nodeArchString(arch)
                val nodeTar = "node-v20.11.0-linux-$nodeArch.tar.gz"
                val nodeUrl = "https://nodejs.org/dist/v20.11.0/$nodeTar"
                val nodeFile = File(envManager.homeDir, nodeTar)

                onStep("Downloading Node.js ($nodeArch)...")
                val dlResult = downloader.download(nodeUrl, nodeFile) { pct ->
                    stepProgress(7, pct)
                }
                if (!dlResult.success) {
                    onError(SetupError("Node.js download failed. Please try again on WiFi."))
                    return false
                }
            }
            prefs.setSetupStep(8)
            stepProgress(7, 100)
        }

        // Step 8: Install Node.js inside Ubuntu
        if (startStep <= 8) {
            onStep("Installing Node.js...")
            val nodeArch = envManager.nodeArchString(arch)
            val nodeTar = "node-v20.11.0-linux-$nodeArch.tar.gz"

            envManager.runInUbuntu(
                "cd ~ && tar -xzf $nodeTar --no-same-owner 2>/dev/null || true && " +
                "echo 'export PATH=\$HOME/node-v20.11.0-linux-$nodeArch/bin:\$PATH' >> ~/.bashrc && " +
                "echo 'export PATH=\$HOME/node-v20.11.0-linux-$nodeArch/bin:\$PATH' >> ~/.profile",
                timeoutSeconds = 180
            )
            File(envManager.homeDir, nodeTar).delete()
            prefs.setSetupStep(9)
            stepProgress(8, 100)
        }

        // Step 9: Install Python + uv
        if (startStep <= 9) {
            onStep("Installing Python tools...")
            envManager.runInUbuntu(
                "apt-get update -qq && apt-get install -y -qq python3 python3-pip curl lsof 2>/dev/null; " +
                "pip install uv --break-system-packages 2>/dev/null; " +
                "pip3 install uv --break-system-packages 2>/dev/null || true",
                timeoutSeconds = 300
            )
            prefs.setSetupStep(10)
            stepProgress(9, 100)
        }

        // Step 10: Download free-claude-code proxy
        if (startStep <= 10) {
            if (!envManager.isProxyInstalled()) {
                onStep("Downloading Claude Code proxy...")
                val proxyZip = File(envManager.homeDir, "proxy.zip")
                val primaryUrl = "https://github.com/Alishahryar1/free-claude-code/archive/refs/heads/main.zip"
                val fallbackUrl = "https://codeload.github.com/Alishahryar1/free-claude-code/zip/refs/heads/main"

                val dlResult = downloader.downloadWithFallback(primaryUrl, fallbackUrl, proxyZip) { pct ->
                    stepProgress(10, pct)
                }
                if (!dlResult.success) {
                    onError(SetupError("Could not download proxy. GitHub may be slow. Will retry on next launch."))
                    return false
                }

                // Extract with Python (no unzip dependency)
                envManager.runInUbuntu(
                    "cd ~ && python3 -c \"import zipfile; zipfile.ZipFile('proxy.zip').extractall('.')\" && rm proxy.zip",
                    timeoutSeconds = 120
                )
                proxyZip.delete()
            }
            prefs.setSetupStep(11)
            stepProgress(10, 100)
        }

        // Step 11: Install Claude Code (latest or pinned fallback)
        if (startStep <= 11) {
            onStep("Fetching latest Claude Code version...")
            val claudeVersion = downloader.fetchLatestClaudeVersion()
            prefs.setInstalledClaudeVersion(claudeVersion)
            onStep("Installing Claude Code ($claudeVersion)...")
            val nodeArch = envManager.nodeArchString(arch)
            val result = envManager.runInUbuntu(
                "export PATH=\$HOME/node-v20.11.0-linux-$nodeArch/bin:\$PATH && " +
                "npm install -g @anthropic-ai/claude-code@$claudeVersion --no-audit --no-fund 2>&1",
                timeoutSeconds = 300
            )
            val claudeOk = result.stdout.contains("added") || envManager.isClaudeInstalled()
            if (!claudeOk) {
                Log.w(TAG, "npm install output: ${result.stdout}")
            }
            prefs.setSetupStep(12)
            stepProgress(11, 100)
        }

        // Step 12: Install proxy dependencies
        if (startStep <= 12) {
            onStep("Installing proxy dependencies...")
            envManager.runInUbuntu(
                "cd ~/free-claude-code-main && " +
                "~/.local/bin/uv sync 2>/dev/null || pip install uvicorn fastapi httpx 2>/dev/null || true",
                timeoutSeconds = 300
            )
            prefs.setSetupStep(13)
            stepProgress(12, 100)
        }

        // Step 13: Write default .env
        if (startStep <= 13) {
            val envFile = File(envManager.homeDir, "free-claude-code-main/.env")
            if (!envFile.exists()) {
                envFile.writeText("# Configured by ClaudeCode Setup\nANTHROPIC_AUTH_TOKEN=freecc\nREQUEST_TIMEOUT=120\n")
            }
            prefs.setSetupStep(14)
            stepProgress(13, 100)
        }

        // Step 14: Smoke test
        if (startStep <= 14) {
            onStep("Verifying installation...")
            val nodeArch = envManager.nodeArchString(arch)
            val result = envManager.runInUbuntu(
                "export PATH=\$HOME/node-v20.11.0-linux-$nodeArch/bin:\$PATH && claude --version 2>&1",
                timeoutSeconds = 30
            )
            val ok = result.stdout.contains(".") || result.exitCode == 0
            if (!ok) {
                Log.w(TAG, "Smoke test failed: ${result.stdout} ${result.stderr}")
            }
            stepProgress(14, 100)
        }

        prefs.setSetupStep(15)
        prefs.setSetupComplete(true)
        onProgress(100)
        return true
    }

    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun hasEnoughStorage(bytes: Long): Boolean {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes >= bytes
        } catch (e: Exception) { true }
    }
}
