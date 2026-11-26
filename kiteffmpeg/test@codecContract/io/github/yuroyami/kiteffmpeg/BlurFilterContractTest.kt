package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two blurs that every build carries, `gblur` and `avgblur` (#222). They take the place of
 * `boxblur`, which is GPL in FFmpeg. Each one softens a hard edge and leaves the far side of the
 * picture as it was.
 */
class BlurFilterContractTest {

    /** A picture that is black left of the middle and white right of it. */
    private fun edge(): Frame {
        val bytes = ByteArray(SIZE * SIZE * 3 / 2) { 128.toByte() }
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) bytes[y * SIZE + x] = (if (x < SIZE / 2) BLACK else WHITE).toByte()
        }
        return Frame.ofVideo(bytes, SIZE, SIZE, PixelFormat.Yuv420p, ptsMicros = 0L)
    }

    /** The luma row in the middle of what [description] makes of [edge]. */
    private fun blurredRow(description: String): IntArray = runBlocking {
        FilterGraph.buildVideo(
            description = description,
            width = SIZE,
            height = SIZE,
            pixelFormat = PixelFormat.Yuv420p,
            timeBase = Rational(1, 1_000_000),
            frameRate = Rational(25, 1),
        ).use { graph ->
            val frames = graph.process(flowOf(edge())).toList()
            assertEquals(1, frames.size, "$description gave ${frames.size} frames")
            frames.single().use { frame ->
                assertEquals(SIZE, frame.info.width, description)
                val luma = frame.copyPlanesToByteArray()
                IntArray(SIZE) { x -> luma[SIZE / 2 * SIZE + x].toInt() and 0xFF }
            }
        }
    }

    private fun assertTheEdgeIsSoftened(description: String) {
        val row = blurredRow(description)
        assertTrue(row[SIZE / 2 - 1] > BLACK + 20, "$description left the dark side of the edge at ${row[SIZE / 2 - 1]}: ${row.toList()}")
        assertTrue(row[SIZE / 2] < WHITE - 20, "$description left the bright side of the edge at ${row[SIZE / 2]}: ${row.toList()}")
        assertTrue(row.first() < BLACK + 4, "$description changed the far dark side to ${row.first()}")
        assertTrue(row.last() > WHITE - 4, "$description changed the far bright side to ${row.last()}")
    }

    @Test
    fun gblurSoftensAnEdge() = assertTheEdgeIsSoftened("gblur=sigma=5")

    @Test
    fun avgblurSoftensAnEdge() = assertTheEdgeIsSoftened("avgblur=sizeX=4")

    private companion object {
        const val SIZE = 64
        const val BLACK = 16
        const val WHITE = 235
    }
}
