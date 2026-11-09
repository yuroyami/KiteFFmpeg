package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A JPEG is read as full range, so a thumbnail of limited-range video must have its samples
 * expanded: black at 16 has to come out at 0 and white at 235 at 255 (#193). FFmpeg's MJPEG
 * encoder takes plain yuv420p and expands nothing, so the thumbnails used to be grey.
 */
class JpegRangeContractTest {

    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** The luma the JPEG of a flat limited-range picture at [luma] decodes to. */
    private fun jpegLumaOf(luma: Int): Int {
        val picture = ByteArray(16 * 16) { luma.toByte() } + ByteArray(16 * 16 / 2) { 128.toByte() }
        val jpeg = Frame.ofVideo(picture, 16, 16, PixelFormat.Yuv420p, ptsMicros = 0L).use { it.encodeImage(CodecId.Mjpeg) }
        val path = materializeContractMedia(jpeg, sha256Hex(jpeg)).also(paths::add)
        return MediaSource.open(path).use { source ->
            val video = checkNotNull(source.primaryVideo) { "the JPEG has no picture" }
            val frames = runBlocking { source.decodedFrames(video).toList() }
            try {
                frames.first().copyPlanesToByteArray()[0].toInt() and 0xFF
            } finally {
                frames.forEach(Frame::close)
            }
        }
    }

    @Test
    fun aLimitedRangeBlackComesOutBlack() {
        val black = jpegLumaOf(16)
        assertTrue(black < 4, "limited black decoded from the JPEG at $black, not near 0")
    }

    @Test
    fun aLimitedRangeWhiteComesOutWhite() {
        val white = jpegLumaOf(235)
        assertTrue(white > 250, "limited white decoded from the JPEG at $white, not near 255")
    }
}
