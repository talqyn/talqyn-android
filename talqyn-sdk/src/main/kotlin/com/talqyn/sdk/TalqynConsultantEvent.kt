package com.talqyn.sdk

/**
 * One event from the consultant's SSE stream.
 *
 * A turn **always** opens with [Status] carrying [TalqynConsultantStage.Thinking] and
 * **always** closes with [Done] — a reliable end-of-stream signal whatever the turn
 * turned out to be. In between comes one of these shapes:
 *
 * - **ordinary search**: `Status` → `Status(Searching)` → `Products` → `Delta`×N →
 *   `Action`×0…2 → `FollowUps`? → `Done`;
 * - **clarification**: `Status` → `Clarify` → `Done` — no products yet;
 * - **redirect**: `Status` → `RedirectToSearch` → `Done` — the request was a search
 *   query, so run it through [TalqynSearchApi.search];
 * - **store question** (delivery, payment, returns, warranty): `Status` → `Delta` →
 *   `Done` — short text, no products;
 * - **degradation**: `Status` → `Products` → `Fallback` → `Done` — products are there,
 *   text is not;
 * - **retrieval failure**: `Status(Searching)` → `Error` → `Done`.
 *
 * Multi-step plans may interleave several `Status`, `Products`, and `Action` events
 * before the closing `Delta` and `Done`.
 *
 * An event the server adds is skipped by a shipped build, but the SDK release that
 * learns it adds a subtype here. A `when` that names every event with no `else` stops
 * compiling on that update: handle the events you act on and give the rest to an `else`.
 */
public sealed interface TalqynConsultantEvent {
    /** What the consultant is doing right now. */
    public data class Status(val stage: TalqynConsultantStage) : TalqynConsultantEvent

    /** The products found for this turn. May arrive without any text at all — render results independently of [Delta]. */
    public data class Products(val products: TalqynConsultantProducts) : TalqynConsultantEvent

    /** An increment of the answer text. May contain `[p:ID]` product markers; see [TalqynAnswerMarkup]. */
    public data class Delta(val text: String) : TalqynConsultantEvent

    /** The turn needs more context before it can search. */
    public data class Clarify(val clarify: TalqynClarify) : TalqynConsultantEvent

    /** The request was an ordinary search query, not a consultation: run [query] through [TalqynSearchApi.search]. */
    public data class RedirectToSearch(val query: String) : TalqynConsultantEvent

    /** The turn will produce no text. Products, if any, have already arrived. */
    public data class Fallback(val reason: TalqynFallbackReason) : TalqynConsultantEvent

    /** An interface action the consultant proposes. */
    public data class Action(val action: TalqynConsultantAction) : TalqynConsultantEvent

    /**
     * Two or three ready-made follow-up prompts.
     *
     * Exactly one such event per turn, after the text and the actions. Render them as
     * chips and send a tap verbatim as [TalqynConsultantQuery.question] with the same
     * session id.
     */
    public data class FollowUps(val items: List<String>) : TalqynConsultantEvent

    /** The turn failed on Talqyn's side, with a code such as `retrieval_failed` or `internal_error`. */
    public data class Error(val code: String) : TalqynConsultantEvent

    /** The turn is over. */
    public data class Done(val done: TalqynConsultantDone) : TalqynConsultantEvent

    /** Whether this is [Done], after which the stream yields nothing more. */
    public val isTerminal: Boolean get() = this is Done

    public companion object {
        /**
         * Parses one stream event.
         *
         * [TalqynConsultantApi.ask] applies this to every message and skips what it cannot
         * parse, so a new event type on the server does not break a shipped app.
         *
         * @return The parsed event, or `null` when the event name is unknown to this
         *   version of the SDK or its payload does not decode.
         */
        @JvmStatic
        public fun from(message: TalqynSseMessage): TalqynConsultantEvent? {
            val json = try {
                TalqynJson.parse(message.data).asJsonObject()
            } catch (e: TalqynJsonException) {
                return null
            }
            return when (message.name) {
                "status" -> Status(TalqynConsultantStage(json.string("stage") ?: ""))
                "products" -> Products(TalqynConsultantProducts.decode(json))
                "delta" -> Delta(json.string("text") ?: "")
                "clarify" -> Clarify(TalqynClarify.decode(json))
                // `redirect` is the former name of the same event: a storefront that lived
                // through the rename must not lose redirects.
                "redirect_to_search", "redirect" -> RedirectToSearch(json.string("query") ?: "")
                "fallback" -> Fallback(TalqynFallbackReason(json.string("reason") ?: ""))
                "action" -> Action(TalqynConsultantAction.decode(json))
                "follow_ups" -> FollowUps(json.strings("items"))
                "error" -> Error(json.string("code") ?: "")
                "done" -> Done(TalqynConsultantDone.decode(json))
                else -> null
            }
        }
    }
}
