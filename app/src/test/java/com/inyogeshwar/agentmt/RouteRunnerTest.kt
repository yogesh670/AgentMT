package com.inyogeshwar.agentmt

import com.inyogeshwar.agentmt.agent.llm.LlmEvent
import com.inyogeshwar.agentmt.agent.llm.LlmException
import com.inyogeshwar.agentmt.agent.routing.Candidate
import com.inyogeshwar.agentmt.agent.routing.FailureClassifier
import com.inyogeshwar.agentmt.agent.routing.FailureKind
import com.inyogeshwar.agentmt.agent.routing.MidStreamException
import com.inyogeshwar.agentmt.agent.routing.NoRouteException
import com.inyogeshwar.agentmt.agent.routing.RouteRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RouteRunnerTest {
    private fun cand(cred: String, model: String = "m", vision: Boolean? = null, cooldown: Long = 0, invalid: Boolean = false) =
        Candidate("or", "OpenRouter", model, cred, cred, cooldown, invalid, vision, null)

    private val notes = ArrayList<String>()
    private val tried = ArrayList<String>()
    private fun hooks() = RouteRunner.Hooks(onEvent = {}, onNote = { notes += it }, onFailure = { _, _ -> }, onSuccess = { _, _ -> })
    private val runner = RouteRunner(maxAttempts = 4, clock = { 1_000L }, backoff = {})

    @Test fun firstCandidateSucceeds() = runBlocking {
        val c = runner.run(listOf(cand("a"), cand("b")), false, true, { c, _ -> tried += c.credentialId!! }, hooks())
        assertEquals("a", c.credentialId); assertEquals(listOf("a"), tried)
    }

    @Test fun rateLimitFallsBackToNextAccount() = runBlocking {
        val c = runner.run(listOf(cand("a"), cand("b"), cand("c")), false, true, { c, emit ->
            tried += c.credentialId!!
            if (c.credentialId == "a") throw LlmException(429, "rate limited", 30_000)
            emit(LlmEvent.Text("hi"))
        }, hooks())
        assertEquals("b", c.credentialId); assertEquals(listOf("a", "b"), tried); assertTrue(notes.single().contains("rate limited"))
    }

    @Test fun authFailureDoesNotRetrySameKey() = runBlocking {
        val c = runner.run(listOf(cand("a", "m1"), cand("a", "m2"), cand("b", "m1")), false, true, { c, _ ->
            tried += "${c.credentialId}/${c.model}"
            if (c.credentialId == "a") throw LlmException(401, "bad key")
        }, hooks())
        assertEquals("b", c.credentialId); assertEquals(listOf("a/m1", "b/m1"), tried)
    }

    @Test fun badRequestStopsImmediately() = runBlocking {
        try {
            runner.run(listOf(cand("a"), cand("b")), false, true, { c, _ -> tried += c.credentialId!!; throw LlmException(400, "invalid json") }, hooks())
            fail("expected")
        } catch (e: LlmException) { assertEquals(listOf("a"), tried) }
    }

    @Test fun retryBudgetIsBounded() = runBlocking {
        val r = RouteRunner(maxAttempts = 2, clock = { 0L }, backoff = {})
        try {
            r.run((1..6).map { cand("k$it") }, false, true, { c, _ -> tried += c.credentialId!!; throw LlmException(503, "down") }, hooks())
            fail("expected")
        } catch (e: NoRouteException) { assertEquals(2, tried.size); assertTrue(e.message!!.contains("retry budget")) }
    }

    @Test fun skipsCoolingInvalidAndTextOnlyForImages() = runBlocking {
        val c = runner.run(listOf(cand("a", cooldown = 5_000), cand("b", invalid = true), cand("c", vision = false), cand("d")), true, true,
            { c, _ -> tried += c.credentialId!! }, hooks())
        assertEquals("d", c.credentialId); assertEquals(listOf("d"), tried)
    }

    @Test fun noFallbackAfterOutputStarted() = runBlocking {
        try {
            runner.run(listOf(cand("a"), cand("b")), false, true, { c, emit ->
                tried += c.credentialId!!; emit(LlmEvent.Text("partial")); throw IOException("reset")
            }, hooks())
            fail("expected")
        } catch (e: MidStreamException) { assertEquals(listOf("a"), tried) }
    }

    @Test fun cancellationIsNeverRetried() = runBlocking {
        try {
            runner.run(listOf(cand("a"), cand("b")), false, true, { c, _ -> tried += c.credentialId!!; throw CancellationException("user") }, hooks())
            fail("expected")
        } catch (e: CancellationException) { assertEquals(listOf("a"), tried) }
    }

    @Test fun classifier() {
        assertEquals(FailureKind.RATE_LIMIT, FailureClassifier.classify(LlmException(429, "x")).kind)
        assertEquals(30_000L, FailureClassifier.classify(LlmException(429, "x", 30_000)).cooldownMs)
        assertEquals(FailureClassifier.QUOTA_COOLDOWN, FailureClassifier.classify(LlmException(429, "free-models-per-day exceeded")).cooldownMs)
        assertEquals(FailureKind.NO_CREDIT, FailureClassifier.classify(LlmException(402, "x")).kind)
        assertEquals(FailureKind.AUTH, FailureClassifier.classify(LlmException(401, "x")).kind)
        assertEquals(FailureKind.SERVER, FailureClassifier.classify(LlmException(502, "x")).kind)
        assertEquals(FailureKind.NETWORK, FailureClassifier.classify(IOException("x")).kind)
        assertEquals(false, FailureClassifier.classify(LlmException(400, "bad")).fallback)
    }
}
