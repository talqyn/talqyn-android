package com.talqyn.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One event parsed out of a `text/event-stream` response.
 *
 * @property name The event name, from the `event:` field. `"message"` when the stream omitted one.
 * @property data The raw JSON payload assembled from the event's `data:` fields.
 */
public data class TalqynSseMessage(
    val name: String,
    val data: String,
)

/**
 * An incremental parser for `text/event-stream` bodies.
 *
 * State lives outside the stream on purpose: the parser can then be exercised
 * line by line in a test, with no network and no coroutines.
 *
 * Talqyn emits `event: <name>`, one or more `data: <json>` lines, and a blank line.
 * Comment lines (a leading `:`, used by proxies for keep-alive) and the `id` and
 * `retry` fields are skipped.
 *
 * ```kotlin
 * val decoder = TalqynSseDecoder()
 * lines.collect { line -> decoder.consume(line)?.let(::handle) }
 * decoder.finish()?.let(::handle)
 * ```
 */
public class TalqynSseDecoder {
    private var name = DEFAULT_EVENT_NAME
    private val payload = ArrayList<String>()

    /**
     * Feeds the parser one line of the response body, its terminator already stripped.
     *
     * @return The event this line completed, or `null` while the event is still being accumulated.
     */
    public fun consume(line: String): TalqynSseMessage? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        if (line.startsWith("event:")) {
            // Flush before renaming: `event:` always opens a new event here, so a
            // blank line dropped by a proxy must not merge two into one.
            val completed = flush()
            name = fieldValue(line, "event:")
            return completed
        }
        if (line.startsWith("data:")) {
            payload.add(fieldValue(line, "data:"))
        }
        return null
    }

    /** Closes the stream, emitting an event that never got its trailing blank line. */
    public fun finish(): TalqynSseMessage? = flush()

    private fun flush(): TalqynSseMessage? {
        val message = if (payload.isEmpty()) null else TalqynSseMessage(name, payload.joinToString("\n"))
        name = DEFAULT_EVENT_NAME
        payload.clear()
        return message
    }

    private companion object {
        const val DEFAULT_EVENT_NAME = "message"

        /** The specification strips exactly **one** space after the colon, no more. */
        fun fieldValue(line: String, prefix: String): String {
            val value = line.substring(prefix.length)
            return if (value.startsWith(" ")) value.substring(1) else value
        }
    }
}

/**
 * Splits a byte stream into lines the way `text/event-stream` defines them.
 *
 * CR, LF, or CR LF end a line, and nothing else does. `BufferedReader.readLine`
 * agrees, but `String.lines()` also breaks on characters that are legal inside a
 * JSON string, and a `data:` line carrying one would come apart and its event would
 * be dropped. Splitting on bytes is safe: no UTF-8 continuation byte equals CR or LF.
 *
 * Public so a custom [TalqynHttpTransport] can split its streamed body the same way.
 */
public class TalqynLineSplitter {
    private val buffer = ByteArrayOutputStream()

    /** A CR was just seen: a following LF belongs to the same terminator. */
    private var pendingCr = false

    /**
     * Feeds one byte.
     *
     * @return The line this byte terminated, without its terminator, or `null` while
     *   the line is still being accumulated.
     */
    public fun consume(byte: Byte): String? = when (byte) {
        CR -> {
            pendingCr = true
            take()
        }
        LF -> if (pendingCr) {
            pendingCr = false
            null
        } else {
            take()
        }
        else -> {
            pendingCr = false
            buffer.write(byte.toInt())
            null
        }
    }

    /** Ends the stream, emitting a last line that had no terminator. */
    public fun finish(): String? {
        pendingCr = false
        return if (buffer.size() == 0) null else take()
    }

    private fun take(): String {
        val line = buffer.toString(Charsets.UTF_8.name())
        buffer.reset()
        return line
    }

    public companion object {
        private const val CR: Byte = 0x0D
        private const val LF: Byte = 0x0A

        /**
         * Splits a body into a flow of lines.
         *
         * Reading happens on the I/O dispatcher and answers to cancellation: a
         * cancelled collector calls [onClose], which should close the connection so
         * the read blocked on it fails at once. [onClose] also runs when the body
         * ends or fails — exactly once in every case, and from whichever thread gets
         * there first: the cancelling one, often the main thread, or the collector's.
         * Make it safe to call from another thread than the one reading — for OkHttp
         * that is `call.cancel()`, not `response.close()`. What it throws is dropped.
         *
         * @param input The response body. Closed when the flow completes.
         * @param onClose Closes the request behind the body.
         */
        @JvmStatic
        public fun lines(input: InputStream, onClose: () -> Unit = {}): Flow<String> = flow {
            val closed = AtomicBoolean(false)
            // Cancellation reaches here from a completion handler, where an exception
            // would crash the app; and a close that is not idempotent must not run twice.
            val closeOnce = {
                if (closed.compareAndSet(false, true)) {
                    try {
                        onClose()
                    } catch (e: Exception) {
                        // Closing failed; the connection goes with the stream regardless.
                    }
                }
            }
            try {
                val splitter = TalqynLineSplitter()
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val count = interruptible(closeOnce) { input.read(chunk) }
                    if (count < 0) break
                    for (index in 0 until count) {
                        splitter.consume(chunk[index])?.let { emit(it) }
                    }
                }
                splitter.finish()?.let { emit(it) }
            } finally {
                runCatching { input.close() }
                closeOnce()
            }
        }
    }
}
