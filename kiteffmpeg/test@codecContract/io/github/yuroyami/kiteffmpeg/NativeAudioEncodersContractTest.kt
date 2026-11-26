package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FFmpeg's own Opus, ALAC, AC-3 and E-AC-3 encoders (#220). Each one writes a test tone that
 * decodes back to the same tone, and ALAC to the same samples. A frame that does not match the
 * encoder reaches it converted or is refused, and is never read as it is, because FFmpeg reads
 * an audio frame with the encoder's geometry and would run off its end.
 */
class NativeAudioEncodersContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** One new encoder and the container that it writes into. */
    private class Case(val codec: String, val extension: String, val options: Map<String, String> = emptyMap())

    // FFmpeg marks its Opus encoder experimental, so it opens only with `strict` set.
    private val cases = listOf(
        Case("opus", "ogg", mapOf("strict" to "experimental")),
        Case("alac", "m4a"),
        Case("ac3", "mp4"),
        Case("eac3", "mkv"),
    )

    private fun spec(case: Case, format: SampleFormat = SampleFormat.None) = AudioEncoderSpec(
        codec = CodecId(case.codec),
        sampleRate = RATE,
        channels = CHANNELS,
        sampleFormat = format,
        options = case.options,
    )

    /** Sample [index] of the tone, between -0.5 and 0.5. */
    private fun tone(index: Int): Double = 0.5 * sin(2 * PI * FREQUENCY * index / RATE)

    /** Frame [index] of the tone, [size] samples long, in [format], which is fltp, s16p or s32p. */
    private fun toneFrame(index: Int, size: Int, format: SampleFormat, channels: Int = CHANNELS): Frame {
        val width = if (format == SampleFormat.S16p) 2 else 4
        val bytes = ByteArray(size * channels * width)
        for (channel in 0 until channels) {
            for (sample in 0 until size) {
                val value = tone(index * size + sample)
                val bits = when (format) {
                    SampleFormat.S16p -> (value * 32767).roundToInt()
                    SampleFormat.S32p -> (value * 2147483647).roundToInt()
                    SampleFormat.FltP -> value.toFloat().toRawBits()
                    else -> error("no tone in ${format.name}")
                }
                val at = (channel * size + sample) * width
                for (byte in 0 until width) bytes[at + byte] = (bits shr (8 * byte)).toByte()
            }
        }
        return Frame.ofAudio(bytes, size, RATE, channels, format, ptsMicros = index * size * 1_000_000L / RATE)
    }

    /** Writes [frames] frames of the tone with [case]'s encoder, and answers the samples it took. */
    private fun writeTone(case: Case, path: String, frames: Int, format: SampleFormat = SampleFormat.None): Int {
        var samples = 0
        MediaSink.open(path).use { sink ->
            val encoder = sink.addAudioEncoder(spec(case, format))
            assertTrue(encoder.frameSize > 0, "${case.codec} takes fixed chunks")
            samples = frames * encoder.frameSize
            runBlocking {
                encoder.drive((0 until frames).asFlow().map { toneFrame(it, encoder.frameSize, encoder.sampleFormat) })
            }
        }
        return samples
    }

    /** The first channel of [path]'s decoded audio, between -1 and 1. */
    private fun decodedFirstChannel(path: String): DoubleArray = MediaSource.open(path).use { source ->
        val audio = checkNotNull(source.primaryAudio) { "$path has no audio stream" }
        val frames = runBlocking { source.decodedFrames(audio).toList() }
        try {
            frames.flatMap(::firstChannel).toDoubleArray()
        } finally {
            frames.forEach(Frame::close)
        }
    }

    private fun firstChannel(frame: Frame): List<Double> {
        val info = frame.info
        val bytes = frame.copyPlanesToByteArray()
        val kind = info.sampleFormat.name.removeSuffix("p")
        val width = if (kind == "s16") 2 else 4
        val step = if (info.sampleFormat.isPlanar) 1 else info.channelCount
        fun word(at: Int): Int = (0 until width).fold(0) { word, byte -> word or ((bytes[at + byte].toInt() and 0xFF) shl (8 * byte)) }
        return List(info.sampleCount) { sample ->
            val at = sample * step * width
            when (kind) {
                "s16" -> word(at).toShort() / 32768.0
                "s32" -> word(at) / 2147483648.0
                "flt" -> Float.fromBits(word(at)).toDouble()
                else -> error("unexpected sample format ${info.sampleFormat.name}")
            }
        }
    }

    /** [decoded] holds about [samples] samples of the tone: its loudness and its pitch. */
    private fun assertTheTone(codec: String, decoded: DoubleArray, samples: Int) {
        assertTrue(abs(decoded.size - samples) <= samples / 10, "$codec decoded ${decoded.size} samples of $samples")
        val middle = decoded.copyOfRange(decoded.size / 10, decoded.size * 9 / 10)
        val rms = sqrt(middle.sumOf { it * it } / middle.size)
        assertTrue(abs(rms - 0.5 / sqrt(2.0)) < 0.05, "$codec decoded the tone at an RMS of $rms")
        val crossings = (1 until middle.size).count { (middle[it - 1] < 0) != (middle[it] < 0) }
        val pitch = crossings / 2.0 * RATE / middle.size
        assertTrue(abs(pitch - FREQUENCY) < FREQUENCY * 0.02, "$codec decoded a tone of $pitch Hz")
    }

    @Test
    fun eachNewEncoderWritesAToneThatDecodesBackToTheTone() {
        cases.forEach { case ->
            val path = path(case.extension)
            val samples = writeTone(case, path, frames = 48)
            assertTheTone(case.codec, decodedFirstChannel(path), samples)
        }
    }

    @Test
    fun alacDecodesBackToTheSamplesItWrote() {
        val path = path("m4a")
        val samples = writeTone(cases.single { it.codec == "alac" }, path, frames = 12, format = SampleFormat.S16p)
        val decoded = decodedFirstChannel(path)
        assertEquals(samples, decoded.size, "ALAC keeps the exact length")
        for (index in 0 until samples) {
            assertEquals((tone(index) * 32767).roundToInt() / 32768.0, decoded[index], "sample $index")
        }
    }

    @Test
    fun aMonoFrameReachesEachStereoEncoderConverted() {
        cases.forEach { case ->
            val path = path(case.extension)
            MediaSink.open(path).use { sink ->
                val encoder = sink.addAudioEncoder(spec(case))
                runBlocking {
                    encoder.drive((0 until 8).asFlow().map { toneFrame(it, encoder.frameSize, encoder.sampleFormat, channels = 1) })
                }
            }
            MediaSource.open(path).use { source ->
                assertEquals(CHANNELS, source.primaryAudio?.audio?.channels, "${case.codec}: the mono frames reach the file as stereo")
            }
            assertTrue(decodedFirstChannel(path).isNotEmpty(), "${case.codec}: the converted frames were not encoded")
        }
    }

    @Test
    fun aFrameAtAnotherRateIsRefusedByEachNewEncoder() {
        cases.forEach { case ->
            val sink = MediaSink.open(path(case.extension))
            try {
                val encoder = sink.addAudioEncoder(spec(case))
                val width = if (encoder.sampleFormat == SampleFormat.S16p) 2 else 4
                val refused = Frame.ofAudio(
                    ByteArray(encoder.frameSize * CHANNELS * width),
                    encoder.frameSize,
                    44_100,
                    CHANNELS,
                    encoder.sampleFormat,
                    ptsMicros = 0L,
                )
                val refusal = assertFailsWith<FFmpegException>(case.codec) { runBlocking { encoder.drive(flowOf(refused)) } }
                assertTrue("sample rate" in refusal.message.orEmpty(), "${case.codec} said: ${refusal.message}")
            } finally {
                runCatching { sink.close() }
            }
        }
    }

    private companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
        const val FREQUENCY = 440.0
    }
}
