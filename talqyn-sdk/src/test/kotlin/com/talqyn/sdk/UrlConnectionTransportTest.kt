package com.talqyn.sdk

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds

/**
 * The default transport end to end, over a raw HTTP server on localhost: headers come back
 * before the body, and the body is split into lines the way the SDK expects — including a
 * terminator torn across two chunks.
 */
class UrlConnectionTransportTest {
    private var server: RawHttpServer? = null

    @After
    fun tearDown() {
        server?.close()
    }

    private fun serve(status: Int, headers: Map<String, String>, chunks: List<String>, closeDelimited: Boolean): TalqynHttpRequest {
        val started = RawHttpServer(status, headers, chunks.map { it.toByteArray() }, closeDelimited)
        server = started
        return TalqynHttpRequest("GET", started.url, mapOf("Accept" to "text/event-stream"), null, 5.seconds)
    }

    @Test
    fun sendReturnsBodyStatusAndLowercasedHeaders() = runBlocking {
        val request = serve(
            429,
            mapOf("Retry-After" to "7", "X-Request-ID" to "req-1"),
            listOf("""{"detail":"Rate limit exceeded"}"""),
            closeDelimited = false,
        )
        val result = TalqynUrlConnectionTransport().send(request)
        assertEquals(429, result.response.statusCode)
        assertEquals("7", result.response.value("retry-after"))
        assertEquals("req-1", result.response.value("X-REQUEST-ID"))
        assertEquals("""{"detail":"Rate limit exceeded"}""", result.body.toString(Charsets.UTF_8))
    }

    @Test
    fun streamSplitsLinesAcrossChunks() = runBlocking {
        val request = serve(
            200,
            mapOf("Content-Type" to "text/event-stream"),
            listOf(
                "event: status\r",
                "\ndata: {\"stage\":\"thinking\"}\r\n\r\nevent: delta\ndata: {\"text\":\"a\u2028b\"}\n\n",
                "event: done\ndata: {\"session_id\":\"s\"}",
            ),
            closeDelimited = true,
        )
        val stream = TalqynUrlConnectionTransport().stream(request)
        assertEquals(200, stream.response.statusCode)
        assertEquals(
            listOf(
                "event: status", "data: {\"stage\":\"thinking\"}", "",
                "event: delta", "data: {\"text\":\"a\u2028b\"}", "",
                "event: done", "data: {\"session_id\":\"s\"}",
            ),
            stream.lines.toList(),
        )
    }

    @Test
    fun streamedErrorBodyIsReadableAsLines() = runBlocking {
        val request = serve(401, emptyMap(), listOf("""{"detail":"Invalid device token"}"""), closeDelimited = false)
        val stream = TalqynUrlConnectionTransport().stream(request)
        assertEquals(401, stream.response.statusCode)
        assertEquals(listOf("""{"detail":"Invalid device token"}"""), stream.lines.toList())
    }

    /**
     * Answers one request with a canned response, writing the body chunk by chunk with a pause in
     * between, so the client reads it in several pieces.
     */
    private class RawHttpServer(
        status: Int,
        headers: Map<String, String>,
        chunks: List<ByteArray>,
        closeDelimited: Boolean,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

        val url: String = "http://127.0.0.1:${socket.localPort}/v1/consultant/ask"

        init {
            Thread {
                try {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val out = client.getOutputStream()
                        val head = StringBuilder("HTTP/1.1 $status Status\r\n")
                        headers.forEach { (name, value) -> head.append("$name: $value\r\n") }
                        if (!closeDelimited) head.append("Content-Length: ${chunks.sumOf { it.size }}\r\n")
                        head.append("Connection: close\r\n\r\n")
                        out.write(head.toString().toByteArray())
                        out.flush()
                        for (chunk in chunks) {
                            out.write(chunk)
                            out.flush()
                            Thread.sleep(30)
                        }
                    }
                } catch (e: Exception) {
                    // The test fails on its own assertions; the server has nobody to tell.
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        override fun close() {
            socket.close()
        }
    }
}
