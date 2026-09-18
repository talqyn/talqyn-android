package com.talqyn.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one piece of SSE parsing written by hand: `String.lines()` breaks on characters that are
 * legal inside a JSON string.
 */
class LineSplitterTest {
    private fun split(text: String): List<String> {
        val splitter = TalqynLineSplitter()
        val lines = text.toByteArray(Charsets.UTF_8).asList().mapNotNull { splitter.consume(it) }.toMutableList()
        splitter.finish()?.let { lines.add(it) }
        return lines
    }

    @Test
    fun everyTerminatorTheSpecificationAllows() {
        assertEquals(listOf("a", "b"), split("a\nb\n"))
        assertEquals(listOf("a", "b"), split("a\rb\r"))
        assertEquals(listOf("a", "b"), split("a\r\nb\r\n"))
        assertEquals("CR LF is one terminator; the blank line survives", listOf("a", "", "b"), split("a\r\n\r\nb\n"))
        assertEquals("LF CR is two terminators", listOf("a", "", "b"), split("a\n\rb"))
    }

    @Test
    fun tailWithoutTerminatorIsEmittedOnFinish() {
        assertEquals(listOf("event: done", "data: {}"), split("event: done\ndata: {}"))
        assertEquals(emptyList<String>(), split(""))
        assertEquals(listOf(""), split("\n"))
    }

    @Test
    fun unicodeLineSeparatorsStayInsideTheLine() {
        val delta = "data: {\"text\":\"line\u2028more\u2029and\u0085end\"}"
        assertEquals(listOf(delta, ""), split(delta + "\n\n"))
    }

    /** The CR ends one read and the LF opens the next. */
    @Test
    fun terminatorSplitAcrossChunksIsOneTerminator() = runBlocking {
        val input = ChunkedInputStream(listOf("event: delta\r".toByteArray(), "\ndata: {}\r\n\r\n".toByteArray()))
        val lines = TalqynLineSplitter.lines(input).toList()
        assertEquals(listOf("event: delta", "data: {}", ""), lines)
    }

    @Test
    fun linesReportTheUnderlyingFailure() = runBlocking {
        val input = ChunkedInputStream(listOf("data: {".toByteArray()), failure = IOException("dropped"))
        var closed = false
        val lines = ArrayList<String>()
        val error = expectFailure<IOException>("expected the failure to propagate") {
            TalqynLineSplitter.lines(input) { closed = true }.collect { lines.add(it) }
        }
        assertEquals("dropped", error.message)
        assertTrue("an unterminated line is not delivered on failure", lines.isEmpty())
        assertTrue("the request behind the body is closed", closed)
    }

    /** A character whose bytes arrive in separate reads is still one character. */
    @Test
    fun aCharacterSplitAcrossReadsArrivesWhole() = runBlocking {
        val line = "data: {\"text\":\"Жы ₸ 😀\"}"
        val input = ChunkedInputStream((line + "\n").toByteArray(Charsets.UTF_8).map { byteArrayOf(it) })
        assertEquals(listOf(line), TalqynLineSplitter.lines(input).toList())
    }

    @Test
    fun closeRunsOnceWhenTheBodyEnds() = runBlocking {
        val closes = AtomicInteger()
        TalqynLineSplitter.lines(ChunkedInputStream(listOf("a\n".toByteArray()))) { closes.incrementAndGet() }.toList()
        assertEquals(1, closes.get())
    }

    /** Cancellation closes from the cancelling thread, and the flow closes again on its way out: a close that is not idempotent must still run once. */
    @Test
    fun closeRunsOnceWhenTheCollectorIsCancelled() = runBlocking {
        val closes = AtomicInteger()
        val released = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                released.await()
                throw IOException("closed")
            }
        }
        val collector = launch(Dispatchers.Default) {
            TalqynLineSplitter.lines(input) {
                closes.incrementAndGet()
                released.countDown()
            }.collect { }
        }
        delay(100)
        collector.cancelAndJoin()
        delay(50)
        assertEquals(1, closes.get())
    }

    /** Hands out one chunk per read, then ends — or fails. */
    private class ChunkedInputStream(chunks: List<ByteArray>, private val failure: IOException? = null) : InputStream() {
        private val pending = ArrayDeque(chunks)

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val chunk = pending.removeFirstOrNull() ?: failure?.let { throw it } ?: return -1
            chunk.copyInto(b, off)
            return chunk.size
        }
    }
}
