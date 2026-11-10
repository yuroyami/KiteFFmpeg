package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An extraction decodes from the keyframe before its target, not from five seconds before that
 * (#252). With a keyframe every 50 frames, no target needs more than 50 frames and the B-frame
 * delay; the five second back-off decoded up to 125 more at 25 fps.
 */
class ExtractFrameWalkTest {

    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private fun walksFromTheKeyframeBeforeEachTarget(extension: String) {
        val input = contractOutputPath(extension).also(paths::add)
        val made = MediaOracle.generate(
            listOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=20", "-c:v", "mpeg4", "-g", "50", "-bf", "2", "-q:v", "5"),
            input,
        )
        if (!made) return println("extract walk degraded: no ffmpeg")
        MediaSource.open(input).use { source ->
            val walks = (6..19).map { second ->
                runBlocking { source.extractFrame(second * 1_000_000L - 120_000L) }.close()
                source.extractWalk
            }
            assertTrue(walks.all { it in 1..54 }, "$extension decoded more than the keyframe's group for a target: $walks")
        }
    }

    @Test
    fun anMp4ExtractionWalksFromTheKeyframeBeforeItsTarget() = walksFromTheKeyframeBeforeEachTarget("mp4")

    @Test
    fun aMatroskaExtractionWalksFromTheKeyframeBeforeItsTarget() = walksFromTheKeyframeBeforeEachTarget("mkv")

    @Test
    fun anMpegTsExtractionWalksFromTheKeyframeBeforeItsTarget() = walksFromTheKeyframeBeforeEachTarget("ts")
}
