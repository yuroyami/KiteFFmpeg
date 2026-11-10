package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A stream that cannot seek by time, here raw Annex-B H.264 whose frames carry no timestamp, gives
 * extractFrame the frame at its target: it goes back to the first byte and counts frames at the
 * stream's rate (#253). It used to fail at the seek with a bare EPERM.
 */
class UnseekableExtractTest {

    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private fun rawStream(): String? {
        val input = contractOutputPath("h264").also(paths::add)
        val made = MediaOracle.generate(
            listOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=4", "-c:v", "libx264", "-g", "25", "-bf", "0", "-f", "h264"),
            input,
        )
        return input.takeIf { made }
    }

    @Test
    fun aStreamThatCannotSeekByTimeIsDecodedFromItsFirstByte() = runBlocking {
        val input = rawStream() ?: return@runBlocking println("unseekable extract degraded: no ffmpeg")
        val pictures = MediaSource.open(input).use { source ->
            source.decodedFrames(checkNotNull(source.primaryVideo)).toList().map { frame ->
                frame.use { it.copyPlanesToByteArray() }
            }
        }
        assertEquals(100, pictures.size, "four seconds at 25 fps")
        MediaSource.open(input).use { source ->
            // Twice, and backwards the second time, after the first call read past the start.
            for (target in listOf(2_000_000L, 400_000L)) {
                val index = (target / 40_000L).toInt()
                source.extractFrame(target).use { frame ->
                    assertContentEquals(pictures[index], frame.copyPlanesToByteArray(), "the picture at ${target}µs is frame $index")
                }
            }
        }
    }

    @Test
    fun theAsyncRuntimeRefusesSuchAStreamWithTheReason() = runBlocking {
        val input = rawStream() ?: return@runBlocking println("unseekable extract degraded: no ffmpeg")
        val runtime = FFmpeg.createAsyncRuntime()
        try {
            runtime.open(AsyncBytes(readContractBytes(input))).useAsync { source ->
                val refused = assertFailsWith<FFmpegException> { source.extractFrame(1_000_000L) }
                assertIs<FFmpegError.Unsupported>(refused.error, refused.message)
                assertTrue("cannot seek" in refused.error.message, refused.error.message)
            }
        } finally {
            runtime.close()
        }
    }
}
