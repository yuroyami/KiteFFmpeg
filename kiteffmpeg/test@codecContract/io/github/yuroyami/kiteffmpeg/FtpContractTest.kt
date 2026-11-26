package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FFmpeg's ftp protocol reads a file from an FTP server (#268). The server is [FtpTestServer] on
 * the loopback, and the file is larger than FFmpeg's read buffer, so a seek reaches the server as
 * a transfer from an offset. Windows has no loopback listener yet (#279), so the case skips there.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class FtpContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    @Test
    fun anFtpAddressOpensReadsToTheEndAndSeeks() {
        val path = clip()
        val file = readContractBytes(path)
        assertTrue(file.size > 4 * 32 * 1024, "the clip must be larger than FFmpeg's read buffer, it is ${file.size} bytes")
        val expected = MediaSource.open(path).use(::readAndSeek)
        val control = listenOnLoopback() ?: return println("ftp degraded: cannot listen on the loopback")
        FtpTestServer(control, checkNotNull(listenOnLoopback()), file).use { server ->
            val read = MediaSource.open(server.url, OPEN_OPTIONS).use(::readAndSeek)
            assertEquals(expected, read, server.transcript)
            assertEquals(FRAMES, read.first.size, server.transcript)
            assertTrue(read.second.size in 1 until FRAMES, "the seek read ${read.second.size} pictures")
            // The seek after the end reached the server as a transfer from inside the file.
            assertTrue(server.transfers.last() > 0, "transfers started at ${server.transfers}:\n${server.transcript}")
            assertTrue("ABOR" in server.transcript.lines(), server.transcript)
        }
    }

    /** The times of every picture of [source], then of every picture after a seek back to [SEEK_TARGET]. */
    private fun readAndSeek(source: MediaSource): Pair<List<Long?>, List<Long?>> {
        val video = source.streams[checkNotNull(source.primaryVideo).index]
        return source.openPacketReader(listOf(video)).use { reader ->
            val whole = times(reader)
            reader.seek(SEEK_TARGET, SeekDirection.Backward)
            whole to times(reader)
        }
    }

    private fun times(reader: PacketReader): List<Long?> = buildList {
        while (true) {
            val packet = reader.read() ?: break
            packet.use { add(it.ptsMicros) }
        }
    }

    /** Two seconds of 320x240 mpeg4 in MP4, with a keyframe every half second. */
    private fun clip(): String = contractOutputPath("mp4").also(paths::add).also { path ->
        MediaSink.open(path).use { sink ->
            val encoder = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 320,
                    height = 240,
                    frameRate = Rational(30, 1),
                    bitrateBps = 2_000_000,
                    keyframeIntervalFrames = 15,
                ),
            )
            runBlocking {
                encoder.drive(
                    (0 until FRAMES).asFlow().map { index ->
                        val bytes = ByteArray(320 * 240 * 3 / 2) { ((it * 31 + index * 17) % 251).toByte() }
                        Frame.ofVideo(bytes, 320, 240, PixelFormat.Yuv420p, index * 1_000_000L / 30)
                    },
                )
            }
        }
    }

    private companion object {
        const val FRAMES = 60
        const val SEEK_TARGET = 1_000_000L

        /** A five second limit on each socket wait, so a stalled transfer fails the test. */
        val OPEN_OPTIONS = mapOf("timeout" to "5000000")
    }
}
