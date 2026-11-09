package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phones store portrait video as a landscape picture and a display matrix that turns it upright.
 * A transcode used to drop the matrix, so the clip played on its side everywhere (#270). It now
 * keeps it, unless the caller's own filter turns the picture.
 */
class TranscodeOrientationContractTest {

    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** One second of mpeg4 at 160x120 in MP4 with a 90 degree display rotation. */
    private fun portrait(): String? {
        val plain = path("mp4")
        if (!MediaOracle.generate(listOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=10:duration=1", "-c:v", "mpeg4"), plain)) return null
        val turned = path("mp4")
        if (!MediaOracle.generate(listOf("-display_rotation:v:0", "90", "-i", plain, "-c", "copy"), turned)) return null
        return turned
    }

    private val spec = VideoEncoderSpec(codec = CodecId("mpeg4"), width = 160, height = 120, frameRate = Rational(10, 1), bitrateBps = 500_000L)

    private fun rotationOf(path: String): Int = MediaSource.open(path).use { checkNotNull(it.primaryVideo).rotationDegrees }

    @Test
    fun aTranscodedPortraitClipKeepsItsRotation() {
        val input = portrait() ?: return println("orientation contract degraded: no ffmpeg")
        val source = rotationOf(input)
        assertEquals(true, source != 0, "the fixture must carry a rotation")
        val output = path("mp4")
        runBlocking { Transcoder.transcode(input = input, output = output, spec = spec) }
        assertEquals(source, rotationOf(output), "the output turns the picture as the source did")
    }

    @Test
    fun aFilterThatTurnsThePictureLeavesTheMatrixBehind() {
        val input = portrait() ?: return println("orientation contract degraded: no ffmpeg")
        val output = path("mp4")
        runBlocking {
            Transcoder.transcode(
                input = input,
                output = output,
                spec = VideoEncoderSpec(codec = CodecId("mpeg4"), width = 120, height = 160, frameRate = Rational(10, 1), bitrateBps = 500_000L),
                videoFilter = "transpose=clock",
            )
        }
        assertEquals(0, rotationOf(output), "a picture the filter already turned is not turned again")
    }
}
