package com.claudecodesetup

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
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
            setPadding(0, dpToPx(6), 0, dpToPx(6))
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
        val toggle = Switch(this).apply {
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

        binding.btnManageApprovals.setOnClickListener { showToolPermissionsDialog() }

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

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private data class ToolEntry(val name: String, val desc: String)
    private data class ToolSection(val title: String, val tools: List<ToolEntry>)

    private val TOOL_SECTIONS = listOf(
        ToolSection("File Operations", listOf(
            ToolEntry("Read",      "Read file contents — safe, view only"),
            ToolEntry("Write",     "Create or overwrite a file"),
            ToolEntry("Edit",      "Modify part of an existing file"),
            ToolEntry("MultiEdit", "Multiple edits to one file at once"),
            ToolEntry("Glob",      "Find files by name pattern"),
            ToolEntry("Grep",      "Search text inside files"),
            ToolEntry("LS",        "List files in a directory — safe, view only"),
        )),
        ToolSection("Shell", listOf(
            ToolEntry("Bash", "Run any terminal command — covers all !install tools too"),
        )),
        ToolSection("Web", listOf(
            ToolEntry("WebSearch", "Search the web"),
            ToolEntry("WebFetch",  "Open and read a URL"),
        )),
        ToolSection("Tasks & Agents", listOf(
            ToolEntry("TodoWrite", "Claude writes its own task checklist"),
            ToolEntry("TodoRead",  "Claude reads its own task checklist"),
            ToolEntry("Agent",     "Spawn a sub-agent for complex multi-step tasks"),
        )),
    )

    private fun showToolPermissionsDialog() {
        val file = File(filesDir, "auto_approve.json")
        val allowSet: MutableSet<String> = try {
            val obj = org.json.JSONObject(file.readText())
            val arr = obj.optJSONArray("allow") ?: org.json.JSONArray()
            (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
        } catch (_: Exception) { mutableSetOf() }

        val knownToolNames = TOOL_SECTIONS.flatMap { it.tools }.map { it.name }.toSet()

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(4), dpToPx(20), dpToPx(8))
        }
        scroll.addView(layout)

        val checkBoxes = mutableListOf<Pair<String, CheckBox>>()

        TOOL_SECTIONS.forEach { section ->
            layout.addView(TextView(this).apply {
                text = section.title.uppercase()
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent_blue))
                textSize = 10f
                letterSpacing = 0.12f
                setPadding(dpToPx(2), dpToPx(14), dpToPx(2), dpToPx(4))
            })

            section.tools.forEach { tool ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dpToPx(2), dpToPx(4), dpToPx(2), dpToPx(4))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                }
                val cb = CheckBox(this).apply {
                    isChecked = tool.name in allowSet
                    setOnCheckedChangeListener(null)
                }
                val labelCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpToPx(10), 0, 0, 0)
                }
                labelCol.addView(TextView(this).apply {
                    text = tool.name
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                    textSize = 14f
                })
                labelCol.addView(TextView(this).apply {
                    text = tool.desc
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                    textSize = 11f
                })
                row.addView(cb)
                row.addView(labelCol)
                row.setOnClickListener { cb.isChecked = !cb.isChecked }
                layout.addView(row)
                checkBoxes.add(tool.name to cb)
            }
        }

        // Show any extra entries added via in-session "Always allow" that aren't in our list
        val extras = allowSet.filter { it !in knownToolNames }
        if (extras.isNotEmpty()) {
            layout.addView(TextView(this).apply {
                text = "CUSTOM (added via session)"
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                textSize = 10f
                letterSpacing = 0.12f
                setPadding(dpToPx(2), dpToPx(14), dpToPx(2), dpToPx(4))
            })
            extras.forEach { name ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dpToPx(2), dpToPx(4), dpToPx(2), dpToPx(4))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                }
                val cb = CheckBox(this).apply { isChecked = true }
                val label = TextView(this).apply {
                    text = name
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                    textSize = 14f
                    setPadding(dpToPx(10), 0, 0, 0)
                }
                row.addView(cb)
                row.addView(label)
                row.setOnClickListener { cb.isChecked = !cb.isChecked }
                layout.addView(row)
                checkBoxes.add(name to cb)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Tool Permissions")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val newAllow = checkBoxes.filter { it.second.isChecked }.map { it.first }
                val obj = try { org.json.JSONObject(file.readText()) } catch (_: Exception) { org.json.JSONObject() }
                obj.put("allow", org.json.JSONArray(newAllow))
                file.writeText(obj.toString(2))
                Toast.makeText(this, "Tool permissions saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
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
