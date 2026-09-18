package com.talqyn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.talqyn.consultant.TalqynAssistantTurn
import com.talqyn.sdk.TalqynClarify

/** When a clarification comes up as a sheet and when it stays in the transcript. */
class ClarifyPresentationTests {
    private fun asking() = TalqynAssistantTurn(
        question = "recommend something",
        clarify = TalqynClarify("clarify", listOf(TalqynClarify.Question(id = "budget", label = "Budget?", options = listOf("under 300k")))),
    )

    @Test
    fun oneSheetPerQuestionTheShopperTyped() {
        val policy = TalqynClarifyPresentation()
        policy.beginRequest()
        val first = asking()
        assertEquals(TalqynClarifyPresentation.Decision.Sheet, policy.decide(first, canPresent = true))
        assertEquals("a turn is decided once", TalqynClarifyPresentation.Decision.None, policy.decide(first, canPresent = true))

        val followUp = asking()
        assertEquals("a second sheet in a row is an interrogation", TalqynClarifyPresentation.Decision.Inline, policy.decide(followUp, canPresent = true))
        assertTrue(followUp.id in policy.inlineTurns)

        policy.beginRequest()
        assertEquals("a new question gets its own", TalqynClarifyPresentation.Decision.Sheet, policy.decide(asking(), canPresent = true))
    }

    @Test
    fun aSheetThatCannotComeUpStaysInline() {
        val policy = TalqynClarifyPresentation()
        policy.beginRequest()
        val turn = asking()
        assertEquals(TalqynClarifyPresentation.Decision.Inline, policy.decide(turn, canPresent = false))
        assertTrue(turn.id in policy.inlineTurns)
    }

    @Test
    fun aDismissedSheetMovesIntoTheTranscriptAndIsForgottenWithItsTurn() {
        val policy = TalqynClarifyPresentation()
        policy.beginRequest()
        val turn = asking()
        policy.decide(turn, canPresent = true)
        assertFalse(turn.id in policy.inlineTurns)
        policy.sheetDismissed(turn.id)
        assertTrue(turn.id in policy.inlineTurns)
        policy.forget(listOf(turn.id))
        assertFalse(turn.id in policy.inlineTurns)
        assertEquals("a forgotten turn may be decided again", TalqynClarifyPresentation.Decision.Inline, policy.decide(turn, canPresent = true))
    }

    @Test
    fun forgettingEverythingStartsANewConversation() {
        val policy = TalqynClarifyPresentation()
        policy.beginRequest()
        policy.decide(asking(), canPresent = true)
        val second = asking()
        policy.decide(second, canPresent = true)
        policy.forgetAll()
        assertTrue(policy.inlineTurns.isEmpty())
        assertEquals("the sheet of the previous conversation no longer counts", TalqynClarifyPresentation.Decision.Sheet, policy.decide(asking(), canPresent = true))
    }

    @Test
    fun anAnsweredOrPlainTurnHasNothingToShow() {
        val policy = TalqynClarifyPresentation()
        assertEquals(TalqynClarifyPresentation.Decision.None, policy.decide(asking().copy(clarifyAnswer = "Budget: under 300k."), canPresent = true))
        assertEquals(TalqynClarifyPresentation.Decision.None, policy.decide(TalqynAssistantTurn(question = "q"), canPresent = true))
    }
}
