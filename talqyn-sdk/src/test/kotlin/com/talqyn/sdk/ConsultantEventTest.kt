package com.talqyn.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsultantEventTest {
    private fun event(name: String, json: String): TalqynConsultantEvent? = TalqynConsultantEvent.from(TalqynSseMessage(name, json))

    @Test
    fun statusProductsDeltaDone() {
        assertEquals(TalqynConsultantEvent.Status(TalqynConsultantStage.Thinking), event("status", """{"stage":"thinking"}"""))
        assertEquals(TalqynConsultantEvent.Delta("hi"), event("delta", """{"text":"hi"}"""))

        val products = event("products", """{"items":[{"talqyn_id":1,"title":"A"}],"search_id":"s1"}""")
            as? TalqynConsultantEvent.Products ?: throw AssertionError("expected a products event")
        assertEquals(1, products.products.items.size)
        assertEquals("s1", products.products.searchId)
        assertNull(products.products.groups)

        val done = event("done", """{"session_id":"abc","ttft_ms":320,"total_ms":1800}""")
            as? TalqynConsultantEvent.Done ?: throw AssertionError("expected a done event")
        assertEquals("abc", done.done.sessionId)
        assertEquals(320, done.done.timeToFirstTokenMs)
        assertTrue(done.isTerminal)
    }

    @Test
    fun multiStepProductsCarryGroups() {
        val products = event(
            "products",
            """{"items":[{"talqyn_id":1,"title":"A"}],
               "groups":[{"role":"sofa","items":[{"talqyn_id":1,"title":"A"}]}]}""",
        ) as? TalqynConsultantEvent.Products ?: throw AssertionError("expected a products event")
        assertEquals("sofa", products.products.groups?.firstOrNull()?.role)
    }

    @Test
    fun clarifyEvent() {
        val clarify = event(
            "clarify",
            """{"message":"clarify","questions":[{"id":"budget","label":"Budget","multi":false,"options":["under 300k"]}]}""",
        ) as? TalqynConsultantEvent.Clarify ?: throw AssertionError("expected a clarify event")
        assertEquals("clarify", clarify.clarify.message)
        assertEquals("budget", clarify.clarify.questions.first().id)
        assertEquals(false, clarify.clarify.questions.first().multi)
    }

    @Test
    fun redirectAcceptsBothEventNames() {
        assertEquals(TalqynConsultantEvent.RedirectToSearch("iphone"), event("redirect_to_search", """{"query":"iphone"}"""))
        assertEquals(TalqynConsultantEvent.RedirectToSearch("iphone"), event("redirect", """{"query":"iphone"}"""))
    }

    @Test
    fun fallbackReasonIsOpenSet() {
        assertEquals(
            TalqynConsultantEvent.Fallback(TalqynFallbackReason.BudgetExceeded),
            event("fallback", """{"reason":"budget_exceeded"}"""),
        )
        val fallback = event("fallback", """{"reason":"provider_meltdown"}""")
            as? TalqynConsultantEvent.Fallback ?: throw AssertionError("an unknown reason must arrive, not be dropped")
        assertEquals("provider_meltdown", fallback.reason.rawValue)
        assertFalse(fallback.reason.isBudgetExhausted)
    }

    @Test
    fun comparisonAction() {
        val action = event(
            "action",
            """{"type":"show_comparison","table":{"talqyn_ids":[1,2],"titles":["A","B"],
               "rows":[{"label":"Screen","values":["15\"",null]}]}}""",
        ) as? TalqynConsultantEvent.Action ?: throw AssertionError("expected an action event")
        val comparison = action.action as? TalqynConsultantAction.ShowComparison ?: throw AssertionError("expected a comparison")
        assertEquals(listOf(1L, 2L), comparison.table.talqynIds)
        val values = comparison.table.rows.first().values
        assertEquals(2, values.size)
        assertEquals("15\"", values[0])
        assertNull("a missing characteristic is null, not an empty string", values[1])
    }

    @Test
    fun unknownActionTypeDoesNotBreakTurn() {
        val action = event("action", """{"type":"open_cart"}""") as? TalqynConsultantEvent.Action
            ?: throw AssertionError("expected an action event")
        assertEquals(TalqynConsultantAction.Unknown("open_cart"), action.action)
    }

    @Test
    fun followUpsAndError() {
        assertEquals(TalqynConsultantEvent.FollowUps(listOf("which one is quieter?")), event("follow_ups", """{"items":["which one is quieter?"]}"""))
        assertEquals(TalqynConsultantEvent.Error("retrieval_failed"), event("error", """{"code":"retrieval_failed"}"""))
    }

    /** A new server-side event type must not break a shipped app. */
    @Test
    fun unknownEventIsSkipped() {
        assertNull(event("telemetry", "{}"))
    }

    @Test
    fun undecodablePayloadIsSkipped() {
        assertNull(event("delta", "not json"))
        assertNull(event("delta", "[1,2]"))
    }

    @Test
    fun unknownStageSurvives() {
        val status = event("status", """{"stage":"composing"}""") as? TalqynConsultantEvent.Status
            ?: throw AssertionError("expected a status event")
        assertEquals("composing", status.stage.rawValue)
    }
}
