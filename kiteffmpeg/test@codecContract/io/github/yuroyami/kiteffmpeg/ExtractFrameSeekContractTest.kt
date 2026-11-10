package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * extractFrame answers the first frame at or after its target on every container, now that a seek
 * for the only picture stream aims at the target itself rather than five seconds before it (#252).
 * MPEG-TS is the case the old back-off was for: it has no index, so its seek goes by byte position.
 */
class ExtractFrameSeekContractTest {

    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** Twenty seconds of mpeg4 at 25 fps with B-frames and a keyframe every two seconds. */
    private fun clip(extension: String): String? {
        val out = path(extension)
        val made = MediaOracle.generate(
            listOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=20", "-c:v", "mpeg4", "-g", "50", "-bf", "2", "-q:v", "5"),
            out,
        )
        return out.takeIf { made }
    }

    /** Every frame's time in microseconds from the media's start, in display order. */
    private fun frameTimes(path: String): List<Long> = MediaSource.open(path).use { source ->
        val video = checkNotNull(source.primaryVideo)
        val times = mutableListOf<Long>()
        runBlocking {
            source.decodedFrames(video).collect { frame ->
                times += frame.info.pts * 1_000_000L * video.timeBase.num / video.timeBase.den - source.startTimeMicros
                frame.close()
            }
        }
        times.sorted()
    }

    private fun extractsTheFrameAtEachTarget(extension: String) {
        val input = clip(extension) ?: return println("extract contract degraded: no ffmpeg")
        val times = frameTimes(input)
        assertTrue(times.size >= 490, "the fixture decodes whole: ${times.size} frames")
        val targets = (1..19).map { it * 1_000_000L - 120_000L } + listOf(0L, 4_000_000L, 10_000_000L)
        val started = TimeSource.Monotonic.markNow()
        MediaSource.open(input).use { source ->
            val video = checkNotNull(source.primaryVideo)
            for (target in targets) {
                val expected = times.first { it >= target - 1_000 }
                val frame = runBlocking { source.extractFrame(target) }
                val got = frame.use { it.info.pts * 1_000_000L * video.timeBase.num / video.timeBase.den - source.startTimeMicros }
                assertTrue(kotlin.math.abs(got - expected) <= 1_000, "$extension at $target us: got $got, wanted $expected")
            }
        }
        println("extract contract $extension: ${targets.size} frames in ${started.elapsedNow()}")
    }

    @Test
    fun anMp4ClipExtractsTheFrameAtEachTarget() = extractsTheFrameAtEachTarget("mp4")

    @Test
    fun aMatroskaClipExtractsTheFrameAtEachTarget() = extractsTheFrameAtEachTarget("mkv")

    @Test
    fun anMpegTsClipExtractsTheFrameAtEachTarget() = extractsTheFrameAtEachTarget("ts")
}
