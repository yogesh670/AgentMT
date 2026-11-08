package com.inyogeshwar.agentmt.agent.routing

import com.inyogeshwar.agentmt.agent.llm.LlmEvent
import com.inyogeshwar.agentmt.agent.llm.LlmException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class FailureKind { RATE_LIMIT, NO_CREDIT, AUTH, SERVER, NETWORK, TIMEOUT, CONTEXT, BAD_REQUEST, OTHER }

data class Failure(val kind: FailureKind, val code: Int?, val message: String, val cooldownMs: Long, val fallback: Boolean) {
    val label: String get() = when (kind) {
        FailureKind.RATE_LIMIT -> "rate limited"
        FailureKind.NO_CREDIT -> "out of credit"
        FailureKind.AUTH -> "key rejected"
        FailureKind.SERVER -> "server error"
        FailureKind.NETWORK -> "unreachable"
        FailureKind.TIMEOUT -> "timed out"
        FailureKind.CONTEXT -> "context too long"
        FailureKind.BAD_REQUEST -> "request rejected"
        FailureKind.OTHER -> "failed"
    }
}

object FailureClassifier {
    const val DEFAULT_RATE_COOLDOWN = 60_000L
    const val QUOTA_COOLDOWN = 60 * 60_000L
    const val CREDIT_COOLDOWN = 6 * 60 * 60_000L
    const val SERVER_COOLDOWN = 20_000L
    const val NETWORK_COOLDOWN = 15_000L
    const val MAX_COOLDOWN = 24 * 60 * 60_000L

    fun classify(e: Throwable): Failure {
        val msg = (e.message ?: e.javaClass.simpleName).take(300)
        val low = msg.lowercase()
        return when (e) {
            is LlmException -> when {
                e.code == 429 -> {
                    val daily = listOf("per day", "daily", "quota", "free-models-per-day", "exceeded your current quota").any { it in low }
                    Failure(FailureKind.RATE_LIMIT, 429, msg,
                        (e.retryAfterMs ?: if (daily) QUOTA_COOLDOWN else DEFAULT_RATE_COOLDOWN).coerceIn(1_000, MAX_COOLDOWN), true)
                }
                e.code == 402 || "insufficient credit" in low || "insufficient_quota" in low || "credit balance" in low ->
                    Failure(FailureKind.NO_CREDIT, e.code, msg, CREDIT_COOLDOWN, true)
                e.code == 401 || e.code == 403 -> Failure(FailureKind.AUTH, e.code, msg, 0, true)
                e.code == 413 || "context length" in low || "context_length" in low || "too many tokens" in low || "maximum context" in low ->
                    Failure(FailureKind.CONTEXT, e.code, msg, 0, true)
                e.code == 408 -> Failure(FailureKind.TIMEOUT, e.code, msg, SERVER_COOLDOWN, true)
                e.code >= 500 -> Failure(FailureKind.SERVER, e.code, msg, SERVER_COOLDOWN, true)
                e.code == 404 -> Failure(FailureKind.BAD_REQUEST, e.code, msg, 0, true) // model missing on this provider: try the next one
                e.code == 400 && ("image" in low || "tool" in low || "does not support" in low || "not supported" in low) ->
                    Failure(FailureKind.BAD_REQUEST, e.code, msg, 0, true) // capability mismatch: another model may work
                e.code in 400..499 -> Failure(FailureKind.BAD_REQUEST, e.code, msg, 0, false)
                else -> Failure(FailureKind.OTHER, e.code, msg, 0, false)
            }
            is SocketTimeoutException, is InterruptedIOException -> Failure(FailureKind.TIMEOUT, null, msg, SERVER_COOLDOWN, true)
            is UnknownHostException -> Failure(FailureKind.NETWORK, null, msg, NETWORK_COOLDOWN, true)
            is IOException -> Failure(FailureKind.NETWORK, null, msg, NETWORK_COOLDOWN, true)
            else -> Failure(FailureKind.OTHER, null, msg, 0, false)
        }
    }
}

/** One thing the router can try: a provider + model + (optionally) a specific key. */
data class Candidate(
    val providerId: String,
    val providerName: String,
    val model: String,
    val credentialId: String?,
    val credentialLabel: String?,
    val cooldownUntil: Long = 0,
    val invalid: Boolean = false,
    val vision: Boolean? = null,
    val tools: Boolean? = null,
) {
    val display: String get() = listOfNotNull(providerName, credentialLabel).joinToString(" · ") + " / " + model
}

class NoRouteException(message: String) : Exception(message)

/** Thrown when the stream had already produced output; we never replay a half-streamed answer elsewhere. */
class MidStreamException(val failure: Failure, cause: Throwable) : Exception("${failure.label}: ${failure.message}", cause)

/**
 * Bounded fallback loop (doc 22 §8): at most [maxAttempts] upstream calls and [deadlineMs] wall-clock before the
 * first token. Never retries after cancellation or after output started. Skips cooling-down, invalid and
 * capability-incompatible candidates and says why.
 */
class RouteRunner(
    private val maxAttempts: Int,
    private val deadlineMs: Long = 180_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: suspend (Long) -> Unit = { delay(it) },
) {
    data class Hooks(
        val onEvent: suspend (LlmEvent) -> Unit,
        val onNote: suspend (String) -> Unit,
        val onFailure: suspend (Candidate, Failure) -> Unit,
        val onSuccess: suspend (Candidate, Long) -> Unit,
    )

    suspend fun run(
        candidates: List<Candidate>,
        needsVision: Boolean,
        needsTools: Boolean,
        attempt: suspend (Candidate, suspend (LlmEvent) -> Unit) -> Unit,
        hooks: Hooks,
    ): Candidate {
        val start = clock()
        val skipped = ArrayList<String>()
        var attempts = 0
        var lastFailure: Failure? = null
        val coolingHere = HashSet<String>()
        for ((i, c) in candidates.withIndex()) {
            val now = clock()
            val credKey = c.credentialId ?: c.providerId
            when {
                c.invalid -> { skipped += "${c.display}: key rejected earlier"; continue }
                c.cooldownUntil > now || credKey in coolingHere -> {
                    skipped += "${c.display}: cooling down" + if (c.cooldownUntil > now) " ${(c.cooldownUntil - now) / 1000}s" else ""
                    continue
                }
                needsVision && c.vision == false -> { skipped += "${c.display}: no image input"; continue }
                needsTools && c.tools == false -> { skipped += "${c.display}: no tool calling"; continue }
            }
            if (attempts >= maxAttempts) { skipped += "retry budget used ($maxAttempts attempts)"; break }
            if (now - start > deadlineMs) { skipped += "time budget used"; break }
            attempts++
            var produced = false
            val t0 = clock()
            try {
                attempt(c) { ev ->
                    if (ev is LlmEvent.Text || ev is LlmEvent.ToolCalls || ev is LlmEvent.Reasoning) produced = true
                    hooks.onEvent(ev)
                }
                hooks.onSuccess(c, clock() - t0)
                return c
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val f = FailureClassifier.classify(e)
                lastFailure = f
                hooks.onFailure(c, f)
                if (produced) throw MidStreamException(f, e)
                if (!f.fallback) throw e
                if (f.kind == FailureKind.RATE_LIMIT || f.kind == FailureKind.NO_CREDIT || f.kind == FailureKind.AUTH) coolingHere += credKey
                val next = candidates.drop(i + 1).firstOrNull()
                if (next != null) {
                    hooks.onNote("${c.display} ${f.label}${f.code?.let { " ($it)" } ?: ""} → trying ${next.display}")
                    if (f.kind == FailureKind.SERVER || f.kind == FailureKind.TIMEOUT) backoff((400L shl (attempts - 1).coerceAtMost(4)).coerceAtMost(4000))
                }
            }
        }
        val why = buildString {
            append("No model could answer.")
            lastFailure?.let { append(" Last error: ${it.label} — ${it.message.take(160)}.") }
            if (skipped.isNotEmpty()) append(" Skipped: ${skipped.joinToString("; ")}.")
            append(" Add another key or model under Settings → Routing.")
        }
        throw NoRouteException(why)
    }
}
