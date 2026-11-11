package com.inyogeshwar.agentmt.agent

import com.inyogeshwar.agentmt.agent.llm.AnthropicClient
import com.inyogeshwar.agentmt.agent.llm.LlmClient
import com.inyogeshwar.agentmt.agent.llm.OpenAiClient
import com.inyogeshwar.agentmt.data.db.ProviderEntity

data class ProviderPreset(
    val id: String, val name: String, val kind: String, val baseUrl: String,
    val suggestedModels: List<String>, val keyUrl: String?, val needsKey: Boolean = true, val note: String = "",
    /** Hand-picked presets show in onboarding and at the top of the list; the rest come from the bundled catalog. */
    val featured: Boolean = true,
)

object ProviderPresets {
    private val featuredList = listOf(
        ProviderPreset("openai", "OpenAI", "openai", "https://api.openai.com/v1",
            listOf("gpt-4.1", "gpt-4.1-mini", "o4-mini"), "https://platform.openai.com/api-keys"),
        ProviderPreset("anthropic", "Anthropic", "anthropic", "https://api.anthropic.com/v1",
            listOf("claude-sonnet-4-5", "claude-haiku-4-5", "claude-opus-4-1"), "https://console.anthropic.com/settings/keys"),
        ProviderPreset("openrouter", "OpenRouter", "openai", "https://openrouter.ai/api/v1",
            listOf("anthropic/claude-sonnet-4.5", "openai/gpt-4.1", "google/gemini-2.5-pro", "deepseek/deepseek-chat"),
            "https://openrouter.ai/keys", note = "One key, hundreds of models. Some are free."),
        ProviderPreset("gemini", "Google Gemini", "openai", "https://generativelanguage.googleapis.com/v1beta/openai",
            listOf("gemini-2.5-flash", "gemini-2.5-pro"), "https://aistudio.google.com/apikey", note = "Free tier available."),
        ProviderPreset("groq", "Groq", "openai", "https://api.groq.com/openai/v1",
            listOf("llama-3.3-70b-versatile", "moonshotai/kimi-k2-instruct"), "https://console.groq.com/keys"),
        ProviderPreset("deepseek", "DeepSeek", "openai", "https://api.deepseek.com/v1",
            listOf("deepseek-chat", "deepseek-reasoner"), "https://platform.deepseek.com/api_keys"),
        ProviderPreset("ollama", "Ollama (local)", "openai", "http://localhost:11434/v1",
            listOf("qwen2.5-coder:7b"), null, needsKey = false, note = "Runs on your own machine or on your network."),
        ProviderPreset("custom", "Custom (OpenAI-compatible)", "openai", "https://", emptyList(), null, needsKey = false),
    )
    val featured: List<ProviderPreset> get() = featuredList

    /** Featured presets first, then every catalog provider not already covered (same id or same base URL). */
    val all: List<ProviderPreset> by lazy {
        val cat = ProviderCatalog.providers
        val ids = featuredList.map { it.id }.toSet() + setOf("google")
        val urls = featuredList.map { it.baseUrl.trimEnd('/') }.toSet()
        featuredList.dropLast(1) + cat.filter { it.id !in ids && it.api.trimEnd('/') !in urls && it.api.startsWith("http") && it.models.isNotEmpty() }
            .sortedBy { it.name.lowercase() }
            .map { cp ->
                val local = cp.api.contains("127.0.0.1") || cp.api.contains("localhost")
                ProviderPreset(cp.id, cp.name, if (cp.kind == "anthropic") "anthropic" else "openai", cp.api,
                    cp.models.sortedByDescending { it.tools }.map { it.id }.take(40), null, needsKey = !local,
                    note = "${cp.models.size} models" + (if (cp.models.any { it.inPrice == 0.0 && it.outPrice == 0.0 }) " · some free" else "") +
                        (if (cp.env.isNotBlank()) " · ${cp.env}" else ""), featured = false)
            } + featuredList.last()
    }

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

fun ProviderEntity.modelList(): List<String> = models.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

fun clientFor(p: ProviderEntity, key: String?): LlmClient =
    if (p.kind == "anthropic") AnthropicClient(p.baseUrl, key) else OpenAiClient(p.baseUrl, key)
