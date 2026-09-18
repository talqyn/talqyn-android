package com.talqyn.consultant

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
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
import java.net.URI
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * The tests' main thread. The conversation is main-thread bound, like a screen: the test body
 * and the conversation's coroutines share this one thread, and polling with `delay` hands it
 * over, the way a main-actor test does.
 *
 * Dispatched the way the conversation's default, `Dispatchers.Main.immediate`, is: work started
 * on this thread runs at once, work arriving from another thread is queued. A queue-only
 * dispatcher would hide every difference between "starts now" and "starts on the next pass" —
 * and how quick taps on a rating coalesce depends on exactly that.
 */
internal object TestMain {
    private const val THREAD = "test-main"

    private val queue = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD).apply { isDaemon = true }
    }.asCoroutineDispatcher()

    val dispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = Thread.currentThread().name != THREAD

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.dispatch(context, block)
        }
    }

    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}

internal fun mainTest(block: suspend CoroutineScope.() -> Unit) {
    runBlocking(TestMain.dispatcher) {
        withTimeout(30_000) { block() }
    }
}

internal suspend fun waitUntil(message: String, timeoutMillis: Long = 2_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) fail(message)
        delay(5)
    }
}

/** A stub transport: a queue of canned responses and a record of what the SDK actually sent. */
internal class StubTransport : TalqynHttpTransport {
    class Sent(val request: TalqynHttpRequest) {
        /** Decoded, trailing slash kept: it is part of the instant-search address. */
        val path: String get() = URI(request.url).path.orEmpty()
        val query: String? get() = URI(request.url).rawQuery
        val method: String get() = request.method
        val bodyText: String get() = request.body?.toString(Charsets.UTF_8).orEmpty()
        val bodyJson: Map<*, *> get() = if (bodyText.isEmpty()) emptyMap<String, Any?>() else TestJson.parse(bodyText) as Map<*, *>
    }

    private val lock = Any()
    private val responses = ArrayDeque<Result<TalqynHttpResult>>()
    private val streams = ArrayDeque<Result<Pair<List<String>, TalqynHttpResponse>>>()
    private val recorded = ArrayList<Sent>()

    val sent: List<Sent> get() = synchronized(lock) { recorded.toList() }

    /** Forgets what was sent so far — the mint a fixture performed — so the test's own requests start at index zero. */
    fun clearSent() {
        synchronized(lock) { recorded.clear() }
    }

    fun enqueue(json: String, status: Int = 200, headers: Map<String, String> = emptyMap()) {
        synchronized(lock) {
            responses.addLast(Result.success(TalqynHttpResult(json.toByteArray(), TalqynHttpResponse(status, headers))))
        }
    }

    fun enqueue(error: Throwable) {
        synchronized(lock) { responses.addLast(Result.failure(error)) }
    }

    /** Puts a response **ahead** of everything queued so far: for a mint a fixture performs. */
    fun prepend(json: String, status: Int = 200) {
        synchronized(lock) {
            responses.addFirst(Result.success(TalqynHttpResult(json.toByteArray(), TalqynHttpResponse(status))))
        }
    }

    fun enqueueStream(lines: List<String>, status: Int = 200) {
        synchronized(lock) { streams.addLast(Result.success(lines to TalqynHttpResponse(status))) }
    }

    fun enqueueDeviceToken(token: String = "tlqd_test", expiresIn: Int = 900, userId: String? = null) {
        val user = userId?.let { "\"user_id\":\"$it\"," }.orEmpty()
        enqueue("""{"token":"$token",$user"expires_at":"2026-08-26T12:15:00Z","expires_in":$expiresIn}""")
    }

    override suspend fun send(request: TalqynHttpRequest): TalqynHttpResult = synchronized(lock) {
        recorded.add(Sent(request))
        responses.removeFirstOrNull() ?: Result.failure(IOException("stub response queue is empty"))
    }.getOrThrow()

    override suspend fun stream(request: TalqynHttpRequest): TalqynHttpStream {
        val (lines, response) = synchronized(lock) {
            recorded.add(Sent(request))
            streams.removeFirstOrNull() ?: Result.failure(IOException("stub stream queue is empty"))
        }.getOrThrow()
        return TalqynHttpStream(response, lines.asFlow())
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
    private const val TOKEN = """{"token":"tlqd_test","expires_at":"2026-08-26T12:15:00Z","expires_in":900}"""

    fun deviceToken(identity: TalqynDeviceIdentity = TalqynDeviceIdentity.Guest): TalqynDeviceTokenCredentials =
        TalqynDeviceTokenCredentials(
            storefront = "myshop",
            clientKeyId = "ck_3f9a1c2b7d4e",
            clientSecret = "s3cr3t-client-key-value-32-chars-long",
            identity = identity,
        )

    fun client(
        transport: TalqynHttpTransport,
        credentials: TalqynDeviceTokenCredentials = deviceToken(),
        retryPolicy: TalqynRetryPolicy = TalqynRetryPolicy.None,
        cityId: String? = null,
    ): Talqyn = Talqyn(
        TalqynConfiguration(
            credentials = credentials,
            baseUrl = "https://api.example.com",
            defaultCityId = cityId,
            retryPolicy = retryPolicy,
            userIdStore = TalqynInMemoryUserIdStore(),
            transport = transport,
        ),
    )

    /** A client that already holds a device token, with the mint forgotten by the transport. */
    suspend fun preparedClient(transport: StubTransport, retryPolicy: TalqynRetryPolicy = TalqynRetryPolicy.None): Talqyn {
        // Ahead of the queue: tests line their responses up before the client exists.
        transport.prepend(TOKEN)
        val talqyn = client(transport, retryPolicy = retryPolicy)
        talqyn.prepare()
        transport.clearSent()
        return talqyn
    }
}

/** Just enough JSON to read back what the SDK sent: maps, lists, strings, longs, doubles, booleans, null. */
internal object TestJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skip()
        check(reader.index == text.length) { "trailing characters in $text" }
        return value
    }

    private class Reader(val text: String) {
        var index = 0

        fun skip() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun value(): Any? {
            skip()
            return when (text[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        fun objectValue(): Map<String, Any?> {
            index++
            val map = LinkedHashMap<String, Any?>()
            skip()
            if (text[index] == '}') {
                index++
                return map
            }
            do {
                skip()
                val key = string()
                skip()
                check(text[index++] == ':')
                map[key] = value()
                skip()
            } while (text[index++] == ',')
            return map
        }

        fun arrayValue(): List<Any?> {
            index++
            val list = ArrayList<Any?>()
            skip()
            if (text[index] == ']') {
                index++
                return list
            }
            do {
                list.add(value())
                skip()
            } while (text[index++] == ',')
            return list
        }

        fun string(): String {
            check(text[index++] == '"')
            val out = StringBuilder()
            while (true) {
                when (val char = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> when (val escaped = text[index++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            out.append(text.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> out.append(escaped)
                    }
                    else -> out.append(char)
                }
            }
        }

        fun literal(word: String, value: Any?): Any? {
            check(text.startsWith(word, index))
            index += word.length
            return value
        }

        fun number(): Any {
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] in "-+.eE")) index++
            val raw = text.substring(start, index)
            return raw.toLongOrNull() ?: raw.toDouble()
        }
    }
}
