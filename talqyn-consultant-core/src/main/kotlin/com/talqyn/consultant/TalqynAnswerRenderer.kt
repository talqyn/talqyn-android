package com.talqyn.consultant

import com.talqyn.sdk.TalqynProduct

/**
 * A run of answer text: bold or not, and either prose or the name of a product the
 * consultant cited.
 *
 * @property productId The [TalqynProduct.talqynId] of the product this run names, when the
 *   run stands where a `[p:ID]` marker was. `null` for prose.
 */
public data class TalqynTextRun(
    val text: String,
    val isBold: Boolean = false,
    val productId: Long? = null,
) {
    /** Whether the run is a product's name put in place of a marker. */
    public val isProduct: Boolean get() = productId != null
}

/**
 * One line of an answer paragraph.
 *
 * @property id The line's position in its paragraph.
 * @property kind How the line is set.
 * @property runs The line's text.
 */
public data class TalqynAnswerLine(
    val id: Int,
    val kind: Kind,
    val runs: List<TalqynTextRun>,
) {
    /** The line as plain text, for accessibility and tests. */
    public val plainText: String get() = runs.joinToString("") { it.text }

    /** How a line is set. */
    public sealed interface Kind {
        public data object Plain : Kind

        public data object Heading : Kind

        public data object Bullet : Kind

        /** A numbered item, with its number as written. */
        public data class Numbered(val number: String) : Kind
    }
}

/** A piece of a rendered answer: a paragraph, or the product cards cited by the sentence just before them. */
public sealed interface TalqynAnswerBlock {
    /** Stable across re-renders of a growing answer, so a view can diff blocks. */
    public val id: Int

    public data class Paragraph(override val id: Int, val lines: List<TalqynAnswerLine>) : TalqynAnswerBlock

    public data class Products(override val id: Int, val items: List<TalqynProduct>) : TalqynAnswerBlock
}

/**
 * Turns answer text into blocks a view can lay out.
 *
 * The consultant writes light Markdown — `**bold**`, `# headings`, `-` bullets, `1.` lists —
 * and cites products inline as `[p:ID]`. The marker stands where the product's name would
 * be, so it is replaced by the title (as a [TalqynTextRun] carrying the product id) and the
 * product card is pulled out of the text and placed right after the sentence that mentioned
 * it, so the card sits next to the claim rather than in a pile at the end. Markers for
 * products the turn does not have are dropped silently, and a marker still being typed
 * (`[p:12`) is hidden until it completes.
 *
 * Public so a storefront with its own screen renders answers the same way.
 */
public object TalqynAnswerRenderer {
    // The patterns are written for two engines: the JVM's in tests and ICU's on a device. ICU
    // refuses inline flags such as `(?U)` and the two disagree on what `\s` covers, so classes
    // are spelled out and every bracket is escaped.

    // The trailing-marker pattern catches "[", "[p", "[p:", "[p:12" at the end of a stream
    // chunk: the rest of the marker is still on its way.
    private val trailingPartialMarker = Regex("""\[(?:p(?::[0-9]*)?)?$""")
    private val markerPattern = Regex("""[ \t]*\[p:([0-9]+)\]""")

    // The same marker without the whitespace before it: a marker that turns into a name
    // keeps the space that separated it from the word before.
    private val bareMarkerPattern = Regex("""\[p:([0-9]+)\]""")
    private val boldPattern = Regex("""\*\*(.+?)\*\*""")
    private val headingPattern = Regex("""^[ \t]{0,3}#{1,6}[ \t]+""")
    private val bulletPattern = Regex("""^[ \t]{0,3}[-*•][ \t]+""")
    private val numberedPattern = Regex("""^[ \t]{0,3}([0-9]{1,2})[.)][ \t]+""")

    // A sentence ends before whitespace of any kind — a no-break space in "300 000 ₸." included.
    private val sentenceEndPattern = Regex("""[.!?…;]+[")»”’\]]*(?=[\s\p{Z}]|$)""")

    /**
     * Renders answer text.
     *
     * @param text The answer so far, markers included.
     * @param products The products the markers may refer to, by Talqyn id.
     * @return Paragraphs and product blocks in reading order. Block ids are stable across
     *   re-renders of a growing answer, so a view can diff them.
     */
    @JvmStatic
    public fun blocks(text: String, products: Map<Long, TalqynProduct>): List<TalqynAnswerBlock> {
        val blocks = ArrayList<TalqynAnswerBlock>()
        val pendingLines = ArrayList<String>()
        val shownIds = HashSet<Long>()
        var slot = 0

        fun flushText() {
            val joined = pendingLines.joinToString("\n").trim()
            pendingLines.clear()
            if (joined.isEmpty()) return
            blocks.add(TalqynAnswerBlock.Paragraph(slot * 2, renderLines(joined, products)))
        }

        // A CR LF line end is one line end. Split on LF alone it would leave a CR at the end of
        // every line — in the text, and on a blank line that must end its paragraph.
        for (line in stripTrailingPartialMarker(text.replace("\r\n", "\n")).split("\n")) {
            if (line.isBlank()) {
                if (pendingLines.isNotEmpty()) {
                    flushText()
                    slot += 1
                }
                continue
            }

            var buffer = ""
            for (segment in splitByCitations(line, products)) {
                buffer = listOf(buffer, segment.text).filter { it.isNotEmpty() }.joinToString(" ")
                val items = segment.citedIds.filter { it !in shownIds }.mapNotNull { products[it] }
                if (items.isEmpty()) continue
                items.forEach { shownIds.add(it.talqynId) }
                pendingLines.add(buffer)
                buffer = ""
                flushText()
                blocks.add(TalqynAnswerBlock.Products(slot * 2 + 1, items))
                slot += 1
            }
            if (buffer.isNotEmpty()) pendingLines.add(buffer)
        }

        flushText()
        return blocks
    }

    /** Removes every `[p:ID]` marker and the whitespace before it, keeping the text. */
    @JvmStatic
    public fun stripped(text: String): String =
        markerPattern.replace(stripTrailingPartialMarker(text), "").trimEnd(' ', '\t')

    /**
     * The answer as plain text, with product names in place of the markers and lists written
     * out — for the clipboard, or a share sheet.
     *
     * @return Paragraphs separated by blank lines, bullets as `• `, numbered items as `1. `.
     *   The product cards are not written out: the sentences that cited them already name them.
     */
    @JvmStatic
    public fun plainText(text: String, products: Map<Long, TalqynProduct>): String = plainText(blocks(text, products))

    /** The paragraphs of rendered blocks as plain text; see the other [plainText]. */
    @JvmStatic
    public fun plainText(blocks: List<TalqynAnswerBlock>): String =
        blocks.filterIsInstance<TalqynAnswerBlock.Paragraph>().joinToString("\n\n") { paragraph ->
            paragraph.lines.joinToString("\n") { line ->
                when (val kind = line.kind) {
                    TalqynAnswerLine.Kind.Plain, TalqynAnswerLine.Kind.Heading -> line.plainText
                    TalqynAnswerLine.Kind.Bullet -> "• " + line.plainText
                    is TalqynAnswerLine.Kind.Numbered -> "${kind.number}. " + line.plainText
                }
            }
        }

    /** What a marker turns into: the product's title, or its brand when the title is empty. `null` when there is nothing to show. */
    private fun name(product: TalqynProduct?): String? {
        if (product == null) return null
        return listOf(product.title, product.brandName.orEmpty()).map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }

    // region Citations

    private class CitationSegment(val text: String, val citedIds: List<Long>)

    /**
     * Splits a line at the end of every sentence that cites a product, so the cards can be
     * placed right after it.
     *
     * A marker that resolves to a product stays in the segment's text, to be replaced by the
     * product's name when the runs are built; one that does not is dropped together with the
     * whitespace before it.
     */
    private fun splitByCitations(line: String, products: Map<Long, TalqynProduct>): List<CitationSegment> {
        val matches = markerPattern.findAll(line).toList()
        if (matches.isEmpty()) return listOf(CitationSegment(line, emptyList()))

        // A list item is one unit: its cards go after the whole item, not after the first
        // period inside it.
        val splitsBySentence = parseLine(line).first == TalqynAnswerLine.Kind.Plain

        val segments = ArrayList<CitationSegment>()
        val cleaned = StringBuilder()
        val citedIds = ArrayList<Long>()
        var cursor = 0

        for ((index, match) in matches.withIndex()) {
            cleaned.append(line, cursor, match.range.first)
            cursor = match.range.last + 1

            val id = asciiLong(match.groupValues[1])
            if (id != null && name(products[id]) != null) cleaned.append(match.value)
            if (id != null && products[id] != null && id !in citedIds) citedIds.add(id)
            if (citedIds.isEmpty()) continue

            val sentenceEnd = if (splitsBySentence) sentenceEndLocation(line, cursor) else line.length
            val nextMarker = if (index + 1 < matches.size) matches[index + 1].range.first else line.length
            if (nextMarker < sentenceEnd) continue

            cleaned.append(line, cursor, sentenceEnd)
            cursor = sentenceEnd
            segments.add(CitationSegment(trimmed(cleaned.toString(), keepingIndent = segments.isEmpty()), citedIds.toList()))
            cleaned.setLength(0)
            citedIds.clear()
        }

        cleaned.append(line, cursor, line.length)
        val tail = trimmed(cleaned.toString(), keepingIndent = segments.isEmpty())
        if (tail.isNotEmpty() || citedIds.isNotEmpty()) {
            segments.add(CitationSegment(tail, citedIds.toList()))
        }
        return segments
    }

    private fun sentenceEndLocation(line: String, from: Int): Int =
        sentenceEndPattern.find(line, from)?.let { it.range.last + 1 } ?: line.length

    private fun trimmed(text: String, keepingIndent: Boolean): String {
        val value = text.trimEnd()
        return if (keepingIndent) value else value.trimStart()
    }

    // endregion

    // region Markdown

    private fun parseLine(line: String): Pair<TalqynAnswerLine.Kind, String> {
        headingPattern.find(line)?.let { return TalqynAnswerLine.Kind.Heading to line.substring(it.range.last + 1) }
        bulletPattern.find(line)?.let { return TalqynAnswerLine.Kind.Bullet to line.substring(it.range.last + 1) }
        numberedPattern.find(line)?.let {
            return TalqynAnswerLine.Kind.Numbered(it.groupValues[1]) to line.substring(it.range.last + 1)
        }
        return TalqynAnswerLine.Kind.Plain to line
    }

    private fun renderLines(text: String, products: Map<Long, TalqynProduct>): List<TalqynAnswerLine> =
        text.split("\n").mapIndexed { index, line ->
            val (kind, content) = parseLine(line)
            TalqynAnswerLine(index, kind, runs(content, products))
        }

    /** `**bold**` becomes a bold run and a `[p:ID]` marker becomes the product's name; everything else stays regular. */
    private fun runs(text: String, products: Map<Long, TalqynProduct>): List<TalqynTextRun> {
        val result = ArrayList<TalqynTextRun>()
        var cursor = 0
        for (match in boldPattern.findAll(text)) {
            if (match.range.first > cursor) {
                result += namedRuns(text.substring(cursor, match.range.first), isBold = false, products = products)
            }
            result += namedRuns(match.groupValues[1], isBold = true, products = products)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) result += namedRuns(text.substring(cursor), isBold = false, products = products)
        return result
    }

    /** Splits a run at its markers, putting the product's name where each marker was. A marker with no name to show is dropped. */
    private fun namedRuns(text: String, isBold: Boolean, products: Map<Long, TalqynProduct>): List<TalqynTextRun> {
        val result = ArrayList<TalqynTextRun>()
        var cursor = 0
        for (match in bareMarkerPattern.findAll(text)) {
            if (match.range.first > cursor) {
                result.add(TalqynTextRun(text.substring(cursor, match.range.first), isBold))
            }
            val id = asciiLong(match.groupValues[1])
            val name = id?.let { name(products[it]) }
            if (id != null && name != null) result.add(TalqynTextRun(name, isBold, productId = id))
            cursor = match.range.last + 1
        }
        if (cursor < text.length) result.add(TalqynTextRun(text.substring(cursor), isBold))
        return result
    }

    private fun stripTrailingPartialMarker(text: String): String =
        trailingPartialMarker.find(text)?.let { text.substring(0, it.range.first) } ?: text

    /** Digits outside ASCII match `\d` but are no id. */
    private fun asciiLong(raw: String): Long? = if (raw.all { it in '0'..'9' }) raw.toLongOrNull() else null

    // endregion
}
