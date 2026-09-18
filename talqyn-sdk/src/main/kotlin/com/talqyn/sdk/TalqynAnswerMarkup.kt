package com.talqyn.sdk

/**
 * Product markers inside consultant text.
 *
 * Text arriving in [TalqynConsultantEvent.Delta] may contain inline markers of the form
 * `[p:1234]`, where the number is a [TalqynProduct.talqynId] from a `products` event
 * already delivered in the same turn. The server strips markers pointing at products
 * outside the turn's results before they leave, so any marker reaching a client is valid
 * and resolves to an element of [TalqynConsultantProducts.items].
 *
 * Render one as a link or a chip to the product — or drop it with [stripped] if inline
 * mentions are not part of your design.
 *
 * ```kotlin
 * for (segment in TalqynAnswerMarkup.segments(text)) {
 *     when (segment) {
 *         is TalqynAnswerMarkup.Segment.Text -> append(segment.text)
 *         is TalqynAnswerMarkup.Segment.Product -> appendChip(segment.talqynId)
 *     }
 * }
 * ```
 */
public object TalqynAnswerMarkup {
    /** A run of answer text, or a product mentioned inside it. */
    public sealed interface Segment {
        /** Plain text, with no markers left in it. */
        public data class Text(val text: String) : Segment

        /** A product mention, by its [TalqynProduct.talqynId]. */
        public data class Product(val talqynId: Long) : Segment
    }

    // `[0-9]`, not `\d`: digits outside ASCII would match and then fail to parse. The closing
    // bracket is escaped for ICU, the engine on a device, which is stricter than the JVM's.
    private val pattern = Regex("""\[p:([0-9]+)\]""")

    /**
     * Splits answer text into text runs and product mentions.
     *
     * Anything that looks like a marker but does not parse — a number too large for a
     * `Long` — stays as text.
     *
     * @return The segments in order. Empty for empty input; a single [Segment.Text] when
     *   there are no markers.
     */
    @JvmStatic
    public fun segments(text: String): List<Segment> {
        if (text.isEmpty()) return emptyList()
        val segments = ArrayList<Segment>()
        // Adjacent text runs merge, so a marker kept as text does not split the
        // sentence around it into three segments.
        fun appendText(run: String) {
            if (run.isEmpty()) return
            val last = segments.lastOrNull()
            if (last is Segment.Text) {
                segments[segments.size - 1] = Segment.Text(last.text + run)
            } else {
                segments.add(Segment.Text(run))
            }
        }

        var cursor = 0
        for (match in pattern.findAll(text)) {
            if (match.range.first > cursor) appendText(text.substring(cursor, match.range.first))
            val id = match.groupValues[1].toLongOrNull()
            if (id != null) segments.add(Segment.Product(id)) else appendText(match.value)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) appendText(text.substring(cursor))
        return segments
    }

    /**
     * Removes every product marker from answer text. Built on [segments], so the two agree
     * on what counts as a marker. Surrounding spacing is left untouched.
     */
    @JvmStatic
    public fun stripped(text: String): String =
        segments(text).filterIsInstance<Segment.Text>().joinToString("") { it.text }

    /** The [TalqynProduct.talqynId] values mentioned in answer text, in order of appearance, repeats included. */
    @JvmStatic
    public fun mentionedProductIds(text: String): List<Long> =
        segments(text).filterIsInstance<Segment.Product>().map { it.talqynId }
}
