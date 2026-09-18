package com.talqyn.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class ErrorMappingTest {
    private val instantRetries = TalqynRetryPolicy(maxRetries = 2, baseDelay = Duration.ZERO, maxDelay = Duration.ZERO)

    private suspend fun failingSearch(
        json: String,
        status: Int,
        headers: Map<String, String> = emptyMap(),
        retryPolicy: TalqynRetryPolicy = TalqynRetryPolicy.None,
    ): TalqynException? {
        val transport = StubTransport()
        transport.enqueue(json, status, headers)
        return try {
            val talqyn = TestFixtures.preparedClient(transport, retryPolicy = retryPolicy)
            talqyn.search.search("iphone")
            null
        } catch (e: TalqynException) {
            e
        }
    }

    @Test
    fun rateLimitCarriesRetryAfter() = runBlocking {
        val error = failingSearch("""{"detail":"Rate limit exceeded"}""", 429, mapOf("Retry-After" to "60"))
            as? TalqynException.RateLimited ?: throw AssertionError("expected rateLimited")
        assertEquals(60.seconds, error.retryAfter)
        assertEquals("Rate limit exceeded", error.detail)
        assertTrue(error.isRetryable)
    }

    /** HTTP headers are case-insensitive: the server may use any casing. */
    @Test
    fun retryAfterIsCaseInsensitive() = runBlocking {
        val error = failingSearch("{}", 429, mapOf("retry-after" to "5"))
        assertEquals(5.seconds, error?.retryAfter)
    }

    @Test
    fun validationErrorNamesFields() = runBlocking {
        val error = failingSearch(
            """
            {"error":"validation_error","request_id":"req-1",
             "detail":[{"loc":["body","filters",0],"msg":"too many keys","type":"value_error"}]}
            """,
            422,
        ) as? TalqynException.Validation ?: throw AssertionError("expected validation")
        assertEquals(listOf("body.filters.0"), error.fields)
        assertEquals("too many keys", error.detail)
        assertEquals("req-1", error.requestId)
        assertFalse(error.isRetryable)
    }

    @Test
    fun forbiddenKeepsServerDetail() = runBlocking {
        val error = failingSearch("""{"detail":"API key is missing the 'search' scope"}""", 403)
            as? TalqynException.Forbidden ?: throw AssertionError("expected forbidden")
        assertEquals("API key is missing the 'search' scope", error.detail)
    }

    @Test
    fun notFound() = runBlocking {
        val error = failingSearch("""{"detail":"chat not found"}""", 404)
        assertTrue("expected notFound, got $error", error is TalqynException.NotFound)
    }

    @Test
    fun serverEnvelopeCarriesCodeAndRequestId() = runBlocking {
        val error = failingSearch("""{"error":"database_unavailable","request_id":"a1b2c3"}""", 503)
            as? TalqynException.Server ?: throw AssertionError("expected server")
        assertEquals(503, error.status)
        assertEquals("database_unavailable", error.code)
        assertEquals("a1b2c3", error.requestId)
        assertTrue(error.isRetryable)
    }

    /** With no envelope to name it, the request id comes from the response header. */
    @Test
    fun requestIdFallsBackToTheResponseHeader() = runBlocking {
        val error = failingSearch("", 502, mapOf("X-Request-ID" to "hdr-1"))
        assertEquals("hdr-1", error?.requestId)
    }

    /** A 503 during a deploy names its own wait; the policy should see it. */
    @Test
    fun serverErrorCarriesRetryAfter() = runBlocking {
        val error = failingSearch("""{"error":"overloaded"}""", 503, mapOf("Retry-After" to "2"))
        assertEquals(2.seconds, error?.retryAfter)
    }

    /** Only a 5xx is the server's own failure. A 400 or a 405 is a fixed answer to a fixed request. */
    @Test
    fun clientErrorOutsideTheKnownSetIsNotRetried() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"detail":"bad request"}""", status = 400)

        val talqyn = TestFixtures.preparedClient(transport, retryPolicy = TalqynRetryPolicy(3, Duration.ZERO, Duration.ZERO))
        val error = expectFailure<TalqynException.Server>("expected a failure") { talqyn.search.search("iphone") }
        assertEquals(400, error.status)
        assertFalse(error.isRetryable)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun emptyBodyStillMapsByStatus() = runBlocking {
        val error = failingSearch("", 502) as? TalqynException.Server ?: throw AssertionError("expected server")
        assertEquals(502, error.status)
        assertNull(error.code)
    }

    @Test
    fun retryPolicyRepeatsServerErrors() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"error":"overloaded"}""", status = 503)
        transport.enqueue("""{"error":"overloaded"}""", status = 503)
        transport.enqueue("""{"search_id":"s","query":"x","locale":"ru","total":0,"results":[]}""")

        val talqyn = TestFixtures.preparedClient(transport, retryPolicy = instantRetries)
        talqyn.search.search("iphone")
        assertEquals(3, transport.sent.size)
    }

    /** A `5xx` may arrive after the event was already recorded, and a duplicate click is worse than a miss. */
    @Test
    fun eventsAreNotRepeatedAfterAServerError() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"error":"internal_error"}""", status = 500)
        transport.enqueue("", status = 204)

        val talqyn = TestFixtures.preparedClient(transport, retryPolicy = instantRetries)
        val error = expectFailure<TalqynException>("expected a failure") {
            talqyn.events.productClick(TalqynProductClickEvent(searchId = "s1", talqynId = 1, position = 0, source = TalqynEventSource.Instant))
        }
        assertEquals(500, error.statusCode)
        assertEquals(1, transport.sent.size)
    }

    /** A `429` is the one refusal that says the event was certainly not recorded. */
    @Test
    fun eventsAreRepeatedAfterARateLimit() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"detail":"Rate limit exceeded"}""", status = 429)
        transport.enqueue("", status = 204)

        val talqyn = TestFixtures.preparedClient(transport, retryPolicy = instantRetries)
        talqyn.events.searchSubmit(TalqynSearchSubmitEvent(query = "iphone", source = TalqynEventSource.Instant, resultsCount = 8))
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun clientErrorsAreNotRetried() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"detail":"nope"}""", status = 403)

        val talqyn = TestFixtures.preparedClient(transport, retryPolicy = TalqynRetryPolicy(3, Duration.ZERO, Duration.ZERO))
        expectFailure<TalqynException.Forbidden> { talqyn.search.search("iphone") }
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun decodingFailureIsReported() = runBlocking {
        val error = failingSearch("not json", 200)
        assertTrue("expected decoding, got $error", error is TalqynException.Decoding)
    }

    /** `Retry-After` comes in two forms; a date in the past is "now", not a negative wait. */
    @Test
    fun retryAfterIsReadInBothForms() {
        val now = TalqynDates.parseHttp("Tue, 26 Aug 2025 12:15:00 GMT")!!
        assertEquals(7.seconds, TalqynHttpResponse(429, mapOf("Retry-After" to "7")).retryAfter(now))
        assertEquals(7.seconds, TalqynHttpResponse(429, mapOf("Retry-After" to " 7 ")).retryAfter(now))
        assertEquals(30.seconds, TalqynHttpResponse(503, mapOf("Retry-After" to "Tue, 26 Aug 2025 12:15:30 GMT")).retryAfter(now))
        assertEquals(Duration.ZERO, TalqynHttpResponse(503, mapOf("Retry-After" to "Tue, 26 Aug 2025 12:00:00 GMT")).retryAfter(now))
        assertNull(TalqynHttpResponse(429, mapOf("Retry-After" to "soon")).retryAfter(now))
        assertNull(TalqynHttpResponse(429, mapOf("Retry-After" to "NaN")).retryAfter(now))
        assertNull(TalqynHttpResponse(429).retryAfter(now))
    }

    @Test
    fun retryPolicyHonoursCap() {
        val policy = TalqynRetryPolicy(maxRetries = 2, baseDelay = 300.milliseconds, maxDelay = 5.seconds)
        assertEquals(300.milliseconds, policy.delay(0, null, jitter = 1.0))
        assertEquals(600.milliseconds, policy.delay(1, null, jitter = 1.0))
        assertEquals("backoff is capped", 5.seconds, policy.delay(10, null, jitter = 1.0))
        assertEquals("a short Retry-After is honoured as-is", 2.seconds, policy.delay(0, 2.seconds, jitter = 0.0))
        assertNull(
            "a Retry-After beyond the cap declines the retry: a shorter wait would land in the same exhausted bucket",
            policy.delay(0, 60.seconds),
        )
    }

    /** Waits spread between half and all of each step, so an outage's clients do not all return at once. The cap holds. */
    @Test
    fun backoffIsJitteredWithinItsStep() {
        val policy = TalqynRetryPolicy(maxRetries = 2, baseDelay = 400.milliseconds, maxDelay = 5.seconds)
        fun seconds(duration: Duration?) = duration?.toDouble(DurationUnit.SECONDS) ?: -1.0
        assertEquals("half of the 0.8 s step", 0.4, seconds(policy.delay(1, null, jitter = 0.0)), 1e-9)
        assertEquals(0.6, seconds(policy.delay(1, null, jitter = 0.5)), 1e-9)
        assertEquals("half of the capped step", 2.5, seconds(policy.delay(10, null, jitter = 0.0)), 1e-9)
        repeat(50) {
            val wait = seconds(policy.delay(0, null))
            assertTrue("$wait is outside the step", wait in 0.2..0.4)
        }
    }

    /** A per-minute bucket answers `Retry-After: 60`. Waiting the cap and asking again is a hung search field, then the same 429. */
    @Test
    fun rateLimitBeyondCapSurfacesAtOnce() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"detail":"Rate limit exceeded"}""", status = 429, headers = mapOf("Retry-After" to "60"))

        val talqyn = TestFixtures.preparedClient(transport, retryPolicy = TalqynRetryPolicy.Default)
        val started = System.currentTimeMillis()
        val error = expectFailure<TalqynException>("expected a rate-limit failure") { talqyn.search.search("iphone") }
        assertEquals("the app gets the server's wait to act on", 60.seconds, error.retryAfter)
        assertEquals(1, transport.sent.size)
        assertTrue(System.currentTimeMillis() - started < 1000)
    }

    @Test
    fun rateLimitWithinCapIsRetried() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"detail":"Rate limit exceeded"}""", status = 429, headers = mapOf("Retry-After" to "0"))
        transport.enqueue("""{"search_id":"s","query":"x","locale":"ru","total":0,"results":[]}""")

        val talqyn = TestFixtures.preparedClient(transport, retryPolicy = TalqynRetryPolicy.Default)
        talqyn.search.search("iphone")
        assertEquals(2, transport.sent.size)
    }

    /** Every failure an SDK call surfaces is a [TalqynException] — including one the caller produced before anything was sent. */
    @Test
    fun encodingFailureIsTalqynException() = runBlocking {
        val transport = StubTransport()
        val talqyn = TestFixtures.preparedClient(transport)
        val error = expectFailure<TalqynException.Encoding>("expected an encoding failure") {
            talqyn.search.search(TalqynSearchQuery(query = "iphone", priceMin = Double.POSITIVE_INFINITY))
        }
        assertFalse(error.isRetryable)
        assertTrue("nothing must be sent", transport.sent.isEmpty())
    }
}
