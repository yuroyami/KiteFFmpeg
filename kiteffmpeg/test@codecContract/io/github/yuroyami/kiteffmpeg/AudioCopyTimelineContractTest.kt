package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Copied audio keeps its samples where they belong when its source's clock is coarser than they
 * are (#154).
 *
 * Matroska stamps times in whole milliseconds, so the packets of AAC at 48 kHz, 1024 samples or
 * 21.333 ms each, are stamped 21 or 22 ms apart. A copy into MP4, which counts samples, used to
 * rescale each rounded time on its own: the packets sat 1008 or 1056 samples apart, the last lasted
 * 1008, and the sound ended 16 samples short. The source is AAC encoded by this library into
 * Matroska; each output's packets are read back in its own time base.
 */
internal class AudioCopyTimelineContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** [BLOCKS] frames of a 440 Hz tone as AAC in Matroska, with [gapSamples] of nothing after frame [GAP_AFTER]. */
    private fun source(gapSamples: Int = 0): String = path("mkv").also { file ->
        MediaSink.open(file).use { sink ->
            val sound = sink.addAudioEncoder(AudioEncoderSpec(codec = CodecId.Aac, sampleRate = RATE, channels = 1))
            runBlocking { sound.drive((0 until BLOCKS).asFlow().map { tone(it, if (it > GAP_AFTER) gapSamples else 0) }) }
        }
    }

    private fun tone(block: Int, delaySamples: Int): Frame {
        val bytes = ByteArray(AAC_FRAME * 4)
        for (i in 0 until AAC_FRAME) {
            val bits = (sin(2.0 * PI * 440.0 * (block * AAC_FRAME + i) / RATE) * 0.5).toFloat().toRawBits()
            for (b in 0 until 4) bytes[i * 4 + b] = (bits shr (8 * b)).toByte()
        }
        val ptsMicros = (block * AAC_FRAME + delaySamples).toLong() * 1_000_000L / RATE
        return Frame.ofAudio(bytes, AAC_FRAME, RATE, 1, SampleFormat.FltP, ptsMicros = ptsMicros)
    }

    private class Timing(val time: Long, val duration: Long)

    /** Each audio packet of [path]'s first audio stream, as its time and duration in [timeBase], read without decoding. */
    @OptIn(KiteFFmpegLowLevelApi::class)
    private fun packetTimes(path: String, timeBase: Rational): List<Timing> = MediaSource.open(path).use { source ->
        val audio = checkNotNull(source.primaryAudio) { "$path has no audio stream" }
        source.openPacketReader(listOf(audio)).use { reader ->
            buildList {
                while (true) {
                    val packet = reader.read() ?: break
                    packet.use {
                        assertEquals(timeBase, it.timeBase, "$path's audio counts in $timeBase")
                        add(Timing(it.pts, it.duration))
                    }
                }
            }
        }
    }

    /** An MP4 copy of [input] at [output] holds every sample, each packet starting where the one before it ended. */
    private fun assertCountedCopy(input: String, output: String) {
        assertEquals(TranscodeFixtures.decodedSampleCount(input), TranscodeFixtures.decodedSampleCount(output), "every sample of $input is heard")
        val packets = packetTimes(output, Rational(1, RATE))
        assertEquals(BLOCKS + 1, packets.size, "the priming packet and one packet per frame")
        packets.forEachIndexed { index, packet ->
            // The first packet is the encoder's priming, which the output hides to the sample.
            assertEquals((index - 1L) * AAC_FRAME, packet.time, "packet $index starts on its sample")
            assertEquals(AAC_FRAME.toLong(), packet.duration, "packet $index lasts its 1024 samples")
        }
    }

    @Test
    fun aRemuxOfAacFromMatroskaIntoMp4KeepsEverySampleInPlace() {
        val input = source()
        val output = path("mp4")
        runBlocking { Remuxer.remux(input, output) }
        assertCountedCopy(input, output)
    }

    @Test
    fun aTranscodeCopyingAacFromMatroskaIntoMp4KeepsEverySampleInPlace() {
        val input = source()
        val output = path("mp4")
        runBlocking { Transcoder.transcode(input = input, output = output, audioCopy = true) }
        assertCountedCopy(input, output)
    }

    /** Only a disagreement no rounding explains moves a packet off the count: here, half a second of nothing. */
    @Test
    fun aGapInTheSoundIsKept() {
        val gap = RATE / 2
        val input = source(gapSamples = gap)
        val stated = packetTimes(input, Rational(1, 1000)).zipWithNext { a, b -> b.time - a.time }
        assertTrue(stated.any { it >= 500 }, "the source carries the gap, $stated")
        val output = path("mp4")
        runBlocking { Remuxer.remux(input, output) }
        val steps = packetTimes(output, Rational(1, RATE)).zipWithNext { a, b -> b.time - a.time }
        val jump = steps.indexOfFirst { it != AAC_FRAME.toLong() }
        assertTrue(jump >= 0, "the gap is in the output, $steps")
        // Placed by its own rounded time, so to within a millisecond and a sample.
        assertTrue(abs(steps[jump] - (AAC_FRAME + gap)) <= RATE / 1000 + 1, "the gap lasts ${steps[jump] - AAC_FRAME} samples of $gap")
        assertTrue(steps.drop(jump + 1).all { it == AAC_FRAME.toLong() }, "the count carries on after the gap, $steps")
    }

    /** Matroska into Matroska rounds the counted times back to the milliseconds the source stated. */
    @Test
    fun aRemuxOfMatroskaIntoMatroskaKeepsItsTimes() {
        val input = source()
        val output = path("mkv")
        runBlocking { Remuxer.remux(input, output) }
        val before = packetTimes(input, Rational(1, 1000)).map { it.time }
        assertEquals(before, packetTimes(output, Rational(1, 1000)).map { it.time })
        assertEquals(TranscodeFixtures.decodedSampleCount(input), TranscodeFixtures.decodedSampleCount(output))
    }

    private companion object {
        /** The frame count of the report in #154, whose copy ended 16 samples short. */
        const val BLOCKS = 281
        const val GAP_AFTER = 100
        const val RATE = 48_000
        const val AAC_FRAME = 1024
    }
}
