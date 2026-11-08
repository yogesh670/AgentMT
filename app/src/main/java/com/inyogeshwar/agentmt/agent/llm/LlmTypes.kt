package com.inyogeshwar.agentmt.agent.llm

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

data class ToolCall(val id: String, val name: String, val arguments: String)

data class ToolSpec(val name: String, val description: String, val schema: JSONObject)

/** Binary content attached to a user message. */
sealed interface Part {
    data class Image(val mime: String, val base64: String) : Part
    data class Pdf(val name: String, val base64: String) : Part
}

/** Provider-neutral transcript entry. role = user | assistant | tool. */
data class ChatMsg(
    val role: String,
    val text: String,
    val parts: List<Part> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
)

data class LlmRequest(
    val model: String,
    val system: String,
    val messages: List<ChatMsg>,
    val tools: List<ToolSpec>,
    val maxTokens: Int = 8192,
    val reasoning: String? = null,
)

sealed interface LlmEvent {
    data class Text(val delta: String) : LlmEvent
    data class Reasoning(val delta: String) : LlmEvent
    data class ToolCalls(val calls: List<ToolCall>) : LlmEvent
    data class Usage(val input: Long, val output: Long) : LlmEvent
    data class Finish(val reason: String?) : LlmEvent
}

/** [retryAfterMs] comes from Retry-After / X-RateLimit-Reset headers when the provider sends them. */
class LlmException(val code: Int, message: String, val retryAfterMs: Long? = null) : Exception(message)

/** Catalog entry. Null capability = unknown (never guessed). */
data class ModelInfo(val id: String, val vision: Boolean? = null, val tools: Boolean? = null, val contextLength: Int? = null)

interface LlmClient {
    fun stream(req: LlmRequest): Flow<LlmEvent>
    suspend fun listModels(): List<ModelInfo>
}

fun retryAfterMs(r: okhttp3.Response, now: Long = System.currentTimeMillis()): Long? {
    r.header("Retry-After")?.trim()?.let { v ->
        v.toLongOrNull()?.let { return it * 1000 }
        runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(v)!!.time - now }
            .getOrNull()?.let { return it.coerceAtLeast(0) }
    }
    (r.header("X-RateLimit-Reset") ?: r.header("x-ratelimit-reset-requests"))?.trim()?.toLongOrNull()?.let { v ->
        return when {
            v > 1_000_000_000_000L -> (v - now).coerceAtLeast(0)      // epoch ms
            v > 1_000_000_000L -> (v * 1000 - now).coerceAtLeast(0)    // epoch s
            else -> v * 1000                                           // seconds
        }
    }
    return null
}

fun httpError(r: okhttp3.Response): LlmException {
    val body = r.body?.string().orEmpty()
    return LlmException(r.code, errorMessage(r.code, body), retryAfterMs(r))
}

internal fun errorMessage(code: Int, body: String): String {
    val detail = runCatching {
        val o = JSONObject(body)
        o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: o.optString("message")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(300)
    val hint = when (code) {
        401, 403 -> "Check the API key."
        402 -> "Out of credit on this account."
        404 -> "Check the base URL and model name."
        429 -> "Rate limited or out of credit."
        in 500..599 -> "The provider had a server error."
        else -> ""
    }
    return "HTTP $code: $detail ${hint}".trim()
}
