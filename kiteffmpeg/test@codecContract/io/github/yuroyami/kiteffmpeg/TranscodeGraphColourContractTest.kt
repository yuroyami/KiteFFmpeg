package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A transcode's video graph is built for the matrix and range its frames declare (#214). Built
 * without them, a scale that stays in YUV took FFmpeg's default for an unknown input: a same-size
 * scale of BT.709 full range came out tagged and converted as BT.601 limited, 39 levels off.
 */
class TranscodeGraphColourContractTest {

    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private fun clip(matrix: String, codecMatrix: String, range: String): String? {
        val out = contractOutputPath("mkv").also(paths::add)
        val made = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=10:duration=1",
                "-vf", "scale=out_color_matrix=$matrix:out_range=$range",
                "-c:v", "ffv1", "-pix_fmt", "yuv420p", "-colorspace", codecMatrix, "-color_range", range,
            ),
            out,
        )
        return out.takeIf { made }
    }

    private fun aSameSizeScaleKeepsTheFrames(matrix: String, codecMatrix: String, range: String, expected: ColorMatrix) {
        val input = clip(matrix, codecMatrix, range) ?: return println("graph colour contract degraded: no ffmpeg")
        MediaSource.open(input).use { source ->
            val video = checkNotNull(source.primaryVideo)
            transcodeVideoGraph("scale=320:240", video).use { graph ->
                var compared = 0
                runBlocking {
                    source.decodedFrames(video).collect { frame ->
                        val before = frame.copyPlanesToByteArray()
                        val inColor = frame.info.color
                        graph.feed(frame, { out ->
                            out.use {
                                assertEquals(expected, it.info.color.matrix, "the output kept its matrix")
                                assertEquals(inColor.fullRange, it.info.color.fullRange, "the output kept its range")
                                assertTrue(it.info.color.rangeSpecified, "the output still declares its range")
                                assertTrue(before.contentEquals(it.copyPlanesToByteArray()), "a same-size scale changed the pixels")
                                compared++
                            }
                        })
                    }
                }
                graph.flush { it.close() }
                assertTrue(compared > 0, "the graph gave no frame")
            }
        }
    }

    @Test
    fun aBt709FullRangeFrameKeepsItsColour() = aSameSizeScaleKeepsTheFrames("bt709", "bt709", "pc", ColorMatrix.Bt709)

    @Test
    fun aBt709LimitedRangeFrameKeepsItsColour() = aSameSizeScaleKeepsTheFrames("bt709", "bt709", "tv", ColorMatrix.Bt709)

    @Test
    fun aBt2020LimitedRangeFrameKeepsItsColour() = aSameSizeScaleKeepsTheFrames("bt2020", "bt2020nc", "tv", ColorMatrix.Bt2020Ncl)
}
