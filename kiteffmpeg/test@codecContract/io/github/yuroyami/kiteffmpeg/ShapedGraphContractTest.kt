package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stream that changes shape partway through: the transcode rebuilds its filter graph for the
 * new shape on every backend, instead of filtering by the old one or refusing the frame.
 *
 * Each input is two files joined byte for byte, which is how a broadcast recording changes shape:
 * the second part carries its own header in the stream, and the decoder follows it.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
internal class ShapedGraphContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** [first] then [second], joined into one [extension] file, or null without ffmpeg. */
    private fun joined(first: List<String>, second: List<String>, extension: String, extra: List<String> = emptyList()): String? {
        val parts = listOf(first, second).map { arguments ->
            path(extension).takeIf { MediaOracle.generate(arguments + listOf("-f", extension), it) } ?: return null
        }
        val output = path(if (extension == "m4v") "ts" else extension)
        val written = MediaOracle.generate(listOf("-i", "concat:${parts[0]}|${parts[1]}", "-c", "copy") + extra, output)
        return output.takeIf { written }
    }

    /**
     * One second of 160x120 video with square pixels, then one second with pixels twice as wide,
     * in MPEG-TS. The stream declares the shape of the second part, so the first frames already
     * differ from what the stream declares.
     */
    private fun pixelShapeChange(): String? {
        fun part(sar: Int) = listOf(
            "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=1",
            "-vf", "setsar=$sar", "-c:v", "mpeg4", "-q:v", "2",
        )
        // Each part restarts its clock, so the join numbers the frames again, 25 to the second.
        return joined(part(1), part(2), "m4v", listOf("-bsf:v", "setts=ts=N/(25*TB)"))
    }

    /** Mean luma of columns 200 to 319 of a 320x120 yuv420p [frame]. */
    private fun rightSideLuma(frame: Frame): Double {
        val luma = frame.copyPlanesToByteArray()
        var sum = 0L
        for (row in 0 until 120) for (col in 200 until 320) sum += luma[row * 320 + col].toInt() and 0xFF
        return sum / (120.0 * 120)
    }

    /**
     * The filter stretches each frame to square pixels and pads it to 320 wide. Square frames stay
     * 160 wide, so their right side is black; the wide frames become 320 wide and fill it. A graph
     * that keeps the shape it was built for scales every frame alike, and one half comes out wrong.
     * The shipping FFmpeg build has no `setsar`; `scale` already writes square pixels here.
     */
    @Test
    fun aPixelShapeChangeMidStreamRebuildsTheVideoGraph() {
        val input = pixelShapeChange() ?: return println("shaped graph contract degraded: no ffmpeg")
        val output = path("mkv")
        runBlocking {
            Transcoder.transcode(
                input = input,
                output = output,
                spec = VideoEncoderSpec(CodecId.Mpeg4, width = 320, height = 120, frameRate = Rational(25, 1)),
                videoFilter = "scale=w=iw*sar:h=ih,pad=w=320:h=120",
            )
        }
        val sides = MediaSource.open(output).use { source ->
            val video = source.streams.single()
            runBlocking {
                buildList {
                    source.decodedFrames(video).collect { frame ->
                        frame.use { add(assertNotNullPts(it.ptsMicros) to rightSideLuma(it)) }
                    }
                }
            }
        }
        val square = sides.filter { it.first < 900_000L }
        val wide = sides.filter { it.first >= 1_100_000L }
        assertTrue(square.size >= 20 && wide.size >= 20, "frames on each side of the change: ${square.size} and ${wide.size}")
        square.forEach { (pts, luma) -> assertTrue(luma < 24.0, "a square frame at $pts us is not padded: luma $luma") }
        wide.forEach { (pts, luma) -> assertTrue(luma > 40.0, "a wide frame at $pts us was scaled as square: luma $luma") }
    }

    private fun assertNotNullPts(pts: Long?): Long = pts ?: error("an output frame has no timestamp")

    /** One second of stereo AC-3, then one second of 5.1, as a broadcast channel switches for a film. */
    private fun stereoThenSurround(): String? {
        val tone = listOf("-f", "lavfi", "-i", "sine=frequency=440:duration=1:sample_rate=48000")
        return joined(
            tone + listOf("-ac", "2", "-c:a", "ac3"),
            tone + listOf("-af", "pan=5.1(side)|FL=c0|FR=c0|FC=c0|LFE=c0|SL=c0|SR=c0", "-c:a", "ac3"),
            "ac3",
        )
    }

    /** Decoded samples per second of the first channel of [path]: count and root mean square. */
    private fun loudnessBySecond(path: String): Map<Long, Pair<Int, Double>> = MediaSource.open(path).use { source ->
        val audio = source.streams.single()
        val counts = mutableMapOf<Long, Int>()
        val squares = mutableMapOf<Long, Double>()
        runBlocking {
            source.decodedFrames(audio).collect { frame ->
                frame.use {
                    val second = assertNotNullPts(it.ptsMicros) / 1_000_000L
                    val samples = it.info.sampleCount
                    // AAC decodes to planar floats, so the first samples * 4 bytes are channel one.
                    val bytes = it.copyPlanesToByteArray()
                    var sum = 0.0
                    for (i in 0 until samples) {
                        val bits = (bytes[4 * i].toInt() and 0xFF) or ((bytes[4 * i + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[4 * i + 2].toInt() and 0xFF) shl 16) or ((bytes[4 * i + 3].toInt() and 0xFF) shl 24)
                        val value = Float.fromBits(bits)
                        sum += value * value
                    }
                    counts[second] = (counts[second] ?: 0) + samples
                    squares[second] = (squares[second] ?: 0.0) + sum
                }
            }
        }
        counts.mapValues { (second, count) -> count to sqrt(squares.getValue(second) / count) }
    }

    /**
     * The stream declares 5.1 for the whole file, the first second decodes to stereo and the
     * second to 5.1. Both halves come out, and both carry the tone.
     */
    @Test
    fun anAudioTrackThatTurnsFromStereoToSurroundTranscodesWhole() {
        val input = stereoThenSurround() ?: return println("shaped graph contract degraded: no ffmpeg")
        val output = path("mkv")
        runBlocking {
            Transcoder.transcode(
                input = input,
                output = output,
                audioSpec = AudioEncoderSpec(CodecId.Aac, sampleRate = 48_000, channels = 2),
            )
        }
        val seconds = loudnessBySecond(output)
        val total = seconds.values.sumOf { it.first }
        assertTrue(total in 94_000..102_000, "the output holds $total samples, not about two seconds")
        for (second in 0L..1L) {
            val (count, rms) = seconds[second] ?: error("no audio in second $second: $seconds")
            assertTrue(count > 40_000 && rms > 0.02, "second $second: $count samples at level $rms")
        }
    }

    /** Six channels of the tone in a WAV whose header names [layout], an FFmpeg layout name. */
    private fun sixChannels(layout: String, surrounds: String): String? {
        val output = path("wav")
        val written = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1:sample_rate=48000",
                "-af", "pan=$layout|FL=c0|FR=c0|FC=c0|LFE=c0|$surrounds", "-c:a", "pcm_s16le",
            ),
            output,
        )
        return output.takeIf { written }
    }

    /**
     * Six channels with back surrounds, then six with side surrounds: the count stays, the layout
     * changes, and a graph built for one layout refuses a frame of the other.
     */
    @Test
    fun aLayoutChangeWithTheSameChannelCountRebuildsTheAudioGraph() {
        val back = sixChannels("5.1", "BL=c0|BR=c0") ?: return println("shaped graph contract degraded: no ffmpeg")
        val side = sixChannels("5.1(side)", "SL=c0|SR=c0") ?: return println("shaped graph contract degraded: no ffmpeg")
        val layouts = listOf(back, side).map { file ->
            MediaSource.open(file).use { it.streams.single().audio?.channelLayoutMask }
        }
        assertEquals(listOf(0x3FL, 0x60FL), layouts, "the fixtures name the two layouts")

        var produced = 0
        val declared = MediaSource.open(back).use { AudioShape.declaredBy(it.streams.single()) }
        ShapedGraph(declared, { AudioShape.of(it) }) { shape ->
            FilterGraph.buildAudio(
                description = "anull",
                sampleRate = shape.sampleRate,
                sampleFormat = shape.sampleFormat,
                channels = shape.channels,
                timeBase = Rational(1, 48_000),
                outputSampleRate = 48_000,
                outputSampleFormat = SampleFormat.S16,
                outputChannels = 2,
                channelLayoutMask = shape.channelLayoutMask,
            )
        }.use { graph ->
            val count: (Frame) -> Unit = { produced += it.info.sampleCount }
            for (file in listOf(back, side)) {
                MediaSource.open(file).use { source ->
                    runBlocking { source.decodedFrames(source.streams.single()).collect { graph.feed(it, count) } }
                }
            }
            graph.flush(count)
        }
        assertEquals(96_000, produced, "both seconds come out of the graph")
    }
}
