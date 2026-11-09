package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A frame made from RGBA bytes is converted for the encoder, and the pixels must be made with the
 * colour the encoder declares (#195). Before, the matrix came from the height alone and the range
 * from the frame, so a 640x360 video declared BT.709 held BT.601 pixels, and one declared full
 * range held 16 to 235.
 */
class EncoderColorConversionContractTest {

    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private val width = 640
    private val height = 360

    /** Encodes three frames of one RGBA colour with [color] declared, and answers the decoded Y, U and V. */
    private fun encodedYuv(red: Int, green: Int, blue: Int, color: ColorInfo): List<Int> {
        val path = contractOutputPath("mkv").also(paths::add)
        val rgba = ByteArray(width * height * 4) { i ->
            when (i % 4) { 0 -> red; 1 -> green; 2 -> blue; else -> 255 }.toByte()
        }
        MediaSink.open(path).use { sink ->
            val encoder = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"), width = width, height = height, frameRate = Rational(10, 1),
                    bitrateBps = 4_000_000L, color = color,
                ),
            )
            runBlocking {
                encoder.drive(flowOf(*Array(3) { Frame.ofVideo(rgba, width, height, PixelFormat.Rgba, ptsMicros = it * 100_000L) }))
            }
        }
        return MediaSource.open(path).use { source ->
            val frames = runBlocking { source.decodedFrames(checkNotNull(source.primaryVideo)).toList() }
            try {
                val bytes = frames.first().copyPlanesToByteArray()
                val luma = width * height
                listOf(bytes[luma / 2 + width / 2], bytes[luma + luma / 8], bytes[luma + luma / 4 + luma / 8]).map { it.toInt() and 0xFF }
            } finally {
                frames.forEach(Frame::close)
            }
        }
    }

    private fun near(expected: List<Int>, actual: List<Int>) =
        expected.zip(actual).all { (e, a) -> abs(e - a) <= 2 }

    @Test
    fun redDeclaredBt709AtStandardDefinitionSizeIsBt709Red() {
        val yuv = encodedYuv(255, 0, 0, ColorInfo(matrix = ColorMatrix.Bt709))
        assertTrue(near(listOf(63, 102, 240), yuv), "BT.709 limited red is 63, 102, 240, not $yuv")
    }

    @Test
    fun blackDeclaredFullRangeIsZero() {
        val yuv = encodedYuv(0, 0, 0, ColorInfo(matrix = ColorMatrix.Bt709, fullRange = true, rangeSpecified = true))
        assertTrue(yuv[0] <= 2, "full range black is 0, not ${yuv[0]}")
    }
}
