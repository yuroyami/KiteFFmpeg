package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.DemuxOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The live network protocols open through [MediaSource.open] (#124). The `ffmpeg` command line
 * sends a short test pattern on the loopback, or receives one, and the open must yield its packets.
 * A device cannot start a process, so the suite skips there.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class LiveProtocolContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    @Test
    fun aUdpStreamOpens() {
        val port = port()
        val sender = startMediaOracle("ffmpeg", clip(8) + listOf("-f", "mpegts", "udp://127.0.0.1:$port?pkt_size=1316"))
            ?: return println("live protocols degraded: no ffmpeg")
        try {
            val packets = withWatchdog { interrupt ->
                MediaSource.open("udp://127.0.0.1:$port?timeout=5000000", emptyMap(), interrupt).use { videoPackets(it, LIVE_PACKETS) }
            }
            assertEquals(LIVE_PACKETS, packets, "the UDP stream ended early")
        } finally {
            sender.await()
        }
    }

    @Test
    fun aRawTcpStreamOpens() {
        val port = port()
        val sender = startMediaOracle("ffmpeg", clip(8) + listOf("-f", "mpegts", "tcp://127.0.0.1:$port?listen=1&listen_timeout=15000"))
            ?: return println("live protocols degraded: no ffmpeg")
        try {
            val packets = withWatchdog { interrupt -> openWhenListening("tcp://127.0.0.1:$port", interrupt).use { videoPackets(it, LIVE_PACKETS) } }
            assertEquals(LIVE_PACKETS, packets, "the TCP stream ended early")
        } finally {
            sender.await()
        }
    }

    @Test
    fun anRtmpStreamOpens() {
        val port = port()
        val url = "rtmp://127.0.0.1:$port/live/kite"
        val sender = startMediaOracle("ffmpeg", clip(8, codec = "flv") + listOf("-f", "flv", "-listen", "1", "-timeout", "15", url))
            ?: return println("live protocols degraded: no ffmpeg")
        try {
            val packets = withWatchdog { interrupt -> openWhenListening(url, interrupt).use { videoPackets(it, LIVE_PACKETS) } }
            assertEquals(LIVE_PACKETS, packets, "the RTMP stream ended early")
        } finally {
            sender.await()
        }
    }

    /**
     * An SDP file read from disk names the protocols it needs. FFmpeg lets an input opened through
     * `file` reach only `file`, `crypto` and `data`, so the open lists `udp` and `rtp` itself.
     */
    @Test
    fun anSdpFileOpensTheRtpStreamItDescribes() {
        val port = port()
        val sender = startMediaOracle("ffmpeg", clip(8) + listOf("-f", "rtp_mpegts", "rtp://127.0.0.1:$port"))
            ?: return println("live protocols degraded: no ffmpeg")
        try {
            val sdp = sdpFile(port)
            val packets = withWatchdog { interrupt ->
                val options = DemuxOptions(format = "sdp", protocolWhitelist = setOf("file", "udp", "rtp"))
                MediaSource.open(sdp, options.compile().toMap(), interrupt).use { videoPackets(it, LIVE_PACKETS) }
            }
            assertEquals(LIVE_PACKETS, packets, "the RTP stream ended early")
        } finally {
            sender.await()
        }
    }

    @Test
    fun anSdpFileWithoutTheProtocolsNamedIsRefused() {
        runMediaOracle("ffmpeg", listOf("-version")) ?: return println("live protocols degraded: no ffmpeg")
        val sdp = sdpFile(port())
        val refusal = assertFailsWith<FFmpegException> {
            withWatchdog { interrupt -> MediaSource.open(sdp, DemuxOptions(format = "sdp").compile().toMap(), interrupt).close() }
        }
        println("live protocols: an SDP file without the protocols named: ${refusal.message}")
        // The demuxer is there and refused its own RTP address. A build without it says so instead.
        assertIs<FFmpegError.InvalidData>(refusal.error, "was ${refusal.error}")
    }

    /** A scheme the build carries no protocol for is a typed refusal, which needs no sender. */
    @Test
    fun aSchemeTheBuildDoesNotCarryIsATypedRefusal() {
        for (url in listOf("srt://127.0.0.1:9/live", "rtmps://127.0.0.1:9/live/kite", "https://127.0.0.1:9/live.ts")) {
            val refusal = assertFailsWith<FFmpegException>(url) { MediaSource.open(url).close() }
            assertIs<FFmpegError.ProtocolNotFound>(refusal.error, "$url was ${refusal.error}")
        }
    }

    @Test
    fun anRtspStreamPublishedOverUdpOpens(): Unit = rtspPublished("udp")

    @Test
    fun anRtspStreamPublishedOverTcpOpens(): Unit = rtspPublished("tcp")

    /**
     * The `rtsp` demuxer listens and the command line publishes to it over [transport], which
     * proves the demuxer, both transports and the transport option. A camera is the other role,
     * where the demuxer dials a server, and the command line cannot be that server.
     */
    private fun rtspPublished(transport: String) {
        runMediaOracle("ffmpeg", listOf("-version")) ?: return println("live protocols degraded: no ffmpeg")
        val port = port()
        val url = "rtsp://127.0.0.1:$port/live"
        val packets = withWatchdog { interrupt ->
            runBlocking {
                val receiver = async(Dispatchers.Default) {
                    val options = mapOf("rtsp_flags" to "listen", "listen_timeout" to "15", "rtsp_transport" to transport)
                    MediaSource.open(url, options, interrupt).use { source ->
                        assertTrue("rtsp_transport" !in source.unusedOpenOptions, "unused: ${source.unusedOpenOptions}")
                        videoPackets(source, Int.MAX_VALUE)
                    }
                }
                // The publisher fails at once while nothing listens yet, so it tries again, unless
                // the listener has already failed and its own error is the one to report.
                val start = TimeSource.Monotonic.markNow()
                while (!receiver.isCompleted) {
                    val publisher = startMediaOracle("ffmpeg", clip(3) + listOf("-f", "rtsp", "-rtsp_transport", transport, url))
                        ?: error("ffmpeg stopped starting")
                    if (publisher.await() == 0) break
                    check(start.elapsedNow() < 10.seconds) { "nothing listened on $url" }
                    delay(200)
                }
                receiver.await()
            }
        }
        assertTrue(packets >= 50, "expected most of the 75 frames over RTSP and $transport, got $packets")
    }

    /** A loopback SDP that describes one MPEG-TS stream over RTP on [port], written to a file. */
    private fun sdpFile(port: Int): String {
        val text = "v=0\no=- 0 0 IN IP4 127.0.0.1\ns=KiteFFmpeg\nc=IN IP4 127.0.0.1\nt=0 0\nm=video $port RTP/AVP 33\n"
        val bytes = text.encodeToByteArray()
        return materializeContractMedia(bytes, sha256Hex(bytes)).also(paths::add)
    }

    /**
     * Opens [url] on a sender that listens, trying again while the connection is refused: the
     * command line takes a moment to start listening.
     */
    private fun openWhenListening(url: String, interrupt: OpenInterrupt): MediaSource {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            try {
                return MediaSource.open(url, emptyMap(), interrupt)
            } catch (refused: FFmpegException) {
                if (refused.error is FFmpegError.ProtocolNotFound) throw refused
                if (interrupt.isInterrupted || start.elapsedNow() > 10.seconds) throw refused
            }
            runBlocking { delay(100) }
        }
    }

    /** Reads packets of the first video stream until [limit] arrived or the stream ended. */
    private fun videoPackets(source: MediaSource, limit: Int): Int {
        val video = source.primaryVideo ?: error("the stream has no video, only ${source.streams.map { it.type }}")
        var count = 0
        source.openPacketReader(listOf(video)).use { reader ->
            while (count < limit) {
                val packet = reader.read() ?: break
                packet.close()
                count++
            }
        }
        return count
    }

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

    private companion object {
        /** One second of the pattern. */
        const val LIVE_PACKETS = 25

        /**
         * The command line's input: a test pattern of [seconds] at 25 frames a second, sent in real
         * time, with a keyframe every ten frames so that a receiver that joins late starts soon.
         */
        fun clip(seconds: Int, codec: String = "mpeg4"): List<String> = listOf(
            "-v", "error", "-re", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25", "-t", "$seconds",
            "-c:v", codec, "-g", "10",
        )

        /** An even port, because RTP sends its control packets to the port above. */
        fun port(): Int = Random.nextInt(10_000, 30_000) * 2
    }
}
