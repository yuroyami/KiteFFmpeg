@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.QuarterTurn
import io.github.yuroyami.kiteffmpeg.dsl.audioFilters
import io.github.yuroyami.kiteffmpeg.dsl.videoFilters
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The editing filters that the filter DSL offers are in the recipe (#77).
 *
 * This is a macOS test on purpose. The macOS CI job bakes its FFmpeg tree from the current recipe,
 * and the other jobs link released trees, which can predate a recipe change.
 */
class EditingFiltersTest {

    @Test
    fun everyEditingFilterTheDslOffersIsCompiledIn() {
        val video = videoFilters {
            crop(32, 24)
            transpose(QuarterTurn.Clockwise)
            fps(Rational(25, 1))
            drawBox(0, 0, 8, 8)
        }.missingFilters()
        val audio = audioFilters { pan("stereo", "c0=FL", "c1=FR") }.missingFilters()
        assertEquals(emptyList(), video + audio)

        val wanted = listOf("crop", "transpose", "hflip", "vflip", "fps", "drawbox", "fade", "pan", "setsar", "setdar")
        assertEquals(emptyList(), wanted.filterNot(FFmpeg::hasFilter))
    }

    @Test
    fun aCropGraphTurnsA64PixelFrameInto32Pixels() {
        val graph = FilterGraph.buildVideo(
            description = "crop=32:24:0:0",
            width = 64,
            height = 48,
            pixelFormat = PixelFormat.Yuv420p,
            timeBase = Rational(1, 25),
            frameRate = Rational(25, 1),
        )
        try {
            val grey = ByteArray(64 * 48 * 3 / 2) { 128.toByte() }
            val widths = mutableListOf<Int>()
            graph.feedInput(0, Frame.ofVideo(grey, 64, 48, PixelFormat.Yuv420p, ptsMicros = 0)) { widths += it.info.width }
            graph.flushInput(0) { widths += it.info.width }
            assertEquals(listOf(32), widths)
        } finally {
            graph.close()
        }
    }
}
