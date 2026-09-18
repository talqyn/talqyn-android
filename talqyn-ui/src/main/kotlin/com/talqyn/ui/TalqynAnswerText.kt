package com.talqyn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.talqyn.consultant.TalqynAnswerLine
import com.talqyn.consultant.TalqynTextRun

/**
 * A rendered answer paragraph in the theme's type: headings, bullets and numbered items with a
 * hanging indent, bold runs, and product names that open the product.
 */
@Composable
internal fun TalqynAnswerParagraph(lines: List<TalqynAnswerLine>, onProduct: (Long) -> Unit, modifier: Modifier = Modifier) {
    val fonts = LocalTalqyn.current.fonts
    Column(modifier) {
        lines.forEach { line ->
            when (val kind = line.kind) {
                TalqynAnswerLine.Kind.Heading ->
                    AnswerLine(line.runs, fonts.headline, fonts.headline, onProduct, Modifier.padding(top = 2.dp))
                TalqynAnswerLine.Kind.Plain -> AnswerLine(line.runs, fonts.body, fonts.bodyBold, onProduct)
                TalqynAnswerLine.Kind.Bullet -> ListLine("•", line.runs, onProduct)
                is TalqynAnswerLine.Kind.Numbered -> ListLine("${kind.number}.", line.runs, onProduct)
            }
        }
    }
}

/** The marker stands in its own column, so a wrapped line of the item starts under the item's text, not under the marker. */
@Composable
private fun ListLine(marker: String, runs: List<TalqynTextRun>, onProduct: (Long) -> Unit) {
    val talqyn = LocalTalqyn.current
    Row {
        BasicText(
            text = marker,
            style = talqyn.fonts.body.answerLineHeight().copy(color = talqyn.colors.textPrimary),
            modifier = Modifier.widthIn(min = 16.dp).padding(end = 4.dp),
        )
        AnswerLine(runs, talqyn.fonts.body, talqyn.fonts.bodyBold, onProduct, Modifier.weight(1f))
    }
}

@Composable
private fun AnswerLine(
    runs: List<TalqynTextRun>,
    style: TextStyle,
    bold: TextStyle,
    onProduct: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTalqyn.current.colors
    val currentOnProduct by rememberUpdatedState(onProduct)
    // Built once per line rather than on every recomposition: the text compares the string with
    // the one it laid out, and a new string — a new link listener in it above all — would lay out
    // every line again on every frame of a streaming answer, the lines that did not change included.
    val text = remember(runs, bold, colors.accent) { annotated(runs, bold, colors.accent) { id -> currentOnProduct(id) } }
    BasicText(
        text = text,
        style = style.answerLineHeight().copy(color = colors.textPrimary),
        modifier = modifier,
    )
}

/**
 * Bold runs and product names are set in the bold face: a name stands where the consultant put a
 * marker, and the weight tells it apart from the prose around it. A name is also a link to its
 * product, drawn in the accent — the card may be a paragraph away, the name is right under the
 * finger.
 */
private fun annotated(runs: List<TalqynTextRun>, bold: TextStyle, accent: Color, onProduct: (Long) -> Unit): AnnotatedString =
    buildAnnotatedString {
        val boldSpan = SpanStyle(fontFamily = bold.fontFamily, fontWeight = bold.fontWeight, fontSynthesis = bold.fontSynthesis)
        for (run in runs) {
            val id = run.productId
            when {
                id != null -> withLink(
                    LinkAnnotation.Clickable(
                        tag = "talqyn-product:$id",
                        styles = TextLinkStyles(style = SpanStyle(color = accent)),
                        linkInteractionListener = { onProduct(id) },
                    ),
                ) {
                    withStyle(boldSpan) { append(run.text) }
                }
                run.isBold -> withStyle(boldSpan) { append(run.text) }
                else -> append(run.text)
            }
        }
    }

/** A little air between the lines of an answer: it is read, not scanned. */
private fun TextStyle.answerLineHeight(): TextStyle =
    if (fontSize.isSpecified) copy(lineHeight = (fontSize.value * 1.4f).sp) else this
