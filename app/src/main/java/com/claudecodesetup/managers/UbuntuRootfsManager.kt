package com.claudecodesetup.managers

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Ubuntu-engine rootfs lifecycle (ubuntu-engine.md, P1).
 *
 * Downloads a proot-distro Ubuntu rootfs (.tar.xz), extracts it into
 * filesDir/ubuntu, and runs commands inside it via the bundled proot
 * (libproot.so in nativeLibDir — proven to exec on-device, "confirm C").
 *
 * Everything here is Kotlin/ProcessBuilder so the Ubuntu engine is fully
 * self-contained; the Node bridge is not involved until the engine swap (P2).
 *
 * Java/Android has no built-in xz, so extraction uses org.tukaani:xz +
 * commons-compress. Device nodes in the tarball are skipped (mknod needs root;
 * proot binds the real /dev over them). See ubuntu-engine.md for grounding.
 */
class UbuntuRootfsManager(private val context: Context) {

    private val filesDir = context.filesDir
    private val nativeDir = context.applicationInfo.nativeLibraryDir
    private val rootfs = File(filesDir, "ubuntu")
    private val marker = File(rootfs, ".nexus_rootfs_ready")
    private val prootLibDir = File(filesDir, ".proot-lib")

    fun isInstalled(): Boolean = marker.exists()

    /** Phase callback for UI: (human-readable phase, 0..100 percent or -1 if indeterminate). */
    data class Step(val phase: String, val pct: Int)
    data class Result(val success: Boolean, val message: String)

    private fun rootfsUrl(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return if (abi.startsWith("armeabi"))
            "https://github.com/termux/proot-distro/releases/download/v4.6.0/ubuntu-arm-pd-v4.6.0.tar.xz"
        else
            "https://github.com/termux/proot-distro/releases/download/v4.6.0/ubuntu-aarch64-pd-v4.6.0.tar.xz"
    }

    /**
     * Download + extract the rootfs (P1b). Idempotent: a completed install
     * (marker present) short-circuits. Reports coarse progress via [onStep].
     */
    suspend fun installRootfs(onStep: (Step) -> Unit): Result = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext Result(true, "Rootfs already installed at ${rootfs.absolutePath}")
        try {
            val tarXz = File(context.cacheDir, "ubuntu-rootfs.tar.xz")
            onStep(Step("Downloading Ubuntu rootfs (~23 MB)…", 0))
            val dl = DownloadManager().download(rootfsUrl(), tarXz) { p ->
                onStep(Step("Downloading Ubuntu rootfs (~23 MB)…", p))
            }
            if (!dl.success) return@withContext Result(false, "Download failed: ${dl.error}")

            onStep(Step("Extracting rootfs (~99 MB)…", -1))
            if (rootfs.exists()) rootfs.deleteRecursively()
            rootfs.mkdirs()
            val (files, links, skipped) = extractTarXz(tarXz, rootfs) { n ->
                if (n % 500 == 0) onStep(Step("Extracting rootfs… ($n entries)", -1))
            }
            tarXz.delete()

            ensureFakeSysData()
            File(rootfs, "tmp").mkdirs()
            marker.writeText("ubuntu-aarch64-pd-v4.6.0\n")
            Result(true, "Extracted $files files, $links symlinks (skipped $skipped device nodes).")
        } catch (e: Exception) {
            Result(false, "Extract error: ${e.message}")
        }
    }

    /**
     * Stream a .tar.xz into [dest], stripping the single top-level dir prefix
     * (e.g. "ubuntu-aarch64/"). Returns (fileCount, symlinkCount, skippedCount).
     * Skips device/fifo nodes (can't be created without root).
     */
    private fun extractTarXz(tarXz: File, dest: File, onEntry: (Int) -> Unit): Triple<Int, Int, Int> {
        var files = 0; var links = 0; var skipped = 0; var n = 0
        val destCanon = dest.canonicalPath
        TarArchiveInputStream(XZInputStream(BufferedInputStream(FileInputStream(tarXz)))).use { tin ->
            var entry: TarArchiveEntry? = tin.nextTarEntry
            while (entry != null) {
                n++
                // Strip the leading "<top-dir>/" component; skip the top dir itself.
                val rel = entry.name.substringAfter('/', "")
                if (rel.isEmpty()) { entry = tin.nextTarEntry; continue }

                val out = File(dest, rel)
                // Path-traversal guard.
                if (!out.canonicalPath.startsWith(destCanon)) { entry = tin.nextTarEntry; continue }

                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        try { if (out.exists() || isSymlink(out)) out.delete() } catch (_: Exception) {}
                        try {
                            Files.createSymbolicLink(Paths.get(out.absolutePath), Paths.get(entry.linkName))
                            links++
                        } catch (_: Exception) { /* dangling/duplicate — tolerate */ }
                    }
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tin.copyTo(it, 64 * 1024) }
                        if ((entry.mode and 0b001_000_000) != 0) out.setExecutable(true, false)
                        files++
                    }
                    else -> skipped++   // char/block device, fifo, socket — proot binds real /dev
                }
                onEntry(n)
                entry = tin.nextTarEntry
            }
        }
        return Triple(files, links, skipped)
    }

    private fun isSymlink(f: File): Boolean =
        try { Files.isSymbolicLink(Paths.get(f.absolutePath)) } catch (_: Exception) { false }

    // ── proot exec ──────────────────────────────────────────────────────────

    /** libtalloc.so.2 → nativeDir/libtalloc.so symlink (proot keeps NEEDED .so.2). */
    private fun ensureTallocLink() {
        prootLibDir.mkdirs()
        val link = File(prootLibDir, "libtalloc.so.2")
        try { if (link.exists() || isSymlink(link)) link.delete() } catch (_: Exception) {}
        try {
            Files.createSymbolicLink(Paths.get(link.absolutePath), Paths.get("$nativeDir/libtalloc.so"))
        } catch (_: Exception) {}
    }

    /** Fake /proc + /sys/.empty files proot binds over (Android restricts the real ones). */
    private fun ensureFakeSysData() {
        val proc = File(rootfs, "proc"); proc.mkdirs()
        File(rootfs, "sys/.empty").mkdirs()
        mapOf(
            ".loadavg" to "0.12 0.07 0.02 2/165 765\n",
            ".stat" to "cpu  1957 0 2877 93280 262 342 254 87 0 0\n",
            ".uptime" to "1500.00 1234.56\n",
            ".version" to "Linux version 6.17.0-PRoot-Distro\n",
            ".vmstat" to "nr_free_pages 1\n",
            ".sysctl_entry_cap_last_cap" to "40\n",
            ".sysctl_inotify_max_user_watches" to "524288\n",
        ).forEach { (name, content) ->
            val f = File(proc, name); if (!f.exists()) f.writeText(content)
        }
    }

    private fun prootArgv(command: List<String>, cwd: String = "/root"): List<String> {
        val rp = rootfs.absolutePath
        val argv = mutableListOf(
            "$nativeDir/libproot.so",
            "-L",
            "--kernel-release=6.17.0-PRoot-Distro",
            "--link2symlink",
            "--kill-on-exit",
            "--rootfs=$rp",
            "--root-id",
            "--cwd=$cwd",
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=/sys",
            "--bind=$rp/proc/.loadavg:/proc/loadavg",
            "--bind=$rp/proc/.stat:/proc/stat",
            "--bind=$rp/proc/.uptime:/proc/uptime",
            "--bind=$rp/proc/.version:/proc/version",
            "--bind=$rp/proc/.vmstat:/proc/vmstat",
            "--bind=$rp/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap",
            "--bind=$rp/proc/.sysctl_inotify_max_user_watches:/proc/sys/fs/inotify/max_user_watches",
            "--bind=$rp/sys/.empty:/sys/fs/selinux",
            // Share app dirs into the guest.
            "--bind=${filesDir.absolutePath}:/root/.nexus",
            "--bind=/sdcard:/sdcard",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
        )
        argv.addAll(command)
        return argv
    }

    /** Run a command inside the rootfs. Returns (exitOrNull, mergedOutput). */
    suspend fun runInRootfs(command: List<String>, timeoutMs: Long = 60_000): Pair<Int?, String> =
        withContext(Dispatchers.IO) {
            ensureTallocLink()
            File(rootfs, "tmp").mkdirs()
            val pb = ProcessBuilder(prootArgv(command)).redirectErrorStream(true)
            pb.environment().apply {
                put("LD_LIBRARY_PATH", "${prootLibDir.absolutePath}:$nativeDir")
                put("PROOT_LOADER", "$nativeDir/libproot-loader.so")
                put("PROOT_LOADER_32", "$nativeDir/libproot-loader32.so")
                put("PROOT_L2S_DIR", "${rootfs.absolutePath}/.l2s")
                put("PROOT_TMP_DIR", "${rootfs.absolutePath}/tmp")
            }
            try {
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText()
                val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) { proc.destroyForcibly(); return@withContext null to (out + "\n[timeout ${timeoutMs}ms]") }
                proc.exitValue() to out
            } catch (e: Exception) {
                null to "exec error: ${e.message}"
            }
        }

    /** P1b acceptance probe: prove the rootfs runs end-to-end via proot. */
    suspend fun probeOsRelease(): Pair<Int?, String> = runInRootfs(listOf("/usr/bin/cat", "/etc/os-release"), 30_000)
}
