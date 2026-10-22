package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A frame copies its planes into an array that its caller keeps, with the same bytes as a fresh
 * copy. A converter can then allocate one array for a stream instead of one for every frame.
 */
class FramePlanesIntoContractTest {

    // 70 is not a multiple of any row alignment, so FFmpeg pads every plane's rows and the copy
    // has to drop that padding.
    private val width = 70
    private val height = 48

    private fun videoBytes(seed: Int) = ByteArray(width * height * 3 / 2) { (it * seed).toByte() }

    private fun videoFrame(bytes: ByteArray) =
        Frame.ofVideo(bytes, width, height, PixelFormat.Yuv420p, ptsMicros = 0L)

    @Test
    fun aVideoFrameWritesItsBytesToTheStartOfTheArray() {
        val bytes = videoBytes(31)
        videoFrame(bytes).use { frame ->
            assertEquals(bytes.size, frame.planesByteCount())
            val destination = ByteArray(bytes.size + 16) { SENTINEL }
            assertEquals(bytes.size, frame.copyPlanesInto(destination))
            assertContentEquals(bytes, destination.copyOf(bytes.size))
            assertTrue(destination.drop(bytes.size).all { it == SENTINEL }, "the bytes past the frame changed")
        }
    }

    @Test
    fun anAudioFrameWritesItsSamplesToTheStartOfTheArray() {
        val samples = 1024
        val bytes = ByteArray(samples * 2 * 4) { (it * 7).toByte() }
        Frame.ofAudio(bytes, samples, 48_000, 2, SampleFormat.FltP, ptsMicros = 0L).use { frame ->
            assertEquals(bytes.size, frame.planesByteCount())
            val destination = ByteArray(bytes.size)
            assertEquals(bytes.size, frame.copyPlanesInto(destination))
            assertContentEquals(bytes, destination)
        }
    }

    @Test
    fun oneArrayServesEveryFrameOfAStream() {
        val destination = ByteArray(width * height * 3 / 2)
        for (seed in listOf(3, 5, 11)) {
            val bytes = videoBytes(seed)
            videoFrame(bytes).use { frame -> frame.copyPlanesInto(destination) }
            assertContentEquals(bytes, destination, "frame $seed")
        }
    }

    @Test
    fun aShortArrayIsRefusedAndLeftAsItWas() {
        val bytes = videoBytes(13)
        videoFrame(bytes).use { frame ->
            val short = ByteArray(bytes.size - 1) { SENTINEL }
            val refusal = assertFailsWith<FFmpegException> { frame.copyPlanesInto(short) }
            assertIs<FFmpegError.InvalidArgument>(refusal.error)
            assertTrue("${bytes.size}" in refusal.message.orEmpty(), "the refusal names the size it needs: ${refusal.message}")
            assertTrue(short.all { it == SENTINEL }, "a refused copy wrote into the array")
        }
    }

    private companion object {
        const val SENTINEL: Byte = 0x5A
    }
}
