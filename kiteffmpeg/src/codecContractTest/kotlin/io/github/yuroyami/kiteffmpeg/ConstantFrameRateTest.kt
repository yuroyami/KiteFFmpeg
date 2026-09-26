package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The constant-rate converter at the edges of what a Long holds. */
internal class ConstantFrameRateTest {

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
