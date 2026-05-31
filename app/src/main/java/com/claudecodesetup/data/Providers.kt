package com.claudecodesetup.data

import androidx.annotation.DrawableRes
import com.claudecodesetup.R

object Cap {
    const val TOOLS     = "tools"     // supports function/tool calling (agentic mode)
    const val VISION    = "vision"    // understands image input
    const val REASONING = "reasoning" // extended chain-of-thought / thinking
    const val FAST      = "fast"      // low latency / small/flash model
    const val FREE      = "free"      // free tier available
    const val CODING    = "coding"    // specialized for code generation
    const val LONG_CTX  = "long_ctx"  // 200K+ context window
}

data class AiModel(
    val name: String,
    val modelId: String,
    val caps: Set<String> = emptySet(),
    val description: String = ""
)

data class Provider(
    val id: String,
    val name: String,
    val signupUrl: String,
    val rateLimit: String,
    val malaysiaStatus: MalaysiaStatus,
    val malaysiaNote: String,
    val warningNote: String? = null,
    val baseUrl: String,
    val requiresProxy: Boolean,
    val requiresApiKey: Boolean = true,
    val models: List<AiModel>,
    /** Clearbit / brand logo URL — legacy fallback when iconResId is not set. */
    val iconUrl: String = "",
    /** Bundled VectorDrawable for the provider's brand mark. 0 = none, falls back
     *  to iconUrl then to the first-letter initial tile. */
    @DrawableRes val iconResId: Int = 0,
    /** Show a server URL input field in the API-key screen (Ollama / private servers). */
    val isUrlConfigurable: Boolean = false,
    /** Supports live model list fetch (shows ↻ Refresh in model picker). */
    val supportsLiveFetch: Boolean = false
)

enum class MalaysiaStatus { GREEN, YELLOW, RED }

object Providers {

    val NVIDIA_NIM = Provider(
        id = "nvidia_nim",
        name = "NVIDIA NIM",
        iconResId = R.drawable.ic_brand_nvidia,
        supportsLiveFetch = true,
        signupUrl = "https://build.nvidia.com/models",
        rateLimit = "40 req/min · Free forever",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("GLM 4.7",          "z-ai/glm4.7",                            setOf(Cap.TOOLS, Cap.FREE),      "General chat"),
            AiModel("GLM 5",            "z-ai/glm5",                              setOf(Cap.TOOLS, Cap.FREE),      "Latest GLM"),
            AiModel("Kimi K2.5",        "moonshotai/kimi-k2.5",                   setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX, Cap.FREE), "Vision · 1M ctx"),
            AiModel("MiniMax M2.5",     "minimaxai/minimax-m2.5",                 setOf(Cap.TOOLS, Cap.FREE),      "General chat"),
            AiModel("Step 3.5 Flash",   "stepfun-ai/step-3.5-flash",              setOf(Cap.FAST, Cap.FREE),       "Fast chat"),
            AiModel("DeepSeek V4 Flash","deepseek-ai/deepseek-v4-flash",          setOf(Cap.REASONING, Cap.FAST, Cap.FREE), "Fast thinker"),
            AiModel("Llama 3.3 70B",    "meta/llama-3.3-70b-instruct",            setOf(Cap.TOOLS, Cap.FREE),      "Reliable · text only"),
            AiModel("Qwen 3.5 235B",    "qwen/qwen3.5-235b-a22b",                 setOf(Cap.TOOLS, Cap.REASONING, Cap.FREE), "Smart · large")
        )
    )

    val OPENROUTER = Provider(
        id = "openrouter",
        name = "OpenRouter",
        iconResId = R.drawable.ic_brand_openrouter,
        supportsLiveFetch = true,
        signupUrl = "https://openrouter.ai",
        rateLimit = "20 req/min · 50 req/day free",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Email signup works",
        warningNote = "50 req/day = ~5 real prompts. Consider Gemini for 1500/day instead.",
        baseUrl = "https://openrouter.ai/api/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Kimi K2.5 ⭐",         "moonshotai/kimi-k2:free",                             setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX, Cap.FREE), "Best free pick · vision"),
            AiModel("GPT-OSS 120B",          "openai/gpt-oss-120b:free",                           setOf(Cap.TOOLS, Cap.FREE),                           "OpenAI open source"),
            AiModel("GPT-OSS 20B",           "openai/gpt-oss-20b:free",                            setOf(Cap.TOOLS, Cap.FAST, Cap.FREE),                 "Lightweight OpenAI"),
            AiModel("MiniMax M2.5",          "minimax/minimax-m2.5:free",                          setOf(Cap.TOOLS, Cap.FREE),                           "General chat"),
            AiModel("Nemotron Super 120B",   "nvidia/nemotron-3-super-120b-a12b:free",             setOf(Cap.TOOLS, Cap.FREE),                           "NVIDIA large model"),
            AiModel("Nemotron Nano Omni 30B","nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", setOf(Cap.REASONING, Cap.FAST, Cap.FREE),             "Fast reasoning · text only"),
            AiModel("Nemotron Nano 12B VL",  "nvidia/nemotron-nano-12b-v2-vl:free",                setOf(Cap.VISION, Cap.FAST, Cap.FREE),                "Vision · compact"),
            AiModel("Nemotron Nano 9B",      "nvidia/nemotron-nano-9b-v2:free",                    setOf(Cap.FAST, Cap.FREE),                            "Ultra fast · text only"),
            AiModel("Llama 4 Scout",         "meta-llama/llama-4-scout:free",                      setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX, Cap.FREE), "Vision · 10M ctx"),
            AiModel("Llama 4 Maverick",      "meta-llama/llama-4-maverick:free",                   setOf(Cap.TOOLS, Cap.VISION, Cap.FREE),               "Vision capable"),
            AiModel("Llama 3.3 70B",         "meta-llama/llama-3.3-70b-instruct:free",             setOf(Cap.TOOLS, Cap.FREE),                           "Reliable · text only"),
            AiModel("DeepSeek R1",           "deepseek/deepseek-r1-0528:free",                     setOf(Cap.REASONING, Cap.FREE),                       "Chain-of-thought"),
            AiModel("DeepSeek V3",           "deepseek/deepseek-chat-v3-5:free",                   setOf(Cap.TOOLS, Cap.FREE),                           "Smart general"),
            AiModel("Qwen 3 235B A22B",      "qwen/qwen3-235b-a22b:free",                          setOf(Cap.TOOLS, Cap.REASONING, Cap.FREE),            "Best reasoning free"),
            AiModel("Qwen 3 30B A3B",        "qwen/qwen3-30b-a3b:free",                            setOf(Cap.TOOLS, Cap.FREE),                           "Compact smart"),
            AiModel("Mistral Small 3.2",     "mistralai/mistral-small-3.2-24b-instruct:free",      setOf(Cap.TOOLS, Cap.FREE),                           "EU model · efficient"),
            AiModel("Gemma 3 27B",           "google/gemma-3-27b-it:free",                         setOf(Cap.TOOLS, Cap.FREE),                           "Google open model"),
            AiModel("Gemma 3 12B",           "google/gemma-3-12b-it:free",                         setOf(Cap.TOOLS, Cap.FAST, Cap.FREE),                 "Fast · Google"),
            AiModel("Laguna M.1 (Poolside)", "poolside/laguna-m.1:free",                           setOf(Cap.CODING, Cap.FREE),                          "Code specialist"),
            AiModel("Cobuddy (Baidu)",       "baidu/cobuddy:free",                                 setOf(Cap.FREE),                                      "Baidu model"),
            AiModel("LFM 2.5 1.2B (Liquid)", "liquid/lfm-2.5-1.2b-instruct:free",                 setOf(Cap.FAST, Cap.FREE),                            "Ultra tiny · edge")
        )
    )

    val GEMINI = Provider(
        id = "gemini",
        name = "Google Gemini",
        iconResId = R.drawable.ic_brand_gemini,
        supportsLiveFetch = true,
        signupUrl = "https://aistudio.google.com",
        rateLimit = "15 req/min · 1500 req/day free",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Google account only — recommended for Malaysia",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        requiresProxy = true,
        models = listOf(
            AiModel("Gemini 3.1 Pro (Preview)", "gemini-3.1-pro-preview", setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX, Cap.FREE), "Most capable · agentic"),
            AiModel("Gemini 3 Flash (Preview)", "gemini-3-flash-preview",  setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.FAST, Cap.LONG_CTX, Cap.FREE), "Best balance · fast"),
            AiModel("Gemini 3.1 Flash Lite",    "gemini-3.1-flash-lite",   setOf(Cap.TOOLS, Cap.VISION, Cap.FAST, Cap.FREE),                            "Ultra fast · cheap"),
            AiModel("Gemini 2.5 Pro",           "gemini-2.5-pro",          setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX, Cap.FREE),         "Deep reasoning"),
            AiModel("Gemini 2.5 Flash",         "gemini-2.5-flash",        setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.FAST, Cap.LONG_CTX, Cap.FREE), "Smart & fast"),
            AiModel("Gemini 2.5 Flash Lite",    "gemini-2.5-flash-lite",   setOf(Cap.TOOLS, Cap.VISION, Cap.FAST, Cap.FREE),                            "Budget pick"),
            AiModel("Gemini 2.0 Flash",         "gemini-2.0-flash",        setOf(Cap.TOOLS, Cap.VISION, Cap.FAST, Cap.LONG_CTX, Cap.FREE),              "Reliable workhorse"),
            AiModel("Gemini 1.5 Flash",         "gemini-1.5-flash",        setOf(Cap.TOOLS, Cap.VISION, Cap.FAST, Cap.LONG_CTX, Cap.FREE),              "Legacy stable"),
            AiModel("Gemini 1.5 Flash 8B",      "gemini-1.5-flash-8b",     setOf(Cap.TOOLS, Cap.VISION, Cap.FAST, Cap.FREE),                            "Tiny & fast")
        )
    )

    val META_LLAMA = Provider(
        id = "meta_llama",
        name = "Meta Llama API",
        iconResId = R.drawable.ic_brand_meta,
        supportsLiveFetch = true,
        signupUrl = "https://llama.developer.meta.com",
        rateLimit = "Limited free preview",
        malaysiaStatus = MalaysiaStatus.YELLOW,
        malaysiaNote = "US priority — may work in Malaysia",
        baseUrl = "https://api.llama.com/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Llama 4 Scout",    "meta-llama/llama-4-scout",          setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX), "Vision · 10M ctx"),
            AiModel("Llama 4 Maverick", "meta-llama/llama-4-maverick",       setOf(Cap.TOOLS, Cap.VISION),               "Vision capable"),
            AiModel("Llama 3.3 70B",    "meta-llama/llama-3.3-70b-instruct", setOf(Cap.TOOLS),                           "Text only · reliable")
        )
    )

    val DEEPSEEK = Provider(
        id = "deepseek",
        name = "DeepSeek",
        iconResId = R.drawable.ic_brand_deepseek,
        supportsLiveFetch = true,
        signupUrl = "https://platform.deepseek.com/api_keys",
        rateLimit = "Very cheap · Free tier available",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere",
        baseUrl = "https://api.deepseek.com/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("DeepSeek Chat (V3)",     "deepseek-chat",     setOf(Cap.TOOLS),     "General chat · text only"),
            AiModel("DeepSeek Reasoner (R1)", "deepseek-reasoner", setOf(Cap.REASONING), "Deep reasoning · text only")
        )
    )

    val KIMI = Provider(
        id = "kimi",
        name = "Kimi (Moonshot AI)",
        // No CC0 brand mark available — falls back to stylized tile.
        // Run scripts/add-brand-icon.sh with Moonshot's official SVG URL to populate.
        supportsLiveFetch = true,
        signupUrl = "https://platform.moonshot.ai",
        rateLimit = "Paid credits required",
        malaysiaStatus = MalaysiaStatus.YELLOW,
        malaysiaNote = "Direct API requires paid credits — use Kimi K2.5 via OpenRouter (free) instead",
        warningNote = "Direct Kimi API suspends account when balance is 0. Use OpenRouter → Kimi K2.5 for free access.",
        baseUrl = "https://api.moonshot.ai/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Kimi K2",         "kimi-k2",          setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX), "Vision · 1M ctx"),
            AiModel("Moonshot v1 8k",  "moonshot-v1-8k",   setOf(Cap.TOOLS),                           "Short context · text"),
            AiModel("Moonshot v1 32k", "moonshot-v1-32k",  setOf(Cap.TOOLS),                           "Medium context · text")
        )
    )

    val QWEN = Provider(
        id = "qwen",
        name = "Qwen (Alibaba)",
        iconResId = R.drawable.ic_brand_qwen,
        supportsLiveFetch = true,
        signupUrl = "https://modelstudio.console.alibabacloud.com/",
        rateLimit = "Free trial quota on signup · then paid",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "International endpoint (Singapore) — works in Malaysia",
        baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Qwen Max",         "qwen-max",         setOf(Cap.TOOLS, Cap.LONG_CTX),             "Most capable · text"),
            AiModel("Qwen Plus",        "qwen-plus",        setOf(Cap.TOOLS, Cap.LONG_CTX),             "Balanced"),
            AiModel("Qwen Turbo",       "qwen-turbo",       setOf(Cap.TOOLS, Cap.FAST, Cap.LONG_CTX),   "Fast · cheap"),
            AiModel("Qwen3 Coder Plus", "qwen3-coder-plus", setOf(Cap.TOOLS, Cap.CODING, Cap.LONG_CTX), "Code specialist"),
            AiModel("Qwen VL Max",      "qwen-vl-max",      setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX), "Vision · most capable"),
            AiModel("QwQ Plus",         "qwq-plus",         setOf(Cap.REASONING),                       "Deep reasoning · text")
        )
    )

    val MISTRAL = Provider(
        id = "mistral",
        name = "Mistral AI",
        iconResId = R.drawable.ic_brand_mistral,
        supportsLiveFetch = true,
        signupUrl = "https://console.mistral.ai/api-keys",
        rateLimit = "Free tier (rate-limited) · then paid",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "EU provider — works everywhere",
        baseUrl = "https://api.mistral.ai/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Mistral Large",     "mistral-large-latest",  setOf(Cap.TOOLS, Cap.LONG_CTX),          "Most capable · text"),
            AiModel("Mistral Medium",    "mistral-medium-latest", setOf(Cap.TOOLS, Cap.LONG_CTX),          "Balanced"),
            AiModel("Mistral Small",     "mistral-small-latest",  setOf(Cap.TOOLS, Cap.FAST),              "Fast · efficient"),
            AiModel("Codestral",         "codestral-latest",      setOf(Cap.TOOLS, Cap.CODING),            "Code specialist"),
            AiModel("Pixtral Large",     "pixtral-large-latest",  setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX), "Vision capable"),
            AiModel("Ministral 8B",      "ministral-8b-latest",   setOf(Cap.TOOLS, Cap.FAST),              "Tiny · edge"),
            AiModel("Magistral Medium",  "magistral-medium-latest", setOf(Cap.REASONING),                  "Deep reasoning")
        )
    )

    val OLLAMA = Provider(
        id = "ollama",
        name = "Personal AI",
        iconResId = R.drawable.ic_brand_ollama,
        isUrlConfigurable = true,
        supportsLiveFetch = true,
        signupUrl = "https://ollama.com/library",
        rateLimit = "Unlimited · 100% free · Private",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "No internet needed",
        warningNote = null,
        baseUrl = "http://localhost:11434/v1",
        requiresProxy = true,
        requiresApiKey = false,
        models = listOf(
            AiModel("Qwen3 1.5B",        "qwen3:1.5b",         setOf(Cap.FAST, Cap.FREE, Cap.TOOLS),  "~1.1 GB · 6GB+ RAM"),
            AiModel("Phi-4 Mini 3.8B",   "phi4-mini:latest",   setOf(Cap.FAST, Cap.FREE, Cap.CODING), "~2.7 GB · 6GB+ RAM"),
            AiModel("Llama 3.2 3B",      "llama3.2:3b",        setOf(Cap.FAST, Cap.FREE),             "~2.2 GB · 6GB+ RAM"),
            AiModel("Qwen3 4B",          "qwen3:4b",           setOf(Cap.TOOLS, Cap.FREE, Cap.REASONING), "~2.9 GB · 8GB+ RAM"),
            AiModel("Gemma3 4B",         "gemma3:4b",          setOf(Cap.TOOLS, Cap.FREE),            "~3.3 GB · 8GB+ RAM"),
            AiModel("Qwen3 8B",          "qwen3:8b",           setOf(Cap.TOOLS, Cap.FREE, Cap.CODING), "~5.8 GB · 12GB+ RAM"),
            AiModel("Qwen 2.5 Coder 7B", "qwen2.5-coder:7b",  setOf(Cap.CODING, Cap.FAST, Cap.FREE), "~5 GB · code specialist"),
            AiModel("Mistral 7B",        "mistral:7b",         setOf(Cap.FAST, Cap.FREE),             "~4.1 GB · general chat")
        )
    )

    val GROQ = Provider(
        id = "groq",
        name = "Groq",
        // No CC0 brand mark available — falls back to stylized tile.
        // Run scripts/add-brand-icon.sh with Groq's official SVG URL to populate.
        supportsLiveFetch = true,
        signupUrl = "https://console.groq.com/keys",
        rateLimit = "Free · 14,400 req/day · Ultra-fast",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere — no CC required",
        baseUrl = "https://api.groq.com/openai/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Llama 4 Scout 17B",       "meta-llama/llama-4-scout-17b-16e-instruct",      setOf(Cap.TOOLS, Cap.VISION, Cap.FAST, Cap.FREE), "Vision · ultra fast"),
            AiModel("Llama 4 Maverick 17B",    "meta-llama/llama-4-maverick-17b-128e-instruct",  setOf(Cap.TOOLS, Cap.VISION, Cap.FREE),           "Vision capable"),
            AiModel("Llama 3.3 70B",           "llama-3.3-70b-versatile",                        setOf(Cap.TOOLS, Cap.FREE),                       "Reliable · text only"),
            AiModel("Llama 3.1 8B (Instant)",  "llama-3.1-8b-instant",                           setOf(Cap.FAST, Cap.FREE),                        "Fastest on Groq"),
            AiModel("DeepSeek R1 Distill 70B", "deepseek-r1-distill-llama-70b",                  setOf(Cap.REASONING, Cap.FREE),                   "Chain-of-thought"),
            AiModel("Qwen QwQ 32B",            "qwen-qwq-32b",                                   setOf(Cap.REASONING, Cap.FREE),                   "Math & reasoning"),
            AiModel("Gemma 2 9B",              "gemma2-9b-it",                                   setOf(Cap.FAST, Cap.FREE),                        "Compact · text only"),
            AiModel("Mixtral 8x7B",            "mixtral-8x7b-32768",                             setOf(Cap.TOOLS, Cap.LONG_CTX, Cap.FREE),         "MoE · long context")
        )
    )

    val ANTHROPIC = Provider(
        id = "anthropic",
        name = "Anthropic (Claude.ai)",
        iconResId = R.drawable.ic_brand_claude,
        supportsLiveFetch = false,
        signupUrl = "https://claude.ai",
        rateLimit = "Subscription billing",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere",
        baseUrl = "",
        requiresProxy = false,
        models = listOf(
            AiModel("Claude Sonnet 4.6", "claude-sonnet-4-6",         setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX), "Best balance · latest"),
            AiModel("Claude Opus 4.7",   "claude-opus-4-7",           setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX), "Most capable · latest"),
            AiModel("Claude Haiku 4.5",  "claude-haiku-4-5-20251001", setOf(Cap.TOOLS, Cap.VISION, Cap.FAST),                    "Fastest · cheapest"),
            AiModel("Claude Sonnet 4.5", "claude-sonnet-4-5",         setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX), "Previous gen"),
            AiModel("Claude Opus 4.5",   "claude-opus-4-5",           setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX), "Previous gen")
        )
    )

    val ANTHROPIC_API = Provider(
        id = "anthropic_api",
        name = "Anthropic API",
        iconResId = R.drawable.ic_brand_claude,
        supportsLiveFetch = true,
        signupUrl = "https://console.anthropic.com/settings/api-keys",
        rateLimit = "Pay per token · no rate limit",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere",
        baseUrl = "https://api.anthropic.com",
        requiresProxy = true,
        models = listOf(
            AiModel("Claude Sonnet 4.6", "claude-sonnet-4-6",         setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX), "Best balance · latest"),
            AiModel("Claude Opus 4.7",   "claude-opus-4-7",           setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX), "Most capable · latest"),
            AiModel("Claude Haiku 4.5",  "claude-haiku-4-5-20251001", setOf(Cap.TOOLS, Cap.VISION, Cap.FAST),                    "Fastest · cheapest"),
            AiModel("Claude Sonnet 4.5", "claude-sonnet-4-5",         setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX), "Previous gen"),
            AiModel("Claude Opus 4.5",   "claude-opus-4-5",           setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX), "Previous gen")
        )
    )

    val LOCAL_LLAMA = Provider(
        id = "local_llama",
        name = "Local AI (On-Device)",
        iconUrl = "",
        supportsLiveFetch = false,
        signupUrl = "",
        rateLimit = "Unlimited · 100% offline · Private",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "No internet needed",
        baseUrl = "http://127.0.0.1:8080/v1",
        requiresProxy = true,
        requiresApiKey = false,
        isUrlConfigurable = false,
        models = emptyList()
    )

    val ALL = listOf(GROQ, GEMINI, OPENROUTER, ANTHROPIC_API, DEEPSEEK, KIMI, QWEN, MISTRAL, NVIDIA_NIM, META_LLAMA, OLLAMA)

    fun byId(id: String): Provider? = when (id) {
        "anthropic"     -> ANTHROPIC
        "anthropic_api" -> ANTHROPIC_API
        "local_llama"   -> LOCAL_LLAMA
        else            -> ALL.find { it.id == id }
    }

    /** Infer capability flags from a model ID — used for live-fetched OpenRouter models. */
    fun deriveCaps(modelId: String): Set<String> {
        val lo = modelId.lowercase()
        val caps = mutableSetOf<String>()
        if (":free" in lo || "kimi-k2" in lo) caps += Cap.FREE
        if ("vl" in lo || "vision" in lo || "scout" in lo || "maverick" in lo ||
            "gemini" in lo || "claude" in lo || "pixtral" in lo) caps += Cap.VISION
        if ("r1" in lo || "reason" in lo || "think" in lo || "qwq" in lo || "o1" in lo ||
            "o3" in lo || "gemini-2.5" in lo || "gemini-3" in lo || "magistral" in lo) caps += Cap.REASONING
        if ("flash" in lo || "fast" in lo || "nano" in lo || "mini" in lo ||
            "8b" in lo || "1.2b" in lo || "7b" in lo || "haiku" in lo || "lite" in lo) caps += Cap.FAST
        if ("code" in lo || "coder" in lo || "coding" in lo || "laguna" in lo || "poolside" in lo) caps += Cap.CODING
        if ("gemini-1.5" in lo || "gemini-2" in lo || "gemini-3" in lo || "kimi-k2" in lo ||
            "llama-4-scout" in lo || "claude" in lo) caps += Cap.LONG_CTX
        // Most major models support tools
        if ("gpt" in lo || "llama" in lo || "mistral" in lo || "gemma" in lo ||
            "qwen" in lo || "gemini" in lo || "kimi" in lo || "deepseek-chat" in lo ||
            "minimax" in lo || "claude" in lo || "glm" in lo || "mixtral" in lo) caps += Cap.TOOLS
        return caps
    }

    /** Derive a short human description for live-fetched models that have no static description. */
    fun deriveDescription(modelId: String, caps: Set<String>): String {
        val lo = modelId.lowercase()
        return when {
            Cap.VISION in caps && Cap.REASONING in caps && Cap.FAST in caps -> "Vision · fast thinking"
            Cap.VISION in caps && Cap.REASONING in caps                      -> "Vision · deep reasoning"
            Cap.VISION in caps && Cap.FAST in caps                           -> "Vision · fast"
            Cap.VISION in caps                                               -> "Vision capable"
            Cap.REASONING in caps && Cap.FAST in caps                        -> "Fast reasoning"
            Cap.REASONING in caps                                            -> "Deep reasoning"
            Cap.CODING in caps                                               -> "Code specialist"
            Cap.FAST in caps && Cap.LONG_CTX in caps                        -> "Fast · long context"
            Cap.FAST in caps                                                 -> "Fast responses"
            Cap.LONG_CTX in caps                                             -> "Long context"
            "embed" in lo || "embedding" in lo                               -> "Embeddings only"
            else                                                             -> "General purpose"
        }
    }
}
