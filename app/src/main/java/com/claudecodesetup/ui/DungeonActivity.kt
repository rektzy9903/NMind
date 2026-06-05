package com.claudecodesetup.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.webkit.*
import com.claudecodesetup.data.AppPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DungeonActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: AppPreferences

    // Folder picker — result calls back into JS via evaluateJavascript
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val path = uri?.let { treeUriToPath(it) }
        val js = if (path != null) "window.onFolderPicked(${JSONObject.quote(path)})"
                 else "window.onFolderPicked(null)"
        webView.post { webView.evaluateJavascript(js, null) }
    }

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
            wv.addJavascriptInterface(DungeonBridge(), "DungeonAndroid")
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
        webView.loadDataWithBaseURL(
            "file:///android_asset/dungeon/",
            html, "text/html", "utf-8", null
        )
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun treeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val colon = docId.indexOf(':')
            if (colon < 0) return null
            val volume = docId.substring(0, colon)
            val rel    = docId.substring(colon + 1)
            if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0/$rel"
            else "/storage/$volume/$rel"
        } catch (_: Exception) { null }
    }

    private fun dungeonsFile() = File(filesDir, "dungeons.json")

    private fun loadDungeons(): JSONArray {
        return try {
            val f = dungeonsFile()
            if (f.exists()) JSONArray(f.readText()) else JSONArray()
        } catch (_: Exception) { JSONArray() }
    }

    private fun saveDungeons(arr: JSONArray) {
        dungeonsFile().writeText(arr.toString())
    }

    // ── JS Bridge ────────────────────────────────────────────────────────────

    @Suppress("unused")
    inner class DungeonBridge {

        @JavascriptInterface
        fun getProjectPath(): String = prefs.getProjectPath()

        // Close the Dungeon activity (home-screen back arrow → main menu)
        @JavascriptInterface
        fun finishActivity() {
            runOnUiThread { finish() }
        }

        // Launch the system folder picker; result fires window.onFolderPicked(path)
        @JavascriptInterface
        fun pickFolder() {
            runOnUiThread { folderPicker.launch(null) }
        }

        // Dungeon list persistence
        @JavascriptInterface
        fun getDungeons(): String = loadDungeons().toString()

        @JavascriptInterface
        fun addDungeon(name: String, path: String): Boolean {
            return try {
                val arr = loadDungeons()
                // avoid duplicates by path
                for (i in 0 until arr.length()) {
                    if (arr.getJSONObject(i).optString("path") == path) return true
                }
                arr.put(JSONObject().apply {
                    put("name", name)
                    put("path", path)
                    put("created", System.currentTimeMillis())
                })
                saveDungeons(arr)
                true
            } catch (_: Exception) { false }
        }

        @JavascriptInterface
        fun removeDungeon(path: String): Boolean {
            return try {
                val arr = loadDungeons()
                val next = JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("path") != path) next.put(obj)
                }
                saveDungeons(next)
                true
            } catch (_: Exception) { false }
        }

        // File system
        @JavascriptInterface
        fun readFile(path: String): String {
            return try { File(path).takeIf { it.exists() }?.readText() ?: "" }
            catch (_: Exception) { "" }
        }

        @JavascriptInterface
        fun writeFile(path: String, content: String): Boolean {
            return try { val f = File(path); f.parentFile?.mkdirs(); f.writeText(content); true }
            catch (_: Exception) { false }
        }

        @JavascriptInterface
        fun listDir(path: String): String {
            return try {
                val entries = File(path).listFiles() ?: return "[]"
                val sb = StringBuilder("[")
                entries.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                    .forEachIndexed { i, f ->
                        if (i > 0) sb.append(",")
                        sb.append("{\"name\":\"${f.name.esc()}\",\"isDir\":${f.isDirectory}}")
                    }
                sb.append("]"); sb.toString()
            } catch (_: Exception) { "[]" }
        }

        @JavascriptInterface
        fun getAgents(): String {
            val agentsDir = File(System.getProperty("user.home") ?: "/", ".claude/agents")
            if (!agentsDir.exists()) return "[]"
            val sb = StringBuilder("[")
            var first = true
            agentsDir.listFiles { f -> f.extension == "md" }?.sortedBy { it.name }?.forEach { f ->
                try {
                    val raw = f.readText()
                    val name = f.nameWithoutExtension
                    val desc  = Regex("^description:\\s*(.+)$", RegexOption.MULTILINE).find(raw)?.groupValues?.get(1)?.trim() ?: ""
                    val model = Regex("^model:\\s*(.+)$",       RegexOption.MULTILINE).find(raw)?.groupValues?.get(1)?.trim() ?: ""
                    val tools = Regex("^tools:\\s*(.+)$",       RegexOption.MULTILINE).find(raw)?.groupValues?.get(1)?.trim() ?: ""
                    val body  = raw.replace(Regex("^---[\\s\\S]*?---\\s*", RegexOption.MULTILINE), "").trim()
                    if (!first) sb.append(","); first = false
                    sb.append("{\"name\":\"${name.esc()}\",\"description\":\"${desc.esc()}\",\"model\":\"${model.esc()}\",\"tools\":\"${tools.esc()}\",\"body\":\"${body.esc()}\"}")
                } catch (_: Exception) {}
            }
            sb.append("]"); return sb.toString()
        }

        @JavascriptInterface
        fun spawnAgent(agentName: String, cwd: String, task: String): String {
            val sid = "dungeon-${System.currentTimeMillis()}"
            try {
                val socket = java.net.Socket("127.0.0.1", 8083)
                val out = socket.getOutputStream()
                out.write("SESSION:$sid:dungeon\n!dungeon-dispatch\nagent=$agentName\ncwd=$cwd\ntask=${task.replace("\n","\\n")}\nEND\n".toByteArray())
                out.flush(); socket.close()
            } catch (_: Exception) {}
            return sid
        }

        private fun String.esc() = replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","").replace("\t","\\t")
    }
}
