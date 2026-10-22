package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A filter that moves time keeps every frame it makes from the selected input.
 *
 * `startMicros` and `endMicros` select a part of the INPUT. A filter such as `setpts=2*PTS` or
 * `atempo=0.5` then stretches that part, and the stretched output is longer than the selection.
 * The encoder used to apply the trim end a second time, to the filtered timestamps, so a slowed
 * down clip lost its second half and an offset clip lost almost everything.
 *
 * Each video case uses an output rate that matches the filtered rate, so every filtered frame is
 * one output frame and the count is exact. `ffmpeg` with a `trim` filter in front of the same
 * filter, and `fps` at the output rate behind it, is the oracle.
 */
internal class TranscodeFilterTimingTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** 50 frames at 25 fps: two seconds, one frame every 40 ms. */
    private fun twoSeconds(): String = path("mkv").also {
        TranscodeFixtures.writeConstantRateVideo(it, Rational(25, 1), frames = 50)
    }

    private fun transcodeVideo(
        input: String,
        filter: String,
        outputRate: Rational,
        startMicros: Long = 0L,
        endMicros: Long,
    ): String = path("mkv").also { output ->
        runBlocking {
            Transcoder.transcode(
                input = input,
                output = output,
                spec = TranscodeFixtures.videoSpec(outputRate),
                videoFilter = filter,
                startMicros = startMicros,
                endMicros = endMicros,
            )
        }
    }

    /**
     * Asserts [output] shows [expected] input frames, and that `ffmpeg` shows the same ones. The
     * transcoder writes the spec's constant rate the way the `fps` filter does, so the oracle runs
     * the same filter followed by `fps` at [rate].
     */
    private fun assertFrames(expected: List<Int>, output: String, input: String, oracleFilter: String, rate: Rational) {
        assertEquals(expected, TranscodeFixtures.decodedFrameIndices(output), "input frames the output shows")
        MediaOracle.videoFrameCount(output)?.let { assertEquals(expected.size, it, "frames ffprobe counts in the output") }
        val reference = path("mkv")
        val oracleArguments = listOf("-vf", "$oracleFilter,fps=$rate", "-c:v", "mpeg4", "-q:v", "2")
        if (MediaOracle.reference(input, oracleArguments, reference)) {
            assertEquals(expected, TranscodeFixtures.decodedFrameIndices(reference), "input frames ffmpeg shows with $oracleFilter")
        }
    }

    /**
     * The issue's reproduction: the first second, slowed by two, lasts about two seconds.
     * Red when the encoder checks the trim end again: 13 frames and 0.96 s.
     */
    @Test
    fun slowedDownSelectionLastsTwiceAsLong() {
        val output = transcodeVideo(twoSeconds(), "setpts=2*PTS", Rational(25, 1), endMicros = 1_000_000L)
        val duration = MediaSource.open(output).use { assertNotNullDuration(it.durationMicros) }
        assertTrue(duration in 1_900_000L..2_200_000L, "the slowed first second lasts $duration us")
        MediaOracle.durationMicros(output)?.let { probed ->
            assertTrue(probed in 1_900_000L..2_200_000L, "ffprobe reads $probed us")
        }
    }

    /** Frames 0 to 1000 ms, every 80 ms after the filter: 26 frames at 12.5 fps. */
    @Test
    fun slowingDownKeepsEverySelectedFrame() {
        val input = twoSeconds()
        val rate = Rational(25, 2)
        val output = transcodeVideo(input, "setpts=2*PTS", rate, endMicros = 1_020_000L)
        assertFrames((0..25).toList(), output, input, "trim=end=1.02,setpts=2*PTS", rate)
        val times = TranscodeFixtures.decodedFrameTimes(output)
        assertEquals(2_000_000L, times.last() - times.first(), "the last kept frame sits two seconds after the first")
    }

    /** Frames 0 to 1000 ms, every 20 ms after the filter: 26 frames at 50 fps. */
    @Test
    fun speedingUpKeepsEverySelectedFrame() {
        val input = twoSeconds()
        val rate = Rational(50, 1)
        val output = transcodeVideo(input, "setpts=0.5*PTS", rate, endMicros = 1_020_000L)
        assertFrames((0..25).toList(), output, input, "trim=end=1.02,setpts=0.5*PTS", rate)
        val times = TranscodeFixtures.decodedFrameTimes(output)
        assertEquals(500_000L, times.last() - times.first(), "the last kept frame sits half a second after the first")
    }

    /** Red when the encoder checks the trim end again: every frame moved past it and one survived. */
    @Test
    fun aTimestampOffsetKeepsEverySelectedFrame() {
        val input = twoSeconds()
        val rate = Rational(25, 1)
        val output = transcodeVideo(input, "setpts=PTS+1/TB", rate, endMicros = 1_020_000L)
        assertFrames((0..25).toList(), output, input, "trim=end=1.02,setpts=PTS+1/TB", rate)
    }

    /**
     * Frames 520 to 1480 ms, slowed by two. Red when the encoder checks the trim end again: the
     * filtered frames pass 1.5 s from the seventh on.
     */
    @Test
    fun aTrimStartAndASlowDownKeepEverySelectedFrame() {
        val input = twoSeconds()
        val output = transcodeVideo(
            input,
            "setpts=2*PTS",
            Rational(25, 2),
            startMicros = 500_000L,
            endMicros = 1_500_000L,
        )
        assertFrames((13..37).toList(), output, input, "trim=start=0.5:end=1.5,setpts=2*PTS", Rational(25, 2))
    }

    /**
     * Half a second of audio at half speed lasts a second. Red when the encoder checks the trim end
     * again: the output stops near half a second. The oracle runs `atempo` on the same selection.
     */
    @Test
    fun slowedDownAudioKeepsEverySelectedSample() {
        val rate = 48_000
        val input = path("wav")
        TranscodeFixtures.writePcm(input, sampleCount = rate, sampleRate = rate, channels = 1) { i, _ ->
            (i % 20_000).toShort()
        }
        val output = path("wav")
        runBlocking {
            Transcoder.transcode(
                input = input,
                output = output,
                audioSpec = TranscodeFixtures.pcmSpec(rate, 1),
                audioFilter = "atempo=0.5",
                endMicros = 500_000L,
            )
        }
        val samples = TranscodeFixtures.decodedSampleCount(output)
        // atempo works in windows of a few thousand samples, so its length is only near double.
        assertTrue(abs(samples - rate) < 4_096, "half a second at half speed came out as $samples samples")
        val reference = path("wav")
        if (MediaOracle.reference(input, listOf("-af", "atrim=end=0.5,atempo=0.5", "-c:a", "pcm_s16le"), reference)) {
            assertEquals(MediaOracle.audioSampleCount(reference), samples, "samples ffmpeg made from the same selection")
        }
    }

    private fun assertNotNullDuration(duration: Long?): Long = checkNotNull(duration) { "the output declares no duration" }
}
