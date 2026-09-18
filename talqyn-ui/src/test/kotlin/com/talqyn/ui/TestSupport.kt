package com.talqyn.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail
import com.talqyn.sdk.Talqyn
import com.talqyn.sdk.TalqynConfiguration
import com.talqyn.sdk.TalqynDeviceIdentity
import com.talqyn.sdk.TalqynDeviceTokenCredentials
import com.talqyn.sdk.TalqynHttpRequest
import com.talqyn.sdk.TalqynHttpResponse
import com.talqyn.sdk.TalqynHttpResult
import com.talqyn.sdk.TalqynHttpStream
import com.talqyn.sdk.TalqynHttpTransport
import com.talqyn.sdk.TalqynInMemoryUserIdStore
import com.talqyn.sdk.TalqynRetryPolicy
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The tests' main thread. The conversation is main-thread bound, like a screen: the test body and
 * the conversation's coroutines share this one thread, and polling with `delay` hands it over.
 */
internal object TestMain {
    val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "test-main").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}

internal fun mainTest(block: suspend CoroutineScope.() -> Unit) {
    runBlocking(TestMain.dispatcher) {
        withTimeout(30_000) { block() }
    }
}

internal suspend fun waitUntil(message: String, timeoutMillis: Long = 3_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) fail(message)
        delay(5)
    }
}

/** One server-sent event as the stream carries it. */
internal fun sse(name: String, data: String): List<String> = listOf("event: $name", "data: $data", "")

/** A stub transport: canned responses and streams, in order. */
internal class StubTransport : TalqynHttpTransport {
    private val lock = Any()
    private val responses = ArrayDeque<TalqynHttpResult>()
    private val streams = ArrayDeque<List<String>>()

    fun enqueue(json: String, status: Int = 200) {
        synchronized(lock) { responses.addLast(TalqynHttpResult(json.toByteArray(), TalqynHttpResponse(status))) }
    }

    fun prepend(json: String) {
        synchronized(lock) { responses.addFirst(TalqynHttpResult(json.toByteArray(), TalqynHttpResponse(200))) }
    }

    fun enqueueStream(lines: List<String>) {
        synchronized(lock) { streams.addLast(lines) }
    }

    fun enqueueDeviceToken() = enqueue(TestFixtures.TOKEN)

    override suspend fun send(request: TalqynHttpRequest): TalqynHttpResult =
        synchronized(lock) { responses.removeFirstOrNull() } ?: throw IOException("stub response queue is empty")

    override suspend fun stream(request: TalqynHttpRequest): TalqynHttpStream {
        val lines = synchronized(lock) { streams.removeFirstOrNull() } ?: throw IOException("stub stream queue is empty")
        return TalqynHttpStream(TalqynHttpResponse(200), lines.asFlow())
    }
}

/**
 * A transport whose stream delivers lines with a delay, like a network does: what happens during a
 * turn — cancellation above all — is only visible while the turn is still running.
 */
internal class SlowStreamTransport : TalqynHttpTransport {
    val responses = StubTransport()

    @Volatile
    var wasTerminated = false
        private set

    @Volatile
    private var lines: List<String> = emptyList()

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

internal object TestFixtures {
    const val TOKEN = """{"token":"tlqd_test","expires_at":"2026-08-26T12:15:00Z","expires_in":900}"""

    fun client(transport: TalqynHttpTransport): Talqyn = Talqyn(
        TalqynConfiguration(
            credentials = TalqynDeviceTokenCredentials(
                storefront = "myshop",
                clientKeyId = "ck_3f9a1c2b7d4e",
                clientSecret = "s3cr3t-client-key-value-32-chars-long",
                identity = TalqynDeviceIdentity.Guest,
            ),
            baseUrl = "https://api.example.com",
            retryPolicy = TalqynRetryPolicy.None,
            userIdStore = TalqynInMemoryUserIdStore(),
            transport = transport,
        ),
    )

    /** A client that already holds a device token: for tests about turns, not about the token. */
    suspend fun preparedClient(transport: StubTransport): Talqyn {
        transport.prepend(TOKEN)
        return client(transport).also { it.prepare() }
    }
}
