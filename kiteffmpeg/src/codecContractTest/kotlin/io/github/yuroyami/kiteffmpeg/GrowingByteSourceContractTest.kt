package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A byte source that grows while it is read, as a recording in progress does (#177). FFmpeg asks
 * for the size again whenever it needs it, and an MPEG-TS seek searches between the start and the
 * size it is given then, so a source that answers with what it holds now can be seeked into the
 * part that arrived after the open.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class GrowingByteSourceContractTest {

    @Test
    fun aSeekReachesThePartThatArrivedAfterTheOpen() {
        // The first third, cut on a transport packet, is all there is when the open runs.
        val source = GrowingSource(fixture, available = fixture.size / 3 / TS_PACKET * TS_PACKET)
        MediaSource.open(source).use { media ->
            val video = media.primaryVideo ?: error("the fixture has no video stream")
            val start = media.openPacketReader(listOf(video)).use { reader ->
                generateSequence { reader.read() }.firstNotNullOf { packet -> packet.use { it.ptsMicros } }
            }
            source.available = fixture.size
            runBlocking { media.seekMicros(5_000_000) }
            val landed = media.openPacketReader(listOf(video)).use { reader ->
                generateSequence { reader.read() }.firstNotNullOf { packet -> packet.use { it.ptsMicros } }
            } - start
            // A keyframe a little before the target is a seek that arrived; the first third ends near 2 s.
            assertTrue(landed >= 4_000_000, "the seek to 5 s landed at $landed us, inside what the open saw")
        }
    }

    /** Bytes of which only the first [available] exist yet, and a size that says so. */
    private class GrowingSource(private val bytes: ByteArray, var available: Int) : MediaByteSource {
        private var position = 0
        override val size: Long get() = available.toLong()
        override val seekable: Boolean = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= available) return -1
            val count = minOf(length, available - position)
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
        const val TS_PACKET = 188

        /** Six seconds of MPEG-TS video, a keyframe every 12 pictures. */
        val fixture: ByteArray by lazy {
            val path = contractOutputPath("ts")
            try {
                MediaSink.open(path).use { sink ->
                    val encoder = sink.addVideoEncoder(
                        VideoEncoderSpec(
                            codec = CodecId("mpeg4"),
                            width = 160,
                            height = 120,
                            frameRate = Rational(30, 1),
                            bitrateBps = 400_000,
                        ),
                    )
                    runBlocking {
                        encoder.drive(
                            (0 until 180).asFlow().map { index ->
                                Frame.ofVideo(noisyFrame(160, 120, index), 160, 120, PixelFormat.Yuv420p, index * 1_000_000L / 30)
                            },
                        )
                    }
                }
                readContractBytes(path)
            } finally {
                deleteContractPath(path)
            }
        }

        /** A picture of noise that changes each frame, so every picture costs real bytes. */
        fun noisyFrame(width: Int, height: Int, index: Int): ByteArray {
            val luma = ByteArray(width * height) { i -> (((i * 1103515245 + index * 12345) ushr 13) and 0xFF).toByte() }
            val chroma = ByteArray(width * height / 2) { 128.toByte() }
            return luma + chroma
        }
    }
}
