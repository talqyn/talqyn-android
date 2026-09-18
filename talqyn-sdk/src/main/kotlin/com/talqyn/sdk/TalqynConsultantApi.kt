package com.talqyn.sdk

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile

/**
 * The CIP consultant: a conversation turn and the shopper's chat history.
 *
 * Requires the `consultant` scope. Reached through [Talqyn.consultant]. This is the one
 * part of the API with direct money behind it — every turn is an LLM call — so it has
 * its own rate-limit bucket, separate from search.
 */
public class TalqynConsultantApi internal constructor(
    private val client: TalqynApiClient,
    private val defaults: TalqynDefaults,
    private val logHandler: ((TalqynLogEvent) -> Unit)?,
) {
    // region Conversation turn

    /**
     * Asks the consultant and streams the turn: `POST /v1/consultant/ask`.
     *
     * The flow always ends with [TalqynConsultantEvent.Done]; carry its session id into
     * the next question to continue the conversation. Events this version of the SDK does
     * not recognize are skipped rather than surfaced, so a new event type on the server
     * cannot break a shipped app.
     *
     * The flow is cold: the request is made when it is collected, and cancelled with the
     * collecting coroutine, so a turn nobody reads does not keep running. Collect it
     * **once** — every collection sends the question again, and that is another paid turn.
     * The client's defaults (locale, place, A/B bucket) are the ones of the moment the turn
     * is sent. Reading stops at `Done`: nothing follows it, and a connection a proxy holds
     * open after it must not keep the turn looking unfinished. The stream is read and
     * decoded off the collector's thread.
     *
     * ```kotlin
     * talqyn.consultant.ask(query).collect { event ->
     *     when (event) {
     *         is TalqynConsultantEvent.Delta -> transcript.append(event.text)
     *         is TalqynConsultantEvent.Done -> session = event.done.sessionId
     *         else -> Unit
     *     }
     * }
     * ```
     *
     * Collecting throws [TalqynException] — commonly [TalqynException.RateLimited] when
     * the consultant bucket is exhausted, or [TalqynException.Transport] if the connection
     * drops mid-turn. A stream that ends **before** `done` was cut short somewhere between
     * Talqyn and the device and throws [TalqynException.Transport] with a
     * [TalqynConnectionLostException], after the events that did arrive. A turn that
     * degrades on Talqyn's side does **not** throw: it arrives as
     * [TalqynConsultantEvent.Fallback] with the products intact.
     */
    public fun ask(query: TalqynConsultantQuery): Flow<TalqynConsultantEvent> = flow {
        val prepared = defaults.apply(query)
        var isComplete = false
        client.stream(path = "consultant/ask", body = prepared.toJson())
            .mapNotNull { message ->
                val event = TalqynConsultantEvent.from(message)
                if (event == null) {
                    // Skipped by design, but silently is how a renamed event goes unnoticed
                    // until a shopper complains.
                    logHandler.log(TalqynLogEvent.Level.Debug, "consultant event '${message.name}' skipped: unknown or undecodable")
                }
                event
            }
            .transformWhile { event ->
                emit(event)
                !event.isTerminal
            }
            .collect { event ->
                if (event.isTerminal) isComplete = true
                emit(event)
            }
        // The contract closes every turn with `done`. Without it the stream was cut
        // short — a proxy, an idle timeout, a dropped connection — and the app must not
        // take a turn that merely stopped for one that finished. A stream that ended
        // because its collector was cancelled is neither.
        if (!isComplete) {
            currentCoroutineContext().ensureActive()
            logHandler.log(TalqynLogEvent.Level.Warning, "consultant stream ended without a done event")
            throw TalqynException.Transport(TalqynConnectionLostException("the consultant stream ended before its done event"))
        }
    }.flowOn(client.decodeDispatcher)

    /**
     * Asks the consultant a plain question.
     *
     * @param question The shopper's question. 1–2000 characters.
     * @param sessionId The session of the previous turn, when continuing a conversation.
     */
    public fun ask(question: String, sessionId: String? = null): Flow<TalqynConsultantEvent> =
        ask(TalqynConsultantQuery(question = question, sessionId = sessionId))

    /**
     * Asks the consultant and waits for the whole turn: `POST /v1/consultant/ask?stream=false`.
     *
     * The answer arrives complete, and therefore later: the shopper waits in silence
     * instead of reading the text as it is generated. Use [ask] for a conversation screen;
     * this is for places with no room for a stream — a widget, or an answer prepared in the
     * background.
     *
     * The request waits as long as a stream waits for its next byte
     * ([TalqynConfiguration.streamTimeout]): nothing comes back until the whole turn is
     * written, and the ordinary timeout would cut a long one. A failure is repeated only
     * after a `429`: a turn is an LLM call that may have run before its answer was lost,
     * and a repeat would pay for it twice and write it into the history twice.
     */
    public suspend fun answer(query: TalqynConsultantQuery): TalqynConsultantAnswer =
        client.send(
            path = "consultant/ask",
            query = listOf("stream" to "false"),
            body = defaults.apply(query).toJson(),
            safety = TalqynRetrySafety.OnlyIfRejected,
            timeout = client.streamTimeout,
        ) { TalqynConsultantAnswer.decode(it.asJsonObject()) }

    // endregion

    // region Rating a turn

    /**
     * Rates a turn: `POST /v1/consultant/feedback`.
     *
     * Rating a turn again replaces its rating. Any turn can be rated — a clarification or
     * a fallback as well as an answer — as long as it has a [TalqynConsultantDone.turnId].
     *
     * Unlike events, a rating is written before the call returns: the thumb has a visible
     * state, and the answer says whether it holds. A failure is repeated like a read — a
     * repeat of the same rating replaces it with itself.
     *
     * @throws TalqynException [TalqynException.NotFound] when there is no such turn in that
     *   conversation, [TalqynException.Validation] for a turn id that is not a UUID or a
     *   reason the server does not know.
     */
    public suspend fun submitFeedback(feedback: TalqynFeedback) {
        client.sendWithoutResponse(
            path = "consultant/feedback",
            body = defaults.apply(feedback).toJson(),
            safety = TalqynRetrySafety.Idempotent,
        )
    }

    /**
     * Takes a rating back: `DELETE /v1/consultant/feedback/{turn_id}`.
     *
     * @throws TalqynException [TalqynException.NotFound] when the turn had no rating. That
     *   is also what a repeat of a deletion that did go through answers, so a caller that
     *   only wants the rating gone may treat it as done.
     */
    public suspend fun withdrawFeedback(turnId: String) {
        client.sendWithoutResponse(
            method = "DELETE",
            path = "consultant/feedback/${TalqynRequestBuilder.segment(turnId)}",
            safety = TalqynRetrySafety.Idempotent,
        )
    }

    // endregion

    // region Chat history

    /**
     * Lists the shopper's conversations, newest first: `GET /v1/consultant/chats`.
     *
     * The token must name the shopper — any identity but [TalqynDeviceIdentity.Guest].
     * Under a guest token the server answers `403` rather than an empty list —
     * deliberately, so that a storefront that forgot to name its shopper cannot ship a
     * silently empty screen.
     *
     * @throws TalqynException Notably [TalqynException.Forbidden] under a guest token.
     */
    public suspend fun chats(limit: Int = 20, offset: Int = 0): List<TalqynChatSummary> =
        client.send(
            method = "GET",
            path = "consultant/chats",
            query = listOf("limit" to limit.toString(), "offset" to offset.toString()),
        ) { it.asJsonObjects(TalqynChatSummary::decode) }

    /**
     * Reads one conversation: `GET /v1/consultant/chats/{session_id}`.
     *
     * The transcript arrives with the product cards hydrated, so a conversation can be
     * redrawn with its results rather than as bare text.
     *
     * @param locale The language to hydrate product cards in. `null` uses the client default.
     * @throws TalqynException [TalqynException.NotFound] both for a conversation that does
     *   not exist and for one belonging to somebody else; the two are indistinguishable on purpose.
     */
    public suspend fun chat(sessionId: String, locale: TalqynLocale? = null): TalqynChatTranscript =
        client.send(
            method = "GET",
            path = "consultant/chats/${TalqynRequestBuilder.segment(sessionId)}",
            query = listOf("locale" to (locale ?: defaults.current.locale).wireValue),
        ) { TalqynChatTranscript.decode(it.asJsonObject()) }

    /**
     * Deletes one of the shopper's conversations: `DELETE /v1/consultant/chats/{session_id}`.
     *
     * Removes both the journal rows and the working memory of the session. This is the
     * shopper clearing one conversation from their own storefront, not an operator erasing
     * a data subject.
     *
     * @throws TalqynException [TalqynException.NotFound] when the conversation does not exist
     *   or is not this shopper's.
     */
    public suspend fun deleteChat(sessionId: String) {
        client.sendWithoutResponse(method = "DELETE", path = "consultant/chats/${TalqynRequestBuilder.segment(sessionId)}")
    }

    // endregion
}
