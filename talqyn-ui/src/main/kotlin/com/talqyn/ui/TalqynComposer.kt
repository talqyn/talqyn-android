package com.talqyn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.talqyn.consultant.TalqynConversationLimits
import kotlin.math.min

/** The gap above the pill, inside the composer's own bounds. The transcript counts on it when it measures its distance to the pill. */
internal val TALQYN_COMPOSER_TOP_INSET = 8.dp

/** What stands between the last row and the pill of the composer over it once the transcript is at its end. */
internal val TALQYN_COMPOSER_GAP = 32.dp

/** How much of the palette's shadow the pill takes: it floats a little lighter than a card. */
private const val PILL_SHADOW_STRENGTH = 0.67f

/**
 * The input pill: a growing text field, a counter near the limit, and a button that sends — or
 * stops the answer while one is streaming. Under the pill, a line that the consultant can be wrong.
 *
 * @param draft The draft this composition shows.
 * @param currentDraft The draft as the conversation holds it right now.
 */
@Composable
internal fun TalqynComposer(
    draft: String,
    currentDraft: () -> String,
    isStreaming: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val fonts = talqyn.fonts
    val strings = talqyn.strings
    val haptics = rememberTalqynHaptics()
    val limit = TalqynConversationLimits.MAX_INPUT_LENGTH

    var field by remember { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
    // The conversation owns the draft: a prefilled question, an edited one, a cleared field after
    // sending all arrive from there. A draft that is no longer the conversation's is an echo of the
    // shopper's own typing still on its way to the screen: applied, it would take back the letters
    // typed since and throw the cursor to the end.
    LaunchedEffect(draft) {
        if (draft != field.text && draft == currentDraft()) field = TextFieldValue(draft, TextRange(draft.length))
    }
    val isBlank = field.text.isBlank()
    val count = field.text.length
    val pillShape = RoundedCornerShape(talqyn.metrics.composerRadius)

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.padding(
            start = talqyn.metrics.horizontalMargin,
            end = talqyn.metrics.horizontalMargin,
            top = TALQYN_COMPOSER_TOP_INSET,
            bottom = 8.dp,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .talqynShadow(pillShape, colors.shadow, radius = 12.dp, offsetY = 4.dp, strength = PILL_SHADOW_STRENGTH)
                .background(colors.surface, pillShape)
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            BasicTextField(
                value = field,
                onValueChange = { next ->
                    val text = next.text.take(limit)
                    field = if (text.length == next.text.length) {
                        next
                    } else {
                        next.copy(text = text, selection = TextRange(min(next.selection.start, text.length), min(next.selection.end, text.length)))
                    }
                    if (text != currentDraft()) onDraftChange(text)
                },
                textStyle = fonts.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                minLines = 1,
                // Four lines before the text starts to scroll.
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                    ) {
                        if (field.text.isEmpty()) {
                            BasicText(strings.placeholder, style = fonts.body.copy(color = colors.textTertiary), maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            if (count >= limit - COUNTER_THRESHOLD) {
                BasicText(
                    text = "$count/$limit",
                    style = fonts.micro.copy(color = if (count >= limit) colors.error else colors.textTertiary),
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .talqynPressable(enabled = isStreaming || !isBlank) {
                        haptics()
                        if (isStreaming) onStop() else onSend(field.text)
                    }
                    .semantics { contentDescription = if (isStreaming) strings.stop else strings.send },
            ) {
                TalqynIconImage(
                    icon = if (isStreaming) talqyn.icons.stop else talqyn.icons.send,
                    tint = when {
                        isStreaming -> colors.error
                        isBlank -> colors.textTertiary
                        else -> colors.accent
                    },
                    size = 30.dp,
                )
            }
        }
        // The answers are a model's: say so where every answer is read from, in the shopper's
        // language, small and out of the way. An app that says it elsewhere blanks the string.
        if (strings.disclaimer.isNotEmpty()) {
            BasicText(
                text = strings.fill(strings.disclaimer, strings.title),
                style = fonts.micro.copy(color = colors.textTertiary, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val COUNTER_THRESHOLD = 100

/**
 * The bar the composer floats on.
 *
 * The transcript runs the whole height of the screen and passes under the composer, so what is
 * behind the pill fades the last rows out rather than cutting them off with a slab: the top edge
 * blends in over 24 dp, so the bar meets the transcript without a seam across it.
 */
@Composable
internal fun TalqynGlassBar(modifier: Modifier = Modifier) {
    val background = LocalTalqyn.current.colors.background
    val fade = with(LocalDensity.current) { 24.dp.toPx() }
    Box(
        modifier.drawBehind {
            val stop = if (size.height > 0) (fade / size.height).coerceIn(0f, 1f) else 1f
            drawRect(
                Brush.verticalGradient(
                    0f to background.copy(alpha = 0f),
                    stop to background.copy(alpha = 0.97f),
                    1f to background.copy(alpha = 0.97f),
                ),
            )
        },
    )
}
