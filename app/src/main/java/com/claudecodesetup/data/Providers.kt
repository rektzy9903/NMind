package com.claudecodesetup.data

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
    val caps: Set<String> = emptySet()
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
    /** Clearbit / brand logo URL shown in provider and model cards. */
    val iconUrl: String = "",
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
        iconUrl = "https://logo.clearbit.com/nvidia.com",
        supportsLiveFetch = true,
        signupUrl = "https://build.nvidia.com/models",
        rateLimit = "40 req/min · Free forever",
        malaysiaStatus = MalaysiaStatus.RED,
        malaysiaNote = "SMS verification fails in Malaysia",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("GLM 4.7", "z-ai/glm4.7",                            setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("GLM 5",   "z-ai/glm5",                              setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("Kimi K2.5",        "moonshotai/kimi-k2.5",          setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX, Cap.FREE)),
            AiModel("MiniMax M2.5",     "minimaxai/minimax-m2.5",        setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("Step 3.5 Flash",   "stepfun-ai/step-3.5-flash",     setOf(Cap.FAST, Cap.FREE)),
            AiModel("DeepSeek V4 Flash","deepseek-ai/deepseek-v4-flash", setOf(Cap.REASONING, Cap.FAST, Cap.FREE)),
            AiModel("Llama 3.3 70B",    "meta/llama-3.3-70b-instruct",   setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("Qwen 3.5 235B",    "qwen/qwen3.5-235b-a22b",        setOf(Cap.TOOLS, Cap.REASONING, Cap.FREE))
        )
    )

    val OPENROUTER = Provider(
        id = "openrouter",
        name = "OpenRouter",
        iconUrl = "https://logo.clearbit.com/openrouter.ai",
        supportsLiveFetch = true,
        signupUrl = "https://openrouter.ai",
        rateLimit = "20 req/min · 50 req/day free",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Email signup works",
        warningNote = "50 req/day = ~5 real prompts. Consider Gemini for 1500/day instead.",
        baseUrl = "https://openrouter.ai/api/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Kimi K2.5 ⭐ (Recommended)", "moonshotai/kimi-k2.5",                         setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX, Cap.FREE)),
            AiModel("GPT-OSS 120B",                "openai/gpt-oss-120b:free",                      setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("GPT-OSS 20B",                 "openai/gpt-oss-20b:free",                       setOf(Cap.TOOLS, Cap.FAST, Cap.FREE)),
            AiModel("MiniMax M2.5",                "minimax/minimax-m2.5:free",                     setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("Nemotron Super 120B",          "nvidia/nemotron-3-super-120b-a12b:free",        setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("Nemotron Nano Omni 30B",       "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", setOf(Cap.REASONING, Cap.FAST, Cap.FREE)),
            AiModel("Nemotron Nano 12B VL",         "nvidia/nemotron-nano-12b-v2-vl:free",           setOf(Cap.VISION, Cap.FAST, Cap.FREE)),
            AiModel("Nemotron Nano 9B",             "nvidia/nemotron-nano-9b-v2:free",               setOf(Cap.FAST, Cap.FREE)),
            AiModel("Llama 4 Scout",                "meta-llama/llama-4-scout:free",                 setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX, Cap.FREE)),
            AiModel("Llama 4 Maverick",             "meta-llama/llama-4-maverick:free",              setOf(Cap.TOOLS, Cap.VISION, Cap.FREE)),
            AiModel("Llama 3.3 70B",               "meta-llama/llama-3.3-70b-instruct:free",        setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("DeepSeek R1 (free)",           "deepseek/deepseek-r1:free",                     setOf(Cap.REASONING, Cap.FREE)),
            AiModel("DeepSeek V3 (free)",           "deepseek/deepseek-chat-v3-5:free",              setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("Qwen 3 235B A22B",             "qwen/qwen3-235b-a22b:free",                     setOf(Cap.TOOLS, Cap.REASONING, Cap.FREE)),
            AiModel("Qwen 3 30B A3B",               "qwen/qwen3-30b-a3b:free",                       setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("Mistral Small 3.2",            "mistralai/mistral-small-3.2-24b-instruct:free", setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("Gemma 3 27B",                  "google/gemma-3-27b-it:free",                    setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("Gemma 3 12B",                  "google/gemma-3-12b-it:free",                    setOf(Cap.TOOLS, Cap.FAST, Cap.FREE)),
            AiModel("Laguna M.1 (Poolside)",        "poolside/laguna-m.1:free",                      setOf(Cap.CODING, Cap.FREE)),
            AiModel("Cobuddy (Baidu)",              "baidu/cobuddy:free",                            setOf(Cap.FREE)),
            AiModel("LFM 2.5 1.2B (Liquid)",       "liquid/lfm-2.5-1.2b-instruct:free",             setOf(Cap.FAST, Cap.FREE))
        )
    )

    val GEMINI = Provider(
        id = "gemini",
        name = "Google Gemini",
        iconUrl = "https://logo.clearbit.com/google.com",
        supportsLiveFetch = true,
        signupUrl = "https://aistudio.google.com",
        rateLimit = "15 req/min · 1500 req/day free",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Google account only — recommended for Malaysia",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        requiresProxy = true,
        models = listOf(
            AiModel("Gemini 3.1 Pro (Preview)",      "gemini-3.1-pro-preview",       setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX, Cap.FREE)),
            AiModel("Gemini 3 Flash (Preview)",      "gemini-3-flash-preview",        setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.FAST, Cap.LONG_CTX, Cap.FREE)),
            AiModel("Gemini 3.1 Flash Lite",         "gemini-3.1-flash-lite",         setOf(Cap.TOOLS, Cap.VISION, Cap.FAST, Cap.FREE)),
            AiModel("Gemini 2.5 Pro",                "gemini-2.5-pro",                setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX, Cap.FREE)),
            AiModel("Gemini 2.5 Flash",              "gemini-2.5-flash",              setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.FAST, Cap.LONG_CTX, Cap.FREE)),
            AiModel("Gemini 2.5 Flash Lite",         "gemini-2.5-flash-lite",         setOf(Cap.TOOLS, Cap.VISION, Cap.FAST, Cap.FREE)),
            AiModel("Gemini 2.0 Flash",              "gemini-2.0-flash",              setOf(Cap.TOOLS, Cap.VISION, Cap.FAST, Cap.LONG_CTX, Cap.FREE)),
            AiModel("Gemini 1.5 Flash",              "gemini-1.5-flash",              setOf(Cap.TOOLS, Cap.VISION, Cap.FAST, Cap.LONG_CTX, Cap.FREE)),
            AiModel("Gemini 1.5 Flash 8B",           "gemini-1.5-flash-8b",           setOf(Cap.TOOLS, Cap.VISION, Cap.FAST, Cap.FREE))
        )
    )

    val META_LLAMA = Provider(
        id = "meta_llama",
        name = "Meta Llama API",
        iconUrl = "https://logo.clearbit.com/meta.com",
        supportsLiveFetch = true,
        signupUrl = "https://llama.developer.meta.com",
        rateLimit = "Limited free preview",
        malaysiaStatus = MalaysiaStatus.YELLOW,
        malaysiaNote = "US priority — may work in Malaysia",
        baseUrl = "https://api.llama.com/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Llama 4 Scout",    "meta-llama/llama-4-scout",              setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX)),
            AiModel("Llama 4 Maverick", "meta-llama/llama-4-maverick",           setOf(Cap.TOOLS, Cap.VISION)),
            AiModel("Llama 3.3 70B",    "meta-llama/llama-3.3-70b-instruct",     setOf(Cap.TOOLS))
        )
    )

    val DEEPSEEK = Provider(
        id = "deepseek",
        name = "DeepSeek",
        iconUrl = "https://logo.clearbit.com/deepseek.com",
        supportsLiveFetch = true,
        signupUrl = "https://platform.deepseek.com/api_keys",
        rateLimit = "Very cheap · Free tier available",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere",
        baseUrl = "https://api.deepseek.com/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("DeepSeek Chat (V3)",     "deepseek-chat",     setOf(Cap.TOOLS)),
            AiModel("DeepSeek Reasoner (R1)", "deepseek-reasoner", setOf(Cap.REASONING))
        )
    )

    val KIMI = Provider(
        id = "kimi",
        name = "Kimi (Moonshot AI)",
        iconUrl = "https://logo.clearbit.com/moonshot.ai",
        supportsLiveFetch = true,
        signupUrl = "https://platform.moonshot.ai",
        rateLimit = "Paid credits required",
        malaysiaStatus = MalaysiaStatus.YELLOW,
        malaysiaNote = "Direct API requires paid credits — use Kimi K2.5 via OpenRouter (free) instead",
        warningNote = "Direct Kimi API suspends account when balance is 0. Use OpenRouter → Kimi K2.5 for free access.",
        baseUrl = "https://api.moonshot.ai/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Kimi K2",         "kimi-k2",          setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX)),
            AiModel("Moonshot v1 8k",  "moonshot-v1-8k",   setOf(Cap.TOOLS)),
            AiModel("Moonshot v1 32k", "moonshot-v1-32k",  setOf(Cap.TOOLS))
        )
    )

    val OLLAMA = Provider(
        id = "ollama",
        name = "Ollama / Private Server",
        iconUrl = "https://logo.clearbit.com/ollama.com",
        isUrlConfigurable = true,
        supportsLiveFetch = true,
        signupUrl = "https://ollama.com/library",
        rateLimit = "Unlimited · 100% free",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "No internet needed",
        warningNote = "Enter any OpenAI-compatible server URL — local PC, Oracle Cloud, or any private server",
        baseUrl = "http://localhost:11434",
        requiresProxy = true,
        requiresApiKey = false,
        models = listOf(
            AiModel("Llama 3.1 8B",       "llama3.1:8b",        setOf(Cap.FAST, Cap.FREE)),
            AiModel("Llama 3.1 70B",      "llama3.1:70b",       setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("Qwen 2.5 Coder 7B",  "qwen2.5-coder:7b",   setOf(Cap.CODING, Cap.FAST, Cap.FREE)),
            AiModel("Mistral 7B",         "mistral:7b",         setOf(Cap.FAST, Cap.FREE))
        )
    )

    val GROQ = Provider(
        id = "groq",
        name = "Groq",
        iconUrl = "https://logo.clearbit.com/groq.com",
        supportsLiveFetch = true,
        signupUrl = "https://console.groq.com/keys",
        rateLimit = "Free · 14,400 req/day · Ultra-fast",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere — no CC required",
        baseUrl = "https://api.groq.com/openai/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Llama 4 Scout 17B",      "meta-llama/llama-4-scout-17b-16e-instruct", setOf(Cap.TOOLS, Cap.VISION, Cap.FAST, Cap.FREE)),
            AiModel("Llama 4 Maverick 17B",   "meta-llama/llama-4-maverick-17b-128e-instruct", setOf(Cap.TOOLS, Cap.VISION, Cap.FREE)),
            AiModel("Llama 3.3 70B",          "llama-3.3-70b-versatile",                  setOf(Cap.TOOLS, Cap.FREE)),
            AiModel("Llama 3.1 8B (Instant)", "llama-3.1-8b-instant",                     setOf(Cap.FAST, Cap.FREE)),
            AiModel("DeepSeek R1 Distill 70B","deepseek-r1-distill-llama-70b",             setOf(Cap.REASONING, Cap.FREE)),
            AiModel("Qwen QwQ 32B",           "qwen-qwq-32b",                             setOf(Cap.REASONING, Cap.FREE)),
            AiModel("Gemma 2 9B",             "gemma2-9b-it",                             setOf(Cap.FAST, Cap.FREE)),
            AiModel("Mixtral 8x7B",           "mixtral-8x7b-32768",                       setOf(Cap.TOOLS, Cap.LONG_CTX, Cap.FREE))
        )
    )

    val ANTHROPIC = Provider(
        id = "anthropic",
        name = "Anthropic (Claude.ai)",
        iconUrl = "https://logo.clearbit.com/anthropic.com",
        supportsLiveFetch = true,
        signupUrl = "https://console.anthropic.com/settings/api-keys",
        rateLimit = "Subscription billing",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere",
        baseUrl = "",
        requiresProxy = false,
        models = listOf(
            AiModel("Claude Sonnet 4.5", "claude-sonnet-4-5",          setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX)),
            AiModel("Claude Opus 4.5",   "claude-opus-4-5",            setOf(Cap.TOOLS, Cap.VISION, Cap.REASONING, Cap.LONG_CTX)),
            AiModel("Claude Haiku 4.5",  "claude-haiku-4-5-20251001",  setOf(Cap.TOOLS, Cap.VISION, Cap.FAST))
        )
    )

    val ALL = listOf(GROQ, GEMINI, OPENROUTER, DEEPSEEK, KIMI, NVIDIA_NIM, META_LLAMA, OLLAMA)

    fun byId(id: String): Provider? = ALL.find { it.id == id }

    /** Infer capability flags from a model ID — used for live-fetched OpenRouter models. */
    fun deriveCaps(modelId: String): Set<String> {
        val lo = modelId.lowercase()
        val caps = mutableSetOf<String>()
        if (":free" in lo || "kimi-k2" in lo) caps += Cap.FREE
        if ("vl" in lo || "vision" in lo || "omni" in lo || "scout" in lo || "maverick" in lo ||
            "gemini" in lo || "claude" in lo) caps += Cap.VISION
        if ("r1" in lo || "reason" in lo || "think" in lo || "qwq" in lo || "o1" in lo ||
            "o3" in lo || "gemini-2.5" in lo || "gemini-3" in lo) caps += Cap.REASONING
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
}
