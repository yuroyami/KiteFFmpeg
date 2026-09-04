package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A remux or a transcode whose output IS its input refuses before it writes a byte.
 *
 * The sink truncates the file the source is still reading, and enough packets sit in the
 * source's read buffer for a small, valid-looking output to be written and success returned. A
 * caller who passed the same path twice got a four-frame file back in place of their media.
 */
class SameFileRefusalTest {

    private val files = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        files.forEach { it.delete() }
        files.clear()
    }

    private fun clip(): File {
        val file = File.createTempFile("kiteffmpeg-same-file", ".mkv").also { files += it }
        MediaSink.open(file.absolutePath).use { sink ->
            val video = sink.addVideoEncoder(
                VideoEncoderSpec(CodecId("mpeg4"), 64, 64, frameRate = Rational(25, 1)),
            )
            runBlocking {
                video.drive(
                    (0 until 60).asFlow().map { i ->
                        val pixels = ByteArray(64 * 64 * 3 / 2) { ((it + i * 7) % 251).toByte() }
                        Frame.ofVideo(pixels, 64, 64, PixelFormat.Yuv420p, i * 40_000L)
                    },
                )
            }
        }
        return file
    }

    private val spec = VideoEncoderSpec(CodecId("mpeg4"), 64, 64, frameRate = Rational(25, 1))

    private fun assertRefusedUntouched(input: File, output: String) {
        val before = input.readBytes()
        val remux = assertFailsWith<FFmpegException> {
            runBlocking { Remuxer.remux(input.absolutePath, output) }
        }
        assertTrue("same file" in remux.message.orEmpty(), "remux refused for another reason: ${remux.message}")
        val transcode = assertFailsWith<FFmpegException> {
            runBlocking { Transcoder.transcode(input.absolutePath, output, spec = spec) }
        }
        assertTrue("same file" in transcode.message.orEmpty(), "transcode refused for another reason: ${transcode.message}")
        assertContentEquals(before, input.readBytes(), "the input changed before the refusal")
    }

    @Test
    fun `the same path is refused before any write`() {
        val input = clip()
        assertRefusedUntouched(input, input.absolutePath)
    }

    @Test
    fun `another spelling of the same path is refused too`() {
        val input = clip()
        val spelled = File(input.parentFile, "./${input.name}").path
        assertRefusedUntouched(input, spelled)
    }

    @Test
    fun `a symbolic link to the input is refused too`() {
        val input = clip()
        val link = File(input.parentFile, input.nameWithoutExtension + "-link.mkv").also { files += it }
        Files.createSymbolicLink(link.toPath(), input.toPath())
        assertRefusedUntouched(input, link.absolutePath)
    }

    @Test
    fun `a different file next to the input is still allowed`() {
        val input = clip()
        val output = File(input.parentFile, input.nameWithoutExtension + "-copy.mkv").also { files += it }
        runBlocking { Remuxer.remux(input.absolutePath, output.absolutePath) }
        assertTrue(output.length() > 0L, "an ordinary remux wrote nothing")
    }
}
