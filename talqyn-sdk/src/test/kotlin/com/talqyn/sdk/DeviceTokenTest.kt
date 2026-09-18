package com.talqyn.sdk

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class DeviceTokenTest {
    private val emptySearch = """{"search_id":"s","query":"x","locale":"ru","total":0,"results":[]}"""

    @Test
    fun firstRequestMintsTokenAndUsesIt() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken(token = "tlqd_abc")
        transport.enqueue(emptySearch)

        val talqyn = TestFixtures.client(transport = transport)
        talqyn.search.search("iphone")

        assertEquals(2, transport.sent.size)
        val mint = transport.sent[0]
        assertEquals("/v1/consultant/token", mint.path)
        assertEquals("ck_3f9a1c2b7d4e", mint.header("X-Client-Key"))
        assertNotNull(mint.header("X-Client-Timestamp"))
        assertNotNull(mint.header("X-Client-Nonce"))
        assertEquals(64, mint.header("X-Client-Sig")?.length)
        // The client secret never travels: only its id and the signature do.
        assertFalse(mint.bodyText.contains("s3cr3t"))
        assertFalse(mint.request.headers.values.any { it.contains("s3cr3t") })

        val search = transport.sent[1]
        assertEquals("/v1/search/", search.path)
        assertEquals("Bearer tlqd_abc", search.header("Authorization"))
        // The token names the shopper: no headers needed under it.
        assertNull(search.header("X-User-ID"))
        assertNull(search.header("X-User-Sig"))
    }

    /** The signature must cover exactly the bytes that went on the wire. */
    @Test
    fun mintSignatureCoversSentBody() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        transport.enqueue(emptySearch)

        val talqyn = TestFixtures.client(transport = transport)
        talqyn.search.search("iphone")

        val mint = transport.sent[0]
        val expected = TalqynClientSignature.sign(
            secret = TestFixtures.SECRET,
            keyId = mint.header("X-Client-Key")!!,
            timestamp = mint.header("X-Client-Timestamp")!!,
            nonce = mint.header("X-Client-Nonce")!!,
            body = mint.body!!,
        )
        assertEquals(expected, mint.header("X-Client-Sig"))
    }

    @Test
    fun guestTokenBodyHasNoUserId() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        transport.enqueue(emptySearch)

        val talqyn = TestFixtures.client(credentials = TestFixtures.deviceToken(TalqynDeviceIdentity.Guest), transport = transport)
        talqyn.search.search("iphone")

        assertEquals("myshop", transport.sent[0].bodyJson["storefront"])
        assertNull(transport.sent[0].bodyJson["user_id"])
    }

    @Test
    fun namedIdentitySendsUserId() = runBlocking {
        val transport = StubTransport()
        val identifier = UUID.randomUUID()
        val canonical = identifier.toString().lowercase(Locale.ROOT)
        transport.enqueueDeviceToken(userId = canonical)
        transport.enqueue(emptySearch)

        val talqyn = TestFixtures.client(credentials = TestFixtures.deviceToken(TalqynDeviceIdentity.User(identifier)), transport = transport)
        talqyn.search.search("iphone")

        assertEquals(canonical, transport.sent[0].bodyJson["user_id"])
        assertEquals(canonical, talqyn.currentUserId())
    }

    /** The persistent anonymous id must survive a restart: chat history hangs on it. */
    @Test
    fun persistentAnonymousIdentityIsStoredAndReused() = runBlocking {
        val store = TalqynInMemoryUserIdStore()
        val first = StubTransport()
        first.enqueueDeviceToken()
        first.enqueue(emptySearch)

        val talqyn = TestFixtures.client(
            credentials = TestFixtures.deviceToken(TalqynDeviceIdentity.PersistentAnonymous),
            transport = first,
            userIdStore = store,
        )
        talqyn.search.search("iphone")
        val generated = first.sent[0].bodyJson["user_id"] as String
        assertNotNull(store.loadUserId())

        // A fresh SDK instance, the same shopper.
        val second = StubTransport()
        second.enqueueDeviceToken()
        second.enqueue(emptySearch)
        val restarted = TestFixtures.client(
            credentials = TestFixtures.deviceToken(TalqynDeviceIdentity.PersistentAnonymous),
            transport = second,
            userIdStore = store,
        )
        restarted.search.search("iphone")
        assertEquals(generated, second.sent[0].bodyJson["user_id"])
    }

    /** The default store keeps the id and the clock correction in a file that a new process reads back. */
    @Test
    fun fileStoreSurvivesARestart() {
        val directory = Files.createTempDirectory("talqyn-store").toFile()
        try {
            val file = File(directory, "talqyn/identity.properties")
            val id = UUID.randomUUID()
            TalqynFileUserIdStore(file).apply {
                saveUserId(id)
                saveClockOffset((-900).seconds)
            }
            val reopened = TalqynFileUserIdStore(file)
            assertEquals(id, reopened.loadUserId())
            assertEquals((-900).seconds, reopened.loadClockOffset())

            reopened.saveUserId(null)
            reopened.saveClockOffset(null)
            val cleared = TalqynFileUserIdStore(file)
            assertNull(cleared.loadUserId())
            assertNull(cleared.loadClockOffset())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fileStoreIgnoresAnIdThatIsNotAUuid() {
        val directory = Files.createTempDirectory("talqyn-store").toFile()
        try {
            val file = File(directory, "identity.properties")
            file.writeText("user_id=1-2-3-4-5\n")
            assertNull(TalqynFileUserIdStore(file).loadUserId())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun tokenIsReusedAcrossRequests() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        transport.enqueue(emptySearch)
        transport.enqueue(emptySearch)

        val talqyn = TestFixtures.client(transport = transport)
        talqyn.search.search("iphone")
        talqyn.search.search("samsung")

        assertEquals(1, transport.sent.count { it.path.endsWith("/consultant/token") })
    }

    /** Concurrent screens must not mint two tokens. */
    @Test
    fun concurrentRequestsMintOnce() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        repeat(4) { transport.enqueue(emptySearch) }

        val talqyn = TestFixtures.client(transport = transport)
        (0 until 4).map { async(kotlinx.coroutines.Dispatchers.Default) { talqyn.search.search("iphone") } }.awaitAll()

        assertEquals(1, transport.sent.count { it.path.endsWith("/consultant/token") })
    }

    @Test
    fun unauthorizedTriggersOneReissueAndRetry() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken(token = "tlqd_old")
        transport.enqueue("""{"detail":"Invalid device token"}""", status = 401)
        transport.enqueueDeviceToken(token = "tlqd_new")
        transport.enqueue(emptySearch)

        val talqyn = TestFixtures.client(transport = transport)
        talqyn.search.search("iphone")

        assertEquals(4, transport.sent.size)
        assertEquals("Bearer tlqd_old", transport.sent[1].header("Authorization"))
        assertEquals("Bearer tlqd_new", transport.sent[3].header("Authorization"))
    }

    /** A second 401 in a row means expiry is not the cause: reissuing in a loop would never end. */
    @Test
    fun repeatedUnauthorizedSurfacesError() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        transport.enqueue("""{"detail":"Invalid device token"}""", status = 401)
        transport.enqueueDeviceToken()
        transport.enqueue("""{"detail":"Invalid device token"}""", status = 401)

        val talqyn = TestFixtures.client(transport = transport)
        val error = expectFailure<TalqynException.Unauthorized>("expected an authorization failure") { talqyn.search.search("iphone") }
        assertEquals("Invalid device token", error.detail)
    }

    @Test
    fun mintDisabledSurfacesForbiddenAndIsNotRetriedImmediately() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"detail":"Device token issuance is not enabled for this storefront"}""", status = 403)

        val talqyn = TestFixtures.client(transport = transport)
        expectFailure<TalqynException.Forbidden>("expected the mint to be refused") { talqyn.search.search("iphone") }

        // Cooldown after an unrecoverable refusal: no hammering the endpoint.
        expectFailure<TalqynException.Forbidden>("expected the same refusal") { talqyn.search.search("iphone") }
        assertEquals("the second mint must not reach the network", 1, transport.sent.size)
    }

    @Test
    fun notConfiguredInstallationHasItsOwnError() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"detail":"Device tokens are not configured"}""", status = 501)

        val talqyn = TestFixtures.client(transport = transport)
        val error = expectFailure<TalqynException.DeviceTokensNotConfigured> { talqyn.search.search("iphone") }
        assertFalse(error.isRetryable)
    }

    @Test
    fun noAnchorKeyIsRetryable() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"detail":"No active API key"}""", status = 503)

        val talqyn = TestFixtures.client(transport = transport)
        val error = expectFailure<TalqynException.DeviceTokensUnavailable> { talqyn.search.search("iphone") }
        assertTrue(error.isRetryable)
    }

    /** "No anchor key" is a support conversation. A platform 503 — or a bare one from a proxy — is not. */
    @Test
    fun platform503OnMintIsAnOrdinaryServerError() = runBlocking {
        for (body in listOf("""{"error":"overloaded","request_id":"r1"}""", "")) {
            val transport = StubTransport()
            transport.enqueue(body, status = 503)

            val talqyn = TestFixtures.client(transport = transport)
            val error = expectFailure<TalqynException.Server>("expected server for body $body") { talqyn.search.search("iphone") }
            assertEquals(503, error.status)
            assertTrue(error.isRetryable)
        }
    }

    /** Changing the shopper must discard the token, or the new shopper's turns land in the previous one's history. */
    @Test
    fun identityChangeReissuesToken() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken(token = "tlqd_guest")
        transport.enqueue(emptySearch)
        transport.enqueueDeviceToken(token = "tlqd_named")
        transport.enqueue(emptySearch)

        val talqyn = TestFixtures.client(transport = transport)
        talqyn.search.search("iphone")
        talqyn.setIdentity(TalqynDeviceIdentity.User(UUID.randomUUID()))
        talqyn.search.search("iphone")

        assertEquals(4, transport.sent.size)
        assertEquals("Bearer tlqd_named", transport.sent[3].header("Authorization"))
    }

    @Test
    fun currentIdentityFollowsWhatTheAppSet() = runBlocking {
        val transport = StubTransport()
        val talqyn = TestFixtures.client(credentials = TestFixtures.deviceToken(TalqynDeviceIdentity.Guest), transport = transport)
        assertEquals(TalqynDeviceIdentity.Guest, talqyn.currentIdentity())

        val shopper = UUID.randomUUID()
        talqyn.setIdentity(TalqynDeviceIdentity.User(shopper))
        assertEquals(TalqynDeviceIdentity.User(shopper), talqyn.currentIdentity())
        assertTrue("reading the identity mints nothing", transport.sent.isEmpty())
    }

    @Test
    fun prepareMintsAhead() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()

        val talqyn = TestFixtures.client(transport = transport)
        talqyn.prepare()

        assertEquals(1, transport.sent.size)
        assertEquals("/v1/consultant/token", transport.sent[0].path)
    }

    /** The configuration prints without the secret: it lands in crash reports and logs. */
    @Test
    fun credentialsDoNotPrintTheSecret() {
        assertFalse(TestFixtures.deviceToken().toString().contains(TestFixtures.SECRET))
    }

    // region Lifecycle, driven by a fake clock

    /** Past the refresh mark the token is still good: it is served at once and the next one is fetched behind the request's back. */
    @Test
    fun staleTokenIsServedWhileReissuingInBackground() = runBlocking {
        val clock = FakeClock()
        val transport = StubTransport()
        transport.enqueueDeviceToken(token = "tlqd_first", expiresIn = 900)
        val authorizer = TestFixtures.authorizer(transport, clock)

        authorizer.prepare()
        clock.advance(800.seconds) // refresh at 780 s, expiry at 900 s
        transport.enqueueDeviceToken(token = "tlqd_second", expiresIn = 900)

        assertEquals("no request waits for a reissue", "Bearer tlqd_first", authorizer.bearer())
        assertTrue(
            "the background reissue must install its token",
            waitUntil { authorizer.bearer() == "Bearer tlqd_second" },
        )
        assertEquals("one reissue, not one per request", 2, transport.sent.size)
    }

    /** A reissue that fails costs nothing while the current token lasts. */
    @Test
    fun reissueFailureKeepsTheLiveToken() = runBlocking {
        val clock = FakeClock()
        val transport = StubTransport()
        transport.enqueueDeviceToken(token = "tlqd_live", expiresIn = 900)
        val logs = LogCollector()
        val authorizer = TestFixtures.authorizer(transport, clock, logHandler = logs::append)

        authorizer.prepare()
        clock.advance(800.seconds)
        transport.enqueue("""{"error":"overloaded"}""", status = 503)

        assertEquals("Bearer tlqd_live", authorizer.bearer())
        assertTrue(
            "a failed reissue is a log line, not a failed request",
            waitUntil { logs.all.any { it.level == TalqynLogEvent.Level.Warning && it.message.contains("reissue failed") } },
        )

        clock.advance(99.seconds) // 899 s: still inside the lifetime
        assertEquals("Bearer tlqd_live", authorizer.bearer())
    }

    /**
     * A transient failure must not turn every request into a mint attempt for the rest of the
     * refresh window: the background reissue pauses, while a request whose token has expired
     * still gets its mint.
     */
    @Test
    fun transientReissueFailurePausesBackgroundAttempts() = runBlocking {
        val clock = FakeClock()
        val transport = StubTransport()
        transport.enqueueDeviceToken(token = "tlqd_live", expiresIn = 900)
        val authorizer = TestFixtures.authorizer(transport, clock)

        authorizer.prepare()
        clock.advance(800.seconds)
        transport.enqueue("""{"error":"overloaded"}""", status = 503)
        assertEquals("Bearer tlqd_live", authorizer.bearer())
        waitUntil { transport.sent.size == 2 }
        // The failure is installed a moment after the transport answers.
        delay(50)

        clock.advance(2.seconds)
        repeat(5) { assertEquals("Bearer tlqd_live", authorizer.bearer()) }
        delay(50)
        assertEquals("five requests inside the pause must not mint five times", 2, transport.sent.size)

        clock.advance(5.seconds)
        transport.enqueueDeviceToken(token = "tlqd_next", expiresIn = 900)
        assertEquals("still served while the retry runs", "Bearer tlqd_live", authorizer.bearer())
        assertTrue("after the pause the reissue is tried again", waitUntil { authorizer.bearer() == "Bearer tlqd_next" })
    }

    /** Once the token has actually expired, the request waits for the mint. */
    @Test
    fun expiredTokenWaitsForAFreshOne() = runBlocking {
        val clock = FakeClock()
        val transport = StubTransport()
        transport.enqueueDeviceToken(token = "tlqd_old", expiresIn = 900)
        transport.enqueueDeviceToken(token = "tlqd_new", expiresIn = 900)
        val authorizer = TestFixtures.authorizer(transport, clock)

        authorizer.prepare()
        clock.advance(901.seconds)

        assertEquals("Bearer tlqd_new", authorizer.bearer())
        assertEquals(2, transport.sent.size)
    }

    /** A refusal during a background reissue must not lock out the token that still works — only the next mint after expiry sees the cooldown. */
    @Test
    fun refusedReissueDoesNotBlockTheLiveToken() = runBlocking {
        val clock = FakeClock()
        val transport = StubTransport()
        transport.enqueueDeviceToken(token = "tlqd_live", expiresIn = 900)
        val authorizer = TestFixtures.authorizer(transport, clock)

        authorizer.prepare()
        clock.advance(800.seconds)
        transport.enqueue("""{"detail":"Device token issuance is not enabled"}""", status = 403)
        assertEquals("Bearer tlqd_live", authorizer.bearer())
        waitUntil { transport.sent.size == 2 }
        delay(50)

        clock.advance(50.seconds) // 850 s: live, and inside the 60 s cooldown
        assertEquals("Bearer tlqd_live", authorizer.bearer())
        delay(50)
        assertEquals("no second attempt during the cooldown", 2, transport.sent.size)

        clock.advance(60.seconds) // 910 s: expired, cooldown over
        transport.enqueueDeviceToken(token = "tlqd_after", expiresIn = 900)
        assertEquals("Bearer tlqd_after", authorizer.bearer())
    }

    /** Two requests race past expiry and both see a 401. The second report arrives after the first minted a replacement: it must survive. */
    @Test
    fun stale401DoesNotDropTheFreshToken() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken(token = "tlqd_old")
        transport.enqueueDeviceToken(token = "tlqd_new")
        val authorizer = TestFixtures.authorizer(transport, FakeClock())

        authorizer.prepare()
        authorizer.invalidate("Bearer tlqd_old")
        assertEquals("Bearer tlqd_new", authorizer.bearer())

        authorizer.invalidate("Bearer tlqd_old") // late report
        assertEquals("Bearer tlqd_new", authorizer.bearer())
        assertEquals("the fresh token must not be minted again", 2, transport.sent.size)
    }

    // endregion

    // region Device clock

    /**
     * A phone with automatic time switched off signs with the wrong time and gets 401 forever.
     * The response's own Date header says what time it is: the mint is signed once more with that,
     * and the correction sticks.
     */
    @Test
    fun skewedClockIsCorrectedFromTheServersDate() = runBlocking {
        val transport = StubTransport()
        transport.enqueue(
            """{"detail":"timestamp outside the acceptance window"}""",
            status = 401,
            headers = mapOf("Date" to httpDate(Instant.now().plusSeconds(600))),
        )
        transport.enqueueDeviceToken(token = "tlqd_corrected")
        val logs = LogCollector()
        val authorizer = TestFixtures.authorizer(transport, FakeClock(), logHandler = logs::append)

        assertEquals("Bearer tlqd_corrected", authorizer.bearer())
        assertEquals(2, transport.sent.size)

        val first = transport.sent[0].header("X-Client-Timestamp")!!.toLong()
        val second = transport.sent[1].header("X-Client-Timestamp")!!.toLong()
        assertEquals("the retry is stamped with the server's time", 600.0, (second - first).toDouble(), 3.0)
        assertNotEquals(transport.sent[0].header("X-Client-Nonce"), transport.sent[1].header("X-Client-Nonce"))
        assertTrue(logs.all.any { it.level == TalqynLogEvent.Level.Info && it.message.contains("clock") })

        // The next mint signs with the corrected time from its first attempt.
        transport.enqueueDeviceToken(token = "tlqd_next")
        authorizer.setIdentity(TalqynDeviceIdentity.User(UUID.randomUUID()))
        assertEquals("Bearer tlqd_next", authorizer.bearer())
        val third = transport.sent[2].header("X-Client-Timestamp")!!.toLong()
        assertEquals(600.0, (third - first).toDouble(), 3.0)
    }

    /** The correction outlives the process: the next launch signs correctly from its first attempt. */
    @Test
    fun clockCorrectionIsPersisted() = runBlocking {
        val store = TalqynInMemoryUserIdStore()
        val transport = StubTransport()
        transport.enqueue(
            """{"detail":"timestamp outside the acceptance window"}""",
            status = 401,
            headers = mapOf("Date" to httpDate(Instant.now().minusSeconds(900))),
        )
        transport.enqueueDeviceToken(token = "tlqd_first")
        val first = TestFixtures.authorizer(transport, FakeClock(), store = store)
        assertEquals("Bearer tlqd_first", first.bearer())
        assertEquals(-900.0, store.loadClockOffset()!!.toDouble(DurationUnit.SECONDS), 3.0)

        // "Next launch": a fresh authorizer over the same store.
        val relaunch = StubTransport()
        relaunch.enqueueDeviceToken(token = "tlqd_relaunch")
        val second = TestFixtures.authorizer(relaunch, FakeClock(), store = store)
        assertEquals("Bearer tlqd_relaunch", second.bearer())
        assertEquals("no rejected mint to learn the skew again", 1, relaunch.sent.size)
        val stamp = relaunch.sent[0].header("X-Client-Timestamp")!!.toDouble()
        assertEquals(System.currentTimeMillis() / 1000.0 - 900, stamp, 3.0)
    }

    /** A clock that was fixed since the last launch: the stale correction earns one 401, the skew is measured again, and the store is cleared. */
    @Test
    fun fixedClockClearsTheStoredCorrection() = runBlocking {
        val store = TalqynInMemoryUserIdStore()
        store.saveClockOffset(600.seconds)
        val transport = StubTransport()
        transport.enqueue(
            """{"detail":"timestamp outside the acceptance window"}""",
            status = 401,
            headers = mapOf("Date" to httpDate(Instant.now())),
        )
        transport.enqueueDeviceToken(token = "tlqd_fixed")
        val authorizer = TestFixtures.authorizer(transport, FakeClock(), store = store)

        assertEquals("Bearer tlqd_fixed", authorizer.bearer())
        assertEquals(2, transport.sent.size)
        assertNull("an offset of zero is forgotten, not stored", store.loadClockOffset())
    }

    /** A 401 with the clocks in agreement is about the key, not the time. */
    @Test
    fun unauthorizedWithAgreeingClocksIsNotRetried() = runBlocking {
        val transport = StubTransport()
        transport.enqueue(
            """{"detail":"Invalid client key"}""",
            status = 401,
            headers = mapOf("Date" to httpDate(Instant.now())),
        )
        val authorizer = TestFixtures.authorizer(transport, FakeClock())

        expectFailure<TalqynException.Unauthorized>("expected an authorization failure") { authorizer.headers() }
        assertEquals(1, transport.sent.size)
    }

    // endregion

    private suspend fun TalqynDeviceTokenAuthorizer.bearer(): String? = headers()["Authorization"]
}
