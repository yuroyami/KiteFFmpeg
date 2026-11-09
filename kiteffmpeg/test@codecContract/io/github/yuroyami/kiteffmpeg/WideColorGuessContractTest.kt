package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The C conversion guesses an untagged frame's matrix by the same rule as [ColorInfo.guessFor]:
 * a 1280x536 film is BT.709 by its width (#194). Before, the C side took BT.709 only from 720
 * lines, so BT.709 red came out of the conversion as 233, 0, 2.
 */
class WideColorGuessContractTest {

    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    @Test
    fun anUntaggedScopeFilmConvertsWithTheBt709Matrix() {
        val width = 1280
        val height = 536
        // Pure red in BT.709 at limited range.
        val picture = ByteArray(width * height) { 63.toByte() } +
            ByteArray(width * height / 4) { 102.toByte() } +
            ByteArray(width * height / 4) { 240.toByte() }
        val png = Frame.ofVideo(picture, width, height, PixelFormat.Yuv420p, ptsMicros = 0L).use { it.encodeImage(CodecId.Png) }
        val path = materializeContractMedia(png, sha256Hex(png)).also(paths::add)
        val rgb = MediaSource.open(path).use { source ->
            val video = checkNotNull(source.primaryVideo)
            val frames = runBlocking { source.decodedFrames(video).toList() }
            try {
                frames.first().copyPlanesToByteArray().copyOfRange(0, 3).map { it.toInt() and 0xFF }
            } finally {
                frames.forEach(Frame::close)
            }
        }
        assertTrue(rgb[0] > 250 && rgb[1] < 6 && rgb[2] < 6, "BT.709 red converted to $rgb")
    }
}
