package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A display matrix that `ffmpeg` wrote into a real file, read back through [MediaSource]: a mirror
 * is reported as a mirror, not as a half turn. Skipped where there is no command-line oracle, which
 * is an Android device.
 */
internal class DisplayMatrixContractTest {
    private val paths = mutableListOf<String>()

    private fun path(): String = contractOutputPath("mp4").also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** The video stream of a file whose display matrix [options] set, or null without an oracle. */
    private fun videoWith(vararg options: String): StreamInfo? {
        val plain = path()
        val source = listOf("-f", "lavfi", "-i", "testsrc=size=64x48:rate=1:duration=1", "-c:v", "mpeg4")
        if (!MediaOracle.generate(source, plain)) return null
        val marked = path()
        // The display matrix is an input option in ffmpeg, so it takes a second, copying pass.
        if (!MediaOracle.generate(options.toList() + listOf("-i", plain, "-c", "copy"), marked)) return null
        return MediaSource.open(marked).use { opened -> opened.streams.first { it.type == MediaType.Video } }
    }

    @Test
    fun aLeftRightMirrorIsAMirrorWithNoTurn() {
        val video = videoWith("-display_hflip") ?: return println("display matrix contract degraded: no ffmpeg")
        assertEquals(0 to true, video.rotationDegrees to video.mirrored)
    }

    @Test
    fun anUpsideDownMirrorIsAMirrorAndAHalfTurn() {
        val video = videoWith("-display_vflip") ?: return println("display matrix contract degraded: no ffmpeg")
        assertEquals(180 to true, video.rotationDegrees to video.mirrored)
    }

    @Test
    fun aTurnAloneIsNoMirror() {
        // -display_rotation counts counter-clockwise, and the stream reports the clockwise turn.
        val video = videoWith("-display_rotation", "90") ?: return println("display matrix contract degraded: no ffmpeg")
        assertEquals(270 to false, video.rotationDegrees to video.mirrored)
    }
}
