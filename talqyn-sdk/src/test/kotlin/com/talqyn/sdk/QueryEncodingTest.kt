package com.talqyn.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueryEncodingTest {
    /** Through the writer and back: what goes on the wire, not what the builder holds. */
    private fun encode(body: Map<String, Any?>): Map<*, *> = TalqynJson.parse(TalqynJson.encode(body)) as Map<*, *>

    @Test
    fun searchQueryUsesContractFieldNames() {
        val json = encode(
            TalqynSearchQuery(
                query = "iphone 15",
                locale = TalqynLocale.Ru,
                limit = 10,
                categoryId = 42,
                priceMin = 1000.0,
                cityId = "10",
                variant = "b",
            ).toJson(),
        )
        assertEquals("iphone 15", json["query"])
        assertEquals("ru", json["locale"])
        assertEquals(10L, json["limit"])
        assertEquals(42L, json["category_id"])
        assertEquals(1000.0, (json["price_min"] as Number).toDouble(), 0.0)
        assertEquals("10", json["city_id"])
        assertEquals(false, json["in_stock_only"])
        assertEquals("b", json["variant"])
        assertNull(json["brand_id"])
        assertNull(json["location_id"])
        assertNull(json["price_max"])
    }

    /** A whole price goes out without a fraction, and a fractional one keeps it. */
    @Test
    fun pricesAreWrittenAsTheContractReadsThem() {
        assertEquals(
            """{"query":"x","limit":20,"price_min":300000,"price_max":1.5,"in_stock_only":false}""",
            TalqynJson.encode(TalqynSearchQuery(query = "x", priceMin = 300000.0, priceMax = 1.5).toJson()),
        )
    }

    @Test
    fun fullSearchQueryOmitsEmptyFilters() {
        val bare = encode(TalqynFullSearchQuery(query = "smartphone").toJson())
        assertNull(bare["filters"])
        assertEquals("relevance", bare["sort"])
        assertEquals(0L, bare["offset"])
        assertEquals(20L, bare["limit"])
        assertEquals(false, bare["has_discount"])

        val filtered = encode(
            TalqynFullSearchQuery(
                query = "smartphone",
                limit = 24,
                sort = TalqynSort.PriceAscending,
                filters = mapOf("brand" to listOf("apple", "samsung")),
            ).toJson(),
        )
        assertEquals("price_asc", filtered["sort"])
        assertEquals(24L, filtered["limit"])
        assertEquals(listOf("apple", "samsung"), (filtered["filters"] as Map<*, *>)["brand"])
    }

    /** The filter panel counts against the same selection as the listing. */
    @Test
    fun filtersQueryDerivedFromListingKeepsCriteria() {
        val listing = TalqynFullSearchQuery(
            query = "smartphone",
            limit = 24,
            offset = 48,
            sort = TalqynSort.PriceDescending,
            filters = mapOf("brand" to listOf("apple")),
            categoryId = 7,
            cityId = "10",
        )
        val json = encode(listing.filtersQuery.toJson())
        assertEquals("smartphone", json["query"])
        assertEquals(7L, json["category_id"])
        assertEquals("10", json["city_id"])
        assertEquals(listOf("apple"), (json["filters"] as Map<*, *>)["brand"])
        // The facet panel has no limit/offset/sort: it counts the whole selection.
        assertNull(json["limit"])
        assertNull(json["offset"])
        assertNull(json["sort"])
    }

    @Test
    fun consultantQueryFieldNames() {
        val json = encode(
            TalqynConsultantQuery(question = "need a laptop", locale = TalqynLocale.Kk, sessionId = "abc12345", locationId = "77").toJson(),
        )
        assertEquals("need a laptop", json["question"])
        assertEquals("kk", json["locale"])
        assertEquals("abc12345", json["session_id"])
        assertEquals("77", json["location_id"])
        assertNull(json["city_id"])
    }

    @Test
    fun eventEncoding() {
        val click = encode(TalqynProductClickEvent(searchId = "6c5f2e8a", talqynId = 1234, position = 3, source = TalqynEventSource.Consultant).toJson())
        assertEquals("6c5f2e8a", click["search_id"])
        assertEquals(1234L, click["talqyn_id"])
        assertEquals(3L, click["position"])
        assertEquals("cip", click["source"])

        val submit = encode(TalqynSearchSubmitEvent(query = "iphone", source = TalqynEventSource.Instant, locale = TalqynLocale.Ru, resultsCount = 8).toJson())
        assertEquals(8L, submit["results_count"])
        assertEquals("instant", submit["source"])

        val category = encode(TalqynCategoryClickEvent(categoryId = 42, query = "iphone").toJson())
        assertEquals(42L, category["category_id"])
    }

    @Test
    fun nextPageAdvancesOffset() {
        val query = TalqynFullSearchQuery(query = "smartphone", limit = 24)
        val empty = TalqynFullSearchResponse.decode(
            TalqynJson.parseObject(
                """{"query":"smartphone","locale":"ru","offset":0,"limit":24,"sort":"relevance","total":50,"results":[]}""".toByteArray(),
            ),
        )
        assertNull("an empty page ends the listing, or pagination loops", query.nextPage(empty))

        val withItems = TalqynFullSearchResponse.decode(
            TalqynJson.parseObject(
                """{"query":"s","locale":"ru","offset":0,"limit":2,"sort":"relevance","total":5,"results":[{"talqyn_id":1,"title":"a"},{"talqyn_id":2,"title":"b"}]}""".toByteArray(),
            ),
        )
        assertEquals(2, query.nextPage(withItems)?.offset)
    }

    /**
     * The catalog is translated into three languages; every other app language gets the default
     * rather than a language the catalog is not written in.
     */
    @Test
    fun matchingMapsAnAppLanguageOntoASupportedLocale() {
        assertEquals(TalqynLocale.Kk, TalqynLocale.matching("kk"))
        assertEquals(TalqynLocale.Kk, TalqynLocale.matching("kk-KZ"))
        assertEquals(TalqynLocale.Kk, TalqynLocale.matching("KK"))
        assertEquals(TalqynLocale.Ru, TalqynLocale.matching("ru-RU"))
        assertEquals(TalqynLocale.En, TalqynLocale.matching("en-US"))
        assertEquals(TalqynLocale.En, TalqynLocale.matching("de"))
        assertEquals(TalqynLocale.En, TalqynLocale.matching(null))
    }
}
