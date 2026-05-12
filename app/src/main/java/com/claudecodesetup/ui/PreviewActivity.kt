package com.claudecodesetup.ui

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.claudecodesetup.data.AppPreferences
import java.io.File

class PreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
        }
        wv.webViewClient = WebViewClient()
        setContentView(wv)
        supportActionBar?.title = "Web Preview"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = AppPreferences(this)
        val projectPath = intent.getStringExtra("project_path")
            ?: prefs.getProjectPath().ifEmpty { filesDir.absolutePath }

        val indexFile = File(projectPath, "index.html")
        if (indexFile.exists()) {
            wv.loadUrl("file://${indexFile.absolutePath}")
        } else {
            wv.loadData(
                "<html><body style='background:#0a0618;color:#9ca3af;font-family:monospace;padding:24px'>" +
                "<h2 style='color:#c8b8ff'>No index.html found</h2>" +
                "<p>Project path: $projectPath</p>" +
                "<p>Create an index.html file to preview it here.</p></body></html>",
                "text/html", "utf-8"
            )
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
