package com.talqyn.sdk

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * A failure returned by Talqyn or by the SDK on its way there.
 *
 * Every SDK call throws this type. It decodes both envelopes the contract defines —
 * `{"detail": …}` for authentication and rate limiting, `{"error": …, "request_id": …}`
 * for everything else — so a caller can branch on the subclass rather than on a
 * status code.
 *
 * Use [isRetryable] to tell a transient failure from a permanent one, and [requestId]
 * to quote a request when contacting Talqyn support. Two failures are equal when they
 * are the same case with the same values — which is what a screen that re-renders on
 * a change needs: the same failure twice is no change.
 *
 * Cancellation is not a failure and is not wrapped: a cancelled coroutine sees the
 * `CancellationException` it always sees.
 *
 * The cases follow the contract, and a minor release may add one when the contract
 * does. A `when` that names every case with no `else` stops compiling the day that
 * happens: handle the cases you act on and give the rest to an `else` — or branch on
 * [isRetryable] and [statusCode].
 */
public sealed class TalqynException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * The credentials were rejected (`401`).
     *
     * The SDK already reissued the device token and retried once before surfacing
     * this, so reaching it means reissuing did not help either: the client key itself
     * is invalid or revoked, or the mint was refused.
     */
    public class Unauthorized(
        public val detail: String?,
        override val requestId: String?,
    ) : TalqynException(detail ?: "Talqyn: request is not authorized")

    /**
     * The credentials are valid but not sufficient (`403`).
     *
     * Raised when device-token issuance is not enabled for the storefront, or when
     * `/v1/consultant/chats` is read under a guest token, which names no shopper.
     */
    public class Forbidden(
        public val detail: String?,
        override val requestId: String?,
    ) : TalqynException(detail ?: "Talqyn: access denied")

    /**
     * Nothing was found at that address (`404`).
     *
     * For a chat this also means "not yours": an unknown session id and somebody
     * else's answer identically, on purpose — a `403` would confirm that the
     * conversation exists.
     */
    public class NotFound(
        override val requestId: String?,
    ) : TalqynException("Talqyn: not found")

    /**
     * The request body failed validation (`422`).
     *
     * @property fields Dotted paths of the offending fields, for example
     *   `["body.filters.0"]`. The server never echoes the raw values.
     * @property detail The first validation message, when the server sent one.
     */
    public class Validation(
        public val fields: List<String>,
        public val detail: String?,
        override val requestId: String?,
    ) : TalqynException(
        (detail ?: "Talqyn: request failed validation") +
            (if (fields.isEmpty()) "" else " (${fields.joinToString(", ")})"),
    )

    /**
     * A rate-limit bucket is exhausted (`429`).
     *
     * @property retryAfter The `Retry-After` value, when the server sent one.
     */
    public class RateLimited(
        override val retryAfter: Duration?,
        public val detail: String?,
        override val requestId: String?,
    ) : TalqynException("Talqyn: rate limit exceeded")

    /**
     * The server failed, or answered in a way the SDK maps to no other case.
     *
     * A `5xx` is the server's own failure and is retried; any other status that lands
     * here — a `400`, a `405`, a redirect that was not followed — is a fixed answer to
     * a fixed request and is not.
     *
     * @property status The HTTP status code.
     * @property code The `error` field of the envelope — `upstream_unavailable`,
     *   `database_unavailable`, `overloaded`, or `internal_error`.
     * @property retryAfter The `Retry-After` value, when the server sent one — a `503`
     *   during a deploy often names its own wait.
     */
    public class Server(
        public val status: Int,
        public val code: String?,
        public val detail: String?,
        override val retryAfter: Duration?,
        override val requestId: String?,
    ) : TalqynException(detail ?: "Talqyn: server error $status${code?.let { " ($it)" } ?: ""}")

    /** Device tokens are not configured on this Talqyn installation (`501`). Not a "try again later": a support conversation. */
    public class DeviceTokensNotConfigured(
        override val requestId: String?,
    ) : TalqynException("Talqyn: device token issuance is not configured")

    /**
     * The storefront has no live anchor key to issue device tokens against (`503`).
     *
     * Also a support conversation, though the SDK keeps retrying since the condition
     * can clear on its own.
     */
    public class DeviceTokensUnavailable(
        override val requestId: String?,
    ) : TalqynException("Talqyn: device token issuance is temporarily unavailable")

    /**
     * The request never completed: a dropped connection, a timeout, or no network at all.
     *
     * @property cause What failed — usually an `IOException`; a
     *   [TalqynConnectionLostException] when a consultant stream ended before its `done`.
     */
    public class Transport(
        override val cause: Throwable,
    ) : TalqynException("Talqyn: request failed (${cause.message ?: cause.javaClass.simpleName})", cause)

    /** The response could not be decoded into the expected model. */
    public class Decoding(
        override val cause: Throwable,
        override val requestId: String?,
    ) : TalqynException("Talqyn: could not decode the response (${cause.message})", cause)

    /**
     * The request body could not be encoded — a non-finite number in a price bound,
     * for instance. Nothing was sent: this is a programming error on the calling side,
     * not an answer from the server.
     */
    public class Encoding(
        override val cause: Throwable,
    ) : TalqynException("Talqyn: could not encode the request (${cause.message})", cause)

    /** The SDK was configured in a way that cannot produce a request — an empty key, or a base URL that does not parse. */
    public class InvalidConfiguration(
        public val reason: String,
    ) : TalqynException("Talqyn: invalid configuration — $reason")

    /**
     * The shopper changed ([Talqyn.setIdentity]) while the request waited for its device token.
     *
     * Nothing was sent: the token on its way named the previous shopper, and a request under
     * it would have landed in their history. The SDK does not repeat the request — whether it
     * still makes sense for the new shopper is the caller's call.
     *
     * An answer rather than a coroutine cancellation: the caller was not cancelled and is still
     * waiting for one, and a bare `CancellationException` would end it silently, leaving a
     * spinner spinning. The twin SDK reports the same moment as `.cancelled`.
     */
    public class IdentityChanged : TalqynException("Talqyn: the shopper changed while the request waited for a device token")

    /** The `X-Request-ID` of the failed request, when the failure carries one: what Talqyn support needs to find it. */
    public open val requestId: String? get() = null

    /**
     * How long to wait before repeating, as instructed by the server. `null` unless
     * the failure is [RateLimited] or [Server] with a `Retry-After` header.
     */
    public open val retryAfter: Duration? get() = null

    /**
     * Whether repeating the request could plausibly succeed.
     *
     * `true` for rate limiting, `5xx` server failures, transport failures, and a
     * storefront temporarily without an anchor key; `false` for client errors, which
     * would produce the same answer again. The SDK already applies this through
     * [TalqynRetryPolicy]; the property is public so a caller can decide whether to
     * offer a "try again" affordance.
     */
    public val isRetryable: Boolean
        get() = when (this) {
            is RateLimited, is Transport, is DeviceTokensUnavailable -> true
            is Server -> status >= 500
            is Unauthorized, is Forbidden, is NotFound, is Validation, is Decoding, is Encoding,
            is InvalidConfiguration, is DeviceTokensNotConfigured, is IdentityChanged,
            -> false
        }

    /** The HTTP status code behind the failure, or `null` if it never reached the server. */
    public val statusCode: Int?
        get() = when (this) {
            is Unauthorized -> 401
            is Forbidden -> 403
            is NotFound -> 404
            is Validation -> 422
            is RateLimited -> 429
            is DeviceTokensNotConfigured -> 501
            is DeviceTokensUnavailable -> 503
            is Server -> status
            is Transport, is Decoding, is Encoding, is InvalidConfiguration, is IdentityChanged -> null
        }

    /**
     * What makes two failures the same. A cause has no equality of its own and is
     * compared by its type and message.
     */
    private val identity: List<Any?>
        get() = when (this) {
            is Unauthorized -> listOf(detail, requestId)
            is Forbidden -> listOf(detail, requestId)
            is NotFound -> listOf(requestId)
            is Validation -> listOf(fields, detail, requestId)
            is RateLimited -> listOf(retryAfter, detail, requestId)
            is Server -> listOf(status, code, detail, retryAfter, requestId)
            is DeviceTokensNotConfigured -> listOf(requestId)
            is DeviceTokensUnavailable -> listOf(requestId)
            is Transport -> listOf(cause.javaClass, cause.message)
            is Decoding -> listOf(cause.javaClass, cause.message, requestId)
            is Encoding -> listOf(cause.javaClass, cause.message)
            is InvalidConfiguration -> listOf(reason)
            is IdentityChanged -> emptyList()
        }

    final override fun equals(other: Any?): Boolean =
        other is TalqynException && other.javaClass == javaClass && other.identity == identity

    final override fun hashCode(): Int = javaClass.hashCode() * 31 + identity.hashCode()

    public companion object {
        /**
         * Normalizes any caught failure into a [TalqynException].
         *
         * Every SDK call throws this type already, so a `catch` around one does not
         * need this. A `catch` around a transport of your own does, and so does a
         * screen that stores whatever it caught. Public so a storefront drawing its own
         * screen does not write this mapping a second time.
         *
         * A `CancellationException` is not a failure and is rethrown rather than
         * wrapped — so `catch (e: Exception) { failure = TalqynException.wrap(e) }`
         * leaves a cancelled coroutine cancelled.
         *
         * @return [error] itself when it already is a [TalqynException], [Encoding] for
         *   a body that could not be written, and [Transport] for anything else.
         */
        @JvmStatic
        public fun wrap(error: Throwable): TalqynException = when (error) {
            is CancellationException -> throw error
            is TalqynException -> error
            is TalqynJsonException -> Encoding(error)
            else -> Transport(error)
        }

        /**
         * Maps a server response onto a failure. One mapping for every endpoint: they
         * share status codes and envelopes and must not diverge. The mint endpoint's
         * own codes (`501`, `503`) are handled by [TalqynDeviceTokenMinter] first.
         */
        internal fun from(response: TalqynHttpResponse, body: ByteArray, requestId: String?): TalqynException {
            val envelope = TalqynErrorEnvelope.parse(body)
            val id = envelope?.requestId ?: response.value("X-Request-ID") ?: requestId
            val detail = envelope?.detail
            val retryAfter = response.retryAfter()
            return when (response.statusCode) {
                401 -> Unauthorized(detail, id)
                403 -> Forbidden(detail, id)
                404 -> NotFound(id)
                422 -> Validation(envelope?.fields.orEmpty(), detail, id)
                429 -> RateLimited(retryAfter, detail, id)
                else -> Server(response.statusCode, envelope?.error, detail, retryAfter, id)
            }
        }
    }
}

/**
 * The connection closed before the consultant's `done` event: the stream was cut
 * short somewhere between Talqyn and the device — a proxy, an idle timeout, a lost
 * network. Arrives as the cause of a [TalqynException.Transport].
 */
public class TalqynConnectionLostException(message: String) : IOException(message)

/** An error body in both shapes the contract defines. Everything is optional: a 502 from a proxy may carry no body at all. */
internal class TalqynErrorEnvelope(
    val error: String?,
    val detail: String?,
    val fields: List<String>,
    val requestId: String?,
) {
    companion object {
        fun parse(body: ByteArray): TalqynErrorEnvelope? {
            if (body.isEmpty()) return null
            val json = try {
                TalqynJson.parseObject(body)
            } catch (e: TalqynJsonException) {
                return null
            }
            var detail: String? = null
            var fields = emptyList<String>()
            // `detail` is a string for auth and rate limits and a list of objects for
            // 422 — one field, two shapes, decided at parse time.
            when (val raw = json.raw("detail")) {
                is String -> detail = raw
                is List<*> -> {
                    val items = raw.map { it as? Map<*, *> }
                    if (items.none { it == null }) {
                        val validation = items.map { JsonObject(it!!) }
                        fields = validation.mapNotNull { item ->
                            // `loc` mixes strings and indices: ["body", "filters", 0].
                            val path = (item.raw("loc") as? List<*>).orEmpty().mapNotNull { part ->
                                part as? String ?: JsonObject.asInt(part)?.toString()
                            }
                            path.takeIf { it.isNotEmpty() }?.joinToString(".")
                        }
                        detail = validation.firstNotNullOfOrNull { it.string("msg") ?: it.string("type") }
                    }
                }
            }
            return TalqynErrorEnvelope(json.string("error"), detail, fields, json.string("request_id"))
        }
    }
}
