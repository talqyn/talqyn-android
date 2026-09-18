package com.talqyn.sdk

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * Mints a device token: `POST /v1/consultant/token`.
 *
 * The endpoint is guarded by the storefront's client **signature**, not by a tenant
 * key, so this does not go through the shared authorization pipeline — there would
 * be nothing to authorize it with.
 */
internal class TalqynDeviceTokenMinter(
    private val builder: TalqynRequestBuilder,
    private val transport: TalqynHttpTransport,
    private val logHandler: ((TalqynLogEvent) -> Unit)?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * A minted token and the clock correction it was minted with.
     *
     * @property clockOffsetMillis Server clock minus device clock. Carried forward so
     *   the next mint signs with the right time from the first attempt.
     */
    class Minted(val token: TalqynDeviceToken, val clockOffsetMillis: Long)

    /**
     * Mints a token.
     *
     * A `401` is re-examined before it is surfaced: the response's `Date` header says
     * what time the server thinks it is, and if the device clock disagrees by more
     * than [CLOCK_TOLERANCE_MILLIS] the request is signed once more with the server's
     * time. A phone with automatic time switched off is otherwise locked out of search
     * entirely — under a device token, search depends on the mint too.
     *
     * @param clockOffsetMillis The correction learned by a previous mint.
     */
    suspend fun mint(
        storefront: String,
        clientKeyId: String,
        clientSecret: String,
        userId: UUID?,
        clockOffsetMillis: Long = 0,
    ): Minted {
        if (clientKeyId.isEmpty() || clientSecret.isEmpty()) {
            throw TalqynException.InvalidConfiguration("storefront client key is empty")
        }
        if (storefront.isEmpty()) {
            throw TalqynException.InvalidConfiguration("storefront slug is empty")
        }

        // Serialized exactly once: the signature must cover the bytes that go on the
        // wire. Re-encoding could reorder keys and break it.
        val body = TalqynJson.encodeToBytes(
            jsonObject {
                put("storefront", storefront)
                putIfNotNull("user_id", userId?.toString()?.lowercase(Locale.ROOT))
            },
        )

        var offset = clockOffsetMillis
        var didCorrectClock = false

        while (true) {
            val requestId = UUID.randomUUID().toString()
            // A fresh nonce every time: the server rejects a repeat.
            val headers = TalqynClientSignature.headers(
                keyId = clientKeyId,
                secret = clientSecret,
                body = body,
                timestamp = Instant.ofEpochMilli(now() + offset),
            ) + ("X-Request-ID" to requestId)

            val request = builder.request(method = "POST", path = "consultant/token", body = body, headers = headers)
            val result = try {
                transport.send(request)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // The mint's own cancellation — a change of shopper — ends it here. One that
                    // is not its own, from a timeout inside the app's transport, is a failed
                    // request, and is retried as one.
                    currentCoroutineContext().ensureActive()
                    throw TalqynException.Transport(e)
                }
                throw TalqynException.wrap(e)
            }
            val received = now()

            if (result.response.isSuccess) {
                val token = try {
                    TalqynDeviceToken.decode(TalqynJson.parseObject(result.body))
                } catch (e: TalqynJsonException) {
                    throw TalqynException.Decoding(e, requestId)
                }
                return Minted(token, offset)
            }

            val serverTime = result.response.value("Date")?.let(TalqynDates::parseHttp)
            if (result.response.statusCode == 401 && !didCorrectClock && serverTime != null) {
                val skew = serverTime.toEpochMilli() - received
                if (abs(skew - offset) > CLOCK_TOLERANCE_MILLIS) {
                    didCorrectClock = true
                    // A skew inside the tolerance is a clock that is fine — the previous
                    // correction was the problem — and the Date header's one-second
                    // precision is not worth keeping.
                    offset = if (abs(skew) <= CLOCK_TOLERANCE_MILLIS) 0 else skew
                    logHandler.log(
                        TalqynLogEvent.Level.Info,
                        "device clock is off by ${skew / 1000}s; signing the mint with the server's time",
                        requestId,
                    )
                    continue
                }
            }

            val error = describe(result.response, result.body, requestId)
            logHandler.log(TalqynLogEvent.Level.Warning, "device token mint rejected: ${error.message}", error.requestId)
            throw error
        }
    }

    companion object {
        /**
         * How far the device clock must be off before a `401` is read as a clock problem
         * rather than a key problem. The acceptance window is
         * [TalqynClientSignature.maxSkew]; a clock inside it does not cause a `401`, so a
         * small measured skew leaves the refusal as it is.
         */
        const val CLOCK_TOLERANCE_MILLIS: Long = 60_000

        /**
         * Minting has its own status codes, and they mean different things: 403 — not
         * enabled for the storefront, 501 — not configured on the installation, 503 — no
         * live anchor key. The last two are support conversations rather than "try
         * later", hence their own cases.
         *
         * The storefront-level refusal arrives in the auth envelope, `{"detail": …}`. A
         * `503` in the platform envelope — `{"error": "overloaded", …}` — or a bare `503`
         * from a proxy is an ordinary server failure and must not read as "call support".
         */
        private fun describe(response: TalqynHttpResponse, body: ByteArray, requestId: String): TalqynException {
            val envelope = TalqynErrorEnvelope.parse(body)
            return when {
                response.statusCode == 501 ->
                    TalqynException.DeviceTokensNotConfigured(envelope?.requestId ?: requestId)
                response.statusCode == 503 && envelope != null && envelope.error == null && envelope.detail != null ->
                    TalqynException.DeviceTokensUnavailable(envelope.requestId ?: requestId)
                else -> TalqynException.from(response, body, requestId)
            }
        }
    }
}
