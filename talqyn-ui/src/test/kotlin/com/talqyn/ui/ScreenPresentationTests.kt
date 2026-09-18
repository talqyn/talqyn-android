package com.talqyn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.talqyn.consultant.TalqynAssistantTurn
import com.talqyn.consultant.TalqynConversationState
import com.talqyn.consultant.TalqynTurn
import com.talqyn.consultant.TalqynUserTurn
import com.talqyn.sdk.TalqynClarify
import com.talqyn.sdk.TalqynComparisonTable

/**
 * What the screen does about its conversation — across its composition being disposed and composed
 * again, by a product page opened over it or a rotation, which the presentation outlives.
 */
class ScreenPresentationTests {
    private val clarify = TalqynClarify("Clarify", listOf(TalqynClarify.Question(id = "budget", label = "Budget?", options = listOf("under 300k"))))
    private val user = TalqynUserTurn(text = "recommend a laptop")
    private val asking = TalqynAssistantTurn(question = user.text, clarify = clarify)

    private fun streaming(vararg turns: TalqynTurn) = TalqynConversationState(turns = turns.toList(), isStreaming = true)

    private fun settled(vararg turns: TalqynTurn) = TalqynConversationState(turns = turns.toList())

    @Test
    fun aNewQuestionIsPinnedAndItsAnswersQuestionComesUpOnceTheAnswerSettles() {
        val presentation = TalqynScreenPresentation()
        presentation.update(TalqynConversationState(), transcriptAtStart = true)

        val asked = presentation.update(streaming(user, asking), transcriptAtStart = true)
        assertEquals(TalqynTranscriptAnchor.NewestTurn, asked.anchor)
        assertNull("nothing comes up while the answer streams", presentation.sheetTurnId)

        val done = presentation.update(settled(user, asking), transcriptAtStart = false)
        assertEquals(TalqynTranscriptAnchor.Keep, done.anchor)
        assertTrue(done.answerSettled)
        assertEquals(asking.id, presentation.sheetTurnId)
    }

    /** A product page opened over the screen: the composition goes, the presentation stays, and the screen comes back as it was left. */
    @Test
    fun aScreenComposedAgainKeepsTheQuestionItMovedIntoTheTranscript() {
        val presentation = TalqynScreenPresentation()
        presentation.update(TalqynConversationState(), transcriptAtStart = true)
        presentation.update(streaming(user, asking), transcriptAtStart = true)
        presentation.update(settled(user, asking), transcriptAtStart = false)
        presentation.sheetDismissed(asking.id)
        assertNull(presentation.sheetTurnId)
        assertTrue(asking.id in presentation.inlineClarifyTurns)

        val back = presentation.update(settled(user, asking), transcriptAtStart = false)
        assertEquals("the transcript stays where the shopper left it", TalqynTranscriptAnchor.Keep, back.anchor)
        assertFalse(back.answerSettled)
        assertTrue("the card is still in the transcript", asking.id in presentation.inlineClarifyTurns)
        assertNull("and the sheet does not come up again", presentation.sheetTurnId)
    }

    @Test
    fun aScreenComposedOverAConversationUnderWayTakesNothingInItForNew() {
        val presentation = TalqynScreenPresentation()
        val first = presentation.update(settled(user, asking), transcriptAtStart = true)
        assertEquals("the last question is pinned, as on any screen opened over it", TalqynTranscriptAnchor.NewestTurn, first.anchor)
        assertFalse(first.answerSettled)
        assertNull("a question already asked does not come up as a sheet", presentation.sheetTurnId)
        assertTrue("it stands in the transcript instead of vanishing", asking.id in presentation.inlineClarifyTurns)

        val restored = TalqynScreenPresentation().update(settled(user, asking), transcriptAtStart = false)
        assertEquals("a position restored from saved state is not overridden", TalqynTranscriptAnchor.Keep, restored.anchor)
    }

    @Test
    fun aScreenComposedOverAStreamingAnswerAsksOnceItSettles() {
        val presentation = TalqynScreenPresentation()
        presentation.update(streaming(user, asking), transcriptAtStart = true)
        presentation.update(settled(user, asking), transcriptAtStart = true)
        assertEquals(asking.id, presentation.sheetTurnId)
    }

    @Test
    fun anAnswerThatCameAtOnceMayAsk() {
        val presentation = TalqynScreenPresentation()
        presentation.update(TalqynConversationState(), transcriptAtStart = true)
        val reaction = presentation.update(settled(user, asking), transcriptAtStart = true)
        assertEquals(TalqynTranscriptAnchor.NewestTurn, reaction.anchor)
        assertEquals(asking.id, presentation.sheetTurnId)
    }

    @Test
    fun aRestoredChatOpensAtItsEndWithItsQuestionInTheTranscript() {
        val presentation = TalqynScreenPresentation()
        presentation.update(TalqynConversationState(), transcriptAtStart = true)
        presentation.update(TalqynConversationState(isRestoring = true), transcriptAtStart = true)
        val reaction = presentation.update(settled(user, asking), transcriptAtStart = true)
        assertEquals(TalqynTranscriptAnchor.Bottom, reaction.anchor)
        assertTrue(reaction.isReplaced)
        assertNull(presentation.sheetTurnId)
        assertTrue(asking.id in presentation.inlineClarifyTurns)
    }

    @Test
    fun nothingComesUpOverTheComparison() {
        val presentation = TalqynScreenPresentation()
        presentation.update(streaming(user, asking), transcriptAtStart = true)
        presentation.comparison = TalqynComparisonTable(listOf(1L, 2L), listOf("A", "B"), listOf(TalqynComparisonTable.Row("Price", listOf("1", "2"))))
        presentation.update(settled(user, asking), transcriptAtStart = false)
        assertNull(presentation.sheetTurnId)
        assertTrue(asking.id in presentation.inlineClarifyTurns)
    }

    @Test
    fun aResetReportsTheTurnsThatWentAndStartsOver() {
        val presentation = TalqynScreenPresentation()
        presentation.update(settled(user, asking), transcriptAtStart = true)
        val reset = presentation.update(TalqynConversationState(), transcriptAtStart = false)
        assertEquals(listOf(user.id, asking.id), reset.removedTurnIds)
        assertTrue(reset.isReplaced)
        assertTrue(presentation.inlineClarifyTurns.isEmpty())
    }
}
