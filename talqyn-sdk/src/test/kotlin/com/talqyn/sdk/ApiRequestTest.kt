package com.talqyn.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Endpoints: path, body, headers, and how defaults are filled in. */
class ApiRequestTest {
    private val emptySearch = """{"search_id":"s","query":"x","locale":"ru","total":0,"results":[]}"""
    private val emptyListing = """{"query":"x","locale":"ru","offset":0,"limit":20,"sort":"relevance","total":0,"results":[]}"""

    @Test
    fun everyRequestCarriesTheTokenAndARequestId() = runBlocking {
        val transport = StubTransport()
        val talqyn = TestFixtures.preparedClient(transport)
        transport.enqueue(emptySearch)
        talqyn.search.search("iphone")

        val sent = transport.sent[0]
        assertEquals("Bearer tlqd_test", sent.header("Authorization"))
        assertNotNull(sent.header("X-Request-ID"))
        assertEquals("application/json", sent.header("Accept"))
        assertEquals(Talqyn.CLIENT_HEADER, sent.header("X-Talqyn-SDK"))
    }

    /** Support cannot narrow a report to a build without this, so it travels on the mint too. */
    @Test
    fun theMintCarriesTheSdkVersion() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        val talqyn = TestFixtures.client(transport = transport)
        talqyn.prepare()

        val header = transport.sent[0].header("X-Talqyn-SDK")
        assertEquals(Talqyn.CLIENT_HEADER, header)
        assertTrue("got $header", header?.endsWith("/" + Talqyn.VERSION) == true)
    }

    @Test
    fun searchPathsAndBodies() = runBlocking {
        val transport = StubTransport()
        transport.enqueue(emptySearch)
        transport.enqueue(emptyListing)
        transport.enqueue("""{"groups":[]}""")

        val talqyn = TestFixtures.preparedClient(transport)
        talqyn.search.search("iphone")
        talqyn.search.full(TalqynFullSearchQuery(query = "smartphone", sort = TalqynSort.PriceAscending))
        talqyn.search.filters(TalqynFiltersQuery(query = "smartphone"))

        assertEquals(listOf("/v1/search/", "/v1/search/full", "/v1/search/filters"), transport.sent.map { it.path })
        assertEquals("iphone", transport.sent[0].bodyJson["query"])
        assertEquals("price_asc", transport.sent[1].bodyJson["sort"])
    }

    @Test
    fun listingWithFiltersSendsSameCriteria() = runBlocking {
        val transport = StubTransport()
        transport.enqueue(emptyListing)
        transport.enqueue("""{"groups":[]}""")

        val talqyn = TestFixtures.preparedClient(transport)
        val query = TalqynFullSearchQuery(query = "smartphone", limit = 24, filters = mapOf("brand" to listOf("apple")), categoryId = 7)
        talqyn.search.listingWithFilters(query)

        assertEquals(2, transport.sent.size)
        for (body in transport.sent.map { it.bodyJson }) {
            assertEquals("smartphone", body["query"])
            assertEquals(7L, body["category_id"])
            assertEquals(listOf("apple"), (body["filters"] as Map<*, *>)["brand"])
        }
    }

    @Test
    fun consultantJsonModeAddsStreamFalse() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("""{"tenant_id":"t","answer":"answer","products":[]}""")

        val talqyn = TestFixtures.preparedClient(transport)
        val answer = talqyn.consultant.answer(TalqynConsultantQuery(question = "hi"))

        assertEquals("answer", answer.answer)
        assertEquals("/v1/consultant/ask", transport.sent[0].path)
        assertEquals("stream=false", transport.sent[0].query)
    }

    @Test
    fun chatEndpoints() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("[]")
        transport.enqueue("""{"session_id":"s1","messages":[],"products":[]}""")
        transport.enqueue("")

        val talqyn = TestFixtures.preparedClient(transport)
        talqyn.consultant.chats(limit = 5, offset = 10)
        talqyn.consultant.chat(sessionId = "s1", locale = TalqynLocale.Kk)
        talqyn.consultant.deleteChat(sessionId = "s1")

        assertEquals("/v1/consultant/chats", transport.sent[0].path)
        assertEquals("limit=5&offset=10", transport.sent[0].query)
        assertEquals("GET", transport.sent[0].request.method)
        assertEquals("/v1/consultant/chats/s1", transport.sent[1].path)
        assertEquals("locale=kk", transport.sent[1].query)
        assertEquals("DELETE", transport.sent[2].request.method)
    }

    @Test
    fun eventEndpoints() = runBlocking {
        val transport = StubTransport()
        repeat(3) { transport.enqueue("", status = 204) }

        val talqyn = TestFixtures.preparedClient(transport)
        talqyn.events.productClick(TalqynProductClickEvent(searchId = "s1", talqynId = 1, position = 0, source = TalqynEventSource.Instant))
        talqyn.events.searchSubmit(TalqynSearchSubmitEvent(query = "iphone", source = TalqynEventSource.Instant, resultsCount = 8))
        talqyn.events.categoryClick(TalqynCategoryClickEvent(categoryId = 42))

        assertEquals(
            listOf("/v1/events/product-click", "/v1/events/search", "/v1/events/category-click"),
            transport.sent.map { it.path },
        )
        assertEquals("en", transport.sent[1].bodyJson["locale"])
    }

    /** Fire and forget reaches the network without the caller waiting, and a failure reaches the log, not the caller. */
    @Test
    fun trackDeliversAndSwallowsFailures() = runBlocking {
        val transport = StubTransport()
        transport.enqueue("", status = 204)
        transport.enqueue("""{"detail":"missing the 'events' scope"}""", status = 403)
        val logs = LogCollector()
        transport.prepend("""{"token":"tlqd_test","expires_at":"2026-08-26T12:15:00Z","expires_in":900}""")
        val talqyn = TestFixtures.client(transport = transport, logHandler = logs::append)
        talqyn.prepare()

        talqyn.events.track(TalqynCategoryClickEvent(categoryId = 42))
        assertTrue(waitUntil { transport.sent.size == 2 })
        talqyn.events.track(TalqynCategoryClickEvent(categoryId = 43))
        assertTrue(
            "a permanent refusal is a warning: history and click-through are silently not being built",
            waitUntil { logs.all.any { it.level == TalqynLogEvent.Level.Warning && it.message.contains("category-click") } },
        )
    }

    // region Defaults

    @Test
    fun defaultCityIsInjected() = runBlocking {
        val transport = StubTransport()
        transport.enqueue(emptySearch)

        val talqyn = TestFixtures.preparedClient(transport, cityId = "10")
        talqyn.search.search("iphone")

        assertEquals("10", transport.sent[0].bodyJson["city_id"])
    }

    /** A store beats a city: pairing a default store with an explicitly named city would override the shopper's choice. */
    @Test
    fun explicitPlaceSuppressesDefaults() = runBlocking {
        val transport = StubTransport()
        transport.enqueue(emptySearch)

        val talqyn = TestFixtures.preparedClient(transport, cityId = "10")
        talqyn.setPlace(cityId = "10", locationId = "5")
        talqyn.search.search(TalqynSearchQuery(query = "iphone", cityId = "47"))

        assertEquals("47", transport.sent[0].bodyJson["city_id"])
        assertNull(transport.sent[0].bodyJson["location_id"])
    }

    @Test
    fun setPlaceAndLocaleAffectSubsequentRequests() = runBlocking {
        val transport = StubTransport()
        transport.enqueue(emptySearch)
        transport.enqueue("""{"tenant_id":"t","answer":"","products":[]}""")

        val talqyn = TestFixtures.preparedClient(transport)
        talqyn.setPlace(cityId = "10", locationId = "5")
        talqyn.setLocale(TalqynLocale.Kk)
        talqyn.setVariant("exp-b")

        talqyn.search.search("iphone")
        talqyn.consultant.answer(TalqynConsultantQuery(question = "hi"))

        val search = transport.sent[0].bodyJson
        assertEquals("10", search["city_id"])
        assertEquals("5", search["location_id"])
        assertEquals("kk", search["locale"])
        assertEquals("exp-b", search["variant"])

        // Place and locale travel on every consultant turn: a session keeps neither.
        val ask = transport.sent[1].bodyJson
        assertEquals("10", ask["city_id"])
        assertEquals("kk", ask["locale"])

        assertEquals(TalqynLocale.Kk, talqyn.currentLocale)
        assertEquals("5", talqyn.currentPlace.locationId)
    }

    // endregion
}
