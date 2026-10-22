package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The native half of the JVM's stall test: an [OpenInterrupt] raised from another thread stops an
 * open that waits on a server which accepted the connection and never answers.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class NativeOpenInterruptStallTest {

    /** A loopback socket that listens and never accepts; the kernel still completes handshakes. */
    private class SilentServer : AutoCloseable {
        private val fd: Int = socket(AF_INET, SOCK_STREAM, 0)
        val port: Int

        init {
            check(fd >= 0) { "socket() failed" }
            port = memScoped {
                val address = alloc<sockaddr_in>()
                address.sin_len = sizeOf<sockaddr_in>().convert()
                address.sin_family = AF_INET.convert()
                address.sin_port = 0u
                // 127.0.0.1 in network byte order, read as a little-endian word.
                address.sin_addr.s_addr = 0x0100007Fu
                check(bind(fd, address.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) { "bind() failed" }
                check(listen(fd, 8) == 0) { "listen() failed" }
                val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                check(getsockname(fd, address.ptr.reinterpret<sockaddr>(), length.ptr) == 0) { "getsockname() failed" }
                val networkOrder = address.sin_port.toInt()
                ((networkOrder and 0xFF) shl 8) or (networkOrder shr 8)
            }
        }

        override fun close() {
            close(fd)
        }
    }

    @Test
    fun aRequestRaisedWhileTheOpenWaitsStopsItWithinTheDeadline() {
        SilentServer().use { server ->
            val url = "http://127.0.0.1:${server.port}/stall.mkv"
            val request = OpenInterrupt()
            val opener = newSingleThreadContext("stalled-open")
            try {
                runBlocking {
                    val outcome = async(opener) {
                        runCatching { MediaSource.open(url, emptyMap(), request).close() }.exceptionOrNull()
                    }
                    delay(200)
                    assertTrue(!outcome.isCompleted, "the open finished before the request, so it was not waiting on the server")
                    val raisedAt = TimeSource.Monotonic.markNow()
                    request.interrupt()
                    val failure = withTimeout(DEADLINE_MILLIS) { outcome.await() }
                    val error = assertIs<FFmpegException>(failure, "the open must fail typed, got $failure")
                    assertIs<FFmpegError.Interrupted>(error.error, "the failure must be Interrupted, was ${error.error}")
                    println("open interrupted ${raisedAt.elapsedNow().inWholeMilliseconds} ms after the request")
                }
            } finally {
                opener.close()
            }
        }
    }

    private companion object {
        /** FFmpeg checks the request every 100 ms while it waits; this leaves room for a busy host. */
        const val DEADLINE_MILLIS = 5_000L
    }
}
