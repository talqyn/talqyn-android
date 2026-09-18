package com.talqyn.sdk

import java.util.Locale

/**
 * The language a request is served in.
 *
 * The contract accepts exactly three values, `en`, `ru`, and `kk`; anything
 * else is rejected with `422`. That is why this is a closed enumeration rather
 * than an extensible wrapper: an unknown locale is a programming error, not a
 * value to pass through.
 */
public enum class TalqynLocale(public val wireValue: String) {
    /** English. The default. */
    En("en"),

    /** Russian. */
    Ru("ru"),

    /** Kazakh. */
    Kk("kk"),
    ;

    public companion object {
        /**
         * Maps an app language code onto a supported locale.
         *
         * Anything that is neither Kazakh nor Russian resolves to [En]: a
         * tenant catalog is translated into these three languages only, so
         * falling back to the default locale is the only meaningful answer for
         * the rest.
         *
         * @param languageCode A language identifier such as `"kk"`, `"kk-KZ"`,
         *   or `Locale.getDefault().language`. May be `null`.
         */
        @JvmStatic
        public fun matching(languageCode: String?): TalqynLocale {
            val code = languageCode?.lowercase(Locale.ROOT) ?: return En
            return when {
                code.startsWith("kk") -> Kk
                code.startsWith("ru") -> Ru
                else -> En
            }
        }
    }
}

/** The ordering applied to a listing request: the `sort` field of `POST /v1/search/full`. */
public enum class TalqynSort(public val wireValue: String) {
    /** Server-side relevance ranking. The default. */
    Relevance("relevance"),

    /** Cheapest first. */
    PriceAscending("price_asc"),

    /** Most expensive first. */
    PriceDescending("price_desc"),

    /** Deepest discount first. */
    DiscountDescending("discount_desc"),
}
