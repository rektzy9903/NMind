package com.claudecodesetup.managers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class EnvironmentManager(private val context: Context) {

    private val TAG = "EnvironmentManager"

    /** Root of our isolated Termux environment */
    val filesDir: File = context.filesDir
    val termuxPrefix: File = File(filesDir, "usr")
    val homeDir: File = File(filesDir, "home")
    val tmpDir: File = File(filesDir, "tmp")

    private val bootstrapZip: File = File(filesDir, "bootstrap.zip")
    private val prootBin: File = File(termuxPrefix, "bin/proot")

    data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

    // ─── Bootstrap ───────────────────────────────────────────────────────────

    fun isBootstrapped(): Boolean = prootBin.exists() && termuxPrefix.resolve("bin/bash").exists()

    suspend fun extractBootstrap(zipFile: File, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                filesDir.mkdirs()
                termuxPrefix.mkdirs()
                homeDir.mkdirs()
                tmpDir.mkdirs()

                val total = countZipEntries(zipFile)
                var count = 0

                java.util.zip.ZipFile(zipFile).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val outFile = File(filesDir, entry.name)

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            zf.getInputStream(entry).use { input ->
                                outFile.outputStream().use { out -> input.copyTo(out) }
                            }
                        }
                        count++
                        onProgress((count.toDouble() / total * 100).toInt())
                    }
                }
                fixPermissions()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap extraction failed", e)
                false
            }
        }

    private fun countZipEntries(zip: File): Int {
        var count = 0
        java.util.zip.ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) { entries.nextElement(); count++ }
        }
        return count.coerceAtLeast(1)
    }

    private fun fixPermissions() {
        listOf("bin", "libexec", "lib/apt/methods").forEach { dir ->
            termuxPrefix.resolve(dir).listFiles()?.forEach { f ->
                f.setExecutable(true, false)
            }
        }
    }

    // ─── Command execution (inside Termux env, no proot) ─────────────────────

    suspend fun runInTermux(vararg cmd: String): ExecResult = withContext(Dispatchers.IO) {
        runProcess(
            buildList {
                add(termuxPrefix.resolve("bin/bash").absolutePath)
                add("-c")
                add(cmd.joinToString(" "))
            },
            buildEnv()
        )
    }

    // ─── Command execution (inside Ubuntu via proot-distro) ───────────────────

    suspend fun runInUbuntu(command: String): ExecResult = withContext(Dispatchers.IO) {
        runProcess(
            listOf(
                termuxPrefix.resolve("bin/bash").absolutePath,
                "-c",
                "proot-distro login ubuntu -- /bin/bash -c ${shellQuote(command)}"
            ),
            buildEnv()
        )
    }

    fun buildUbuntuShellArgs(innerCommand: String): List<String> = listOf(
        termuxPrefix.resolve("bin/bash").absolutePath,
        "-c",
        "proot-distro login ubuntu -- /bin/bash -c ${shellQuote(innerCommand)}"
    )

    // ─── Detect CPU architecture ──────────────────────────────────────────────

    suspend fun detectArch(): String = withContext(Dispatchers.IO) {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        when {
            abi.startsWith("arm64") -> "arm64"
            abi.startsWith("armeabi") -> "arm"
            abi == "x86_64" -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> "arm64"
        }
    }

    fun nodeArchString(arch: String): String = when (arch) {
        "arm64" -> "arm64"
        "arm" -> "armv7l"
        "x86_64" -> "x64"
        "x86" -> "x86"
        else -> "arm64"
    }

    fun bootstrapArchString(arch: String): String = when (arch) {
        "arm64" -> "aarch64"
        "arm" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> "aarch64"
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun buildEnv(): Map<String, String> = mapOf(
        "HOME" to homeDir.absolutePath,
        "TMPDIR" to tmpDir.absolutePath,
        "PREFIX" to termuxPrefix.absolutePath,
        "PATH" to "${termuxPrefix.absolutePath}/bin:${termuxPrefix.absolutePath}/usr/bin:/usr/bin:/bin",
        "LD_LIBRARY_PATH" to "${termuxPrefix.absolutePath}/lib",
        "TERM" to "xterm-256color",
        "LANG" to "en_US.UTF-8",
        "ANDROID_DATA" to context.filesDir.parentFile!!.absolutePath,
        "ANDROID_ROOT" to "/system"
    )

    private fun runProcess(cmd: List<String>, env: Map<String, String>): ExecResult {
        return try {
            val pb = ProcessBuilder(cmd).apply {
                environment().clear()
                environment().putAll(env)
                directory(homeDir)
            }
            val proc = pb.start()
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            val exit = proc.waitFor()
            ExecResult(exit, stdout, stderr)
        } catch (e: Exception) {
            ExecResult(-1, "", e.message ?: "Unknown error")
        }
    }

    private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    fun isUbuntuInstalled(): Boolean =
        File(filesDir, "var/lib/proot-distro/installed-rootfs/ubuntu").exists()

    fun isNodeInstalled(arch: String): Boolean {
        val nodeArch = nodeArchString(arch)
        return File(homeDir, "node-v20.11.0-linux-$nodeArch/bin/node").exists()
    }

    fun isClaudeInstalled(): Boolean {
        val paths = listOf(
            File("/root/.npm-global/bin/claude"),
            File("/root/.local/bin/claude"),
            File("/usr/local/bin/claude")
        )
        return paths.any { it.exists() }
    }

    fun isProxyInstalled(): Boolean =
        File(homeDir, "free-claude-code-main/server.py").exists() ||
        File(homeDir, "free-claude-code-main/server/main.py").exists()
}
