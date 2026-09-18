package com.talqyn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.talqyn.sdk.TalqynComparisonTable
import com.talqyn.sdk.TalqynProduct

/** The comparison table as the screen lays it out. */
class ComparisonModelTests {
    private val samsung = TalqynProduct(talqynId = 1, title = "Samsung Galaxy S25", price = 449990.0)
    private val apple = TalqynProduct(talqynId = 2, title = "Apple iPhone 16", price = 529990.0)
    private val products = mapOf(1L to samsung, 2L to apple)

    private fun row(label: String, vararg values: String?) = TalqynComparisonTable.Row(label, values.toList())

    private fun table(vararg rows: TalqynComparisonTable.Row, ids: List<Long> = listOf(1, 2)) =
        TalqynComparisonTable(ids, listOf("Galaxy", "iPhone"), rows.toList())

    @Test
    fun columnsAreTitledByTheProductWhenTheTurnHasIt() {
        val model = TalqynComparisonModel.make(table(row("Price", "1", "2"), ids = listOf(1, 99)), products)
        assertEquals(listOf("Samsung Galaxy S25", "iPhone"), model.columns.map { it.title })
        assertEquals(samsung, model.columns[0].product)
        assertNull(model.columns[1].product)
    }

    @Test
    fun aPriceRowIsRecognizedByItsValuesInAnyLanguage() {
        val model = TalqynComparisonModel.make(
            table(row("Price", "449990", "529990"), row("Бағасы", " 449990.0 ", "529990"), row("Brand", "Samsung", "Apple")),
            products,
        )
        assertEquals(listOf(true, true, false), model.rows.map { it.isPrice })
    }

    @Test
    fun aNumberThatIsNotThePriceOrAValueThatIsNoNumberRefutesIt() {
        val columns = TalqynComparisonModel.make(table(), products).columns
        assertFalse("a number that is not the price", TalqynComparisonModel.isPriceRow(row("Price", "449990", "1"), columns))
        assertFalse("a value that is no number", TalqynComparisonModel.isPriceRow(row("Price", "449990", "pricey"), columns))
        assertFalse("storage in gigabytes happens to be numbers", TalqynComparisonModel.isPriceRow(row("Storage", "256", "256"), columns))
    }

    @Test
    fun aColumnWithoutAKnownPriceNeitherConfirmsNorRefutes() {
        val unknown = TalqynComparisonModel.make(table(ids = listOf(1, 99)), products).columns
        assertTrue(TalqynComparisonModel.isPriceRow(row("Price", "449990", "123"), unknown))
        assertTrue("a missing value is skipped", TalqynComparisonModel.isPriceRow(row("Price", "449990", null), unknown))

        val none = TalqynComparisonModel.make(table(ids = listOf(98, 99)), products).columns
        assertFalse("nothing matched is no price row", TalqynComparisonModel.isPriceRow(row("Price", "1", "2"), none))
    }

    @Test
    fun onlyDifferencesHidesTheRowsWhereEveryProductSaysTheSame() {
        val model = TalqynComparisonModel.make(
            table(row("Brand", "Samsung", "Apple"), row("Storage", "256 GB", "256 GB"), row("NFC", null, ""), row("Color", "black", null)),
            products,
        )
        assertTrue(model.hasDifferences)
        assertEquals(listOf("Brand", "Color"), model.keepingOnlyDifferences().rows.map { it.row.label })
        assertEquals("the columns stay", model.columns, model.keepingOnlyDifferences().columns)
    }

    @Test
    fun aTableWhereEverythingMatchesHasNoDifferencesToShow() {
        val model = TalqynComparisonModel.make(table(row("Storage", "256 GB", "256 GB")), products)
        assertFalse(model.hasDifferences)
        assertTrue(model.keepingOnlyDifferences().rows.isEmpty())
    }
}
