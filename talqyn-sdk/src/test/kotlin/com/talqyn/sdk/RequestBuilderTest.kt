package com.talqyn.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class RequestBuilderTest {
    private fun builder(base: String, version: String = "v1") = TalqynRequestBuilder(base, version, 30.seconds)

    /**
     * The SDK carries no endpoint of its own: the host of the configuration is the only one
     * there is, and the token mint goes there too — the minter and the API calls share one
     * builder.
     */
    @Test
    fun configuredHostIsWhereEveryRequestGoes() = runBlocking {
        val transport = StubTransport()
        transport.enqueueDeviceToken()
        val talqyn = TestFixtures.client(transport = transport, baseUrl = "https://gateway.example.com/talqyn")
        transport.enqueue("""{"search_id":"s","query":"x","locale":"ru","total":0,"results":[]}""")
        talqyn.search.search("iphone")

        assertEquals(
            listOf(
                "https://gateway.example.com/talqyn/v1/consultant/token",
                "https://gateway.example.com/talqyn/v1/search/",
            ),
            transport.sent.map { it.request.url },
        )
    }

    /** A stand is addressed by its own URL, and requests are built against it. */
    @Test
    fun givenBaseUrlIsBuiltAgainst() {
        val stand = "https://gateway.example.com/talqyn"
        val configuration = TalqynConfiguration(
            credentials = TalqynDeviceTokenCredentials("myshop", "ck_1", "s"),
            baseUrl = stand,
        )
        assertEquals(stand, configuration.baseUrl)
        assertEquals(
            "https://gateway.example.com/talqyn/v1/search/",
            builder(configuration.baseUrl).url("search/"),
        )
    }

    /** The trailing slash of instant search is part of the endpoint address. */
    @Test
    fun instantSearchPathKeepsTrailingSlash() {
        assertEquals("https://api.example.com/v1/search/", builder("https://api.example.com").url("search/"))
    }

    @Test
    fun baseWithTrailingSlashDoesNotDoubleUp() {
        assertEquals("https://api.example.com/v1/search/full", builder("https://api.example.com/").url("search/full"))
    }

    @Test
    fun baseWithPathPrefixIsPreserved() {
        assertEquals(
            "https://gateway.example.com/talqyn/v1/consultant/ask",
            builder("https://gateway.example.com/talqyn").url("consultant/ask"),
        )
    }

    @Test
    fun emptyVersionSkipsSegment() {
        assertEquals("https://api.example.com/search/", builder("https://api.example.com", version = "").url("search/"))
    }

    @Test
    fun queryItemsAreEncoded() {
        assertEquals(
            "https://api.example.com/v1/consultant/chats?limit=20",
            builder("https://api.example.com").url("consultant/chats", listOf("limit" to "20")),
        )
    }

    /** A gateway may be addressed with a query of its own; it must survive. */
    @Test
    fun baseUrlQueryIsKeptAheadOfRequestItems() {
        assertEquals(
            "https://gw.example.com/talqyn/v1/consultant/chats?key=abc&limit=20",
            builder("https://gw.example.com/talqyn?key=abc").url("consultant/chats", listOf("limit" to "20")),
        )
        assertEquals(
            "https://gw.example.com/talqyn/v1/search/?key=abc",
            builder("https://gw.example.com/talqyn?key=abc").url("search/"),
        )
    }

    @Test
    fun sessionIdIsEscapedIntoPath() {
        assertEquals(
            "https://api.example.com/v1/consultant/chats/a%20b%3Fc",
            builder("https://api.example.com").url("consultant/chats/a b?c"),
        )
    }

    /** A session id is a value, not a route: a `/` inside it must not climb out of `chats/`. */
    @Test
    fun segmentEscapesTheSeparator() {
        assertEquals(
            "https://api.example.com/v1/consultant/chats/..%2F..%2Fadmin",
            builder("https://api.example.com").url("consultant/chats/${TalqynRequestBuilder.segment("../../admin")}"),
        )
    }

    @Test
    fun unparseableBaseIsAConfigurationError() {
        expectFailure<TalqynException.InvalidConfiguration> { builder("not a url").url("search/") }
    }

    @Test
    fun requestCarriesJsonContentTypeOnlyWithBody() {
        val withBody = builder("https://api.example.com").request(method = "POST", path = "search/", body = "{}".toByteArray())
        assertEquals("application/json", withBody.header("Content-Type"))

        val withoutBody = builder("https://api.example.com").request(method = "GET", path = "consultant/chats")
        assertNull(withoutBody.header("Content-Type"))
    }
}
