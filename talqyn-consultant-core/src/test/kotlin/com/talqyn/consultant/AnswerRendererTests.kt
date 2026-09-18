package com.talqyn.consultant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.talqyn.sdk.TalqynClarify
import com.talqyn.sdk.TalqynFallbackReason
import com.talqyn.sdk.TalqynFeedbackReason
import com.talqyn.sdk.TalqynProduct
import java.util.Locale

class AnswerRendererTests {
    private val products = mapOf(
        1L to TalqynProduct(talqynId = 1, title = "Acer"),
        2L to TalqynProduct(talqynId = 2, title = "Lenovo"),
    )

    private fun paragraphs(blocks: List<TalqynAnswerBlock>): List<List<String>> =
        blocks.filterIsInstance<TalqynAnswerBlock.Paragraph>().map { paragraph -> paragraph.lines.map { it.plainText } }

    @Test
    fun citationCardsFollowTheSentenceThatCitesThem() {
        val blocks = TalqynAnswerRenderer.blocks("Take [p:1]. It is quieter. Or [p:2] — pricier.", products)
        assertEquals("expected paragraph, cards, paragraph, cards: $blocks", 4, blocks.size)
        val first = blocks[0] as TalqynAnswerBlock.Paragraph
        val cited = blocks[1] as TalqynAnswerBlock.Products
        val second = blocks[2] as TalqynAnswerBlock.Paragraph
        val cited2 = blocks[3] as TalqynAnswerBlock.Products
        assertEquals(listOf("Take Acer."), first.lines.map { it.plainText })
        assertEquals(listOf(1L), cited.items.map { it.talqynId })
        assertEquals(listOf("It is quieter. Or Lenovo — pricier."), second.lines.map { it.plainText })
        assertEquals(listOf(2L), cited2.items.map { it.talqynId })
    }

    @Test
    fun aMarkerBecomesTheProductNameInItsOwnRun() {
        val blocks = TalqynAnswerRenderer.blocks("The [p:2] has a quieter fan than the [p:1].", products)
        val lines = (blocks.first() as TalqynAnswerBlock.Paragraph).lines
        assertEquals(
            listOf(
                TalqynTextRun("The "),
                TalqynTextRun("Lenovo", productId = 2),
                TalqynTextRun(" has a quieter fan than the "),
                TalqynTextRun("Acer", productId = 1),
                TalqynTextRun("."),
            ),
            lines[0].runs,
        )
    }

    @Test
    fun aNameInsideBoldStaysBold() {
        val blocks = TalqynAnswerRenderer.blocks("**Pick: [p:1]** — and that is that.", products)
        val lines = (blocks.first() as TalqynAnswerBlock.Paragraph).lines
        assertEquals(
            listOf(
                TalqynTextRun("Pick: ", isBold = true),
                TalqynTextRun("Acer", isBold = true, productId = 1),
                TalqynTextRun(" — and that is that."),
            ),
            lines[0].runs,
        )
    }

    @Test
    fun aNamelessProductFallsBackToItsBrand() {
        val catalog = mapOf(
            7L to TalqynProduct(talqynId = 7, title = "  ", brandName = "Asus"),
            8L to TalqynProduct(talqynId = 8, title = ""),
        )
        val blocks = TalqynAnswerRenderer.blocks("Compare [p:7] and [p:8].", catalog)
        assertEquals("no title, no brand: the marker is dropped", listOf(listOf("Compare Asus and.")), paragraphs(blocks))
        val items = (blocks.last() as TalqynAnswerBlock.Products).items
        assertEquals("a product with nothing to call it still gets its card", listOf(7L, 8L), items.map { it.talqynId })
    }

    @Test
    fun aListItemIsCitedAsAWhole() {
        val blocks = TalqynAnswerRenderer.blocks("1. Pick [p:1]. Fast SSD.\n2. Or [p:2].", products)
        val lines = (blocks[0] as TalqynAnswerBlock.Paragraph).lines
        val cards = blocks[1] as TalqynAnswerBlock.Products
        assertEquals(1, lines.size)
        assertEquals(TalqynAnswerLine.Kind.Numbered("1"), lines[0].kind)
        assertEquals("the whole item stays together above its card", "Pick Acer. Fast SSD.", lines[0].plainText)
        assertEquals(listOf(1L), cards.items.map { it.talqynId })
    }

    @Test
    fun markdownLineKindsAndBoldRuns() {
        val blocks = TalqynAnswerRenderer.blocks("# Summary\nPlain **bold** line\n- item\n* another item\n3) third", emptyMap())
        assertEquals("expected one paragraph: $blocks", 1, blocks.size)
        val lines = (blocks.first() as TalqynAnswerBlock.Paragraph).lines
        assertEquals(
            listOf(
                TalqynAnswerLine.Kind.Heading,
                TalqynAnswerLine.Kind.Plain,
                TalqynAnswerLine.Kind.Bullet,
                TalqynAnswerLine.Kind.Bullet,
                TalqynAnswerLine.Kind.Numbered("3"),
            ),
            lines.map { it.kind },
        )
        assertEquals("Summary", lines[0].plainText)
        assertEquals(
            listOf(TalqynTextRun("Plain "), TalqynTextRun("bold", isBold = true), TalqynTextRun(" line")),
            lines[1].runs,
        )
        assertEquals("third", lines[4].plainText)
    }

    @Test
    fun blankLinesSplitParagraphsWithStableIds() {
        val blocks = TalqynAnswerRenderer.blocks("First paragraph.\n\nSecond paragraph.", emptyMap())
        assertEquals(listOf(0, 2), blocks.map { it.id })
        assertEquals(listOf(listOf("First paragraph."), listOf("Second paragraph.")), paragraphs(blocks))
    }

    @Test
    fun unknownMarkersAreDroppedAndRepeatedOnesNamedAgain() {
        val blocks = TalqynAnswerRenderer.blocks("See [p:42] and [p:1], then again [p:1].", products)
        val cards = blocks.filterIsInstance<TalqynAnswerBlock.Products>().map { block -> block.items.map { it.talqynId } }
        assertEquals("an unknown product vanishes, a repeat is shown once", listOf(listOf(1L)), cards)
        assertEquals(
            "the unknown marker leaves no trace, the repeat is named without a second card",
            "See and Acer, then again Acer.",
            paragraphs(blocks).flatten().joinToString("|"),
        )
    }

    /** A CR LF line end is one line end: no CR stays in a line, and a blank CR LF line ends its paragraph. */
    @Test
    fun crlfLineEndsAreLineEnds() {
        val blocks = TalqynAnswerRenderer.blocks("First paragraph\r\n\r\n- item\r\nsecond", emptyMap())
        assertEquals(listOf(listOf("First paragraph"), listOf("item", "second")), paragraphs(blocks))
    }

    @Test
    fun aMarkerStillBeingTypedIsHidden() {
        for (partial in listOf("Take [", "Take [p", "Take [p:", "Take [p:12")) {
            val blocks = TalqynAnswerRenderer.blocks(partial, products)
            assertEquals(partial, listOf(listOf("Take")), paragraphs(blocks))
        }
    }

    @Test
    fun strippedRemovesMarkers() {
        assertEquals("Take or. More", TalqynAnswerRenderer.stripped("Take [p:1] or [p:2]. More [p:"))
    }

    @Test
    fun plainTextNamesProductsAndWritesListsOut() {
        val text = "# Summary\nTake [p:1].\n\n- quieter\n- cheaper [p:2]\n1. first\n2) second"
        assertEquals(
            "a blank line stands where the cited cards were",
            "Summary\nTake Acer.\n\n• quieter\n• cheaper Lenovo\n\n1. first\n2. second",
            TalqynAnswerRenderer.plainText(text, products),
        )
        assertEquals("", TalqynAnswerRenderer.plainText("", products))
    }

    @Test
    fun emptyTextRendersNothing() {
        assertTrue(TalqynAnswerRenderer.blocks("", products).isEmpty())
        assertTrue(TalqynAnswerRenderer.blocks("\n\n  \n", products).isEmpty())
    }
}

class UiStringsTests {
    @Test
    fun russianProductsCountPicksThePluralForm() {
        val ru = TalqynUiStrings.Ru
        assertEquals(
            listOf("1 товар", "2 товара", "4 товара", "5 товаров", "11 товаров", "14 товаров", "21 товар", "22 товара", "25 товаров", "111 товаров"),
            listOf(1, 2, 4, 5, 11, 14, 21, 22, 25, 111).map(ru::productsCount),
        )
    }

    /** English is the only two-form set: the rule has to read the second form, which the other two never exercise. */
    @Test
    fun englishProductsCountPicksTheTwoForms() {
        val en = TalqynUiStrings.En
        assertEquals(
            listOf("1 product", "2 products", "5 products", "11 products", "21 products", "111 products"),
            listOf(1, 2, 5, 11, 21, 111).map(en::productsCount),
        )
    }

    @Test
    fun kazakhProductsCountHasOneForm() {
        val kk = TalqynUiStrings.Kk
        assertEquals(listOf("1 тауар", "2 тауар", "5 тауар"), listOf(1, 2, 5).map(kk::productsCount))
    }

    /** Every copy set labels every reason the API accepts: a reason with no label is silently not offered. */
    @Test
    fun everyFeedbackReasonHasALabel() {
        val reasons = listOf(
            TalqynFeedbackReason.NotRelevant,
            TalqynFeedbackReason.WrongInfo,
            TalqynFeedbackReason.TooManyQuestions,
            TalqynFeedbackReason.NoAnswer,
            TalqynFeedbackReason.PriceStock,
            TalqynFeedbackReason.Other,
        )
        for (strings in listOf(TalqynUiStrings.En, TalqynUiStrings.Ru, TalqynUiStrings.Kk)) {
            for (reason in reasons) {
                assertNotNull("${reason.rawValue} has no label", strings.feedbackReasonText(reason))
            }
        }
    }

    /** The shopper's own limit clears in hours, the account's does not: the two must not read the same, and neither promises "today". */
    @Test
    fun budgetFallbacksSayDifferentThings() {
        for (strings in listOf(TalqynUiStrings.En, TalqynUiStrings.Ru, TalqynUiStrings.Kk)) {
            val own = strings.fallbackText(TalqynFallbackReason.UserBudgetExceeded)
            val account = strings.fallbackText(TalqynFallbackReason.BudgetExceeded)
            assertNotEquals(own, account)
            val lowered = own.lowercase(Locale.ROOT)
            assertFalse(lowered.contains("today") || lowered.contains("сегодня") || lowered.contains("бүгін"))
        }
        assertEquals(
            "a suffix after the colon is detail",
            TalqynUiStrings.Ru.fallbackText(TalqynFallbackReason.Timeout),
            TalqynUiStrings.Ru.fallbackText(TalqynFallbackReason("timeout:llm")),
        )
    }

    /** A repeat would meet the same wall after a spent budget, a turn that ran out, or a refusal — and may well work after a timeout. */
    @Test
    fun whichFallbacksInviteARetry() {
        assertFalse(TalqynFallbackReason.UserBudgetExceeded.invitesRetry)
        assertFalse(TalqynFallbackReason.BudgetExceeded.invitesRetry)
        assertFalse(TalqynFallbackReason.TurnBudget.invitesRetry)
        assertFalse(TalqynFallbackReason("refusal:policy").invitesRetry)
        assertTrue(TalqynFallbackReason.Timeout.invitesRetry)
        assertTrue(TalqynFallbackReason("something_new").invitesRetry)
    }

    /** A price is written on every card of every re-render; the shared formatter must write the same thing from any thread. */
    @Test
    fun tengeFormatsWholeAndFractionalPrices() = runBlocking {
        val tenge = TalqynPriceFormatter.Tenge
        assertEquals("449\u00A0990\u00A0₸", tenge.format(449_990.0))
        val results = (0 until 50).map { async(Dispatchers.Default) { tenge.format(1_000.0) } }.awaitAll().toSet()
        assertEquals(setOf("1\u00A0000\u00A0₸"), results)
    }
}

class ClarifyDraftTests {
    private val budget = TalqynClarify.Question(id = "budget", label = "What is the budget?", multi = false, options = listOf("under 150k", "under 300k"))
    private val use = TalqynClarify.Question(id = "use", label = "What for? ", multi = true, options = listOf("school", "games"))

    @Test
    fun singleChoiceReplacesAndMultiChoiceAccumulates() {
        var draft = TalqynClarifyDraft()
        draft = draft.toggle("under 150k", budget)
        draft = draft.toggle("under 300k", budget)
        assertEquals(listOf("under 300k"), draft.selected["budget"])
        draft = draft.toggle("under 300k", budget)
        assertNull("toggling the selection off clears it", draft.selected["budget"])

        draft = draft.toggle("school", use)
        draft = draft.toggle("games", use)
        assertEquals(listOf("school", "games"), draft.selected["use"])
        draft = draft.toggle("school", use)
        assertEquals(listOf("games"), draft.selected["use"])
        assertTrue(draft.isSelected("games", use))
    }

    @Test
    fun answerRestatesLabelsWithoutQuestionMarks() {
        var draft = TalqynClarifyDraft()
        assertTrue(draft.isEmpty)
        assertEquals("", draft.answer(listOf(budget, use)))

        draft = draft.toggle("under 300k", budget).toggle("school", use).toggle("games", use)
        assertEquals("What is the budget: under 300k. What for: school, games.", draft.answer(listOf(budget, use)))

        draft = draft.copy(custom = "  and it should be quiet  ")
        assertEquals("What is the budget: under 300k. What for: school, games. and it should be quiet", draft.answer(listOf(budget, use)))

        val onlyText = TalqynClarifyDraft(custom = "any")
        assertEquals("any", onlyText.answer(listOf(budget)))
        assertFalse(onlyText.isEmpty)
    }
}
