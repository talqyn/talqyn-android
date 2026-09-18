package com.talqyn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import com.talqyn.consultant.TalqynAssistantTurn
import com.talqyn.consultant.TalqynConversation
import com.talqyn.consultant.TalqynPriceFormatter
import com.talqyn.consultant.TalqynUiStrings
import com.talqyn.sdk.TalqynFeedbackReason

/** A turn the consultant gave up on, from the stream to the row the transcript draws. */
class FallbackNoticeTests {
    /** The stream of a turn that gave up, with or without the products it found before giving up. */
    private fun fallbackTurn(withProducts: Boolean, reason: String, followUps: String = """["anything cheaper?","compare them"]"""): List<String> =
        sse("status", """{"stage":"thinking"}""") +
            (if (withProducts) sse("products", """{"items":[{"talqyn_id":1,"title":"Acer"}],"search_id":"srch-1"}""") else emptyList()) +
            sse("fallback", """{"reason":"$reason"}""") +
            sse("follow_ups", """{"items":$followUps}""") +
            sse("done", """{"session_id":"sess-1"}""")

    private suspend fun settled(lines: List<String>, maxFollowUps: Int = 3): TalqynConversation {
        val transport = StubTransport()
        transport.enqueueStream(lines)
        val conversation = TalqynConversation(TestFixtures.preparedClient(transport), maxFollowUps, TestMain.scope())
        conversation.send("what to give as a housewarming gift?")
        waitUntil("the turn never settled") { !conversation.state.value.isStreaming }
        return conversation
    }

    private suspend fun fallbackRow(withProducts: Boolean, reason: String = "budget_exceeded"): Pair<TalqynTurnRow, TalqynConversation> {
        val conversation = settled(fallbackTurn(withProducts, reason))
        val state = conversation.state.value
        val turn = state.turns.last() as TalqynAssistantTurn
        assertEquals(reason, turn.fallbackReason?.rawValue)
        val builder = TalqynTranscriptRowBuilder(TalqynUiStrings.En, TalqynPriceFormatter.Tenge)
        return builder.turnRow(turn, state, emptySet()) to conversation
    }

    /** The reason alone: there is no carousel under it to point at. */
    @Test
    fun aFallbackWithNoProductsDoesNotPromiseThem() = mainTest {
        assertEquals("The consultant is unavailable right now", fallbackRow(withProducts = false).first.notice?.text)
    }

    /** The products the turn did find are worth pointing at. */
    @Test
    fun aFallbackWithProductsPointsAtThem() = mainTest {
        assertEquals("The consultant is unavailable right now, but here is what matches", fallbackRow(withProducts = true).first.notice?.text)
    }

    /** The carousel is all the turn has, so it is not headed as an extra. */
    @Test
    fun fallbackProductsGetTheirOwnHeader() = mainTest {
        assertEquals(listOf("What turned up for your request"), fallbackRow(withProducts = true).first.productSections.map { it.title })
    }

    /** Whatever stopped the turn would stop the next question too. */
    @Test
    fun aFallbackTurnOffersNoFollowUps() = mainTest {
        val (_, conversation) = fallbackRow(withProducts = true)
        val state = conversation.state.value
        assertEquals(listOf("anything cheaper?", "compare them"), (state.turns.last() as TalqynAssistantTurn).followUps)
        assertEquals(emptyList<String>(), state.suggestedQuestions(listOf("Example")))
    }

    /**
     * The shopper's own limit is spent for hours: a retry button would only walk them into it
     * again. A timeout may clear, and gets one.
     */
    @Test
    fun retryIsOfferedOnlyWhereItCouldWork() = mainTest {
        val spent = fallbackRow(withProducts = true, reason = "user_budget_exceeded").first.notice
        assertEquals(false, spent?.showsRetry)
        assertEquals("You have used up your questions for the next few hours, but here is what matches", spent?.text)
        assertEquals(true, fallbackRow(withProducts = true, reason = "timeout").first.notice?.showsRetry)
    }

    /** "No answer" is exactly what a shopper may want to say about a turn that gave up: rated, with its own reasons, nothing to copy. */
    @Test
    fun aFallbackTurnCanBeRatedButNotCopied() = mainTest {
        val toolbar = fallbackRow(withProducts = true).first.toolbar
        assertNotNull(toolbar)
        assertNull(toolbar?.copyText)
        assertEquals(TalqynFeedbackReason.NoAnswer, toolbar?.offeredReasons?.first())
    }

    /** How many prompts to put in front of the shopper is the storefront's call: the server may send more than are worth showing. */
    @Test
    fun followUpCountFollowsTheConversation() = mainTest {
        val lines = sse("status", """{"stage":"thinking"}""") +
            sse("delta", """{"text":"Answer"}""") +
            sse("follow_ups", """{"items":["first","second","third","fourth"]}""") +
            sse("done", """{"session_id":"sess-1"}""")
        suspend fun followUps(limit: Int) = (settled(lines, limit).state.value.turns.last() as TalqynAssistantTurn).followUps
        assertEquals("three by default", 3, followUps(3).size)
        assertEquals(listOf("first"), followUps(1))
        assertEquals(emptyList<String>(), followUps(0))
        assertEquals("no padding past what came", 4, followUps(10).size)
    }
}
