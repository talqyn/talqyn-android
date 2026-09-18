package com.talqyn.sdk

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Whether a failed request may be sent again.
 *
 * The retry policy decides how often; this decides whether at all, from what a
 * repeat would cost if the first attempt had in fact gone through.
 */
internal enum class TalqynRetrySafety {
    /** Repeating is harmless: a read, or a write that overwrites itself. */
    Idempotent,

    /**
     * The server starts the work only once it has accepted the request.
     *
     * A refusal that came back as a status — a `429`, a `5xx` from before the stream
     * opened — means nothing was done, and is repeated. A connection that dropped is
     * not: the work may already be running on the other end. A consultant turn is an
     * LLM call, and running it twice is paying twice and recording the turn twice.
     */
    UntilAccepted,

    /**
     * Repeated only after a `429`: an exhausted bucket is the one refusal that says
     * the request was certainly not carried out. A `5xx` or a lost connection may have
     * come after the work was done.
     */
    OnlyIfRejected,
}

/**
 * The SDK's transport core: authorization, retries, error mapping, SSE.
 *
 * The API surfaces describe a path and a body and see none of this, so search, the
 * consultant, and events cannot drift apart in how they behave.
 *
 * @property decodeDispatcher Where responses are read. Not the caller's: a screen calls
 *   from the main thread, and a listing with its facets, or a turn's product cards, is
 *   more JSON than fits between two frames.
 */
internal class TalqynApiClient(
    private val builder: TalqynRequestBuilder,
    private val transport: TalqynHttpTransport,
    private val authorizer: TalqynDeviceTokenAuthorizer,
    private val retryPolicy: TalqynRetryPolicy,
    val streamTimeout: Duration,
    private val logHandler: ((TalqynLogEvent) -> Unit)?,
    private val jitter: () -> Double = { Random.nextDouble() },
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    // region Unary requests

    /**
     * Sends a request and decodes its body.
     *
     * @param safety Whether a failed attempt may be repeated.
     * @param timeout Overrides the configured request timeout — for a request that
     *   sends nothing back until a long piece of work is done.
     */
    suspend fun <T> send(
        method: String = "POST",
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: Map<String, Any?>? = null,
        safety: TalqynRetrySafety = TalqynRetrySafety.Idempotent,
        timeout: Duration? = null,
        decode: (Any?) -> T,
    ): T {
        val (data, requestId) = perform(method, path, query, body, safety, timeout)
        return withContext(decodeDispatcher) {
            try {
                decode(TalqynJson.parse(data))
            } catch (e: TalqynJsonException) {
                logHandler.log(TalqynLogEvent.Level.Error, "could not decode the response from $path", requestId)
                throw TalqynException.Decoding(e, requestId)
            }
        }
    }

    /** For endpoints with no response body (`204`). */
    suspend fun sendWithoutResponse(
        method: String = "POST",
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: Map<String, Any?>? = null,
        safety: TalqynRetrySafety = TalqynRetrySafety.Idempotent,
    ) {
        perform(method, path, query, body, safety, timeout = null)
    }

    private suspend fun perform(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        body: Map<String, Any?>?,
        safety: TalqynRetrySafety,
        timeout: Duration?,
    ): Pair<ByteArray, String> {
        val payload = body?.let(::encode)
        var attempt = 0
        var didRefreshAuthorization = false

        while (true) {
            val requestId = UUID.randomUUID().toString()
            // Everything before the transport is local — the token included — and a
            // failure there sent nothing that could be carried out twice.
            var didSend = false
            try {
                val headers = authorizer.headers() + ("X-Request-ID" to requestId)
                val request = builder.request(method, path, query, payload, headers, timeout = timeout)
                didSend = true
                val result = transport.send(request)
                if (result.response.isSuccess) return result.body to requestId
                // A device token expiring is routine, and reissuing is the only cure.
                // One attempt, not a loop: a second 401 means the cause is not expiry.
                if (result.response.statusCode == 401 && !didRefreshAuthorization) {
                    didRefreshAuthorization = true
                    authorizer.invalidate(headers["Authorization"])
                    continue
                }
                throw TalqynException.from(result.response, result.body, requestId)
            } catch (e: Exception) {
                val failure = failureOf(e)
                val wait = retryDelay(failure, safety, didSend, attempt) ?: throw failure
                sleep(wait)
                attempt += 1
            }
        }
    }

    // endregion

    // region Server-sent events

    /**
     * A `text/event-stream` of events.
     *
     * Response headers are read **before** the first line: a failing status is an
     * error in full and must be parsed as an ordinary body, not as a stream. The
     * request is made when the flow is collected.
     */
    fun stream(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: Map<String, Any?>,
    ): Flow<TalqynSseMessage> = flow {
        val lines = openStream(path, query, encode(body))
        val decoder = TalqynSseDecoder()
        lines
            .catch { e -> throw TalqynException.wrap(e) }
            .collect { line -> decoder.consume(line)?.let { emit(it) } }
        decoder.finish()?.let { emit(it) }
    }

    /**
     * Opens the stream. Repeated under [TalqynRetrySafety.UntilAccepted]: the turn
     * behind a stream starts once the server answers `200`, so a refusal before it is
     * safe to repeat and a dropped connection is not.
     */
    private suspend fun openStream(path: String, query: List<Pair<String, String>>, body: ByteArray): Flow<String> {
        var didRefreshAuthorization = false
        var attempt = 0

        while (true) {
            val requestId = UUID.randomUUID().toString()
            var didSend = false
            try {
                val headers = authorizer.headers() + ("X-Request-ID" to requestId)
                val request = builder.request(
                    method = "POST",
                    path = path,
                    query = query,
                    body = body,
                    headers = headers,
                    accept = "text/event-stream",
                    timeout = streamTimeout,
                )
                didSend = true
                val stream = transport.stream(request)
                if (stream.response.isSuccess) return stream.lines
                val data = collectErrorBody(stream.lines)
                if (stream.response.statusCode == 401 && !didRefreshAuthorization) {
                    didRefreshAuthorization = true
                    authorizer.invalidate(headers["Authorization"])
                    continue
                }
                throw TalqynException.from(stream.response, data, requestId)
            } catch (e: Exception) {
                val failure = failureOf(e)
                val wait = retryDelay(failure, TalqynRetrySafety.UntilAccepted, didSend, attempt) ?: throw failure
                sleep(wait)
                attempt += 1
            }
        }
    }

    /**
     * A failing streamed request delivers its error body as the same lines. Read a
     * bounded amount: this is a small envelope, not a stream.
     */
    private suspend fun collectErrorBody(lines: Flow<String>): ByteArray {
        val text = StringBuilder()
        try {
            lines.takeWhile { text.length < 8 * 1024 }.collect { text.append(it) }
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            // A read failure ends the body like its end does: there is no more to read.
        }
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    // endregion

    /**
     * What a caught exception means for the request.
     *
     * The caller's own cancellation goes on as it is. A `CancellationException` that is not
     * the caller's — a `withTimeout` inside a transport of the app's own — is a failed
     * request like any other: let through, it would end a caller that is still waiting for an
     * answer, silently.
     */
    private suspend fun failureOf(error: Exception): TalqynException {
        if (error is CancellationException) {
            currentCoroutineContext().ensureActive()
            return TalqynException.Transport(error)
        }
        return TalqynException.wrap(error)
    }

    private fun encode(body: Map<String, Any?>): ByteArray = try {
        TalqynJson.encodeToBytes(body)
    } catch (e: TalqynJsonException) {
        throw TalqynException.Encoding(e)
    }

    /** The wait before the next attempt, or `null` when this failure is final. */
    private fun retryDelay(failure: TalqynException, safety: TalqynRetrySafety, didSend: Boolean, attempt: Int): Duration? {
        if (!canRetry(failure, safety, didSend) || attempt >= retryPolicy.maxRetries) return null
        return retryPolicy.delay(attempt, failure.retryAfter, jitter())
    }

    companion object {
        /**
         * Whether a failure may be answered by repeating the request.
         *
         * @param didSend Whether the request reached the transport. A failure before
         *   it — a token that could not be minted — is repeated under any safety:
         *   nothing was sent to be carried out twice.
         */
        fun canRetry(error: TalqynException, safety: TalqynRetrySafety, didSend: Boolean): Boolean {
            if (!error.isRetryable) return false
            if (!didSend) return true
            return when (safety) {
                TalqynRetrySafety.Idempotent -> true
                // A status is an answer, and an answer before the work started means the
                // work did not start. A transport failure has none.
                TalqynRetrySafety.UntilAccepted -> error.statusCode != null
                TalqynRetrySafety.OnlyIfRejected -> error.statusCode == 429
            }
        }
    }
}
