package com.talqyn.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The reader against what a server, a proxy, or a catalog feed may send — held to what Foundation's
 * reader does on the twin SDK, so one body is never fine on one platform and broken on the other.
 */
class JsonReaderTest {
    @Test
    fun aByteOrderMarkBeforeTheDocumentIsSkipped() {
        val parsed = TalqynJson.parse("\uFEFF{\"a\":1}".toByteArray()) as Map<*, *>
        assertEquals(1L, parsed["a"])
    }

    @Test
    fun unicodeEscapesAndSurrogatePairs() {
        assertEquals("Ж\uD83D\uDE00", TalqynJson.parse("\"\\u0416\\uD83D\\uDE00\""))
        expectFailure<TalqynJsonException>("a lone high surrogate") { TalqynJson.parse("\"\\uD83D\"") }
        expectFailure<TalqynJsonException>("a lone low surrogate") { TalqynJson.parse("\"\\uDE00x\"") }
        expectFailure<TalqynJsonException>("a high surrogate followed by no low one") { TalqynJson.parse("\"\\uD83D\\u0041\"") }
        expectFailure<TalqynJsonException>("a sign is no hex digit") { TalqynJson.parse("\"\\u+041\"") }
        expectFailure<TalqynJsonException>("non-ASCII digits are no hex digits") { TalqynJson.parse("\"\\u\u0660\u0660\u0664\u0661\"") }
    }

    @Test
    fun rawControlCharactersInsideAStringAreRejected() {
        expectFailure<TalqynJsonException> { TalqynJson.parse("\"a\u0001b\"") }
        expectFailure<TalqynJsonException> { TalqynJson.parse("\"line\nbreak\"") }
    }

    @Test
    fun numbersFollowTheGrammar() {
        assertEquals(0L, TalqynJson.parse("0"))
        assertEquals(0L, TalqynJson.parse("-0"))
        assertEquals(0.5, TalqynJson.parse("0.5"))
        expectFailure<TalqynJsonException>("a leading zero") { TalqynJson.parse("0123") }
        expectFailure<TalqynJsonException>("a leading zero after a sign") { TalqynJson.parse("-01") }
        expectFailure<TalqynJsonException>("a number that reads as infinity") { TalqynJson.parse("1e400") }
    }

    /** Talqyn's ids are 64-bit: a card past 2³¹ is a card, not a decoding failure. */
    @Test
    fun anIdBeyondIntDecodes() {
        val product = TalqynProduct.decode(TalqynJson.parseObject("""{"talqyn_id":3000000000,"title":"x","brand_id":4000000000}""".toByteArray()))
        assertEquals(3_000_000_000L, product.talqynId)
        assertEquals(4_000_000_000L, product.brandId)
        val table = TalqynComparisonTable.decode(TalqynJson.parseObject("""{"talqyn_ids":[3000000000,1],"titles":["a","b"]}""".toByteArray()))
        assertEquals(listOf(3_000_000_000L, 1L), table.talqynIds)
    }

    @Test
    fun theWriterEscapesEveryControlCharacter() {
        assertEquals("\"a\\u0001\\n\\t\\f\\u001f\"", TalqynJson.encode("a\u0001\n\t\u000C\u001F"))
    }

    @Test
    fun offsetsWithAndWithoutAColon() {
        val expected = Instant.parse("2026-08-26T07:00:00Z")
        assertEquals(expected, TalqynDates.parseIso("2026-08-26T12:00:00+05:00"))
        assertEquals(expected, TalqynDates.parseIso("2026-08-26T12:00:00+0500"))
        assertEquals(expected, TalqynDates.parseIso("2026-08-26T07:00:00Z"))
        assertEquals(Instant.parse("2026-08-26T07:00:00.512Z"), TalqynDates.parseIso("2026-08-26T12:00:00.512+0500"))
        assertNull(TalqynDates.parseIso("yesterday"))
    }

    /** The same bytes the twin SDK produces: an escape in place stays, a stray `%`, a bracket, and a second `#` are encoded. */
    @Test
    fun lenientUrls() {
        assertEquals("https://x.kz/img%2050%25.jpg", TalqynUrls.lenient("https://x.kz/img 50%.jpg"))
        assertEquals("https://cdn.kz/img%5B1%5D.jpg", TalqynUrls.lenient("https://cdn.kz/img[1].jpg"))
        assertEquals("https://x/a#b%23c", TalqynUrls.lenient("https://x/a#b#c"))
        assertEquals("https://x/a%20b%20c.jpg", TalqynUrls.lenient("https://x/a%20b c.jpg"))
        assertEquals("https://x.kz/%D1%84%D0%BE%D1%82%D0%BE%201.jpg", TalqynUrls.lenient("https://x.kz/фото 1.jpg"))
        assertEquals("raw non-ASCII is encoded even where java.net.URI would take it", "https://x.kz/%D1%84%D0%BE%D1%82%D0%BE1.jpg", TalqynUrls.lenient("https://x.kz/фото1.jpg"))
        assertEquals("a valid URL is kept byte for byte", "https://x.kz/a.jpg?w=1&h=2", TalqynUrls.lenient("https://x.kz/a.jpg?w=1&h=2"))
        assertNull(TalqynUrls.lenient(""))
    }
}
