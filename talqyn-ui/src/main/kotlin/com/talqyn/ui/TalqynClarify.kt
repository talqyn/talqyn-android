package com.talqyn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.talqyn.consultant.TalqynClarifyDraft
import com.talqyn.consultant.TalqynConversationLimits
import com.talqyn.sdk.TalqynClarify
import kotlin.math.min

/**
 * The clarifying questions as chips, a free-text field, skip and submit — inline in the transcript.
 *
 * @param draft The draft this composition shows.
 * @param currentDraft The draft as the conversation holds it right now, which every change starts
 *   from: two chips tapped within one frame, or letters typed faster than the screen recomposes,
 *   would otherwise each be applied to the draft of the last composition, and the later change
 *   would take back the earlier one.
 */
@Composable
internal fun TalqynClarifyCard(
    clarify: TalqynClarify,
    draft: TalqynClarifyDraft,
    currentDraft: () -> TalqynClarifyDraft,
    isInteractive: Boolean,
    onDraftChange: (TalqynClarifyDraft) -> Unit,
    onSubmit: (String) -> Unit,
) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val answer = draft.answer(clarify.questions)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (isInteractive) 1f else 0.5f }
            .background(colors.surfaceSecondary, RoundedCornerShape(talqyn.metrics.cornerRadius))
            .padding(12.dp),
    ) {
        if (clarify.message.isNotEmpty()) {
            BasicText(clarify.message, style = talqyn.fonts.callout.copy(color = colors.textPrimary))
        }
        TalqynClarifyQuestions(clarify, draft, labelColor = colors.textPrimary, spacing = 12.dp, labelSpacing = 8.dp) { option, question ->
            if (isInteractive) onDraftChange(currentDraft().toggle(option, question))
        }
        TalqynClarifyField(
            value = draft.custom,
            currentValue = { currentDraft().custom },
            onValueChange = { onDraftChange(currentDraft().copy(custom = it)) },
            enabled = isInteractive,
            background = colors.surface,
            bordered = true,
            height = 44.dp,
            // A field inside the card rounds a little less than the card around it.
            radius = maxOf(4.dp, talqyn.metrics.cornerRadius - 2.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = talqyn.strings.clarifySkip,
                style = talqyn.fonts.label.copy(color = colors.textSecondary),
                modifier = Modifier.talqynPressable(enabled = isInteractive) { onSubmit(talqyn.strings.clarifySkipValue) },
            )
            Spacer(Modifier.weight(1f))
            TalqynPillButton(
                title = talqyn.strings.clarifySubmit,
                onClick = { currentDraft().answer(clarify.questions).takeIf { it.isNotEmpty() }?.let(onSubmit) },
                enabled = isInteractive && answer.isNotEmpty(),
            )
        }
    }
}

/**
 * The clarifying questions as a sheet: the first time a turn asks, the questions come up over the
 * transcript; dismissed, they stay as a card in it. The sheet is as tall as its questions, up to
 * the screen.
 *
 * @param currentDraft The draft as the conversation holds it right now: see [TalqynClarifyCard].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TalqynClarifySheet(
    clarify: TalqynClarify,
    draft: TalqynClarifyDraft,
    currentDraft: () -> TalqynClarifyDraft,
    onDraftChange: (TalqynClarifyDraft) -> Unit,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val answer = draft.answer(clarify.questions)
    // The sheet slides away before the answer goes: submitting is not a dismissal, and the
    // question must not reappear as an inline card.
    val finish: (String) -> Unit = { value ->
        scope.launch { sheetState.hide() }.invokeOnCompletion { onSubmit(value) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.border) },
    ) {
        // The sheet is a window of its own, composed under the system's font scale again.
        ProvideTalqynDensity {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                ) {
                    if (clarify.message.isNotEmpty()) {
                        BasicText(clarify.message, style = talqyn.fonts.title.copy(color = colors.textPrimary))
                    }
                    TalqynClarifyQuestions(clarify, draft, labelColor = colors.textSecondary, spacing = 20.dp, labelSpacing = 10.dp) { option, question ->
                        onDraftChange(currentDraft().toggle(option, question))
                    }
                    TalqynClarifyField(
                        value = draft.custom,
                        currentValue = { currentDraft().custom },
                        onValueChange = { onDraftChange(currentDraft().copy(custom = it)) },
                        enabled = true,
                        background = colors.surfaceSecondary,
                        bordered = false,
                        height = 52.dp,
                        radius = talqyn.metrics.cornerRadius,
                    )
                }
                TalqynDivider()
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                ) {
                    TalqynPillButton(
                        title = talqyn.strings.clarifySubmit,
                        onClick = { currentDraft().answer(clarify.questions).takeIf { it.isNotEmpty() }?.let(finish) },
                        modifier = Modifier.fillMaxWidth(),
                        height = 52.dp,
                        enabled = answer.isNotEmpty(),
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .talqynPressable { finish(talqyn.strings.clarifySkipValue) },
                    ) {
                        BasicText(talqyn.strings.clarifySkip, style = talqyn.fonts.label.copy(color = colors.textSecondary))
                    }
                }
            }
        }
    }
}

@Composable
private fun TalqynClarifyQuestions(
    clarify: TalqynClarify,
    draft: TalqynClarifyDraft,
    labelColor: Color,
    spacing: Dp,
    labelSpacing: Dp,
    onToggle: (String, TalqynClarify.Question) -> Unit,
) {
    val talqyn = LocalTalqyn.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        clarify.questions.forEach { question ->
            Column(verticalArrangement = Arrangement.spacedBy(labelSpacing)) {
                BasicText(question.label, style = talqyn.fonts.label.copy(color = labelColor))
                TalqynChipsFlow {
                    question.options.forEach { option ->
                        TalqynChip(
                            title = option,
                            isSelected = draft.isSelected(option, question),
                            onClick = { onToggle(option, question) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The free-text answer: one line, no longer than the contract takes.
 *
 * @param currentValue The answer as the conversation holds it right now.
 */
@Composable
private fun TalqynClarifyField(
    value: String,
    currentValue: () -> String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    background: Color,
    bordered: Boolean,
    height: Dp,
    radius: Dp,
) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val focusManager = LocalFocusManager.current
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // The conversation owns the answer: one cleared or put back elsewhere arrives here. A value
    // that is no longer the conversation's is an echo of the shopper's own typing still on its way
    // to the screen, and must not take back the letters typed since.
    LaunchedEffect(value) {
        if (field.text != value && value == currentValue()) field = TextFieldValue(value, TextRange(value.length))
    }
    val shape = RoundedCornerShape(radius)
    BasicTextField(
        value = field,
        onValueChange = { next ->
            val text = next.text.take(TalqynConversationLimits.MAX_CLARIFY_CUSTOM_LENGTH)
            field = if (text.length == next.text.length) {
                next
            } else {
                next.copy(text = text, selection = TextRange(min(next.selection.start, text.length), min(next.selection.end, text.length)))
            }
            if (text != currentValue()) onValueChange(text)
        },
        enabled = enabled,
        singleLine = true,
        textStyle = talqyn.fonts.callout.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = height)
                    .background(background, shape)
                    .then(if (bordered) Modifier.border(1.dp, colors.border, shape) else Modifier)
                    .padding(horizontal = 12.dp),
            ) {
                if (field.text.isEmpty()) {
                    BasicText(talqyn.strings.clarifyCustomPlaceholder, style = talqyn.fonts.callout.copy(color = colors.textTertiary))
                }
                inner()
            }
        },
    )
}
