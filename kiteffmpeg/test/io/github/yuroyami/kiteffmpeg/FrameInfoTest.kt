package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameInfoTest {

    private fun videoFrame(pts: Long, tb: Rational = Rational(1, 30)) = FrameInfo(
        streamIndex = 0,
        type = MediaType.Video,
        pts = pts,
        timeBase = tb,
    )

    @Test
    fun ptsSecondsUsesTimeBase() {
        assertEquals(2.0, videoFrame(pts = 60).ptsSeconds)
        assertEquals(0.5, videoFrame(pts = 45_000, tb = Rational(1, 90_000)).ptsSeconds)
    }

    @Test
    fun noptsDetected() {
        val f = videoFrame(pts = FrameInfo.NOPTS)
        assertFalse(f.hasPts)
        assertTrue(f.ptsSeconds.isNaN())
        assertTrue(videoFrame(pts = 0).hasPts)
    }

    @Test
    fun mediaTypeClassification() {
        assertTrue(MediaType.Video.isAv)
        assertTrue(MediaType.Audio.isAv)
        assertFalse(MediaType.Subtitle.isAv)
        assertFalse(MediaType.Unknown.isAv)
    }

    @Test
    fun sampleFormatPlanarHeuristic() {
        assertTrue(SampleFormat.FltP.isPlanar)
        assertFalse(SampleFormat.S16.isPlanar)
    }
}
