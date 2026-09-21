package com.talqyn.sdk

/**
 * A request for instant search (`POST /v1/search/`).
 *
 * Fields left `null` are filled from [TalqynConfiguration] — locale, place, and A/B
 * bucket. A value set here always wins.
 *
 * @property query What the shopper typed. 1–500 characters, non-empty after trimming.
 * @property locale The language to search in. `null` uses the client default.
 * @property limit How many products to return. 1–50.
 * @property categoryId Restricts results to one category.
 * @property brandId Restricts results to one brand.
 * @property priceMin The lower price bound. Must not exceed [priceMax].
 * @property priceMax The upper price bound.
 * @property inStockOnly Whether to drop out-of-stock products.
 * @property cityId The shopper's city — the `id` of an option in the `city` group of
 *   [TalqynSearchApi.filters]. An unknown id is not an error: results quietly narrow
 *   to "available everywhere".
 * @property locationId The shopper's store — the `id` of an option in the `location`
 *   group. Takes precedence over [cityId].
 * @property variant The storefront's A/B bucket: echoed into analytics, no effect on results.
 */
public data class TalqynSearchQuery(
    val query: String,
    val locale: TalqynLocale? = null,
    val limit: Int = 20,
    val categoryId: Long? = null,
    val brandId: Long? = null,
    val priceMin: Double? = null,
    val priceMax: Double? = null,
    val inStockOnly: Boolean = false,
    val cityId: String? = null,
    val locationId: String? = null,
    val variant: String? = null,
) {
    internal fun toJson(): Map<String, Any?> = jsonObject {
        put("query", query)
        putIfNotNull("locale", locale?.wireValue)
        put("limit", limit)
        putIfNotNull("category_id", categoryId)
        putIfNotNull("brand_id", brandId)
        putIfNotNull("price_min", priceMin)
        putIfNotNull("price_max", priceMax)
        put("in_stock_only", inStockOnly)
        putIfNotNull("city_id", cityId)
        putIfNotNull("location_id", locationId)
        putIfNotNull("variant", variant)
    }
}

/**
 * A request for the start screen (`POST /v1/search/start`): what to show under an
 * **empty** search field.
 *
 * There is no `query` property, and that is the point — an empty query is not a query.
 * It has neither a vector nor a prefix, so ranking, completions, and correction are all
 * off, and the response carries popularity rather than relevance. Hence its own endpoint
 * and its own request.
 *
 * Fields left `null` are filled from [TalqynConfiguration] — locale, place, and A/B bucket.
 *
 * @property locale The language to build the screen in. `null` uses the client default.
 * @property limit How many products to return. 1–50. Applies to
 *   [TalqynStartResponse.products] only: the other blocks are fixed in size by the
 *   server — 5 past queries, 8 popular ones, 8 categories.
 * @property cityId The shopper's city — the `id` of an option in the `city` group of
 *   [TalqynSearchApi.filters]. Products unavailable there are left out of the block
 *   rather than shown as out of stock.
 * @property locationId The shopper's store — the `id` of an option in the `location`
 *   group. Takes precedence over [cityId].
 * @property variant The storefront's A/B bucket: echoed into analytics, no effect on the screen.
 */
public data class TalqynStartQuery(
    val locale: TalqynLocale? = null,
    val limit: Int = 10,
    val cityId: String? = null,
    val locationId: String? = null,
    val variant: String? = null,
) {
    internal fun toJson(): Map<String, Any?> = jsonObject {
        putIfNotNull("locale", locale?.wireValue)
        put("limit", limit)
        putIfNotNull("city_id", cityId)
        putIfNotNull("location_id", locationId)
        putIfNotNull("variant", variant)
    }
}

/**
 * The selection criteria shared by a listing and its filter panel.
 *
 * They are shared on the server too: `/v1/search/full` and `/v1/search/filters` accept
 * one body, and the two must not drift — a panel has to count against the same
 * selection the listing displays.
 *
 * @property query What the shopper typed. 1–500 characters.
 * @property locale The language to search in. `null` uses the client default.
 * @property categoryId Restricts results to one category.
 * @property brandId Restricts results to one brand.
 * @property priceMin The lower price bound.
 * @property priceMax The upper price bound.
 * @property inStockOnly Whether to drop out-of-stock products.
 * @property hasDiscount Whether to keep only discounted products.
 * @property filters Structural filters: `{group slug: [value slugs]}`. OR within a group,
 *   AND across groups. Slugs come from [TalqynSearchApi.filters]. The contract caps this
 *   at 20 keys, 50 values per key, and 100 characters per value.
 * @property cityId The shopper's city, in your catalog's numbering.
 * @property locationId The shopper's store, in your catalog's numbering. Beats [cityId].
 */
public data class TalqynFilterCriteria(
    val query: String,
    val locale: TalqynLocale? = null,
    val categoryId: Long? = null,
    val brandId: Long? = null,
    val priceMin: Double? = null,
    val priceMax: Double? = null,
    val inStockOnly: Boolean = false,
    val hasDiscount: Boolean = false,
    val filters: Map<String, List<String>> = emptyMap(),
    val cityId: String? = null,
    val locationId: String? = null,
) {
    internal fun writeTo(builder: JsonBuilder) {
        builder.put("query", query)
        builder.putIfNotNull("locale", locale?.wireValue)
        builder.putIfNotNull("category_id", categoryId)
        builder.putIfNotNull("brand_id", brandId)
        builder.putIfNotNull("price_min", priceMin)
        builder.putIfNotNull("price_max", priceMax)
        builder.put("in_stock_only", inStockOnly)
        builder.put("has_discount", hasDiscount)
        if (filters.isNotEmpty()) builder.put("filters", filters)
        builder.putIfNotNull("city_id", cityId)
        builder.putIfNotNull("location_id", locationId)
    }
}

/**
 * A request for one page of a listing (`POST /v1/search/full`).
 *
 * @property criteria What to select.
 * @property limit The page size. 1–100.
 * @property offset Where the page starts. 0–10000.
 * @property sort How to order the page.
 * @property variant The storefront's A/B bucket.
 */
public data class TalqynFullSearchQuery(
    val criteria: TalqynFilterCriteria,
    val limit: Int = 20,
    val offset: Int = 0,
    val sort: TalqynSort = TalqynSort.Relevance,
    val variant: String? = null,
) {
    /** Creates a listing request field by field. */
    public constructor(
        query: String,
        locale: TalqynLocale? = null,
        limit: Int = 20,
        offset: Int = 0,
        sort: TalqynSort = TalqynSort.Relevance,
        filters: Map<String, List<String>> = emptyMap(),
        categoryId: Long? = null,
        brandId: Long? = null,
        priceMin: Double? = null,
        priceMax: Double? = null,
        inStockOnly: Boolean = false,
        hasDiscount: Boolean = false,
        cityId: String? = null,
        locationId: String? = null,
        variant: String? = null,
    ) : this(
        criteria = TalqynFilterCriteria(
            query = query,
            locale = locale,
            categoryId = categoryId,
            brandId = brandId,
            priceMin = priceMin,
            priceMax = priceMax,
            inStockOnly = inStockOnly,
            hasDiscount = hasDiscount,
            filters = filters,
            cityId = cityId,
            locationId = locationId,
        ),
        limit = limit,
        offset = offset,
        sort = sort,
        variant = variant,
    )

    /**
     * The same selection, shaped as a filter-panel request. Recomputing the criteria by
     * hand for the second call is how a panel ends up counting against a different
     * selection than the one on screen.
     */
    public val filtersQuery: TalqynFiltersQuery get() = TalqynFiltersQuery(criteria)

    /**
     * Returns this request advanced to the next page.
     *
     * @param response The page that just came back.
     * @return A copy positioned at the next offset, or `null` when the listing is exhausted.
     */
    public fun nextPage(after: TalqynFullSearchResponse): TalqynFullSearchQuery? =
        after.nextOffset?.let { copy(offset = it) }

    internal fun toJson(): Map<String, Any?> = jsonObject {
        criteria.writeTo(this)
        put("limit", limit)
        put("offset", offset)
        put("sort", sort.wireValue)
        putIfNotNull("variant", variant)
    }
}

/**
 * A request for facet counts (`POST /v1/search/filters`).
 *
 * The same body as a listing request, minus paging and ordering: the panel counts
 * across the whole selection, not one page of it.
 *
 * @property criteria What to count against.
 */
public data class TalqynFiltersQuery(
    val criteria: TalqynFilterCriteria,
) {
    /** Creates a facet request field by field. */
    public constructor(
        query: String,
        locale: TalqynLocale? = null,
        filters: Map<String, List<String>> = emptyMap(),
        categoryId: Long? = null,
        brandId: Long? = null,
        priceMin: Double? = null,
        priceMax: Double? = null,
        inStockOnly: Boolean = false,
        hasDiscount: Boolean = false,
        cityId: String? = null,
        locationId: String? = null,
    ) : this(
        TalqynFilterCriteria(
            query = query,
            locale = locale,
            categoryId = categoryId,
            brandId = brandId,
            priceMin = priceMin,
            priceMax = priceMax,
            inStockOnly = inStockOnly,
            hasDiscount = hasDiscount,
            filters = filters,
            cityId = cityId,
            locationId = locationId,
        ),
    )

    internal fun toJson(): Map<String, Any?> = jsonObject { criteria.writeTo(this) }
}

/**
 * A question for the consultant (`POST /v1/consultant/ask`).
 *
 * @property question The shopper's question. 1–2000 characters.
 * @property locale The language to answer in. `null` uses the client default.
 * @property sessionId The session of the previous turn, from [TalqynConsultantEvent.Done].
 *   Omit it to start a new conversation.
 * @property cityId The shopper's city. Send it on **every** turn: a session does not
 *   remember a place, because a shopper may change cities mid-conversation. The SDK
 *   fills this from the client default when it is `null`.
 * @property locationId The shopper's store. Sent per turn like [cityId], and takes precedence over it.
 * @property variant The storefront's A/B bucket.
 */
public data class TalqynConsultantQuery(
    val question: String,
    val locale: TalqynLocale? = null,
    val sessionId: String? = null,
    val cityId: String? = null,
    val locationId: String? = null,
    val variant: String? = null,
) {
    internal fun toJson(): Map<String, Any?> = jsonObject {
        put("question", question)
        putIfNotNull("locale", locale?.wireValue)
        putIfNotNull("session_id", sessionId)
        putIfNotNull("city_id", cityId)
        putIfNotNull("location_id", locationId)
        putIfNotNull("variant", variant)
    }
}
