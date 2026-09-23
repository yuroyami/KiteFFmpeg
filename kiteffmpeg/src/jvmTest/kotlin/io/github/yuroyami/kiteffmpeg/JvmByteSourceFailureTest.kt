package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An exception thrown by a caller's [MediaByteSource] reaches the caller as the cause of the
 * [FFmpegException] it produced.
 *
 * FFmpeg only receives an error code from the bridge, so without the cause the caller catches a
 * bare I/O error and the exception that actually happened is lost. Each test fails one path: the
 * open, a packet read, a decode flow and a seek. The last test checks the opposite direction: an
 * exception FFmpeg recovered from must not become the cause of a later, unrelated error.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class JvmByteSourceFailureTest {

    /** Thrown by the test source, so `assertSame` proves that this exact object arrived. */
    private class SourceFailure(message: String) : RuntimeException(message)

    @Test
    fun anOpenThatFailsInsideReadCarriesTheSourceException() {
        val thrown = SourceFailure("the source failed while the open probed it")
        val source = FixtureSource(fixture).apply { failReadsWith = thrown }

        val error = runCatching { MediaSource.open(source) }.exceptionOrNull()

        assertTrue(source.failedReads > 0, "the open never read from the source")
        assertIs<FFmpegException>(error)
        assertSame(thrown, error.cause, "the open must carry the source's exception as its cause")
    }

    @Test
    fun aPacketReadThatFailsInsideReadCarriesTheSourceException() {
        val source = FixtureSource(fixture)
        MediaSource.open(source).use { media ->
            val thrown = SourceFailure("the source failed while a packet was read")
            source.failReadsWith = thrown

            val error = runCatching {
                media.openPacketReader(media.streams).use { reader ->
                    while (true) {
                        val packet = reader.read() ?: break
                        packet.close()
                    }
                }
            }.exceptionOrNull()

            assertTrue(source.failedReads > 0, NO_READ_AFTER_OPEN)
            assertIs<FFmpegException>(error)
            assertSame(thrown, error.cause, "a packet read must carry the source's exception as its cause")
        }
    }

    @Test
    fun aDecodeFlowThatFailsInsideReadCarriesTheSourceException() {
        val source = FixtureSource(fixture)
        MediaSource.open(source).use { media ->
            val video = media.primaryVideo ?: error("the fixture has no video stream")
            val thrown = SourceFailure("the source failed while a decode flow read it")
            source.failReadsWith = thrown

            val error = runCatching {
                runBlocking { media.decodedFrames(video).collect { it.close() } }
            }.exceptionOrNull()

            assertTrue(source.failedReads > 0, NO_READ_AFTER_OPEN)
            assertIs<FFmpegException>(error)
            assertSame(thrown, error.cause, "a decode flow must carry the source's exception as its cause")
        }
    }

    @Test
    fun aSeekThatFailsInsideSeekCarriesTheSourceException() {
        val source = FixtureSource(fixture)
        MediaSource.open(source).use { media ->
            val thrown = SourceFailure("the source failed while a seek moved it")
            source.failSeeksWith = thrown

            val error = runCatching { runBlocking { media.seekMicros(4_000_000) } }.exceptionOrNull()

            assertTrue(source.failedSeeks > 0, "the seek never reached the source")
            assertIs<FFmpegException>(error)
            assertSame(thrown, error.cause, "a seek must carry the source's exception as its cause")
        }
    }

    /**
     * FFmpeg ignores a failed seek while the open estimates the duration of an MPEG-TS file, then
     * keeps reading. A later read error that the source did not throw must carry no cause.
     */
    @Test
    fun anExceptionTheOpenRecoveredFromDoesNotExplainALaterError() {
        val source = FixtureSource(fixture).apply {
            failOneSeekWith = SourceFailure("a seek failure that FFmpeg ignores")
        }
        MediaSource.open(source).use { media ->
            assertEquals(1, source.failedSeeks, "the open swallowed no failed seek, so this test proves nothing")
            assertTrue(source.readsAfterFailedSeek > 0, "no read delivered bytes after the failed seek")
            source.readsNothing = true

            val error = runCatching {
                media.openPacketReader(media.streams).use { reader ->
                    while (true) {
                        val packet = reader.read() ?: break
                        packet.close()
                    }
                }
            }.exceptionOrNull()

            assertIs<FFmpegException>(error)
            assertNull(error.cause, "an exception FFmpeg recovered from must not explain a later error")
        }
    }

    /**
     * Serves [bytes]. Once a failure is set it is thrown from every read or seek, a one-shot seek
     * failure is thrown once, and [readsNothing] breaks the read contract without an exception.
     */
    private class FixtureSource(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        var failReadsWith: Throwable? = null
        var failSeeksWith: Throwable? = null
        var failOneSeekWith: Throwable? = null
        var readsNothing = false
        var failedReads = 0
        var failedSeeks = 0
        var readsAfterFailedSeek = 0

        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            failReadsWith?.let {
                failedReads++
                throw it
            }
            if (readsNothing) return 0
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            if (failedSeeks > 0) readsAfterFailedSeek++
            return count
        }

        override fun seek(position: Long) {
            failSeeksWith?.let {
                failedSeeks++
                throw it
            }
            failOneSeekWith?.let {
                failOneSeekWith = null
                failedSeeks++
                throw it
            }
            this.position = position.toInt()
        }

        override fun close() = Unit
    }

    private companion object {
        const val NO_READ_AFTER_OPEN =
            "no read reached the source after the open, so the fixture fits in what the open buffered"

        /** Six seconds of MPEG-TS video, larger than what the open reads ahead. */
        val fixture: ByteArray by lazy {
            val file = File.createTempFile("kiteffmpeg-source-failure-", ".ts")
            try {
                MediaSink.open(file.absolutePath).use { sink ->
                    val encoder = sink.addVideoEncoder(
                        VideoEncoderSpec(
                            codec = CodecId("mpeg4"),
                            width = 320,
                            height = 240,
                            frameRate = Rational(30, 1),
                            bitrateBps = 800_000,
                        ),
                    )
                    runBlocking {
                        encoder.drive(
                            (0 until 180).asFlow().map { index ->
                                Frame.ofVideo(noisyFrame(320, 240, index), 320, 240, PixelFormat.Yuv420p, index * 1_000_000L / 30)
                            },
                        )
                    }
                }
                file.readBytes()
            } finally {
                file.delete()
            }
        }

        /** Noisy luma keeps the encoded size up, so the file outgrows the open's read-ahead. */
        fun noisyFrame(width: Int, height: Int, index: Int): ByteArray {
            val luma = ByteArray(width * height) { i -> (((i * 1103515245 + index * 12345) ushr 13) and 0xFF).toByte() }
            val u = ByteArray(width * height / 4) { 100.toByte() }
            val v = ByteArray(width * height / 4) { (140 + index % 40).toByte() }
            return luma + u + v
        }
    }
}
