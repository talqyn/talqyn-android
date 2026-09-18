package com.talqyn.sdk

/**
 * A product card.
 *
 * The same shape everywhere Talqyn returns products: instant search, listings, the
 * consultant's results, and chat transcripts.
 *
 * @property talqynId Talqyn's internal product id. It does not exist in your catalog.
 *   Its only purpose is to tie Talqyn's own responses together: the `[p:ID]` markers
 *   in consultant text, [TalqynProductClickEvent.talqynId], comparison tables, and
 *   transcript hydration. To act on a product in **your** world, use [externalId].
 *   A 64-bit number, as on the server: an `Int` would lose every product past 2³¹.
 * @property externalId The product id in **your** system — the offer id or SKU you
 *   supplied in the feed or push. Everything you do on your side keys off this.
 *   Optional by contract: whether to display a card without one is the app's
 *   decision, not the SDK's.
 * @property title The product title in the requested locale.
 * @property slug The product slug, when the catalog carries one.
 * @property brandName The brand's display name.
 * @property brandId The brand in the form the `brandId` request parameter accepts.
 * @property brandSlug The brand in the form `filters["brand"]` accepts on a listing request.
 * @property brandLogoUrl The brand's logo, or `null` when the brand has none.
 * @property categoryPath The category path from root to leaf, in the requested locale.
 * @property price The current price.
 * @property priceBefore The price before the discount, or `null` when there is no discount.
 * @property inStock Whether the product is in stock for the place the request named.
 *   `null` when the response did not say. Only an explicit `false` means the product
 *   cannot be bought: a card without the field is not marked as out of stock.
 * @property rating The average review score.
 * @property reviewsCount How many reviews the score is based on.
 * @property imageUrl The product image.
 * @property productUrl The product page on your storefront.
 * @property score The relevance score. `null` indicates degraded results — the
 *   reranker was unavailable and the order comes from RRF instead. Scores are not
 *   comparable across responses.
 */
public data class TalqynProduct(
    val talqynId: Long,
    val externalId: String? = null,
    val title: String,
    val slug: String? = null,
    val brandName: String? = null,
    val brandId: Long? = null,
    val brandSlug: String? = null,
    val brandLogoUrl: String? = null,
    val categoryPath: List<String> = emptyList(),
    val price: Double? = null,
    val priceBefore: Double? = null,
    val inStock: Boolean? = null,
    val rating: Double? = null,
    val reviewsCount: Int = 0,
    val imageUrl: String? = null,
    val productUrl: String? = null,
    val score: Double? = null,
) {
    /** Whether there is a struck-through price to show. */
    public val hasDiscount: Boolean
        get() = price != null && priceBefore != null && priceBefore > price

    internal companion object {
        /**
         * Decodes a product card. Every field except the identifier tolerates being
         * absent or null. `product_id` is the legacy name of `talqyn_id`: the server
         * still accepts both, so a response carrying the old one must not break.
         */
        fun decode(json: JsonObject): TalqynProduct {
            val id = json.long("talqyn_id") ?: json.long("product_id")
                ?: throw TalqynJsonException("product card carries no talqyn_id")
            return TalqynProduct(
                talqynId = id,
                externalId = json.string("external_id"),
                title = json.string("title") ?: "",
                slug = json.string("slug"),
                brandName = json.string("brand_name"),
                brandId = json.long("brand_id"),
                brandSlug = json.string("brand_slug"),
                brandLogoUrl = TalqynUrls.lenient(json.string("brand_logo_url")),
                categoryPath = json.strings("category_path"),
                price = json.double("price"),
                priceBefore = json.double("price_before"),
                inStock = json.bool("in_stock"),
                rating = json.double("rating"),
                reviewsCount = json.int("reviews_count") ?: 0,
                imageUrl = TalqynUrls.lenient(json.string("image_url")),
                productUrl = TalqynUrls.lenient(json.string("url")),
                score = json.double("score"),
            )
        }
    }
}
