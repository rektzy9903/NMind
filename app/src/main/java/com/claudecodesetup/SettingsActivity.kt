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
import com.claudecodesetup.services.FloatingOverlayService
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
        setupOverlaySwitch()
        setupPtySwitch()
    }

    override fun onResume() {
        super.onResume()
        // Refresh switch state in case user just returned from the permission screen
        syncOverlaySwitchState()
    }

    private fun populateFields() {
        binding.etProviderRemoteUrl.setText(prefs.getProviderRemoteUrl())
        val mode       = prefs.getLoginMode()
        val providerId = prefs.getProviderId()
        val provider   = Providers.byId(providerId)
        val model      = prefs.getModelId()

        // Project path and custom system prompt
        binding.etProjectPath.setText(prefs.getProjectPath())
        binding.etCustomSystemPrompt.setText(prefs.getCustomSystemPrompt())

        binding.tvCurrentProvider.text = when (mode) {
            AppPreferences.MODE_SUBSCRIPTION -> "Claude Subscription"
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
        prefs.setProviderRemoteUrl(binding.etProviderRemoteUrl.text.toString().trim())
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

        binding.btnCustomCommands.setOnClickListener { showCustomCommandsDialog() }
    }

    private fun showCustomCommandsDialog() {
        val commandsDir = File(filesDir, ".claude/commands")
        commandsDir.mkdirs()

        val files = commandsDir.listFiles { f -> f.extension == "md" }?.sortedBy { it.name } ?: emptyList()
        val names = (files.map { "/${it.nameWithoutExtension}" } + listOf("+ New command")).toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Custom slash commands")
            .setItems(names) { _, which ->
                if (which == names.size - 1) {
                    showCommandEditorDialog(null, commandsDir)
                } else {
                    showCommandEditorDialog(files[which], commandsDir)
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showCommandEditorDialog(file: File?, commandsDir: File) {
        val nameInput = android.widget.EditText(this).apply {
            hint = "command-name (no slash)"
            setText(file?.nameWithoutExtension ?: "")
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(4))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val contentInput = android.widget.EditText(this).apply {
            hint = "Command description / instructions in markdown"
            setText(file?.readText() ?: "")
            setPadding(dpToPx(16), dpToPx(4), dpToPx(16), dpToPx(8))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = android.view.Gravity.TOP
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            addView(nameInput)
            addView(contentInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (file == null) "New command" else "Edit /${file.nameWithoutExtension}")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim().replace("[^a-zA-Z0-9_-]".toRegex(), "-")
                val content = contentInput.text.toString()
                if (name.isEmpty()) { Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                file?.delete() // rename: delete old, write new
                File(commandsDir, "$name.md").writeText(content)
                Toast.makeText(this, "/${name} saved — restart session to use it", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)

        if (file != null) {
            dialog.setNeutralButton("Delete") { _, _ ->
                file.delete()
                Toast.makeText(this, "/${file.nameWithoutExtension} deleted", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun setupPtySwitch() {
        binding.switchPtyMode.isChecked = prefs.getPtyMode()
        binding.switchPtyMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.setPtyMode(isChecked)
            bridgeManager.refreshConfig(prefs)
            val msg = if (isChecked) "PTY mode enabled — takes effect on next message" else "PTY mode disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupOverlaySwitch() {
        syncOverlaySwitchState()

        binding.switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    // Permission not granted — show the button and revert the switch
                    binding.switchOverlay.isChecked = false
                    binding.btnOverlayPermission.visibility = View.VISIBLE
                } else {
                    binding.btnOverlayPermission.visibility = View.GONE
                    prefs.setOverlayEnabled(true)
                    startForegroundService(Intent(this, FloatingOverlayService::class.java))
                    Toast.makeText(this, "Floating overlay enabled", Toast.LENGTH_SHORT).show()
                }
            } else {
                binding.btnOverlayPermission.visibility = View.GONE
                prefs.setOverlayEnabled(false)
                startService(Intent(this, FloatingOverlayService::class.java)
                    .setAction(FloatingOverlayService.ACTION_STOP))
                Toast.makeText(this, "Floating overlay disabled", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnOverlayPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        binding.btnEditOverlayPrompts.setOnClickListener { showEditPromptsDialog() }
    }

    private fun showEditPromptsDialog() {
        val prompts = prefs.getOverlayPrompts().toMutableList()
        val input   = android.widget.EditText(this).apply {
            setText(prompts.joinToString("\n"))
            hint       = "One prompt per line"
            inputType  = android.text.InputType.TYPE_CLASS_TEXT or
                         android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines   = 5
            gravity    = android.view.Gravity.TOP
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Overlay quick prompts")
            .setMessage("One prompt per line (max 10)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val updated = input.text.toString()
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .take(10)
                prefs.setOverlayPrompts(updated)
                Toast.makeText(this, "Prompts saved — reopen overlay to see changes", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset defaults") { _, _ ->
                prefs.setOverlayPrompts(com.claudecodesetup.data.AppPreferences.DEFAULT_OVERLAY_PROMPTS)
                Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun syncOverlaySwitchState() {
        val hasPermission = Settings.canDrawOverlays(this)
        val enabled       = prefs.getOverlayEnabled() && hasPermission
        // Update pref if permission was revoked externally
        if (!hasPermission && prefs.getOverlayEnabled()) prefs.setOverlayEnabled(false)
        binding.switchOverlay.isChecked         = enabled
        binding.btnOverlayPermission.visibility =
            if (!hasPermission && prefs.getOverlayEnabled()) View.VISIBLE else View.GONE
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
