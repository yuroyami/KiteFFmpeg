package io.github.yuroyami.kiteffmpeg

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The network failures a player most needs to tell apart arrive typed, not as a raw errno that
 * differs by system (#245).
 */
class NetworkErrorTypeTest {

    @Test
    fun aConnectionToAClosedPortIsRefused() {
        val port = ServerSocket(0).use { it.localPort }
        val failure = assertFailsWith<FFmpegException> { MediaSource.open("tcp://127.0.0.1:$port").close() }
        assertIs<FFmpegError.ConnectionRefused>(failure.error, "was ${failure.error} (code ${failure.error.code})")
    }

    @Test
    fun aSilentServerTimesOut() {
        ServerSocket(0).use { server ->
            // Accepts the connection and then sends nothing.
            val accepting = Thread { runCatching { server.accept().use { Thread.sleep(5_000) } } }.apply { isDaemon = true; start() }
            val failure = assertFailsWith<FFmpegException> {
                MediaSource.open("tcp://127.0.0.1:${server.localPort}", mapOf("rw_timeout" to "300000")).close()
            }
            assertIs<FFmpegError.TimedOut>(failure.error, "was ${failure.error} (code ${failure.error.code})")
            accepting.interrupt()
        }
    }
}
