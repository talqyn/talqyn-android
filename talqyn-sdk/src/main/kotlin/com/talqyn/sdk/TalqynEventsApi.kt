package com.talqyn.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Storefront events: clicks and submitted queries.
 *
 * Requires the `events` scope. Reached through [Talqyn.events].
 *
 * Not analytics for its own sake: the `history` block of an instant-search response and
 * the denominator of click-through are both assembled from these rows. The storefront
 * has to report them itself — by definition there is no backend of yours in the chain to
 * do it. An event is attributed to the shopper the device token names.
 */
public class TalqynEventsApi internal constructor(
    private val client: TalqynApiClient,
    private val defaults: TalqynDefaults,
    private val logHandler: ((TalqynLogEvent) -> Unit)?,
    private val scope: CoroutineScope,
) {
    /** Reports a product-card tap: `POST /v1/events/product-click`. Use [track] to fire and forget. */
    public suspend fun productClick(event: TalqynProductClickEvent) {
        client.sendWithoutResponse(
            path = "events/product-click",
            body = defaults.apply(event).toJson(),
            safety = TalqynRetrySafety.OnlyIfRejected,
        )
    }

    /** Reports a submitted search query: `POST /v1/events/search`. Use [track] to fire and forget. */
    public suspend fun searchSubmit(event: TalqynSearchSubmitEvent) {
        client.sendWithoutResponse(
            path = "events/search",
            body = defaults.apply(event).toJson(),
            safety = TalqynRetrySafety.OnlyIfRejected,
        )
    }

    /** Reports a category tap in the navigation block: `POST /v1/events/category-click`. Use [track] to fire and forget. */
    public suspend fun categoryClick(event: TalqynCategoryClickEvent) {
        client.sendWithoutResponse(
            path = "events/category-click",
            body = defaults.apply(event).toJson(),
            safety = TalqynRetrySafety.OnlyIfRejected,
        )
    }

    // region Fire and forget

    /**
     * Reports a product-card tap without waiting for the result.
     *
     * Failures are swallowed into [TalqynConfiguration.logHandler]: analytics must not be
     * able to break the screen a shopper just tapped. A transient failure is logged at
     * `Debug`; a permanent refusal — a key without the `events` scope, a body the server
     * rejects — at `Warning`, because it means the `history` block and click-through are
     * silently not being built.
     */
    public fun track(event: TalqynProductClickEvent) {
        fireAndForget("product-click") { productClick(event) }
    }

    /** Reports a submitted search query without waiting for the result. Failures go to [TalqynConfiguration.logHandler]. */
    public fun track(event: TalqynSearchSubmitEvent) {
        fireAndForget("search") { searchSubmit(event) }
    }

    /** Reports a category tap without waiting for the result. Failures go to [TalqynConfiguration.logHandler]. */
    public fun track(event: TalqynCategoryClickEvent) {
        fireAndForget("category-click") { categoryClick(event) }
    }

    private fun fireAndForget(name: String, send: suspend () -> Unit) {
        scope.launch {
            try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = TalqynException.wrap(e)
                // A shopper who changed in the middle is no misconfiguration to warn about.
                val isPassing = failure.isRetryable || failure is TalqynException.IdentityChanged
                logHandler.log(
                    if (isPassing) TalqynLogEvent.Level.Debug else TalqynLogEvent.Level.Warning,
                    "event $name was not delivered: ${failure.message}",
                    failure.requestId,
                )
            }
        }
    }

    // endregion
}
