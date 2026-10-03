@file:OptIn(KiteFFmpegLowLevelApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import platform.posix.remove
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * A sink refuses to copy a stream from the file it writes (#146). Writing its header would
 * truncate the media the source reads. The links and other spellings of a path are covered where
 * the platform can make them: SameFileNativeTest on macOS for the identity itself, and
 * SameFileRefusalTest on the JVM for the sink.
 */
class SinkSameFileTest {

    private val made = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        made.forEach { remove(it) }
        made.clear()
    }

    private fun clip(): String {
        val path = "${systemTempRoot()}/kiteffmpeg-sink-same-${Random.nextLong().toULong()}.mkv".also(made::add)
        MediaSink.open(path).use { sink ->
            val video = sink.addVideoEncoder(VideoEncoderSpec(CodecId("mpeg4"), 64, 64, frameRate = Rational(25, 1)))
            runBlocking {
                video.drive(
                    (0 until 25).asFlow().map { i ->
                        val pixels = ByteArray(64 * 64 * 3 / 2) { ((it + i * 7) % 251).toByte() }
                        Frame.ofVideo(pixels, 64, 64, PixelFormat.Yuv420p, i * 40_000L)
                    },
                )
            }
        }
        return path
    }

    private fun bytesOf(path: String): ByteArray {
        val file = platform.posix.fopen(path, "rb") ?: error("cannot open $path")
        try {
            platform.posix.fseek(file, 0, platform.posix.SEEK_END)
            val size = platform.posix.ftell(file).toInt()
            platform.posix.fseek(file, 0, platform.posix.SEEK_SET)
            val bytes = ByteArray(size)
            if (size > 0) {
                bytes.usePinned { pinned ->
                    check(platform.posix.fread(pinned.addressOf(0), 1u, size.toULong(), file).toInt() == size) { "short read of $path" }
                }
            }
            return bytes
        } finally {
            platform.posix.fclose(file)
        }
    }

    private fun assertCopyRefusedUntouched(input: String, output: String, opened: String = input) {
        val before = bytesOf(input)
        MediaSource.open(opened).use { source ->
            MediaSink.open(output, format = "matroska").use { sink ->
                val refused = runCatching { sink.addCopyStream(source, source.streams.first()) }.exceptionOrNull()
                assertTrue(refused is FFmpegException && refused.error is FFmpegError.InvalidArgument, "not refused: $refused")
                assertTrue("same file" in refused.message.orEmpty(), "refused for another reason: ${refused.message}")
            }
        }
        assertContentEquals(before, bytesOf(input), "the input changed")
    }

    @Test
    fun aSinkRefusesToCopyFromTheFileItWrites() {
        val input = clip()
        assertCopyRefusedUntouched(input, input)
    }

    @Test
    fun aSinkSeesThroughTheFileProtocolPrefixOnEitherSide() {
        val input = clip()
        assertCopyRefusedUntouched(input, "file:$input")
        assertCopyRefusedUntouched(input, input, opened = "file:$input")
    }
}
