@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A transcode of a descriptor opens it once (#197). FFmpeg's `fd` protocol duplicates the
 * descriptor and never rewinds it, and a duplicate shares the file offset, so the second open that
 * reads the encoder's first frame started where the first open left the offset: a Matroska input
 * failed with invalid data, and an MP4 one wrote a short file.
 */
class TranscodeDescriptorContractTest {

    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** Three seconds of mpeg4 at 160x120 and 10 fps, in [extension]'s container. */
    private fun clip(extension: String): String? {
        val out = path(extension)
        val made = MediaOracle.generate(listOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=10:duration=3", "-c:v", "mpeg4"), out)
        return out.takeIf { made }
    }

    private val spec = VideoEncoderSpec(codec = CodecId("mpeg4"), width = 160, height = 120, frameRate = Rational(10, 1), bitrateBps = 500_000L)

    private fun framesOf(path: String): Int = MediaSource.open(path).use { source ->
        var count = 0
        runBlocking { source.decodedFrames(checkNotNull(source.primaryVideo)).collect { it.close(); count++ } }
        count
    }

    private fun transcodesWholeThroughADescriptor(extension: String) {
        val input = clip(extension) ?: return println("descriptor contract degraded: no ffmpeg")
        val byPath = path("mkv")
        runBlocking { Transcoder.transcode(input = input, output = byPath, spec = spec) }
        val fd = open(input, O_RDONLY)
        assertTrue(fd >= 0, "the fixture opens")
        val byDescriptor = path("mkv")
        try {
            runBlocking { Transcoder.transcode("fd:", mapOf("fd" to fd.toString()), output = byDescriptor, spec = spec) }
        } finally {
            close(fd)
        }
        val expected = framesOf(byPath)
        assertEquals(30, expected, "the transcode by path keeps every frame")
        assertEquals(expected, framesOf(byDescriptor), "the transcode through the descriptor lost frames")
    }

    @Test
    fun aMatroskaInputTranscodesWholeThroughADescriptor() = transcodesWholeThroughADescriptor("mkv")

    @Test
    fun anMp4InputTranscodesWholeThroughADescriptor() = transcodesWholeThroughADescriptor("mp4")
}
