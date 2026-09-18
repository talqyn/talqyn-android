package com.talqyn.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.talqyn.consultant.TalqynAnswerRating
import com.talqyn.consultant.TalqynAssistantTurn
import com.talqyn.sdk.TalqynFeedbackReason
import com.talqyn.sdk.TalqynProduct

/**
 * How a product card is laid out where it is shown — what the app is told when it draws the
 * card itself.
 */
public enum class TalqynProductCardLayout {
    /** Image at the start, details beside it, the full width of the transcript: a product cited in the text. */
    Horizontal,

    /** Image over the details: a tile in the carousel under the answer, or one of a pair of products cited by one sentence. */
    Vertical,
}

/**
 * The product card itself, drawn by the app in place of the SDK's: its own image loading,
 * price, badges, and buttons.
 *
 * Asked once per card shown; return `null` for the SDK's card — the default, and the choice
 * may differ by product or by layout. The SDK sets the width — the transcript's for a
 * horizontal card, its own tile's for a vertical one — as the card's minimum and maximum both
 * (`Modifier.requiredWidth` inside the card overrides it), and takes the height from the card.
 * A card is never asked for its intrinsic size, so it may be built of anything, an async image
 * or a pager included; the two cards of a pair each stand at their own height, top-aligned.
 *
 * The SDK also handles the tap on the card: it reports the click to Talqyn and calls
 * `onOpenProduct`, so the card must not open the product itself. Buttons inside it — the cart,
 * the wishlist — keep working as usual.
 *
 * ```kotlin
 * productCard = TalqynProductCardProvider { product, layout ->
 *     when (layout) {
 *         TalqynProductCardLayout.Horizontal -> { { ProductRowCard(product) } }
 *         TalqynProductCardLayout.Vertical -> { { ProductTileCard(product) } }
 *     }
 * }
 * ```
 */
public fun interface TalqynProductCardProvider {
    public fun card(product: TalqynProduct, layout: TalqynProductCardLayout): (@Composable () -> Unit)?
}

/**
 * The way out of the consultant, drawn at the start of its header.
 *
 * Compose does not tell a screen how it was opened, so the app says it: a back arrow for a
 * destination on a navigation stack, a cross for a screen shown over another, `null` for a
 * root — a tab of its own — where there is nowhere to go back to. With a way out, history
 * moves to the end of the header, next to a new chat.
 */
@Immutable
public sealed interface TalqynNavigation {
    /** Invoked when the arrow is tapped. */
    public val onClick: () -> Unit

    /** A back arrow. */
    public class Back(override val onClick: () -> Unit) : TalqynNavigation

    /** A cross. */
    public class Close(override val onClick: () -> Unit) : TalqynNavigation
}

/**
 * The shopper rated a turn, or picked a reason under a thumb down. The rating is already on
 * its way to Talqyn; this is for the app's own analytics.
 */
public fun interface TalqynRatingListener {
    /**
     * @param rating The verdict, or `null` when it was taken back.
     * @param reasons Why the answer did not help, as picked so far.
     * @param turn The turn rated.
     */
    public fun onRate(rating: TalqynAnswerRating?, reasons: List<TalqynFeedbackReason>, turn: TalqynAssistantTurn)
}
