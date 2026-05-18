package com.claudecodesetup.managers

import android.content.Context
import android.util.Log
import java.io.File
import java.net.Socket

class LlamaServerManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LlamaServerManager"
        const val SERVER_PORT = 8080
        const val SERVER_HOST = "127.0.0.1"

        @Volatile private var instance: LlamaServerManager? = null

        fun get(context: Context): LlamaServerManager =
            instance ?: synchronized(this) {
                instance ?: LlamaServerManager(context.applicationContext).also { instance = it }
            }
    }

    private var serverProcess: Process? = null

    val modelsDir: File get() = File(context.filesDir, "models").also { it.mkdirs() }

    fun isBinaryAvailable(): Boolean =
        File(context.applicationInfo.nativeLibraryDir, "libllamaserver.so").exists()

    fun isServerRunning(): Boolean = try {
        Socket(SERVER_HOST, SERVER_PORT).use { true }
    } catch (_: Exception) { false }

    fun modelFile(id: String): File = File(modelsDir, "$id.gguf")

    fun getInstalledModelIds(): List<String> =
        modelsDir.listFiles()
            ?.filter { it.extension == "gguf" && it.length() > 0 }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()

    fun startServer(modelId: String): Boolean {
        if (isServerRunning()) stopServer()
        val binary = File(context.applicationInfo.nativeLibraryDir, "libllamaserver.so")
        val model = modelFile(modelId)
        if (!binary.exists() || !model.exists()) return false
        return try {
            serverProcess = ProcessBuilder(
                binary.absolutePath,
                "-m", model.absolutePath,
                "--host", SERVER_HOST,
                "--port", SERVER_PORT.toString(),
                "-c", "2048",
                "--threads", "4",
                "--model-alias", modelId,
                "--log-disable"
            ).redirectErrorStream(true).start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start llama-server", e)
            false
        }
    }

    fun stopServer() {
        serverProcess?.destroy()
        serverProcess = null
    }

    /** Polls until the server responds on its port or the timeout elapses. */
    fun waitUntilReady(timeoutMs: Long = 30_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isServerRunning()) return true
            Thread.sleep(500)
        }
        return false
    }

    fun deleteModel(id: String) { modelFile(id).delete() }
}
