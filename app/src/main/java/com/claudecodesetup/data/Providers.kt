package com.claudecodesetup.data

data class AiModel(
    val name: String,
    val modelId: String
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
    val models: List<AiModel>
)

enum class MalaysiaStatus { GREEN, YELLOW, RED }

object Providers {

    val NVIDIA_NIM = Provider(
        id = "nvidia_nim",
        name = "NVIDIA NIM",
        signupUrl = "https://build.nvidia.com/models",
        rateLimit = "40 req/min · Free forever",
        malaysiaStatus = MalaysiaStatus.RED,
        malaysiaNote = "SMS verification fails in Malaysia",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("GLM 4.7", "z-ai/glm4.7"),
            AiModel("GLM 5", "z-ai/glm5"),
            AiModel("Kimi K2.5", "moonshotai/kimi-k2.5"),
            AiModel("MiniMax M2.5", "minimaxai/minimax-m2.5"),
            AiModel("Step 3.5 Flash", "stepfun-ai/step-3.5-flash"),
            AiModel("DeepSeek V4 Flash", "deepseek-ai/deepseek-v4-flash"),
            AiModel("Llama 3.3 70B", "meta/llama-3.3-70b-instruct"),
            AiModel("Qwen 3.5 235B", "qwen/qwen3.5-235b-a22b")
        )
    )

    val OPENROUTER = Provider(
        id = "openrouter",
        name = "OpenRouter",
        signupUrl = "https://openrouter.ai",
        rateLimit = "20 req/min · 50 req/day free",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Email signup works",
        warningNote = "50 req/day = ~5 real prompts. Consider Gemini for 1500/day instead.",
        baseUrl = "https://openrouter.ai/api/v1",
        requiresProxy = true,
        models = listOf(
            // Confirmed working free models — always shown even if live fetch fails
            AiModel("Kimi K2.5 ⭐ (Recommended)", "moonshotai/kimi-k2:free"),
            AiModel("GPT-OSS 120B", "openai/gpt-oss-120b:free"),
            AiModel("GPT-OSS 20B", "openai/gpt-oss-20b:free"),
            AiModel("MiniMax M2.5", "minimax/minimax-m2.5:free"),
            AiModel("Nemotron Super 120B", "nvidia/nemotron-3-super-120b-a12b:free"),
            AiModel("Nemotron Nano Omni 30B", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"),
            AiModel("Nemotron Nano 12B VL", "nvidia/nemotron-nano-12b-v2-vl:free"),
            AiModel("Nemotron Nano 9B", "nvidia/nemotron-nano-9b-v2:free"),
            AiModel("Llama 4 Scout", "meta-llama/llama-4-scout:free"),
            AiModel("Llama 4 Maverick", "meta-llama/llama-4-maverick:free"),
            AiModel("Llama 3.3 70B", "meta-llama/llama-3.3-70b-instruct:free"),
            AiModel("DeepSeek R1 (free)", "deepseek/deepseek-r1:free"),
            AiModel("DeepSeek V3 (free)", "deepseek/deepseek-chat-v3-5:free"),
            AiModel("Qwen 3 235B A22B", "qwen/qwen3-235b-a22b:free"),
            AiModel("Qwen 3 30B A3B", "qwen/qwen3-30b-a3b:free"),
            AiModel("Mistral Small 3.2", "mistralai/mistral-small-3.2-24b-instruct:free"),
            AiModel("Gemma 3 27B", "google/gemma-3-27b-it:free"),
            AiModel("Gemma 3 12B", "google/gemma-3-12b-it:free"),
            AiModel("Laguna M.1 (Poolside)", "poolside/laguna-m.1:free"),
            AiModel("Cobuddy (Baidu)", "baidu/cobuddy:free"),
            AiModel("LFM 2.5 1.2B (Liquid)", "liquid/lfm-2.5-1.2b-instruct:free")
        )
    )

    val GEMINI = Provider(
        id = "gemini",
        name = "Google Gemini",
        signupUrl = "https://aistudio.google.com",
        rateLimit = "15 req/min · 1500 req/day free",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Google account only — recommended for Malaysia",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        requiresProxy = true,
        models = listOf(
            AiModel("Gemini 2.5 Flash", "gemini-2.5-flash-preview-05-20"),
            AiModel("Gemini 2.0 Flash", "gemini-2.0-flash"),
            AiModel("Gemini 1.5 Flash", "gemini-1.5-flash"),
            AiModel("Gemini 1.5 Flash 8B", "gemini-1.5-flash-8b")
        )
    )

    val META_LLAMA = Provider(
        id = "meta_llama",
        name = "Meta Llama API",
        signupUrl = "https://llama.developer.meta.com",
        rateLimit = "Limited free preview",
        malaysiaStatus = MalaysiaStatus.YELLOW,
        malaysiaNote = "US priority — may work in Malaysia",
        baseUrl = "https://api.llama.com/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Llama 4 Scout", "meta-llama/llama-4-scout"),
            AiModel("Llama 4 Maverick", "meta-llama/llama-4-maverick"),
            AiModel("Llama 3.3 70B", "meta-llama/llama-3.3-70b-instruct")
        )
    )

    val DEEPSEEK = Provider(
        id = "deepseek",
        name = "DeepSeek",
        signupUrl = "https://platform.deepseek.com/api_keys",
        rateLimit = "Very cheap · Free tier available",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere",
        baseUrl = "https://api.deepseek.com/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("DeepSeek Chat (V3)", "deepseek-chat"),
            AiModel("DeepSeek Reasoner (R1)", "deepseek-reasoner")
        )
    )

    val KIMI = Provider(
        id = "kimi",
        name = "Kimi (Moonshot AI)",
        signupUrl = "https://platform.moonshot.ai",
        rateLimit = "Paid credits required",
        malaysiaStatus = MalaysiaStatus.YELLOW,
        malaysiaNote = "Direct API requires paid credits — use Kimi K2.5 via OpenRouter (free) instead",
        warningNote = "Direct Kimi API suspends account when balance is 0. Use OpenRouter → Kimi K2.5 for free access.",
        baseUrl = "https://api.moonshot.ai/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Kimi K2", "kimi-k2"),
            AiModel("Moonshot v1 8k", "moonshot-v1-8k"),
            AiModel("Moonshot v1 32k", "moonshot-v1-32k")
        )
    )

    val OLLAMA = Provider(
        id = "ollama",
        name = "Ollama (Local PC only)",
        signupUrl = "https://ollama.com/library",
        rateLimit = "Unlimited · 100% free",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "No internet needed",
        warningNote = "Requires PC with 8GB+ RAM running Ollama — not your phone",
        baseUrl = "http://localhost:11434",
        requiresProxy = true,
        requiresApiKey = false,
        models = listOf(
            AiModel("Llama 3.1 8B", "llama3.1:8b"),
            AiModel("Llama 3.1 70B", "llama3.1:70b"),
            AiModel("Qwen 2.5 Coder 7B", "qwen2.5-coder:7b"),
            AiModel("Mistral 7B", "mistral:7b")
        )
    )

    val ANTHROPIC = Provider(
        id = "anthropic",
        name = "Anthropic (Claude.ai)",
        signupUrl = "https://console.anthropic.com/settings/api-keys",
        rateLimit = "Subscription billing",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere",
        baseUrl = "",
        requiresProxy = false,
        models = listOf(
            AiModel("Claude Sonnet 4.5", "claude-sonnet-4-5"),
            AiModel("Claude Opus 4.5", "claude-opus-4-5"),
            AiModel("Claude Haiku 4.5", "claude-haiku-4-5-20251001")
        )
    )

    val ALL = listOf(GEMINI, OPENROUTER, DEEPSEEK, KIMI, NVIDIA_NIM, META_LLAMA, OLLAMA)

    fun byId(id: String): Provider? = ALL.find { it.id == id }
}
