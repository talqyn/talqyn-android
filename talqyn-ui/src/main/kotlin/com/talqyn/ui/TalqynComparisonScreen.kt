package com.talqyn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import kotlinx.coroutines.launch
import com.talqyn.consultant.TalqynPriceFormatter
import com.talqyn.consultant.TalqynUiStrings
import com.talqyn.sdk.TalqynComparisonTable
import com.talqyn.sdk.TalqynProduct
import kotlin.math.abs

/**
 * A comparison table: product headers across the top, characteristics down the side, values in
 * between. The characteristic column stays put while the values scroll sideways, and "only
 * differences" hides the rows where every product says the same thing.
 *
 * The consultant screen opens this itself when the shopper taps "open comparison". It is public
 * for a storefront that keeps its own header (`showsHeader = false`) and opens the table from
 * its own navigation.
 *
 * @param table The table the consultant proposed.
 * @param products The turn's products by Talqyn id, for the headers.
 * @param onOpenProduct A header was tapped. [onClose] is called first.
 * @param onClose The shopper closed the table — the cross, or the system back.
 * @param theme Colors, type, icons, and shapes.
 * @param strings Copy. Defaults to Russian; the consultant screen passes its own.
 * @param priceFormatter How prices are written.
 * @param imageLoader Loads product images.
 */
@Composable
public fun TalqynComparisonScreen(
    table: TalqynComparisonTable,
    products: Map<Long, TalqynProduct>,
    onOpenProduct: (TalqynProduct) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    theme: TalqynTheme = TalqynTheme.Default,
    strings: TalqynUiStrings = TalqynUiStrings.En,
    priceFormatter: TalqynPriceFormatter = TalqynPriceFormatter.Tenge,
    imageLoader: TalqynImageLoader = TalqynUrlImageLoader.Shared,
) {
    ProvideTalqyn(theme, strings, priceFormatter, imageLoader) {
        val colors = LocalTalqyn.current.colors
        TalqynSystemBars(top = colors.surface, bottom = colors.surface)
        TalqynComparisonContent(table, products, onOpenProduct, onClose, modifier)
    }
}

/** A column of the comparison: the product, when the turn has it, and the header's title. */
@Immutable
internal class TalqynComparisonColumn(val product: TalqynProduct?, val title: String)

/** A row of the comparison, with what it is known to hold. */
@Immutable
internal class TalqynComparisonRow(val row: TalqynComparisonTable.Row, val isPrice: Boolean)

/** The table as the screen lays it out. */
@Immutable
internal class TalqynComparisonModel(
    val columns: List<TalqynComparisonColumn>,
    val rows: List<TalqynComparisonRow>,
) {
    val hasDifferences: Boolean get() = rows.any { differs(it.row) }

    fun keepingOnlyDifferences(): TalqynComparisonModel =
        TalqynComparisonModel(columns, rows.filter { differs(it.row) })

    private fun differs(row: TalqynComparisonTable.Row): Boolean =
        columns.indices.map { index -> row.values.getOrNull(index).orEmpty() }.toSet().size > 1

    companion object {
        fun make(table: TalqynComparisonTable, products: Map<Long, TalqynProduct>): TalqynComparisonModel {
            val columns = table.titles.mapIndexed { index, title ->
                val product = table.talqynIds.getOrNull(index)?.let { products[it] }
                TalqynComparisonColumn(product, product?.title ?: title)
            }
            return TalqynComparisonModel(columns, table.rows.map { TalqynComparisonRow(it, isPriceRow(it, columns)) })
        }

        /**
         * Whether a row holds the products' prices — to write them as prices rather than as the
         * bare numbers the table carries.
         *
         * Recognized by its values, not its label: the label is the server's wording in one
         * language, and a row whose every number is the price of its column's product is a price
         * row in any language. A column whose product or price is unknown neither confirms nor
         * refutes it.
         */
        fun isPriceRow(row: TalqynComparisonTable.Row, columns: List<TalqynComparisonColumn>): Boolean {
            var matched = 0
            for ((index, column) in columns.withIndex()) {
                val raw = row.values.getOrNull(index) ?: continue
                val value = raw.trim().toDoubleOrNull() ?: return false
                val price = column.product?.price ?: continue
                if (abs(value - price) >= 0.01) return false
                matched += 1
            }
            return matched > 0
        }
    }
}

private val LabelColumnWidth: Dp = 116.dp
private val MinColumnWidth: Dp = 130.dp
private val MinRowHeight: Dp = 40.dp

/** The comparison inside a screen that already provides the theme. */
@Composable
internal fun TalqynComparisonContent(
    table: TalqynComparisonTable,
    products: Map<Long, TalqynProduct>,
    onOpenProduct: (TalqynProduct) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val model = remember(table, products) { TalqynComparisonModel.make(table, products) }
    var onlyDifferences by rememberSaveable { mutableStateOf(false) }
    val shown = remember(model, onlyDifferences) { if (onlyDifferences) model.keepingOnlyDifferences() else model }
    // The product headers and the value columns scroll as one: every row shares this state.
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onClose)

    Column(
        modifier
            .fillMaxSize()
            .background(colors.surface)
            // The table covers the consultant: a touch on a blank part of it must not reach the transcript beneath.
            .talqynBlocksTouches()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .statusBarsPadding()
                .height(56.dp),
        ) {
            if (model.hasDifferences) {
                BasicText(
                    text = talqyn.strings.comparisonOnlyDifferences,
                    style = talqyn.fonts.label.copy(color = if (onlyDifferences) colors.accent else colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = talqyn.metrics.horizontalMargin)
                        .widthIn(max = 120.dp)
                        .semantics { selected = onlyDifferences }
                        .talqynPressable {
                            onlyDifferences = !onlyDifferences
                            scope.launch { vertical.scrollTo(0) }
                        },
                )
            }
            BasicText(
                text = talqyn.strings.comparisonTitle,
                style = talqyn.fonts.headline.copy(color = colors.textPrimary, textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).widthIn(max = 160.dp),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .size(44.dp)
                    .semantics { contentDescription = talqyn.strings.close }
                    .talqynPressable(onClick = onClose),
            ) {
                TalqynIconImage(talqyn.icons.close, tint = colors.textPrimary, size = 16.dp)
            }
        }
        TalqynDivider()

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // The columns share the width, so a rotation or a resized window lays them out again.
            val columnCount = model.columns.size
            val available = maxWidth - LabelColumnWidth
            val columnWidth = if (columnCount > 0 && available > 0.dp) max(MinColumnWidth, available / columnCount) else MinColumnWidth

            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(LabelColumnWidth))
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(horizontal)
                            .height(IntrinsicSize.Min),
                    ) {
                        model.columns.forEach { column ->
                            ComparisonProductHeader(column, columnWidth, onOpen = { product ->
                                onClose()
                                onOpenProduct(product)
                            })
                        }
                    }
                }
                TalqynDivider()

                if (shown.rows.isEmpty()) {
                    BasicText(
                        text = talqyn.strings.comparisonNoDifferences,
                        style = talqyn.fonts.callout.copy(color = colors.textSecondary, textAlign = TextAlign.Center),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp, start = talqyn.metrics.horizontalMargin * 2, end = talqyn.metrics.horizontalMargin * 2),
                    )
                } else if (columnCount >= 2) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(vertical)
                            .navigationBarsPadding(),
                    ) {
                        shown.rows.forEachIndexed { index, row ->
                            ComparisonTableRow(
                                row = row,
                                columns = columnCount,
                                columnWidth = columnWidth,
                                background = if (index % 2 == 0) colors.surface else colors.surfaceSecondary,
                                horizontal = horizontal,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonProductHeader(column: TalqynComparisonColumn, width: Dp, onOpen: (TalqynProduct) -> Unit) {
    val talqyn = LocalTalqyn.current
    val product = column.product
    val tappable = if (product != null) Modifier.talqynPressable { onOpen(product) } else Modifier
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .semantics(mergeDescendants = true) { contentDescription = column.title }
            .then(tappable)
            .padding(12.dp),
    ) {
        TalqynRemoteImage(
            url = product?.imageUrl,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(talqyn.metrics.cardRadius)),
        )
        BasicText(
            text = column.title,
            style = talqyn.fonts.captionBold.copy(color = talqyn.colors.textPrimary),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One characteristic: a pinned label and the scrolling values, as tall as the tallest cell. */
@Composable
private fun ComparisonTableRow(
    row: TalqynComparisonRow,
    columns: Int,
    columnWidth: Dp,
    background: Color,
    horizontal: ScrollState,
) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val border = colors.border
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        ComparisonCell(
            text = capitalized(row.row.label),
            style = talqyn.fonts.captionBold.copy(color = colors.textSecondary),
            background = background,
            modifier = Modifier
                .width(LabelColumnWidth)
                .drawBehind {
                    val x = size.width - 0.5.dp.toPx() / 2
                    drawLine(border, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.5.dp.toPx())
                },
            start = talqyn.metrics.horizontalMargin,
            end = 8.dp,
        )
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(horizontal),
        ) {
            repeat(columns) { column ->
                ComparisonCell(
                    text = cellText(row, column, talqyn.price),
                    style = (if (row.isPrice) talqyn.fonts.captionBold else talqyn.fonts.caption).copy(color = colors.textPrimary),
                    background = background,
                    modifier = Modifier.width(columnWidth),
                    start = 12.dp,
                    end = 12.dp,
                )
            }
        }
    }
}

@Composable
private fun ComparisonCell(text: String, style: TextStyle, background: Color, modifier: Modifier, start: Dp, end: Dp) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = MinRowHeight)
            .background(background)
            .padding(start = start, end = end, top = 10.dp, bottom = 10.dp),
    ) {
        BasicText(text, style = style)
    }
}

private fun cellText(row: TalqynComparisonRow, column: Int, price: TalqynPriceFormatter): String {
    val value = row.row.values.getOrNull(column)
    if (value.isNullOrEmpty()) return "—"
    if (!row.isPrice) return value
    val number = value.trim().toDoubleOrNull() ?: return value
    return price.format(number)
}

private fun capitalized(label: String): String = label.take(1).uppercase() + label.drop(1)
