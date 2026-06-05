package com.claudecodesetup.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.claudecodesetup.data.AppPreferences
import java.io.File

class DungeonActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: AppPreferences

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)

        webView = WebView(this).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
            }
            wv.addJavascriptInterface(DungeonBridge(this, prefs), "DungeonAndroid")
            setContentView(wv)
        }

        loadDungeon()
    }

    private fun loadDungeon() {
        val devFile = File(filesDir, "dungeon_dev.html")
        val html = if (devFile.exists() && devFile.length() > 500) {
            devFile.readText()
        } else {
            assets.open("dungeon/index.html").bufferedReader().readText()
        }
        // Base URL points to assets/dungeon/ so relative paths (images, css) resolve
        webView.loadDataWithBaseURL(
            "file:///android_asset/dungeon/",
            html,
            "text/html",
            "utf-8",
            null
        )
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}

@Suppress("unused")
private class DungeonBridge(
    private val context: Context,
    private val prefs: AppPreferences,
) {

    @JavascriptInterface
    fun getProjectPath(): String = prefs.getProjectPath()

    @JavascriptInterface
    fun readFile(path: String): String {
        return try {
            File(path).takeIf { it.exists() }?.readText() ?: ""
        } catch (_: Exception) { "" }
    }

    @JavascriptInterface
    fun writeFile(path: String, content: String): Boolean {
        return try {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            true
        } catch (_: Exception) { false }
    }

    @JavascriptInterface
    fun listDir(path: String): String {
        // Returns JSON array of {name, isDir} for entries in path
        return try {
            val entries = File(path).listFiles() ?: return "[]"
            val sb = StringBuilder("[")
            entries.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                .forEachIndexed { i, f ->
                    if (i > 0) sb.append(",")
                    sb.append("{\"name\":\"${f.name.replace("\"","\\\"")}\",\"isDir\":${f.isDirectory}}")
                }
            sb.append("]")
            sb.toString()
        } catch (_: Exception) { "[]" }
    }

    @JavascriptInterface
    fun getAgents(): String {
        // Returns JSON array of {name, description, model, tools, body} from ~/.claude/agents/*.md
        val agentsDir = File(System.getProperty("user.home") ?: "/", ".claude/agents")
        if (!agentsDir.exists()) return "[]"
        val sb = StringBuilder("[")
        var first = true
        agentsDir.listFiles { f -> f.extension == "md" }?.sortedBy { it.name }?.forEach { f ->
            try {
                val raw = f.readText()
                val name = f.nameWithoutExtension
                // Parse basic frontmatter: description, model, tools
                val desc = Regex("^description:\\s*(.+)$", RegexOption.MULTILINE)
                    .find(raw)?.groupValues?.get(1)?.trim() ?: ""
                val model = Regex("^model:\\s*(.+)$", RegexOption.MULTILINE)
                    .find(raw)?.groupValues?.get(1)?.trim() ?: ""
                val tools = Regex("^tools:\\s*(.+)$", RegexOption.MULTILINE)
                    .find(raw)?.groupValues?.get(1)?.trim() ?: ""
                val body = raw.replace(Regex("^---[\\s\\S]*?---\\s*", RegexOption.MULTILINE), "").trim()
                if (!first) sb.append(",")
                first = false
                sb.append("{")
                sb.append("\"name\":\"${name.esc()}\",")
                sb.append("\"description\":\"${desc.esc()}\",")
                sb.append("\"model\":\"${model.esc()}\",")
                sb.append("\"tools\":\"${tools.esc()}\",")
                sb.append("\"body\":\"${body.esc()}\"")
                sb.append("}")
            } catch (_: Exception) {}
        }
        sb.append("]")
        return sb.toString()
    }

    // spawnAgent: fires a claude --print dispatch via the bridge TCP socket.
    // The bridge receives a special !dungeon-dispatch command that carries the
    // agent name, cwd, and task — bridge.js creates a dedicated session slot
    // and streams OSC events back tagged with the sessionId.
    @JavascriptInterface
    fun spawnAgent(agentName: String, cwd: String, task: String): String {
        // Returns a sessionId string the JS uses to track this dispatch
        val sid = "dungeon-${System.currentTimeMillis()}"
        try {
            val socket = java.net.Socket("127.0.0.1", 8083)
            val out = socket.getOutputStream()
            val payload = buildString {
                append("SESSION:$sid:dungeon\n")
                append("!dungeon-dispatch\n")
                append("agent=$agentName\n")
                append("cwd=${cwd}\n")
                append("task=${task.replace("\n", "\\n")}\n")
                append("END\n")
            }
            out.write(payload.toByteArray())
            out.flush()
            socket.close()
        } catch (_: Exception) {}
        return sid
    }

    private fun String.esc() = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("\t", "\\t")
}
