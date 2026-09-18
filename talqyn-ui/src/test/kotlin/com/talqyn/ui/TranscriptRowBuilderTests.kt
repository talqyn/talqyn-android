package com.talqyn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.talqyn.consultant.TalqynAnswerBlock
import com.talqyn.consultant.TalqynAssistantTurn
import com.talqyn.consultant.TalqynClarifyDraft
import com.talqyn.consultant.TalqynConversationState
import com.talqyn.consultant.TalqynPriceFormatter
import com.talqyn.consultant.TalqynTurn
import com.talqyn.consultant.TalqynUiStrings
import com.talqyn.consultant.TalqynUserTurn
import com.talqyn.sdk.TalqynActionFilters
import com.talqyn.sdk.TalqynClarify
import com.talqyn.sdk.TalqynComparisonTable
import com.talqyn.sdk.TalqynConsultantAction
import com.talqyn.sdk.TalqynException
import com.talqyn.sdk.TalqynFallbackReason
import com.talqyn.sdk.TalqynFeedbackReason
import com.talqyn.sdk.TalqynProduct
import com.talqyn.sdk.TalqynProductGroup
import java.io.IOException
import java.util.UUID

/** What a turn of the transcript shows, built from the conversation's state alone. */
class TranscriptRowBuilderTests {
    private val strings = TalqynUiStrings.En
    private val price = TalqynPriceFormatter.Tenge
    private val builder = TalqynTranscriptRowBuilder(strings, price)

    private fun product(id: Long, brand: String? = null, price: Double? = null) =
        TalqynProduct(talqynId = id, title = "Product $id", brandName = brand, price = price)

    /** A turn that has finished: no stage, the way a done event leaves it. */
    private fun settled(question: String = "what do you recommend?") = TalqynAssistantTurn(question = question, stage = null)

    private fun row(
        turn: TalqynAssistantTurn,
        after: List<TalqynTurn> = emptyList(),
        isStreaming: Boolean = false,
        dismissed: Set<UUID> = emptySet(),
        drafts: Map<UUID, TalqynClarifyDraft> = emptyMap(),
    ): TalqynTurnRow {
        val state = TalqynConversationState(
            turns = listOf(TalqynUserTurn(text = turn.question), turn) + after,
            isStreaming = isStreaming,
            productsById = turn.products.associateBy { it.talqynId },
            clarifyDrafts = drafts,
        )
        return builder.turnRow(turn, state, dismissed)
    }

    // region Notices

    @Test
    fun aStoppedTurnSaysSoAndOffersRetryWhenLatest() {
        val turn = settled().copy(text = "Taking", wasStopped = true)
        val notice = row(turn).notice
        assertEquals(TalqynTurnNotice(TalqynNoticeTone.Neutral, "Answer stopped", showsRetry = true), notice)
        assertEquals(false, row(turn, after = listOf(settled("more"))).notice?.showsRetry)
    }

    @Test
    fun aServerErrorIsWordedByItsCodeAndAClientFailureGenerically() {
        val server = row(settled().copy(errorCode = "retrieval_failed")).notice
        assertEquals(TalqynNoticeTone.Error, server?.tone)
        assertEquals("Could not find any products, please try again", server?.text)
        assertEquals(strings.errorGeneric, row(settled().copy(errorCode = "something_new")).notice?.text)

        val client = row(settled().copy(failure = TalqynException.Transport(IOException("offline")))).notice
        assertEquals(strings.errorGeneric, client?.text)
        assertEquals(true, client?.showsRetry)
    }

    @Test
    fun aFallbackPointsAtProductsOnlyWhenThereAreSome() {
        val bare = settled().copy(fallbackReason = TalqynFallbackReason.BudgetExceeded)
        assertEquals("The consultant is unavailable right now", row(bare).notice?.text)
        assertEquals(TalqynNoticeTone.Warning, row(bare).notice?.tone)

        val withProducts = bare.copy(products = listOf(product(1)))
        assertEquals("The consultant is unavailable right now, but here is what matches", row(withProducts).notice?.text)

        val withGroups = bare.copy(groups = listOf(TalqynProductGroup("sofa", listOf(product(2)))))
        assertEquals("The consultant is unavailable right now, but here is what matches", row(withGroups).notice?.text)
    }

    @Test
    fun aFallbackOffersRetryOnlyWhereItCouldWork() {
        fun retry(reason: String) = row(settled().copy(fallbackReason = TalqynFallbackReason(reason))).notice?.showsRetry
        assertEquals(false, retry("user_budget_exceeded"))
        assertEquals(false, retry("budget_exceeded"))
        assertEquals(false, retry("turn_budget"))
        assertEquals(false, retry("refusal"))
        assertEquals(true, retry("timeout"))
        assertEquals(true, retry("circuit_open"))
        assertEquals("an unknown reason gets the benefit of the doubt", true, retry("brand_new"))
        assertEquals("the suffix after a colon does not change the verdict", false, retry("user_budget_exceeded:device"))
    }

    @Test
    fun anOrdinaryAnswerHasNoNotice() {
        assertNull(row(settled().copy(text = "Take Acer.")).notice)
    }

    // endregion

    // region Toolbar

    @Test
    fun anAnswerIsRatedAndCopied() {
        val toolbar = row(settled().copy(text = "Take **Acer**.")).toolbar
        assertNotNull(toolbar)
        assertEquals("Take Acer.", toolbar?.copyText)
        assertEquals(TalqynTranscriptRowBuilder.ANSWER_REASONS, toolbar?.offeredReasons)
    }

    @Test
    fun aFallbackIsRatedWithItsOwnReasonsButNotCopied() {
        val toolbar = row(settled().copy(fallbackReason = TalqynFallbackReason.Timeout, products = listOf(product(1)))).toolbar
        assertNotNull(toolbar)
        assertNull(toolbar?.copyText)
        assertEquals(TalqynFeedbackReason.NoAnswer, toolbar?.offeredReasons?.first())
    }

    @Test
    fun theToolbarCarriesTheRatingAsTheTurnHoldsIt() {
        val turn = settled().copy(
            text = "Answer",
            rating = com.talqyn.consultant.TalqynAnswerRating.NotHelpful,
            feedbackReasons = listOf(TalqynFeedbackReason.WrongInfo),
        )
        val toolbar = row(turn).toolbar
        assertEquals(com.talqyn.consultant.TalqynAnswerRating.NotHelpful, toolbar?.rating)
        assertEquals(listOf(TalqynFeedbackReason.WrongInfo), toolbar?.selectedReasons)
    }

    @Test
    fun nothingIsRatedWhileStreamingOrWhereThereIsNoAnswer() {
        assertNull("a half-written answer", row(settled().copy(text = "Tak"), isStreaming = true).toolbar)
        assertNull("a clarification", row(settled().copy(clarify = TalqynClarify("clarify", emptyList()))).toolbar)
        assertNull("a redirect", row(settled().copy(redirectQuery = "iphone")).toolbar)
        assertNull("a failure", row(settled().copy(text = "Taking", errorCode = "internal_error")).toolbar)
        assertNull("a stopped turn", row(settled().copy(text = "Taking", wasStopped = true)).toolbar)
        assertNull("nothing to copy and no fallback", row(settled()).toolbar)
    }

    @Test
    fun anOlderTurnKeepsItsToolbarWhileANewerOneStreams() {
        val older = settled().copy(text = "Answer")
        val streaming = TalqynAssistantTurn(question = "more")
        assertNotNull(row(older, after = listOf(streaming), isStreaming = true).toolbar)
    }

    // endregion

    // region Products

    @Test
    fun theCarouselLeavesOutTheProductsTheTextCited() {
        val turn = settled().copy(text = "Take [p:1].", products = listOf(product(1), product(2)))
        val built = row(turn)
        val cited = built.blocks.filterIsInstance<TalqynAnswerBlock.Products>().flatMap { block -> block.items.map { it.talqynId } }
        assertEquals(listOf(1L), cited)
        assertEquals(listOf(TalqynProductSection("Also worth a look", listOf(product(2)))), built.productSections)
    }

    @Test
    fun fallbackProductsGetTheirOwnHeader() {
        val turn = settled().copy(fallbackReason = TalqynFallbackReason.BudgetExceeded, products = listOf(product(1)))
        assertEquals(listOf("What turned up for your request"), row(turn).productSections.map { it.title })
    }

    @Test
    fun groupsAreTitledByRoleAndCount() {
        val turn = settled().copy(
            text = "Sofa [p:1] will do.",
            products = listOf(product(1), product(2), product(3)),
            groups = listOf(
                TalqynProductGroup("sofa", listOf(product(1))),
                TalqynProductGroup("side table", listOf(product(2), product(3))),
            ),
        )
        assertEquals("a group whose products were all cited is left out", listOf("Side table · 2"), row(turn).productSections.map { it.title })
    }

    /** A carousel is keyed by product: a product listed twice must not bring it down. */
    @Test
    fun aProductListedTwiceIsShownOnce() {
        fun ids(turn: TalqynAssistantTurn) = row(turn).productSections.map { section -> section.products.map { it.talqynId } }
        val turn = settled().copy(text = "See", products = listOf(product(1), product(2), product(1)))
        assertEquals(listOf(listOf(1L, 2L)), ids(turn))
        assertEquals(listOf(listOf(3L)), ids(turn.copy(groups = listOf(TalqynProductGroup("sofa", listOf(product(3), product(3)))))))
    }

    @Test
    fun theCarouselWaitsForTheTurnToSettle() {
        val turn = TalqynAssistantTurn(question = "q", text = "See", products = listOf(product(1)))
        val streaming = row(turn, isStreaming = true)
        assertTrue(streaming.productSections.isEmpty())
        assertTrue(streaming.isStreaming)
        assertNotNull(streaming.stage)
    }

    // endregion

    // region Actions

    @Test
    fun filtersAreSummarizedInTheScreensCopy() {
        fun summary(filters: TalqynActionFilters) = filters.summary(strings, price)
        assertEquals("${price.format(100000.0)} – ${price.format(300000.0)}", summary(TalqynActionFilters(priceMin = 100000.0, priceMax = 300000.0)))
        assertEquals("up to ${price.format(300000.0)}", summary(TalqynActionFilters(priceMax = 300000.0)))
        assertEquals("from ${price.format(100000.0)}", summary(TalqynActionFilters(priceMin = 100000.0)))
        assertEquals(
            "from ${price.format(100000.0)} · on sale · Apple · Samsung",
            summary(TalqynActionFilters(priceMin = 100000.0, hasDiscount = true, filters = mapOf("brand" to listOf("Samsung", "Apple")))),
        )
        assertEquals("the legacy flat form, for a turn that carries only it", "Apple", summary(TalqynActionFilters(attributes = mapOf("brand" to "Apple"))))
        assertEquals(
            "the structured form wins over the legacy one",
            "Samsung",
            summary(TalqynActionFilters(filters = mapOf("brand" to listOf("Samsung")), attributes = mapOf("brand" to "Apple"))),
        )
    }

    @Test
    fun aBarePercentInReplacedCopyStaysAPercentSign() {
        val copy = strings.copy(filterUpTo = "up to %@ (10% off)")
        assertEquals("up to ${price.format(5.0)} (10% off)", TalqynActionFilters(priceMax = 5.0).summary(copy, price))
    }

    @Test
    fun aComparisonIsSummarizedByBrandsOrByCount() {
        val table = TalqynComparisonTable(listOf(1, 2), listOf("A", "B"), listOf(TalqynComparisonTable.Row("Price", listOf("1", "2"))))
        val brands = settled().copy(
            text = "Compared",
            products = listOf(product(1, brand = "Samsung"), product(2, brand = "Apple")),
            actions = listOf(TalqynConsultantAction.ShowComparison(table)),
        )
        assertEquals(listOf(TalqynTurnAction.Comparison(table, "Samsung · Apple")), row(brands).actions)

        val sameBrand = brands.copy(products = listOf(product(1, brand = "Apple"), product(2, brand = "Apple")))
        assertEquals(listOf(TalqynTurnAction.Comparison(table, "2 products")), row(sameBrand).actions)

        val unknownBrand = brands.copy(products = listOf(product(1, brand = "Apple"), product(2)))
        assertEquals(listOf(TalqynTurnAction.Comparison(table, "2 products")), row(unknownBrand).actions)
    }

    @Test
    fun aTableNotWorthAScreenAndAnUnknownActionAreDropped() {
        val oneColumn = TalqynComparisonTable(listOf(1), listOf("A"), listOf(TalqynComparisonTable.Row("Price", listOf("1"))))
        val noRows = TalqynComparisonTable(listOf(1, 2), listOf("A", "B"), emptyList())
        val turn = settled().copy(
            text = "Answer",
            actions = listOf(
                TalqynConsultantAction.ShowComparison(oneColumn),
                TalqynConsultantAction.ShowComparison(noRows),
                TalqynConsultantAction.Unknown("open_map"),
                TalqynConsultantAction.ApplyFilters(TalqynActionFilters(hasDiscount = true)),
            ),
        )
        assertEquals(listOf(TalqynTurnAction.Filters(TalqynActionFilters(hasDiscount = true), "on sale")), row(turn).actions)
        assertFalse(oneColumn.isRenderable)
        assertFalse(noRows.isRenderable)
    }

    // endregion

    // region Clarify

    private val clarify = TalqynClarify(
        "Clarify",
        listOf(TalqynClarify.Question(id = "budget", label = "Budget?", options = listOf("under 300k"))),
    )

    @Test
    fun theLatestQuestionIsNotDrawnWhileItsTurnStreams() {
        val turn = TalqynAssistantTurn(question = "recommend something", clarify = clarify)
        assertNull(row(turn, isStreaming = true).clarify)
        assertNull("even once its sheet was dismissed", row(turn, isStreaming = true, dismissed = setOf(turn.id)).clarify)
    }

    @Test
    fun theLatestQuestionIsASheetFirstAndACardOnceTheSheetIsDismissed() {
        val turn = settled().copy(clarify = clarify)
        assertNull("the sheet asks it", row(turn).clarify)

        val draft = TalqynClarifyDraft(selected = mapOf("budget" to listOf("under 300k")))
        val card = row(turn, dismissed = setOf(turn.id), drafts = mapOf(turn.id to draft)).clarify
        assertEquals(TalqynTurnClarify.Pending(clarify, draft, isInteractive = true), card)
    }

    @Test
    fun anOlderQuestionIsACardThatTakesNoInput() {
        val turn = settled().copy(clarify = clarify)
        assertEquals(
            TalqynTurnClarify.Pending(clarify, TalqynClarifyDraft(), isInteractive = false),
            row(turn, after = listOf(settled("more"))).clarify,
        )
        assertEquals(
            "a newer turn streaming does not hide it",
            TalqynTurnClarify.Pending(clarify, TalqynClarifyDraft(), isInteractive = false),
            row(turn, after = listOf(TalqynAssistantTurn(question = "more")), isStreaming = true).clarify,
        )
    }

    @Test
    fun anAnsweredQuestionShowsTheAnswer() {
        val turn = settled().copy(clarify = clarify, clarifyAnswer = "Budget: under 300k.")
        assertEquals(TalqynTurnClarify.Answered("Budget: under 300k."), row(turn).clarify)
        assertEquals(TalqynTurnClarify.Answered("Budget: under 300k."), row(turn, isStreaming = true).clarify)
    }

    // endregion

    // region Rows

    @Test
    fun theExamplesAreOfferedUnderTheFirstAnswerLeavingOutTheOneAsked() {
        val asked = strings.exampleQuestions.first()
        val turn = settled(asked).copy(text = "Answer")
        val state = TalqynConversationState(turns = listOf(TalqynUserTurn(text = asked), turn))
        val rows = builder.rows(state, emptySet())
        assertEquals(3, rows.size)
        assertEquals(TalqynTranscriptRow.Suggestions(strings.exampleQuestions.drop(1)), rows.last())
        assertEquals(TalqynTranscriptRow.SUGGESTIONS_KEY, rows.last().key)
    }

    @Test
    fun aFallbackTurnOffersNoSuggestions() {
        val turn = settled().copy(fallbackReason = TalqynFallbackReason.Timeout, followUps = listOf("anything cheaper?"))
        val state = TalqynConversationState(turns = listOf(TalqynUserTurn(text = "q"), turn))
        assertTrue(builder.rows(state, emptySet()).none { it is TalqynTranscriptRow.Suggestions })
    }

    @Test
    fun rowsAreKeyedByTheirTurns() {
        val user = TalqynUserTurn(text = "q")
        val turn = settled("q").copy(text = "Answer", followUps = listOf("anything else?"))
        val rows = builder.rows(TalqynConversationState(turns = listOf(user, turn)), emptySet())
        assertEquals(listOf<Any>(user.id, turn.id, TalqynTranscriptRow.SUGGESTIONS_KEY), rows.map { it.key })
        assertEquals(TalqynTranscriptRow.Suggestions(listOf("anything else?")), rows.last())
    }

    // endregion
}
