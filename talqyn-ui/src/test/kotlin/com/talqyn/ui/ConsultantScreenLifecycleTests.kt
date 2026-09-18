package com.talqyn.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.talqyn.consultant.TalqynAssistantTurn
import com.talqyn.consultant.TalqynConversation

/**
 * Who owns a turn's lifetime. Every turn is an LLM call, so a screen that goes away for good has
 * to stop paying for an answer nobody will read — while a screen merely covered by a product page
 * must not lose the answer the shopper is coming back to.
 *
 * On Android the line between the two is the screen's `ViewModel`: covered, it stays; popped for
 * good, it is cleared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsultantScreenLifecycleTests {
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        // `viewModelScope` runs on the main dispatcher; here that is the tests' main thread.
        Dispatchers.setMain(TestMain.dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun turnLines() =
        sse("status", """{"stage":"thinking"}""") +
            sse("delta", """{"text":"Taking"}""") +
            sse("delta", """{"text":" a laptop"}""") +
            sse("done", """{"session_id":"sess-1"}""")

    /** The conversation the screen would take from `rememberTalqynConversation`. */
    private fun screenConversation(transport: SlowStreamTransport): TalqynConversation {
        val talqyn = TestFixtures.client(transport)
        val factory = viewModelFactory { initializer { TalqynConversationHolder(talqyn, 3) } }
        return ViewModelProvider(store, factory)[TalqynConversationHolder::class.java].conversation
    }

    private fun slowTransport() = SlowStreamTransport().apply {
        responses.enqueueDeviceToken()
        enqueueStream(turnLines())
    }

    private fun TalqynConversation.lastTurn() = state.value.turns.last() as TalqynAssistantTurn

    @Test
    fun leavingTheScreenStopsTheTurn() = mainTest {
        val transport = slowTransport()
        val conversation = screenConversation(transport)

        conversation.send("need a laptop for school")
        waitUntil("the answer never started") { conversation.lastTurn().text.isNotEmpty() }

        store.clear()
        waitUntil("the turn never settled") { !conversation.state.value.isStreaming }

        assertTrue(conversation.lastTurn().wasStopped)
        waitUntil("the request behind the stream is still open") { transport.wasTerminated }
    }

    /** A product page opens over the transcript mid-answer: the turn continues, and the shopper comes back to a finished answer. */
    @Test
    fun aCoveredScreenKeepsTheTurn() = mainTest {
        val transport = slowTransport()
        val conversation = screenConversation(transport)

        conversation.send("need a laptop for school")
        waitUntil("the answer never started") { conversation.lastTurn().text.isNotEmpty() }

        // The screen leaves composition, its ViewModel stays: the conversation is looked up again.
        assertTrue(conversation === screenConversation(transport))
        waitUntil("the turn never settled") { !conversation.state.value.isStreaming }

        val turn = conversation.lastTurn()
        assertFalse(turn.wasStopped)
        assertEquals("Taking a laptop", turn.text)
        assertEquals("sess-1", conversation.state.value.sessionId)
    }
}
