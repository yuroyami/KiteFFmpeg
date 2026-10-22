package io.github.yuroyami.kiteffmpeg

import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An encoded video's last frame lasts one frame, like every other (#143).
 *
 * FFmpeg's encoders hand back packets with no duration unless asked to keep one, and MP4 ends a
 * track where its last sample ends, so a last packet with no duration made a sample of length zero
 * that ordinary playback drops: 14 frames at 10 fps played as 13 and lasted 1.3 seconds. The
 * command-line `ffprobe` is the independent reading of the frames and the length.
 */
internal class EncodedVideoTailContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** Writes [frames] frames at [rate] into a new [extension] file and checks every one plays for its whole tick. */
    private fun assertWholeTail(extension: String, rate: Rational, frames: Int) {
        val output = path(extension)
        TranscodeFixtures.writeConstantRateVideo(output, rate, frames)
        val expected = frames * 1_000_000L * rate.den / rate.num
        val at = "$frames frames at $rate fps in $extension"
        assertEquals((0 until frames).toList(), TranscodeFixtures.decodedFrameIndices(output), "$at: the frames decoded")
        MediaOracle.videoFrameCount(output)?.let { assertEquals(frames, it, "$at: the frames ffprobe decodes") }
        val length = MediaSource.open(output).use { checkNotNull(it.durationMicros) { "$at: no duration" } }
        // Matroska keeps milliseconds, and a container's time base can round a tick by one unit.
        assertTrue(abs(length - expected) <= 1_000L, "$at: lasts $length us, expected $expected")
        MediaOracle.durationMicros(output)?.let {
            assertTrue(abs(it - expected) <= 1_000L, "$at: ffprobe reads $it us, expected $expected")
        }
    }

    /** The issue's reproduction: red when the last sample has no duration, 13 frames and 1.3 s. */
    @Test
    fun anMp4PlaysItsLastFrame() = assertWholeTail("mp4", Rational(10, 1), frames = 14)

    @Test
    fun aMovPlaysItsLastFrame() = assertWholeTail("mov", Rational(25, 1), frames = 10)

    @Test
    fun aFractionalRateKeepsItsLastFrame() = assertWholeTail("mp4", Rational(24_000, 1_001), frames = 12)

    @Test
    fun aSingleFrameLastsOneFrame() = assertWholeTail("mp4", Rational(30, 1), frames = 1)

    @Test
    fun matroskaLastsAsLongAsItsFrames() = assertWholeTail("mkv", Rational(25, 1), frames = 10)
}
