package com.talqyn.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.talqyn.consultant.TalqynAssistantTurn
import com.talqyn.consultant.TalqynConversation
import com.talqyn.consultant.TalqynConversationState
import com.talqyn.consultant.TalqynUserTurn
import com.talqyn.sdk.TalqynComparisonTable
import java.util.UUID
import java.util.WeakHashMap

/**
 * What the consultant screen shows over its conversation — the clarify sheet, the comparison,
 * history — and how much of the conversation it has already reacted to.
 *
 * Kept next to the conversation rather than in the composition: a navigation host disposes the
 * screen's composition when a product page opens over it and composes it again on the way back,
 * and a rotation does the same. Kept in `remember`, the screen would come back having forgotten
 * the question it had moved into the transcript, and would take every turn for a new one —
 * pinning the last question to the top over wherever the shopper had scrolled.
 */
@Stable
internal class TalqynScreenPresentation {
    private val clarify = TalqynClarifyPresentation()

    /** Turns whose clarify card stands in the transcript. */
    var inlineClarifyTurns: Set<UUID> by mutableStateOf(emptySet())
        private set

    /** The turn whose question is up as a sheet. */
    var sheetTurnId: UUID? by mutableStateOf(null)

    /** The comparison open over the transcript. */
    var comparison: TalqynComparisonTable? by mutableStateOf(null)

    /** Whether history is open over the transcript. */
    var showsHistory: Boolean by mutableStateOf(false)

    /** The turns as the screen last took them in; `null` until it has seen the conversation at all. */
    private var seenTurnIds: List<UUID>? = null
    private var wasStreaming = false
    private var wasRestoring = false

    /** What the screen does about a change of its conversation. */
    class Reaction(
        /** How the transcript moves. */
        val anchor: TalqynTranscriptAnchor,
        /** Turns gone from the conversation, whose rendering is no longer needed. */
        val removedTurnIds: List<UUID>,
        /** The conversation was replaced as a whole: started over, or a chat restored. */
        val isReplaced: Boolean,
        /** An answer has just finished coming in. */
        val answerSettled: Boolean,
    )

    /**
     * Takes in the conversation as it is now, and says what the screen does about what changed
     * since it last looked.
     *
     * A screen that has not seen the conversation before — composed over one already under way —
     * takes nothing in it for new: it pins the last question only while the transcript is where a
     * new list starts, and a question already asked stays in the transcript instead of coming up
     * as a sheet.
     *
     * @param transcriptAtStart Whether the transcript is still at its first row, so that a position
     *   restored from saved state is not overridden.
     */
    fun update(state: TalqynConversationState, transcriptAtStart: Boolean): Reaction {
        val turnIds = state.turns.map { it.id }
        val seen = seenTurnIds
        val streamingEnded = wasStreaming && !state.isStreaming
        val restoreEnded = wasRestoring && !state.isRestoring
        var anchor = TalqynTranscriptAnchor.Keep
        var removed = emptyList<UUID>()
        var isReplaced = false
        var isNewRequest = false

        if (seen == null) {
            if (transcriptAtStart && state.turns.any { it is TalqynUserTurn }) anchor = TalqynTranscriptAnchor.NewestTurn
        } else if (turnIds != seen) {
            val current = turnIds.toHashSet()
            val previous = seen.toHashSet()
            removed = seen.filter { it !in current }
            clarify.forget(removed)
            isNewRequest = state.turns.any { it is TalqynUserTurn && it.id !in previous }
            if (isNewRequest) clarify.beginRequest()
            if (restoreEnded || state.isEmpty) {
                clarify.forgetAll()
                isReplaced = true
            }
            anchor = when {
                restoreEnded -> TalqynTranscriptAnchor.Bottom
                isNewRequest -> TalqynTranscriptAnchor.NewestTurn
                else -> TalqynTranscriptAnchor.Keep
            }
        }

        val last = state.turns.lastOrNull() as? TalqynAssistantTurn
        if (last != null && !state.isStreaming && !state.isRestoring) {
            // A question the screen watched come in — its stream ended, or the shopper's new
            // question was answered at once — may come up as a sheet. One the screen finds already
            // asked, in a restored chat or a conversation it was composed over, stays in the transcript.
            val watched = streamingEnded || (isNewRequest && !isReplaced)
            val isCovered = comparison != null || showsHistory || sheetTurnId != null
            if (clarify.decide(last, canPresent = watched && !isCovered) == TalqynClarifyPresentation.Decision.Sheet) {
                sheetTurnId = last.id
            }
        }
        inlineClarifyTurns = clarify.inlineTurns

        seenTurnIds = turnIds
        wasStreaming = state.isStreaming
        wasRestoring = state.isRestoring
        return Reaction(anchor, removed, isReplaced, answerSettled = streamingEnded)
    }

    /** The shopper dismissed the sheet: its question moves into the transcript. */
    fun sheetDismissed(turnId: UUID) {
        if (sheetTurnId == turnId) sheetTurnId = null
        clarify.sheetDismissed(turnId)
        inlineClarifyTurns = clarify.inlineTurns
    }
}

/**
 * Keeps each conversation's [TalqynScreenPresentation] for as long as the screen's
 * `ViewModelStoreOwner` lives — a destination on the back stack, an activity across a rotation.
 *
 * Weakly: a conversation the app lets go of takes its presentation with it.
 */
internal class TalqynPresentationStore : ViewModel() {
    private val presentations = WeakHashMap<TalqynConversation, TalqynScreenPresentation>()

    fun presentation(conversation: TalqynConversation): TalqynScreenPresentation =
        presentations.getOrPut(conversation) { TalqynScreenPresentation() }
}
