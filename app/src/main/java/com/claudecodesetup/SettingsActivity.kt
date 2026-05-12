package com.claudecodesetup

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.Providers
import com.claudecodesetup.data.ProvidersRepository
import com.claudecodesetup.databinding.ActivitySettingsBinding
import com.claudecodesetup.managers.NodeBridgeManager
import kotlinx.coroutines.launch

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

        binding.tvCurrentProvider.text = when (mode) {
            AppPreferences.MODE_SUBSCRIPTION -> "Claude Subscription"
            AppPreferences.MODE_GEMINI       -> "Google Gemini — $model"
            else -> "${provider?.name ?: "Unknown"} — $model"
        }

        // Show "Change model" only for OpenRouter (live free model list available)
        binding.btnChangeModel.visibility =
            if (providerId == "openrouter") View.VISIBLE else View.GONE

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

    private fun setupActions() {
        binding.btnChangeProvider.setOnClickListener {
            prefs.clearProviderOnly()
            startActivity(Intent(this, com.claudecodesetup.ui.ComposeActivity::class.java))
            finish()
        }

        binding.btnChangeModel.setOnClickListener { startModelRefresh() }

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
    }

    // ─── Live model picker ────────────────────────────────────────────────────

    private fun startModelRefresh() {
        val key = prefs.getApiKey()
        if (key.isEmpty()) {
            Toast.makeText(this, "No API key stored — re-configure via Change provider", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnChangeModel.isEnabled = false
        binding.btnChangeModel.text = "Loading models…"
        lifecycleScope.launch {
            try {
                val models = ProvidersRepository.fetchOpenRouterFreeModels(key)
                if (models.isEmpty()) {
                    Toast.makeText(this@SettingsActivity,
                        "No free models found — check your API key", Toast.LENGTH_SHORT).show()
                } else {
                    showModelPickerDialog(models)
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity,
                    "Failed to load models: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnChangeModel.isEnabled = true
                binding.btnChangeModel.text = "Change model"
            }
        }
    }

    private fun showModelPickerDialog(models: List<AiModel>) {
        val currentModelId = prefs.getModelId()
        val names = models.map { it.name }.toTypedArray()
        val currentIndex = models.indexOfFirst { it.modelId == currentModelId }.coerceAtLeast(0)
        var selectedIndex = currentIndex

        AlertDialog.Builder(this)
            .setTitle("Free models (${models.size})")
            .setSingleChoiceItems(names, currentIndex) { _, which -> selectedIndex = which }
            .setPositiveButton("Apply") { _, _ ->
                val model = models[selectedIndex]
                prefs.setModelId(model.modelId)
                bridgeManager.refreshConfig(prefs)

                val provider = Providers.byId(prefs.getProviderId())
                binding.tvCurrentProvider.text =
                    "${provider?.name ?: "OpenRouter"} — ${model.modelId}"
                Toast.makeText(this, "Model set to ${model.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Reset ────────────────────────────────────────────────────────────────

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
