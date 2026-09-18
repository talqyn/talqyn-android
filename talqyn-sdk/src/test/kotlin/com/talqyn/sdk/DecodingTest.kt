package com.talqyn.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Responses are taken from the contract (`docs/public_api.md`): this test pins the models to it. */
class DecodingTest {
    private fun json(text: String): JsonObject = TalqynJson.parseObject(text.toByteArray())

    @Test
    fun instantSearchResponse() {
        val response = TalqynSearchResponse.decode(
            json(
                """
                {
                  "search_id": "6c5f2e8a-0000-4000-8000-000000000000",
                  "query": "iphone 15", "locale": "ru", "total": 8,
                  "results": [{
                    "talqyn_id": 1234, "external_id": "256073",
                    "title": "Apple iPhone 15 128GB",
                    "slug": "apple-iphone-15-128gb", "brand_name": "Apple",
                    "brand_id": 7, "brand_slug": "apple", "brand_logo_url": null,
                    "category_path": ["Phones and gadgets", "Phones"],
                    "price": 449990, "price_before": 479990, "in_stock": true,
                    "rating": 4.8, "reviews_count": 213,
                    "image_url": "https://cdn.example.com/1.jpg", "url": "https://shop.example.com/p/1",
                    "score": 0.87
                  }],
                  "suggestions": [{"text": "iphone 15 pro", "weight": 12, "highlight_from": 8}],
                  "chips": [{"text": "Apple", "weight": 5}],
                  "showcase": [{"text": "headphones", "weight": 3, "highlight_from": 0}],
                  "categories": [{"id": 42, "name": "Phones", "slug": "smartfony", "path": "1.42", "parent_name": "Phones and gadgets"}],
                  "brands": [{"id": 7, "name": "Apple", "slug": "apple", "logo_url": null}],
                  "history": ["headphones"],
                  "corrected_from": "iphone15"
                }
                """,
            ),
        )

        assertEquals("6c5f2e8a-0000-4000-8000-000000000000", response.searchId)
        assertEquals(8, response.total)
        assertEquals("iphone15", response.correctedFrom)
        assertEquals(listOf("headphones"), response.history)
        // showcase carries suggestions, not products.
        assertEquals("headphones", response.showcase.first().text)
        assertEquals(8, response.suggestions.first().highlightFrom)
        assertEquals("Phones and gadgets", response.categories.first().parentName)

        val product = response.results.first()
        assertEquals(1234, product.talqynId)
        assertEquals("256073", product.externalId)
        assertEquals("apple", product.brandSlug)
        assertEquals(2, product.categoryPath.size)
        assertEquals(449_990.0, product.price!!, 0.0)
        assertEquals(true, product.inStock)
        assertTrue(product.hasDiscount)
        assertEquals("https://cdn.example.com/1.jpg", product.imageUrl)
        assertEquals("https://shop.example.com/p/1", product.productUrl)
        assertNull(product.brandLogoUrl)
    }

    @Test
    fun productAcceptsLegacyProductIdAlias() {
        val product = TalqynProduct.decode(json("""{"product_id": 55, "title": "x"}"""))
        assertEquals(55, product.talqynId)
        assertNull("a card that does not mention stock is not out of stock", product.inStock)
        assertEquals(0, product.reviewsCount)
        assertNull(product.price)
        assertFalse(product.hasDiscount)
    }

    @Test
    fun productWithoutIdentifierIsRejected() {
        expectFailure<TalqynJsonException> { TalqynProduct.decode(json("""{"title": "x"}""")) }
    }

    /** One malformed card costs that card, not the page it came in. */
    @Test
    fun malformedCardIsDroppedNotThePage() {
        val response = TalqynSearchResponse.decode(
            json(
                """
                {"search_id": "s", "query": "x", "locale": "ru", "total": 4,
                 "results": [
                   {"talqyn_id": 1, "title": "a"},
                   {"title": "no identifier"},
                   null,
                   "not even an object",
                   {"talqyn_id": 2, "title": "b"}
                 ]}
                """,
            ),
        )
        assertEquals(listOf(1L, 2L), response.results.map { it.talqynId })
        assertEquals(4, response.total)
    }

    @Test
    fun productUrlWithCyrillicPath() {
        val product = TalqynProduct.decode(json("""{"talqyn_id":1,"title":"x","url":"https://shop.kz/товар/1"}"""))
        assertNotNull(product.productUrl)
    }

    /** A space is not a legal URL character; the image must not vanish over it. */
    @Test
    fun imageUrlWithSpacesIsEncodedNotDropped() {
        val product = TalqynProduct.decode(json("""{"talqyn_id":1,"title":"x","image_url":"https://cdn.kz/a b.jpg"}"""))
        assertEquals("https://cdn.kz/a%20b.jpg", product.imageUrl)
    }

    @Test
    fun filtersResponseGroupsAndPlaceHelpers() {
        val response = TalqynFiltersResponse.decode(
            json(
                """
                {"groups": [
                  {"slug": "brand", "label": "Brand", "type": "list",
                   "options": [
                     {"slug": "apple", "label": "Apple", "count": 42, "state": "active"},
                     {"slug": "samsung", "label": "Samsung", "count": 0, "state": "disabled"}
                   ]},
                  {"slug": "price", "label": "Price", "type": "range",
                   "options": [], "min": 15000, "max": 890000, "selected_min": null, "selected_max": null},
                  {"slug": "city", "label": "City", "type": "list",
                   "options": [{"slug": "almaty", "label": "Almaty", "id": "10", "count": 297, "state": "enabled"}]},
                  {"slug": "location", "label": "Store", "type": "list",
                   "options": [{"slug": "5", "label": "Mega Mall", "id": "2f5f", "city_slug": "almaty", "count": 12, "state": "enabled"}]}
                ]}
                """,
            ),
        )

        assertEquals(4, response.groups.size)
        assertEquals(listOf("brand", "price"), response.panelGroups.map { it.slug })
        assertEquals(15000.0, response.priceGroup?.min!!, 0.0)
        assertEquals(TalqynFilterGroup.Kind.Range, response.priceGroup?.type)
        assertEquals("10", response.cityGroup?.options?.first()?.id)
        assertEquals("almaty", response.locationGroup?.options?.first()?.citySlug)
        assertEquals(mapOf("brand" to listOf("apple")), response.selectedFilters)

        val samsung = response.group("brand")!!.options.last()
        assertTrue(samsung.isDisabled)
        assertFalse(samsung.isSelected)
    }

    @Test
    fun unknownFilterKindAndStateSurviveDecoding() {
        val response = TalqynFiltersResponse.decode(
            json("""{"groups": [{"slug": "x", "type": "colorpicker", "options": [{"slug": "a", "state": "highlighted"}]}]}"""),
        )
        assertEquals("colorpicker", response.groups.first().type.rawValue)
        assertEquals("highlighted", response.groups.first().options.first().state.rawValue)
    }

    @Test
    fun consultantJsonAnswer() {
        val answer = TalqynConsultantAnswer.decode(
            json(
                """
                {"tenant_id": "3f2a", "answer": "Here are the options [p:1]", "session_id": "a1b2c3d4",
                 "products": [{"talqyn_id": 1, "title": "Laptop"}],
                 "fallback_reason": null, "clarify": null, "groups": null,
                 "redirect_query": null, "actions": [
                    {"type": "apply_filters", "filters": {"category_id": 5, "filters": {"ram": ["16"]}, "attrs": {"ram": "16"}}}
                 ],
                 "follow_ups": ["show cheaper ones"], "search_id": "s1"}
                """,
            ),
        )
        assertEquals("a1b2c3d4", answer.sessionId)
        assertEquals(1, answer.products.size)
        assertEquals(listOf("show cheaper ones"), answer.followUps)
        assertFalse(answer.isFallback)

        val filters = (answer.actions.first() as? TalqynConsultantAction.ApplyFilters)?.filters
            ?: throw AssertionError("expected an apply_filters action")
        assertEquals(5L, filters.categoryId)
        assertEquals(listOf("16"), filters.filters["ram"])
        assertEquals("16", filters.attributes["ram"])

        val criteria = filters.criteria(query = "laptop", cityId = "10")
        assertEquals("laptop", criteria.query)
        assertEquals(5L, criteria.categoryId)
        assertEquals("10", criteria.cityId)
    }

    @Test
    fun fallbackAnswerCarriesReason() {
        val answer = TalqynConsultantAnswer.decode(
            json("""{"tenant_id": "3f2a", "answer": "", "products": [], "fallback_reason": "user_budget_exceeded"}"""),
        )
        assertTrue(answer.isFallback)
        assertEquals(TalqynFallbackReason.UserBudgetExceeded, answer.fallbackReason)
        assertEquals(true, answer.fallbackReason?.isBudgetExhausted)
    }

    @Test
    fun chatTranscriptHydratesProductsPerMessage() {
        val transcript = TalqynChatTranscript.decode(
            json(
                """
                {"session_id": "s1", "title": "laptop for school",
                 "messages": [
                   {"role": "user", "text": "need a laptop", "talqyn_ids": [], "route": "consult", "created_at": "2026-08-26T12:00:00Z"},
                   {"role": "assistant", "text": "here", "talqyn_ids": [2, 1], "created_at": "2026-08-26T12:00:03.512Z"}
                 ],
                 "products": [{"talqyn_id": 1, "title": "A"}, {"talqyn_id": 2, "title": "B"}]}
                """,
            ),
        )
        assertEquals(2, transcript.messages.size)
        assertEquals(TalqynChatMessage.Route.Consult, transcript.messages[0].route)
        assertFalse(transcript.messages[0].isRedirect)
        assertNotNull(transcript.messages[0].createdAt)
        // Postgres fractional seconds parse too.
        assertNotNull(transcript.messages[1].createdAt)
        assertEquals(listOf("B", "A"), transcript.productsFor(transcript.messages[1]).map { it.title })
    }

    /** A message nobody wrote costs that message, not the transcript. */
    @Test
    fun messageWithoutRoleIsDroppedAlone() {
        val transcript = TalqynChatTranscript.decode(
            json("""{"session_id":"s1","messages":[{"text":"orphan"},{"role":"user","text":"q"}],"products":[]}"""),
        )
        assertEquals(listOf("q"), transcript.messages.map { it.text })
    }

    @Test
    fun chatSummaryList() {
        val chats = TalqynJson.parse(
            """
            [{"session_id": "s1", "title": null, "message_count": 4,
              "created_at": "2026-08-26T12:00:00Z", "last_message_at": "2026-08-26T12:10:00Z"}]
            """,
        ).asJsonObjects(TalqynChatSummary::decode)
        assertEquals(4, chats.first().messageCount)
        assertNull(chats.first().title)
        assertNotNull(chats.first().lastMessageAt)
    }

    @Test
    fun deviceTokenResponse() {
        val token = TalqynDeviceToken.decode(
            json(
                """
                {"token": "tlqd_eyJ", "expires_at": "2026-08-26T12:15:00Z", "expires_in": 900,
                 "user_id": "6f1c2b9a-3e47-4b8f-9a10-2c5d8e7f4a01"}
                """,
            ),
        )
        assertEquals("tlqd_eyJ", token.token)
        assertEquals(900.seconds, token.expiresIn)
        assertNotNull(token.expiresAt)
        assertEquals("6f1c2b9a-3e47-4b8f-9a10-2c5d8e7f4a01", token.userId)
        assertFalse("the token stays out of logs", token.toString().contains("tlqd_eyJ"))
    }

    /** The reader against the corners of JSON the writer never produces but a server or proxy might. */
    @Test
    fun jsonReaderHandlesEscapesNumbersAndRejectsGarbage() {
        val parsed = TalqynJson.parse("""{"a":"Ж\n\"x\"","b":-1.5e2,"c":9007199254740993,"d":[true,false,null]}""") as Map<*, *>
        assertEquals("Ж\n\"x\"", parsed["a"])
        assertEquals(-150.0, parsed["b"] as Double, 0.0)
        assertEquals(9007199254740993L, parsed["c"])
        assertEquals(listOf(true, false, null), parsed["d"])
        expectFailure<TalqynJsonException> { TalqynJson.parse("{\"a\":1,}") }
        expectFailure<TalqynJsonException> { TalqynJson.parse("[1] 2") }
        expectFailure<TalqynJsonException> { TalqynJson.parse("[".repeat(10_000)) }
    }
}
