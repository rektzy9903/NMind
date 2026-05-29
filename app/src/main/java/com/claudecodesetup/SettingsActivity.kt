package com.claudecodesetup

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Providers
import com.claudecodesetup.databinding.ActivitySettingsBinding
import com.claudecodesetup.managers.NodeBridgeManager
import com.claudecodesetup.managers.ScheduledPrompt
import com.claudecodesetup.managers.ScheduledPromptsManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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
    }

    override fun onResume() {
        super.onResume()
        refreshMcpRows()
        refreshPreferenceToggles()
    }

    @Suppress("DEPRECATION")
    private fun refreshPreferenceToggles() {
        binding.switchResponseNotifications.isChecked = prefs.isResponseNotificationsEnabled()
        binding.switchAutoStartBoot.isChecked = prefs.isAutoStartOnBoot()
        binding.switchSkipKeyPrompt.isChecked = prefs.isSkipKeyPromptEnabled()
    }

    private fun refreshMcpRows() {
        val container = binding.mcpServerRows
        container.removeAllViews()

        val httpArr  = try { JSONArray(prefs.getMcpServersJson()) } catch (_: Exception) { JSONArray() }
        val stdioArr = try { JSONArray(prefs.getMcpStdioServersJson()) } catch (_: Exception) { JSONArray() }
        val total = httpArr.length() + stdioArr.length()

        binding.btnMcpServers.text =
            if (total > 0) "Manage MCP Servers ($total)" else "Manage MCP Servers"

        if (total == 0) return

        // Build rows for HTTP servers
        for (i in 0 until httpArr.length()) {
            val obj = httpArr.getJSONObject(i)
            val name = obj.optString("name", "Unnamed")
            val url  = obj.optString("url", "")
            val enabled = obj.optBoolean("enabled", true)
            container.addView(buildMcpRow(name, url, enabled) { isOn ->
                obj.put("enabled", isOn)
                prefs.saveMcpServersJson(httpArr.toString())
                bridgeManager.writeMcpConfig(prefs)
            })
        }

        // Build rows for stdio servers
        for (i in 0 until stdioArr.length()) {
            val obj = stdioArr.getJSONObject(i)
            val name    = obj.optString("name", "Unnamed")
            val command = obj.optString("command", "")
            val enabled = obj.optBoolean("enabled", true)
            container.addView(buildMcpRow(name, command, enabled) { isOn ->
                obj.put("enabled", isOn)
                prefs.saveMcpStdioServersJson(stdioArr.toString())
                bridgeManager.writeMcpConfig(prefs)
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun buildMcpRow(
        name: String,
        subtitle: String,
        enabled: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14), dpToPx(13), dpToPx(14), dpToPx(13))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = name
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            textSize = 14f
        })
        if (subtitle.isNotEmpty()) {
            textCol.addView(TextView(this).apply {
                text = subtitle
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        val toggle = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = enabled
            setOnCheckedChangeListener(null)
        }
        toggle.setOnCheckedChangeListener { _, isOn -> onToggle(isOn) }
        row.addView(textCol)
        row.addView(toggle)
        return row
    }

    private fun populateFields() {
        binding.etProviderRemoteUrl.setText(prefs.getProviderRemoteUrl())
        val mode       = prefs.getLoginMode()
        val providerId = prefs.getProviderId()
        val provider   = Providers.byId(providerId)
        val model      = prefs.getModelId()

        binding.tvCurrentProvider.text = when (mode) {
            AppPreferences.MODE_SUBSCRIPTION -> "Claude (subscription)"
            else -> provider?.name ?: "Unknown"
        }
        binding.tvCurrentModel.text = when (mode) {
            AppPreferences.MODE_SUBSCRIPTION -> model.ifEmpty { "claude-sonnet-4-6" }
            else -> model.ifEmpty { "—" }
        }

        // Always show Change model (visibility controlled by XML, not code)
        binding.btnChangeModel.visibility = View.VISIBLE

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

    @Suppress("DEPRECATION")
    private fun setupActions() {
        binding.switchResponseNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.setResponseNotificationsEnabled(isChecked)
        }
        binding.switchAutoStartBoot.setOnCheckedChangeListener { _, isChecked ->
            prefs.setAutoStartOnBoot(isChecked)
        }
        binding.switchSkipKeyPrompt.setOnCheckedChangeListener { _, isChecked ->
            prefs.setSkipKeyPromptEnabled(isChecked)
        }

        binding.btnChangeProvider.setOnClickListener {
            startActivity(Intent(this, com.claudecodesetup.ui.ComposeActivity::class.java)
                .putExtra("start_at", "subscription"))
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

        binding.btnMcpServers.setOnClickListener {
            startActivity(Intent(this, com.claudecodesetup.ui.McpActivity::class.java))
        }

        binding.btnScheduledPrompts.setOnClickListener { showScheduledPromptsDialog() }

        binding.btnCustomCommands.setOnClickListener { showCustomCommandsDialog() }

        binding.btnRestartBridge.setOnClickListener {
            startService(
                android.content.Intent(this, com.claudecodesetup.services.ClaudeService::class.java)
                    .setAction(com.claudecodesetup.services.ClaudeService.ACTION_RESTART_BRIDGE)
            )
            android.widget.Toast.makeText(this, "Bridge restarting…", android.widget.Toast.LENGTH_SHORT).show()
        }
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

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

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
