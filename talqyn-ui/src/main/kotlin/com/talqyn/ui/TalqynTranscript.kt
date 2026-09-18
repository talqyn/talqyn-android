package com.talqyn.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max

/** A request to move the transcript, told apart from the previous one by [id]. */
@Immutable
internal data class TalqynAnchorRequest(val id: Int, val kind: TalqynTranscriptAnchor)

/**
 * Where the transcript is: the list's own state, the question pinned to the top, and the room
 * kept under the last row so the pin holds.
 */
@Stable
internal class TalqynTranscriptState(val listState: LazyListState) {
    /** The row pinned to the top of the viewport, or `null`. */
    var anchoredKey: Any? by mutableStateOf(null)

    /** Room under the last row beyond the composer's own, in pixels: what keeps a pinned question at the top while its answer is short. */
    var extraReservePx: Int by mutableIntStateOf(0)

    /** Whether the end of the transcript is in view. */
    var isPinnedToBottom: Boolean by mutableStateOf(true)

    /** Lets go of the pin and goes to the end. */
    suspend fun scrollToBottom(rowCount: Int, animated: Boolean) {
        anchoredKey = null
        extraReservePx = 0
        if (animated) listState.animateScrollToItem(rowCount) else listState.scrollToItem(rowCount)
    }
}

private const val RESERVE_KEY = "talqyn.reserve"
private val FIRST_ROW_TOP = 16.dp
private val ROW_SPACING = 20.dp
private val ANCHOR_TOP_GAP = 8.dp
private val BOTTOM_PROXIMITY = 32.dp

/**
 * Runs a scroll the transcript makes by itself.
 *
 * A drag takes the list over by cancelling whatever scroll is under way, and the
 * `CancellationException` that says so is about that scroll alone. Let through, it would end the
 * collector that keeps the pin and the room under the last row — for as long as the screen stays
 * composed — while the screen itself carries on.
 */
private suspend inline fun scrollQuietly(scroll: () -> Unit) {
    try {
        scroll()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}

/**
 * The scrolling conversation.
 *
 * A new question is pinned to the top of the viewport and stays there while the answer grows
 * underneath — the shopper reads from the start of the answer, not from its end. Room is kept
 * below the last row so the pin holds even while the answer is short. The pin lets go the moment
 * the shopper drags, and the room shrinks to what the current position still needs — never
 * less, so nothing moves under the finger.
 *
 * The list spans the screen, so it scrolls from anywhere; its content is one column, no wider
 * than the theme's `maxContentWidth` and centered in it.
 *
 * @param bottomReserve How much of the transcript's bottom the composer floating over it covers,
 *   the gap to the pill included: the last row needs this much room below it to be readable.
 */
@Composable
internal fun TalqynTranscript(
    rows: List<TalqynTranscriptRow>,
    anchor: TalqynAnchorRequest,
    bottomReserve: Dp,
    state: TalqynTranscriptState,
    actions: TalqynTurnActions,
    modifier: Modifier = Modifier,
) {
    val talqyn = LocalTalqyn.current
    val density = LocalDensity.current
    val listState = state.listState
    val currentRows by rememberUpdatedState(rows)
    val reservePx by rememberUpdatedState(with(density) { bottomReserve.roundToPx() })

    LaunchedEffect(anchor.id) {
        when (anchor.kind) {
            TalqynTranscriptAnchor.NewestTurn -> {
                val rowsNow = currentRows
                var index = rowsNow.indexOfLast { it !is TalqynTranscriptRow.Suggestions }
                if (index > 0 && rowsNow[index - 1] is TalqynTranscriptRow.User) index -= 1
                state.anchoredKey = rowsNow.getOrNull(index)?.key
            }
            TalqynTranscriptAnchor.Bottom -> state.scrollToBottom(currentRows.size, animated = false)
            TalqynTranscriptAnchor.Keep -> Unit
        }
    }

    // The pin lets go the moment the shopper drags.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) state.anchoredKey = null
        }
    }

    // Keeps the pin, the room under the last row, and the "at the end" flag in step with every
    // layout of the list.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo to state.anchoredKey }.collect { (info, anchored) ->
            val items = info.visibleItemsInfo
            val viewportEnd = info.viewportEndOffset
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            val gapPx = with(density) { ANCHOR_TOP_GAP.roundToPx() }
            val lastRowIndex = info.totalItemsCount - 2
            val lastRow = items.firstOrNull { it.index == lastRowIndex }

            if (anchored != null) {
                val anchorItem = items.firstOrNull { it.key == anchored }
                val rowIndex = currentRows.indexOfFirst { it.key == anchored }
                if (rowIndex < 0) {
                    state.anchoredKey = null
                } else {
                    val topPadding = with(density) { (if (rowIndex == 0) FIRST_ROW_TOP else ROW_SPACING).roundToPx() }
                    if (anchorItem != null) {
                        // The room under the tail is what the viewport has left once the pinned
                        // question and everything after it are laid out.
                        val extra = if (lastRow == null) {
                            0
                        } else {
                            val tail = lastRow.offset + lastRow.size - (anchorItem.offset + topPadding) + gapPx
                            max(0, viewport - tail - reservePx)
                        }
                        if (extra != state.extraReservePx) {
                            // The room arrives with the next layout; the pin is set against that one.
                            state.extraReservePx = extra
                            return@collect
                        }
                        val shift = anchorItem.offset - (gapPx - topPadding)
                        // Only a scroll the list can make, and one per frame: a scroll re-measures
                        // at once, and a pin the list cannot reach would otherwise be asked for again
                        // and again without the main thread ever drawing.
                        val reachable = (abs(shift) > 1) &&
                            ((shift > 0 && listState.canScrollForward) || (shift < 0 && listState.canScrollBackward))
                        if (reachable && !listState.isScrollInProgress) {
                            withFrameNanos { }
                            scrollQuietly { listState.scrollToItem(rowIndex, topPadding - gapPx) }
                        }
                    } else if (!listState.isScrollInProgress) {
                        withFrameNanos { }
                        scrollQuietly { listState.scrollToItem(rowIndex, topPadding - gapPx) }
                    }
                }
            } else if (state.extraReservePx > 0) {
                // The room exists for the pin. Once the pin is gone it must not outlive it as empty
                // space: keep only the part in view, and it reaches zero as the shopper scrolls up.
                val reserveItem = items.firstOrNull { it.key == RESERVE_KEY }
                val inView = if (reserveItem == null) 0 else max(0, viewportEnd - reserveItem.offset - reservePx)
                if (inView < state.extraReservePx) state.extraReservePx = inView
            }

            val proximityPx = with(density) { BOTTOM_PROXIMITY.roundToPx() }
            val pinned = when {
                lastRowIndex < 0 -> true
                lastRow == null -> false
                else -> lastRow.offset + lastRow.size - (viewportEnd - reservePx) <= proximityPx
            }
            if (pinned != state.isPinnedToBottom) state.isPinnedToBottom = pinned
        }
    }

    LazyColumn(
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, row -> row.javaClass.name }) { index, row ->
            Box(
                Modifier
                    .widthIn(max = talqyn.metrics.maxContentWidth)
                    .fillMaxWidth()
                    .padding(top = if (index == 0) FIRST_ROW_TOP else ROW_SPACING),
            ) {
                when (row) {
                    is TalqynTranscriptRow.User -> TalqynUserBubble(row.text, actions.onEditQuestion)
                    is TalqynTranscriptRow.Assistant -> TalqynAssistantTurnView(row.row, actions)
                    is TalqynTranscriptRow.Suggestions -> TalqynSuggestions(row.questions, actions.onSuggestion)
                }
            }
        }
        item(key = RESERVE_KEY) {
            Spacer(Modifier.height(bottomReserve + with(density) { state.extraReservePx.toDp() }))
        }
    }
}
