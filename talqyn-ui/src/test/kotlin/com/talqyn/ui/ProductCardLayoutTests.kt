package com.talqyn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.talqyn.consultant.TalqynPriceFormatter
import com.talqyn.consultant.TalqynUiStrings
import com.talqyn.sdk.TalqynProduct

/** How the screen lays out product cards — the app's own above all, which may be built of anything. */
@RunWith(RobolectricTestRunner::class)
class ProductCardLayoutTests {
    @get:Rule
    val compose = createComposeRule()

    private fun product(id: Long) = TalqynProduct(talqynId = id, title = "Product $id")

    @Composable
    private fun Cards(provider: TalqynProductCardProvider?, content: @Composable () -> Unit) {
        ProvideTalqyn(TalqynTheme.Default, TalqynUiStrings.Ru, TalqynPriceFormatter.Tenge, TalqynUrlImageLoader.Shared) {
            CompositionLocalProvider(LocalTalqynCardProvider provides provider, content = content)
        }
    }

    /**
     * `BoxWithConstraints` is a `SubcomposeLayout`, as Coil's `SubcomposeAsyncImage`, a pager, or a
     * lazy row inside a card are — and a `SubcomposeLayout` cannot be asked for its intrinsic size.
     */
    @Test
    fun aPairOfAppCardsThatCannotBeMeasuredForIntrinsicsLaysOut() {
        val provider = TalqynProductCardProvider { product, _ -> { BoxWithConstraints { BasicText(product.title, Modifier.widthIn(max = maxWidth)) } } }
        compose.setContent { Cards(provider) { TalqynCitedCards(listOf(product(1), product(2))) {} } }
        compose.onNodeWithText("Product 1").assertIsDisplayed()
        compose.onNodeWithText("Product 2").assertIsDisplayed()
    }

    @Test
    fun aPairOfTheAppsCardAndTheSdksLaysOut() {
        val provider = TalqynProductCardProvider { product, _ ->
            if (product.talqynId == 1L) {
                { BoxWithConstraints { BasicText("custom ${product.title}", Modifier.widthIn(max = maxWidth)) } }
            } else {
                null
            }
        }
        compose.setContent { Cards(provider) { TalqynCitedCards(listOf(product(1), product(2))) {} } }
        compose.onNodeWithText("custom Product 1").assertIsDisplayed()
        compose.onNodeWithText("Product 2").assertIsDisplayed()
    }

    /** The width the SDK gives a card is its minimum too: a card with narrower content still spans its half of the row. */
    @Test
    fun anAppCardSpansItsHalfOfThePair() {
        val provider = TalqynProductCardProvider { product, _ -> { BasicText(product.title, Modifier.testTag("card-${product.talqynId}")) } }
        // Narrower than Robolectric's default screen, 320 dp, so the row is exactly this wide.
        compose.setContent { Cards(provider) { Box(Modifier.width(300.dp)) { TalqynCitedCards(listOf(product(1), product(2))) {} } } }
        // The frame takes the tap, and a clickable merges what it holds: the card is found in the unmerged tree.
        val first = compose.onNodeWithTag("card-1", useUnmergedTree = true).getBoundsInRoot()
        val second = compose.onNodeWithTag("card-2", useUnmergedTree = true).getBoundsInRoot()
        first.width.assertIsEqualTo(146.dp, "the width of the first card")
        second.width.assertIsEqualTo(146.dp, "the width of the second card")
        second.left.assertIsEqualTo(154.dp, "where the second card starts")
    }
}
