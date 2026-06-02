package com.claudecodesetup

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Providers
import com.claudecodesetup.databinding.ActivitySettingsBinding
import com.claudecodesetup.managers.NodeBridgeManager
import com.claudecodesetup.managers.UbuntuRootfsManager
import kotlinx.coroutines.launch
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
        if (BuildConfig.DEBUG) addUbuntuEngineDebugSection()
    }

    /**
     * DEBUG-only Ubuntu-engine bring-up panel (ubuntu-engine.md, P1b).
     * Downloads + extracts the proot-distro Ubuntu rootfs, then probes it via
     * proot (`cat /etc/os-release`) to prove the rootfs+proot+binds chain works
     * on-device. Built programmatically so no layout XML changes are needed.
     */
    private fun addUbuntuEngineDebugSection() {
        val ubuntu = UbuntuRootfsManager(this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#151518"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad }
            layoutParams = lp
        }
        val header = TextView(this).apply {
            text = "🐞 Ubuntu engine (debug)"
            setTextColor(Color.parseColor("#E8834A"))
            textSize = 14f
        }
        val status = TextView(this).apply {
            text = if (ubuntu.isInstalled()) "Rootfs: installed" else "Rootfs: not installed"
            setTextColor(Color.parseColor("#9090A0"))
            textSize = 12f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
        }
        val output = TextView(this).apply {
            setTextColor(Color.parseColor("#3DD68C"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setBackgroundColor(Color.parseColor("#0A0A0C"))
            visibility = View.GONE
        }
        val btn = Button(this).apply {
            text = "Install + probe Ubuntu rootfs"
        }
        btn.setOnClickListener {
            btn.isEnabled = false
            bar.visibility = View.VISIBLE
            output.visibility = View.GONE
            lifecycleScope.launch {
                val res = ubuntu.installRootfs { step ->
                    runOnUiThread {
                        status.text = step.phase
                        if (step.pct < 0) { bar.isIndeterminate = true }
                        else { bar.isIndeterminate = false; bar.progress = step.pct }
                    }
                }
                if (!res.success) {
                    runOnUiThread {
                        bar.visibility = View.GONE
                        status.text = "❌ ${res.message}"
                        status.setTextColor(Color.parseColor("#F87171"))
                        btn.isEnabled = true
                    }
                    return@launch
                }
                runOnUiThread { status.text = "✅ ${res.message}\nProbing via proot…" }
                val (code, out) = ubuntu.probeOsRelease()
                runOnUiThread {
                    bar.visibility = View.GONE
                    output.visibility = View.VISIBLE
                    val ok = code == 0 && out.contains("Ubuntu", ignoreCase = true)
                    status.text = if (ok) "✅ Ubuntu rootfs runs via proot (exit=$code)"
                                  else "❌ probe failed (exit=$code)"
                    status.setTextColor(Color.parseColor(if (ok) "#3DD68C" else "#F87171"))
                    output.text = out.take(1500)
                    btn.isEnabled = true
                }
            }
        }

        card.addView(header)
        card.addView(status)
        card.addView(bar)
        card.addView(btn)
        card.addView(output)
        binding.settingsContent.addView(card)
    }

    override fun onResume() {
        super.onResume()
        refreshMcpRows()
        refreshPreferenceToggles()
        refreshToolsSummary()
    }

    private fun refreshToolsSummary() {
        val off = try { JSONArray(prefs.getDisabledToolsJson()).length() } catch (_: Exception) { 0 }
        binding.tvToolsSummary.text =
            if (off == 0) "All tools enabled" else "$off tool(s) turned off"
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

        binding.btnCustomCommands.setOnClickListener { showCustomCommandsDialog() }

        binding.btnManageTools.setOnClickListener { showToolControlDialog() }

        binding.btnRestartBridge.setOnClickListener {
            startService(
                android.content.Intent(this, com.claudecodesetup.services.ClaudeService::class.java)
                    .setAction(com.claudecodesetup.services.ClaudeService.ACTION_RESTART_BRIDGE)
            )
            android.widget.Toast.makeText(this, "Bridge restarting…", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Tool control ────────────────────────────────────────────────────────
    // Lets the user turn individual tools OFF. Disabled tools are stripped from
    // every request by the proxy (bridge.js), so they save input tokens AND
    // actually take effect — unlike the old permissions.allow approach the '*'
    // wildcard silently overrode. Only tools that are actually sent appear here
    // (the always-useless ones — Cron*, Worktree*, etc. — are pruned by the
    // hardcoded PRUNED_TOOLS set and never reach the model regardless).
    private data class ToolDef(val name: String, val desc: String, val core: Boolean)
    private data class ToolGroup(val title: String, val tools: List<ToolDef>)

    private val toolGroups = listOf(
        ToolGroup("File & Shell  ·  recommended on", listOf(
            ToolDef("Read",  "Read file contents", true),
            ToolDef("Write", "Create or overwrite files", true),
            ToolDef("Edit",  "Modify existing files", true),
            ToolDef("Bash",  "Run terminal commands (also runs !install tools)", true),
            ToolDef("Glob",  "Find files by name pattern", true),
            ToolDef("Grep",  "Search text inside files", true),
        )),
        ToolGroup("Web", listOf(
            ToolDef("WebSearch", "Search the web", false),
            ToolDef("WebFetch",  "Open and read a URL", false),
        )),
        ToolGroup("Agents & Tasks", listOf(
            ToolDef("Agent",           "Spawn a sub-agent for multi-step tasks", false),
            ToolDef("TodoWrite",       "Claude tracks its own task checklist", false),
            ToolDef("AskUserQuestion", "Ask you a multiple-choice question", false),
            ToolDef("Skill",           "Run a built-in skill", false),
        )),
    )

    private fun showToolControlDialog() {
        val disabled = try {
            val a = JSONArray(prefs.getDisabledToolsJson())
            (0 until a.length()).map { a.getString(it) }.toMutableSet()
        } catch (_: Exception) { mutableSetOf<String>() }

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), px(8))
        }
        content.addView(TextView(this).apply {
            text = "Turning a tool off removes that ability and shrinks every request (fewer input tokens). File & shell tools are needed for normal coding."
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_tertiary))
            setPadding(0, 0, 0, px(8))
        })

        val boxes = mutableMapOf<String, CheckBox>()
        for (group in toolGroups) {
            content.addView(TextView(this).apply {
                text = group.title
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent_orange))
                setPadding(0, px(12), 0, px(4))
            })
            for (t in group.tools) {
                val cb = CheckBox(this).apply {
                    text = "${t.name} — ${t.desc}"
                    textSize = 13f
                    isChecked = !disabled.contains(t.name)  // checked = enabled
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                }
                boxes[t.name] = cb
                content.addView(cb)
            }
        }

        val scroll = ScrollView(this).apply { addView(content) }

        AlertDialog.Builder(this)
            .setTitle("Tools")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val off = JSONArray()
                boxes.forEach { (name, cb) -> if (!cb.isChecked) off.put(name) }
                prefs.saveDisabledToolsJson(off.toString())
                bridgeManager.refreshConfig(prefs)   // rewrite bridge_config.json now
                refreshToolsSummary()
                Toast.makeText(this,
                    if (off.length() == 0) "All tools enabled"
                    else "${off.length()} tool(s) turned off — applies on next message",
                    Toast.LENGTH_SHORT).show()
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
