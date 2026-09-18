package com.talqyn.sdk

/**
 * A shopper's verdict on one consultant turn.
 *
 * Closed on purpose: the contract has exactly two values, and a third would not be a
 * new verdict but a different feature.
 */
public enum class TalqynFeedbackVerdict(public val wireValue: String) {
    /** The answer helped. */
    Up("up"),

    /** The answer did not. */
    Down("down"),
    ;

    internal companion object {
        fun fromWire(raw: String): TalqynFeedbackVerdict? = entries.firstOrNull { it.wireValue == raw }
    }
}

/**
 * Why a turn did not help.
 *
 * A dislike without a reason says only "bad". The reasons exist to say **what** to
 * fix, and each points at a different part of the consultant — a reason is worth more
 * than the thumb it comes with.
 *
 * Extensible rather than an enumeration, like the other wire values the contract may
 * grow; the server rejects a value it does not know with `422`, so send the ones
 * declared here.
 */
@JvmInline
public value class TalqynFeedbackReason(public val rawValue: String) {
    public companion object {
        /** The products are not what was asked for. */
        public val NotRelevant: TalqynFeedbackReason = TalqynFeedbackReason("not_relevant")

        /** The answer states something that is not true. */
        public val WrongInfo: TalqynFeedbackReason = TalqynFeedbackReason("wrong_info")

        /** The consultant asked too many clarifying questions. */
        public val TooManyQuestions: TalqynFeedbackReason = TalqynFeedbackReason("too_many_questions")

        /** There was no answer — the turn fell back or failed. */
        public val NoAnswer: TalqynFeedbackReason = TalqynFeedbackReason("no_answer")

        /** A price or the availability is wrong. */
        public val PriceStock: TalqynFeedbackReason = TalqynFeedbackReason("price_stock")

        /** Something else; say what in [TalqynFeedback.comment]. */
        public val Other: TalqynFeedbackReason = TalqynFeedbackReason("other")
    }
}

/**
 * A shopper's rating of one consultant turn: `POST /v1/consultant/feedback`.
 *
 * A turn is named by [turnId], which arrives in [TalqynConsultantDone.turnId] — or
 * [TalqynConsultantAnswer.turnId], or [TalqynChatMessage.turnId] for a turn reopened
 * from history. Knowing the id is what entitles a storefront to rate the turn: it was
 * shown to this client and no other.
 *
 * Rating the same turn again replaces the previous rating — the shopper changed their
 * mind, they did not rate twice.
 *
 * @property turnId The turn, from its `done` event.
 * @property sessionId The conversation the turn belongs to — the session id from the
 *   same `done` event. A turn from another conversation is `404`.
 * @property verdict Up or down.
 * @property reasons Why the turn did not help, most important first. Down only: the
 *   server drops reasons sent with [TalqynFeedbackVerdict.Up]. Up to six.
 * @property comment The shopper's own words. Down only, up to 500 characters.
 * @property talqynIds The cards the shopper called out as wrong — [TalqynProduct.talqynId].
 *   Down only, up to 20.
 * @property variant The storefront's A/B bucket. Filled from the client default when `null`.
 */
public data class TalqynFeedback(
    val turnId: String,
    val sessionId: String,
    val verdict: TalqynFeedbackVerdict,
    val reasons: List<TalqynFeedbackReason> = emptyList(),
    val comment: String? = null,
    val talqynIds: List<Long> = emptyList(),
    val variant: String? = null,
) {
    /** The down-only fields are left out of an up rating rather than sent for the server to drop. */
    internal fun toJson(): Map<String, Any?> = jsonObject {
        put("turn_id", turnId)
        put("session_id", sessionId)
        put("verdict", verdict.wireValue)
        if (verdict == TalqynFeedbackVerdict.Down) {
            if (reasons.isNotEmpty()) put("reasons", reasons.map { it.rawValue })
            putIfNotNull("comment", comment)
            if (talqynIds.isNotEmpty()) put("talqyn_ids", talqynIds)
        }
        putIfNotNull("variant", variant)
    }
}
