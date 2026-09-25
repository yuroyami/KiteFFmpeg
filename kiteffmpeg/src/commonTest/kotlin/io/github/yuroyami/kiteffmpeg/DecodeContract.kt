package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The decode half of the codec contract, written once and run by every backend on the same bytes:
 * the JVM and Android through the JNI adapter, macOS through cinterop, and the web through the
 * linked codec module. Every expected value is pinned here, so a backend that disagrees with the
 * others fails its own run, and each case is its own test so the report lists them one by one.
 *
 * Only what a decode-only build can do is here. The web build has decoders and demuxers but no
 * encoders, muxers or file system, so [CodecContractTest] keeps the rest.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
abstract class DecodeContract {

    /** Makes the backend usable, or returns false when this run has none to test. */
    protected open suspend fun backendReady(): Boolean = true

    private fun contract(block: suspend () -> Unit): TestResult = runTest {
        if (backendReady()) block()
    }

    private fun open(source: FixtureBytes = FixtureBytes()): MediaSource = MediaSource.open(source)

    @Test
    fun theContainerReadsTheSameOnEveryBackend() = contract {
        open().use { source ->
            assertEquals("mov,mp4,m4a,3gp,3g2,mj2", source.formatName)
            assertEquals(2, source.streams.size)
            assertEquals(500_000L, source.durationMicros)
            assertEquals(0L, source.startTimeMicros)
            assertTrue(source.isSeekable)
            assertEquals("KiteFFmpeg decode contract", source.metadata["title"])

            val video = assertNotNull(source.primaryVideo)
            assertEquals(0, video.index)
            assertEquals("h264", video.codec.name)
            assertEquals(Rational(1, 15360), video.timeBase)
            val picture = assertNotNull(video.video)
            assertEquals(64, picture.width)
            assertEquals(64, picture.height)
            assertEquals(PixelFormat.Yuv420p, picture.pixelFormat)
            assertEquals(Rational(30, 1), picture.frameRate)
            assertEquals(VIDEO_EXTRADATA_SHA256, sha256Hex(assertNotNull(video.codecExtradata)), "avcC record")

            val audio = assertNotNull(source.primaryAudio)
            assertEquals(1, audio.index)
            assertEquals("aac", audio.codec.name)
            assertEquals("eng", audio.metadata["language"])
            val sound = assertNotNull(audio.audio)
            assertEquals(48_000, sound.sampleRate)
            assertEquals(2, sound.channels)
            assertEquals(STEREO_MASK, sound.channelLayoutMask)
            assertEquals(AUDIO_EXTRADATA_SHA256, sha256Hex(assertNotNull(audio.codecExtradata)), "AudioSpecificConfig")
        }
    }

    @Test
    fun packetsArriveInTheSameOrderWithTheSameTimes() = contract {
        open().use { source ->
            val packets = source.openPacketReader(source.streams).use { reader -> reader.drain() }
            assertEquals(15, packets.count { it.startsWith("0:") }, "video packets")
            assertEquals(AUDIO_PACKETS, packets.count { it.startsWith("1:") }, "audio packets")
            assertEquals(PACKETS_SHA256, sha256Hex(packets.joinToString("\n").encodeToByteArray()), packets.joinToString("\n"))
        }
    }

    @Test
    fun everyVideoFrameDecodesToTheSameBytes() = contract {
        open().use { source ->
            val frames = source.decodedFrames(assertNotNull(source.primaryVideo)).toList()
            try {
                assertEquals(VIDEO_PTS_MICROS, frames.map { it.ptsMicros })
                assertTrue(frames.all { it.info.width == 64 && it.info.height == 64 && it.info.pixelFormat == PixelFormat.Yuv420p })
                assertEquals(VIDEO_PLANES_SHA256, frames.planesSha256(), "decoded pictures")
            } finally {
                frames.forEach(Frame::close)
            }
        }
    }

    @Test
    fun audioDecodesToTheSameLengthAndTone() = contract {
        open().use { source ->
            val frames = source.decodedFrames(assertNotNull(source.primaryAudio)).toList()
            try {
                assertTrue(frames.all { it.info.sampleRate == 48_000 && it.info.channelCount == 2 })
                assertEquals(SampleFormat("fltp"), frames.first().info.sampleFormat)
                assertEquals(AUDIO_SAMPLES, frames.sumOf { it.info.sampleCount.toLong() }, "samples per channel")
                val left = FloatArray(frames.sumOf { it.info.sampleCount })
                val right = FloatArray(left.size)
                var at = 0
                for (frame in frames) {
                    val bytes = frame.copyPlanesToByteArray()
                    val count = frame.info.sampleCount
                    for (i in 0 until count) {
                        left[at + i] = bytes.floatAt(i * 4)
                        right[at + i] = bytes.floatAt((count + i) * 4)
                    }
                    at += count
                }
                // 1 kHz for half a second crosses zero about a thousand times; the tone's peak is 1/8.
                val crossings = (1 until left.size).count { (left[it - 1] < 0f) != (left[it] < 0f) }
                assertTrue(crossings in 990..1010, "zero crossings $crossings, not a 1 kHz tone")
                val peak = left.maxOf { kotlin.math.abs(it) }
                assertTrue(peak in 0.10f..0.15f, "peak $peak, not the tone's 0.125")
                val spread = left.indices.maxOf { kotlin.math.abs(left[it] - right[it]) }
                assertTrue(spread < 0.01f, "the two channels of a mono tone differ by $spread")
            } finally {
                frames.forEach(Frame::close)
            }
        }
    }

    @Test
    fun aSeekLandsOnTheSameKeyframe() = contract {
        open().use { source ->
            val video = assertNotNull(source.primaryVideo)
            source.seekMicros(250_000)
            source.decodedFrames(video).first().use { frame ->
                assertEquals(166_667L, frame.ptsMicros, "the keyframe at or before 250 ms")
            }
            source.openPacketReader(listOf(video)).use { reader ->
                reader.seek(250_000)
                val packet = assertNotNull(reader.read())
                packet.use {
                    assertTrue(it.isKeyframe)
                    assertEquals(166_667L, it.ptsMicros)
                }
            }
        }
    }

    @Test
    fun aDrainedDecoderGivesEveryFrameAndRestartsAfterAFlush() = contract {
        open().use { source ->
            val video = assertNotNull(source.primaryVideo)
            source.openPacketReader(listOf(video)).use { reader ->
                source.openDecoder(video).use { decoder ->
                    assertEquals(VIDEO_PLANES_SHA256, decoder.decodeAll(reader), "first pass")
                    assertTrue(decoder.isDrained)
                    decoder.flush()
                    reader.seek(0)
                    assertEquals(VIDEO_PLANES_SHA256, decoder.decodeAll(reader), "after flush and seek")
                }
            }
        }
    }

    @Test
    fun closedObjectsRefuseTheSameWay() = contract {
        open().use { source ->
            val video = assertNotNull(source.primaryVideo)
            val reader = source.openPacketReader(listOf(video))
            val packet = assertNotNull(reader.read())
            packet.close()
            assertFailsWith<IllegalStateException> { packet.pts }
            reader.close()
            assertFailsWith<IllegalStateException> { reader.read() }
            val decoder = source.openDecoder(video)
            decoder.close()
            assertFailsWith<IllegalStateException> { decoder.send(null) }
        }
    }

    @Test
    fun aByteSourceFailureArrivesAsTheCause() = contract {
        // The open, because the whole fixture fits in what the open reads ahead: a failure armed
        // after it would never be reached.
        val thrown = IllegalStateException("the byte source failed while the open read it")
        val error = runCatching { open(FixtureBytes().apply { failReadsWith = thrown }) }.exceptionOrNull()
        assertIs<FFmpegException>(error)
        assertSame(thrown, error.cause, "the open must carry the byte source's exception as its cause")
    }

    /** Reads every packet, closing each, and describes it as stream:pts:dts:size:key. */
    private fun PacketReader.drain(): List<String> = buildList {
        while (true) {
            val packet = read() ?: break
            packet.use { add("${it.streamIndex}:${it.pts}:${it.dts}:${it.sizeBytes}:${if (it.isKeyframe) "K" else "-"}") }
        }
    }

    /** Decodes every packet [reader] still has, then drains; returns the frames' digest. */
    private fun StreamDecoder.decodeAll(reader: PacketReader): String {
        val frames = mutableListOf<Frame>()
        try {
            fun collect() {
                while (true) frames += receive() ?: return
            }
            while (true) {
                val packet = reader.read() ?: break
                packet.use {
                    while (!send(it)) collect()
                }
                collect()
            }
            send(null)
            collect()
            return frames.planesSha256()
        } finally {
            frames.forEach(Frame::close)
        }
    }

    private fun List<Frame>.planesSha256(): String {
        val all = map { it.copyPlanesToByteArray() }
        val joined = ByteArray(all.sumOf { it.size })
        var at = 0
        for (planes in all) {
            planes.copyInto(joined, at)
            at += planes.size
        }
        return sha256Hex(joined)
    }

    private fun ByteArray.floatAt(offset: Int): Float = Float.fromBits(
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24),
    )

    /** [DecodeContractMedia] as a seekable byte source that can be told to fail. */
    private class FixtureBytes : MediaByteSource {
        private val bytes = DecodeContractMedia.bytes
        private var position = 0
        var failReadsWith: Throwable? = null

        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            failReadsWith?.let { throw it }
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close(): Unit = Unit
    }

    private companion object {
        const val STEREO_MASK = 3L
        const val AUDIO_PACKETS = 25
        const val AUDIO_SAMPLES = 24_000L
        const val VIDEO_EXTRADATA_SHA256 = "009ef696ded42ddfe7b0dc636488108e009e06fc10376c9b335321064988c54d"
        const val AUDIO_EXTRADATA_SHA256 = "b3108813350006ef07740046fab14af9c374938867d16db02fe1a8baa5e72f10"
        const val PACKETS_SHA256 = "c4ba757270c2a785578e709ef5813a2de23485411ddd7e243b493fc4bf907133"
        const val VIDEO_PLANES_SHA256 = "65d95e3095654c561603a29702e1f8845f8c1c12dd89ccd49d707995c7882621"
        val VIDEO_PTS_MICROS: List<Long?> = listOf(
            0L, 33_333L, 66_667L, 100_000L, 133_333L, 166_667L, 200_000L, 233_333L,
            266_667L, 300_000L, 333_333L, 366_667L, 400_000L, 433_333L, 466_667L,
        )
    }
}
