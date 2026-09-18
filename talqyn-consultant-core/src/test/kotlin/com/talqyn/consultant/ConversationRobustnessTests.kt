package com.talqyn.consultant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.talqyn.sdk.TalqynDeviceIdentity
import com.talqyn.sdk.TalqynException
import com.talqyn.sdk.TalqynHttpRequest
import com.talqyn.sdk.TalqynHttpResponse
import com.talqyn.sdk.TalqynHttpResult
import com.talqyn.sdk.TalqynHttpStream
import com.talqyn.sdk.TalqynHttpTransport
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** The conversation and the history list at their edges: a closed scope, a shopper changing mid-request, a screen closing mid-write. */
class ConversationRobustnessTests {
    private fun event(name: String, data: String): List<String> = listOf("event: $name", "data: $data", "")

    /** A transport whose first mint waits for the test: the window in which the shopper can change. */
    private class GatedMintTransport(val stub: StubTransport = StubTransport()) : TalqynHttpTransport {
        val mintStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun send(request: TalqynHttpRequest): TalqynHttpResult {
            if (request.url.endsWith("/consultant/token")) {
                mintStarted.complete(Unit)
                release.await()
                return TalqynHttpResult(TOKEN.toByteArray(), TalqynHttpResponse(200))
            }
            return stub.send(request)
        }

        override suspend fun stream(request: TalqynHttpRequest): TalqynHttpStream = stub.stream(request)
    }

    /** A transport whose writes take a while and remember whether they finished. */
    private class SlowWriteTransport(val stub: StubTransport, private val path: String) : TalqynHttpTransport {
        val finished = AtomicBoolean(false)

        override suspend fun send(request: TalqynHttpRequest): TalqynHttpResult {
            if (!request.url.contains(path)) return stub.send(request)
            delay(150)
            finished.set(true)
            return TalqynHttpResult(ByteArray(0), TalqynHttpResponse(204))
        }

        override suspend fun stream(request: TalqynHttpRequest): TalqynHttpStream = stub.stream(request)
    }

    // region Scope

    @Test
    fun closeEndsTheConversationButNotTheScopeItWasGiven() = mainTest {
        val parent = TestMain.scope()
        val conversation = TalqynConversation(TestFixtures.client(StubTransport()), scope = parent)

        conversation.close()
        assertTrue("the host's scope keeps its own work", parent.isActive)

        conversation.send("question")
        assertFalse("a closed conversation takes no work", conversation.state.value.isStreaming)
        assertTrue(conversation.state.value.turns.isEmpty())
    }

    /** A conversation outliving its scope must not show a turn that streams for ever with nothing behind it. */
    @Test
    fun aConversationWhoseScopeEndedDoesNotWedge() = mainTest {
        val parent = TestMain.scope()
        val conversation = TalqynConversation(TestFixtures.client(StubTransport()), scope = parent)
        parent.cancel()

        conversation.send("question")
        conversation.restore("s1")
        assertFalse(conversation.state.value.isStreaming)
        assertFalse(conversation.state.value.isRestoring)
    }

    @Test
    fun closeEndsTheHistoryButNotTheScopeItWasGiven() = mainTest {
        val parent = TestMain.scope()
        val history = TalqynChatHistory(TestFixtures.client(StubTransport()), scope = parent)
        history.close()
        assertTrue(parent.isActive)
        history.load()
        assertEquals("a closed list loads nothing", TalqynChatHistory.State.Loading, history.state.value)
    }

    // endregion

    // region A shopper changing mid-request

    @Test
    fun aShopperChangingBeforeTheQuestionWentOutStopsTheTurn() = mainTest {
        val transport = GatedMintTransport()
        val talqyn = TestFixtures.client(transport)
        val conversation = TalqynConversation(talqyn, scope = TestMain.scope())

        conversation.send("question")
        transport.mintStarted.await()
        talqyn.setIdentity(TalqynDeviceIdentity.User(UUID.randomUUID()))
        transport.release.complete(Unit)

        waitUntil("the turn never ended") { !conversation.state.value.isStreaming }
        val turn = conversation.state.value.turns.last() as TalqynAssistantTurn
        assertTrue(turn.wasStopped)
        assertNull("nothing failed: nothing was asked", turn.failure)
    }

    @Test
    fun aShopperChangingWhileAChatIsRestoredEndsTheRestore() = mainTest {
        val transport = GatedMintTransport()
        val talqyn = TestFixtures.client(transport)
        val conversation = TalqynConversation(talqyn, scope = TestMain.scope())

        conversation.restore("s1")
        transport.mintStarted.await()
        talqyn.setIdentity(TalqynDeviceIdentity.User(UUID.randomUUID()))
        transport.release.complete(Unit)

        waitUntil("the restore spinner never went away") { !conversation.state.value.isRestoring }
        val failure = conversation.state.value.restoreFailure
        assertTrue("got $failure", failure is TalqynException.IdentityChanged)
        conversation.clearRestoreFailure()
    }

    @Test
    fun aShopperChangingWhileTheHistoryLoadsEndsTheLoad() = mainTest {
        val transport = GatedMintTransport()
        val talqyn = TestFixtures.client(transport)
        val history = TalqynChatHistory(talqyn, scope = TestMain.scope())

        history.load()
        transport.mintStarted.await()
        talqyn.setIdentity(TalqynDeviceIdentity.User(UUID.randomUUID()))
        transport.release.complete(Unit)

        waitUntil("the list never left its spinner") { history.state.value !is TalqynChatHistory.State.Loading }
        val state = history.state.value
        assertTrue("got $state", state is TalqynChatHistory.State.Failed && state.error is TalqynException.IdentityChanged)
    }

    // endregion

    // region Writes outlive the screen

    @Test
    fun aRatingOnItsWayIsSavedEvenIfTheConversationCloses() = mainTest {
        val stub = StubTransport()
        stub.enqueueDeviceToken()
        stub.enqueueStream(event("delta", """{"text":"ok"}""") + event("done", """{"session_id":"s1","turn_id":"$TURN"}"""))
        val transport = SlowWriteTransport(stub, "/consultant/feedback")
        val conversation = TalqynConversation(TestFixtures.client(transport), scope = TestMain.scope())
        conversation.send("question")
        waitUntil("the turn never settled") { !conversation.state.value.isStreaming }
        val turn = conversation.state.value.turns.last() as TalqynAssistantTurn

        conversation.rate(turn.id, TalqynAnswerRating.Helpful)
        delay(50)
        conversation.close()
        waitUntil("the rating went away with the screen") { transport.finished.get() }
    }

    @Test
    fun aDeletionOnItsWayIsCarriedOutEvenIfTheListCloses() = mainTest {
        val stub = StubTransport()
        stub.enqueueDeviceToken()
        val transport = SlowWriteTransport(stub, "/consultant/chats/")
        val history = TalqynChatHistory(TestFixtures.client(transport), scope = TestMain.scope())

        history.delete("s1")
        delay(50)
        history.close()
        waitUntil("the deletion went away with the screen") { transport.finished.get() }
    }

    // endregion

    /** A product is shown once however often the stream or the transcript names it: a list keyed by product id cannot hold it twice. */
    @Test
    fun aProductNamedTwiceIsShownOnce() = mainTest {
        val transport = StubTransport()
        transport.enqueueStream(
            event("products", """{"items":[{"talqyn_id":1,"title":"A"},{"talqyn_id":1,"title":"A"},{"talqyn_id":2,"title":"B"}]}""") +
                event("products", """{"items":[{"talqyn_id":2,"title":"B"},{"talqyn_id":3,"title":"C"}]}""") +
                event("done", """{"session_id":"s"}"""),
        )
        transport.enqueue(
            """{"session_id":"s1","messages":[{"role":"user","text":"q","talqyn_ids":[]},""" +
                """{"role":"assistant","text":"a","talqyn_ids":[1,1,2]}],"products":[{"talqyn_id":1,"title":"A"},{"talqyn_id":2,"title":"B"}]}""",
        )
        val conversation = TalqynConversation(TestFixtures.preparedClient(transport), scope = TestMain.scope())

        conversation.send("question")
        waitUntil("the turn never settled") { !conversation.state.value.isStreaming }
        assertEquals(listOf(1L, 2L, 3L), (conversation.state.value.turns.last() as TalqynAssistantTurn).products.map { it.talqynId })

        conversation.restore("s1")
        waitUntil("the chat never opened") { !conversation.state.value.isRestoring }
        assertEquals(listOf(1L, 2L), (conversation.state.value.turns.last() as TalqynAssistantTurn).products.map { it.talqynId })
    }

    /** A Kazakh date of another year keeps its day unpadded, as the twin SDK writes it: `5 қыр.`, not `05 қыр.`. */
    @Test
    fun aKazakhDateOfAnotherYearDoesNotPadTheDay() {
        val zone = ZoneOffset.UTC
        val now = LocalDateTime.of(2026, 9, 10, 15, 30).atZone(zone).toInstant()
        val strings = TalqynUiStrings.Kk
        val subtitle = TalqynChatHistory.subtitle(LocalDateTime.of(2025, 9, 5, 12, 0).atZone(zone).toInstant(), strings, strings.locale, zone, now)
        assertTrue("got '$subtitle'", subtitle.contains("2025") && Regex("(?<![0-9])5(?![0-9])").containsMatchIn(subtitle))
        assertFalse("got '$subtitle'", Regex("(?<![0-9])05(?![0-9])").containsMatchIn(subtitle))
        val russian = TalqynChatHistory.subtitle(LocalDateTime.of(2025, 9, 5, 12, 0).atZone(zone).toInstant(), TalqynUiStrings.Ru, TalqynUiStrings.Ru.locale, zone, now)
        assertTrue("got '$russian'", russian.startsWith("5 сент"))
    }

    private companion object {
        const val TOKEN = """{"token":"tlqd_test","expires_at":"2026-08-26T12:15:00Z","expires_in":900}"""
        const val TURN = "3f2a9c1e-7b4d-4e8a-9c0f-1a2b3c4d5e6f"
    }
}
