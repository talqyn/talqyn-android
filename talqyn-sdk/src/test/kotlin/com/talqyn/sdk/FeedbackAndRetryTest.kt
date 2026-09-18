package com.talqyn.sdk

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Ratings of consultant turns, and which failed requests may be sent again. */
class FeedbackAndRetryTest {
    private val turnId = "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"
    private val instantRetries = TalqynRetryPolicy(maxRetries = 2, baseDelay = Duration.ZERO, maxDelay = Duration.ZERO)

    // region Feedback

    @Test
    fun feedbackIsPostedWithReasonsOnlyForADislike() = runBlocking {
        val transport = StubTransport()
        repeat(3) { transport.enqueue("", status = 204) }
        val talqyn = TestFixtures.preparedClient(transport)
        talqyn.setVariant("exp-b")

        talqyn.consultant.submitFeedback(
            TalqynFeedback(
                turnId = turnId,
                sessionId = "sess-0001",
                verdict = TalqynFeedbackVerdict.Down,
                reasons = listOf(TalqynFeedbackReason.NotRelevant, TalqynFeedbackReason.PriceStock),
                comment = "wrong ones",
                talqynIds = listOf(7),
            ),
        )
        talqyn.consultant.submitFeedback(
            TalqynFeedback(
                turnId = turnId,
                sessionId = "sess-0001",
                verdict = TalqynFeedbackVerdict.Up,
                reasons = listOf(TalqynFeedbackReason.Other),
                comment = "extra",
            ),
        )
        talqyn.consultant.withdrawFeedback(turnId)

        val down = transport.sent[0]
        assertEquals("/v1/consultant/feedback", down.path)
        assertEquals("POST", down.request.method)
        assertEquals(turnId, down.bodyJson["turn_id"])
        assertEquals("sess-0001", down.bodyJson["session_id"])
        assertEquals("down", down.bodyJson["verdict"])
        assertEquals(listOf("not_relevant", "price_stock"), down.bodyJson["reasons"])
        assertEquals("wrong ones", down.bodyJson["comment"])
        assertEquals(listOf(7L), down.bodyJson["talqyn_ids"])
        assertEquals("the client's bucket is filled in", "exp-b", down.bodyJson["variant"])

        val up = transport.sent[1].bodyJson
        assertEquals("up", up["verdict"])
        assertNull("an up rating carries no reasons for the server to drop", up["reasons"])
        assertNull(up["comment"])

        assertEquals("DELETE", transport.sent[2].request.method)
        assertEquals("/v1/consultant/feedback/$turnId", transport.sent[2].path)
    }

    /** A rating replaces itself, so a repeat is harmless and a failure is retried like a read. */
    @Test
    fun feedbackIsRetriedAfterAServerError() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"error":"database_unavailable"}""", status = 503)
        transport.enqueue("", status = 204)
        val talqyn = TestFixtures.preparedClient(transport, retryPolicy = instantRetries)
        talqyn.consultant.submitFeedback(TalqynFeedback(turnId = turnId, sessionId = "sess-0001", verdict = TalqynFeedbackVerdict.Up))
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun turnIdArrivesOnDoneTheAnswerAndTheTranscript() {
        val done = TalqynConsultantDone.decode(TalqynJson.parseObject("""{"session_id":"s1","turn_id":"$turnId","total_ms":10}""".toByteArray()))
        assertEquals(turnId, done.turnId)

        val answer = TalqynConsultantAnswer.decode(TalqynJson.parseObject("""{"answer":"","products":[],"turn_id":"$turnId"}""".toByteArray()))
        assertEquals(turnId, answer.turnId)

        val transcript = TalqynChatTranscript.decode(
            TalqynJson.parseObject(
                """
                {"session_id":"s1","messages":[
                  {"role":"user","text":"q","turn_id":"$turnId","feedback":"down"},
                  {"role":"assistant","text":"a","turn_id":"$turnId","feedback":"down"},
                  {"role":"user","text":"old"}
                ],"products":[]}
                """.toByteArray(),
            ),
        )
        assertEquals(turnId, transcript.messages[1].turnId)
        assertEquals(TalqynFeedbackVerdict.Down, transcript.messages[1].feedback)
        assertNull("a turn from before ratings has no id", transcript.messages[2].turnId)
        assertNull(transcript.messages[2].feedback)
    }

    // endregion

    // region Retry safety

    /**
     * A consultant turn starts once the server accepts the stream. A refusal before that is safe
     * to repeat; a connection that dropped may have left a paid turn running on the other end.
     */
    @Test
    fun aStreamIsRepeatedAfterARefusalButNotAfterADroppedConnection() = runBlocking {
        val transport = StubTransport()
        transport.enqueueStream(listOf("""{"error":"overloaded"}"""), status = 503)
        transport.enqueueStream(listOf("event: done", """data: {"session_id":"s"}""", ""))
        transport.enqueueStream(StubIOException("connection lost"))
        transport.enqueueStream(listOf("event: done", """data: {"session_id":"s"}""", ""))
        val talqyn = TestFixtures.preparedClient(transport, retryPolicy = instantRetries)

        talqyn.consultant.ask("question").collect()
        assertEquals("the 503 was repeated", 2, transport.sent.size)

        val error = expectFailure<TalqynException.Transport>("expected the dropped connection to surface") {
            talqyn.consultant.ask("another question").collect()
        }
        assertTrue(error.cause is StubIOException)
        assertEquals("a dropped connection is not repeated", 3, transport.sent.size)
    }

    /** Nothing comes back from `stream=false` until the turn is written, so a `5xx` may follow a turn that ran. */
    @Test
    fun theJsonAnswerIsRepeatedOnlyAfterARateLimit() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"detail":"Rate limit exceeded"}""", status = 429)
        transport.enqueue("""{"answer":"ok","products":[]}""")
        transport.enqueue("""{"error":"internal_error"}""", status = 500)
        transport.enqueue("""{"answer":"ok","products":[]}""")
        val talqyn = TestFixtures.preparedClient(transport, retryPolicy = instantRetries)

        talqyn.consultant.answer(TalqynConsultantQuery(question = "hi"))
        assertEquals(2, transport.sent.size)

        val error = expectFailure<TalqynException>("expected the server error to surface") {
            talqyn.consultant.answer(TalqynConsultantQuery(question = "hi"))
        }
        assertEquals(500, error.statusCode)
        assertEquals(3, transport.sent.size)
        assertEquals(
            "the whole turn is waited for as long as a stream waits for its first byte",
            60.seconds,
            transport.sent[0].request.timeout,
        )
    }

    /** A token that could not be minted means the request itself was never sent: even an event may be tried again. */
    @Test
    fun aFailedMintIsRetriedEvenForAnEvent() = runBlocking {
        val transport = StubTransport()
        transport.enqueue(StubIOException("no internet"))
        transport.enqueueDeviceToken()
        transport.enqueue("", status = 204)
        val talqyn = TestFixtures.client(transport = transport, retryPolicy = instantRetries)

        talqyn.events.productClick(TalqynProductClickEvent(searchId = "s1", talqynId = 1, position = 0, source = TalqynEventSource.Instant))
        assertEquals(
            listOf("/v1/consultant/token", "/v1/consultant/token", "/v1/events/product-click"),
            transport.sent.map { it.path },
        )
    }

    @Test
    fun retrySafetyRules() {
        val overloaded = TalqynException.Server(503, "overloaded", null, null, null)
        val limited = TalqynException.RateLimited(null, null, null)
        val dropped = TalqynException.Transport(StubIOException("connection lost"))
        val refused = TalqynException.Forbidden(null, null)

        for (error in listOf(overloaded, limited, dropped)) {
            assertTrue(TalqynApiClient.canRetry(error, TalqynRetrySafety.Idempotent, didSend = true))
        }
        assertTrue(TalqynApiClient.canRetry(overloaded, TalqynRetrySafety.UntilAccepted, didSend = true))
        assertFalse(TalqynApiClient.canRetry(dropped, TalqynRetrySafety.UntilAccepted, didSend = true))
        assertTrue(TalqynApiClient.canRetry(limited, TalqynRetrySafety.OnlyIfRejected, didSend = true))
        assertFalse(TalqynApiClient.canRetry(overloaded, TalqynRetrySafety.OnlyIfRejected, didSend = true))
        assertTrue("nothing was sent", TalqynApiClient.canRetry(dropped, TalqynRetrySafety.OnlyIfRejected, didSend = false))
        assertFalse("a refusal stays a refusal", TalqynApiClient.canRetry(refused, TalqynRetrySafety.Idempotent, didSend = false))
    }

    // endregion

    // region Errors

    /** A transport of your own may fail with an error of its own; the SDK reports it as a transport failure without losing what it was. */
    @Test
    fun wrapKeepsAForeignErrorUnderneath() {
        class PinningFailed : Exception("certificate pin mismatch")

        val original = PinningFailed()
        val wrapped = TalqynException.wrap(original) as? TalqynException.Transport
            ?: throw AssertionError("expected transport")
        assertSame(original, wrapped.cause)
        assertTrue(wrapped.message, wrapped.message.orEmpty().contains("pin"))
    }

    /** Cancellation is not a failure: wrapping it hands it straight back to the coroutine machinery. */
    @Test
    fun wrapRethrowsCancellation() {
        expectFailure<CancellationException> { TalqynException.wrap(CancellationException("stopped")) }
        val talqyn = TalqynException.NotFound("a")
        assertSame(talqyn, TalqynException.wrap(talqyn))
    }

    @Test
    fun errorsCompareByValue() {
        assertEquals(TalqynException.NotFound("a"), TalqynException.NotFound("a"))
        assertNotEquals(TalqynException.NotFound("a"), TalqynException.NotFound("b"))
        assertNotEquals(TalqynException.NotFound(null), TalqynException.Forbidden(null, null))
        assertEquals(
            TalqynException.Transport(SocketTimeoutException("timed out")),
            TalqynException.Transport(SocketTimeoutException("timed out")),
        )
        assertEquals(
            TalqynException.Transport(SocketTimeoutException("timed out")).hashCode(),
            TalqynException.Transport(SocketTimeoutException("timed out")).hashCode(),
        )
    }

    /** The advice for a `when` over events — an `else` branch for subtypes a later release adds — must compile cleanly. */
    @Test
    fun anEventWhenWithAnElseBranchCompiles() {
        val event: TalqynConsultantEvent = TalqynConsultantEvent.Status(TalqynConsultantStage.Thinking)
        val name = when (event) {
            is TalqynConsultantEvent.Status -> "status"
            is TalqynConsultantEvent.Delta -> "delta"
            is TalqynConsultantEvent.Done -> "done"
            else -> "other"
        }
        assertEquals("status", name)
    }

    // endregion
}
