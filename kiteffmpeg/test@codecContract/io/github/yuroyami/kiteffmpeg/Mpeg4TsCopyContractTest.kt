package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stream copy of MPEG-4 Part 2 video into MPEG-TS can be decoded (#159).
 *
 * An encoder writing MPEG-4 Part 2 for MP4 or Matroska puts the headers that give the picture size
 * and coding in the extradata alone, and MPEG-TS has nowhere to carry extradata, so FFmpeg's writer
 * copied such a stream into a file in which no picture could be decoded. It now puts the headers in
 * front of each keyframe. The fix is the FFmpeg patch `0007`, so these tests fail on a tree built
 * before it.
 *
 * The source is three seconds of MPEG-4 Part 2 at 10 fps in MP4, written by this library, whose
 * encoder writes global headers for a container that keeps them. Each copy is read back by this
 * library, picture by picture or packet by packet.
 */
internal class Mpeg4TsCopyContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private fun source(): String = path("mp4").also { file ->
        MediaSink.open(file).use { sink ->
            val video = sink.addVideoEncoder(TranscodeFixtures.videoSpec(Rational(10, 1)))
            val pictures = (0 until FRAMES).asFlow().map { i -> TranscodeFixtures.texturedPicture(i, ptsMicros = i * 100_000L) }
            runBlocking { video.drive(pictures) }
        }
    }

    @Test
    fun aCopyFromMp4IntoMpegTsDecodesEveryPicture() {
        val input = source()
        assertEquals((0 until FRAMES).toList(), TranscodeFixtures.decodedFrameIndices(input), "the fixture itself")
        val output = path("ts")
        runBlocking { Remuxer.remux(input = input, output = output) }
        assertEquals((0 until FRAMES).toList(), TranscodeFixtures.decodedFrameIndices(output), "the pictures of the copy")
    }

    @Test
    fun theHeadersGoInFrontOfEachKeyframeOnce() {
        val input = source()
        val first = path("ts")
        runBlocking { Remuxer.remux(input = input, output = first) }
        val source = packets(input)
        val copy = packets(first)
        assertEquals(source.map { it.first }, copy.map { it.first }, "which packets are keyframes")
        val growth = source.zip(copy).map { (from, to) -> to.second - from.second }
        val headers = growth.first()
        assertTrue(headers > 0, "the first keyframe grew by $headers bytes, so the headers are not in the stream")
        assertEquals(source.map { if (it.first) headers else 0 }, growth, "the bytes each packet gained")
        // A copy out of MPEG-TS finds the headers in the stream and makes them its extradata, and
        // they must not then go in a second time.
        val second = path("ts")
        runBlocking { Remuxer.remux(input = first, output = second) }
        assertEquals(copy, packets(second), "the packets of the second copy")
    }

    /** Whether each video packet of [path] is a keyframe, and its size. */
    @OptIn(KiteFFmpegLowLevelApi::class)
    private fun packets(path: String): List<Pair<Boolean, Int>> = MediaSource.open(path).use { source ->
        val video = checkNotNull(source.primaryVideo) { "$path has no video stream" }
        source.openPacketReader(listOf(video)).use { reader ->
            generateSequence { reader.read() }.map { packet -> packet.use { it.isKeyframe to it.sizeBytes } }.toList()
        }
    }

    private companion object {
        const val FRAMES = 30
    }
}
