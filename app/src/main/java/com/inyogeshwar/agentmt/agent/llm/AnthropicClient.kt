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

/** Anthropic Messages API with streaming and tool use. */
class AnthropicClient(private val baseUrl: String, private val apiKey: String?) : LlmClient {

    private fun headers() = mapOf(
        "x-api-key" to (apiKey ?: ""),
        "anthropic-version" to "2023-06-01",
    )

    override fun stream(req: LlmRequest): Flow<LlmEvent> = flow {
        val body = JSONObject().apply {
            put("model", req.model)
            put("max_tokens", req.maxTokens)
            put("stream", true)
            put("system", req.system)
            put("messages", toMessages(req.messages))
            if (req.tools.isNotEmpty()) put("tools", JSONArray().apply {
                req.tools.forEach { put(JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.schema)) }
            })
        }
        val resp = Http.client.newCall(Http.post("${baseUrl.trimEnd('/')}/messages", body.toString(), headers())).await()
        resp.use { r ->
            if (!r.isSuccessful) throw httpError(r)
            val blocks = HashMap<Int, Triple<String, String, StringBuilder>>() // index -> (id, name, json)
            var inTok = 0L; var outTok = 0L; var stop: String? = null
            r.forEachSse { _, data ->
                val o = runCatching { JSONObject(data) }.getOrNull() ?: return@forEachSse
                when (o.optString("type")) {
                    "message_start" -> o.optJSONObject("message")?.optJSONObject("usage")?.let { inTok = it.optLong("input_tokens") }
                    "content_block_start" -> {
                        val cb = o.getJSONObject("content_block")
                        if (cb.optString("type") == "tool_use")
                            blocks[o.optInt("index")] = Triple(cb.optString("id"), cb.optString("name"), StringBuilder())
                    }
                    "content_block_delta" -> {
                        val d = o.getJSONObject("delta")
                        when (d.optString("type")) {
                            "text_delta" -> emit(LlmEvent.Text(d.optString("text")))
                            "thinking_delta" -> emit(LlmEvent.Reasoning(d.optString("thinking")))
                            "input_json_delta" -> blocks[o.optInt("index")]?.third?.append(d.optString("partial_json"))
                        }
                    }
                    "message_delta" -> {
                        o.optJSONObject("usage")?.let { outTok = it.optLong("output_tokens") }
                        o.optJSONObject("delta")?.optStringOrNull("stop_reason")?.let { stop = it }
                    }
                    "error" -> throw LlmException(500, o.optJSONObject("error")?.optString("message") ?: data)
                }
                currentCoroutineContext().ensureActive()
            }
            emit(LlmEvent.Usage(inTok, outTok))
            if (blocks.isNotEmpty()) emit(LlmEvent.ToolCalls(blocks.keys.sorted().map { i ->
                val (id, name, json) = blocks[i]!!
                ToolCall(id, name, json.toString().ifBlank { "{}" })
            }))
            emit(LlmEvent.Finish(stop))
        }
    }.flowOn(Dispatchers.IO)

    private fun toMessages(msgs: List<ChatMsg>): JSONArray {
        val out = JSONArray()
        var pendingResults: JSONArray? = null
        fun flush() { pendingResults?.let { out.put(JSONObject().put("role", "user").put("content", it)) }; pendingResults = null }
        for (m in msgs) {
            when (m.role) {
                "tool" -> {
                    val arr = pendingResults ?: JSONArray().also { pendingResults = it }
                    arr.put(JSONObject().put("type", "tool_result").put("tool_use_id", m.toolCallId)
                        .put("content", m.text.ifEmpty { "(empty)" }).put("is_error", m.isError))
                }
                "user" -> {
                    flush()
                    if (m.parts.isEmpty()) out.put(JSONObject().put("role", "user").put("content", m.text.ifEmpty { " " }))
                    else out.put(JSONObject().put("role", "user").put("content", JSONArray().apply {
                        m.parts.forEach { pt ->
                            when (pt) {
                                is Part.Image -> put(JSONObject().put("type", "image").put("source",
                                    JSONObject().put("type", "base64").put("media_type", pt.mime).put("data", pt.base64)))
                                is Part.Pdf -> put(JSONObject().put("type", "document").put("source",
                                    JSONObject().put("type", "base64").put("media_type", "application/pdf").put("data", pt.base64)))
                            }
                        }
                        put(JSONObject().put("type", "text").put("text", m.text.ifEmpty { " " }))
                    }))
                }
                "assistant" -> {
                    flush()
                    val content = JSONArray()
                    if (m.text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", m.text))
                    m.toolCalls.forEach { c ->
                        content.put(JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name)
                            .put("input", runCatching { JSONObject(c.arguments) }.getOrDefault(JSONObject())))
                    }
                    if (content.length() == 0) content.put(JSONObject().put("type", "text").put("text", "…"))
                    out.put(JSONObject().put("role", "assistant").put("content", content))
                }
            }
        }
        flush()
        return out
    }

    override suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        Http.client.newCall(Http.get("${baseUrl.trimEnd('/')}/models?limit=100", headers())).await().use { r ->
            if (!r.isSuccessful) throw httpError(r)
            val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
            // All current Claude models accept images and tools.
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
                .map { ModelInfo(it, vision = true, tools = true) }
        }
    }
}
