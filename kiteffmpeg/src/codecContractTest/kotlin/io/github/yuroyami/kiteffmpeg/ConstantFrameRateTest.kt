package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The constant-rate converter at the edges of what a Long holds, and where a decoded stream ends. */
internal class ConstantFrameRateTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /**
     * The ticks at [output] that the decoded frames of a Matroska file fill, written at [rate] with
     * frames at [timesMillis]. Matroska counts in milliseconds, so at 1000 fps a frame's duration
     * is one unit of its time base.
     */
    private fun ticksOfDecoded(rate: Rational, timesMillis: List<Long>, output: Rational = Rational(25, 1)): List<Long> {
        val path = contractOutputPath("mkv").also(paths::add)
        TranscodeFixtures.writeVideo(path, rate, timesMillis.map { it * 1_000L })
        val ticks = mutableListOf<Long>()
        MediaSource.open(path).use { source ->
            val frames = runBlocking { source.decodedFrames(checkNotNull(source.primaryVideo)).toList() }
            ConstantFrameRate(output, durationsHold = true).use { rate ->
                frames.forEach { frame -> frame.use { rate.push(it) { _, tick -> ticks += tick } } }
                rate.finish { _, tick -> ticks += tick }
            }
        }
        return ticks
    }

    /**
     * A file written at 1000 fps to place twelve frames at uneven times gives each frame one
     * millisecond, which is what its rate says and not how long the last one shows: FFmpeg's
     * command line reads a one-unit duration after a wider gap as made up and takes the gap, so
     * the frame at 1000 ms lasts the 300 ms before it and the output runs to 1300 ms, 33 ticks.
     * Red when the duration was taken as it stands: 25 ticks, and the last frame never showed.
     */
    @Test
    fun aOneUnitDurationAfterAWiderGapLastsAsLongAsTheGap() {
        val ticks = ticksOfDecoded(Rational(1_000, 1), listOf(0L, 10, 50, 60, 70, 200, 210, 400, 420, 440, 700, 1_000))
        assertEquals((0L..32L).toList(), ticks)
    }

    /**
     * Only a gap more than twice the duration makes it a made-up one: after a gap of two units the
     * last frame keeps its one, so at 1000 fps out it fills the tick at 12 ms and no more.
     */
    @Test
    fun aOneUnitDurationAfterATwoUnitGapStands() {
        val ticks = ticksOfDecoded(Rational(1_000, 1), listOf(0L, 10, 12), output = Rational(1_000, 1))
        assertEquals((0L..12L).toList(), ticks)
    }

    private fun frameAt(ptsMicros: Long): Frame = Frame.ofVideo(
        bytes = ByteArray(TranscodeFixtures.WIDTH * TranscodeFixtures.HEIGHT * 3 / 2),
        width = TranscodeFixtures.WIDTH,
        height = TranscodeFixtures.HEIGHT,
        pixelFormat = PixelFormat.Yuv420p,
        ptsMicros = ptsMicros,
    )

    /**
     * One output frame every 2,000,000,000 seconds. The second frame starts 5e18 microseconds in
     * and lasts as long as the gap before it, so its end is past Long.MAX_VALUE. It still shows
     * for one tick. Red when the end wrapped below zero: the last frame never showed.
     */
    @Test
    fun aFrameWhoseEndPassesTheLongRangeShowsForOneTick() {
        val ticks = mutableListOf<Long>()
        ConstantFrameRate(Rational(1, 2_000_000_000), durationsHold = false).use { rate ->
            for (pts in listOf(0L, 5_000_000_000_000_000_000L)) {
                frameAt(pts).use { frame -> rate.push(frame) { _, tick -> ticks += tick } }
            }
            rate.finish { _, tick -> ticks += tick }
        }
        assertEquals((0L..2_500L).toList(), ticks)
    }

    @Test
    fun aRateThatIsNotPositiveIsRefused() {
        assertFailsWith<IllegalArgumentException> { ConstantFrameRate(Rational(-25, 1), durationsHold = true) }
    }
}
