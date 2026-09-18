package com.talqyn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Who built the consultant. Not copy: the mark reads the same in every locale and in every
 * storefront, and an app that does not want it turns it off rather than rewrites it.
 */
internal const val TALQYN_POWERED_BY = "Powered by Talqyn"

/**
 * The screen before the first question: a title, a line about what the consultant does, and
 * example questions to tap. It sits a third of the way down, and scrolls when the type is too
 * large for the screen.
 */
@Composable
internal fun TalqynEmptyState(showsPoweredBy: Boolean, onExample: (String) -> Unit, modifier: Modifier = Modifier) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val margin = talqyn.metrics.horizontalMargin
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val viewport = maxHeight
        var contentHeight by remember { mutableStateOf(0.dp) }
        val topGap = ((viewport - contentHeight) / 3).coerceAtLeast(0.dp)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(topGap))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { contentHeight = with(density) { it.height.toDp() } },
            ) {
                if (talqyn.icons.emptyState != null) {
                    TalqynIconImage(talqyn.icons.emptyState, colors.accent, 40.dp)
                    Spacer(Modifier.height(16.dp))
                }
                BasicText(
                    text = talqyn.strings.title,
                    style = talqyn.fonts.title.copy(color = colors.textPrimary, textAlign = TextAlign.Center),
                    modifier = Modifier
                        .padding(horizontal = margin)
                        .semantics { heading() },
                )
                Spacer(Modifier.height(16.dp))
                BasicText(
                    text = talqyn.strings.introSubtitle,
                    style = talqyn.fonts.callout.copy(color = colors.textSecondary, textAlign = TextAlign.Center),
                    modifier = Modifier.padding(horizontal = margin * 2),
                )
                // An app with no examples of its own gets no gap where they were.
                if (talqyn.strings.exampleQuestions.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    TalqynChipsFlow(Modifier.padding(horizontal = margin), centersRows = true) {
                        talqyn.strings.exampleQuestions.forEach { example ->
                            TalqynChip(example, onClick = { onExample(example) })
                        }
                    }
                }
                // The mark is shown once, where the screen introduces itself, and nowhere else: the
                // shopper reads it before the first question and is not reminded of it under every answer.
                if (showsPoweredBy) {
                    Spacer(Modifier.height(28.dp))
                    BasicText(TALQYN_POWERED_BY, style = talqyn.fonts.micro.copy(color = colors.textTertiary, textAlign = TextAlign.Center))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * The screen's own header: the way out of the consultant at the start, the title, then history
 * and a new chat. History stands at the start when there is no way out to put there.
 */
@Composable
internal fun TalqynHeader(
    navigation: TalqynNavigation?,
    showsNewChat: Boolean,
    actionsEnabled: Boolean,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
) {
    val talqyn = LocalTalqyn.current
    val colors = talqyn.colors
    val icons = talqyn.icons
    val strings = talqyn.strings
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .statusBarsPadding()
            // A cutout or navigation buttons at the side of a landscape screen.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 6.dp),
        ) {
            Row(Modifier.align(Alignment.CenterStart)) {
                when (navigation) {
                    is TalqynNavigation.Back -> HeaderButton(icons.back, colors.textPrimary, strings.back, enabled = true, onClick = navigation.onClick)
                    is TalqynNavigation.Close -> HeaderButton(icons.close, colors.textPrimary, strings.close, enabled = true, iconSize = 20.dp, onClick = navigation.onClick)
                    null -> HeaderButton(icons.history, colors.textPrimary, strings.historyTitle, enabled = actionsEnabled, onClick = onHistory)
                }
            }
            BasicText(
                text = strings.title,
                style = talqyn.fonts.headline.copy(color = colors.textPrimary, textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 96.dp)
                    .semantics { heading() },
            )
            Row(Modifier.align(Alignment.CenterEnd)) {
                if (navigation != null) {
                    HeaderButton(icons.history, colors.textPrimary, strings.historyTitle, enabled = actionsEnabled, onClick = onHistory)
                }
                if (showsNewChat) {
                    HeaderButton(icons.newChat, colors.accent, strings.newChat, enabled = actionsEnabled, onClick = onNewChat)
                }
            }
        }
        TalqynDivider()
    }
}

@Composable
private fun HeaderButton(
    icon: TalqynIcon?,
    tint: Color,
    label: String,
    enabled: Boolean,
    iconSize: Dp = 22.dp,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .talqynPressable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        TalqynIconImage(icon, tint, iconSize)
    }
}
