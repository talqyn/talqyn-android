package com.talqyn.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A stub transport: a queue of canned responses and a record of what the SDK actually sent. */
internal class StubTransport : TalqynHttpTransport {
    class Sent(val request: TalqynHttpRequest) {
        /** The raw path: the trailing slash of instant search is part of the endpoint address. */
        val path: String get() = URI(request.url).rawPath ?: ""
        val query: String? get() = URI(request.url).rawQuery
        val body: ByteArray? get() = request.body
        val bodyText: String get() = body?.toString(Charsets.UTF_8) ?: ""
        val bodyJson: Map<*, *>
            get() = body?.let { bytes ->
                try {
                    TalqynJson.parse(bytes) as? Map<*, *>
                } catch (e: TalqynJsonException) {
                    null
                }
            } ?: emptyMap<String, Any?>()

        fun header(field: String): String? = request.header(field)
    }

    private sealed interface Canned {
        class Body(val body: ByteArray, val response: TalqynHttpResponse) : Canned
        class Lines(val lines: List<String>, val response: TalqynHttpResponse) : Canned
        class Failure(val error: Exception) : Canned
    }

    private val lock = Any()
    private val responses = ArrayDeque<Canned>()
    private val streams = ArrayDeque<Canned>()
    private val recorded = ArrayList<Sent>()

    val sent: List<Sent> get() = synchronized(lock) { recorded.toList() }

    /** Forgets what was sent so far — the mint a fixture performed on the caller's behalf. */
    fun clearSent() {
        synchronized(lock) { recorded.clear() }
    }

    fun enqueue(json: String, status: Int = 200, headers: Map<String, String> = emptyMap()) {
        synchronized(lock) { responses.addLast(Canned.Body(json.toByteArray(), TalqynHttpResponse(status, headers))) }
    }

    fun enqueue(error: Exception) {
        synchronized(lock) { responses.addLast(Canned.Failure(error)) }
    }

    /** Puts a response **ahead** of everything queued so far: for a mint a fixture performs first. */
    fun prepend(json: String, status: Int = 200) {
        synchronized(lock) { responses.addFirst(Canned.Body(json.toByteArray(), TalqynHttpResponse(status))) }
    }

    fun enqueueStream(lines: List<String>, status: Int = 200, headers: Map<String, String> = emptyMap()) {
        synchronized(lock) { streams.addLast(Canned.Lines(lines, TalqynHttpResponse(status, headers))) }
    }

    /** A stream that never opens: the request failed before any answer came. */
    fun enqueueStream(error: Exception) {
        synchronized(lock) { streams.addLast(Canned.Failure(error)) }
    }

    fun enqueueDeviceToken(token: String = "tlqd_test", expiresIn: Int = 900, userId: String? = null) {
        val user = userId?.let { "\"user_id\":\"$it\"," } ?: ""
        enqueue("""{"token":"$token",$user"expires_at":"2026-08-26T12:15:00Z","expires_in":$expiresIn}""")
    }

    private fun take(queue: ArrayDeque<Canned>, request: TalqynHttpRequest): Canned = synchronized(lock) {
        recorded.add(Sent(request))
        queue.removeFirstOrNull() ?: throw IllegalStateException("the stub queue is empty")
    }

    override suspend fun send(request: TalqynHttpRequest): TalqynHttpResult =
        when (val canned = take(responses, request)) {
            is Canned.Body -> TalqynHttpResult(canned.body, canned.response)
            is Canned.Failure -> throw canned.error
            is Canned.Lines -> throw IllegalStateException("a stream was queued for a unary request")
        }

    override suspend fun stream(request: TalqynHttpRequest): TalqynHttpStream =
        when (val canned = take(streams, request)) {
            is Canned.Lines -> TalqynHttpStream(canned.response, flowOf(*canned.lines.toTypedArray()))
            is Canned.Failure -> throw canned.error
            is Canned.Body -> throw IllegalStateException("a body was queued for a stream")
        }
}

internal object TestFixtures {
    const val SECRET = "s3cr3t-client-key-value-32-chars-long"

    /** The host the tests address: the SDK has none of its own, so every fixture names one. */
    const val BASE_URL = "https://api.example.com"

    fun client(
        credentials: TalqynDeviceTokenCredentials = deviceToken(),
        transport: TalqynHttpTransport,
        baseUrl: String = BASE_URL,
        retryPolicy: TalqynRetryPolicy = TalqynRetryPolicy.None,
        cityId: String? = null,
        userIdStore: TalqynUserIdStore = TalqynInMemoryUserIdStore(),
        logHandler: ((TalqynLogEvent) -> Unit)? = null,
    ): Talqyn = Talqyn(
        TalqynConfiguration(
            credentials = credentials,
            baseUrl = baseUrl,
            defaultCityId = cityId,
            retryPolicy = retryPolicy,
            userIdStore = userIdStore,
            transport = transport,
            logHandler = logHandler,
        ),
    )

    /** A client that already holds a device token, with the mint forgotten by the transport. */
    suspend fun preparedClient(
        transport: StubTransport,
        baseUrl: String = BASE_URL,
        retryPolicy: TalqynRetryPolicy = TalqynRetryPolicy.None,
        cityId: String? = null,
    ): Talqyn {
        // Ahead of the queue: tests line their responses up before the client exists.
        transport.prepend("""{"token":"tlqd_test","expires_at":"2026-08-26T12:15:00Z","expires_in":900}""")
        val talqyn = client(transport = transport, baseUrl = baseUrl, retryPolicy = retryPolicy, cityId = cityId)
        talqyn.prepare()
        transport.clearSent()
        return talqyn
    }

    fun deviceToken(identity: TalqynDeviceIdentity = TalqynDeviceIdentity.Guest): TalqynDeviceTokenCredentials =
        TalqynDeviceTokenCredentials(
            storefront = "myshop",
            clientKeyId = "ck_3f9a1c2b7d4e",
            clientSecret = SECRET,
            identity = identity,
        )

    /** The device-token authorizer on its own, with a clock the test controls. */
    fun authorizer(
        transport: StubTransport,
        clock: FakeClock,
        identity: TalqynDeviceIdentity = TalqynDeviceIdentity.Guest,
        store: TalqynUserIdStore = TalqynInMemoryUserIdStore(),
        logHandler: ((TalqynLogEvent) -> Unit)? = null,
    ): TalqynDeviceTokenAuthorizer {
        val builder = TalqynRequestBuilder(BASE_URL, "v1", 30.seconds)
        return TalqynDeviceTokenAuthorizer(
            credentials = deviceToken(identity),
            store = store,
            minter = TalqynDeviceTokenMinter(builder, transport, logHandler),
            logHandler = logHandler,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            now = clock::nowMillis,
        )
    }
}

/** A clock that moves only when told to. */
internal class FakeClock {
    @Volatile
    private var current = System.currentTimeMillis()

    fun nowMillis(): Long = current

    fun advance(by: Duration) {
        current += by.inWholeMilliseconds
    }
}

/** Collects log events from the SDK. */
internal class LogCollector {
    private val lock = Any()
    private val events = ArrayList<TalqynLogEvent>()

    val all: List<TalqynLogEvent> get() = synchronized(lock) { events.toList() }

    fun append(event: TalqynLogEvent) {
        synchronized(lock) { events.add(event) }
    }
}

/**
 * A transport whose stream delivers lines with a delay, like a network does. A [StubTransport]
 * yields everything at once, which hides every question about what happens **during** a turn —
 * cancellation above all.
 */
internal class SlowStreamTransport : TalqynHttpTransport {
    val responses = StubTransport()

    @Volatile
    private var lines: List<String> = emptyList()

    /** Whether the stream handed out was cancelled or drained. */
    @Volatile
    var wasTerminated: Boolean = false
        private set

    fun enqueueStream(lines: List<String>) {
        this.lines = lines
    }

    override suspend fun send(request: TalqynHttpRequest): TalqynHttpResult = responses.send(request)

    override suspend fun stream(request: TalqynHttpRequest): TalqynHttpStream {
        val queued = lines
        return TalqynHttpStream(
            TalqynHttpResponse(200),
            flow {
                try {
                    for (line in queued) {
                        delay(20)
                        emit(line)
                    }
                } finally {
                    wasTerminated = true
                }
            },
        )
    }
}

/** Runs [block] and returns what it threw, failing when it threw nothing or something else. */
internal inline fun <reified T : Throwable> expectFailure(message: String = "expected a failure", block: () -> Unit): T {
    val thrown = try {
        block()
        null
    } catch (e: Throwable) {
        e
    }
    if (thrown == null) throw AssertionError(message)
    if (thrown !is T) throw AssertionError("expected ${T::class.java.simpleName}, got $thrown", thrown)
    return thrown
}

/** Polls a condition for up to a second: background work has no handle to await. */
internal suspend fun waitUntil(condition: suspend () -> Boolean): Boolean {
    repeat(100) {
        val met = try {
            condition()
        } catch (e: Exception) {
            false
        }
        if (met) return true
        delay(10)
    }
    return false
}

internal fun httpDate(instant: Instant): String =
    DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(instant, ZoneOffset.UTC))

internal class StubIOException(message: String) : IOException(message)
