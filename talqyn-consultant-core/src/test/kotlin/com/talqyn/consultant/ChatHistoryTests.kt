package com.talqyn.consultant

import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.talqyn.sdk.TalqynException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class ChatHistoryTests {
    private fun chats(ids: List<String>): String =
        ids.joinToString(",", prefix = "[", postfix = "]") {
            "{\"session_id\":\"$it\",\"title\":\"chat $it\",\"message_count\":2,\"last_message_at\":\"2026-08-26T12:00:00Z\"}"
        }

    private suspend fun settled(history: TalqynChatHistory) {
        repeat(200) {
            when (val state = history.state.value) {
                TalqynChatHistory.State.Loading -> delay(10)
                is TalqynChatHistory.State.Loaded -> if (state.isLoadingMore) delay(10) else return
                else -> return
            }
        }
    }

    private fun loaded(history: TalqynChatHistory): TalqynChatHistory.State.Loaded =
        history.state.value as? TalqynChatHistory.State.Loaded ?: throw AssertionError("expected a loaded page, got ${history.state.value}")

    @Test
    fun pagesLoadUntilAShortPage() = mainTest {
        val transport = StubTransport()
        transport.enqueue(chats(listOf("a", "b")))
        transport.enqueue(chats(listOf("b", "c")))
        transport.enqueue(chats(listOf("d")))
        val history = TalqynChatHistory(TestFixtures.preparedClient(transport), pageSize = 2, scope = TestMain.scope())

        history.load()
        assertEquals(TalqynChatHistory.State.Loading, history.state.value)
        settled(history)
        val first = loaded(history).chats
        assertEquals(listOf("a", "b"), first.map { it.sessionId })
        assertEquals("limit=2&offset=0", transport.sent.last().query)

        history.loadMoreIfNeeded(first[1])
        settled(history)
        val second = loaded(history).chats
        assertEquals("an overlap is not shown twice", listOf("a", "b", "c"), second.map { it.sessionId })
        assertEquals("limit=2&offset=2", transport.sent.last().query)

        history.loadMoreIfNeeded(second[2])
        settled(history)
        val third = loaded(history).chats
        assertEquals(listOf("a", "b", "c", "d"), third.map { it.sessionId })

        history.loadMoreIfNeeded(third[3])
        settled(history)
        assertEquals("a short page ends the list: no request after it", 3, transport.sent.size)
    }

    @Test
    fun emptyAndFailedStates() = mainTest {
        val transport = StubTransport()
        transport.enqueue("[]")
        transport.enqueue("""{"detail":"no shopper"}""", status = 403)
        val history = TalqynChatHistory(TestFixtures.preparedClient(transport), scope = TestMain.scope())

        history.load()
        settled(history)
        assertEquals(TalqynChatHistory.State.Empty, history.state.value)

        history.load()
        settled(history)
        val state = history.state.value
        assertTrue(
            "expected a forbidden failure, got $state",
            state is TalqynChatHistory.State.Failed && state.error is TalqynException.Forbidden,
        )
    }

    @Test
    fun deleteRemovesTheRowAtOnceAndReloadsOnFailure() = mainTest {
        val transport = StubTransport()
        transport.enqueue(chats(listOf("a", "b")))
        transport.enqueue("", status = 204)
        val history = TalqynChatHistory(TestFixtures.preparedClient(transport), pageSize = 20, scope = TestMain.scope())
        history.load()
        settled(history)

        history.delete("a")
        assertEquals("gone before the server answers", listOf("b"), loaded(history).chats.map { it.sessionId })
        waitUntil("the deletion was never sent") { transport.sent.lastOrNull()?.method == "DELETE" }
        assertEquals("/v1/consultant/chats/a", transport.sent.last().path)

        transport.enqueue("""{"error":"internal_error"}""", status = 500)
        transport.enqueue(chats(listOf("b")))
        history.delete("b")
        waitUntil("the list was never reloaded") { transport.sent.count { it.path.endsWith("/consultant/chats") } == 2 }
        settled(history)
        assertEquals("a failed deletion brings the row back", listOf("b"), loaded(history).chats.map { it.sessionId })
    }

    @Test
    fun subtitleFormats() {
        val zone = ZoneOffset.UTC
        val now = LocalDateTime.of(2026, 9, 10, 15, 30).atZone(zone).toInstant()
        val strings = TalqynUiStrings.Ru
        fun subtitle(date: Instant?) = TalqynChatHistory.subtitle(date, strings, strings.locale, zone, now)

        assertEquals("", subtitle(null))
        assertEquals("13:30", subtitle(now.minus(2, ChronoUnit.HOURS)))
        assertEquals("Вчера", subtitle(now.minus(1, ChronoUnit.DAYS)))
        assertEquals("1 авг.", subtitle(now.minus(40, ChronoUnit.DAYS)))
        // The year is written the way the locale writes it; the exact spaces come from the
        // runtime's locale data, which differs between the JVM and a device.
        val lastYear = subtitle(LocalDateTime.of(2025, 9, 10, 15, 30).atZone(zone).toInstant())
        assertTrue("got '$lastYear'", lastYear.startsWith("10 сент") && lastYear.contains("2025"))
    }

    /** English is the set whose date shapes differ most: a 12-hour clock and the month before the day. */
    @Test
    fun englishSubtitleFormats() {
        val zone = ZoneOffset.UTC
        val now = LocalDateTime.of(2026, 9, 10, 15, 30).atZone(zone).toInstant()
        val strings = TalqynUiStrings.En
        fun subtitle(date: Instant?) = TalqynChatHistory.subtitle(date, strings, strings.locale, zone, now)

        assertEquals("Yesterday", subtitle(now.minus(1, ChronoUnit.DAYS)))
        assertEquals("Aug 1", subtitle(now.minus(40, ChronoUnit.DAYS)))
        val today = subtitle(now.minus(2, ChronoUnit.HOURS))
        assertTrue("got '$today'", today.startsWith("1:30") && today.endsWith("PM"))
        val lastYear = subtitle(LocalDateTime.of(2025, 9, 10, 15, 30).atZone(zone).toInstant())
        assertTrue("got '$lastYear'", lastYear.startsWith("Sep 10") && lastYear.contains("2025"))
    }
}
