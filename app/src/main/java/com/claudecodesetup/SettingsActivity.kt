package com.claudecodesetup

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Providers
import com.claudecodesetup.databinding.ActivitySettingsBinding
import com.claudecodesetup.managers.NodeBridgeManager
import com.claudecodesetup.managers.ScheduledPrompt
import com.claudecodesetup.managers.ScheduledPromptsManager
import com.claudecodesetup.services.FloatingOverlayService
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences
    private lateinit var bridgeManager: NodeBridgeManager

    private val changeModelLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Called when ComposeActivity finishes (any result)
        bridgeManager.refreshConfig(prefs)
    }

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
    }

    override fun onResume() {
        super.onResume()
        syncOverlaySwitchState()
        val mcpCount = try {
            org.json.JSONArray(prefs.getMcpServersJson()).length() +
            org.json.JSONArray(prefs.getMcpStdioServersJson()).length()
        } catch (_: Exception) { 0 }
        binding.btnMcpServers.text =
            if (mcpCount > 0) "Manage MCP Servers ($mcpCount)" else "Manage MCP Servers"
    }

    private fun populateFields() {
        binding.etProviderRemoteUrl.setText(prefs.getProviderRemoteUrl())
        val mode       = prefs.getLoginMode()
        val providerId = prefs.getProviderId()
        val provider   = Providers.byId(providerId)
        val model      = prefs.getModelId()

        binding.tvCurrentProvider.text = when (mode) {
            AppPreferences.MODE_SUBSCRIPTION -> "Claude Subscription"
            else -> "${provider?.name ?: "Unknown"} — $model"
        }

        // Show "Change model" for any provider with multiple models
        binding.btnChangeModel.visibility =
            if ((provider?.models?.size ?: 0) > 1) View.VISIBLE else View.GONE

        val installedVersion = prefs.getInstalledClaudeVersion()
            .ifEmpty { com.claudecodesetup.managers.DownloadManager.PINNED_CLAUDE_VERSION }
        binding.tvClaudeVersion.text = "Nexus Mind v$installedVersion"
        binding.tvAppVersion.text    = "App v${BuildConfig.VERSION_NAME}"

    }

    override fun onPause() {
        super.onPause()
        prefs.setProviderRemoteUrl(binding.etProviderRemoteUrl.text.toString().trim())
        bridgeManager.refreshConfig(prefs)
    }

    private fun setupActions() {
        binding.btnChangeProvider.setOnClickListener {
            startActivity(Intent(this, com.claudecodesetup.ui.ComposeActivity::class.java)
                .putExtra("start_at", "providers"))
            finish()
        }

        binding.btnChangeModel.setOnClickListener {
            changeModelLauncher.launch(
                Intent(this, com.claudecodesetup.ui.ComposeActivity::class.java)
                    .putExtra("start_at", "picker")
            )
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
                    "This will delete your entire Nexus Mind installation and start fresh. " +
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

        binding.btnManageProjects.setOnClickListener {
            startActivity(Intent(this, com.claudecodesetup.ui.ProjectManagerActivity::class.java))
        }

        binding.btnManageApprovals.setOnClickListener { showAutoApprovalsDialog() }

        binding.btnMcpServers.setOnClickListener {
            startActivity(Intent(this, com.claudecodesetup.ui.McpActivity::class.java))
        }

        binding.btnScheduledPrompts.setOnClickListener { showScheduledPromptsDialog() }

        binding.btnCustomCommands.setOnClickListener { showCustomCommandsDialog() }
    }

    private fun showScheduledPromptsDialog() {
        val prompts = ScheduledPromptsManager.getAll(prefs).toMutableList()
        val labels = (prompts.map { p ->
            val base = "${p.timeLabel} — ${p.prompt.take(40)}"
            if (p.enabled) base else "$base (disabled)"
        } + listOf("+ Add new")).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Scheduled prompts")
            .setItems(labels) { _, which ->
                if (which == labels.size - 1) showAddScheduledPromptDialog()
                else showEditScheduledPromptDialog(prompts[which])
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showAddScheduledPromptDialog() {
        showScheduledPromptEditor(null)
    }

    private fun showEditScheduledPromptDialog(prompt: ScheduledPrompt) {
        val items = arrayOf("Edit", "Delete", if (prompt.enabled) "Disable" else "Enable")
        AlertDialog.Builder(this)
            .setTitle("${prompt.timeLabel} — ${prompt.prompt.take(40)}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showScheduledPromptEditor(prompt)
                    1 -> {
                        ScheduledPromptsManager.remove(this, prefs, prompt.id)
                        Toast.makeText(this, "Prompt deleted", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val updated = prompt.copy(enabled = !prompt.enabled)
                        val all = ScheduledPromptsManager.getAll(prefs).map {
                            if (it.id == updated.id) updated else it
                        }
                        ScheduledPromptsManager.save(this, prefs, all)
                        Toast.makeText(this,
                            if (updated.enabled) "Prompt enabled" else "Prompt disabled",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showScheduledPromptEditor(existing: ScheduledPrompt?) {
        val hourField = android.widget.EditText(this).apply {
            hint = "Hour (0–23)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(existing?.hour?.toString() ?: "9")
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(4))
        }
        val minuteField = android.widget.EditText(this).apply {
            hint = "Minute (0–59)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(existing?.minute?.toString() ?: "0")
            setPadding(dpToPx(16), dpToPx(4), dpToPx(16), dpToPx(4))
        }
        val promptField = android.widget.EditText(this).apply {
            hint = "Prompt text (sent to Claude)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(existing?.prompt ?: "")
            minLines = 3
            gravity = android.view.Gravity.TOP
            setPadding(dpToPx(16), dpToPx(4), dpToPx(16), dpToPx(8))
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            addView(hourField)
            addView(minuteField)
            addView(promptField)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add scheduled prompt" else "Edit prompt")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val hour = hourField.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 9
                val minute = minuteField.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 0
                val text = promptField.text.toString().trim()
                if (text.isEmpty()) { Toast.makeText(this, "Prompt text required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val prompt = existing?.copy(prompt = text, hour = hour, minute = minute)
                    ?: ScheduledPrompt(prompt = text, hour = hour, minute = minute)
                if (existing == null) {
                    ScheduledPromptsManager.add(this, prefs, prompt)
                } else {
                    val all = ScheduledPromptsManager.getAll(prefs).map { if (it.id == prompt.id) prompt else it }
                    ScheduledPromptsManager.save(this, prefs, all)
                }
                Toast.makeText(this, "Prompt scheduled for ${prompt.timeLabel} daily", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun showAutoApprovalsDialog() {
        val file = java.io.File(filesDir, "auto_approve.json")
        val list: MutableList<String> = try {
            val obj = org.json.JSONObject(file.readText())
            val arr = obj.optJSONArray("allow") ?: org.json.JSONArray()
            (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        } catch (_: Exception) { mutableListOf() }

        if (list.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Auto-approved tools")
                .setMessage("No tools have been auto-approved yet.\n\nWhen Claude asks permission to use a tool and you tap \"Always allow\", it appears here.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = list.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Auto-approved tools (${list.size})")
            .setItems(labels) { _, which ->
                AlertDialog.Builder(this)
                    .setTitle("Remove \"${list[which]}\"?")
                    .setMessage("Claude will ask for permission again before using this tool.")
                    .setPositiveButton("Remove") { _, _ ->
                        list.removeAt(which)
                        val obj = try { org.json.JSONObject(file.readText()) } catch (_: Exception) { org.json.JSONObject() }
                        obj.put("allow", org.json.JSONArray(list))
                        file.writeText(obj.toString(2))
                        Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNeutralButton("Clear all") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Clear all auto-approvals?")
                    .setMessage("Claude will ask permission for every tool again.")
                    .setPositiveButton("Clear all") { _, _ ->
                        val obj = try { org.json.JSONObject(file.readText()) } catch (_: Exception) { org.json.JSONObject() }
                        obj.put("allow", org.json.JSONArray())
                        file.writeText(obj.toString(2))
                        Toast.makeText(this, "All auto-approvals cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Done", null)
            .show()
    }

    // ─── Reset ─────────────────────────────────────────────────────────────────

    private fun resetEverything() {
        prefs.clearAll()
        try { filesDir.deleteRecursively() } catch (_: Exception) {}
        startActivity(Intent(this, SetupActivity::class.java))
        finishAffinity()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
