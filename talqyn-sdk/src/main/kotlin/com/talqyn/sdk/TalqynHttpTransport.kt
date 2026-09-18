package com.talqyn.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A request as the SDK hands it to a [TalqynHttpTransport].
 *
 * @property method The HTTP method: `GET`, `POST`, or `DELETE`.
 * @property url The absolute URL, already percent-encoded.
 * @property headers The request headers.
 * @property body The exact body bytes, or `null` for none. A mint body is signed:
 *   send these bytes as they are.
 * @property timeout How long to wait for the connection and for each read.
 */
public class TalqynHttpRequest(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String>,
    public val body: ByteArray?,
    public val timeout: Duration,
) {
    /** Returns a header value, matching the field name case-insensitively. */
    public fun header(field: String): String? =
        headers.entries.firstOrNull { it.key.equals(field, ignoreCase = true) }?.value

    override fun toString(): String = "TalqynHttpRequest($method $url)"
}

/**
 * The status line and headers of an HTTP response, without its body.
 *
 * @param headers The response headers in any casing; they are lowercased.
 */
public class TalqynHttpResponse(
    public val statusCode: Int,
    headers: Map<String, String> = emptyMap(),
) {
    /**
     * The response headers, keyed by **lowercased** field name: HTTP header names
     * are case-insensitive, and servers and proxies do not agree on casing. Prefer
     * [value] over reading this directly.
     */
    public val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }

    /** Returns a header value, matching the field name case-insensitively. */
    public fun value(field: String): String? = headers[field.lowercase()]

    /** Whether the status code is in the `2xx` range. */
    public val isSuccess: Boolean get() = statusCode in 200..299

    /**
     * The `Retry-After` header as a wait from now, in either form the header allows:
     * a delay in seconds, or an HTTP date. A date already in the past reads as zero —
     * "now" — rather than as a negative wait.
     */
    internal fun retryAfter(now: Instant = Instant.now()): Duration? {
        val raw = value("Retry-After")?.trim() ?: return null
        raw.toDoubleOrNull()?.let { seconds ->
            if (seconds.isNaN()) return null
            return seconds.coerceAtLeast(0.0).seconds
        }
        TalqynDates.parseHttp(raw)?.let { date ->
            return (date.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0).milliseconds
        }
        return null
    }

    override fun toString(): String = "TalqynHttpResponse($statusCode)"
}

/** A whole response: status, headers, and body. */
public class TalqynHttpResult(
    public val body: ByteArray,
    public val response: TalqynHttpResponse,
)

/**
 * A streamed response: status and headers at once, the body line by line as it arrives.
 *
 * @property lines The body's lines, terminators stripped. Collected once; cancelling
 *   the collection must close the connection.
 */
public class TalqynHttpStream(
    public val response: TalqynHttpResponse,
    public val lines: Flow<String>,
)

/**
 * The HTTP layer the SDK sends requests through.
 *
 * Implement this to route Talqyn traffic through a client of your own — OkHttp with
 * certificate pinning, a corporate proxy, a traffic logger such as Chucker — or to
 * stub the network in tests. Pass the implementation as
 * [TalqynConfiguration.transport]; the default is [TalqynUrlConnectionTransport]. An
 * implementation over a byte stream splits the streamed body with
 * [TalqynLineSplitter.lines]:
 *
 * ```kotlin
 * override suspend fun stream(request: TalqynHttpRequest): TalqynHttpStream {
 *     val call = client.newBuilder()
 *         .readTimeout(request.timeout.toJavaDuration()) // the SDK's streamTimeout, not OkHttp's own 10 s
 *         .build()
 *         .newCall(request.toOkHttp())
 *     val response = call.await()                        // an await that cancels the call when cancelled
 *     return TalqynHttpStream(
 *         TalqynHttpResponse(response.code, response.headers.toMap()),
 *         // `call.cancel()`, not `response.close()`: this runs on the cancelling thread while
 *         // another one reads the body, and only the call is safe to abort from there.
 *         TalqynLineSplitter.lines(response.body.byteStream()) { call.cancel() },
 *     )
 * }
 * ```
 *
 * The SDK calls a transport from several coroutines at once.
 */
public interface TalqynHttpTransport {
    /**
     * Performs a request and returns the whole body.
     *
     * The implementation must **not** throw on a non-`2xx` status: the SDK inspects
     * the status itself and decodes the error envelope from the body. Throw an
     * `IOException` when the request could not be completed. A timeout of the
     * implementation's own that ends in a `CancellationException` — `withTimeout` — is
     * read as a failed request too, as long as the caller itself is still running.
     */
    public suspend fun send(request: TalqynHttpRequest): TalqynHttpResult

    /**
     * Performs a request and returns its body line by line, as it arrives.
     *
     * Used for the consultant's `text/event-stream` responses. The status line and
     * headers must be returned **immediately**, before the first body line: the SDK
     * decides from the status whether this is a stream to parse or an error envelope
     * to read. On a failing status the lines should still carry the error body.
     *
     * Cancelling the collection of the lines must cancel the underlying request.
     */
    public suspend fun stream(request: TalqynHttpRequest): TalqynHttpStream
}

/**
 * The default [TalqynHttpTransport], over `HttpURLConnection`.
 *
 * No cache is used: search results are per-tenant and personal, and no intermediary
 * is allowed to store them. A cancelled coroutine disconnects its connection, so a
 * search overtaken by the next keystroke stops reading at once — and a request whose
 * coroutine was cancelled before it went out is never sent.
 *
 * The connection's own timeouts measure the gap between two reads, and a server that
 * trickles keep-alive comments never lets one expire. On top of them a request has a
 * limit on its whole life, ten times its timeout: five minutes for an ordinary request
 * and ten for a consultant stream under the default configuration — the ceiling the
 * twin SDK sets on its sessions.
 *
 * @param openConnection Opens a connection for a URL — the place for a proxy or a
 *   socket factory of your own.
 */
public class TalqynUrlConnectionTransport(
    private val openConnection: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
) : TalqynHttpTransport {

    override suspend fun send(request: TalqynHttpRequest): TalqynHttpResult {
        val connection = open(request)
        val deadline = TalqynDeadline(connection, request.timeout * DEADLINE_FACTOR)
        val release = {
            deadline.cancel()
            connection.disconnect()
        }
        try {
            return interruptible(release) {
                try {
                    request.body?.let { body -> connection.outputStream.use { it.write(body) } }
                    val status = connection.responseCode
                    val input = if (status >= 400) connection.errorStream else connection.inputStream
                    val body = input?.use { it.readBytes() } ?: ByteArray(0)
                    TalqynHttpResult(body, describe(connection, status))
                } catch (e: Throwable) {
                    connection.disconnect()
                    throw deadline.explain(e)
                }
            }
        } finally {
            deadline.cancel()
        }
    }

    override suspend fun stream(request: TalqynHttpRequest): TalqynHttpStream {
        val connection = open(request)
        // The limit runs on through the body: the body is what can go on for ever.
        val deadline = TalqynDeadline(connection, request.timeout * DEADLINE_FACTOR)
        val release = {
            deadline.cancel()
            connection.disconnect()
        }
        return interruptible(release) {
            try {
                request.body?.let { body -> connection.outputStream.use { it.write(body) } }
                val status = connection.responseCode
                val input: InputStream = (if (status >= 400) connection.errorStream else connection.inputStream)
                    ?: ByteArrayInputStream(ByteArray(0))
                TalqynHttpStream(describe(connection, status), TalqynLineSplitter.lines(deadline.guard(input), release))
            } catch (e: Throwable) {
                release()
                throw deadline.explain(e)
            }
        }
    }

    private fun open(request: TalqynHttpRequest): HttpURLConnection {
        val connection = openConnection(URL(request.url))
        val timeout = request.timeout.inWholeMilliseconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        connection.connectTimeout = timeout
        // The gap between two reads, not the whole response: a consultant turn
        // outlives an ordinary request, and a live stream must not be cut short.
        connection.readTimeout = timeout
        connection.requestMethod = request.method
        connection.useCaches = false
        connection.doInput = true
        for ((field, value) in request.headers) connection.setRequestProperty(field, value)
        val body = request.body
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
        }
        return connection
    }

    private fun describe(connection: HttpURLConnection, status: Int): TalqynHttpResponse {
        val headers = LinkedHashMap<String, String>()
        for ((field, values) in connection.headerFields) {
            if (field != null) headers[field] = values.joinToString(", ")
        }
        return TalqynHttpResponse(status, headers)
    }

    private companion object {
        /** A request's whole life, in multiples of its timeout. */
        const val DEADLINE_FACTOR = 10
    }
}

/**
 * The limit on a request's whole life that `HttpURLConnection` does not have. When it runs
 * out the connection is disconnected, and the read it interrupts reports a timeout — not a
 * socket that closed for no reason anyone can see.
 */
private class TalqynDeadline(connection: HttpURLConnection, limit: Duration) {
    private val expired = AtomicBoolean(false)
    private val timer: ScheduledFuture<*>? =
        if (limit.isPositive() && limit.isFinite()) {
            timers.schedule(
                {
                    expired.set(true)
                    connection.disconnect()
                },
                limit.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
        } else {
            null
        }

    fun cancel() {
        timer?.cancel(false)
    }

    fun explain(error: Throwable): Throwable =
        if (expired.get() && error is IOException && error !is SocketTimeoutException) {
            SocketTimeoutException("the request outlived its deadline").also { it.initCause(error) }
        } else {
            error
        }

    fun guard(input: InputStream): InputStream = object : FilterInputStream(input) {
        override fun read(): Int = try {
            super.read()
        } catch (e: IOException) {
            throw explain(e)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            super.read(b, off, len)
        } catch (e: IOException) {
            throw explain(e)
        }
    }

    private companion object {
        /** One thread for every deadline in the process; all it ever does is disconnect. */
        val timers: ScheduledThreadPoolExecutor by lazy {
            ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "talqyn-deadline").apply { isDaemon = true } }
                .apply { removeOnCancelPolicy = true }
        }
    }
}

/**
 * Runs blocking I/O off the caller's thread, in a way cancellation can reach:
 * `HttpURLConnection` does not answer to a thread interrupt, so a cancelled
 * coroutine calls [onCancel] — a disconnect — and the blocked read fails at once.
 *
 * A disconnect before the connection exists does nothing, so two more moments are
 * covered: a coroutine cancelled while the block still waited for a thread never runs
 * it — the request is never sent — and a block that finishes after the cancellation has
 * what it opened released by [onCancel] rather than dropped with the result.
 */
internal suspend fun <T> interruptible(
    onCancel: () -> Unit,
    executor: Executor = Dispatchers.IO.asExecutor(),
    block: () -> T,
): T =
    suspendCancellableCoroutine { continuation ->
        // A completion handler that throws surfaces as an uncaught exception.
        continuation.invokeOnCancellation { runCatching(onCancel) }
        executor.execute {
            if (!continuation.isActive) return@execute
            runCatching(block).fold(
                onSuccess = { value -> continuation.resume(value) { _, _, _ -> runCatching(onCancel) } },
                onFailure = { error -> continuation.resumeWithException(error) },
            )
        }
    }
