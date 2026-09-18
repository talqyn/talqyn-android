package com.talqyn.sdk

import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The moments between the happy paths: a shopper changing mid-request, callers walking away, storage and logs that fail. */
class RobustnessTest {
    private val emptySearch = """{"search_id":"s","query":"x","locale":"ru","total":0,"results":[]}"""
    private val emptyListing = """{"query":"x","locale":"ru","offset":0,"limit":20,"sort":"relevance","total":0,"results":[]}"""

    private fun token(name: String) = TalqynHttpResult(
        """{"token":"$name","expires_at":"2026-08-26T12:15:00Z","expires_in":900}""".toByteArray(),
        TalqynHttpResponse(200),
    )

    private fun transport(
        onSend: suspend (TalqynHttpRequest) -> TalqynHttpResult,
        onStream: suspend (TalqynHttpRequest) -> TalqynHttpStream = { throw UnsupportedOperationException() },
    ): TalqynHttpTransport = object : TalqynHttpTransport {
        override suspend fun send(request: TalqynHttpRequest): TalqynHttpResult = onSend(request)
        override suspend fun stream(request: TalqynHttpRequest): TalqynHttpStream = onStream(request)
    }

    private fun authorizerOver(transport: TalqynHttpTransport, clock: FakeClock) = TalqynDeviceTokenAuthorizer(
        credentials = TestFixtures.deviceToken(),
        store = TalqynInMemoryUserIdStore(),
        minter = TalqynDeviceTokenMinter(TalqynRequestBuilder(TestFixtures.BASE_URL, "v1", 30.seconds), transport, null),
        logHandler = null,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        now = clock::nowMillis,
    )

    // region The shopper and the token

    /**
     * A request that waits for the token of a shopper who is no longer the shopper is not sent. It gets
     * an answer that says so — not a bare cancellation, which would end a caller nobody cancelled silently.
     */
    @Test
    fun aRequestWaitingForThePreviousShoppersTokenFailsWithIdentityChanged() = runBlocking {
        val mintStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sent = CopyOnWriteArrayList<String>()
        val talqyn = TestFixtures.client(
            transport = transport(onSend = { request ->
                sent.add(request.url)
                mintStarted.complete(Unit)
                release.await()
                token("tlqd_previous")
            }),
        )

        val waiting = async(Dispatchers.Default) { runCatching { talqyn.search.search("iphone") } }
        mintStarted.await()
        talqyn.setIdentity(TalqynDeviceIdentity.User(UUID.randomUUID()))
        release.complete(Unit)

        val failure = waiting.await().exceptionOrNull()
        assertTrue("got $failure", failure is TalqynException.IdentityChanged)
        assertTrue("the search itself never went out: $sent", sent.none { it.contains("/search/") })
    }

    /**
     * A mint every caller walked away from is installed when it finishes, with the time it finished.
     * Left for the next caller, it would be installed as if it had just been issued — and served
     * after it had expired.
     */
    @Test
    fun aMintEveryCallerWalkedAwayFromIsInstalledWhenItFinishes() = runBlocking {
        val clock = FakeClock()
        val mints = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val authorizer = authorizerOver(
            transport(onSend = {
                val number = mints.incrementAndGet()
                if (number == 1) release.await()
                token("tlqd_$number")
            }),
            clock,
        )

        val caller = launch(Dispatchers.Default) { authorizer.headers() }
        assertTrue(waitUntil { mints.get() == 1 })
        caller.cancelAndJoin()
        release.complete(Unit)
        delay(200)

        clock.advance(901.seconds)
        assertEquals("an expired token is not served as fresh", "Bearer tlqd_2", authorizer.headers()["Authorization"])
        assertEquals(2, mints.get())
    }

    @Test
    fun identityChangedIsNeitherRetryableNorAnAnswerFromTheServer() {
        val failure = TalqynException.IdentityChanged()
        assertFalse(failure.isRetryable)
        assertNull(failure.statusCode)
        assertNull(failure.requestId)
        assertEquals(TalqynException.IdentityChanged(), failure)
    }

    /** The shopper id is all that keeps a shopper's history theirs: it stays out of every printout of the configuration. */
    @Test
    fun theConfigurationPrintsNeitherTheSecretNorTheShopperId() {
        val id = UUID.randomUUID()
        val credentials = TestFixtures.deviceToken(TalqynDeviceIdentity.User(id))
        for (text in listOf(credentials.toString(), TalqynConfiguration(credentials = credentials, baseUrl = TestFixtures.BASE_URL).toString(), TalqynDeviceIdentity.User(id).toString())) {
            assertFalse(text, text.contains(id.toString()))
            assertFalse(text, text.contains(TestFixtures.SECRET))
        }
    }

    // endregion

    // region Storage

    /** An id that cannot be read is not an id that does not exist: answered with a new UUID, the shopper's history would be orphaned for good. */
    @Test
    fun aStoreFileThatCannotBeReadIsNotReplacedWithANewShopper() = runBlocking {
        val directory = Files.createTempDirectory("talqyn-store").toFile()
        try {
            val file = File(directory, "identity.properties")
            file.mkdirs() // there, and unreadable as a file
            val store = TalqynFileUserIdStore(file)
            expectFailure<IOException> { store.loadUserId() }

            val stub = StubTransport()
            val talqyn = TestFixtures.client(
                credentials = TestFixtures.deviceToken(TalqynDeviceIdentity.PersistentAnonymous),
                transport = stub,
                userIdStore = store,
            )
            expectFailure<TalqynException.Transport>("a store that cannot be read fails the request") { talqyn.search.search("iphone") }
            assertTrue("nothing was sent under an invented shopper", stub.sent.isEmpty())
            assertTrue("the file is left as it was", file.isDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Two stores over one file — two clients, or two processes — do not erase each other's values. */
    @Test
    fun twoStoresOverOneFileKeepEachOthersValues() {
        val directory = Files.createTempDirectory("talqyn-store").toFile()
        try {
            val file = File(directory, "identity.properties")
            val first = TalqynFileUserIdStore(file)
            val second = TalqynFileUserIdStore(file)
            assertNull("read before the other one writes", second.loadUserId())

            val id = UUID.randomUUID()
            first.saveUserId(id)
            second.saveClockOffset(30.seconds)

            val reopened = TalqynFileUserIdStore(file)
            assertEquals(id, reopened.loadUserId())
            assertEquals(30.seconds, reopened.loadClockOffset())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** An app that already keeps an anonymous id adopts it as it is, whatever case it was written in. */
    @Test
    fun theSharedPreferencesStoreAdoptsAnExistingId() {
        val preferences = FakePreferences()
        val existing = UUID.randomUUID()
        preferences.values["consultant_anonymous_id"] = existing.toString().uppercase()
        val store = TalqynSharedPreferencesUserIdStore(preferences, key = "consultant_anonymous_id")
        assertEquals(existing, store.loadUserId())

        store.saveClockOffset((-90).seconds)
        assertEquals((-90).seconds, store.loadClockOffset())
        assertEquals("kept under the key's own suffix", -90_000L, preferences.values["consultant_anonymous_id.clock_offset"])

        store.saveUserId(null)
        assertNull(store.loadUserId())
        preferences.values["consultant_anonymous_id"] = "1-2-3-4-5"
        assertNull("an id that is not a UUID reads as absent", store.loadUserId())
    }

    // endregion

    // region Transports and logs of the app's own

    /** `withTimeout` inside a transport ends in a `CancellationException`; the caller is still running, so it is a failed request. */
    @Test
    fun aTimeoutInsideACustomTransportIsAFailedRequest() = runBlocking {
        val attempts = AtomicInteger()
        val talqyn = TestFixtures.client(
            transport = transport(onSend = { request ->
                if (request.url.endsWith("/consultant/token")) return@transport token("tlqd_test")
                attempts.incrementAndGet()
                withTimeout(10) {
                    delay(1_000)
                    throw AssertionError("the timeout should have fired")
                }
            }),
            retryPolicy = TalqynRetryPolicy(maxRetries = 1, baseDelay = 1.milliseconds),
        )
        val error = expectFailure<TalqynException.Transport>("expected a transport failure") { talqyn.search.search("iphone") }
        assertTrue("got ${error.cause}", error.cause is CancellationException)
        assertEquals("repeated like any transport failure", 2, attempts.get())
    }

    /** A log handler that throws costs the log line: not the failure it describes. */
    @Test
    fun aThrowingLogHandlerDoesNotReplaceTheFailure() = runBlocking {
        val stub = StubTransport()
        stub.enqueueDeviceToken()
        stub.enqueue("not json")
        val talqyn = TestFixtures.client(transport = stub, logHandler = { throw IllegalStateException("a logger that wants the main thread") })
        expectFailure<TalqynException.Decoding> { talqyn.search.search("iphone") }
        Unit
    }

    /** …and not the process, when the line comes from background work with nobody to catch it. */
    @Test
    fun aThrowingLogHandlerDoesNotCrashFireAndForget() = runBlocking {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        try {
            val stub = StubTransport()
            stub.enqueueDeviceToken()
            stub.enqueue("""{"detail":"missing the 'events' scope"}""", status = 403)
            val calls = AtomicInteger()
            val talqyn = TestFixtures.client(transport = stub, logHandler = {
                calls.incrementAndGet()
                throw IllegalStateException("a logger that wants the main thread")
            })
            talqyn.events.track(TalqynCategoryClickEvent(categoryId = 42))
            assertTrue(waitUntil { stub.sent.size == 2 && calls.get() > 0 })
            delay(100)
            assertTrue("nothing reached the uncaught exception handler: $uncaught", uncaught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    // endregion

    // region Consultant and search

    /** Nothing follows `done`. A connection a proxy holds open after it must not keep the turn unfinished. */
    @Test
    fun readingStopsAtDoneEvenIfTheConnectionStaysOpen() = runBlocking {
        val closed = CompletableDeferred<Unit>()
        val talqyn = TestFixtures.client(
            transport = transport(
                onSend = { token("tlqd_test") },
                onStream = {
                    TalqynHttpStream(
                        TalqynHttpResponse(200),
                        flow {
                            try {
                                listOf("event: delta", "data: {\"text\":\"a\"}", "", "event: done", "data: {\"session_id\":\"s\"}", "")
                                    .forEach { emit(it) }
                                awaitCancellation()
                            } finally {
                                closed.complete(Unit)
                            }
                        },
                    )
                },
            ),
        )
        val events = withTimeout(2_000) { talqyn.consultant.ask("question").toList() }
        assertTrue(events.last().isTerminal)
        withTimeout(2_000) { closed.await() }
    }

    /** A turn goes out with the defaults of the moment it is sent, not of the moment its flow was made. */
    @Test
    fun aTurnIsSentWithTheDefaultsOfTheMomentItIsSent() = runBlocking {
        val stub = StubTransport()
        stub.enqueueStream(listOf("event: done", "data: {\"session_id\":\"s\"}", ""))
        val talqyn = TestFixtures.preparedClient(stub, cityId = "10")
        val turn = talqyn.consultant.ask("question")
        talqyn.setPlace(cityId = "47")
        turn.toList()
        assertEquals("47", stub.sent.last().bodyJson["city_id"])
    }

    /** The listing and its panel are built from one reading of the defaults: a city changed in between reaches neither. */
    @Test
    fun theListingAndItsPanelCountAgainstOneReadingOfTheDefaults() = runBlocking {
        val stub = StubTransport()
        stub.enqueueDeviceToken()
        stub.enqueue(emptyListing)
        stub.enqueue("""{"groups":[]}""")
        lateinit var talqyn: Talqyn
        talqyn = TestFixtures.client(
            transport = transport(onSend = { request ->
                // The shopper picks another city while the first request is out.
                if (request.url.contains("/search/")) talqyn.setPlace(cityId = "47")
                stub.send(request)
            }),
            cityId = "10",
        )
        talqyn.search.listingWithFilters(TalqynFullSearchQuery(query = "smartphone"))

        val bodies = stub.sent.filter { it.path.startsWith("/v1/search/") }.map { it.bodyJson["city_id"] }
        assertEquals(listOf("10", "10"), bodies)
    }

    /** The version in `X-Talqyn-SDK` is the version the build declares, not a constant typed next to it. */
    @Test
    fun theReportedVersionIsTheVersionTheSdkIsBuiltUnder() {
        assertEquals(System.getProperty("talqyn.version"), Talqyn.VERSION)
        assertEquals("android/${Talqyn.VERSION}", Talqyn.CLIENT_HEADER)
    }

    // endregion

    private class FakePreferences : SharedPreferences {
        val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)

        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val changes = HashMap<String, Any?>()
            private val removals = HashSet<String>()

            private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { changes[key.orEmpty()] = value }

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values)

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

            override fun remove(key: String?): SharedPreferences.Editor = apply { removals.add(key.orEmpty()) }

            override fun clear(): SharedPreferences.Editor = apply { removals.addAll(values.keys) }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                removals.forEach(values::remove)
                values.putAll(changes)
            }
        }
    }
}
