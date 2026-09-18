package com.talqyn.sdk

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import kotlin.math.abs

/** A JSON document the SDK could not read or write. */
internal class TalqynJsonException(message: String) : Exception(message)

/**
 * The SDK's JSON, both ways, with no library behind it.
 *
 * `org.json` ships inside Android, but its objects forget the order keys were
 * put in, and a library of the SDK's choosing would be a version conflict in
 * somebody else's app. The contract is small enough for a reader and a writer
 * of its own: values come out as [Map], [List], [String], [Long], [Double],
 * [Boolean], or `null`, and maps keep their order — a mint body is signed byte
 * for byte, and a field name should be readable next to the model rather than
 * follow from a serializer setting.
 *
 * The reader accepts what Foundation's reader on the twin SDK accepts, no more and
 * no less: a body one platform renders and the other rejects is a bug report nobody
 * can reproduce.
 */
internal object TalqynJson {
    /** Deeper than any response of the contract; a bound, so a hostile body cannot overflow the stack. */
    private const val MAX_DEPTH = 256

    fun parse(text: String): Any? = Reader(text).document()

    fun parse(bytes: ByteArray): Any? = parse(bytes.toString(Charsets.UTF_8))

    fun parseObject(bytes: ByteArray): JsonObject = parse(bytes).asJsonObject()

    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun encodeToBytes(value: Any?): ByteArray = encode(value).toByteArray(Charsets.UTF_8)

    // region Writing

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value)
            is Double -> writeNumber(out, value)
            is Float -> writeNumber(out, value.toDouble())
            is Number -> writeNumber(out, value.toDouble())
            is String -> writeString(out, value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, element) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key as? String ?: throw TalqynJsonException("an object key is not a string"))
                    out.append(':')
                    write(out, element)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (element in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, element)
                }
                out.append(']')
            }
            else -> throw TalqynJsonException("cannot encode a ${value.javaClass.simpleName}")
        }
    }

    /** A whole number is written without a fraction: `300000`, not `300000.0`. */
    private fun writeNumber(out: StringBuilder, value: Double) {
        if (!value.isFinite()) throw TalqynJsonException("cannot encode the non-finite number $value")
        if (value == Math.rint(value) && abs(value) < 1e15) {
            out.append(value.toLong())
        } else {
            out.append(value)
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (char < ' ') {
                    out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
            }
        }
        out.append('"')
    }

    // endregion

    // region Reading

    private class Reader(private val text: String) {
        private var index = 0
        private var depth = 0

        fun document(): Any? {
            // A byte order mark before the document is an encoding artifact, not content:
            // proxies and editors add one, and it must not cost a whole response.
            if (text.startsWith('\uFEFF')) index = 1
            skipWhitespace()
            val value = value()
            skipWhitespace()
            if (index != text.length) fail("unexpected characters after the document")
            return value
        }

        private fun value(): Any? {
            if (index >= text.length) fail("unexpected end of the document")
            return when (val char = text[index]) {
                '{' -> nested { objectValue() }
                '[' -> nested { arrayValue() }
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (char == '-' || char in '0'..'9') numberValue() else fail("unexpected character '$char'")
            }
        }

        private inline fun <T> nested(read: () -> T): T {
            if (++depth > MAX_DEPTH) fail("the document is nested too deeply")
            val value = read()
            depth--
            return value
        }

        private fun objectValue(): Map<String, Any?> {
            index++
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected an object key")
                val key = stringValue()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = value()
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> fail("expected ',' or '}' in an object")
                }
            }
        }

        private fun arrayValue(): List<Any?> {
            index++
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                result.add(value())
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> fail("expected ',' or ']' in an array")
                }
            }
        }

        private fun stringValue(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) fail("unterminated string")
                val char = text[index]
                when {
                    char == '"' -> {
                        index++
                        return out.toString()
                    }
                    char == '\\' -> {
                        index++
                        escape(out)
                    }
                    // RFC 8259 requires control characters inside a string to be escaped.
                    char < ' ' -> fail("unescaped control character in a string")
                    else -> {
                        out.append(char)
                        index++
                    }
                }
            }
        }

        private fun escape(out: StringBuilder) {
            if (index >= text.length) fail("unterminated escape")
            when (val escaped = text[index++]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    val code = hexEscape()
                    when {
                        // A character beyond the basic plane is written as two escapes, and
                        // half of the pair is no character at all.
                        Character.isHighSurrogate(code) -> {
                            if (!text.startsWith("\\u", index)) fail("unpaired surrogate in a unicode escape")
                            index += 2
                            val low = hexEscape()
                            if (!Character.isLowSurrogate(low)) fail("unpaired surrogate in a unicode escape")
                            out.append(code).append(low)
                        }
                        Character.isLowSurrogate(code) -> fail("unpaired surrogate in a unicode escape")
                        else -> out.append(code)
                    }
                }
                else -> fail("invalid escape '\\$escaped'")
            }
        }

        /** The four hex digits of a `\u` escape — ASCII ones only: `+041` parses as a number, and is no escape. */
        private fun hexEscape(): Char {
            if (index + 4 > text.length) fail("truncated unicode escape")
            var code = 0
            repeat(4) {
                val digit = when (val char = text[index]) {
                    in '0'..'9' -> char - '0'
                    in 'a'..'f' -> char - 'a' + 10
                    in 'A'..'F' -> char - 'A' + 10
                    else -> fail("invalid unicode escape")
                }
                code = code * 16 + digit
                index++
            }
            return code.toChar()
        }

        private fun numberValue(): Any {
            val start = index
            if (peek() == '-') index++
            // `0123` is not a JSON number: a leading zero stands on its own.
            if (peek() == '0' && index + 1 < text.length && text[index + 1] in '0'..'9') fail("leading zero in a number")
            if (!digits()) fail("invalid number")
            var isInteger = true
            if (peek() == '.') {
                isInteger = false
                index++
                if (!digits()) fail("invalid number")
            }
            if (peek() == 'e' || peek() == 'E') {
                isInteger = false
                index++
                if (peek() == '+' || peek() == '-') index++
                if (!digits()) fail("invalid number")
            }
            val raw = text.substring(start, index)
            if (isInteger) raw.toLongOrNull()?.let { return it }
            val value = raw.toDoubleOrNull() ?: fail("invalid number")
            // `1e400` reads as infinity, and no price, rating, or count is infinite.
            if (!value.isFinite()) fail("number out of range")
            return value
        }

        private fun digits(): Boolean {
            val start = index
            while (index < text.length && text[index] in '0'..'9') index++
            return index > start
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, index)) fail("unexpected literal")
            index += word.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) {
                index++
            }
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun next(): Char? = if (index < text.length) text[index++] else null

        private fun expect(char: Char) {
            if (next() != char) fail("expected '$char'")
        }

        private fun fail(message: String): Nothing = throw TalqynJsonException("$message at offset $index")
    }

    // endregion
}

/** The value as an object, or a decoding failure. */
internal fun Any?.asJsonObject(): JsonObject =
    (this as? Map<*, *>)?.let(::JsonObject) ?: throw TalqynJsonException("expected a JSON object")

/** The value as an array of objects, each decoded, or a decoding failure. */
internal fun <T> Any?.asJsonObjects(decode: (JsonObject) -> T): List<T> =
    (this as? List<*>)?.map { decode(it.asJsonObject()) } ?: throw TalqynJsonException("expected a JSON array")

/**
 * A JSON object read leniently.
 *
 * A missing key and a `null` read the same: Talqyn serializes events with
 * `exclude_none`, so a field left at its default simply never arrives. A value
 * of an unexpected type reads as absent too: one odd field must not bring down
 * a whole response.
 */
internal class JsonObject(private val map: Map<*, *>) {
    fun raw(key: String): Any? = map[key]

    fun string(key: String): String? = map[key] as? String

    fun int(key: String): Int? = asInt(map[key])

    /** Ids: 64-bit on the server, as they are on the twin SDK, where Swift's `Int` is 64 bits wide. */
    fun long(key: String): Long? = asLong(map[key])

    fun double(key: String): Double? = (map[key] as? Number)?.toDouble()

    fun bool(key: String): Boolean? = map[key] as? Boolean

    fun obj(key: String): JsonObject? = (map[key] as? Map<*, *>)?.let(::JsonObject)

    fun instant(key: String): Instant? = string(key)?.let(TalqynDates::parseIso)

    /**
     * An array that drops the elements it cannot decode: one malformed card
     * must cost that card, not the page it came in.
     */
    fun <T> list(key: String, element: (Any?) -> T?): List<T> = optionalList(key, element).orEmpty()

    /** [list] for a field whose absence is meaningful. */
    fun <T> optionalList(key: String, element: (Any?) -> T?): List<T>? {
        val raw = map[key] as? List<*> ?: return null
        return raw.mapNotNull { value ->
            try {
                element(value)
            } catch (e: TalqynJsonException) {
                null
            }
        }
    }

    fun <T> objects(key: String, decode: (JsonObject) -> T): List<T> =
        list(key) { value -> (value as? Map<*, *>)?.let { decode(JsonObject(it)) } }

    fun <T> optionalObjects(key: String, decode: (JsonObject) -> T): List<T>? =
        optionalList(key) { value -> (value as? Map<*, *>)?.let { decode(JsonObject(it)) } }

    fun strings(key: String): List<String> = list(key) { it as? String }

    fun longs(key: String): List<Long> = list(key, ::asLong)

    // The strict readers decode a collection whole or not at all, for values
    // whose elements are aligned with each other and mean nothing apart.

    fun strictStrings(key: String): List<String>? {
        val raw = map[key] as? List<*> ?: return null
        return raw.map { it as? String ?: return null }
    }

    fun strictLongs(key: String): List<Long>? {
        val raw = map[key] as? List<*> ?: return null
        return raw.map { asLong(it) ?: return null }
    }

    fun strictOptionalStrings(key: String): List<String?>? {
        val raw = map[key] as? List<*> ?: return null
        return raw.map {
            when (it) {
                null -> null
                is String -> it
                else -> return null
            }
        }
    }

    fun strictStringMap(key: String): Map<String, String>? {
        val raw = map[key] as? Map<*, *> ?: return null
        val result = LinkedHashMap<String, String>()
        for ((name, value) in raw) {
            result[name as? String ?: return null] = value as? String ?: return null
        }
        return result
    }

    fun strictStringListMap(key: String): Map<String, List<String>>? {
        val raw = map[key] as? Map<*, *> ?: return null
        val result = LinkedHashMap<String, List<String>>()
        for ((name, value) in raw) {
            val values = value as? List<*> ?: return null
            result[name as? String ?: return null] = values.map { it as? String ?: return null }
        }
        return result
    }

    companion object {
        /** A whole number that fits an `Int`; `5.0` counts, `5.5` and `"5"` do not. */
        fun asInt(value: Any?): Int? = when (value) {
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
            is Double -> if (value == Math.rint(value) && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) value.toInt() else null
            else -> null
        }

        /** A whole number that fits a `Long`; `5.0` counts, `5.5` and `"5"` do not. */
        fun asLong(value: Any?): Long? = when (value) {
            is Long -> value
            is Double -> if (value == Math.rint(value) && value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble()) value.toLong() else null
            else -> null
        }
    }
}

/** Builds a JSON object whose keys keep the order they were put in. */
internal class JsonBuilder {
    val map = LinkedHashMap<String, Any?>()

    fun put(key: String, value: Any?) {
        map[key] = value
    }

    /** Leaves the key out rather than writing `null`: the contract omits what is unset. */
    fun putIfNotNull(key: String, value: Any?) {
        if (value != null) map[key] = value
    }
}

internal inline fun jsonObject(build: JsonBuilder.() -> Unit): Map<String, Any?> = JsonBuilder().apply(build).map

/** Timestamps as the contract writes them. */
internal object TalqynDates {
    /** An offset written without a colon, `+0500`: ISO-8601 allows it, and Foundation reads it. */
    private val basicOffset: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .appendOffset("+HHmm", "Z")
        .toFormatter()

    /**
     * ISO-8601 with and without fractional seconds: Postgres emits `created_at`
     * both ways, and a client has no business failing on that.
     */
    fun parseIso(raw: String): Instant? = parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME) ?: parse(raw, basicOffset)

    private fun parse(raw: String, formatter: DateTimeFormatter): Instant? = try {
        OffsetDateTime.parse(raw, formatter).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }

    /**
     * An RFC 7231 HTTP date, `Sun, 06 Nov 1994 08:49:37 GMT` — the form of the
     * `Date` header and the second form of `Retry-After`.
     */
    fun parseHttp(raw: String): Instant? = try {
        ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }
}

/** Addresses from a catalog. */
internal object TalqynUrls {
    /** What a URL carries as it is, besides ASCII letters and digits. `%` and `#` have rules of their own; `[` and `]` belong to IPv6 hosts only. */
    private const val ALLOWED = "-._~:/?@!$&'()*+,;="
    private const val HEX = "0123456789ABCDEF"

    /**
     * A URL from a catalog string, taken leniently: real feeds carry spaces and
     * non-ASCII characters in paths, and an image must not vanish over that.
     *
     * What a URL may not contain is percent-encoded; what it may is kept. An escape
     * already in place stays as it is — encoding its `%` again would ask for another
     * file — while a stray `%` is encoded. The first `#` starts the fragment; a later
     * one is part of it. The twin SDK walks the same bytes the same way, so a product
     * shows the same image on both.
     */
    fun lenient(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        // Kept byte for byte only when it is a valid URL in ASCII: `java.net.URI` also takes raw
        // non-ASCII characters, which Foundation's parser does not, and those go through the
        // encoding below so that both platforms end up with the same bytes.
        if (raw.all { it.code < 0x80 } && runCatching { java.net.URI(raw) }.isSuccess) return raw
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size + 16)
        var hasFragment = false
        for ((index, byte) in bytes.withIndex()) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            val keep = when {
                code >= 0x80 -> false
                char == '%' -> index + 2 < bytes.size && isHexDigit(bytes[index + 1]) && isHexDigit(bytes[index + 2])
                char == '#' -> !hasFragment.also { hasFragment = true }
                else -> char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in ALLOWED
            }
            if (keep) {
                out.append(char)
            } else {
                out.append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
            }
        }
        val encoded = out.toString()
        return if (runCatching { java.net.URI(encoded) }.isSuccess) encoded else null
    }

    private fun isHexDigit(byte: Byte): Boolean = byte.toInt().toChar().let { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
