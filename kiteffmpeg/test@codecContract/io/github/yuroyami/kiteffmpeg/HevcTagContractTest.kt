package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An HEVC track in MP4 or MOV plays on Apple's players only as hvc1. A copied stream keeps its
 * source's tag where the output maps it to the same codec, as ffmpeg's command line does (#232),
 * and a stream with no tag, as every encoded one is, is written as hvc1 rather than the muxer's
 * first choice, hev1 (#271).
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class HevcTagContractTest {

    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** One second of HEVC from the host's libx265 into [extension], with [tag] when given. */
    private fun fixture(extension: String, tag: String?): String? {
        val output = path(extension)
        val written = MediaOracle.generate(
            listOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=10:duration=1", "-c:v", "libx265", "-x265-params", "log-level=none") +
                (tag?.let { listOf("-tag:v", it) } ?: emptyList()),
            output,
        )
        return output.takeIf { written }
    }

    /** Copies every stream of [input] into a new file of [extension] and answers its HEVC sample entry. */
    private fun remuxedTag(input: String, extension: String): String {
        val output = path(extension)
        MediaSink.open(output).use { sink ->
            MediaSource.open(input).use { source ->
                val copies = source.streams.associate { it.index to sink.addCopyStream(source, it) }
                source.openPacketReader(source.streams).use { reader ->
                    while (true) {
                        val packet = reader.read() ?: break
                        packet.use { copies.getValue(it.streamIndex).write(it) }
                    }
                }
            }
        }
        val text = readContractBytes(output).decodeToString()
        return listOf("hvc1", "hev1").filter { it in text }.joinToString("+").ifEmpty { "none" }
    }

    @Test
    fun anHvc1TrackStaysHvc1() {
        val input = fixture("mp4", "hvc1") ?: return println("hevc tag contract degraded: no ffmpeg with libx265")
        assertEquals("hvc1", remuxedTag(input, "mp4"))
        assertEquals("hvc1", remuxedTag(input, "mov"))
    }

    @Test
    fun anUntaggedHevcTrackIsWrittenAsHvc1() {
        val input = fixture("mkv", null) ?: return println("hevc tag contract degraded: no ffmpeg with libx265")
        assertEquals("hvc1", remuxedTag(input, "mp4"))
    }

    @Test
    fun anHev1TrackStaysHev1AsFFmpegKeepsIt() {
        val input = fixture("mp4", "hev1") ?: return println("hevc tag contract degraded: no ffmpeg with libx265")
        assertEquals("hev1", remuxedTag(input, "mp4"))
    }
}
