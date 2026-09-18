package com.talqyn.sdk

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * A listing page and its filter panel, counted against the same selection.
 *
 * @property listing The page of results.
 * @property filters The facet groups.
 */
public data class TalqynListingWithFilters(
    val listing: TalqynFullSearchResponse,
    val filters: TalqynFiltersResponse,
)

/**
 * Instant search, listings, and the filter panel.
 *
 * Requires the `search` scope, which every device token carries. Reached through
 * [Talqyn.search]. Every request inherits the client's defaults — locale, place, A/B
 * bucket — for the fields it leaves unset.
 */
public class TalqynSearchApi internal constructor(
    private val client: TalqynApiClient,
    private val defaults: TalqynDefaults,
) {
    /**
     * Runs instant search: `POST /v1/search/`.
     *
     * Returns the top matches together with completions, facet chips, brands, and
     * categories — everything a search field with a dropdown needs from one round trip.
     * Report the submitted query through [TalqynEventsApi.searchSubmit]: the `history`
     * block of later responses is assembled from those events.
     *
     * @throws TalqynException Commonly [TalqynException.Validation] for an empty query or an
     *   out-of-range limit, and [TalqynException.RateLimited] when the search bucket is exhausted.
     */
    public suspend fun search(query: TalqynSearchQuery): TalqynSearchResponse {
        // The trailing slash is part of the endpoint address, not a typo.
        return client.send(path = "search/", body = defaults.apply(query).toJson()) {
            TalqynSearchResponse.decode(it.asJsonObject())
        }
    }

    /**
     * Runs instant search for a plain query string.
     *
     * @param text What the shopper typed. 1–500 characters.
     * @param limit How many products to return. 1–50.
     */
    public suspend fun search(text: String, limit: Int = 20): TalqynSearchResponse =
        search(TalqynSearchQuery(query = text, limit = limit))

    /**
     * Fetches one page of a listing: `POST /v1/search/full`. Advance through pages with
     * [TalqynFullSearchQuery.nextPage], which returns `null` once the listing is exhausted.
     */
    public suspend fun full(query: TalqynFullSearchQuery): TalqynFullSearchResponse = sendFull(defaults.apply(query))

    /**
     * Fetches facet counts: `POST /v1/search/filters`.
     *
     * Counts are computed against the current query **and** the filters already applied,
     * which is what makes an option's count the number of products the shopper would get
     * by tapping it.
     */
    public suspend fun filters(query: TalqynFiltersQuery): TalqynFiltersResponse = sendFilters(defaults.apply(query))

    /**
     * Fetches a listing page and its filter panel concurrently.
     *
     * Both endpoints take the same selection, and computing it twice by hand is how a
     * panel ends up counting against something other than what is on screen. So is reading
     * the client's defaults twice: a place changed between the two readings would have the
     * panel count against another city than the listing shows — both requests are built from
     * one reading. They run in parallel; if either fails, the other is cancelled and the call
     * throws at once.
     *
     * ```kotlin
     * val (page, panel) = talqyn.search.listingWithFilters(query)
     * ```
     */
    public suspend fun listingWithFilters(query: TalqynFullSearchQuery): TalqynListingWithFilters = coroutineScope {
        val prepared = defaults.apply(query)
        val listing = async { sendFull(prepared) }
        val filters = async { sendFilters(prepared.filtersQuery) }
        TalqynListingWithFilters(listing.await(), filters.await())
    }

    private suspend fun sendFull(prepared: TalqynFullSearchQuery): TalqynFullSearchResponse =
        client.send(path = "search/full", body = prepared.toJson()) {
            TalqynFullSearchResponse.decode(it.asJsonObject())
        }

    private suspend fun sendFilters(prepared: TalqynFiltersQuery): TalqynFiltersResponse =
        client.send(path = "search/filters", body = prepared.toJson()) {
            TalqynFiltersResponse.decode(it.asJsonObject())
        }
}
