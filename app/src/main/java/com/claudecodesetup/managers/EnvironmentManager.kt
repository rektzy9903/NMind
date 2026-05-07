package com.claudecodesetup.managers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class EnvironmentManager(private val context: Context) {

    private val TAG = "EnvironmentManager"

    /** Root of our isolated Termux environment */
    val filesDir: File = context.filesDir
    val termuxPrefix: File = File(filesDir, "usr")
    val homeDir: File = File(filesDir, "home")
    val tmpDir: File = File(filesDir, "tmp")

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
                var symlinkContent: String? = null

                java.util.zip.ZipFile(zipFile).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()

                        // Termux bootstrap zips include a SYMLINKS.txt that must be
                        // processed separately to recreate every symlink on-device.
                        if (entry.name == "SYMLINKS.txt") {
                            symlinkContent = zf.getInputStream(entry).bufferedReader().readText()
                            count++
                            onProgress((count.toDouble() / total * 100).toInt())
                            continue
                        }

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

                symlinkContent?.let { processSymlinks(it) }
                fixPermissions()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap extraction failed", e)
                false
            }
        }

    /**
     * Process the SYMLINKS.txt file from the Termux bootstrap zip.
     * Each line has the format: <link_path>←<target>  (U+2190 arrow separator)
     */
    private fun processSymlinks(content: String) {
        var created = 0
        var failed = 0
        content.lines().filter { it.isNotBlank() }.forEach { line ->
            val sep = line.indexOf('←') // ← separator
            if (sep < 0) return@forEach
            val linkPath = File(filesDir, line.substring(0, sep).trim())
            val target = line.substring(sep + 1).trim()
            try {
                linkPath.parentFile?.mkdirs()
                val linkNio = linkPath.toPath()
                if (java.nio.file.Files.exists(linkNio) || java.nio.file.Files.isSymbolicLink(linkNio)) {
                    linkPath.delete()
                }
                java.nio.file.Files.createSymbolicLink(linkNio, java.nio.file.Paths.get(target))
                created++
            } catch (e: Exception) {
                Log.w(TAG, "Symlink failed: $linkPath → $target: ${e.message}")
                failed++
            }
        }
        Log.i(TAG, "Symlinks: $created created, $failed failed")
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

    suspend fun runInTermux(
        vararg cmd: String,
        timeoutSeconds: Long = 120
    ): ExecResult = withContext(Dispatchers.IO) {
        val bash = termuxPrefix.resolve("bin/bash")
        val proot = termuxPrefix.resolve("bin/proot")

        // Termux bootstrap binaries have a hardcoded rpath pointing at
        // /data/data/com.termux/files/usr/lib. Binding our app filesDir to
        // that path via proot lets bash, apt, pkg, and proot-distro find their
        // libraries and config files without root or any path-patching.
        val cmdList = if (proot.canExecute()) {
            listOf(
                proot.absolutePath,
                "--bind=${filesDir.absolutePath}:/data/data/com.termux/files",
                "--cwd=${homeDir.absolutePath}",
                bash.absolutePath,
                "-c",
                cmd.joinToString(" ")
            )
        } else {
            // proot not yet available (e.g. first extraction pass) — run directly
            listOf(bash.absolutePath, "-c", cmd.joinToString(" "))
        }

        runProcess(cmdList, buildEnv(), timeoutSeconds)
    }

    // ─── Command execution (inside Ubuntu via proot-distro) ───────────────────

    suspend fun runInUbuntu(
        command: String,
        timeoutSeconds: Long = 120
    ): ExecResult = withContext(Dispatchers.IO) {
        runProcess(
            listOf(
                termuxPrefix.resolve("bin/bash").absolutePath,
                "-c",
                "proot-distro login ubuntu -- /bin/bash -c ${shellQuote(command)}"
            ),
            buildEnv(),
            timeoutSeconds
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

    /**
     * Run a subprocess, collecting stdout and stderr concurrently on separate threads
     * to prevent the classic pipe-buffer deadlock where reading one stream sequentially
     * blocks the process from draining the other.
     */
    private fun runProcess(
        cmd: List<String>,
        env: Map<String, String>,
        timeoutSeconds: Long = 120
    ): ExecResult {
        try {
            val proc = ProcessBuilder(cmd).apply {
                environment().clear()
                environment().putAll(env)
                directory(homeDir)
            }.start()

            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()

            val stdoutThread = Thread {
                try { proc.inputStream.bufferedReader().use { stdoutBuf.append(it.readText()) } }
                catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }

            val stderrThread = Thread {
                try { proc.errorStream.bufferedReader().use { stderrBuf.append(it.readText()) } }
                catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }

            val exited = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!exited) {
                proc.destroyForcibly()
                Log.w(TAG, "Process timed out after ${timeoutSeconds}s: ${cmd.take(3)}")
            }

            // Always drain readers after process terminates (or is killed)
            stdoutThread.join(3000)
            stderrThread.join(3000)

            if (!exited) {
                return ExecResult(-1, stdoutBuf.toString(), "Timed out after ${timeoutSeconds}s")
            }
            return ExecResult(proc.exitValue(), stdoutBuf.toString(), stderrBuf.toString())
        } catch (e: Exception) {
            return ExecResult(-1, "", e.message ?: "Unknown error")
        }
    }

    private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    fun isUbuntuInstalled(): Boolean =
        File(filesDir, "var/lib/proot-distro/installed-rootfs/ubuntu").isDirectory

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
