package com.talqyn.ui

import android.os.Looper
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.talqyn.consultant.TalqynAssistantTurn
import com.talqyn.consultant.TalqynConversation
import com.talqyn.consultant.TalqynPriceFormatter
import com.talqyn.consultant.TalqynUiStrings
import com.talqyn.sdk.Talqyn
import com.talqyn.sdk.TalqynHttpRequest
import com.talqyn.sdk.TalqynHttpResponse
import com.talqyn.sdk.TalqynHttpResult
import com.talqyn.sdk.TalqynHttpStream
import com.talqyn.sdk.TalqynHttpTransport
import java.time.Duration

/** The screen itself, composed: what the shopper — and TalkBack — find on it as the conversation goes. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ConsultantScreenTests {
    @get:Rule
    val compose = createComposeRule()

    private val strings = TalqynUiStrings.En
    private val question = "recommend a laptop"
    private val clarifyMessage = "Tell me the budget"

    private val answerHead = sse("status", """{"stage":"thinking"}""") + sse("delta", """{"text":"I will pick"}""")
    private val answerTail =
        sse("clarify", """{"message":"$clarifyMessage","questions":[{"id":"budget","label":"Budget","multi":false,"options":["under 300k"]}]}""") +
            sse("done", """{"session_id":"sess-1"}""")

    /** A stream that holds before its last lines until the test lets them go: what happens mid-turn is up to the test, not to timing. */
    private class GatedTransport(private val head: List<String>, private val tail: List<String>) : TalqynHttpTransport {
        val responses = StubTransport().apply { enqueueDeviceToken() }
        val gate = CompletableDeferred<Unit>()

        override suspend fun send(request: TalqynHttpRequest): TalqynHttpResult = responses.send(request)

        override suspend fun stream(request: TalqynHttpRequest): TalqynHttpStream = TalqynHttpStream(
            TalqynHttpResponse(200),
            flow {
                head.forEach { emit(it) }
                gate.await()
                tail.forEach { emit(it) }
            },
        )
    }

    private class Screen(val conversation: TalqynConversation, val presentation: TalqynScreenPresentation)

    /** The screen as an app composes it, with a switch that takes it out of composition the way a navigation host does. */
    private fun showScreen(talqyn: Talqyn, shown: () -> Boolean): Screen {
        var screen: Screen? = null
        compose.setContent {
            val conversation = rememberTalqynConversation(talqyn)
            val store = viewModel(key = "com.talqyn.presentation") { TalqynPresentationStore() }
            screen = Screen(conversation, store.presentation(conversation))
            if (shown()) {
                TalqynConsultantScreen(conversation = conversation, strings = strings, onOpenProduct = {}, onOpenSearch = {}, onApplyFilters = {})
            }
        }
        compose.waitForIdle()
        return checkNotNull(screen)
    }

    /**
     * Waits for the conversation, which runs on the main looper. Robolectric's looper moves only when
     * told to: each round runs the work that is due, moves the clock past the conversation's delta
     * flush, and gives the transport's own threads a moment.
     */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting: $what")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.waitForIdle()
            Thread.sleep(10)
        }
    }

    /** A product page opened over the screen mid-answer: the answer settles while the screen is gone, and its question comes up when the shopper is back. */
    @Test
    fun aQuestionThatCameWhileTheScreenWasAwayComesUpOnTheWayBack() {
        val transport = GatedTransport(answerHead, answerTail)
        var shown by mutableStateOf(true)
        val screen = showScreen(TestFixtures.client(transport)) { shown }

        compose.runOnIdle { screen.conversation.send(question) }
        waitFor("the answer to start") { (screen.conversation.state.value.turns.lastOrNull() as? TalqynAssistantTurn)?.text?.isNotEmpty() == true }

        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        transport.gate.complete(Unit)
        waitFor("the answer to settle") { !screen.conversation.state.value.isStreaming }

        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        assertEquals(screen.conversation.state.value.turns.last().id, screen.presentation.sheetTurnId)
        compose.onNodeWithText(clarifyMessage).assertExists()
    }

    /** A screen composed over a conversation whose last answer asks: the question stands in the transcript rather than vanishing, and nothing comes up over the screen. */
    @Test
    fun aQuestionAskedBeforeTheScreenWasComposedStandsInTheTranscript() {
        val transport = StubTransport().apply {
            enqueueDeviceToken()
            enqueueStream(answerHead + answerTail)
        }
        var shown by mutableStateOf(false)
        val screen = showScreen(TestFixtures.client(transport)) { shown }

        compose.runOnIdle { screen.conversation.send(question) }
        waitFor("the answer to settle") { screen.conversation.state.value.let { !it.isStreaming && it.turns.size == 2 } }

        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        assertNull("nothing comes up over a screen just opened", screen.presentation.sheetTurnId)
        assertEquals(1, compose.onAllNodesWithText(clarifyMessage).fetchSemanticsNodes().size)
    }

    /** The comparison opened over the transcript: TalkBack reads the table, not the answer behind it. */
    @Test
    fun theComparisonHidesTheTranscriptFromAccessibility() {
        val transport = StubTransport().apply {
            enqueueDeviceToken()
            enqueueStream(
                sse("delta", """{"text":"Compared the two laptops"}""") +
                    sse("products", """{"items":[{"talqyn_id":1,"title":"Acer"},{"talqyn_id":2,"title":"Asus"}],"search_id":"srch-1"}""") +
                    sse("action", """{"type":"show_comparison","table":{"talqyn_ids":[1,2],"titles":["Acer","Asus"],"rows":[{"label":"Screen","values":["15","14"]}]}}""") +
                    sse("done", """{"session_id":"sess-1"}"""),
            )
        }
        val screen = showScreen(TestFixtures.client(transport)) { true }

        compose.runOnIdle { screen.conversation.send(question) }
        waitFor("the comparison to be offered") { compose.onAllNodesWithText(strings.openComparison).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Compared the two laptops").assertExists()

        // As TalkBack activates it: by its action, wherever the chip is scrolled to.
        compose.onNodeWithText(strings.openComparison).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertNotNull("the chip opens the comparison", screen.presentation.comparison)
        compose.onNodeWithText(strings.comparisonTitle).assertExists()
        compose.onNodeWithText("Compared the two laptops").assertDoesNotExist()
    }

    /** A dialog is a window of its own, composed under the system's font scale again: its content caps the type the way the screen does. */
    @Test
    @Config(fontScale = 2f)
    fun aWindowTheScreenOpensCapsTheTypeLikeTheScreen() {
        var inScreen = 0f
        var inWindow = 0f
        var inWindowCapped = 0f
        val theme = TalqynTheme(fonts = TalqynTheme.Fonts.System.copy(maximumScale = 1.3f))
        compose.setContent {
            ProvideTalqyn(theme, strings, TalqynPriceFormatter.Tenge, TalqynUrlImageLoader.Shared) {
                inScreen = LocalDensity.current.fontScale
                Dialog(onDismissRequest = {}) {
                    inWindow = LocalDensity.current.fontScale
                    ProvideTalqynDensity {
                        inWindowCapped = LocalDensity.current.fontScale
                        BasicText("question")
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1.3f, inScreen)
        assertEquals("the window hands its content the system's scale", 2f, inWindow)
        assertEquals(1.3f, inWindowCapped)
    }
}
