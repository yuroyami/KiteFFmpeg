package io.github.yuroyami.kiteffmpeg

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An [OpenInterrupt] raised from another thread stops an open that is waiting on the network.
 *
 * The server listens and never accepts. The kernel still completes the TCP handshake for a
 * listening socket, so FFmpeg connects, sends its request and then waits for an answer that never
 * comes. Without the request this open would wait for ever.
 */
class JvmOpenInterruptStallTest {

    @Test
    fun aRequestRaisedWhileTheOpenWaitsStopsItWithinTheDeadline() {
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { server ->
            val url = "http://127.0.0.1:${server.localPort}/stall.mkv"
            val request = OpenInterrupt()
            val outcome = CompletableFuture<Throwable?>()
            val opener = Thread {
                try {
                    MediaSource.open(url, emptyMap(), request).close()
                    outcome.complete(null)
                } catch (failure: Throwable) {
                    outcome.complete(failure)
                }
            }
            // A daemon, so a regression that never returns cannot keep the test JVM alive.
            opener.isDaemon = true
            opener.start()

            Thread.sleep(200)
            assertTrue(!outcome.isDone, "the open finished before the request, so it was not waiting on the server")
            val raisedAt = System.nanoTime()
            request.interrupt()

            val failure = try {
                outcome.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
            } catch (timeout: TimeoutException) {
                fail("the open was still waiting $DEADLINE_SECONDS s after the request")
            }
            val tookMillis = (System.nanoTime() - raisedAt) / 1_000_000
            opener.join()
            val error = assertIs<FFmpegException>(failure, "the open must fail typed, got $failure")
            assertIs<FFmpegError.Interrupted>(error.error, "the failure must be Interrupted, was ${error.error}")
            println("open interrupted ${tookMillis} ms after the request")
        }
    }

    @Test
    fun aRequestRaisedBeforeTheOpenNeverReachesTheServer() {
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { server ->
            val request = OpenInterrupt().apply { interrupt() }
            val failure = assertFailsWith<FFmpegException> {
                MediaSource.open("http://127.0.0.1:${server.localPort}/stall.mkv", emptyMap(), request)
            }
            assertIs<FFmpegError.Interrupted>(failure.error)
        }
    }

    private companion object {
        /** FFmpeg checks the request every 100 ms while it waits; this leaves room for a busy host. */
        const val DEADLINE_SECONDS = 5L
    }
}
