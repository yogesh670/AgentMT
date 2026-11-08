package com.inyogeshwar.agentmt.agent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Chat Completions API. Works with OpenAI and compatible endpoints (OpenRouter, Groq, DeepSeek, Ollama, LM Studio…). */
class OpenAiClient(private val baseUrl: String, private val apiKey: String?) : LlmClient {

    private fun headers() = buildMap {
        if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer $apiKey")
        put("HTTP-Referer", "https://github.com/inyogeshwar/agentmt")
        put("X-Title", "AgentMT")
    }

    override fun stream(req: LlmRequest): Flow<LlmEvent> = flow {
        val body = JSONObject().apply {
            put("model", req.model)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            put("messages", toMessages(req))
            req.reasoning?.takeIf { it in setOf("low", "medium", "high") }?.let { put("reasoning_effort", it) }
            if (req.tools.isNotEmpty()) put("tools", JSONArray().apply {
                req.tools.forEach { t ->
                    put(JSONObject().put("type", "function").put("function",
                        JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.schema)))
                }
            })
        }
        val call = Http.client.newCall(Http.post("${baseUrl.trimEnd('/')}/chat/completions", body.toString(), headers()))
        val resp = call.await()
        resp.use { r ->
            if (!r.isSuccessful) throw httpError(r)
            val ids = HashMap<Int, String>(); val names = HashMap<Int, String>(); val args = HashMap<Int, StringBuilder>()
            var finish: String? = null
            r.forEachSse { _, data ->
                if (data == "[DONE]") return@forEachSse
                val o = runCatching { JSONObject(data) }.getOrNull() ?: return@forEachSse
                o.optJSONObject("error")?.let { throw LlmException(500, it.optString("message", data)) }
                o.optJSONObject("usage")?.let { u ->
                    emit(LlmEvent.Usage(u.optLong("prompt_tokens"), u.optLong("completion_tokens")))
                }
                val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: return@forEachSse
                val d = choice.optJSONObject("delta") ?: JSONObject()
                d.optStringOrNull("reasoning_content")?.let { emit(LlmEvent.Reasoning(it)) }
                d.optStringOrNull("reasoning")?.let { emit(LlmEvent.Reasoning(it)) }
                d.optStringOrNull("content")?.let { if (it.isNotEmpty()) emit(LlmEvent.Text(it)) }
                d.optJSONArray("tool_calls")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val tc = arr.getJSONObject(i)
                        val idx = tc.optInt("index", i)
                        tc.optStringOrNull("id")?.let { ids[idx] = it }
                        tc.optJSONObject("function")?.let { f ->
                            f.optStringOrNull("name")?.let { names[idx] = (names[idx] ?: "") + it }
                            f.optStringOrNull("arguments")?.let { args.getOrPut(idx) { StringBuilder() }.append(it) }
                        }
                    }
                }
                choice.optStringOrNull("finish_reason")?.let { finish = it }
                currentCoroutineContext().ensureActive()
            }
            if (names.isNotEmpty()) {
                emit(LlmEvent.ToolCalls(names.keys.sorted().map { i ->
                    ToolCall(ids[i] ?: "call_$i", names[i]!!, args[i]?.toString()?.ifBlank { "{}" } ?: "{}")
                }))
            }
            emit(LlmEvent.Finish(finish))
        }
    }.flowOn(Dispatchers.IO)

    private fun toMessages(req: LlmRequest) = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", req.system))
        req.messages.forEach { m ->
            when (m.role) {
                "user" -> put(JSONObject().put("role", "user").put("content", if (m.parts.isEmpty()) m.text else JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", m.text.ifEmpty { " " }))
                    m.parts.forEach { pt ->
                        when (pt) {
                            is Part.Image -> put(JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:${pt.mime};base64,${pt.base64}")))
                            is Part.Pdf -> put(JSONObject().put("type", "file").put("file",
                                JSONObject().put("filename", pt.name).put("file_data", "data:application/pdf;base64,${pt.base64}")))
                        }
                    }
                }))
                "assistant" -> put(JSONObject().put("role", "assistant").apply {
                    put("content", if (m.text.isEmpty() && m.toolCalls.isNotEmpty()) JSONObject.NULL else m.text)
                    if (m.toolCalls.isNotEmpty()) put("tool_calls", JSONArray().apply {
                        m.toolCalls.forEach { c ->
                            put(JSONObject().put("id", c.id).put("type", "function")
                                .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments)))
                        }
                    })
                })
                "tool" -> put(JSONObject().put("role", "tool").put("tool_call_id", m.toolCallId).put("content", m.text))
            }
        }
    }

    override suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        Http.client.newCall(Http.get("${baseUrl.trimEnd('/')}/models", headers())).await().use { r ->
            if (!r.isSuccessful) throw httpError(r)
            parseOpenAiModels(r.body?.string().orEmpty())
        }
    }
}

internal fun JSONObject.optStringOrNull(k: String): String? =
    if (has(k) && !isNull(k)) optString(k) else null

/** Parses /models from OpenAI-style APIs. OpenRouter adds architecture + supported_parameters, which we read when present. */
fun parseOpenAiModels(body: String): List<ModelInfo> {
    val root = runCatching { JSONObject(body) }.getOrNull()
    val arr = root?.optJSONArray("data") ?: root?.optJSONArray("models") ?: runCatching { JSONArray(body) }.getOrNull() ?: JSONArray()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").ifBlank { o.optString("name") }.removePrefix("models/")
        if (id.isBlank()) return@mapNotNull null
        val mods = o.optJSONObject("architecture")?.optJSONArray("input_modalities")
        val params = o.optJSONArray("supported_parameters")
        ModelInfo(
            id,
            vision = mods?.let { m -> (0 until m.length()).any { m.optString(it) == "image" } },
            tools = params?.let { p -> (0 until p.length()).any { p.optString(it) == "tools" } },
            contextLength = o.optInt("context_length", 0).takeIf { it > 0 },
        )
    }.sortedBy { it.id }
}
