package com.talqyn.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Cancellation and the deadline of the default transport. `HttpURLConnection` does not answer to an
 * interrupt, and a disconnect before a connection exists does nothing: these are the moments in between.
 */
class TransportCancellationTest {
    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        server?.close()
    }

    /** Cancelled while the blocking call still waited for a thread: the request must never start. */
    @Test
    fun aCallCancelledBeforeItsThreadCameNeverRuns() = runBlocking {
        val queue = LinkedBlockingQueue<Runnable>()
        val cancels = AtomicInteger()
        var ran = false
        val job = launch(Dispatchers.Default) {
            interruptible(onCancel = { cancels.incrementAndGet() }, executor = Executor { queue.put(it) }) {
                ran = true
                "response"
            }
        }
        val queued = queue.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("the call was never queued")
        job.cancelAndJoin()
        queued.run()
        assertFalse("a cancelled call must not start its request", ran)
        assertEquals(1, cancels.get())
    }

    /** A call that finishes after its coroutine was cancelled hands back what it opened: released, not dropped with the result. */
    @Test
    fun aResultArrivingAfterCancellationIsReleased() = runBlocking {
        val queue = LinkedBlockingQueue<Runnable>()
        val releases = AtomicInteger()
        lateinit var job: Job
        job = launch(Dispatchers.Default) {
            interruptible(onCancel = { releases.incrementAndGet() }, executor = Executor { queue.put(it) }) {
                // Cancelled while the request is under way — before there was a connection for the first release to close.
                job.cancel()
                "connection"
            }
        }
        val queued = queue.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("the call was never queued")
        queued.run()
        job.join()
        assertEquals("once when cancelled, and once more for what the call opened afterwards", 2, releases.get())
    }

    /** Keep-alive comments reset every read timeout, so only the deadline ends a stream that never finishes. */
    @Test
    fun aStreamThatNeverEndsRunsIntoTheDeadline() = runBlocking {
        val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        server = socket
        Thread {
            try {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) {
                        // The request headers.
                    }
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                    while (true) {
                        out.write(": keep-alive\n".toByteArray())
                        out.flush()
                        Thread.sleep(50)
                    }
                }
            } catch (e: Exception) {
                // The client hung up: the deadline did its work.
            }
        }.apply {
            isDaemon = true
            start()
        }

        // A 150 ms read timeout never fires between 50 ms keep-alives; ten of it — 1.5 s — is the deadline.
        val request = TalqynHttpRequest("GET", "http://127.0.0.1:${socket.localPort}/v1/consultant/ask", emptyMap(), null, 150.milliseconds)
        val started = System.nanoTime()
        val stream = TalqynUrlConnectionTransport().stream(request)
        val error = expectFailure<SocketTimeoutException>("expected the deadline to end the stream") { stream.lines.collect { } }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue("ended after $elapsed ms", elapsed in 1_000..10_000)
        assertTrue("got ${error.message}", error.message.orEmpty().contains("deadline"))
    }
}
