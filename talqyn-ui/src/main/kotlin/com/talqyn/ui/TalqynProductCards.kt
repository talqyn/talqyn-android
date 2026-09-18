package com.talqyn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.talqyn.sdk.TalqynProduct
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * The app's card provider, handed down to every card of the screen.
 *
 * Not a static local: an app that builds its provider in composition hands down a new one with
 * every recomposition of its own, and a static local would recompose the whole screen for it
 * rather than the cards that read it.
 */
internal val LocalTalqynCardProvider = compositionLocalOf<TalqynProductCardProvider?> { null }

/** How the SDK lays its own card out. */
internal enum class TalqynCardStyle {
    /** Image at the start, details beside it. Full width. */
    Row,

    /** Image over details, at the carousel's width. */
    Compact,

    /** Image over details, as wide as its container allows: one of a pair. */
    Tile,
    ;

    /** What the app is told: the carousel tile and the tile of a pair are both vertical. */
    val layout: TalqynProductCardLayout
        get() = if (this == Row) TalqynProductCardLayout.Horizontal else TalqynProductCardLayout.Vertical
}

/** A titled carousel of a turn's products. */
@Immutable
internal data class TalqynProductSection(val title: String, val products: List<TalqynProduct>)

/**
 * A product's card, whichever side draws it: the app's, when its provider has one for the
 * product, otherwise the SDK's. Either way the SDK takes the tap.
 */
@Composable
internal fun TalqynProductCard(
    product: TalqynProduct,
    style: TalqynCardStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val provider = LocalTalqynCardProvider.current
    val appCard = remember(provider, product, style) { provider?.card(product, style.layout) }
    TalqynResolvedProductCard(product, style, appCard, onClick, modifier)
}

/** A card whose provider has already been asked: [appCard] is the app's card, or `null` for the SDK's. */
@Composable
private fun TalqynResolvedProductCard(
    product: TalqynProduct,
    style: TalqynCardStyle,
    appCard: (@Composable () -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberTalqynHaptics()
    val widthModifier = if (style == TalqynCardStyle.Compact) Modifier.width(compactCardWidth()) else Modifier
    val tap = {
        haptics()
        onClick()
    }
    if (appCard != null) {
        // The frame draws nothing — no background, no rounding, no clipping — so the card's own
        // corners and shadow are what the shopper sees. It passes the width it was given on as a
        // minimum too: a card whose content is narrower still spans its tile.
        Box(modifier.then(widthModifier).talqynPressable(onClick = tap), propagateMinConstraints = true) { appCard() }
    } else {
        TalqynSdkProductCard(product, style, modifier.then(widthModifier).talqynPressable(onClick = tap))
    }
}

/** The carousel tile grows with the type, so a title keeps about as many words per line as at the design size. */
@Composable
private fun compactCardWidth() = LocalTalqyn.current.metrics.compactCardWidth * LocalDensity.current.fontScale.coerceAtLeast(1f)

/**
 * The SDK's card: a row for a citation, a compact tile for the carousel, a flexible tile for
 * a pair of cited products side by side.
 *
 * A product the response marked as out of stock is dimmed and says so under the price; a
 * product the response said nothing about is shown as usual.
 */
@Composable
private fun TalqynSdkProductCard(product: TalqynProduct, style: TalqynCardStyle, modifier: Modifier) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val metrics = talqyn.metrics
    val cardShape = RoundedCornerShape(metrics.cardRadius)
    val isOutOfStock = product.inStock == false
    val imageModifier = Modifier
        .clip(cardShape)
        .alpha(if (isOutOfStock) 0.45f else 1f)

    val details: @Composable (Modifier) -> Unit = { detailsModifier ->
        TalqynProductDetails(product, style, isOutOfStock, detailsModifier)
    }

    Box(modifier.background(colors.surface, cardShape).padding(10.dp)) {
        when (style) {
            TalqynCardStyle.Row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TalqynRemoteImage(product.imageUrl, imageModifier.size(metrics.rowCardImageSize))
                details(Modifier.weight(1f))
            }
            TalqynCardStyle.Compact -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TalqynRemoteImage(product.imageUrl, imageModifier.fillMaxWidth().aspectRatio(1f))
                details(Modifier)
            }
            // Two tiles side by side must not cost more height than two rows: the image is a
            // row's image, not a full-width square.
            TalqynCardStyle.Tile -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TalqynRemoteImage(product.imageUrl, imageModifier.fillMaxWidth().height(96.dp))
                details(Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
private fun TalqynProductDetails(product: TalqynProduct, style: TalqynCardStyle, isOutOfStock: Boolean, modifier: Modifier) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val fonts = talqyn.fonts
    val isRow = style == TalqynCardStyle.Row
    val price = product.price?.takeIf { it > 0 }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (isRow) 4.dp else 6.dp)) {
        BasicText(
            text = product.title,
            style = (if (isRow) fonts.callout else fonts.caption).copy(color = if (isOutOfStock) colors.textTertiary else colors.textSecondary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TalqynRatingRow(product.rating, product.reviewsCount)

        val priceText: @Composable () -> Unit = {
            if (price != null) {
                BasicText(
                    text = talqyn.price.format(price),
                    style = (if (isRow) fonts.headline else fonts.label).copy(color = if (isOutOfStock) colors.textTertiary else colors.textPrimary),
                    maxLines = 1,
                )
            }
        }
        val oldPriceText: @Composable () -> Unit = {
            val before = product.priceBefore
            if (product.hasDiscount && before != null) {
                val struck = remember(before, talqyn.price) {
                    buildAnnotatedString {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(talqyn.price.format(before)) }
                    }
                }
                BasicText(
                    text = struck,
                    style = (if (isRow) fonts.footnote else fonts.caption).copy(color = colors.textTertiary),
                    maxLines = 1,
                )
            }
        }
        // The old price sits next to the price, and under it in a narrow tile once the type is
        // large enough that the two no longer fit — a price must never be cut short.
        if (!isRow && LocalDensity.current.fontScale >= 1.3f) {
            Column {
                priceText()
                oldPriceText()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                priceText()
                oldPriceText()
            }
        }
        if (style == TalqynCardStyle.Tile) Spacer(Modifier.weight(1f, fill = false))
        if (isOutOfStock) {
            BasicText(talqyn.strings.outOfStock, style = fonts.captionBold.copy(color = colors.textSecondary))
        }
    }
}

/** The score, the stars, and the review count — or "no reviews". */
@Composable
internal fun TalqynRatingRow(rating: Double?, reviews: Int) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val hasRating = (rating ?: 0.0) > 0 && reviews > 0
    // The score is written the way the copy's language writes a number: `4,8` on a Russian
    // screen, whatever the phone is set to.
    val formatter = remember(talqyn.strings.locale) {
        NumberFormat.getNumberInstance(talqyn.strings.locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 18.dp)) {
        if (hasRating) {
            BasicText(formatter.format(rating ?: 0.0), style = talqyn.fonts.captionBold.copy(color = colors.textSecondary))
            Spacer(Modifier.width(4.dp))
            val filled = (rating ?: 0.0).roundToInt()
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                repeat(5) { index ->
                    TalqynIconImage(
                        icon = if (index < filled) talqyn.icons.ratingStarFilled else talqyn.icons.ratingStar,
                        tint = colors.rating,
                        size = 11.dp,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        BasicText(
            text = if (hasRating) "($reviews)" else talqyn.strings.noReviews,
            style = talqyn.fonts.caption.copy(color = colors.textTertiary),
            maxLines = 1,
        )
    }
}

/**
 * Cards cited by one sentence. Two products — a comparison, most of the time — sit side by side
 * as tiles, so the paragraph is not split by a column of rows; one, or three and more, stack as rows.
 */
@Composable
internal fun TalqynCitedCards(products: List<TalqynProduct>, onProduct: (TalqynProduct) -> Unit) {
    if (products.size == 2) {
        val provider = LocalTalqynCardProvider.current
        val appCards = remember(provider, products) { products.map { provider?.card(it, TalqynProductCardLayout.Vertical) } }
        TalqynCardPair(isAppCard = appCards.map { it != null }) {
            products.forEachIndexed { index, product ->
                TalqynResolvedProductCard(product, TalqynCardStyle.Tile, appCards[index], { onProduct(product) })
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            products.forEach { product ->
                TalqynProductCard(product, TalqynCardStyle.Row, { onProduct(product) }, Modifier.fillMaxWidth())
            }
        }
    }
}

private val PAIR_SPACING = 8.dp

/**
 * Two cards side by side, half the row each, laid out without asking an app's card for its
 * intrinsic size.
 *
 * An app's card may be built of anything, and a `SubcomposeLayout` inside it — an async image,
 * a pager — throws when asked: `Modifier.height(IntrinsicSize.Min)` over the pair would bring the
 * transcript down with it. So an app's card stands at its own height, at the top of the row. The
 * SDK's tiles do answer, and are stretched to the height of the row: a pair of them comes out
 * as tall as each other, the way the design has it.
 *
 * @param isAppCard Which of the cards the app draws, in order.
 */
@Composable
private fun TalqynCardPair(isAppCard: List<Boolean>, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val gap = PAIR_SPACING.roundToPx()
        val rowWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val width = ((rowWidth - gap) / 2).coerceAtLeast(0)
        val measured = arrayOfNulls<Placeable>(measurables.size)
        var height = 0
        measurables.forEachIndexed { index, measurable ->
            val cardHeight = if (isAppCard.getOrElse(index) { false }) {
                val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight))
                measured[index] = placeable
                placeable.height
            } else {
                measurable.minIntrinsicHeight(width)
            }
            height = maxOf(height, cardHeight)
        }
        height = constraints.constrainHeight(height)
        val placeables = measurables.mapIndexed { index, measurable ->
            measured[index] ?: measurable.measure(Constraints.fixed(width, height))
        }
        layout(rowWidth, height) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(index * (width + gap), 0) }
        }
    }
}

/**
 * Titled carousels: the products a turn found beyond the ones cited inline, one carousel per
 * group of a multi-step plan. A carousel starts at the transcript's margin, so its first card
 * lines up with the text above it.
 */
@Composable
internal fun TalqynProductSections(sections: List<TalqynProductSection>, onProduct: (TalqynProduct) -> Unit) {
    val talqyn = LocalTalqyn.current
    val margin = talqyn.metrics.horizontalMargin
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(
                    text = section.title,
                    style = talqyn.fonts.captionBold.copy(color = talqyn.colors.textSecondary),
                    modifier = Modifier.padding(horizontal = margin),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = margin),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    items(section.products, key = { it.talqynId }) { product ->
                        TalqynProductCard(product, TalqynCardStyle.Compact, { onProduct(product) })
                    }
                }
            }
        }
    }
}
