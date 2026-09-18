package com.talqyn.sdk

import java.time.Instant

/**
 * One row in the shopper's list of conversations.
 *
 * @property sessionId The conversation id. Pass it to [TalqynConsultantApi.chat] to read
 *   the transcript, or to [TalqynConsultantQuery.sessionId] to continue the dialogue.
 * @property title The shopper's first message, truncated. `null` for conversations
 *   started before history existed.
 * @property messageCount The transcript length in **messages**, not turns: one turn writes two rows.
 * @property createdAt When the conversation started.
 * @property lastMessageAt When the last message was written — what the list is sorted by, newest first.
 */
public data class TalqynChatSummary(
    val sessionId: String,
    val title: String? = null,
    val messageCount: Int = 0,
    val createdAt: Instant? = null,
    val lastMessageAt: Instant? = null,
) {
    internal companion object {
        /** Tolerates absent fields and both ISO-8601 timestamp forms. */
        fun decode(json: JsonObject): TalqynChatSummary = TalqynChatSummary(
            sessionId = json.string("session_id") ?: "",
            title = json.string("title"),
            messageCount = json.int("message_count") ?: 0,
            createdAt = json.instant("created_at"),
            lastMessageAt = json.instant("last_message_at"),
        )
    }
}

/**
 * One message in a transcript.
 *
 * @property role Who wrote the message.
 * @property text The message text.
 * @property talqynIds The products shown in this turn, as Talqyn's internal ids. The
 *   cards live in [TalqynChatTranscript.products]; resolve them with
 *   [TalqynChatTranscript.productsFor].
 * @property route How the turn was routed. `null` for assistant messages and for rows
 *   written before routing was recorded.
 * @property turnId The id of the turn this message belongs to — the same on the
 *   shopper's message and on the answer. The key to rate the turn with
 *   [TalqynConsultantApi.submitFeedback]. `null` for turns written before ratings existed.
 * @property feedback The rating the shopper already gave the turn, so a reopened chat
 *   shows the thumb that is down instead of inviting a second tap.
 * @property createdAt When the message was written.
 */
public data class TalqynChatMessage(
    val role: Role,
    val text: String,
    val talqynIds: List<Long> = emptyList(),
    val route: Route? = null,
    val turnId: String? = null,
    val feedback: TalqynFeedbackVerdict? = null,
    val createdAt: Instant? = null,
) {
    /** Whether the turn redirected to ordinary search, making [text] a search query rather than a reply. */
    public val isRedirect: Boolean get() = route == Route.Redirect

    /**
     * Who wrote the message. Extensible for the same reason as [Route]: a role this
     * version of the SDK has not seen must not cost the app the message, let alone the
     * transcript.
     */
    @JvmInline
    public value class Role(public val rawValue: String) {
        public companion object {
            /** The shopper. */
            public val User: Role = Role("user")

            /** The consultant. */
            public val Assistant: Role = Role("assistant")
        }
    }

    /**
     * Which way a turn was routed. A storefront needs this for one reason: so that a
     * redirect turn is not rendered as a consultant reply — its text is the search query
     * the shopper was sent to, not prose written for them.
     */
    @JvmInline
    public value class Route(public val rawValue: String) {
        public companion object {
            /** The turn was answered by the consultant. */
            public val Consult: Route = Route("consult")

            /** The turn asked a clarifying question. */
            public val Clarify: Route = Route("clarify")

            /** The turn redirected to ordinary search. */
            public val Redirect: Route = Route("redirect")
        }
    }

    internal companion object {
        /**
         * Decodes a transcript message. A message without a role cannot be rendered and
         * fails to decode; the transcript drops that one message and keeps the rest.
         */
        fun decode(json: JsonObject): TalqynChatMessage {
            val role = json.string("role")?.takeIf { it.isNotEmpty() }
                ?: throw TalqynJsonException("message has no role")
            return TalqynChatMessage(
                role = Role(role),
                text = json.string("text") ?: "",
                talqynIds = json.longs("talqyn_ids"),
                route = json.string("route")?.let(::Route),
                turnId = json.string("turn_id"),
                feedback = json.string("feedback")?.let(TalqynFeedbackVerdict::fromWire),
                createdAt = json.instant("created_at"),
            )
        }
    }
}

/**
 * A full conversation transcript.
 *
 * @property sessionId The conversation id.
 * @property title The shopper's first message, truncated.
 * @property messages Every message, oldest first.
 * @property products Every product mentioned anywhere in the transcript, deduplicated.
 *   Prices and availability are **current**, not what they were during the
 *   conversation: showing last year's price as if it still stood is worse than showing
 *   one that changed. Products deleted from the catalog since are simply absent — the
 *   id remains in [TalqynChatMessage.talqynIds] with no card behind it.
 */
public data class TalqynChatTranscript(
    val sessionId: String,
    val title: String?,
    val messages: List<TalqynChatMessage>,
    val products: List<TalqynProduct>,
) {
    /**
     * Resolves the products shown in one message, in the order they were shown. Ids with
     * no card behind them are skipped.
     */
    public fun productsFor(message: TalqynChatMessage): List<TalqynProduct> {
        val index = LinkedHashMap<Long, TalqynProduct>()
        for (product in products) index.putIfAbsent(product.talqynId, product)
        return message.talqynIds.mapNotNull { index[it] }
    }

    internal companion object {
        /** A message or a card that does not decode is dropped on its own; the rest stays. */
        fun decode(json: JsonObject): TalqynChatTranscript = TalqynChatTranscript(
            sessionId = json.string("session_id") ?: "",
            title = json.string("title"),
            messages = json.objects("messages", TalqynChatMessage::decode),
            products = json.objects("products", TalqynProduct::decode),
        )
    }
}
