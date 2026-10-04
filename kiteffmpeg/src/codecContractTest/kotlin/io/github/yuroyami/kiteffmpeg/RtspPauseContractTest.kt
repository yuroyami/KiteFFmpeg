package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * [MediaSource.pause] and [MediaSource.resume] against a server that ends a silent session, as a
 * camera does (#136). The server announces a two second timeout, so a pause that lets the session
 * fall silent for longer loses it, and the repeated pause the KDoc asks for has to keep it alive.
 * A device cannot listen on the loopback from this suite, so the RTSP cases skip there.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class RtspPauseContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    @Test
    fun aRepeatedPauseKeepsAPausedSessionAliveAndResumePlaysOn() {
        val server = rtspServer() ?: return println("rtsp pause degraded: cannot listen on the loopback")
        server.use {
            withWatchdog { interrupt ->
                MediaSource.open(server.url, OPEN_OPTIONS, interrupt).use { source ->
                    assertTrue(source.pause(), "an RTSP source has a notion of pausing")
                    assertEquals(1, server.pauseCount, server.transcript)
                    val keepalivesBefore = server.keepaliveCount
                    val start = TimeSource.Monotonic.markNow()
                    var calls = 0
                    while (start.elapsedNow() < 6.seconds) {
                        assertTrue(source.pause(), "a paused RTSP source stays paused")
                        calls++
                        sleep(100)
                    }
                    val keepalives = server.keepaliveCount - keepalivesBefore
                    println("rtsp pause: $calls calls over six seconds sent $keepalives keepalives")
                    assertFalse(server.sessionEnded, "the paused session ended:\n${server.transcript}")
                    assertEquals(1, server.pauseCount, "a pause on a paused session sent PAUSE again:\n${server.transcript}")
                    // One keepalive a second, half the two second timeout, not one per call.
                    assertTrue(keepalives in 3..calls / 4, "$keepalives keepalives for $calls calls:\n${server.transcript}")

                    assertTrue(source.resume(), "the pause was in effect")
                    assertEquals(2, server.playCount, server.transcript)
                    assertEquals(PACKETS, audioPackets(source, PACKETS), "the stream did not play on after the pause")
                    server.silence()
                }
            }
        }
    }

    /** The same pause left silent, which proves the server above ends a session that falls quiet. */
    @Test
    fun aPausedSessionLeftSilentEndsAndResumeIsRefused() {
        val server = rtspServer() ?: return println("rtsp pause degraded: cannot listen on the loopback")
        server.use {
            withWatchdog { interrupt ->
                MediaSource.open(server.url, OPEN_OPTIONS, interrupt).use { source ->
                    assertTrue(source.pause())
                    sleep(3_500)
                    val refusal = assertFailsWith<FFmpegException> { source.resume() }
                    println("rtsp pause: resuming an ended session: ${refusal.message}")
                    assertTrue(server.sessionEnded, server.transcript)
                    assertEquals(1, server.playCount, "the server played an ended session:\n${server.transcript}")
                    server.silence()
                }
            }
        }
    }

    /** A resume with no pause in effect sends nothing, because a PLAY there would restart the stream. */
    @Test
    fun aResumeWithoutAPauseSendsNothing() {
        val server = rtspServer() ?: return println("rtsp pause degraded: cannot listen on the loopback")
        server.use {
            withWatchdog { interrupt ->
                MediaSource.open(server.url, OPEN_OPTIONS, interrupt).use { source ->
                    assertEquals(5, audioPackets(source, 5))
                    assertFalse(source.resume(), "there was no pause to lift")
                    assertTrue(source.pause())
                    assertTrue(source.resume())
                    assertFalse(source.resume(), "the pause was already lifted")
                    assertEquals(2, server.playCount, "a resume with nothing to lift sent PLAY:\n${server.transcript}")
                    assertEquals(PACKETS, audioPackets(source, PACKETS))
                    server.silence()
                }
            }
        }
    }

    @Test
    fun aFileAndAByteSourceHaveNoNotionOfPausing() {
        val path = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256).also(paths::add)
        MediaSource.open(path).use { source ->
            assertFalse(source.pause(), "a file has no notion of pausing")
            assertFalse(source.resume(), "a file has no pause to lift")
            assertTrue(audioPackets(source, Int.MAX_VALUE) > 0, "a file read on after the calls")
        }
        MediaSource.open(BytesSource(ContractMedia.bytes)).use { source ->
            assertFalse(source.pause(), "a byte source has no notion of pausing")
            assertFalse(source.resume(), "a byte source has no pause to lift")
            assertTrue(audioPackets(source, Int.MAX_VALUE) > 0, "a byte source read on after the calls")
        }
    }

    private fun rtspServer(): RtspTestServer? = listenOnLoopback()?.let { RtspTestServer(it, timeoutSeconds = 2) }

    /** Reads packets of the first audio stream until [limit] arrived or the stream ended. */
    private fun audioPackets(source: MediaSource, limit: Int): Int {
        val audio = source.primaryAudio ?: error("the stream has no audio, only ${source.streams.map { it.type }}")
        var count = 0
        source.openPacketReader(listOf(audio)).use { reader ->
            while (count < limit) {
                val packet = reader.read() ?: break
                packet.close()
                count++
            }
        }
        return count
    }

    private fun sleep(millis: Long) = runBlocking { delay(millis.milliseconds) }

    /** Runs [block] with an [OpenInterrupt] that fires after twenty seconds, so a stalled stream fails. */
    private fun <T> withWatchdog(block: (OpenInterrupt) -> T): T = runBlocking {
        val interrupt = OpenInterrupt()
        val watchdog = launch(Dispatchers.Default) {
            delay(20_000)
            interrupt.interrupt()
        }
        try {
            block(interrupt)
        } finally {
            watchdog.cancel()
        }
    }

    private class BytesSource(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close() {}
    }

    private companion object {
        /** Half a second of the server's stream. */
        const val PACKETS = 25

        /** Interleaved over the RTSP connection, with a five second limit on each socket wait. */
        val OPEN_OPTIONS = mapOf("rtsp_transport" to "tcp", "timeout" to "5000000")
    }
}
