package com.talqyn.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class SseDecoderTest {
    private fun messages(lines: List<String>): List<TalqynSseMessage> {
        val decoder = TalqynSseDecoder()
        val result = lines.mapNotNull { decoder.consume(it) }.toMutableList()
        decoder.finish()?.let { result.add(it) }
        return result
    }

    @Test
    fun eventAndDataDispatchedOnBlankLine() {
        val parsed = messages(listOf("event: delta", "data: {\"text\":\"hi\"}", ""))
        assertEquals(1, parsed.size)
        assertEquals("delta", parsed[0].name)
        assertEquals("{\"text\":\"hi\"}", parsed[0].data)
    }

    @Test
    fun multilineDataJoinedWithNewline() {
        val parsed = messages(listOf("event: delta", "data: {", "data: \"text\": \"a\"}", ""))
        assertEquals("{\n\"text\": \"a\"}", parsed[0].data)
    }

    @Test
    fun commentsAndUnknownFieldsIgnored() {
        val parsed = messages(listOf(": keep-alive", "id: 42", "retry: 1000", "event: done", "data: {}", ""))
        assertEquals(1, parsed.size)
        assertEquals("done", parsed[0].name)
    }

    /** A proxy may swallow the blank line between events; they must not merge. */
    @Test
    fun eventLineFlushesPendingMessage() {
        val parsed = messages(listOf("event: status", "data: {\"stage\":\"thinking\"}", "event: done", "data: {}", ""))
        assertEquals(listOf("status", "done"), parsed.map { it.name })
    }

    @Test
    fun finishEmitsMessageWithoutTrailingBlankLine() {
        val parsed = messages(listOf("event: done", "data: {\"session_id\":\"abc\"}"))
        assertEquals(listOf("done"), parsed.map { it.name })
    }

    @Test
    fun onlyOneLeadingSpaceStripped() {
        val parsed = messages(listOf("event: delta", "data:  {\"text\":\"x\"}", ""))
        assertEquals(" {\"text\":\"x\"}", parsed[0].data)
    }

    @Test
    fun dataWithoutEventNameDefaultsToMessage() {
        val parsed = messages(listOf("data: {}", ""))
        assertEquals("message", parsed[0].name)
    }
}
