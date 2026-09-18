package com.talqyn.ui

import com.talqyn.consultant.TalqynAssistantTurn
import java.util.UUID

/**
 * When a clarifying question comes up as a sheet and when it stays a card in the transcript.
 *
 * The policy on its own, apart from the screen that presents: which turns already asked, which
 * sheet the shopper swiped away, and whether this question of theirs has had its sheet.
 */
internal class TalqynClarifyPresentation {
    /** How a turn's clarification is shown once its answer has settled. */
    enum class Decision {
        /** Nothing to show — no question, already answered, or already shown. */
        None,

        /** A card in the transcript. */
        Inline,

        /** A sheet over the transcript. */
        Sheet,
    }

    /** Turns whose question stays inline: the sheet was dismissed, or never came up. */
    var inlineTurns: Set<UUID> = emptySet()
        private set

    private val decidedTurns = HashSet<UUID>()
    private var hasShownSheetForRequest = false

    /** The shopper typed a new question: it may have a sheet of its own. */
    fun beginRequest() {
        hasShownSheetForRequest = false
    }

    /**
     * Decides once per turn, when its answer has settled.
     *
     * One sheet per question the shopper typed. A clarification that follows a clarify answer
     * shows inline: a second sheet in a row is an interrogation. So does one that cannot be
     * presented right now — something else is already on screen.
     */
    fun decide(turn: TalqynAssistantTurn, canPresent: Boolean): Decision {
        if (turn.clarify == null || turn.clarifyAnswer != null || turn.id in decidedTurns) return Decision.None
        decidedTurns.add(turn.id)
        if (hasShownSheetForRequest || !canPresent) {
            inlineTurns = inlineTurns + turn.id
            return Decision.Inline
        }
        hasShownSheetForRequest = true
        return Decision.Sheet
    }

    /** The shopper dismissed a turn's sheet: the question moves into the transcript. */
    fun sheetDismissed(turnId: UUID) {
        inlineTurns = inlineTurns + turnId
    }

    /** Forgets turns that left the conversation. */
    fun forget(turnIds: Collection<UUID>) {
        decidedTurns.removeAll(turnIds.toSet())
        inlineTurns = inlineTurns - turnIds.toSet()
    }

    /** Forgets everything: a new or restored conversation. */
    fun forgetAll() {
        decidedTurns.clear()
        inlineTurns = emptySet()
        hasShownSheetForRequest = false
    }
}
