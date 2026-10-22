package io.github.yuroyami.kiteffmpeg

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [Resampler] against the counts and the spectrum a rate change must keep. */
@OptIn(KiteFFmpegLowLevelApi::class)
class ResamplerContractTest {

    private companion object {
        const val IN_RATE = 48_000
        const val OUT_RATE = 44_100
        const val BLOCK = 1024
        const val BLOCKS = 10
        val INPUT = AudioSpec(IN_RATE, 2, SampleFormat.FltP)
        val OUTPUT = AudioSpec(OUT_RATE, 2, SampleFormat.S16)
    }

    /** [BLOCK] samples of a 1 kHz stereo sine as planar float, starting at sample [first]. */
    private fun sineBlock(first: Int, ptsMicros: Long): Frame {
        val bytes = ByteArray(BLOCK * 2 * 4)
        for (channel in 0 until 2) {
            for (i in 0 until BLOCK) {
                val value = (0.5 * sin(2.0 * PI * 1000.0 * (first + i) / IN_RATE)).toFloat().toRawBits()
                val at = (channel * BLOCK + i) * 4
                bytes[at] = value.toByte()
                bytes[at + 1] = (value shr 8).toByte()
                bytes[at + 2] = (value shr 16).toByte()
                bytes[at + 3] = (value shr 24).toByte()
            }
        }
        return Frame.ofAudio(bytes, BLOCK, IN_RATE, 2, SampleFormat.FltP, ptsMicros)
    }

    /** The left channel of an interleaved s16 frame, as doubles. */
    private fun leftChannel(frame: Frame): DoubleArray {
        val bytes = frame.copyPlanesToByteArray()
        val samples = frame.info.sampleCount
        return DoubleArray(samples) { i ->
            val at = i * 4
            ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort().toDouble()
        }
    }

    /** Goertzel power of [signal] at [frequency]. */
    private fun power(signal: DoubleArray, frequency: Double, rate: Int): Double {
        val coefficient = 2.0 * cos(2.0 * PI * frequency / rate)
        var previous = 0.0
        var beforePrevious = 0.0
        for (sample in signal) {
            val current = sample + coefficient * previous - beforePrevious
            beforePrevious = previous
            previous = current
        }
        return previous * previous + beforePrevious * beforePrevious - coefficient * previous * beforePrevious
    }

    @Test
    fun aRateChangeKeepsTheSampleCountAndTheTone() {
        val left = ArrayList<Double>()
        var total = 0L
        var firstPts: Long? = null
        Resampler(INPUT, OUTPUT).use { resampler ->
            fun take(frame: Frame) = frame.use {
                assertEquals(OUT_RATE, it.info.sampleRate)
                assertEquals(2, it.info.channelCount)
                assertEquals(SampleFormat.S16, it.info.sampleFormat)
                if (firstPts == null) firstPts = it.ptsMicros
                total += it.info.sampleCount
                left.addAll(leftChannel(it).asList())
            }
            for (block in 0 until BLOCKS) {
                sineBlock(block * BLOCK, 1_000L + block * BLOCK * 1_000_000L / IN_RATE).use { input ->
                    resampler.convert(input)?.let(::take)
                }
            }
            var tail = 0
            while (true) {
                val held = resampler.flush() ?: break
                tail += held.info.sampleCount
                take(held)
            }
            assertTrue(tail > 0, "a rate change holds samples back, and flush must return them")
        }
        assertTrue(total in 9_406L..9_410L, "10240 samples at 48 kHz came to $total at 44.1 kHz, expected 9408")
        assertEquals(1_000L, firstPts, "the output timeline starts at the first input timestamp")
        val signal = left.toDoubleArray()
        val atTone = power(signal, 1_000.0, OUT_RATE)
        val offTone = power(signal, 1_500.0, OUT_RATE)
        assertTrue(atTone > offTone * 1_000, "the 1 kHz tone did not survive: $atTone at 1 kHz, $offTone at 1.5 kHz")
    }

    @Test
    fun aSpecFFmpegCannotTakeIsRefusedTyped() {
        val failure = assertFailsWith<FFmpegException> { Resampler(INPUT, AudioSpec(OUT_RATE, 0, SampleFormat.S16)) }
        assertIs<FFmpegError.InvalidArgument>(failure.error, "was ${failure.error}")
    }

    @Test
    fun aFrameThatDoesNotMatchTheInputIsRefusedTyped() {
        Resampler(INPUT, OUTPUT).use { resampler ->
            val mono = Frame.ofAudio(ByteArray(BLOCK * 4), BLOCK, IN_RATE, 1, SampleFormat.FltP, 0L)
            mono.use {
                val failure = assertFailsWith<FFmpegException> { resampler.convert(it) }
                assertIs<FFmpegError.InvalidArgument>(failure.error)
                assertTrue("channels" in (failure.message ?: ""), "the refusal must say why: ${failure.message}")
            }
        }
    }

    @Test
    fun aClosedResamplerRefusesAndClosesTwice() {
        val resampler = Resampler(INPUT, OUTPUT)
        resampler.close()
        resampler.close()
        assertFailsWith<IllegalStateException> { resampler.flush() }
        assertNotNull(sineBlock(0, 0L)).use { input ->
            assertFailsWith<IllegalStateException> { resampler.convert(input) }
        }
    }
}
