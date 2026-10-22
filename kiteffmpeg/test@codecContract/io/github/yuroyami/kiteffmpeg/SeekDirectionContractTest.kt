package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a packet reader's seek lands, on real files with a keyframe every two seconds. MP4 and
 * Matroska both lack their own two-sided seek, which is where an unbounded window turned Forward
 * into Backward. Skipped where there is no command-line oracle, which is an Android device.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
internal class SeekDirectionContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** Twelve seconds at 24 frames a second with a keyframe every 48 frames, or null without an oracle. */
    private fun clip(extension: String): String? {
        val output = contractOutputPath(extension).also(paths::add)
        val written = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=24:duration=12",
                "-c:v", "mpeg4", "-g", "48", "-keyint_min", "48", "-sc_threshold", "0",
            ),
            output,
        )
        return output.takeIf { written }
    }

    /** Where the first packet after a seek to 5 seconds sits, from the start of the content. */
    private fun landing(path: String, direction: SeekDirection): Long = MediaSource.open(path).use { source ->
        val video = source.streams.first { it.type == MediaType.Video }
        source.openPacketReader(listOf(video)).use { reader ->
            reader.seek(5_000_000L, direction)
            reader.read()!!.use { (it.ptsMicros ?: 0L) - source.startTimeMicros }
        }
    }

    @Test
    fun forwardLandsOnTheKeyframeAfterTheTargetAndBackwardOnTheOneBefore() {
        for (extension in listOf("mp4", "mkv")) {
            val path = clip(extension) ?: return println("seek direction contract degraded: no ffmpeg")
            assertEquals(6_000_000L, landing(path, SeekDirection.Forward), "Forward in $extension")
            assertEquals(4_000_000L, landing(path, SeekDirection.Backward), "Backward in $extension")
        }
    }
}
