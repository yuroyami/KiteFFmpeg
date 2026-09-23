package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A frame rate change keeps the duration and drops or repeats frames.
 *
 * The encoder's time base has one tick per output frame. The transcoder used to give every input
 * frame its own tick and push a frame that landed on a taken tick to the next one, so 60 frames
 * encoded at 25 fps played for 2.4 seconds. Now it chooses frames against the input timeline the
 * way FFmpeg's `fps` filter does, and the encoder refuses a frame on a taken tick instead of moving
 * it.
 *
 * `ffmpeg -vf fps=<rate>` over the same input is the oracle: the output shows the same input
 * frames, in the same order, as the oracle's does. Each input frame has its own grey level, so a
 * decoded frame says which input frame it shows.
 */
internal class TranscodeFrameRateTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /**
     * Transcodes [input] at [rate], and asserts the output: [expectedCount] frames one tick apart,
     * showing the same input frames that `ffmpeg -vf fps` shows. The counts in the tests are the
     * input's duration times [rate], so a right count is a kept duration.
     */
    private fun assertConverted(input: String, rate: Rational, expectedCount: Int): Pair<String, List<Int>> {
        val output = path("mkv")
        runBlocking {
            Transcoder.transcode(input = input, output = output, spec = TranscodeFixtures.videoSpec(rate))
        }
        val shown = TranscodeFixtures.decodedFrameIndices(output)
        assertEquals(expectedCount, shown.size, "frames in the output at $rate fps: $shown")
        MediaOracle.videoFrameCount(output)?.let { assertEquals(expectedCount, it, "frames ffprobe counts in the output") }
        val reference = path("mkv")
        if (MediaOracle.reference(input, listOf("-vf", "fps=$rate", "-c:v", "mpeg4", "-q:v", "2"), reference)) {
            assertEquals(TranscodeFixtures.decodedFrameIndices(reference), shown, "the input frames ffmpeg's fps filter shows")
        }
        // Matroska stores milliseconds, so each time is within half of one of the exact tick.
        val times = TranscodeFixtures.decodedFrameTimes(output)
        val tick = 1_000_000.0 * rate.den / rate.num
        times.forEachIndexed { index, time ->
            assertTrue(abs(time - times.first() - index * tick) <= 1_000.0, "frame $index at $time us is off its tick")
        }
        return output to shown
    }

    /** The issue's reproduction. Red when every input frame gets its own tick: 60 frames, 2.36 s. */
    @Test
    fun sixtyToTwentyFiveDropsFramesAndKeepsTheDuration() {
        val input = path("mkv")
        TranscodeFixtures.writeConstantRateVideo(input, Rational(60, 1), frames = 60)
        val (output, shown) = assertConverted(input, Rational(25, 1), expectedCount = 25)
        assertTrue(shown.zipWithNext().all { (a, b) -> b > a }, "a slower output only drops frames: $shown")
        val inputDuration = MediaSource.open(input).use { checkNotNull(it.durationMicros) }
        val outputDuration = MediaSource.open(output).use { checkNotNull(it.durationMicros) }
        assertTrue(abs(outputDuration - inputDuration) <= 40_000L, "the input lasts $inputDuration us and the output $outputDuration us")
    }

    /** One second at 24 fps is 60 frames at 60 fps, each input frame shown two or three times. */
    @Test
    fun twentyFourToSixtyRepeatsFrames() {
        val input = path("mkv")
        TranscodeFixtures.writeConstantRateVideo(input, Rational(24, 1), frames = 24)
        val (_, shown) = assertConverted(input, Rational(60, 1), expectedCount = 60)
        assertEquals((0 until 24).toList(), shown.distinct(), "every input frame is shown, in order")
        assertTrue(shown.groupingBy { it }.eachCount().values.all { it in 2..3 }, "each frame shows two or three times: $shown")
    }

    /** 59.94 fps to 23.976 fps keeps two frames in five. */
    @Test
    fun ntscSixtyToNtscTwentyFourKeepsTwoFramesInFive() {
        val input = path("mkv")
        TranscodeFixtures.writeConstantRateVideo(input, Rational(60_000, 1_001), frames = 60)
        assertConverted(input, Rational(24_000, 1_001), expectedCount = 24)
    }

    /**
     * Frames at uneven times: bursts lose frames and gaps repeat the frame before them. The input
     * is written with a one-millisecond time base, so every timestamp keeps its own tick.
     */
    @Test
    fun variableRateInputBecomesConstantRate() {
        val input = path("mkv")
        val times = listOf(0L, 10, 50, 60, 70, 200, 210, 400, 420, 440, 700, 1_000).map { it * 1_000L }
        TranscodeFixtures.writeVideo(input, Rational(1_000, 1), times)
        val output = path("mkv")
        runBlocking {
            Transcoder.transcode(input = input, output = output, spec = TranscodeFixtures.videoSpec(Rational(25, 1)))
        }
        val shown = TranscodeFixtures.decodedFrameIndices(output)
        val reference = path("mkv")
        if (MediaOracle.reference(input, listOf("-vf", "fps=25", "-c:v", "mpeg4", "-q:v", "2"), reference)) {
            assertEquals(TranscodeFixtures.decodedFrameIndices(reference), shown, "the input frames ffmpeg's fps filter shows")
        }
        // Tick 0 is 0 to 40 ms: the frame at 10 ms replaces the one at 0 ms. The gap after 70 ms
        // repeats that frame until the frame at 210 ms, which replaced the one at 200 ms.
        assertEquals(listOf(1, 2, 4, 4, 4, 6, 6, 6, 6, 6), shown.take(10), "the first ten ticks")
    }

    /**
     * An encoder driven directly refuses the second of two frames on one tick, instead of moving
     * it later. Red when it moved them: 60 frames at 60 fps timestamps played for 2.4 seconds.
     */
    @Test
    fun anEncoderRefusesTwoFramesOnOneTick() {
        val output = path("mkv")
        val refusal = assertFailsWith<FFmpegException> {
            MediaSink.open(output).use { sink ->
                val encoder = sink.addVideoEncoder(TranscodeFixtures.videoSpec(Rational(25, 1)))
                runBlocking {
                    encoder.drive(
                        (0 until 60).asFlow().map { index ->
                            Frame.ofVideo(
                                bytes = ByteArray(TranscodeFixtures.WIDTH * TranscodeFixtures.HEIGHT * 3 / 2),
                                width = TranscodeFixtures.WIDTH,
                                height = TranscodeFixtures.HEIGHT,
                                pixelFormat = PixelFormat.Yuv420p,
                                ptsMicros = index * 1_000_000L / 60L,
                            )
                        },
                    )
                }
            }
        }
        assertIs<FFmpegError.InvalidArgument>(refusal.error, refusal.message)
        assertTrue(refusal.message?.contains("tick") == true, "the refusal says why: ${refusal.message}")
    }
}
