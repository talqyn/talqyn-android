package com.talqyn.sdk

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the client needs to talk to Talqyn.
 *
 * [credentials] and [baseUrl] are required; every other value has a working default.
 *
 * ```kotlin
 * val configuration = TalqynConfiguration(
 *     credentials = TalqynDeviceTokenCredentials(
 *         storefront = "myshop",
 *         clientKeyId = "ck_3f9a1c2b7d4e",
 *         clientSecret = secret,
 *     ),
 *     baseUrl = BuildConfig.TALQYN_BASE_URL,
 *     defaultLocale = TalqynLocale.En,
 *     defaultCityId = "10",
 * )
 * ```
 *
 * @property credentials The storefront's client key, which the SDK turns into a device token.
 * @property baseUrl The API host. The SDK ships no endpoint of its own: the host is named
 *   here like the client key next to it, and it belongs in the build configuration beside
 *   that key, because the two change together when an app moves between stands. A path
 *   prefix is preserved, so `https://gateway.example.com/talqyn` works as-is.
 * @property apiVersion The API version path segment. `v1` is canonical. Unprefixed
 *   paths remain working legacy aliases, but a breaking contract change ships as a
 *   new prefix alongside this one; pass an empty string to address the aliases.
 * @property defaultLocale The locale applied to requests that do not name one.
 * @property defaultCityId The shopper's city, applied to requests that name no place:
 *   a `locations.external_id` **in your own catalog's numbering**, taken from the
 *   `id` of an option in the `city` group of [TalqynSearchApi.filters].
 * @property defaultLocationId The shopper's specific store, from the `location` group.
 *   A store beats a city, both here and in the API.
 * @property variant The storefront's A/B bucket, echoed into Talqyn analytics; no
 *   effect on results. `[A-Za-z0-9._:-]`, at most 32 characters.
 * @property timeout The timeout of an ordinary request.
 * @property streamTimeout How long to wait for the next byte of a consultant stream —
 *   the first above all. A whole turn may take longer: the wait restarts on every
 *   chunk received.
 * @property retryPolicy When and how often to repeat a failed request.
 * @property userIdStore Where the persistent anonymous shopper UUID and the device
 *   clock correction are kept. `null` — the default — keeps them in a file of the
 *   app's no-backup directory; see [TalqynFileUserIdStore.noBackup].
 * @property transport The HTTP transport. `null` uses [TalqynUrlConnectionTransport].
 *   Substitute your own to add certificate pinning, a proxy, or a traffic logger;
 *   tests use it to stub the network entirely.
 * @property logHandler Where SDK diagnostics are delivered. Tokens, client secrets,
 *   and shopper ids are never passed to it. Called from background threads.
 */
public data class TalqynConfiguration(
    val credentials: TalqynDeviceTokenCredentials,
    val baseUrl: String,
    val apiVersion: String = "v1",
    val defaultLocale: TalqynLocale = TalqynLocale.En,
    val defaultCityId: String? = null,
    val defaultLocationId: String? = null,
    val variant: String? = null,
    val timeout: Duration = 30.seconds,
    val streamTimeout: Duration = 60.seconds,
    val retryPolicy: TalqynRetryPolicy = TalqynRetryPolicy.Default,
    val userIdStore: TalqynUserIdStore? = null,
    val transport: TalqynHttpTransport? = null,
    val logHandler: ((TalqynLogEvent) -> Unit)? = null,
)

/**
 * When and how often a failed request is repeated.
 *
 * Client errors (`4xx` other than `429`) are never repeated — a repeat produces
 * the same answer. `5xx` responses and dropped connections are repeated with
 * exponential backoff. `429`, and a `5xx` that names a wait, are repeated after
 * `Retry-After` — but only when that wait fits under [maxDelay]. A server asking
 * for a minute is not answered with a five-second retry into the same exhausted
 * bucket: the error is surfaced at once, with [TalqynException.retryAfter] for the
 * app to act on.
 *
 * The policy says how often; the request says whether at all. A request that may
 * have been carried out before its answer was lost is repeated only when the
 * server certainly did not carry it out: an event is repeated after a `429`
 * alone, a consultant turn — an LLM call, paid for — after a refusal it got
 * before it started, never after a connection that dropped under it.
 *
 * Backoff waits carry jitter: each is drawn between half and all of its
 * exponential step. During an outage every installation fails at the same
 * moment, and without the spread they would all come back at the same moment too.
 *
 * @property maxRetries How many additional attempts to make after the first one fails.
 * @property baseDelay The first backoff interval. Doubles on every attempt.
 * @property maxDelay The ceiling on any single wait. Backoff is capped here; a
 *   `Retry-After` above it is not capped but declined.
 */
public data class TalqynRetryPolicy(
    val maxRetries: Int,
    val baseDelay: Duration = 300.milliseconds,
    val maxDelay: Duration = 5.seconds,
) {
    /**
     * The wait before the next attempt, or `null` when the policy declines to
     * repeat: the server named a wait longer than [maxDelay].
     *
     * @param jitter Where between half and all of the backoff step the wait
     *   falls, from 0 to 1. A `Retry-After` is the server's own number and is
     *   not jittered.
     */
    internal fun delay(attempt: Int, retryAfter: Duration?, jitter: Double = Random.nextDouble()): Duration? {
        if (retryAfter != null) {
            if (retryAfter > maxDelay) return null
            return retryAfter.coerceAtLeast(Duration.ZERO)
        }
        val step = minOf(baseDelay * 2.0.pow(attempt), maxDelay)
        return step * (0.5 + 0.5 * jitter.coerceIn(0.0, 1.0))
    }

    public companion object {
        /** Two retries with a 0.3 s base delay, capped at 5 s. The default. */
        @JvmField
        public val Default: TalqynRetryPolicy = TalqynRetryPolicy(maxRetries = 2)

        /** No retries: the first failure is the result. */
        @JvmField
        public val None: TalqynRetryPolicy = TalqynRetryPolicy(maxRetries = 0)
    }
}

/**
 * A diagnostic message emitted by the SDK. Carries no secrets, tokens, or shopper
 * identifiers.
 *
 * @property level How severe the event is.
 * @property message A human-readable description of what happened.
 * @property requestId The `X-Request-ID` of the request involved, when there is one —
 *   the value to quote when contacting Talqyn support.
 */
public data class TalqynLogEvent(
    val level: Level,
    val message: String,
    val requestId: String? = null,
) {
    /** The severity of a [TalqynLogEvent]. */
    public enum class Level {
        /** Routine internal detail, such as a token being minted. */
        Debug,

        /** Something worth noticing during normal operation. */
        Info,

        /** A condition that will degrade the integration if left alone. */
        Warning,

        /** A failure the SDK could not work around. */
        Error,
    }
}

/**
 * Hands a diagnostic to the app's [TalqynConfiguration.logHandler].
 *
 * A handler is the app's code, and it may throw — a logger that insists on the main
 * thread, say. That must cost the log line only: not the request being logged, and not
 * the process, when the line comes from background work with nobody to catch it.
 */
internal fun ((TalqynLogEvent) -> Unit)?.log(level: TalqynLogEvent.Level, message: String, requestId: String? = null) {
    val handler = this ?: return
    try {
        handler(TalqynLogEvent(level, message, requestId))
    } catch (e: Exception) {
        // Nowhere left to report it.
    }
}
