package com.talqyn.consultant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import com.talqyn.sdk.Talqyn
import com.talqyn.sdk.TalqynChatMessage
import com.talqyn.sdk.TalqynChatTranscript
import com.talqyn.sdk.TalqynConsultantEvent
import com.talqyn.sdk.TalqynConsultantQuery
import com.talqyn.sdk.TalqynDeviceIdentity
import com.talqyn.sdk.TalqynEventSource
import com.talqyn.sdk.TalqynException
import com.talqyn.sdk.TalqynFeedback
import com.talqyn.sdk.TalqynFeedbackReason
import com.talqyn.sdk.TalqynProduct
import com.talqyn.sdk.TalqynProductClickEvent
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Everything the consultant screen shows, as one snapshot.
 *
 * @property turns The turns in order. The last assistant turn changes while [isStreaming] is `true`.
 * @property isStreaming Whether an answer is being streamed right now.
 * @property isRestoring Whether a chat from history is being loaded.
 * @property sessionId The conversation id, once the first turn is done.
 * @property draft The composer text.
 * @property clarifyDrafts In-progress clarify answers by turn id, so a draft survives a re-render.
 * @property productsById Every product seen in this conversation, by Talqyn id: what `[p:ID]`
 *   markers and comparison tables resolve against.
 * @property restoreFailure The last failure to load a chat from history, for an alert.
 * @property feedbackFailure The last rating Talqyn did not save. The turn's rating is already
 *   put back to what Talqyn holds; this is for a screen that also wants to say so.
 */
public data class TalqynConversationState(
    val turns: List<TalqynTurn> = emptyList(),
    val isStreaming: Boolean = false,
    val isRestoring: Boolean = false,
    val sessionId: String? = null,
    val draft: String = "",
    val clarifyDrafts: Map<UUID, TalqynClarifyDraft> = emptyMap(),
    val productsById: Map<Long, TalqynProduct> = emptyMap(),
    val restoreFailure: TalqynException? = null,
    val feedbackFailure: TalqynException? = null,
) {
    /** Whether the screen has nothing to show yet. */
    public val isEmpty: Boolean get() = turns.isEmpty()

    /**
     * The prompts to offer under the transcript: the last turn's follow-ups, or — after the
     * very first answer — the example questions not yet asked.
     *
     * A turn the consultant gave up on offers none: whatever stopped it — a spent budget, a
     * timeout — would stop the next question too, so inviting one would only walk the
     * shopper into the same wall.
     *
     * @param examples The example questions, from [TalqynUiStrings.exampleQuestions].
     */
    public fun suggestedQuestions(examples: List<String>): List<String> {
        val last = turns.lastOrNull() as? TalqynAssistantTurn ?: return emptyList()
        if (isStreaming || last.fallbackReason != null) return emptyList()
        if (last.followUps.isNotEmpty()) return last.followUps
        if (!last.isAnswer || turns.count { it is TalqynAssistantTurn } != 1) return emptyList()
        val asked = turns.mapNotNullTo(HashSet()) { (it as? TalqynAssistantTurn)?.question?.normalized() }
        return examples.filter { it.normalized() !in asked }
    }

    /** Whether the clarify card of a turn takes input: only the latest turn, and only when nothing is streaming. */
    public fun isClarifyInteractive(turn: TalqynAssistantTurn): Boolean = !isStreaming && turns.lastOrNull()?.id == turn.id

    /** Whether a turn is the latest one, which is where a retry makes sense. */
    public fun isLast(turn: TalqynTurn): Boolean = turns.lastOrNull()?.id == turn.id

    /** The assistant turn with this id, if the conversation still has it. */
    public fun assistantTurn(id: UUID): TalqynAssistantTurn? = turns.firstOrNull { it.id == id } as? TalqynAssistantTurn

    private fun String.normalized(): String = lowercase(Locale.ROOT).trim()
}

/**
 * The consultant conversation: turns, streaming, clarifications, ratings, history.
 *
 * This is the logic of the consultant screen with no view attached. It backs the screen in
 * `talqyn-ui`, and it is public so a storefront that draws its own screen gets the same
 * behavior — delta batching, retry, clarify answers, ratings, restoring a chat, click events —
 * by observing [state] instead of reimplementing it over [com.talqyn.sdk.TalqynConsultantApi].
 *
 * ```kotlin
 * val conversation = TalqynConversation(talqyn, scope = viewModelScope)
 *
 * conversation.state.collect { state -> render(state.turns) }
 * conversation.send("need a laptop for school")
 * ```
 *
 * Main-thread bound: call it from the main thread, as a view does. Keep it where it survives
 * a configuration change — a `ViewModel` — and stop it when the screen goes away for good:
 * every turn is an LLM call, and one nobody will read is still being paid for. A
 * `viewModelScope` passed in does that by itself; [close] does it for a conversation kept
 * anywhere else.
 *
 * @param talqyn The client to talk through.
 * @param maxFollowUps How many follow-up prompts to keep from a turn. `0` shows none. How many
 *   questions to put in front of the shopper is the storefront's call, not the SDK's; the
 *   server may send more than are worth showing.
 * @param scope Where the conversation's work runs. The conversation works inside a scope of its
 *   own within this one: it ends when this scope ends — pass a `viewModelScope` to tie the two
 *   lifetimes together — and [close] ends only the conversation's work, not the rest of the
 *   scope. Defaults to a scope of its own on the main thread.
 */
public class TalqynConversation(
    public val talqyn: Talqyn,
    maxFollowUps: Int = TalqynConversationLimits.MAX_FOLLOW_UPS,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    /** How many follow-up prompts the screen offers under an answer. */
    public val maxFollowUps: Int = maxFollowUps.coerceAtLeast(0)

    /**
     * The conversation's own work, a child of the scope it was given. [close] cancels this and
     * nothing else: a `viewModelScope` passed in carries the app's own work as well.
     */
    private val work: CoroutineScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val mutableState = MutableStateFlow(TalqynConversationState())

    /** Everything the screen shows. */
    public val state: StateFlow<TalqynConversationState> = mutableState.asStateFlow()

    private var streamJob: Job? = null
    private var restoreJob: Job? = null
    private val pendingDelta = StringBuilder()
    private var flushJob: Job? = null
    private var identitySnapshot: TalqynDeviceIdentity? = null

    /** A rating as Talqyn holds it. */
    private data class FeedbackState(val rating: TalqynAnswerRating?, val reasons: List<TalqynFeedbackReason>) {
        companion object {
            val Unrated = FeedbackState(null, emptyList())
        }
    }

    /** What Talqyn last confirmed for each turn: what a failed update falls back to, and what the next one is compared against. */
    private val confirmedFeedback = HashMap<UUID, FeedbackState>()

    /**
     * One sync per turn. Taps arriving while one runs are not queued: the sync sends the
     * latest state when its request returns, so three quick taps cost two requests, not
     * three, and the last one always wins.
     */
    private val feedbackJobs = HashMap<UUID, Job>()

    // region Composer and clarify drafts

    /** Sets the composer text — to prefill a question, or as the shopper types. */
    public fun updateDraft(text: String) {
        mutableState.update { it.copy(draft = text) }
    }

    /** Keeps an in-progress clarify answer for a turn. */
    public fun updateClarifyDraft(turnId: UUID, draft: TalqynClarifyDraft) {
        mutableState.update { it.copy(clarifyDrafts = it.clarifyDrafts + (turnId to draft)) }
    }

    /** Clears [TalqynConversationState.restoreFailure] once it has been shown. */
    public fun clearRestoreFailure() {
        mutableState.update { it.copy(restoreFailure = null) }
    }

    /** Clears [TalqynConversationState.feedbackFailure] once it has been shown. */
    public fun clearFeedbackFailure() {
        mutableState.update { it.copy(feedbackFailure = null) }
    }

    // endregion

    // region Sending

    /** Sends a question. Empty text, a stream in progress, a restore in progress, or a closed conversation make this a no-op. */
    public fun send(text: String) {
        send(text, showsBubble = true, replaceIndex = null)
    }

    /**
     * Stops the current answer. What arrived stays; the turn is marked as stopped and can be
     * retried.
     */
    public fun stop() {
        streamJob?.cancel()
    }

    /** Starts over: a new session with an empty transcript. Ignored while an answer is streaming. */
    public fun reset() {
        if (mutableState.value.isStreaming) return
        restoreJob?.cancel()
        restoreJob = null
        confirmedFeedback.clear()
        cancelPendingDelta()
        mutableState.update {
            it.copy(
                turns = emptyList(),
                isRestoring = false,
                productsById = emptyMap(),
                clarifyDrafts = emptyMap(),
                sessionId = null,
            )
        }
    }

    /** Asks the same question again, dropping that turn and everything after it. */
    public fun retry(turnId: UUID) {
        val turns = mutableState.value.turns
        val index = turns.indexOfFirst { it.id == turnId }
        val turn = turns.getOrNull(index) as? TalqynAssistantTurn ?: return
        send(turn.question, showsBubble = false, replaceIndex = index)
    }

    /**
     * Answers a clarifying question. The answer is recorded on that turn and sent as the next
     * question in the same session.
     *
     * @param answer The composed answer; see [TalqynClarifyDraft.answer].
     */
    public fun submitClarify(turnId: UUID, answer: String) {
        if (!work.isActive || mutableState.value.assistantTurn(turnId) == null) return
        mutableState.update { state ->
            state.copy(
                turns = state.turns.map { if (it.id == turnId && it is TalqynAssistantTurn) it.copy(clarifyAnswer = answer) else it },
                clarifyDrafts = state.clarifyDrafts - turnId,
            )
        }
        send(answer, showsBubble = false, replaceIndex = null)
    }

    /**
     * Stops the conversation for good: a streaming answer and a chat being restored end, and the
     * conversation takes no work after this. A rating already on its way is still saved. The scope
     * the conversation was given is left running.
     */
    public fun close() {
        work.cancel()
    }

    private fun send(raw: String, showsBubble: Boolean, replaceIndex: Int?) {
        val question = raw.trim()
        val current = mutableState.value
        // A closed conversation takes no work: a turn marked as streaming with nothing to run it
        // would stay that way for ever.
        if (question.isEmpty() || current.isStreaming || current.isRestoring || !work.isActive) return

        var turns = current.turns
        var drafts = current.clarifyDrafts
        var products = current.productsById
        if (replaceIndex != null) {
            val dropped = turns.drop(replaceIndex).map { it.id }
            turns = turns.take(replaceIndex)
            drafts = drafts - dropped.toSet()
            dropped.forEach(confirmedFeedback::remove)
            products = productIndex(turns)
        }
        if (showsBubble) turns = turns + TalqynUserTurn(text = question)
        turns = turns + TalqynAssistantTurn(question = question)
        mutableState.value = current.copy(
            turns = turns,
            clarifyDrafts = drafts,
            productsById = products,
            draft = "",
            isStreaming = true,
        )

        val query = TalqynConsultantQuery(question = question, sessionId = current.sessionId)
        streamJob = work.launch {
            try {
                talqyn.consultant.ask(query).collect(::apply)
            } catch (e: CancellationException) {
                // Stopped — by the shopper, or by a closed screen. What arrived stays, and the
                // turn must not read as finished.
                flushPendingDelta()
                mutateLastAssistant { it.copy(wasStopped = true) }
                finishStream()
                // A cancellation from below, while this job still runs, ends the turn the same
                // way, and the job carries on to its end; the job's own goes on as it is.
                ensureActive()
                return@launch
            } catch (e: Exception) {
                val failure = TalqynException.wrap(e)
                flushPendingDelta()
                mutateLastAssistant { turn ->
                    when {
                        // The shopper changed before the question went out. Nothing was asked and
                        // nothing failed: the turn reads as stopped, as it does on the twin SDK.
                        failure is TalqynException.IdentityChanged -> turn.copy(wasStopped = true)
                        turn.errorCode == null -> turn.copy(failure = failure)
                        else -> turn
                    }
                }
            }
            finishStream()
        }
    }

    private fun finishStream() {
        flushPendingDelta()
        mutateLastAssistant { it.copy(stage = null) }
        mutableState.update { it.copy(isStreaming = false) }
    }

    private fun apply(event: TalqynConsultantEvent) {
        if (event is TalqynConsultantEvent.Delta) {
            pendingDelta.append(event.text)
            scheduleDeltaFlush()
            return
        }
        flushPendingDelta()
        when (event) {
            is TalqynConsultantEvent.Status -> mutateLastAssistant { it.copy(stage = event.stage) }
            is TalqynConsultantEvent.Products -> {
                val payload = event.products
                mutateLastAssistant { turn ->
                    // A card is shown once: a payload may repeat a product within itself, not only
                    // one that an earlier event of the turn brought.
                    val seen = turn.products.mapTo(HashSet()) { it.talqynId }
                    turn.copy(
                        products = turn.products + payload.items.filter { seen.add(it.talqynId) },
                        groups = payload.groups ?: turn.groups,
                        searchId = payload.searchId ?: turn.searchId,
                        // Products are in; the text is what remains to wait for.
                        stage = if (turn.text.isEmpty()) com.talqyn.sdk.TalqynConsultantStage.Composing else null,
                    )
                }
                mutableState.update { state ->
                    state.copy(productsById = state.productsById + payload.items.associateBy { it.talqynId })
                }
            }
            is TalqynConsultantEvent.Delta -> Unit
            is TalqynConsultantEvent.Clarify -> mutateLastAssistant { it.copy(clarify = event.clarify, stage = null) }
            is TalqynConsultantEvent.RedirectToSearch -> mutateLastAssistant { it.copy(redirectQuery = event.query, stage = null) }
            is TalqynConsultantEvent.Fallback -> mutateLastAssistant { it.copy(fallbackReason = event.reason, stage = null) }
            is TalqynConsultantEvent.Action -> mutateLastAssistant { it.copy(actions = it.actions + event.action) }
            is TalqynConsultantEvent.FollowUps -> mutateLastAssistant { it.copy(followUps = sanitizedFollowUps(event.items, maxFollowUps)) }
            is TalqynConsultantEvent.Error -> mutateLastAssistant { it.copy(errorCode = event.code, stage = null) }
            is TalqynConsultantEvent.Done -> {
                val done = event.done
                val session = done.sessionId.ifEmpty { null }
                mutableState.update { it.copy(sessionId = session ?: it.sessionId) }
                mutateLastAssistant {
                    it.copy(
                        talqynTurnId = done.turnId,
                        sessionId = session,
                        timeToFirstTokenMs = done.timeToFirstTokenMs,
                        totalMs = done.totalMs,
                    )
                }
            }
        }
    }

    private fun scheduleDeltaFlush() {
        if (flushJob != null) return
        flushJob = work.launch {
            delay(DELTA_FLUSH_INTERVAL_MILLIS)
            flushJob = null
            flushPendingDelta()
        }
    }

    private fun flushPendingDelta() {
        if (pendingDelta.isEmpty()) return
        val text = pendingDelta.toString()
        pendingDelta.setLength(0)
        mutateLastAssistant { it.copy(text = it.text + text, stage = null) }
    }

    private fun cancelPendingDelta() {
        flushJob?.cancel()
        flushJob = null
        pendingDelta.setLength(0)
    }

    private fun mutateLastAssistant(change: (TalqynAssistantTurn) -> TalqynAssistantTurn) {
        mutableState.update { state ->
            val last = state.turns.lastOrNull() as? TalqynAssistantTurn ?: return@update state
            state.copy(turns = state.turns.dropLast(1) + change(last))
        }
    }

    private fun replaceAssistant(id: UUID, change: (TalqynAssistantTurn) -> TalqynAssistantTurn) {
        mutableState.update { state ->
            state.copy(turns = state.turns.map { if (it.id == id && it is TalqynAssistantTurn) change(it) else it })
        }
    }

    // endregion

    // region Ratings

    /**
     * Records the shopper's verdict on a turn and saves it with Talqyn.
     *
     * The turn shows the new rating at once. Saving follows in the background; if Talqyn
     * does not take it, the turn goes back to the rating Talqyn holds and
     * [TalqynConversationState.feedbackFailure] says why — a thumb that looks pressed must be
     * pressed. Taps faster than the network are coalesced: the last one is what is saved, and
     * taps that end where they started send nothing. A rating on its way is saved even if the
     * conversation is closed meanwhile.
     *
     * A turn with no [TalqynAssistantTurn.talqynTurnId] — one from a server that predates
     * ratings — keeps its rating on the device.
     *
     * @param rating The verdict, or `null` to take it back.
     * @param reasons Why the answer did not help, most important first. Kept only with
     *   [TalqynAnswerRating.NotHelpful].
     */
    public fun rate(turnId: UUID, rating: TalqynAnswerRating?, reasons: List<TalqynFeedbackReason> = emptyList()) {
        if (!work.isActive) return
        val turn = mutableState.value.assistantTurn(turnId) ?: return
        val unique = if (rating == TalqynAnswerRating.NotHelpful) reasons.distinct() else emptyList()
        if (turn.rating == rating && turn.feedbackReasons == unique) return
        replaceAssistant(turnId) { it.copy(rating = rating, feedbackReasons = unique) }
        mutableState.update { it.copy(feedbackFailure = null) }
        if (!turn.isRatedRemotely || feedbackJobs[turnId]?.isActive == true) return
        feedbackJobs[turnId] = work.launch {
            // Taps of one moment settle before anything is sent: the sync starts on the next pass
            // of the main thread — as the twin SDK's does — not inside the tap that started it.
            yield()
            syncFeedback(turnId)
        }
    }

    /** Sends the turn's rating until what Talqyn holds is what the turn shows, or until Talqyn refuses. */
    private suspend fun syncFeedback(turnId: UUID) {
        val consultant = talqyn.consultant
        while (true) {
            val turn = mutableState.value.assistantTurn(turnId) ?: return
            val talqynTurnId = turn.talqynTurnId ?: return
            val sessionId = turn.sessionId ?: return
            val wanted = FeedbackState(turn.rating, turn.feedbackReasons)
            val confirmed = confirmedFeedback[turnId] ?: FeedbackState.Unrated
            if (wanted == confirmed) return
            try {
                // Carried through even if the conversation is closed while the request is out: the
                // shopper saw the thumb pressed, and a screen going away does not take that back.
                withContext(NonCancellable) {
                    val rating = wanted.rating
                    if (rating != null) {
                        consultant.submitFeedback(
                            TalqynFeedback(turnId = talqynTurnId, sessionId = sessionId, verdict = rating.verdict, reasons = wanted.reasons),
                        )
                    } else {
                        try {
                            consultant.withdrawFeedback(talqynTurnId)
                        } catch (e: TalqynException.NotFound) {
                            // No rating on Talqyn's side is what taking it back asked for — a repeat
                            // of a withdrawal that did go through answers exactly this.
                        }
                    }
                }
                confirmedFeedback[turnId] = wanted
            } catch (e: CancellationException) {
                // Closed meanwhile: there is no screen left to show where the rating ended up.
                throw e
            } catch (e: Exception) {
                val failure = TalqynException.wrap(e)
                // A shopper who changed meanwhile is no refusal to report: the rating goes back
                // quietly, as it does on the twin SDK.
                putBack(turnId, confirmed, failure.takeUnless { it is TalqynException.IdentityChanged })
                return
            }
        }
    }

    /** A turn dropped meanwhile — a reset, a retry — has nothing left to put back. */
    private fun putBack(turnId: UUID, confirmed: FeedbackState, failure: TalqynException?) {
        if (mutableState.value.assistantTurn(turnId) == null) return
        replaceAssistant(turnId) { it.copy(rating = confirmed.rating, feedbackReasons = confirmed.reasons) }
        if (failure != null) mutableState.update { it.copy(feedbackFailure = failure) }
    }

    // endregion

    // region Events

    /** Reports a tap on a product card of a turn, so the click has a denominator. Fire and forget. */
    public fun trackProductTap(product: TalqynProduct, turn: TalqynAssistantTurn) {
        val position = turn.products.indexOfFirst { it.talqynId == product.talqynId }.coerceAtLeast(0)
        talqyn.events.track(
            TalqynProductClickEvent(
                searchId = turn.searchId,
                talqynId = product.talqynId,
                position = position,
                source = TalqynEventSource.Consultant,
            ),
        )
    }

    // endregion

    // region History

    /**
     * Loads a conversation from history in place of the current one.
     *
     * Ignored while an answer is streaming, and once the conversation is closed. A failure lands
     * in [TalqynConversationState.restoreFailure] — [TalqynException.NotFound] when the chat was
     * deleted meanwhile, [TalqynException.IdentityChanged] when the shopper changed while it loaded.
     */
    public fun restore(sessionId: String) {
        if (mutableState.value.isStreaming || !work.isActive) return
        restoreJob?.cancel()
        mutableState.update { it.copy(restoreFailure = null, isRestoring = true) }
        restoreJob = work.launch {
            try {
                val transcript = talqyn.consultant.chat(sessionId)
                apply(transcript)
            } catch (e: CancellationException) {
                // This job's own cancellation — another restore, a reset — leaves the state to
                // whoever cancelled it.
                ensureActive()
                // One from below ends the restore like any failure: left alone, the spinner would
                // stay up for ever.
                mutableState.update { it.copy(isRestoring = false, restoreFailure = TalqynException.Transport(e)) }
            } catch (e: Exception) {
                mutableState.update { it.copy(isRestoring = false, restoreFailure = TalqynException.wrap(e)) }
            }
        }
    }

    /** Clears the transcript if it is the conversation that was just deleted from history. */
    public fun discardIfOpen(sessionId: String) {
        if (mutableState.value.sessionId == sessionId) reset()
    }

    private fun apply(transcript: TalqynChatTranscript) {
        val products = LinkedHashMap<Long, TalqynProduct>()
        for (product in transcript.products) products.putIfAbsent(product.talqynId, product)
        val turns = turns(transcript.messages, products, transcript.sessionId)
        // A rating reopened from history is one Talqyn holds: changing it is an update, taking
        // it back a withdrawal. The reasons are not sent back with a transcript, so a reason
        // picked now is new to Talqyn.
        confirmedFeedback.clear()
        for (turn in turns) {
            if (turn is TalqynAssistantTurn && turn.rating != null) {
                confirmedFeedback[turn.id] = FeedbackState(turn.rating, emptyList())
            }
        }
        cancelPendingDelta()
        mutableState.update {
            it.copy(
                turns = turns,
                isRestoring = false,
                productsById = products,
                clarifyDrafts = emptyMap(),
                sessionId = transcript.sessionId,
            )
        }
    }

    // endregion

    // region Identity

    /**
     * Drops the transcript if the shopper changed since the screen last appeared: their
     * conversation must not continue under someone else's history. Call it when the screen
     * appears.
     *
     * Compares the identity as the app set it, not the shopper id: the id reads as a local
     * UUID before the first token and as the server's form after it, and a screen coming back
     * from a product page must not mistake that for a different shopper.
     */
    public suspend fun refreshIdentity() {
        val current = talqyn.currentIdentity()
        val previous = identitySnapshot
        if (previous == null) {
            identitySnapshot = current
            return
        }
        if (previous == current || mutableState.value.isStreaming) return
        identitySnapshot = current
        reset()
    }

    // endregion

    internal companion object {
        /** How long deltas are batched before they reach the view. Rendering on every token is wasted work: text arrives faster than it is read. */
        const val DELTA_FLUSH_INTERVAL_MILLIS = 80L

        /** Trimmed, deduplicated case-insensitively, at most [limit] of them. */
        fun sanitizedFollowUps(items: List<String>, limit: Int = TalqynConversationLimits.MAX_FOLLOW_UPS): List<String> {
            if (limit <= 0) return emptyList()
            val seen = HashSet<String>()
            val result = ArrayList<String>()
            for (item in items) {
                val question = item.trim()
                if (question.isEmpty() || !seen.add(question.lowercase(Locale.ROOT))) continue
                result.add(question)
                if (result.size == limit) break
            }
            return result
        }

        fun productIndex(turns: List<TalqynTurn>): Map<Long, TalqynProduct> {
            val index = LinkedHashMap<Long, TalqynProduct>()
            for (turn in turns) {
                if (turn is TalqynAssistantTurn) turn.products.forEach { index[it.talqynId] = it }
            }
            return index
        }

        /**
         * Pairs each shopper message with the assistant message that follows it. An assistant
         * message on its own — the first row of a chat that started mid-way — becomes a turn
         * with an empty question.
         */
        fun turns(
            messages: List<TalqynChatMessage>,
            products: Map<Long, TalqynProduct>,
            sessionId: String? = null,
        ): List<TalqynTurn> {
            val turns = ArrayList<TalqynTurn>()
            var index = 0
            while (index < messages.size) {
                val message = messages[index]
                index += 1
                if (message.role != TalqynChatMessage.Role.User) {
                    turns.add(restoredTurn(question = null, answer = message, products = products, sessionId = sessionId))
                    continue
                }
                turns.add(TalqynUserTurn(text = message.text))
                var answer: TalqynChatMessage? = null
                if (index < messages.size && messages[index].role == TalqynChatMessage.Role.Assistant) {
                    answer = messages[index]
                    index += 1
                }
                turns.add(restoredTurn(question = message, answer = answer, products = products, sessionId = sessionId))
            }
            return turns
        }

        private fun restoredTurn(
            question: TalqynChatMessage?,
            answer: TalqynChatMessage?,
            products: Map<Long, TalqynProduct>,
            sessionId: String?,
        ): TalqynAssistantTurn {
            val isRedirect = question?.isRedirect == true
            // Both rows of a turn carry its id and its rating; either will do when the other is missing.
            val talqynTurnId = answer?.turnId ?: question?.turnId
            return TalqynAssistantTurn(
                question = question?.text ?: "",
                stage = null,
                redirectQuery = if (isRedirect) answer?.text else null,
                text = if (isRedirect) "" else answer?.text ?: "",
                // A card is shown once, however many times the row names it.
                products = answer?.talqynIds.orEmpty().distinct().mapNotNull { products[it] },
                talqynTurnId = talqynTurnId,
                sessionId = if (talqynTurnId == null) null else sessionId,
                rating = (answer?.feedback ?: question?.feedback)?.let(TalqynAnswerRating::from),
            )
        }
    }
}
