package com.talqyn.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ConsultantStreamTest {
    @Test
    fun turnIsParsedIntoEvents() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        transport.enqueueStream(
            listOf(
                "event: status", "data: {\"stage\":\"thinking\"}", "",
                "event: status", "data: {\"stage\":\"searching\"}", "",
                "event: products", "data: {\"items\":[{\"talqyn_id\":1,\"title\":\"Laptop\"}],\"search_id\":\"s1\"}", "",
                "event: delta", "data: {\"text\":\"Here \"}", "",
                "event: delta", "data: {\"text\":\"[p:1]\"}", "",
                "event: follow_ups", "data: {\"items\":[\"cheaper\"]}", "",
                "event: done", "data: {\"session_id\":\"sess-1\",\"ttft_ms\":300,\"total_ms\":1500}", "",
            ),
        )

        val talqyn = TestFixtures.client(transport = transport)
        val events = talqyn.consultant.ask("need a laptop").toList()

        assertEquals(7, events.size)
        assertEquals(TalqynConsultantEvent.Status(TalqynConsultantStage.Thinking), events.first())
        assertTrue(events.last().isTerminal)

        val text = events.filterIsInstance<TalqynConsultantEvent.Delta>().joinToString("") { it.text }
        assertEquals(listOf(1L), TalqynAnswerMarkup.mentionedProductIds(text))

        val ask = transport.sent[1]
        assertEquals("/v1/consultant/ask", ask.path)
        assertEquals("text/event-stream", ask.header("Accept"))
        assertEquals("need a laptop", ask.bodyJson["question"])
    }

    @Test
    fun sessionIsCarriedIntoNextTurn() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        transport.enqueueStream(listOf("event: done", "data: {\"session_id\":\"sess-1\",\"total_ms\":10}", ""))
        transport.enqueueStream(listOf("event: done", "data: {\"session_id\":\"sess-1\",\"total_ms\":10}", ""))

        val talqyn = TestFixtures.client(transport = transport)
        val first = talqyn.consultant.ask("question").toList()
        val done = first.last() as? TalqynConsultantEvent.Done ?: throw AssertionError("expected done")

        talqyn.consultant.ask("second question", sessionId = done.done.sessionId).toList()
        assertEquals("sess-1", transport.sent[2].bodyJson["session_id"])
    }

    @Test
    fun clarifyTurnHasNoProducts() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        transport.enqueueStream(
            listOf(
                "event: status", "data: {\"stage\":\"thinking\"}", "",
                "event: clarify",
                "data: {\"message\":\"clarify\",\"questions\":[{\"id\":\"budget\",\"label\":\"Budget\",\"options\":[\"under 300k\"]}]}", "",
                "event: done", "data: {\"session_id\":\"s\",\"total_ms\":5}", "",
            ),
        )

        val talqyn = TestFixtures.client(transport = transport)
        val events = talqyn.consultant.ask("recommend something").toList()

        val clarify = events[1] as? TalqynConsultantEvent.Clarify ?: throw AssertionError("expected clarify")
        assertEquals("Budget", clarify.clarify.questions.first().label)
        assertFalse(events.any { it is TalqynConsultantEvent.Products })
    }

    @Test
    fun streamErrorStatusIsSurfaced() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        transport.enqueueStream(listOf("""{"detail":"Rate limit exceeded"}"""), status = 429, headers = mapOf("Retry-After" to "60"))

        val talqyn = TestFixtures.client(transport = transport)
        val error = expectFailure<TalqynException.RateLimited>("expected a rate-limit failure") {
            talqyn.consultant.ask("question").toList()
        }
        assertEquals(60.seconds, error.retryAfter)
    }

    @Test
    fun streamReissuesTokenOn401() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken(token = "tlqd_old")
        transport.enqueueStream(listOf("""{"detail":"Invalid device token"}"""), status = 401)
        transport.enqueueDeviceToken(token = "tlqd_new")
        transport.enqueueStream(listOf("event: done", "data: {\"session_id\":\"s\",\"total_ms\":1}", ""))

        val talqyn = TestFixtures.client(transport = transport)
        val events = talqyn.consultant.ask("question").toList()

        assertEquals(1, events.size)
        assertEquals(4, transport.sent.size)
        assertEquals("Bearer tlqd_new", transport.sent[3].header("Authorization"))
    }

    @Test
    fun fallbackTurnKeepsProducts() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        transport.enqueueStream(
            listOf(
                "event: products", "data: {\"items\":[{\"talqyn_id\":7,\"title\":\"A\"}]}", "",
                "event: fallback", "data: {\"reason\":\"user_budget_exceeded\"}", "",
                "event: done", "data: {\"session_id\":\"s\",\"total_ms\":9}", "",
            ),
        )

        val talqyn = TestFixtures.client(transport = transport)
        val events = talqyn.consultant.ask("question").toList()

        val products = events[0] as? TalqynConsultantEvent.Products ?: throw AssertionError("expected products")
        assertEquals(1, products.products.items.size)
        assertEquals(TalqynConsultantEvent.Fallback(TalqynFallbackReason.UserBudgetExceeded), events[1])
    }

    /**
     * The contract closes every turn with `done`. A stream that ends without it was cut somewhere
     * between Talqyn and the device, and the app must not take a turn that merely stopped for one
     * that finished.
     */
    @Test
    fun streamCutBeforeDoneThrowsAfterDeliveredEvents() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        transport.enqueueStream(
            listOf(
                "event: status", "data: {\"stage\":\"thinking\"}", "",
                "event: delta", "data: {\"text\":\"Here\"}", "",
            ),
        )

        val talqyn = TestFixtures.client(transport = transport)
        val events = ArrayList<TalqynConsultantEvent>()
        val error = expectFailure<TalqynException.Transport>("expected the cut to be reported") {
            talqyn.consultant.ask("question").collect { events.add(it) }
        }
        assertTrue("got ${error.cause}", error.cause is TalqynConnectionLostException)
        assertEquals("what arrived before the cut is delivered first", 2, events.size)
    }

    /** A dismissed screen stops reading. That cancels the request behind the stream, and it is not a cut stream. */
    @Test
    fun stoppingMidTurnCancelsTheRequestQuietly() = runBlocking {
        val transport = SlowStreamTransport()
        transport.responses.enqueueDeviceToken()
        transport.enqueueStream(
            listOf(
                "event: status", "data: {\"stage\":\"thinking\"}", "",
                "event: delta", "data: {\"text\":\"a\"}", "",
                "event: delta", "data: {\"text\":\"b\"}", "",
                "event: done", "data: {\"session_id\":\"s\"}", "",
            ),
        )
        val logs = LogCollector()
        val talqyn = TestFixtures.client(transport = transport, logHandler = logs::append)

        talqyn.consultant.ask("question").first()

        assertTrue("dropping the stream must cancel the request", waitUntil { transport.wasTerminated })
        delay(100)
        assertFalse(
            "a stopped consumer is not a cut stream: ${logs.all.map { it.message }}",
            logs.all.any { it.level == TalqynLogEvent.Level.Warning },
        )
    }

    /** The request is made when the flow is collected, not when `ask` is called. */
    @Test
    fun askIsColdUntilCollected() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        val talqyn = TestFixtures.preparedClient(transport)
        talqyn.consultant.ask("question")
        delay(50)
        assertTrue(transport.sent.isEmpty())
    }
}
