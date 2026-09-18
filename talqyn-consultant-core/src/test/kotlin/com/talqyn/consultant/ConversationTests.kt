package com.talqyn.consultant

import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.talqyn.sdk.TalqynDeviceIdentity
import com.talqyn.sdk.TalqynException
import com.talqyn.sdk.TalqynFeedbackReason
import java.net.SocketTimeoutException
import java.util.UUID

class ConversationTests {
    private fun event(name: String, data: String): List<String> = listOf("event: $name", "data: $data", "")

    private fun fullTurn(sessionId: String = "sess-1", turnId: String? = null): List<String> {
        val turn = turnId?.let { ",\"turn_id\":\"$it\"" }.orEmpty()
        return event("status", """{"stage":"thinking"}""") +
            event("status", """{"stage":"searching"}""") +
            event("products", """{"items":[{"talqyn_id":1,"title":"Acer"},{"talqyn_id":2,"title":"Lenovo"}],"search_id":"srch-1"}""") +
            event("delta", """{"text":"Take "}""") +
            event("delta", """{"text":"[p:1]."}""") +
            event("action", """{"type":"apply_filters","filters":{"price_max":300000}}""") +
            event("follow_ups", """{"items":[" Quieter? ","quieter?","","Cheaper","More","Extra"]}""") +
            event("done", "{\"session_id\":\"$sessionId\"$turn,\"ttft_ms\":300,\"total_ms\":900}")
    }

    private val turnId = "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"

    private fun feedbackRequests(transport: StubTransport) = transport.sent.filter { it.path.contains("/consultant/feedback") }

    private suspend fun makeConversation(transport: StubTransport): TalqynConversation =
        TalqynConversation(TestFixtures.preparedClient(transport), scope = TestMain.scope())

    private suspend fun settle(conversation: TalqynConversation) {
        waitUntil("the turn never settled") { !conversation.state.value.isStreaming }
    }

    private val TalqynConversation.turns get() = state.value.turns
    private val TalqynConversation.lastAssistant get() = state.value.turns.lastOrNull() as? TalqynAssistantTurn

    @Test
    fun turnStreamsIntoAnAssistantTurn() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(fullTurn())
        val conversation = makeConversation(transport)

        conversation.updateDraft("  need a laptop  ")
        conversation.send(conversation.state.value.draft)
        assertTrue(conversation.state.value.isStreaming)
        assertEquals("the composer empties on send", "", conversation.state.value.draft)
        settle(conversation)

        val state = conversation.state.value
        assertEquals(2, state.turns.size)
        val user = state.turns.first() as? TalqynUserTurn ?: return@mainTest fail("expected a user turn")
        assertEquals("trimmed", "need a laptop", user.text)
        val turn = conversation.lastAssistant!!
        assertEquals("need a laptop", turn.question)
        assertEquals("Take [p:1].", turn.text)
        assertEquals(listOf(1L, 2L), turn.products.map { it.talqynId })
        assertEquals("srch-1", turn.searchId)
        assertNull("settled", turn.stage)
        assertEquals(1, turn.actions.size)
        assertEquals("trimmed, deduplicated, capped at three", listOf("Quieter?", "Cheaper", "More"), turn.followUps)
        assertEquals(300, turn.timeToFirstTokenMs)
        assertEquals("sess-1", state.sessionId)
        assertEquals(listOf(1L, 2L), state.productsById.keys.sorted())
        assertTrue(turn.isAnswer)
        assertEquals("need a laptop", transport.sent.last().bodyJson["question"])
    }

    @Test
    fun secondTurnCarriesTheSession() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(fullTurn(sessionId = "sess-1"))
        transport.enqueueStream(fullTurn(sessionId = "sess-1"))
        val conversation = makeConversation(transport)

        conversation.send("first")
        settle(conversation)
        conversation.send("second")
        settle(conversation)

        assertEquals("sess-1", transport.sent.last().bodyJson["session_id"])
        assertEquals(4, conversation.turns.size)
    }

    @Test
    fun sendIsIgnoredWhileStreamingOrEmpty() = mainTest {
        val transport = SlowStreamTransport()
        transport.responses.enqueueDeviceToken()
        transport.enqueueStream(fullTurn())
        val conversation = TalqynConversation(TestFixtures.client(transport), scope = TestMain.scope())

        conversation.send("   ")
        assertTrue(conversation.turns.isEmpty())
        conversation.send("question")
        conversation.send("another question")
        assertEquals("a second send during a stream is dropped", 2, conversation.turns.size)
        settle(conversation)
    }

    @Test
    fun stopMarksTheTurnStoppedAndKeepsWhatArrived() = mainTest {
        val transport = SlowStreamTransport()
        transport.responses.enqueueDeviceToken()
        transport.enqueueStream(fullTurn())
        val conversation = TalqynConversation(TestFixtures.client(transport), scope = TestMain.scope())

        conversation.send("question")
        // Let the products through, then stop before the text.
        waitUntil("the products never arrived") { conversation.lastAssistant?.products?.isNotEmpty() == true }
        conversation.stop()
        settle(conversation)

        val turn = conversation.lastAssistant!!
        assertTrue(turn.wasStopped)
        assertTrue(turn.didFail)
        assertFalse("what arrived stays", turn.products.isEmpty())
        assertNull(turn.stage)
        assertTrue("stopping cancels the request", transport.wasTerminated)
    }

    @Test
    fun serverErrorEventAndTransportFailureAreRecorded() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(
            event("status", """{"stage":"searching"}""") +
                event("error", """{"code":"retrieval_failed"}""") +
                event("done", """{"session_id":"s"}"""),
        )
        transport.enqueueStream(listOf("""{"detail":"Rate limit exceeded"}"""), status = 429)
        val conversation = makeConversation(transport)

        conversation.send("one")
        settle(conversation)
        assertEquals("retrieval_failed", conversation.lastAssistant?.errorCode)

        conversation.send("two")
        settle(conversation)
        val failed = conversation.lastAssistant!!
        assertTrue("expected the transport failure on the turn, got ${failed.failure}", failed.failure is TalqynException.RateLimited)
        assertTrue(failed.didFail)
        assertFalse(failed.isAnswer)
    }

    @Test
    fun retryReplacesTheTurnWithoutANewBubble() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(listOf("""{"detail":"boom"}"""), status = 500)
        transport.enqueueStream(fullTurn())
        val conversation = makeConversation(transport)

        conversation.send("question")
        settle(conversation)
        assertNotNull(conversation.lastAssistant?.failure)
        val failedId = conversation.turns.last().id

        conversation.retry(failedId)
        settle(conversation)

        assertEquals("the failed turn is replaced, not appended", 2, conversation.turns.size)
        assertNotEquals(failedId, conversation.turns.last().id)
        assertEquals("question", conversation.lastAssistant?.question)
        assertNull(conversation.lastAssistant?.failure)
        assertEquals("question", transport.sent.last().bodyJson["question"])
    }

    @Test
    fun clarifyAnswerIsRecordedAndSentAsTheNextQuestion() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(
            event("status", """{"stage":"thinking"}""") +
                event("clarify", """{"message":"clarify","questions":[{"id":"budget","label":"Budget?","multi":false,"options":["under 300k"]}]}""") +
                event("done", """{"session_id":"sess-1"}"""),
        )
        transport.enqueueStream(fullTurn())
        val conversation = makeConversation(transport)

        conversation.send("recommend something")
        settle(conversation)
        val asking = conversation.lastAssistant!!
        val clarify = asking.clarify!!
        assertTrue(conversation.state.value.isClarifyInteractive(asking))
        assertTrue("no prompts under a question", conversation.state.value.suggestedQuestions(listOf("example")).isEmpty())

        val draft = TalqynClarifyDraft().toggle("under 300k", clarify.questions[0])
        conversation.submitClarify(asking.id, draft.answer(clarify.questions))
        settle(conversation)

        val turns = conversation.turns
        assertEquals("no user bubble for a clarify answer", 3, turns.size)
        val answered = turns[1] as TalqynAssistantTurn
        assertEquals("Budget: under 300k.", answered.clarifyAnswer)
        assertFalse("not the latest turn any more", conversation.state.value.isClarifyInteractive(answered))
        assertEquals("Budget: under 300k.", transport.sent.last().bodyJson["question"])
        assertEquals("sess-1", transport.sent.last().bodyJson["session_id"])
    }

    @Test
    fun suggestedQuestions() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(event("delta", """{"text":"ok"}""") + event("done", """{"session_id":"s"}"""))
        transport.enqueueStream(fullTurn())
        val conversation = makeConversation(transport)
        val examples = listOf("Pick a smartphone", "Which fridge?", "A laptop under 300k")

        assertTrue("nothing before the first answer", conversation.state.value.suggestedQuestions(examples).isEmpty())
        conversation.send("pick a smartphone")
        settle(conversation)
        assertEquals(
            "after the first answer: the examples not yet asked, case-insensitively",
            listOf("Which fridge?", "A laptop under 300k"),
            conversation.state.value.suggestedQuestions(examples),
        )

        conversation.send("more")
        assertTrue("nothing while streaming", conversation.state.value.suggestedQuestions(examples).isEmpty())
        settle(conversation)
        assertEquals("the turn's follow-ups win", listOf("Quieter?", "Cheaper", "More"), conversation.state.value.suggestedQuestions(examples))
    }

    @Test
    fun ratingIsKeptOnTheTurnAndTakenBackWithNull() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(fullTurn())
        val conversation = makeConversation(transport)
        conversation.send("need a laptop")
        settle(conversation)
        val id = conversation.lastAssistant!!.id
        assertNull(conversation.lastAssistant?.rating)
        val requestsBefore = transport.sent.size

        conversation.rate(id, TalqynAnswerRating.Helpful)
        assertEquals(TalqynAnswerRating.Helpful, conversation.lastAssistant?.rating)
        conversation.rate(id, TalqynAnswerRating.NotHelpful)
        assertEquals(TalqynAnswerRating.NotHelpful, conversation.lastAssistant?.rating)
        conversation.rate(id, null)
        assertNull(conversation.lastAssistant?.rating)

        conversation.rate(UUID.randomUUID(), TalqynAnswerRating.Helpful)
        assertNull("an unknown turn changes nothing", conversation.lastAssistant?.rating)
        delay(50)
        assertEquals("a turn from a server without turn ids is rated on the device only", requestsBefore, transport.sent.size)
    }

    /** The thumb is saved under the turn's id, and every change after it — a reason picked, the rating taken back — reaches Talqyn in order. */
    @Test
    fun ratingIsSavedWithTalqyn() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(fullTurn(turnId = turnId))
        repeat(3) { transport.enqueue("", status = 204) }
        val conversation = makeConversation(transport)
        conversation.send("need a laptop")
        settle(conversation)
        val turn = conversation.lastAssistant!!
        assertEquals(turnId, turn.talqynTurnId)
        assertEquals("sess-1", turn.sessionId)
        assertTrue(turn.isRatedRemotely)

        conversation.rate(turn.id, TalqynAnswerRating.NotHelpful)
        waitUntil("the dislike was never sent") { feedbackRequests(transport).size == 1 }
        conversation.rate(turn.id, TalqynAnswerRating.NotHelpful, listOf(TalqynFeedbackReason.NotRelevant, TalqynFeedbackReason.NotRelevant))
        waitUntil("the reason was never sent") { feedbackRequests(transport).size == 2 }
        assertEquals("a reason counts once", listOf(TalqynFeedbackReason.NotRelevant), conversation.lastAssistant?.feedbackReasons)
        conversation.rate(turn.id, null)
        waitUntil("the withdrawal was never sent") { feedbackRequests(transport).size == 3 }

        val sent = feedbackRequests(transport)
        assertEquals("down", sent[0].bodyJson["verdict"])
        assertEquals(turnId, sent[0].bodyJson["turn_id"])
        assertEquals("sess-1", sent[0].bodyJson["session_id"])
        assertFalse(sent[0].bodyJson.containsKey("reasons"))
        assertEquals(listOf("not_relevant"), sent[1].bodyJson["reasons"])
        assertEquals("DELETE", sent[2].method)
        assertNull(conversation.lastAssistant?.rating)
        assertNull(conversation.state.value.feedbackFailure)
    }

    /** Taps faster than the network are not queued: what is sent is where the taps ended, and taps that end where they started send nothing. */
    @Test
    fun tapsThatEndWhereTheyStartedSendNothing() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(fullTurn(turnId = turnId))
        val conversation = makeConversation(transport)
        conversation.send("need a laptop")
        settle(conversation)
        val id = conversation.turns.last().id

        conversation.rate(id, TalqynAnswerRating.Helpful)
        conversation.rate(id, TalqynAnswerRating.NotHelpful)
        conversation.rate(id, null)
        delay(50)
        assertTrue(feedbackRequests(transport).isEmpty())
    }

    /** A thumb that looks pressed must be pressed: a rating Talqyn did not take goes back to what Talqyn holds. */
    @Test
    fun aRatingTalqynRefusesIsPutBack() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(fullTurn(turnId = turnId))
        transport.enqueue("""{"error":"internal_error"}""", status = 500)
        val conversation = makeConversation(transport)
        conversation.send("need a laptop")
        settle(conversation)
        val id = conversation.turns.last().id

        conversation.rate(id, TalqynAnswerRating.Helpful)
        assertEquals("shown at once", TalqynAnswerRating.Helpful, conversation.lastAssistant?.rating)
        waitUntil("the refusal never came back") { conversation.state.value.feedbackFailure != null }
        assertNull(conversation.lastAssistant?.rating)
        assertEquals(500, conversation.state.value.feedbackFailure?.statusCode)
    }

    /** Taking back a rating Talqyn no longer has is done, not failed — a repeat of a withdrawal that went through answers exactly this. */
    @Test
    fun withdrawingARatingThatIsAlreadyGoneIsDone() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(fullTurn(turnId = turnId))
        transport.enqueue("", status = 204)
        transport.enqueue("""{"detail":"feedback not found"}""", status = 404)
        val conversation = makeConversation(transport)
        conversation.send("need a laptop")
        settle(conversation)
        val id = conversation.turns.last().id

        conversation.rate(id, TalqynAnswerRating.Helpful)
        waitUntil("the like was never sent") { feedbackRequests(transport).size == 1 }
        conversation.rate(id, null)
        waitUntil("the withdrawal was never sent") { feedbackRequests(transport).size == 2 }
        delay(30)
        assertNull(conversation.lastAssistant?.rating)
        assertNull(conversation.state.value.feedbackFailure)
    }

    /** A chat reopened from history shows the thumb that is already down, and taking it back is a withdrawal of the rating Talqyn holds. */
    @Test
    fun aRestoredRatingIsTheOneTalqynHolds() = mainTest {
        val transport = StubTransport()
        transport.enqueue(
            """
            {"session_id":"s1","messages":[
              {"role":"user","text":"laptop","talqyn_ids":[],"turn_id":"$turnId","feedback":"down"},
              {"role":"assistant","text":"here","talqyn_ids":[],"turn_id":"$turnId","feedback":"down"}],
             "products":[]}
            """.trimIndent(),
        )
        transport.enqueue("", status = 204)
        val conversation = makeConversation(transport)
        conversation.restore("s1")
        waitUntil("the chat never opened") { !conversation.state.value.isRestoring }

        val turn = conversation.lastAssistant!!
        assertEquals(TalqynAnswerRating.NotHelpful, turn.rating)
        assertEquals(turnId, turn.talqynTurnId)
        assertEquals("s1", turn.sessionId)

        conversation.rate(turn.id, null)
        waitUntil("the withdrawal was never sent") { feedbackRequests(transport).size == 1 }
        assertEquals("DELETE", feedbackRequests(transport).first().method)
    }

    /** A turn is compared as a whole: every property that changes redraws it. */
    @Test
    fun turnsCompareByEveryProperty() {
        val original = TalqynAssistantTurn(id = UUID.randomUUID(), question = "q")
        assertNotEquals(original, original.copy(feedbackReasons = listOf(TalqynFeedbackReason.Other)))
        val failed = original.copy(failure = TalqynException.Transport(SocketTimeoutException("timed out")))
        assertNotEquals(original, failed)
        val same = original.copy(failure = TalqynException.Transport(SocketTimeoutException("timed out")))
        assertEquals("the same failure twice is no change", failed, same)
    }

    @Test
    fun productTapIsReportedWithSearchIdAndPosition() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(fullTurn())
        transport.enqueue("", status = 204)
        val conversation = makeConversation(transport)

        conversation.send("question")
        settle(conversation)
        val turn = conversation.lastAssistant!!
        conversation.trackProductTap(turn.products[1], turn)

        waitUntil("the click was never reported") { transport.sent.any { it.path.endsWith("/events/product-click") } }
        val click = transport.sent.last { it.path.endsWith("/events/product-click") }
        assertEquals("srch-1", click.bodyJson["search_id"])
        assertEquals(2L, click.bodyJson["talqyn_id"])
        assertEquals(1L, click.bodyJson["position"])
        assertEquals("cip", click.bodyJson["source"])
    }

    @Test
    fun resetAndDiscardIfOpen() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(fullTurn(sessionId = "sess-1"))
        val conversation = makeConversation(transport)

        conversation.send("question")
        settle(conversation)
        conversation.discardIfOpen("other")
        assertEquals("another chat's deletion changes nothing", 2, conversation.turns.size)

        conversation.discardIfOpen("sess-1")
        assertTrue(conversation.turns.isEmpty())
        assertNull(conversation.state.value.sessionId)
        assertTrue(conversation.state.value.productsById.isEmpty())
    }

    @Test
    fun restoreLoadsATranscript() = mainTest {
        val transport = StubTransport()
        transport.enqueue(
            """
            {"session_id":"s1","messages":[
              {"role":"user","text":"iphone","talqyn_ids":[],"route":"redirect"},
              {"role":"assistant","text":"iphone 15","talqyn_ids":[]},
              {"role":"user","text":"laptop","talqyn_ids":[]},
              {"role":"assistant","text":"here [p:1]","talqyn_ids":[1,9]},
              {"role":"assistant","text":"and more","talqyn_ids":[]}],
             "products":[{"talqyn_id":1,"title":"Acer"}]}
            """.trimIndent(),
        )
        val conversation = makeConversation(transport)

        conversation.restore("s1")
        assertTrue(conversation.state.value.isRestoring)
        waitUntil("the chat never opened") { !conversation.state.value.isRestoring }

        val state = conversation.state.value
        assertNull(state.restoreFailure)
        assertEquals("s1", state.sessionId)
        assertEquals(5, state.turns.size)
        val redirect = state.turns[1] as TalqynAssistantTurn
        assertEquals("a redirect row is a query, not prose", "iphone 15", redirect.redirectQuery)
        assertEquals("", redirect.text)
        val answer = state.turns[3] as TalqynAssistantTurn
        assertEquals("here [p:1]", answer.text)
        assertEquals("an id with no card behind it is skipped", listOf(1L), answer.products.map { it.talqynId })
        assertEquals("an orphan assistant row keeps its place", "", (state.turns[4] as TalqynAssistantTurn).question)
        assertNull(answer.stage)
        assertEquals("locale=en", transport.sent.last().query)
    }

    @Test
    fun restoreFailureIsSurfaced() = mainTest {
        val transport = StubTransport()
        transport.enqueue("""{"detail":"chat not found"}""", status = 404)
        val conversation = makeConversation(transport)

        conversation.restore("gone")
        waitUntil("the restore never ended") { !conversation.state.value.isRestoring }
        val failure = conversation.state.value.restoreFailure
        assertTrue("expected NotFound, got $failure", failure is TalqynException.NotFound)
        assertTrue(conversation.turns.isEmpty())
    }

    /**
     * Coming back from a product page re-checks the identity. The shopper id was a local UUID
     * before the first token and the server's form after it; that is the same shopper, and the
     * transcript must stay.
     */
    @Test
    fun tokenIssuanceIsNotAnIdentityChange() = mainTest {
        val transport = StubTransport()
        transport.enqueueDeviceToken(userId = "6f1c2b9a-3e47-4b8f-9a10-2c5d8e7f4a01") // not the local UUID
        transport.enqueueStream(fullTurn())
        val talqyn = TestFixtures.client(transport, credentials = TestFixtures.deviceToken(TalqynDeviceIdentity.PersistentAnonymous))
        val conversation = TalqynConversation(talqyn, scope = TestMain.scope())

        conversation.refreshIdentity()
        conversation.send("question")
        settle(conversation)
        assertEquals(2, conversation.turns.size)

        conversation.refreshIdentity()
        assertEquals("the token names the same shopper the app did", 2, conversation.turns.size)
    }

    @Test
    fun identityChangeDropsTheTranscript() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(fullTurn())
        val talqyn = TestFixtures.preparedClient(transport)
        val conversation = TalqynConversation(talqyn, scope = TestMain.scope())

        conversation.refreshIdentity()
        conversation.send("question")
        settle(conversation)
        conversation.refreshIdentity()
        assertEquals("same shopper, same transcript", 2, conversation.turns.size)

        transport.enqueueDeviceToken(token = "tlqd_named", userId = "6f1c2b9a-3e47-4b8f-9a10-2c5d8e7f4a01")
        talqyn.setIdentity(TalqynDeviceIdentity.User(UUID.randomUUID()))
        conversation.refreshIdentity()
        assertTrue("a new shopper starts clean", conversation.turns.isEmpty())
    }
}
