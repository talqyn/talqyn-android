package com.talqyn.ui

import androidx.compose.runtime.Immutable
import com.talqyn.consultant.TalqynAnswerBlock
import com.talqyn.consultant.TalqynAnswerRating
import com.talqyn.consultant.TalqynAnswerRenderer
import com.talqyn.consultant.TalqynAssistantTurn
import com.talqyn.consultant.TalqynClarifyDraft
import com.talqyn.consultant.TalqynConversationState
import com.talqyn.consultant.TalqynPriceFormatter
import com.talqyn.consultant.TalqynUiStrings
import com.talqyn.consultant.TalqynUserTurn
import com.talqyn.consultant.invitesRetry
import com.talqyn.sdk.TalqynActionFilters
import com.talqyn.sdk.TalqynClarify
import com.talqyn.sdk.TalqynComparisonTable
import com.talqyn.sdk.TalqynConsultantAction
import com.talqyn.sdk.TalqynConsultantStage
import com.talqyn.sdk.TalqynFeedbackReason
import com.talqyn.sdk.TalqynProduct
import java.util.UUID

/** What a row of the transcript shows. Built from the conversation by [TalqynTranscriptRowBuilder]. */
@Immutable
internal sealed interface TalqynTranscriptRow {
    val key: Any

    data class User(val id: UUID, val text: String) : TalqynTranscriptRow {
        override val key: Any get() = id
    }

    data class Assistant(val row: TalqynTurnRow) : TalqynTranscriptRow {
        override val key: Any get() = row.turnId
    }

    data class Suggestions(val questions: List<String>) : TalqynTranscriptRow {
        override val key: Any get() = SUGGESTIONS_KEY
    }

    companion object {
        const val SUGGESTIONS_KEY = "talqyn.suggestions"
    }
}

/** How the transcript should scroll after a change of its rows. */
internal enum class TalqynTranscriptAnchor {
    /** Keep the current position — and the pinned question, if any. */
    Keep,

    /** Pin the newest question to the top. */
    NewestTurn,

    /** Go to the end. */
    Bottom,
}

/** A proposed action with its one-line summary already written. */
@Immutable
internal sealed interface TalqynTurnAction {
    data class Filters(val filters: TalqynActionFilters, val summary: String) : TalqynTurnAction

    data class Comparison(val table: TalqynComparisonTable, val summary: String) : TalqynTurnAction
}

@Immutable
internal sealed interface TalqynTurnClarify {
    data class Pending(val clarify: TalqynClarify, val draft: TalqynClarifyDraft, val isInteractive: Boolean) : TalqynTurnClarify

    data class Answered(val answer: String) : TalqynTurnClarify
}

/** The controls under a settled turn: its rating, the reasons a thumb down offers, and the text to copy. */
@Immutable
internal data class TalqynTurnToolbar(
    val rating: TalqynAnswerRating?,
    val offeredReasons: List<TalqynFeedbackReason>,
    val selectedReasons: List<TalqynFeedbackReason>,
    /** `null` when the turn has no text worth copying — a fallback. */
    val copyText: String?,
)

internal enum class TalqynNoticeTone { Warning, Error, Neutral }

@Immutable
internal data class TalqynTurnNotice(val tone: TalqynNoticeTone, val text: String, val showsRetry: Boolean)

@Immutable
internal data class TalqynTurnRow(
    val turnId: UUID,
    val question: String,
    val stage: TalqynConsultantStage?,
    val isStreaming: Boolean,
    val blocks: List<TalqynAnswerBlock>,
    val productSections: List<TalqynProductSection>,
    val actions: List<TalqynTurnAction>,
    val clarify: TalqynTurnClarify?,
    val redirectQuery: String?,
    val notice: TalqynTurnNotice?,
    val toolbar: TalqynTurnToolbar?,
)

/**
 * Turns the conversation into rows, with the rendered answer cached per turn: the regex pass
 * over a long answer is not free, and a streaming turn renders again many times a second.
 */
internal class TalqynTranscriptRowBuilder(
    private val strings: TalqynUiStrings,
    private val price: TalqynPriceFormatter,
) {
    private class CacheEntry(
        val text: String,
        val productsCount: Int,
        val catalogCount: Int,
        val blocks: List<TalqynAnswerBlock>,
        val copyText: String,
    )

    private val cache = HashMap<UUID, CacheEntry>()

    fun forget(turnIds: Collection<UUID>) {
        turnIds.forEach(cache::remove)
    }

    fun forgetAll() {
        cache.clear()
    }

    /**
     * @param dismissedClarifyTurns Turns whose clarify sheet the shopper dismissed, so the card
     *   shows inline instead.
     */
    fun rows(state: TalqynConversationState, dismissedClarifyTurns: Set<UUID>): List<TalqynTranscriptRow> {
        val live = state.turns.mapTo(HashSet()) { it.id }
        cache.keys.retainAll(live)
        val rows = state.turns.map { turn ->
            when (turn) {
                is TalqynUserTurn -> TalqynTranscriptRow.User(turn.id, turn.text)
                is TalqynAssistantTurn -> TalqynTranscriptRow.Assistant(turnRow(turn, state, dismissedClarifyTurns))
            }
        }
        val questions = state.suggestedQuestions(strings.exampleQuestions)
        return if (questions.isEmpty()) rows else rows + TalqynTranscriptRow.Suggestions(questions)
    }

    fun turnRow(turn: TalqynAssistantTurn, state: TalqynConversationState, dismissedClarifyTurns: Set<UUID>): TalqynTurnRow {
        val isLast = state.isLast(turn)
        val isActive = isLast && state.isStreaming
        // Cards cited by the text render as the text streams — a card takes its place the
        // moment its marker is complete, the way the words do. Only the carousel of the
        // remaining products waits for the end: its contents depend on which products the text
        // ends up citing.
        val rendered = renderedBlocks(turn, state.productsById)
        val citedIds = rendered.blocks.filterIsInstance<TalqynAnswerBlock.Products>()
            .flatMapTo(HashSet()) { block -> block.items.map { it.talqynId } }

        return TalqynTurnRow(
            turnId = turn.id,
            question = turn.question,
            stage = if (isActive) turn.stage else null,
            isStreaming = isActive,
            blocks = rendered.blocks,
            productSections = if (isActive) emptyList() else productSections(turn, citedIds),
            actions = turn.actions.mapNotNull { action(it, state.productsById) },
            clarify = clarifyRow(turn, state, isLast, dismissedClarifyTurns),
            redirectQuery = turn.redirectQuery,
            notice = notice(turn, isLast),
            toolbar = if (isActive) null else toolbar(turn, rendered.copyText),
        )
    }

    /**
     * Rating waits for the turn to settle: a half-written answer is neither judged nor copied.
     * An answer is rated and copied; a turn that gave up is rated only — "no answer" is exactly
     * what a shopper may want to say about it.
     */
    private fun toolbar(turn: TalqynAssistantTurn, copyText: String): TalqynTurnToolbar? {
        if (!turn.isAnswer) return null
        val isFallback = turn.fallbackReason != null
        if (!isFallback && copyText.isEmpty()) return null
        return TalqynTurnToolbar(
            rating = turn.rating,
            offeredReasons = if (isFallback) FALLBACK_REASONS else ANSWER_REASONS,
            selectedReasons = turn.feedbackReasons,
            copyText = copyText.ifEmpty { null },
        )
    }

    private class Rendered(val blocks: List<TalqynAnswerBlock>, val copyText: String)

    private fun renderedBlocks(turn: TalqynAssistantTurn, catalog: Map<Long, TalqynProduct>): Rendered {
        cache[turn.id]?.let { entry ->
            if (entry.text == turn.text && entry.productsCount == turn.products.size && entry.catalogCount == catalog.size) {
                return Rendered(entry.blocks, entry.copyText)
            }
        }
        val blocks = TalqynAnswerRenderer.blocks(turn.text, catalog)
        val copyText = TalqynAnswerRenderer.plainText(blocks)
        cache[turn.id] = CacheEntry(turn.text, turn.products.size, catalog.size, blocks, copyText)
        return Rendered(blocks, copyText)
    }

    private fun productSections(turn: TalqynAssistantTurn, citedIds: Set<Long>): List<TalqynProductSection> {
        // On a turn the consultant gave up on, these products are not an afterthought under an
        // answer — they are the answer.
        val header = if (turn.fallbackReason == null) strings.productsHeader else strings.fallbackProductsHeader
        // A carousel is keyed by product: one listed twice — in a group, or in the turn — is shown
        // once, or the second key would bring the carousel down.
        val groups = turn.groups
        if (!groups.isNullOrEmpty()) {
            return groups.mapNotNull { group ->
                val items = group.items.filter { it.talqynId !in citedIds }.distinctBy { it.talqynId }
                if (items.isEmpty()) return@mapNotNull null
                val label = group.role.take(1).uppercase() + group.role.drop(1)
                TalqynProductSection("$label · ${items.size}", items)
            }
        }
        val items = turn.products.filter { it.talqynId !in citedIds }.distinctBy { it.talqynId }
        return if (items.isEmpty()) emptyList() else listOf(TalqynProductSection(header, items))
    }

    private fun action(action: TalqynConsultantAction, catalog: Map<Long, TalqynProduct>): TalqynTurnAction? = when (action) {
        is TalqynConsultantAction.ApplyFilters -> TalqynTurnAction.Filters(action.filters, action.filters.summary(strings, price))
        is TalqynConsultantAction.ShowComparison ->
            if (action.table.isRenderable) TalqynTurnAction.Comparison(action.table, comparisonSummary(action.table, catalog)) else null
        is TalqynConsultantAction.Unknown -> null
    }

    /**
     * What is being compared, in a line: the brands when they tell the products apart,
     * otherwise how many. The full titles are on the cards right above the chip.
     */
    private fun comparisonSummary(table: TalqynComparisonTable, catalog: Map<Long, TalqynProduct>): String {
        val brands = table.talqynIds.map { catalog[it]?.brandName?.trim().orEmpty() }
        if (brands.all { it.isNotEmpty() } && brands.toSet().size == brands.size) return brands.joinToString(" · ")
        return strings.productsCount(table.talqynIds.size)
    }

    private fun clarifyRow(
        turn: TalqynAssistantTurn,
        state: TalqynConversationState,
        isLast: Boolean,
        dismissed: Set<UUID>,
    ): TalqynTurnClarify? {
        val clarify = turn.clarify ?: return null
        turn.clarifyAnswer?.let { return TalqynTurnClarify.Answered(it) }
        // While the turn that asks is still streaming there is nothing to answer yet: the question
        // comes up — as a sheet or as a card — once the turn is done, not as a greyed-out card first.
        if (isLast && state.isStreaming) return null
        val isInteractive = isLast
        // The latest question is asked in a sheet first; the inline card takes over once the
        // sheet was dismissed, or when the turn is no longer the latest.
        if (isInteractive && turn.id !in dismissed) return null
        return TalqynTurnClarify.Pending(clarify, state.clarifyDrafts[turn.id] ?: TalqynClarifyDraft(), isInteractive)
    }

    private fun notice(turn: TalqynAssistantTurn, isLast: Boolean): TalqynTurnNotice? {
        if (turn.wasStopped) return TalqynTurnNotice(TalqynNoticeTone.Neutral, strings.aborted, showsRetry = isLast)
        turn.errorCode?.let { return TalqynTurnNotice(TalqynNoticeTone.Error, strings.errorText(it), showsRetry = isLast) }
        if (turn.failure != null) return TalqynTurnNotice(TalqynNoticeTone.Error, strings.errorGeneric, showsRetry = isLast)
        val reason = turn.fallbackReason ?: return null
        // The line points at the products only when the turn found some: a fallback with
        // nothing to show must not promise a carousel.
        val cause = strings.fallbackText(reason)
        val hasProducts = turn.products.isNotEmpty() || !turn.groups.isNullOrEmpty()
        return TalqynTurnNotice(
            tone = TalqynNoticeTone.Warning,
            text = if (hasProducts) strings.fill(strings.fallbackWithProducts, cause) else cause,
            showsRetry = isLast && reason.invitesRetry,
        )
    }

    companion object {
        /** What a thumb down offers under an answer: the parts of an answer that can be wrong. */
        val ANSWER_REASONS = listOf(
            TalqynFeedbackReason.NotRelevant,
            TalqynFeedbackReason.WrongInfo,
            TalqynFeedbackReason.PriceStock,
            TalqynFeedbackReason.Other,
        )

        /** What it offers under a turn that gave up: the missing answer comes first, and the products that did come can still miss. */
        val FALLBACK_REASONS = listOf(
            TalqynFeedbackReason.NoAnswer,
            TalqynFeedbackReason.NotRelevant,
            TalqynFeedbackReason.Other,
        )
    }
}

/**
 * The filters in a line, in the screen's copy: the price bound, "with a discount", the values —
 * `from 100 000 ₸ · with a discount · Apple`.
 *
 * The bounds are filled by replacement, not `String.format`: the copy is the app's to change,
 * and a bare `%` in it must stay a percent sign.
 */
internal fun TalqynActionFilters.summary(strings: TalqynUiStrings, price: TalqynPriceFormatter): String {
    val parts = ArrayList<String>()
    val min = priceMin
    val max = priceMax
    when {
        min != null && max != null -> parts.add("${price.format(min)} – ${price.format(max)}")
        max != null -> parts.add(strings.fill(strings.filterUpTo, price.format(max)))
        min != null -> parts.add(strings.fill(strings.filterFrom, price.format(min)))
    }
    if (hasDiscount) parts.add(strings.filterDiscount)
    // The structured form first; the flat legacy form for turns that carry only it.
    val values = if (filters.isEmpty()) attributes.values.toList() else filters.values.flatten()
    parts.addAll(values.sorted())
    return parts.joinToString(" · ")
}

/** A table needs two columns and a row to be worth a screen. */
internal val TalqynComparisonTable.isRenderable: Boolean
    get() = talqynIds.size >= 2 && rows.isNotEmpty()
