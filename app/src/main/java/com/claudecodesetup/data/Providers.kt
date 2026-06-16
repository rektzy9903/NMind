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
        iconResId = R.drawable.ic_brand_kimi,
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
            AiModel("Qwen Max",         "qwen-max",         setOf(Cap.TOOLS, Cap.LONG_CTX, Cap.FREE),             "Most capable · text"),
            AiModel("Qwen Plus",        "qwen-plus",        setOf(Cap.TOOLS, Cap.LONG_CTX, Cap.FREE),             "Balanced"),
            AiModel("Qwen Turbo",       "qwen-turbo",       setOf(Cap.TOOLS, Cap.FAST, Cap.LONG_CTX, Cap.FREE),   "Fast · cheap"),
            AiModel("Qwen3 Coder Plus", "qwen3-coder-plus", setOf(Cap.TOOLS, Cap.CODING, Cap.LONG_CTX, Cap.FREE), "Code specialist"),
            AiModel("Qwen VL Max",      "qwen-vl-max",      setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX, Cap.FREE), "Vision · most capable"),
            AiModel("QwQ Plus",         "qwq-plus",         setOf(Cap.REASONING, Cap.FREE),                       "Deep reasoning · text")
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
        iconResId = R.drawable.ic_brand_groq,
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

    val CEREBRAS = Provider(
        id = "cerebras",
        name = "Cerebras",
        iconResId = R.drawable.ic_brand_cerebras,
        supportsLiveFetch = true,
        signupUrl = "https://cloud.cerebras.ai",
        rateLimit = "Free · 60K tok/min · 14,400 req/day · fastest",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere — no credit card",
        baseUrl = "https://api.cerebras.ai/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("GPT-OSS 120B",        "gpt-oss-120b",                   setOf(Cap.TOOLS, Cap.REASONING, Cap.FREE),    "Open frontier · ~3000 tok/s"),
            AiModel("Qwen3 235B Instruct", "qwen-3-235b-a22b-instruct-2507", setOf(Cap.TOOLS, Cap.LONG_CTX, Cap.FREE),     "Large · instant speed"),
            AiModel("GLM 4.7",             "zai-glm-4.7",                    setOf(Cap.TOOLS, Cap.REASONING, Cap.FREE),    "Reasoning · thinking tokens"),
            AiModel("Llama 3.1 8B",        "llama3.1-8b",                    setOf(Cap.TOOLS, Cap.FAST, Cap.FREE),         "Compact · fastest")
        )
    )

    // GitHub Models (github.com/marketplace/models) — free, no credit card. Auth is a
    // GitHub Personal Access Token (fine-grained PAT with the "Models" permission, or a
    // classic token). OpenAI-compatible inference endpoint; the model list comes from the
    // PUBLIC catalog endpoint (bare array, no auth) so live fetch works even before a key
    // is entered. Currently the only free route to GPT-5 / GPT-4.1 / o-series + Grok/DeepSeek.
    val GITHUB = Provider(
        id = "github",
        name = "GitHub Models",
        supportsLiveFetch = true,
        signupUrl = "https://github.com/settings/personal-access-tokens",
        rateLimit = "Free · GitHub PAT (Models permission) · rate-limited",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere — GitHub account + token",
        baseUrl = "https://models.github.ai/inference",
        requiresProxy = true,
        models = listOf(
            AiModel("GPT-4.1",          "openai/gpt-4.1",                setOf(Cap.TOOLS, Cap.VISION, Cap.LONG_CTX, Cap.FREE), "OpenAI · most capable"),
            AiModel("GPT-4.1 mini",     "openai/gpt-4.1-mini",          setOf(Cap.TOOLS, Cap.FAST, Cap.FREE),                 "OpenAI · fast"),
            AiModel("GPT-5 mini",       "openai/gpt-5-mini",            setOf(Cap.TOOLS, Cap.REASONING, Cap.FREE),            "OpenAI · reasoning"),
            AiModel("o4-mini",          "openai/o4-mini",               setOf(Cap.REASONING, Cap.FAST, Cap.FREE),             "OpenAI · deep reasoning"),
            AiModel("DeepSeek R1",      "deepseek/deepseek-r1",         setOf(Cap.REASONING, Cap.FREE),                       "Chain-of-thought"),
            AiModel("Llama 3.3 70B",    "meta/llama-3.3-70b-instruct",  setOf(Cap.TOOLS, Cap.FREE),                           "Reliable · text"),
            AiModel("Mistral Small 3.1","mistral-ai/mistral-small-2503",setOf(Cap.TOOLS, Cap.FREE),                           "Compact · tools"),
            AiModel("Phi-4",            "microsoft/phi-4",              setOf(Cap.FREE),                                      "Microsoft · small")
        )
    )

    // SambaNova Cloud (cloud.sambanova.ai) — free, no credit card, persistent free tier
    // (not just trial credits). Very fast RDU inference. OpenAI-compatible API key.
    val SAMBANOVA = Provider(
        id = "sambanova",
        name = "SambaNova",
        supportsLiveFetch = true,
        signupUrl = "https://cloud.sambanova.ai/apis",
        rateLimit = "Free tier · no credit card · ultra-fast",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere — no credit card",
        baseUrl = "https://api.sambanova.ai/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("DeepSeek V3.1",      "DeepSeek-V3.1",              setOf(Cap.TOOLS, Cap.LONG_CTX, Cap.FREE),     "Large MoE · fast"),
            AiModel("Llama 3.3 70B",      "Meta-Llama-3.3-70B-Instruct",setOf(Cap.TOOLS, Cap.FREE),                  "Reliable · text"),
            AiModel("GPT-OSS 120B",       "gpt-oss-120b",              setOf(Cap.TOOLS, Cap.REASONING, Cap.FREE),    "Open frontier"),
            AiModel("MiniMax M2.7",       "MiniMax-M2.7",              setOf(Cap.TOOLS, Cap.FREE),                   "MiniMax · agentic"),
            AiModel("Gemma 4 31B",        "gemma-4-31B-it",            setOf(Cap.TOOLS, Cap.FREE),                   "Google · compact")
        )
    )

    // Z.ai / Zhipu GLM (z.ai) — OpenAI-compatible at /api/paas/v4. Free tier covers the
    // GLM *Flash* models (glm-4.5-flash, glm-4-flash); larger GLM models are paid. Key
    // from z.ai/manage-apikey/apikey-list. GLM is strong at coding + tool use.
    val ZAI = Provider(
        id = "zai",
        name = "Z.ai (GLM)",
        supportsLiveFetch = true,
        signupUrl = "https://z.ai/manage-apikey/apikey-list",
        rateLimit = "Free Flash tier · larger GLM paid",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "International endpoint — works everywhere",
        baseUrl = "https://api.z.ai/api/paas/v4",
        requiresProxy = true,
        models = listOf(
            AiModel("GLM-4.5 Flash", "glm-4.5-flash", setOf(Cap.TOOLS, Cap.FAST, Cap.FREE),            "Free · fast"),
            AiModel("GLM-4 Flash",   "glm-4-flash",   setOf(Cap.TOOLS, Cap.FAST, Cap.FREE),            "Free · compact"),
            AiModel("GLM-4.5 Air",   "glm-4.5-air",   setOf(Cap.TOOLS, Cap.REASONING),                 "Balanced · cheap"),
            AiModel("GLM-4.6",       "glm-4.6",       setOf(Cap.TOOLS, Cap.REASONING, Cap.LONG_CTX),   "Most capable · coding")
        )
    )

    // Cohere (cohere.com) — OpenAI-compatible at /compatibility/v1. Trial API keys are
    // free (rate-limited, no expiry); production keys are paid. Key from
    // dashboard.cohere.com/api-keys. Command family (strong at RAG / tool use).
    val COHERE = Provider(
        id = "cohere",
        name = "Cohere",
        supportsLiveFetch = true,
        signupUrl = "https://dashboard.cohere.com/api-keys",
        rateLimit = "Free trial keys · rate-limited",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere — trial key, no card",
        baseUrl = "https://api.cohere.ai/compatibility/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Command A",    "command-a-03-2025",     setOf(Cap.TOOLS, Cap.LONG_CTX, Cap.FREE), "Most capable"),
            AiModel("Command R+",   "command-r-plus-08-2024",setOf(Cap.TOOLS, Cap.FREE),               "Strong · RAG"),
            AiModel("Command R",    "command-r-08-2024",     setOf(Cap.TOOLS, Cap.FAST, Cap.FREE),     "Balanced"),
            AiModel("Command R7B",  "command-r7b-12-2024",   setOf(Cap.TOOLS, Cap.FAST, Cap.FREE),     "Compact · fast")
        )
    )

    // Hugging Face Inference Router (router.huggingface.co/v1) — OpenAI-compatible
    // gateway over many open models. Free monthly inference credits with a `hf_…` token.
    // Key from huggingface.co/settings/tokens. Live list is large (100+ models).
    val HUGGINGFACE = Provider(
        id = "huggingface",
        name = "Hugging Face",
        iconResId = R.drawable.ic_brand_huggingface,
        supportsLiveFetch = true,
        signupUrl = "https://huggingface.co/settings/tokens",
        rateLimit = "Free monthly credits · hf_ token",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere — HF account token",
        baseUrl = "https://router.huggingface.co/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("DeepSeek R1",     "deepseek-ai/DeepSeek-R1",          setOf(Cap.REASONING, Cap.FREE),  "Chain-of-thought"),
            AiModel("Llama 3.3 70B",   "meta-llama/Llama-3.3-70B-Instruct",setOf(Cap.TOOLS, Cap.FREE),      "Reliable · text"),
            AiModel("GPT-OSS 120B",    "openai/gpt-oss-120b",              setOf(Cap.TOOLS, Cap.REASONING, Cap.FREE), "Open frontier"),
            AiModel("Qwen2.5 Coder 32B","Qwen/Qwen2.5-Coder-32B-Instruct", setOf(Cap.TOOLS, Cap.CODING, Cap.FREE), "Code specialist"),
            AiModel("Llama 3.1 8B",    "meta-llama/Llama-3.1-8B-Instruct", setOf(Cap.FAST, Cap.FREE),       "Compact · fast")
        )
    )

    // Chutes AI (llm.chutes.ai/v1) — OpenAI-compatible decentralized inference. Free with
    // a daily quota; key from chutes.ai. Live fetch is authoritative — the static list is
    // a best-effort fallback (model availability shifts on the network).
    val CHUTES = Provider(
        id = "chutes",
        name = "Chutes AI",
        supportsLiveFetch = true,
        signupUrl = "https://chutes.ai/",
        rateLimit = "Free · daily quota · decentralized",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere — community network",
        baseUrl = "https://llm.chutes.ai/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("DeepSeek R1",   "deepseek-ai/DeepSeek-R1",      setOf(Cap.REASONING, Cap.FREE), "Chain-of-thought"),
            AiModel("DeepSeek V3",   "deepseek-ai/DeepSeek-V3-0324", setOf(Cap.TOOLS, Cap.FREE),     "Large MoE"),
            AiModel("Qwen3 235B",    "Qwen/Qwen3-235B-A22B",         setOf(Cap.TOOLS, Cap.LONG_CTX, Cap.FREE), "Large · reasoning")
        )
    )

    // Scaleway Generative APIs (api.scaleway.ai/v1) — OpenAI-compatible, EU (Paris). Free
    // beta tier; key from console.scaleway.com. Live fetch authoritative (beta catalog).
    val SCALEWAY = Provider(
        id = "scaleway",
        name = "Scaleway",
        supportsLiveFetch = true,
        signupUrl = "https://console.scaleway.com/",
        rateLimit = "Free beta tier · EU",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "EU endpoint (Paris) — works in Malaysia",
        baseUrl = "https://api.scaleway.ai/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("Llama 3.3 70B",     "llama-3.3-70b-instruct",          setOf(Cap.TOOLS, Cap.FREE),  "Reliable · text"),
            AiModel("Qwen2.5 Coder 32B", "qwen2.5-coder-32b-instruct",      setOf(Cap.TOOLS, Cap.CODING, Cap.FREE), "Code specialist"),
            AiModel("DeepSeek R1 Distill","deepseek-r1-distill-llama-70b",   setOf(Cap.REASONING, Cap.FREE), "Chain-of-thought"),
            AiModel("Gemma 3 27B",       "gemma-3-27b-it",                  setOf(Cap.TOOLS, Cap.FREE),  "Google · compact")
        )
    )

    // OpenCode Zen (opencode.ai/zen) — OpenAI-compatible gateway. Has a genuinely
    // FREE tier (no payment method needed) alongside paid Claude/GPT/Gemini. We only
    // ever surface the free models: the live /models endpoint carries no price flag,
    // so fetchOpenCodeFreeModels() filters to ids ending in "-free" + "big-pickle"
    // (the rest require a card and 401 with CreditsError). Same upstream Dahono Labs
    // uses for its free-Claude lane. Key from https://opencode.ai/auth (Bearer sk-...).
    val OPENCODE = Provider(
        id = "opencode",
        name = "OpenCode Zen",
        supportsLiveFetch = true,
        signupUrl = "https://opencode.ai/auth",
        rateLimit = "Free models · no card needed",
        malaysiaStatus = MalaysiaStatus.GREEN,
        malaysiaNote = "Works everywhere — OpenAI-compatible",
        warningNote = "Only OpenCode Zen's free models are shown. Its premium Claude/GPT/Gemini models need a payment method on file and are hidden here.",
        baseUrl = "https://opencode.ai/zen/v1",
        requiresProxy = true,
        models = listOf(
            AiModel("DeepSeek V4 Flash (free)", "deepseek-v4-flash-free", setOf(Cap.FREE, Cap.TOOLS, Cap.REASONING, Cap.LONG_CTX), "Free · DeepSeek MoE"),
            AiModel("Big Pickle (free)",        "big-pickle",             setOf(Cap.FREE, Cap.TOOLS),                               "Free experimental"),
            AiModel("MiMo v2.5 (free)",         "mimo-v2.5-free",         setOf(Cap.FREE, Cap.TOOLS),                               "Xiaomi · free"),
            AiModel("Nemotron 3 Ultra (free)",  "nemotron-3-ultra-free",  setOf(Cap.FREE, Cap.REASONING),                           "NVIDIA · free"),
            AiModel("North Mini Code (free)",   "north-mini-code-free",   setOf(Cap.FREE, Cap.CODING),                              "Code · free")
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

    // Kiro AI (AWS CodeWhisperer) — FREE Claude via OAuth, NOT an API key.
    // Paste-token MVP: the "key" field takes the Kiro credentials JSON
    // (accessToken + refreshToken). Routed by bridge.js's KIRO engine on the
    // codewhisperer baseUrl (binary EventStream → Anthropic SSE). supportsLiveFetch
    // = false → uses the static model list below; "auto" lets Kiro pick the model.
    val KIRO = Provider(
        id = "kiro",
        name = "Kiro AI (free Claude)",
        supportsLiveFetch = false,
        signupUrl = "https://kiro.dev",
        rateLimit = "Free · Claude via Kiro · paste creds JSON",
        malaysiaStatus = MalaysiaStatus.YELLOW,
        malaysiaNote = "Experimental · reverse-engineered AWS CodeWhisperer",
        warningNote = "Paste your Kiro credentials JSON (accessToken + refreshToken) from a desktop Kiro login. Experimental.",
        baseUrl = "https://codewhisperer.us-east-1.amazonaws.com/generateAssistantResponse",
        requiresProxy = true,
        models = listOf(
            AiModel("Auto (Kiro picks)", "auto",            setOf(Cap.FREE, Cap.TOOLS, Cap.LONG_CTX), "Kiro selects the model server-side"),
            AiModel("Claude Sonnet 4.5", "claude-sonnet-4-5", setOf(Cap.FREE, Cap.TOOLS, Cap.REASONING, Cap.LONG_CTX), "If 'auto' 400s, try this")
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

    val ALL = listOf(GROQ, GEMINI, CEREBRAS, GITHUB, SAMBANOVA, ZAI, COHERE, HUGGINGFACE, CHUTES, SCALEWAY, OPENCODE, OPENROUTER, ANTHROPIC_API, DEEPSEEK, KIMI, QWEN, MISTRAL, NVIDIA_NIM, META_LLAMA, KIRO, OLLAMA)

    fun byId(id: String): Provider? = when (id) {
        "anthropic"     -> ANTHROPIC
        "anthropic_api" -> ANTHROPIC_API
        "kiro"          -> KIRO
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
