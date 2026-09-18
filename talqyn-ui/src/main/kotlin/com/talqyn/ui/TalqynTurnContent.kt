package com.talqyn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.talqyn.consultant.TalqynAnswerBlock
import com.talqyn.consultant.TalqynAnswerRating
import com.talqyn.consultant.TalqynClarifyDraft
import com.talqyn.sdk.TalqynActionFilters
import com.talqyn.sdk.TalqynComparisonTable
import com.talqyn.sdk.TalqynFeedbackReason
import com.talqyn.sdk.TalqynProduct
import java.util.UUID

/** What a turn's views report back to the screen. */
@Stable
internal class TalqynTurnActions(
    val onProduct: (TalqynProduct, UUID) -> Unit,
    val onProductMention: (Long, UUID) -> Unit,
    val onRetry: (UUID) -> Unit,
    val onRedirect: (String) -> Unit,
    val onFilters: (TalqynActionFilters, String) -> Unit,
    val onComparison: (TalqynComparisonTable) -> Unit,
    val onSubmitClarify: (String, UUID) -> Unit,
    val onClarifyDraft: (TalqynClarifyDraft, UUID) -> Unit,
    /** A turn's clarify draft as the conversation holds it at the moment of asking, rather than as last composed. */
    val currentClarifyDraft: (UUID) -> TalqynClarifyDraft,
    val onSuggestion: (String) -> Unit,
    val onRate: (TalqynAnswerRating?, List<TalqynFeedbackReason>, UUID) -> Unit,
    val onEditQuestion: (String) -> Unit,
)

/**
 * One assistant turn: status, the answer with inline product cards, the products carousel,
 * proposed actions, the clarify card, notices, and the rate-and-copy toolbar once the turn has
 * settled.
 *
 * Keyed by block, so while the answer streams a paragraph keeps its identity and only its text
 * changes: the shopper's selection holds.
 */
@Composable
internal fun TalqynAssistantTurnView(row: TalqynTurnRow, actions: TalqynTurnActions) {
    val talqyn = LocalTalqyn.current
    val margin = talqyn.metrics.horizontalMargin
    val onProduct: (TalqynProduct) -> Unit = { actions.onProduct(it, row.turnId) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (row.stage != null || row.blocks.isNotEmpty()) {
            Column(Modifier.padding(horizontal = margin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                row.stage?.let { TalqynStatusLine(it) }
                if (row.blocks.isNotEmpty()) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.blocks.forEach { block ->
                                key(block.id) {
                                    when (block) {
                                        is TalqynAnswerBlock.Paragraph ->
                                            TalqynAnswerParagraph(block.lines, onProduct = { id -> actions.onProductMention(id, row.turnId) })
                                        // A card is a button rather than text: a long press on it
                                        // must not start selecting its title and price.
                                        is TalqynAnswerBlock.Products -> DisableSelection { TalqynCitedCards(block.items, onProduct) }
                                    }
                                }
                            }
                        }
                    }
                    if (row.isStreaming) TalqynTypingIndicator()
                }
            }
        }

        if (row.productSections.isNotEmpty()) {
            TalqynProductSections(row.productSections, onProduct)
        }

        val hasFollowUp = row.actions.isNotEmpty() || row.clarify != null || row.redirectQuery != null ||
            row.notice != null || row.toolbar != null
        if (hasFollowUp) {
            Column(Modifier.padding(horizontal = margin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                row.actions.forEach { action ->
                    when (action) {
                        is TalqynTurnAction.Filters -> TalqynActionChip(talqyn.icons.filters, talqyn.strings.applyFilters, action.summary) {
                            actions.onFilters(action.filters, row.question)
                        }
                        is TalqynTurnAction.Comparison -> TalqynActionChip(talqyn.icons.comparison, talqyn.strings.openComparison, action.summary) {
                            actions.onComparison(action.table)
                        }
                    }
                }
                when (val clarify = row.clarify) {
                    is TalqynTurnClarify.Pending -> TalqynClarifyCard(
                        clarify = clarify.clarify,
                        draft = clarify.draft,
                        currentDraft = { actions.currentClarifyDraft(row.turnId) },
                        isInteractive = clarify.isInteractive,
                        onDraftChange = { actions.onClarifyDraft(it, row.turnId) },
                        onSubmit = { actions.onSubmitClarify(it, row.turnId) },
                    )
                    is TalqynTurnClarify.Answered -> TalqynClarifyAnswered(clarify.answer)
                    null -> Unit
                }
                row.redirectQuery?.let { query -> TalqynRedirectNotice { actions.onRedirect(query) } }
                row.notice?.let { notice -> TalqynNotice(notice) { actions.onRetry(row.turnId) } }
                row.toolbar?.let { toolbar ->
                    TalqynAnswerToolbar(toolbar) { rating, reasons -> actions.onRate(rating, reasons, row.turnId) }
                }
            }
        }
    }
}
