package com.claudecodesetup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.data.AiModel
import com.claudecodesetup.data.MalaysiaStatus
import com.claudecodesetup.data.Provider
import com.claudecodesetup.data.Providers
import com.claudecodesetup.data.ProvidersRepository
import com.claudecodesetup.databinding.ActivityLoginFlowBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class LoginFlowActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginFlowBinding
    private lateinit var prefs: AppPreferences

    private var selectedProvider: Provider? = null
    private var selectedModel: AiModel? = null

    // Screen flow state
    private enum class Screen {
        HAS_SUBSCRIPTION, MALAYSIA_CHECK, GEMINI_RECOMMEND, PROVIDER_LIST, API_KEY_ENTRY
    }

    private var currentScreen = Screen.HAS_SUBSCRIPTION

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginFlowBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPreferences(this)
        showHasSubscription()
    }

    // ─── Screen 1: Has subscription? ──────────────────────────────────────────

    private fun showHasSubscription() {
        currentScreen = Screen.HAS_SUBSCRIPTION
        with(binding) {
            tvQuestion.text = "Do you have a Claude subscription or free trial?"
            tvSubtitle.visibility = View.GONE
            btnPrimary.text = "Yes — use my account"
            btnSecondary.text = "No — use free provider"
            btnPrimary.visibility = View.VISIBLE
            btnSecondary.visibility = View.VISIBLE
            providerContainer.visibility = View.GONE
            apiKeyContainer.visibility = View.GONE
            geminiRecommendCard.visibility = View.GONE

            btnPrimary.setOnClickListener { useSubscription() }
            btnSecondary.setOnClickListener { showMalaysiaCheck() }
        }
    }

    // ─── Screen 2: Malaysia check? ────────────────────────────────────────────

    private fun showMalaysiaCheck() {
        currentScreen = Screen.MALAYSIA_CHECK
        with(binding) {
            tvQuestion.text = "Are you in Malaysia?"
            tvSubtitle.visibility = View.GONE
            btnPrimary.text = "Yes"
            btnSecondary.text = "No, other country"
            btnPrimary.visibility = View.VISIBLE
            btnSecondary.visibility = View.VISIBLE
            providerContainer.visibility = View.GONE
            geminiRecommendCard.visibility = View.GONE

            btnPrimary.setOnClickListener { showGeminiRecommend() }
            btnSecondary.setOnClickListener { showProviderList() }
        }
    }

    // ─── Screen 3: Gemini recommendation (Malaysia) ───────────────────────────

    private fun showGeminiRecommend() {
        currentScreen = Screen.GEMINI_RECOMMEND
        val gemini = Providers.GEMINI
        with(binding) {
            tvQuestion.text = "We recommend Google Gemini"
            tvSubtitle.text = "Works perfectly in Malaysia with 1500 free requests per day!"
            tvSubtitle.visibility = View.VISIBLE
            btnPrimary.text = "Use Google Gemini"
            btnSecondary.text = "Let me choose"
            btnPrimary.visibility = View.VISIBLE
            btnSecondary.visibility = View.VISIBLE
            providerContainer.visibility = View.GONE
            geminiRecommendCard.visibility = View.VISIBLE

            btnPrimary.setOnClickListener {
                selectedProvider = gemini
                showApiKeyEntry(gemini)
            }
            btnSecondary.setOnClickListener { showProviderList() }
        }
    }

    // ─── Screen 4: Provider list (async — fetches from assets / remote) ──────

    private fun showProviderList() {
        currentScreen = Screen.PROVIDER_LIST
        with(binding) {
            tvQuestion.text = "Choose your AI provider"
            tvSubtitle.text = "Loading providers…"
            tvSubtitle.visibility = View.VISIBLE
            btnPrimary.visibility = View.GONE
            btnSecondary.visibility = View.GONE
            geminiRecommendCard.visibility = View.GONE
            apiKeyContainer.visibility = View.GONE
            providerContainer.visibility = View.VISIBLE
            providerLoadingBar.visibility = View.VISIBLE
            btnRefreshProviders.visibility = View.GONE
            providerRecycler.adapter = null
        }

        lifecycleScope.launch {
            val result = ProvidersRepository.load(this@LoginFlowActivity)
            with(binding) {
                providerLoadingBar.visibility = View.GONE
                tvSubtitle.text = if (result.fromRemote) "Live list" else "All providers below are free"
                btnRefreshProviders.visibility = View.VISIBLE

                val adapter = ProviderListAdapter(result.providers) { provider ->
                    selectedProvider = provider
                    showApiKeyEntry(provider)
                }
                providerRecycler.layoutManager = LinearLayoutManager(this@LoginFlowActivity)
                providerRecycler.adapter = adapter
            }
        }

        binding.btnRefreshProviders.setOnClickListener { showProviderList() }
    }

    // ─── Screen 5: API key entry ──────────────────────────────────────────────

    private fun showApiKeyEntry(provider: Provider) {
        currentScreen = Screen.API_KEY_ENTRY
        with(binding) {
            tvQuestion.text = "Enter your ${provider.name} API key"
            tvSubtitle.text = "Get a free key at ${provider.signupUrl}"
            tvSubtitle.visibility = View.VISIBLE
            btnPrimary.visibility = View.GONE
            btnSecondary.visibility = View.GONE
            providerContainer.visibility = View.GONE
            geminiRecommendCard.visibility = View.GONE
            apiKeyContainer.visibility = View.VISIBLE

            tvApiSignupLink.text = "Get free key → ${provider.signupUrl}"
            tvApiSignupLink.movementMethod = LinkMovementMethod.getInstance()
            tvApiSignupLink.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.signupUrl)))
            }

            btnPasteKey.setOnClickListener {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    etApiKey.setText(clip.getItemAt(0).text)
                }
            }

            // Model spinner
            val modelNames = provider.models.map { it.name }
            val spinAdapter = ArrayAdapter(
                this@LoginFlowActivity,
                android.R.layout.simple_spinner_item,
                modelNames
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinnerModel.adapter = spinAdapter
            spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedModel = provider.models[pos]
                    tvModelId.text = provider.models[pos].modelId
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            selectedModel = provider.models.firstOrNull()
            tvModelId.text = selectedModel?.modelId ?: ""

            // Show hint for providers that don't need an API key
            if (!provider.requiresApiKey) {
                etApiKey.hint = "No API key needed"
                etApiKey.isEnabled = false
                etApiKey.setText("")
            } else {
                etApiKey.hint = "API Key"
                etApiKey.isEnabled = true
            }

            btnConfirm.setOnClickListener {
                val key = etApiKey.text.toString().trim()
                if (key.isEmpty() && provider.requiresApiKey) {
                    etApiKey.error = "Please enter your API key"
                    return@setOnClickListener
                }
                val model = selectedModel ?: return@setOnClickListener
                btnConfirm.isEnabled = false
                btnConfirm.text = "Validating key…"
                lifecycleScope.launch {
                    val error = validateApiKey(provider, key)
                    if (error == null) {
                        saveAndLaunch(provider, key, model)
                    } else {
                        btnConfirm.isEnabled = true
                        btnConfirm.text = "Start Claude Code →"
                        etApiKey.error = error
                    }
                }
            }
        }
    }

    // ─── API key validation ───────────────────────────────────────────────────

    private val validationClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Returns null if valid, or a short error string if invalid. */
    private suspend fun validateApiKey(provider: Provider, key: String): String? {
        if (!provider.requiresApiKey || key.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                val req = buildValidationRequest(provider, key) ?: return@withContext null
                val resp = validationClient.newCall(req).execute()
                val code = resp.code
                resp.body?.close()
                when {
                    code in 200..299 -> null
                    code == 401 || code == 403 -> "Invalid API key (HTTP $code)"
                    code == 429 -> null  // rate limited but key is valid
                    else -> "Validation failed (HTTP $code) — check key and try again"
                }
            } catch (e: Exception) {
                "Network error — check your connection"
            }
        }
    }

    private fun buildValidationRequest(provider: Provider, key: String): Request? {
        return when (provider.id) {
            "anthropic" -> Request.Builder()
                .url("https://api.anthropic.com/v1/models")
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .build()
            "gemini" -> Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=$key")
                .build()
            "openrouter" -> Request.Builder()
                .url("https://openrouter.ai/api/v1/auth/key")
                .header("Authorization", "Bearer $key")
                .build()
            "nvidia_nim" -> Request.Builder()
                .url("https://integrate.api.nvidia.com/v1/models")
                .header("Authorization", "Bearer $key")
                .build()
            "meta_llama" -> Request.Builder()
                .url("https://api.llama.com/v1/models")
                .header("Authorization", "Bearer $key")
                .build()
            "deepseek" -> Request.Builder()
                .url("https://api.deepseek.com/models")
                .header("Authorization", "Bearer $key")
                .build()
            "kimi" -> Request.Builder()
                .url("https://api.moonshot.ai/v1/models")
                .header("Authorization", "Bearer $key")
                .build()
            else -> null
        }
    }

    // ─── Subscription path ────────────────────────────────────────────────────

    private fun useSubscription() {
        selectedProvider = Providers.ANTHROPIC
        showApiKeyEntry(Providers.ANTHROPIC)
    }

    // ─── Save config and launch ───────────────────────────────────────────────

    private fun saveAndLaunch(provider: Provider, apiKey: String, model: AiModel) {
        val mode = if (provider.id == "anthropic") AppPreferences.MODE_SUBSCRIPTION
                   else AppPreferences.MODE_PROXY
        prefs.setLoginMode(mode)
        prefs.setProviderId(provider.id)
        prefs.setApiKey(apiKey)
        prefs.setModelId(model.modelId)
        prefs.setBaseUrl(provider.baseUrl)
        prefs.setProviderConfigured(true)
        launchTerminal()
    }

    private fun launchTerminal() {
        startActivity(Intent(this, TerminalActivity::class.java))
        finish()
    }

    // ─── Back navigation ──────────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (currentScreen) {
            Screen.HAS_SUBSCRIPTION -> super.onBackPressed()
            Screen.MALAYSIA_CHECK -> showHasSubscription()
            Screen.GEMINI_RECOMMEND -> showMalaysiaCheck()
            Screen.PROVIDER_LIST -> showMalaysiaCheck()
            Screen.API_KEY_ENTRY -> {
                if (selectedProvider?.id == "anthropic") showHasSubscription()
                else showProviderList()
            }
        }
    }

    // ─── Provider list adapter ────────────────────────────────────────────────

    inner class ProviderListAdapter(
        private val providers: List<Provider>,
        private val onSelect: (Provider) -> Unit
    ) : RecyclerView.Adapter<ProviderListAdapter.VH>() {

        inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvProviderName)
            val tvRate: TextView = view.findViewById(R.id.tvProviderRate)
            val tvMalaysia: TextView = view.findViewById(R.id.tvMalaysiaStatus)
            val tvWarning: TextView = view.findViewById(R.id.tvProviderWarning)
            val btnSignup: Button = view.findViewById(R.id.btnProviderSignup)
            val btnSelect: Button = view.findViewById(R.id.btnProviderSelect)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_provider, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = providers[pos]
            h.tvName.text = p.name
            h.tvRate.text = p.rateLimit

            val (emoji, note) = when (p.malaysiaStatus) {
                MalaysiaStatus.GREEN -> "🟢" to p.malaysiaNote
                MalaysiaStatus.YELLOW -> "🟡" to p.malaysiaNote
                MalaysiaStatus.RED -> "🔴" to p.malaysiaNote
            }
            h.tvMalaysia.text = "$emoji $note"

            if (p.warningNote != null) {
                h.tvWarning.visibility = View.VISIBLE
                h.tvWarning.text = "⚠ ${p.warningNote}"
            } else {
                h.tvWarning.visibility = View.GONE
            }

            h.btnSignup.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.signupUrl)))
            }
            h.btnSelect.setOnClickListener { onSelect(p) }
        }

        override fun getItemCount() = providers.size
    }
}
