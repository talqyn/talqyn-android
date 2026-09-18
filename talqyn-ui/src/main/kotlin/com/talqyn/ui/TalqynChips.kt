package com.talqyn.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A chip: suggestions, clarify options, examples, feedback reasons.
 *
 * A pill at the theme's chip height — or squared off by `chipRadius` — that wraps a long
 * question onto up to [maxLines] lines instead of running off the screen.
 */
@Composable
internal fun TalqynChip(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    maxLines: Int = 3,
) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val metrics = talqyn.metrics
    val haptics = rememberTalqynHaptics()
    // The pill's radius follows the design height, so a chip that wraps onto a second line
    // stays a rounded rectangle rather than turning into a blob.
    val shape = RoundedCornerShape(metrics.chipRadius ?: (metrics.chipHeight / 2))
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .talqynPressable {
                haptics()
                onClick()
            }
            .semantics { selected = isSelected }
            .heightIn(min = metrics.chipHeight)
            .background(if (isSelected) colors.accent else colors.surface, shape)
            .border(1.dp, if (isSelected) colors.accent else colors.border, shape)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        BasicText(
            text = title,
            style = talqyn.fonts.footnote.copy(color = if (isSelected) colors.onAccent else colors.textPrimary),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Chips laid out start to end, wrapping onto new rows, optionally centered. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TalqynChipsFlow(
    modifier: Modifier = Modifier,
    centersRows: Boolean = false,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (centersRows) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}
