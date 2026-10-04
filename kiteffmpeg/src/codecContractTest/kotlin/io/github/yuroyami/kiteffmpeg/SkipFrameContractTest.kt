package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.DecoderSkip
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A running decoder's frame skipping, changed half way through a long GOP (#140).
 *
 * The clip is four seconds of H.264 at 24 fps from the host's libx264, all one GOP, with two
 * B-frames between references and no B-pyramid, so every B-frame is a frame nothing predicts from
 * and every other frame is one something does. `ffprobe` says which frame is which. The clip is
 * decoded from its keyframe with [DecoderSkip.NonReference] for the first half of its packets and
 * [DecoderSkip.None] for the rest, with one decoding thread and with four, which hands packets to
 * frame threads. Skipped where there is no command-line oracle with libx264, which is an Android
 * device or a host `ffmpeg` built without it.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
internal class SkipFrameContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private fun oracleHas(encoder: String): Boolean =
        runMediaOracle("ffmpeg", listOf("-hide_banner", "-encoders"))
            ?.lineSequence()?.any { line -> line.split(' ').filter { it.isNotEmpty() }.getOrNull(1) == encoder } == true

    private fun clip(): String? {
        if (!oracleHas("libx264")) return null
        val output = contractOutputPath("mp4").also(paths::add)
        val written = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=24:duration=4",
                "-c:v", "libx264", "-g", "96", "-keyint_min", "96", "-sc_threshold", "0",
                "-bf", "2", "-x264-params", "b-adapt=0:b-pyramid=none", "-pix_fmt", "yuv420p",
            ),
            output,
        )
        return output.takeIf { written }
    }

    /** The presentation time of each B-frame of [path], in its stream's time base, as `ffprobe` reads the picture types. */
    private fun bidirectionalTimes(path: String): Set<Long> = checkNotNull(
        runMediaOracle(
            "ffprobe",
            listOf("-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts,pict_type", "-of", "csv=p=0", path),
        ),
    ).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
        val fields = line.split(',')
        fields[0].toLong().takeIf { fields[1] == "B" }
    }.toSet()

    /**
     * Decodes every packet [reader] still has with [threads] threads, calling [beforeSend] with each
     * packet's place in decode order before it is sent; returns each picture's time and pixels, and
     * appends each packet's time to [packetTimes].
     */
    private fun decode(
        source: MediaSource,
        reader: PacketReader,
        threads: Int,
        packetTimes: MutableList<Long> = mutableListOf(),
        beforeSend: (StreamDecoder, Int) -> Unit = { _, _ -> },
    ): List<Pair<Long, ByteArray>> = source.openDecoder(checkNotNull(source.primaryVideo), threadCount = threads).use { decoder ->
        val pictures = mutableListOf<Pair<Long, ByteArray>>()
        fun collect() {
            while (true) (decoder.receive() ?: return).use { pictures += it.info.pts to it.copyPlanesToByteArray() }
        }
        while (true) {
            val packet = reader.read() ?: break
            packet.use {
                beforeSend(decoder, packetTimes.size)
                packetTimes += it.pts
                while (!decoder.send(it)) collect()
            }
            collect()
        }
        decoder.send(null)
        collect()
        pictures
    }

    @Test
    fun theSecondHalfDecodesAsIfNothingHadBeenSkipped() {
        val path = clip() ?: return println("skip frame contract degraded: no ffmpeg with libx264")
        val bidirectional = bidirectionalTimes(path)
        MediaSource.open(path).use { source ->
            source.openPacketReader(listOf(checkNotNull(source.primaryVideo))).use { reader ->
                val whole = decode(source, reader, threads = 1)
                assertEquals(FRAMES, whole.size, "the frames of a decode that skips nothing")
                val wholeByTime = whole.toMap()
                for (threads in listOf(1, 4)) {
                    reader.seek(0)
                    val packetTimes = mutableListOf<Long>()
                    val skipping = decode(source, reader, threads, packetTimes) { decoder, index ->
                        if (index == 0) decoder.setSkipFrame(DecoderSkip.NonReference)
                        if (index == FRAMES / 2) decoder.setSkipFrame(DecoderSkip.None)
                    }
                    assertEquals(FRAMES, packetTimes.size, "the packets sent with $threads threads")
                    val firstHalf = packetTimes.take(FRAMES / 2).toSet()
                    val secondHalf = packetTimes.drop(FRAMES / 2).toSet()
                    assertTrue((firstHalf intersect bidirectional).size > 10, "the first half holds the B-frames this test is about")
                    val times = skipping.map { it.first }
                    assertEquals(times.size, times.toSet().size, "each picture once with $threads threads")
                    assertEquals(
                        firstHalf - bidirectional,
                        times.filter { it in firstHalf }.toSet(),
                        "the first half with $threads threads gives its reference frames and nothing else",
                    )
                    assertEquals(secondHalf, times.filter { it in secondHalf }.toSet(), "the second half with $threads threads gives every frame")
                    for ((time, planes) in skipping) {
                        assertContentEquals(wholeByTime.getValue(time), planes, "the picture at $time with $threads threads against a decode that skipped nothing")
                    }
                }
            }
        }
    }

    private companion object {
        const val FRAMES = 96
    }
}
