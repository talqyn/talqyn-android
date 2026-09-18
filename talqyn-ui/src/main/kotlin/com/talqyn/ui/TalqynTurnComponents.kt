package com.talqyn.ui

import android.content.ClipData
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.talqyn.consultant.Composing
import com.talqyn.consultant.TalqynAnswerRating
import com.talqyn.sdk.TalqynConsultantStage
import com.talqyn.sdk.TalqynFeedbackReason

/**
 * The shopper's message, at the end of the row in a bubble with a square bottom-end corner. A
 * long press copies it, or puts it back into the composer to be asked differently.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TalqynUserBubble(text: String, onEdit: (String) -> Unit) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val metrics = talqyn.metrics
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showsMenu by remember { mutableStateOf(false) }
    val radius = metrics.bubbleRadius
    val shape = RoundedCornerShape(topStart = radius, topEnd = radius, bottomEnd = 0.dp, bottomStart = radius)

    BoxWithConstraints(
        contentAlignment = Alignment.CenterEnd,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.horizontalMargin),
    ) {
        Box(Modifier.widthIn(max = maxWidth * 0.78f)) {
            BasicText(
                text = text,
                style = talqyn.fonts.body.copy(color = colors.onBubble),
                modifier = Modifier
                    .clip(shape)
                    .background(colors.bubble)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showsMenu = true },
                        onLongClickLabel = talqyn.strings.editQuestion,
                        // The long press knocks by itself; the theme says whether it may.
                        hapticFeedbackEnabled = talqyn.theme.hapticsEnabled,
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            DropdownMenu(expanded = showsMenu, onDismissRequest = { showsMenu = false }, containerColor = colors.surface) {
                // A menu is a window of its own, composed under the system's font scale again.
                ProvideTalqynDensity {
                    DropdownMenuItem(
                        text = { BasicText(talqyn.strings.copyQuestion, style = talqyn.fonts.body.copy(color = colors.textPrimary)) },
                        leadingIcon = { TalqynIconImage(talqyn.icons.copyAnswer, colors.textPrimary, 18.dp) },
                        onClick = {
                            showsMenu = false
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(text, text))) }
                        },
                    )
                    DropdownMenuItem(
                        text = { BasicText(talqyn.strings.editQuestion, style = talqyn.fonts.body.copy(color = colors.textPrimary)) },
                        leadingIcon = { TalqynIconImage(talqyn.icons.editQuestion, colors.textPrimary, 18.dp) },
                        onClick = {
                            showsMenu = false
                            onEdit(text)
                        },
                    )
                }
            }
        }
    }
}

/** A spinner and what the consultant is doing. */
@Composable
internal fun TalqynStatusLine(stage: TalqynConsultantStage) {
    val talqyn = LocalTalqyn.current
    val text = when (stage) {
        TalqynConsultantStage.Searching -> talqyn.strings.searching
        TalqynConsultantStage.Composing -> talqyn.strings.composing
        else -> talqyn.strings.thinking
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = talqyn.colors.textSecondary, strokeWidth = 2.dp)
        BasicText(text, style = talqyn.fonts.callout.copy(color = talqyn.colors.textSecondary))
    }
}

/**
 * Three dots that pulse in turn while text is arriving. The dots are the only thing on screen
 * saying the answer is coming, so with animations removed they stay lit rather than stop existing.
 */
@Composable
internal fun TalqynTypingIndicator() {
    val colors = LocalTalqyn.current.colors
    val reduced = isReducedMotion()
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .height(14.dp)
            .clearAndSetSemantics {},
    ) {
        repeat(3) { index ->
            val alpha = transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(450),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 150),
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer { this.alpha = if (reduced) 1f else alpha.value }
                    .background(colors.textTertiary, CircleShape),
            )
        }
    }
}

/** A notice under a turn: stopped, failed, or degraded — with a retry when the turn is the latest one. */
@Composable
internal fun TalqynNotice(notice: TalqynTurnNotice, onRetry: () -> Unit) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val tint = when (notice.tone) {
        TalqynNoticeTone.Warning -> colors.warning
        TalqynNoticeTone.Error -> colors.error
        TalqynNoticeTone.Neutral -> colors.textSecondary
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSecondary, RoundedCornerShape(talqyn.metrics.cornerRadius))
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(6.dp)
                    .background(tint, CircleShape),
            )
            BasicText(notice.text, style = talqyn.fonts.footnote.copy(color = colors.textSecondary), modifier = Modifier.weight(1f))
        }
        if (notice.showsRetry) {
            BasicText(
                text = talqyn.strings.retry,
                style = talqyn.fonts.label.copy(color = colors.accent),
                modifier = Modifier.talqynPressable(onClick = onRetry),
            )
        }
    }
}

/** "This looks like a search" and a button to open the results. */
@Composable
internal fun TalqynRedirectNotice(onOpen: () -> Unit) {
    val talqyn = LocalTalqyn.current
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(talqyn.colors.surfaceSecondary, RoundedCornerShape(talqyn.metrics.cornerRadius))
            .padding(12.dp),
    ) {
        BasicText(talqyn.strings.redirectNotice, style = talqyn.fonts.footnote.copy(color = talqyn.colors.textSecondary))
        TalqynPillButton(talqyn.strings.openSearch, onClick = onOpen)
    }
}

/** What the shopper answered to a clarification. */
@Composable
internal fun TalqynClarifyAnswered(answer: String) {
    val talqyn = LocalTalqyn.current
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(talqyn.colors.surfaceSecondary, RoundedCornerShape(talqyn.metrics.cornerRadius))
            .padding(12.dp),
    ) {
        BasicText(talqyn.strings.clarifyAnsweredLabel, style = talqyn.fonts.captionBold.copy(color = talqyn.colors.textTertiary))
        BasicText(answer, style = talqyn.fonts.callout.copy(color = talqyn.colors.textPrimary))
    }
}

/**
 * Under a settled turn: was it helpful, why not, and a button that copies the answer.
 *
 * The verdict is a toggle — tapping the chosen thumb again takes it back. A thumb down opens the
 * reasons as chips: picking one is the cheapest way for a shopper to say what to fix, and each
 * pick is saved as it is made. Copying swaps the icon for a checkmark for a moment and says so to
 * TalkBack.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TalqynAnswerToolbar(toolbar: TalqynTurnToolbar, onRate: (TalqynAnswerRating?, List<TalqynFeedbackReason>) -> Unit) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val icons = talqyn.icons
    val strings = talqyn.strings
    val haptics = rememberTalqynHaptics()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    // A new rating or a new text size must not cut short the checkmark of a copy that just
    // happened; a different answer to copy does.
    LaunchedEffect(toolbar.copyText) { copied = false }

    val helpful = toolbar.rating == TalqynAnswerRating.Helpful
    val notHelpful = toolbar.rating == TalqynAnswerRating.NotHelpful

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolbarButton(
                icon = if (helpful) icons.rateHelpfulOn else icons.rateHelpful,
                tint = if (helpful) colors.accent else colors.textTertiary,
                label = strings.rateHelpful,
                isSelected = helpful,
            ) {
                haptics()
                onRate(if (helpful) null else TalqynAnswerRating.Helpful, emptyList())
            }
            Spacer(Modifier.width(2.dp))
            ToolbarButton(
                icon = if (notHelpful) icons.rateNotHelpfulOn else icons.rateNotHelpful,
                tint = if (notHelpful) colors.accent else colors.textTertiary,
                label = strings.rateNotHelpful,
                isSelected = notHelpful,
            ) {
                haptics()
                onRate(if (notHelpful) null else TalqynAnswerRating.NotHelpful, emptyList())
            }
            val copyText = toolbar.copyText
            if (copyText != null) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(width = 1.dp, height = 16.dp)
                        .background(colors.border),
                )
                Spacer(Modifier.width(6.dp))
                ToolbarButton(
                    icon = if (copied) icons.checkmark else icons.copyAnswer,
                    tint = if (copied) colors.accent else colors.textTertiary,
                    label = if (copied) strings.copied else strings.copyAnswer,
                    announcesChanges = true,
                ) {
                    haptics()
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(strings.title, copyText))) }
                    copied = true
                }
            }
        }

        val reasons = toolbar.offeredReasons.mapNotNull { reason -> strings.feedbackReasonText(reason)?.let { reason to it } }
        if (notHelpful && reasons.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(strings.feedbackReasonsTitle, style = talqyn.fonts.captionBold.copy(color = colors.textSecondary))
                TalqynChipsFlow {
                    reasons.forEach { (reason, title) ->
                        val isSelected = reason in toolbar.selectedReasons
                        TalqynChip(
                            title = title,
                            isSelected = isSelected,
                            onClick = {
                                val selected = toolbar.selectedReasons
                                onRate(TalqynAnswerRating.NotHelpful, if (isSelected) selected - reason else selected + reason)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: TalqynIcon?,
    tint: Color,
    label: String,
    isSelected: Boolean = false,
    announcesChanges: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 36.dp, height = 32.dp)
            .talqynPressable(onClick = onClick)
            .semantics {
                contentDescription = label
                selected = isSelected
                if (announcesChanges) liveRegion = LiveRegionMode.Polite
            },
    ) {
        TalqynIconImage(icon, tint, 18.dp)
    }
}

/** A proposed action: apply filters, open a comparison. */
@Composable
internal fun TalqynActionChip(icon: TalqynIcon?, title: String, summary: String, onClick: () -> Unit) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .talqynPressable(onClick = onClick)
            .background(colors.surfaceSecondary, RoundedCornerShape(talqyn.metrics.cornerRadius))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        TalqynIconImage(icon, colors.accent, 16.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = talqyn.fonts.label.copy(color = colors.accent))
            if (summary.isNotEmpty()) {
                BasicText(
                    text = summary,
                    style = talqyn.fonts.caption.copy(color = colors.textSecondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The prompts under an answer, as chips. */
@Composable
internal fun TalqynSuggestions(questions: List<String>, onTap: (String) -> Unit) {
    TalqynChipsFlow(Modifier.padding(horizontal = LocalTalqyn.current.metrics.horizontalMargin)) {
        questions.forEach { question -> TalqynChip(question, onClick = { onTap(question) }) }
    }
}
