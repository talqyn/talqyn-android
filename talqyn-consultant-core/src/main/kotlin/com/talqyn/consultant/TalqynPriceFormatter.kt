package com.talqyn.consultant

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** How prices are written on cards and in filter summaries. */
public fun interface TalqynPriceFormatter {
    /** Formats a price. */
    public fun format(price: Double): String

    public companion object {
        /**
         * `449 990 ₸`: grouped by thousands with a no-break space, no fraction unless the price
         * has one.
         */
        @JvmField
        public val Tenge: TalqynPriceFormatter = TalqynPriceFormatter { value -> "${GroupedNumbers.format(value)} ₸" }
    }
}

/**
 * The number formats a price needs, one pair per thread: a price is written on every card of
 * every re-render while an answer streams, and `DecimalFormat` is neither cheap to build nor
 * safe to share.
 */
private object GroupedNumbers {
    private val formats = object : ThreadLocal<Pair<DecimalFormat, DecimalFormat>>() {
        override fun initialValue(): Pair<DecimalFormat, DecimalFormat> = make(0) to make(2)
    }

    fun format(value: Double): String {
        val (whole, fractional) = formats.get() ?: return value.toString()
        return (if (value == Math.rint(value)) whole else fractional).format(value)
    }

    private fun make(fractionDigits: Int): DecimalFormat {
        val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
        symbols.groupingSeparator = ' '
        return DecimalFormat("#,##0", symbols).apply {
            isGroupingUsed = true
            minimumFractionDigits = 0
            maximumFractionDigits = fractionDigits
        }
    }
}
