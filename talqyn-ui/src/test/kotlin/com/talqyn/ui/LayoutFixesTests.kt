package com.talqyn.ui

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.talqyn.consultant.TalqynAssistantTurn
import com.talqyn.consultant.TalqynClarifyDraft
import com.talqyn.consultant.TalqynConversationState
import com.talqyn.consultant.TalqynPriceFormatter
import com.talqyn.consultant.TalqynTurn
import com.talqyn.consultant.TalqynUiStrings
import com.talqyn.consultant.TalqynUserTurn
import com.talqyn.sdk.TalqynComparisonTable
import com.talqyn.sdk.TalqynProduct
import java.time.Duration

/**
 * Where the screen puts what it draws: the end of the transcript, the comparison's bar, the carousel.
 *
 * On native graphics: the default stand-ins measure a whole line of text at a few pixels, and
 * what a layout does with real text is what is under test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class LayoutFixesTests {
    @get:Rule
    val compose = createComposeRule()

    private fun settle() {
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.waitForIdle()
        }
    }

    // region The keyboard

    private val composer = 120.dp
    private val keyboard = 300.dp

    private fun longChat(): TalqynConversationState {
        val paragraph = List(8) { "A basic model with a manual frother that keeps the same tank and pressure." }.joinToString(" ")
        val turns = ArrayList<TalqynTurn>()
        repeat(4) { index ->
            turns.add(TalqynUserTurn(text = "question $index"))
            turns.add(TalqynAssistantTurn(question = "question $index", text = "$paragraph\n\n$paragraph", stage = null))
        }
        return TalqynConversationState(turns = turns)
    }

    private val noActions = TalqynTurnActions(
        onProduct = { _, _ -> }, onProductMention = { _, _ -> }, onRetry = {}, onRedirect = {}, onFilters = { _, _ -> },
        onComparison = { _, _ -> }, onSubmitClarify = { _, _ -> }, onClarifyDraft = { _, _ -> },
        currentClarifyDraft = { TalqynClarifyDraft() }, onSuggestion = {}, onRate = { _, _, _ -> }, onEditQuestion = {},
    )

    private class Shown(val transcript: TalqynTranscriptState, val reserve: () -> Dp, val setReserve: (Dp) -> Unit)

    private fun showTranscript(anchor: TalqynTranscriptAnchor): Shown {
        val rows = TalqynTranscriptRowBuilder(TalqynUiStrings.En, TalqynPriceFormatter.Tenge).rows(longChat(), emptySet())
        var reserve by mutableStateOf(composer)
        var transcript: TalqynTranscriptState? = null
        compose.setContent {
            ProvideTalqyn(TalqynTheme.Default, TalqynUiStrings.En, TalqynPriceFormatter.Tenge, TalqynUrlImageLoader.Shared) {
                val listState = rememberLazyListState()
                val state = remember(listState) { TalqynTranscriptState(listState) }
                transcript = state
                TalqynTranscript(rows, TalqynAnchorRequest(1, anchor), reserve, state, noActions, Modifier.fillMaxSize())
            }
        }
        settle()
        return Shown(checkNotNull(transcript), { reserve }, { reserve = it })
    }

    /** How much of the end of the transcript is under the composer, in pixels. */
    private fun hiddenTail(shown: Shown): Int {
        val info = shown.transcript.listState.layoutInfo
        val last = info.visibleItemsInfo.first { it.index == info.totalItemsCount - 2 }
        val reservePx = (shown.reserve().value * compose.density.density).toInt()
        return last.offset + last.size - (info.viewportEndOffset - reservePx)
    }

    /** A shopper at the end of the conversation taps the field: the last answer moves up with the keyboard rather than under it. */
    @Test
    fun theEndOfTheTranscriptStaysInViewAsTheKeyboardComesAndGoes() {
        val shown = showTranscript(TalqynTranscriptAnchor.Bottom)
        assertTrue("the transcript did not start at its end", hiddenTail(shown) <= 1)

        // The keyboard rises over a few frames, and the composer with it.
        for (step in 1..4) {
            shown.setReserve(composer + keyboard * step / 4)
            settle()
        }
        assertTrue("the keyboard covers the end of the answer: ${hiddenTail(shown)} px", hiddenTail(shown) <= 1)
        assertTrue(shown.transcript.isPinnedToBottom)

        shown.setReserve(composer)
        settle()
        assertTrue(hiddenTail(shown) <= 1)
        assertTrue(shown.transcript.isPinnedToBottom)
    }

    /** One reading somewhere above the end is left where they are. */
    @Test
    fun aShopperReadingAboveTheEndIsNotMoved() {
        val shown = showTranscript(TalqynTranscriptAnchor.Bottom)
        compose.runOnIdle { kotlinx.coroutines.runBlocking { shown.transcript.listState.scrollToItem(1, 40) } }
        settle()
        val before = shown.transcript.listState.run { firstVisibleItemIndex to firstVisibleItemScrollOffset }

        shown.setReserve(composer + keyboard)
        settle()
        assertEquals(before, shown.transcript.listState.run { firstVisibleItemIndex to firstVisibleItemScrollOffset })
    }

    // endregion

    // region The comparison's bar

    private fun phones() = mapOf(
        1L to TalqynProduct(talqynId = 1, title = "Samsung Galaxy S25 256GB", price = 449_990.0),
        2L to TalqynProduct(talqynId = 2, title = "Apple iPhone 16 256GB", price = 529_990.0),
    )

    private fun table(differs: Boolean) = TalqynComparisonTable(
        talqynIds = listOf(1, 2),
        titles = listOf("A", "B"),
        rows = listOf(
            TalqynComparisonTable.Row("Storage", listOf("256 GB", "256 GB")),
            TalqynComparisonTable.Row("Battery", listOf("4000 mAh", if (differs) "3561 mAh" else "4000 mAh")),
        ),
    )

    private fun textLayout(text: String): TextLayoutResult {
        val results = ArrayList<TextLayoutResult>()
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    /** The switch is written out whole in every language the SDK speaks: cut short, it does not say what it switches. */
    @Test
    fun theDifferencesSwitchIsNeverCutShort() {
        var width by mutableStateOf(390.dp)
        var strings by mutableStateOf(TalqynUiStrings.Kk)
        compose.setContent {
            Box(Modifier.width(width)) {
                TalqynComparisonScreen(table = table(differs = true), products = phones(), onOpenProduct = {}, onClose = {}, strings = strings)
            }
        }
        for (copy in listOf(TalqynUiStrings.En, TalqynUiStrings.Ru, TalqynUiStrings.Kk)) {
            for (each in listOf(390.dp, 360.dp)) {
                strings = copy
                width = each
                settle()
                val label = "${copy.locale} at ${each.value.toInt()} dp"
                val switch = textLayout(copy.comparisonOnlyDifferences)
                assertFalse("$label: the switch is cut short", switch.isLineEllipsized(0))
                val switchBounds = compose.onNodeWithText(copy.comparisonOnlyDifferences, useUnmergedTree = true).getBoundsInRoot()
                val titleBounds = compose.onNodeWithText(copy.comparisonTitle, useUnmergedTree = true).getBoundsInRoot()
                assertTrue("$label: the title runs into the switch", titleBounds.left >= switchBounds.right)
            }
        }
    }

    /** With nothing to switch, the title has the middle of the bar to itself. */
    @Test
    fun theTitleIsCenteredWhenThereIsNothingToSwitch() {
        compose.setContent {
            Box(Modifier.width(390.dp)) {
                TalqynComparisonScreen(table = table(differs = false), products = phones(), onOpenProduct = {}, onClose = {}, strings = TalqynUiStrings.Kk)
            }
        }
        settle()
        val title = compose.onNodeWithText(TalqynUiStrings.Kk.comparisonTitle, useUnmergedTree = true).getBoundsInRoot()
        assertEquals(195f, (title.left + title.right).value / 2, 1f)
    }

    // endregion

    // region The carousel

    /** The tiles of a carousel end on one line and keep their prices on one, however long each title is and whatever else a tile says. */
    @Test
    fun carouselTilesShareAHeight() {
        val short = TalqynProduct(talqynId = 1, title = "Acer", price = 100_000.0)
        val long = TalqynProduct(talqynId = 2, title = "Coffee machine Delonghi Magnifica S ECAM-21.117.SB", price = 200_000.0, inStock = false)
        compose.setContent {
            ProvideTalqyn(TalqynTheme.Default, TalqynUiStrings.En, TalqynPriceFormatter.Tenge, TalqynUrlImageLoader.Shared) {
                TalqynProductSections(listOf(TalqynProductSection("More", listOf(short, long)))) {}
            }
        }
        settle()
        // A card merges what it holds into the node that takes its tap: found by its title, it is the whole card.
        val first = compose.onNodeWithText("Acer", substring = true).getBoundsInRoot()
        val second = compose.onNodeWithText("Delonghi", substring = true).getBoundsInRoot()
        assertEquals("the tiles are of different heights", (first.bottom - first.top).value, (second.bottom - second.top).value, 0.5f)
        val prices = listOf(100_000.0, 200_000.0).map(TalqynPriceFormatter.Tenge::format).map { compose.onNodeWithText(it, useUnmergedTree = true).getBoundsInRoot().top.value }
        assertEquals("the prices are on different lines", prices[0], prices[1], 0.5f)
    }

    // endregion
}
