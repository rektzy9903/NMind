package com.claudecodesetup

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Providers
import com.claudecodesetup.databinding.ActivitySettingsBinding
import com.claudecodesetup.managers.NodeBridgeManager
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences
    private lateinit var bridgeManager: NodeBridgeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        bridgeManager = NodeBridgeManager(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        populateFields()
        setupActions()
    }

    private fun populateFields() {
        val mode       = prefs.getLoginMode()
        val providerId = prefs.getProviderId()
        val provider   = Providers.byId(providerId)
        val model      = prefs.getModelId()

        // Project path and custom system prompt
        binding.etProjectPath.setText(prefs.getProjectPath())
        binding.etCustomSystemPrompt.setText(prefs.getCustomSystemPrompt())

        binding.tvCurrentProvider.text = when (mode) {
            AppPreferences.MODE_SUBSCRIPTION -> "Claude Subscription"
            AppPreferences.MODE_GEMINI       -> "Google Gemini — $model"
            else -> "${provider?.name ?: "Unknown"} — $model"
        }

        // Show "Change model" for any provider with multiple models
        binding.btnChangeModel.visibility =
            if ((provider?.models?.size ?: 0) > 1) View.VISIBLE else View.GONE

        val installedVersion = prefs.getInstalledClaudeVersion()
            .ifEmpty { com.claudecodesetup.managers.DownloadManager.PINNED_CLAUDE_VERSION }
        binding.tvClaudeVersion.text = "Claude Code v$installedVersion"
        binding.tvAppVersion.text    = "App v${BuildConfig.VERSION_NAME}"

        // Language
        val langCodes = arrayOf("en", "ms")
        val currentLang = prefs.getLanguage()
        binding.spinnerLanguage.setSelection(langCodes.indexOf(currentLang).coerceAtLeast(0))
        binding.spinnerLanguage.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: android.view.View?,
                    position: Int, id: Long
                ) { prefs.setLanguage(langCodes[position]) }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    override fun onPause() {
        super.onPause()
        prefs.setProjectPath(binding.etProjectPath.text.toString().trim())
        prefs.setCustomSystemPrompt(binding.etCustomSystemPrompt.text.toString().trim())
        bridgeManager.refreshConfig(prefs)
    }

    private fun setupActions() {
        binding.btnChangeProvider.setOnClickListener {
            prefs.clearProviderOnly()
            startActivity(Intent(this, com.claudecodesetup.ui.ComposeActivity::class.java))
            finish()
        }

        binding.btnChangeModel.setOnClickListener {
            startActivity(
                Intent(this, com.claudecodesetup.ui.ComposeActivity::class.java)
                    .putExtra("start_at", "picker")
            )
            finish()
        }

        binding.btnClearData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear all data?")
                .setMessage("This will erase your API key and provider settings. You'll need to set up again.")
                .setPositiveButton("Clear") { _, _ ->
                    prefs.clearProviderOnly()
                    Toast.makeText(this, "Settings cleared", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, com.claudecodesetup.ui.ComposeActivity::class.java))
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset everything?")
                .setMessage(
                    "This will delete your entire Claude Code installation and start fresh. " +
                    "This cannot be undone."
                )
                .setPositiveButton("Reset") { _, _ -> resetEverything() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnReport.setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/Alishahryar1/free-claude-code/issues/new"))
            )
        }

        binding.btnGrantStorage.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this, "Storage access already granted", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Storage access already granted on this Android version", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBrowseFolder.setOnClickListener {
            val canAccess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
            if (canAccess) {
                showFolderPicker(Environment.getExternalStorageDirectory())
            } else {
                Toast.makeText(this, "Grant storage access first", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnMcpServers.setOnClickListener {
            startActivity(Intent(this, com.claudecodesetup.ui.McpActivity::class.java))
        }
    }

    private fun showFolderPicker(dir: File) {
        val subdirs = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val items = mutableListOf(".. (go up)", "→ Select this folder") + subdirs.map { it.name }
        AlertDialog.Builder(this)
            .setTitle(dir.absolutePath)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showFolderPicker(dir.parentFile ?: dir)
                    1 -> {
                        binding.etProjectPath.setText(dir.absolutePath)
                        prefs.setProjectPath(dir.absolutePath)
                        Toast.makeText(this, "Project folder set", Toast.LENGTH_SHORT).show()
                    }
                    else -> showFolderPicker(subdirs[which - 2])
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Reset ─────────────────────────────────────────────────────────────────

    private fun resetEverything() {
        prefs.clearAll()
        Thread {
            try { filesDir.deleteRecursively() } catch (_: Exception) {}
        }.start()
        startActivity(Intent(this, SetupActivity::class.java))
        finishAffinity()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
