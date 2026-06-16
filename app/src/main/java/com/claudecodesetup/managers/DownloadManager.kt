package com.claudecodesetup.managers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class DownloadManager {



    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class DownloadResult(val success: Boolean, val error: String? = null)

    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val existingBytes = if (dest.exists()) dest.length() else 0L

            val reqBuilder = Request.Builder().url(url)
            if (existingBytes > 0) {
                reqBuilder.header("Range", "bytes=$existingBytes-")
            }

            val response = client.newCall(reqBuilder.build()).execute()

            if (!response.isSuccessful && response.code != 206) {
                response.body?.close()
                return@withContext DownloadResult(false, "HTTP ${response.code}")
            }

            val body = response.body ?: return@withContext DownloadResult(false, "Empty response")

            val resuming = response.code == 206
            val totalBytes = if (resuming) {
                body.contentLength() + existingBytes
            } else {
                body.contentLength()
            }

            if (!resuming && existingBytes > 0) dest.delete()

            FileOutputStream(dest, resuming).use { fos ->
                val buf = ByteArray(8192)
                var downloaded = existingBytes
                var read: Int

                body.byteStream().use { input ->
                    while (input.read(buf).also { read = it } != -1) {
                        fos.write(buf, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            onProgress(((downloaded.toDouble() / totalBytes) * 100).toInt())
                        }
                    }
                }
            }

            DownloadResult(true)
        } catch (e: Exception) {
            try { dest.delete() } catch (_: Exception) {}
            DownloadResult(false, e.message)
        }
    }

    suspend fun downloadWithFallback(
        primaryUrl: String,
        fallbackUrl: String,
        dest: File,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult {
        val primary = download(primaryUrl, dest, onProgress)
        if (primary.success) return primary
        dest.delete() // Don't try to resume a failed primary with a different URL
        return download(fallbackUrl, dest, onProgress)
    }

    companion object {
        // Proot Ubuntu engine: claude-code provisioned into the rootfs by !setup-engine
        // (npm i -g @anthropic-ai/claude-code). Shown in Settings → About when no live
        // version has been recorded. Update when the provisioned baseline changes.
        const val ENGINE_CLAUDE_VERSION = "2.1.161"
    }
}
