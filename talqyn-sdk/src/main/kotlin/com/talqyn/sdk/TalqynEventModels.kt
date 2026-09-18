package com.talqyn.sdk

/** Where a shopper's action took place. */
public enum class TalqynEventSource(public val wireValue: String) {
    /** The search field's dropdown. */
    Instant("instant"),

    /** A listing page. */
    Full("full"),

    /** The consultant's results. Not valid for [TalqynSearchSubmitEvent.source]. */
    Consultant("cip"),
}

/**
 * A shopper tapped a product card.
 *
 * @property searchId The impression the click belongs to — [TalqynSearchResponse.searchId],
 *   [TalqynFullSearchResponse.searchId], or [TalqynConsultantProducts.searchId]. Without it
 *   a click has no denominator and click-through cannot be computed. A click from deep
 *   pagination legitimately arrives without one, since only the first page carries an id.
 * @property talqynId Talqyn's internal product id — [TalqynProduct.talqynId], not your SKU.
 * @property position The zero-based position in the results. For the consultant this is
 *   the index in the turn's flattened product list, exactly as the storefront rendered it.
 * @property source Where the click happened.
 * @property variant The storefront's A/B bucket. Filled from the client default when `null`.
 */
public data class TalqynProductClickEvent(
    val searchId: String?,
    val talqynId: Long,
    val position: Int,
    val source: TalqynEventSource,
    val variant: String? = null,
) {
    internal fun toJson(): Map<String, Any?> = jsonObject {
        putIfNotNull("search_id", searchId)
        put("talqyn_id", talqynId)
        put("position", position)
        put("source", source.wireValue)
        putIfNotNull("variant", variant)
    }
}

/**
 * A shopper submitted a search query.
 *
 * Not optional analytics: the `history` block of an instant-search response is
 * assembled from these rows. A storefront running on a device token has to report
 * them itself — by definition there is no backend of yours in the chain to do it.
 *
 * @property query The query as submitted. 1–500 characters.
 * @property source Where it was submitted from. Only [TalqynEventSource.Instant] and
 *   [TalqynEventSource.Full] are accepted.
 * @property locale The language searched in. Filled from the client default when `null`.
 * @property resultsCount How many results came back, if known.
 * @property variant The storefront's A/B bucket. Filled from the client default when `null`.
 */
public data class TalqynSearchSubmitEvent(
    val query: String,
    val source: TalqynEventSource,
    val locale: TalqynLocale? = null,
    val resultsCount: Int? = null,
    val variant: String? = null,
) {
    internal fun toJson(): Map<String, Any?> = jsonObject {
        put("query", query)
        put("source", source.wireValue)
        putIfNotNull("locale", locale?.wireValue)
        putIfNotNull("results_count", resultsCount)
        putIfNotNull("variant", variant)
    }
}

/**
 * A shopper tapped a category in the navigation block of a search response.
 *
 * @property categoryId The category tapped — [TalqynCategory.id].
 * @property query The query whose results the category appeared in.
 * @property variant The storefront's A/B bucket. Filled from the client default when `null`.
 */
public data class TalqynCategoryClickEvent(
    val categoryId: Long,
    val query: String? = null,
    val variant: String? = null,
) {
    internal fun toJson(): Map<String, Any?> = jsonObject {
        put("category_id", categoryId)
        putIfNotNull("query", query)
        putIfNotNull("variant", variant)
    }
}
