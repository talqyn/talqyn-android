package com.talqyn.sdk

/**
 * A query completion offered under the search field.
 *
 * @property text The suggested query text.
 * @property weight How heavily the suggestion is weighted. Ordering is already applied by the server.
 * @property highlightFrom The character offset at which the suggestion diverges from
 *   what the shopper typed: everything before it is their input, after it the completion.
 */
public data class TalqynSuggestion(
    val text: String,
    val weight: Int,
    val highlightFrom: Int,
) {
    internal companion object {
        fun decode(json: JsonObject): TalqynSuggestion = TalqynSuggestion(
            text = json.string("text") ?: "",
            weight = json.int("weight") ?: 0,
            highlightFrom = json.int("highlight_from") ?: 0,
        )
    }
}

/**
 * A facet chip offered under the search field.
 *
 * @property text The chip label, also the text to search for when it is tapped.
 * @property weight How heavily the chip is weighted. Ordering is already applied by the server.
 */
public data class TalqynChip(
    val text: String,
    val weight: Int,
) {
    internal companion object {
        fun decode(json: JsonObject): TalqynChip = TalqynChip(
            text = json.string("text") ?: "",
            weight = json.int("weight") ?: 0,
        )
    }
}

/**
 * A category in the navigation block of a search response.
 *
 * @property id The category id, as accepted by the `categoryId` request parameter.
 * @property name The category name in the requested locale.
 * @property slug The category slug, when the catalog carries one.
 * @property path The materialized tree path, for example `"1.42"`.
 * @property parentName The parent category's name, for disambiguating same-named leaves.
 */
public data class TalqynCategory(
    val id: Long,
    val name: String,
    val slug: String? = null,
    val path: String? = null,
    val parentName: String? = null,
) {
    internal companion object {
        fun decode(json: JsonObject): TalqynCategory = TalqynCategory(
            id = json.long("id") ?: 0,
            name = json.string("name") ?: "",
            slug = json.string("slug"),
            path = json.string("path"),
            parentName = json.string("parent_name"),
        )
    }
}

/**
 * A brand in the navigation block of a search response.
 *
 * @property id The brand id, as accepted by the `brandId` request parameter.
 * @property name The brand's display name.
 * @property slug The brand slug, as accepted by `filters["brand"]` on a listing request.
 * @property logoUrl The brand logo, or `null` when the brand has none.
 */
public data class TalqynBrand(
    val id: Long,
    val name: String,
    val slug: String? = null,
    val logoUrl: String? = null,
) {
    internal companion object {
        fun decode(json: JsonObject): TalqynBrand = TalqynBrand(
            id = json.long("id") ?: 0,
            name = json.string("name") ?: "",
            slug = json.string("slug"),
            logoUrl = TalqynUrls.lenient(json.string("logo_url")),
        )
    }
}

/**
 * The result of `POST /v1/search/` — instant search for a search field with a dropdown.
 *
 * @property searchId The impression id. Send it back in [TalqynProductClickEvent.searchId]:
 *   without it a click has no denominator and search click-through cannot be computed.
 * @property query The query the results were produced for. Differs from what was sent
 *   when [correctedFrom] is set.
 * @property locale The locale the results were produced in, as its wire value.
 * @property total How many products matched in total, beyond the ones returned.
 * @property results The top matches, already ranked.
 * @property suggestions Query completions for the search field.
 * @property chips Facet chips for the search field.
 * @property showcase Editorial queries shown for an empty search field. These are
 *   **suggestions**, not products.
 * @property categories Categories worth navigating to for this query.
 * @property brands Brands worth navigating to for this query.
 * @property history This shopper's earlier queries. Empty until the token names a
 *   shopper — not under [TalqynDeviceIdentity.Guest] — and the storefront reports
 *   submitted queries through [TalqynEventsApi.searchSubmit]: the block is assembled
 *   from those very events.
 * @property correctedFrom The original text, when the server quietly searched for the
 *   top suggestion instead — a short or misspelled query. [results] already reflect the
 *   corrected text; show this to offer "search for … instead".
 */
public data class TalqynSearchResponse(
    val searchId: String,
    val query: String,
    val locale: String,
    val total: Int,
    val results: List<TalqynProduct>,
    val suggestions: List<TalqynSuggestion>,
    val chips: List<TalqynChip>,
    val showcase: List<TalqynSuggestion>,
    val categories: List<TalqynCategory>,
    val brands: List<TalqynBrand>,
    val history: List<String>,
    val correctedFrom: String?,
) {
    internal companion object {
        /** Tolerates absent fields. A card that does not decode is dropped on its own; the rest of the list stays. */
        fun decode(json: JsonObject): TalqynSearchResponse = TalqynSearchResponse(
            searchId = json.string("search_id") ?: "",
            query = json.string("query") ?: "",
            locale = json.string("locale") ?: TalqynLocale.En.wireValue,
            total = json.int("total") ?: 0,
            results = json.objects("results", TalqynProduct::decode),
            suggestions = json.objects("suggestions", TalqynSuggestion::decode),
            chips = json.objects("chips", TalqynChip::decode),
            showcase = json.objects("showcase", TalqynSuggestion::decode),
            categories = json.objects("categories", TalqynCategory::decode),
            brands = json.objects("brands", TalqynBrand::decode),
            history = json.strings("history"),
            correctedFrom = json.string("corrected_from"),
        )
    }
}

/**
 * The result of `POST /v1/search/start` — what to show under an **empty** search field.
 *
 * Four independent blocks; any of them can come back empty. Only [products] is ranked at
 * all, and by popularity rather than relevance, which is why its cards carry no
 * [TalqynProduct.score].
 *
 * @property searchId The impression id for this screen. Send it back in
 *   [TalqynProductClickEvent.searchId] with [TalqynEventSource.Start]: without it a card
 *   tap has no denominator and the screen's click-through cannot be computed.
 * @property locale The locale the screen was built in, as its wire value.
 * @property history This shopper's recent queries, most recently used first. Empty until
 *   the token names a shopper — not under [TalqynDeviceIdentity.Guest] — and the
 *   storefront reports submitted queries through [TalqynEventsApi.searchSubmit]: the
 *   block is assembled from those very events.
 * @property popularQueries What this storefront searches for, over the last 30 days. A
 *   freshly connected storefront has no traffic yet, so the block stands on the curated
 *   corpus until it does.
 * @property categories Root categories carrying live products, the largest first. Stock
 *   and place are not applied here — the listing behind a tap applies them itself.
 * @property products Popular products, by clicks over the last 30 days; a storefront
 *   without clicks yet falls back to reviews and ratings. Only products in stock where the
 *   shopper is — the city or store of the request, anywhere when it named neither.
 */
public data class TalqynStartResponse(
    val searchId: String,
    val locale: String,
    val history: List<String>,
    val popularQueries: List<String>,
    val categories: List<TalqynCategory>,
    val products: List<TalqynProduct>,
) {
    internal companion object {
        /** Tolerates absent fields. A card that does not decode is dropped on its own; the rest of the block stays. */
        fun decode(json: JsonObject): TalqynStartResponse = TalqynStartResponse(
            searchId = json.string("search_id") ?: "",
            locale = json.string("locale") ?: TalqynLocale.En.wireValue,
            history = json.strings("history"),
            popularQueries = json.strings("popular_queries"),
            categories = json.objects("categories", TalqynCategory::decode),
            products = json.objects("products", TalqynProduct::decode),
        )
    }
}

/**
 * The result of `POST /v1/search/full` — one page of a listing.
 *
 * @property searchId The impression id, present on the **first** page only
 *   (`offset == 0`). Later pages continue that impression rather than starting a new
 *   one, so a click from any page reports this same id.
 * @property query The query the page was produced for.
 * @property locale The locale the page was produced in.
 * @property offset The offset this page starts at.
 * @property limit The page size that was requested.
 * @property sort The ordering that was applied, as its wire value.
 * @property total How many products match the criteria in total.
 * @property results The products on this page.
 */
public data class TalqynFullSearchResponse(
    val searchId: String?,
    val query: String,
    val locale: String,
    val offset: Int,
    val limit: Int,
    val sort: String,
    val total: Int,
    val results: List<TalqynProduct>,
) {
    /**
     * Whether another page can be requested. An empty page ends the listing even when
     * [total] promises more: otherwise pagination would loop on the same offset forever.
     */
    public val hasMore: Boolean get() = results.isNotEmpty() && offset + results.size < total

    /** The offset of the next page, or `null` when the listing is exhausted. */
    public val nextOffset: Int? get() = if (hasMore) offset + results.size else null

    internal companion object {
        fun decode(json: JsonObject): TalqynFullSearchResponse = TalqynFullSearchResponse(
            searchId = json.string("search_id"),
            query = json.string("query") ?: "",
            locale = json.string("locale") ?: TalqynLocale.En.wireValue,
            offset = json.int("offset") ?: 0,
            limit = json.int("limit") ?: 0,
            sort = json.string("sort") ?: TalqynSort.Relevance.wireValue,
            total = json.int("total") ?: 0,
            results = json.objects("results", TalqynProduct::decode),
        )
    }
}
