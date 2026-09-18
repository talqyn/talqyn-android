package com.talqyn.consultant

import com.talqyn.sdk.TalqynClarify
import com.talqyn.sdk.TalqynConsultantAction
import com.talqyn.sdk.TalqynConsultantStage
import com.talqyn.sdk.TalqynException
import com.talqyn.sdk.TalqynFallbackReason
import com.talqyn.sdk.TalqynFeedbackReason
import com.talqyn.sdk.TalqynFeedbackVerdict
import com.talqyn.sdk.TalqynProduct
import com.talqyn.sdk.TalqynProductGroup
import java.util.UUID

/** One entry of a conversation: what the shopper said, or what the consultant answered. */
public sealed interface TalqynTurn {
    /** The stable identity of the turn, kept across every update to it. */
    public val id: UUID
}

/**
 * What the shopper sent.
 *
 * @property text The question as sent.
 */
public data class TalqynUserTurn(
    override val id: UUID = UUID.randomUUID(),
    val text: String,
) : TalqynTurn

/** The shopper's verdict on an answer. */
public enum class TalqynAnswerRating(public val wireValue: String) {
    Helpful("helpful"),
    NotHelpful("not_helpful"),
    ;

    /** The verdict the API takes for this rating. */
    public val verdict: TalqynFeedbackVerdict
        get() = if (this == Helpful) TalqynFeedbackVerdict.Up else TalqynFeedbackVerdict.Down

    public companion object {
        /** The rating a verdict stands for — for a turn reopened from history. */
        @JvmStatic
        public fun from(verdict: TalqynFeedbackVerdict): TalqynAnswerRating =
            if (verdict == TalqynFeedbackVerdict.Up) Helpful else NotHelpful
    }
}

/**
 * The consultant's answer to one question, filled in as the stream arrives.
 *
 * Read it as a snapshot: while [TalqynConversationState.isStreaming] is `true` the last
 * assistant turn keeps changing. Equality is the data class's own, and a screen redraws a
 * turn on inequality: a property added here is part of what redraws the turn without
 * anyone having to remember to add it to a comparison.
 *
 * @property question The question this turn answers — the shopper's text, or a clarify
 *   answer, or the question repeated on retry.
 * @property text The answer text so far, markers included; see [TalqynAnswerRenderer].
 * @property products Every product the turn found, deduplicated, in order of arrival.
 * @property groups The products split by role, for a multi-step plan.
 * @property searchId The impression id of the turn's products, for click events.
 * @property stage What the consultant is doing right now; `null` once the turn settled.
 * @property actions The actions proposed so far.
 * @property followUps Follow-up prompts, sanitized: trimmed, deduplicated, and no more than
 *   the conversation's [TalqynConversation.maxFollowUps].
 * @property clarify The clarifying questions, when the turn asked instead of answering.
 * @property clarifyAnswer What the shopper answered to [clarify], once they did.
 * @property redirectQuery The search query, when the request turned out to be a search.
 * @property fallbackReason Why the turn produced no text, when it produced none.
 * @property errorCode The server's failure code, from an `error` event.
 * @property failure The client-side failure that ended the stream, if one did.
 * @property wasStopped Whether the shopper stopped the answer.
 * @property talqynTurnId The turn's id on Talqyn's side, from its `done` event: what a rating
 *   is saved under. `null` until the turn is done, and for a turn from a server that predates
 *   ratings — such a turn is rated on the device only.
 * @property sessionId The conversation the turn was taken in, from its `done` event.
 * @property rating How the shopper rated the answer, once they did.
 * @property feedbackReasons Why the shopper found the answer unhelpful, most important first.
 *   Empty unless [rating] is [TalqynAnswerRating.NotHelpful].
 * @property timeToFirstTokenMs Time to the first token of the answer, in milliseconds, as
 *   Talqyn measured it — from the turn's `done` event. `null` until the turn is done, for a
 *   turn that streamed no text, and for a turn reopened from history.
 * @property totalMs How long the whole turn took, in milliseconds, as Talqyn measured it —
 *   from the turn's `done` event. `null` until the turn is done, and for a turn reopened from
 *   history.
 */
public data class TalqynAssistantTurn(
    override val id: UUID = UUID.randomUUID(),
    val question: String,
    val text: String = "",
    val products: List<TalqynProduct> = emptyList(),
    val groups: List<TalqynProductGroup>? = null,
    val searchId: String? = null,
    val stage: TalqynConsultantStage? = TalqynConsultantStage.Thinking,
    val actions: List<TalqynConsultantAction> = emptyList(),
    val followUps: List<String> = emptyList(),
    val clarify: TalqynClarify? = null,
    val clarifyAnswer: String? = null,
    val redirectQuery: String? = null,
    val fallbackReason: TalqynFallbackReason? = null,
    val errorCode: String? = null,
    val failure: TalqynException? = null,
    val wasStopped: Boolean = false,
    val talqynTurnId: String? = null,
    val sessionId: String? = null,
    val rating: TalqynAnswerRating? = null,
    val feedbackReasons: List<TalqynFeedbackReason> = emptyList(),
    val timeToFirstTokenMs: Int? = null,
    val totalMs: Int? = null,
) : TalqynTurn {
    /** Whether the turn ended in a failure of any kind: a server error, a dropped stream, or the shopper stopping it. */
    public val didFail: Boolean get() = errorCode != null || failure != null || wasStopped

    /** Whether the turn answered with products or text, rather than a question, a redirect, or a failure. */
    public val isAnswer: Boolean get() = clarify == null && redirectQuery == null && !didFail

    /** Whether a rating of the turn reaches Talqyn, rather than staying on the device. */
    public val isRatedRemotely: Boolean get() = talqynTurnId != null && sessionId != null
}

/**
 * The shopper's in-progress answer to a clarifying question. Immutable: every change
 * returns the next draft.
 *
 * @property selected Selected options by question id.
 * @property custom Free text typed instead of, or in addition to, the options.
 */
public data class TalqynClarifyDraft(
    val selected: Map<String, List<String>> = emptyMap(),
    val custom: String = "",
) {
    /** Whether anything has been chosen or typed. */
    public val isEmpty: Boolean get() = selected.values.all { it.isEmpty() } && custom.isBlank()

    /**
     * Toggles an option. A single-choice question replaces its selection; a
     * multiple-choice one adds and removes.
     */
    public fun toggle(option: String, question: TalqynClarify.Question): TalqynClarifyDraft {
        val current = selected[question.id].orEmpty()
        val next = if (question.multi) {
            if (option in current) current - option else current + option
        } else {
            if (current == listOf(option)) emptyList() else listOf(option)
        }
        return copy(selected = if (next.isEmpty()) selected - question.id else selected + (question.id to next))
    }

    /** Whether an option is selected. */
    public fun isSelected(option: String, question: TalqynClarify.Question): Boolean =
        selected[question.id]?.contains(option) == true

    /**
     * The answer to send: `Budget: up to 300k. Screen: 15". <free text>`, with the labels
     * and options as the questions carried them. The label loses its question mark: it is
     * restated, not asked back.
     */
    public fun answer(questions: List<TalqynClarify.Question>): String {
        val parts = questions.mapNotNull { question ->
            val values = selected[question.id]
            if (values.isNullOrEmpty()) return@mapNotNull null
            val label = question.label.trim { it == '?' || it == ' ' || it == '\t' }
            "$label: ${values.joinToString(", ")}"
        }
        val base = if (parts.isEmpty()) "" else parts.joinToString(". ") + "."
        val extra = custom.trim()
        if (extra.isEmpty()) return base
        return if (base.isEmpty()) extra else "$base $extra"
    }
}

/** The contract's input limits. */
public object TalqynConversationLimits {
    /** The longest question the API accepts. */
    public const val MAX_INPUT_LENGTH: Int = 2000

    /** The longest free-text clarify answer. */
    public const val MAX_CLARIFY_CUSTOM_LENGTH: Int = 500

    /** How many follow-up prompts to show, unless a conversation is built with its own [TalqynConversation.maxFollowUps]. */
    public const val MAX_FOLLOW_UPS: Int = 3
}
