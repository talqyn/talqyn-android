package com.talqyn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.talqyn.consultant.TalqynChatHistory
import com.talqyn.consultant.TalqynPriceFormatter
import com.talqyn.consultant.TalqynUiStrings
import com.talqyn.sdk.Talqyn
import com.talqyn.sdk.TalqynChatSummary
import com.talqyn.sdk.TalqynException

/**
 * The shopper's conversations, newest first: tap to reopen, swipe to delete, pull to refresh,
 * more pages as the list is scrolled.
 *
 * The consultant screen opens this itself from its header. It is public for a storefront that
 * keeps its own header (`showsHeader = false`) and opens history from its own navigation: hand
 * [onSelect] to `TalqynConversation.restore` and [onDelete] to `TalqynConversation.discardIfOpen`.
 *
 * History is a screen of its own, with its own header and close button, rather than a sheet
 * slid over the consultant: a sheet's drag to dismiss would fight the list's scroll.
 *
 * @param talqyn The client to load through.
 * @param onSelect A conversation was chosen. [onClose] is called first.
 * @param onDelete A conversation was deleted.
 * @param onClose The shopper closed the list — the cross, or the system back.
 * @param theme Colors, type, icons, and shapes.
 * @param strings Copy. `null` follows the client's locale, as the consultant screen does.
 */
@Composable
public fun TalqynChatHistoryScreen(
    talqyn: Talqyn,
    onSelect: (sessionId: String) -> Unit,
    onDelete: (sessionId: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    theme: TalqynTheme = TalqynTheme.Default,
    strings: TalqynUiStrings? = null,
) {
    val copy = strings ?: remember(talqyn) { TalqynUiStrings.forLocale(talqyn.currentLocale) }
    ProvideTalqyn(theme, copy, TalqynPriceFormatter.Tenge, TalqynUrlImageLoader.Shared) {
        val colors = LocalTalqyn.current.colors
        TalqynSystemBars(top = colors.surface, bottom = colors.surface)
        TalqynChatHistoryContent(talqyn, onSelect, onDelete, onClose, modifier)
    }
}

/** The history list inside a screen that already provides the theme. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TalqynChatHistoryContent(
    talqyn: Talqyn,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val environment = LocalTalqyn.current
    val colors = environment.colors
    val strings = environment.strings
    val scope = rememberCoroutineScope()
    // Tied to this composition: leaving the screen cancels whatever page is still loading.
    val history = remember(talqyn, scope) { TalqynChatHistory(talqyn, scope = scope) }
    val state by history.state.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TalqynChatSummary?>(null) }

    LaunchedEffect(history) { history.load() }
    // The pull indicator stays until the reload it started is over. Read from the flow itself:
    // `load()` updates it at once, before the collected state catches up.
    LaunchedEffect(isRefreshing, state) {
        if (isRefreshing && !history.state.value.isLoading) isRefreshing = false
    }

    BackHandler(onBack = onClose)

    Column(
        modifier
            .fillMaxSize()
            .background(colors.surface)
            // History covers the consultant: a touch on a blank part of it must not reach the transcript beneath.
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
            BasicText(
                text = strings.historyTitle,
                style = environment.fonts.headline.copy(color = colors.textPrimary, textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).widthIn(max = 220.dp),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .size(44.dp)
                    .semantics { contentDescription = strings.close }
                    .talqynPressable(onClick = onClose),
            ) {
                TalqynIconImage(environment.icons.close, tint = colors.textPrimary, size = 16.dp)
            }
        }
        TalqynDivider()

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                history.load()
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            val loaded = state as? TalqynChatHistory.State.Loaded
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
            ) {
                if (loaded != null) {
                    itemsIndexed(loaded.chats, key = { _, chat -> chat.sessionId }) { index, chat ->
                        LaunchedEffect(chat.sessionId) { history.loadMoreIfNeeded(chat) }
                        HistoryRow(
                            chat = chat,
                            onSelect = {
                                onClose()
                                onSelect(chat.sessionId)
                            },
                            onRequestDelete = { pendingDelete = chat },
                        )
                        if (index < loaded.chats.lastIndex) {
                            TalqynDivider(Modifier.padding(horizontal = environment.metrics.horizontalMargin))
                        }
                    }
                    if (loaded.isLoadingMore) {
                        item(key = "talqyn.history.more") {
                            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), color = colors.textSecondary, strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }

            when (val current = state) {
                TalqynChatHistory.State.Loading -> if (!isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(24.dp),
                        color = colors.textSecondary,
                        strokeWidth = 2.dp,
                    )
                }
                TalqynChatHistory.State.Empty -> HistoryPlaceholder(strings.historyEmpty, onRetry = null, Modifier.align(Alignment.Center))
                is TalqynChatHistory.State.Failed -> HistoryPlaceholder(
                    text = if (current.error is TalqynException.Forbidden) strings.historyUnavailable else strings.historyError,
                    onRetry = { history.load() },
                    modifier = Modifier.align(Alignment.Center),
                )
                is TalqynChatHistory.State.Loaded -> Unit
            }
        }
    }

    pendingDelete?.let { chat ->
        TalqynAlert(
            title = strings.historyDeleteTitle,
            message = strings.historyDeleteConfirm,
            confirm = strings.historyDelete,
            dismiss = strings.cancel,
            onConfirm = {
                pendingDelete = null
                history.delete(chat.sessionId)
                onDelete(chat.sessionId)
            },
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }
}

/** One conversation: its title — or "untitled" — and when it was last written to. Swiped to the start, it asks to be deleted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRow(chat: TalqynChatSummary, onSelect: () -> Unit, onRequestDelete: () -> Unit) {
    val environment = LocalTalqyn.current
    val colors = environment.colors
    val strings = environment.strings
    val title = chat.title?.trim().orEmpty().ifEmpty { strings.historyUntitled }
    val subtitle = remember(chat.lastMessageAt, strings) { TalqynChatHistory.subtitle(chat.lastMessageAt, strings) }
    val dismissState = rememberSwipeToDismissBoxState()

    // A swipe only asks: the row springs back, and the dialog decides. A confirmed deletion
    // removes the row from the list itself.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onRequestDelete()
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.error)
                    .padding(horizontal = environment.metrics.horizontalMargin),
            ) {
                TalqynIconImage(environment.icons.deleteChat, tint = colors.onAccent, size = 20.dp)
                BasicText(strings.historyDelete, style = environment.fonts.label.copy(color = colors.onAccent))
            }
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .semantics {
                    // Swiping is not for everyone: the deletion is offered as an action too.
                    customActions = listOf(
                        CustomAccessibilityAction(strings.historyDelete) {
                            onRequestDelete()
                            true
                        },
                    )
                }
                .clickable(onClick = onSelect)
                .padding(horizontal = environment.metrics.horizontalMargin, vertical = 12.dp),
        ) {
            BasicText(
                text = title,
                style = environment.fonts.body.copy(color = colors.textPrimary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                BasicText(
                    text = subtitle,
                    style = environment.fonts.footnote.copy(color = colors.textSecondary),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HistoryPlaceholder(text: String, onRetry: (() -> Unit)?, modifier: Modifier) {
    val environment = LocalTalqyn.current
    val colors = environment.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.padding(horizontal = 32.dp),
    ) {
        BasicText(text, style = environment.fonts.callout.copy(color = colors.textSecondary, textAlign = TextAlign.Center))
        if (onRetry != null) {
            BasicText(
                text = environment.strings.retry,
                style = environment.fonts.label.copy(color = colors.accent),
                modifier = Modifier
                    .talqynPressable(onClick = onRetry)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Whether a page is on its way: the first one, or the next. What a pull-to-refresh waits out. */
private val TalqynChatHistory.State.isLoading: Boolean
    get() = when (this) {
        TalqynChatHistory.State.Loading -> true
        is TalqynChatHistory.State.Loaded -> isLoadingMore
        TalqynChatHistory.State.Empty, is TalqynChatHistory.State.Failed -> false
    }
