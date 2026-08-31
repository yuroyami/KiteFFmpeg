package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An audio frame the encoder cannot read is refused, not read anyway.
 *
 * This is not a tidiness test. Measured on 2026-08-30, before the guard existed: a frame whose
 * channel count or sample format disagreed with the encoder **crashed the process with SIGSEGV**,
 * because FFmpeg reads `nb_samples * channels * bytes_per_sample` using the ENCODER's idea of both
 * and runs off the end of a narrower or differently-typed buffer. A sample-rate mismatch did not
 * crash: it was accepted silently and encoded at the wrong rate.
 *
 * So the falsification for this file is not a red assertion, it is a dead test runner. Remove the
 * guard and the two format cases below take the whole JVM down with them.
 */
class AudioEncoderMismatchContractTest {

    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private companion object {
        const val RATE = 44_100
        const val CHANNELS = 2
        /** What AAC wants per frame, so a matching frame is genuinely encodable. */
        const val SAMPLES = 1024
    }

    private fun openEncoder(): Pair<MediaSink, AudioEncoder> {
        val path = contractOutputPath("mkv").also(paths::add)
        val sink = MediaSink.open(path)
        val encoder = sink.addAudioEncoder(
            AudioEncoderSpec(codec = CodecId("aac"), sampleRate = RATE, channels = CHANNELS),
        )
        return sink to encoder
    }

    private fun frame(rate: Int, channels: Int, format: SampleFormat) = Frame.ofAudio(
        bytes = ByteArray(SAMPLES * channels * if (format == SampleFormat.S16) 2 else 4),
        sampleCount = SAMPLES,
        sampleRate = rate,
        channels = channels,
        sampleFormat = format,
        ptsMicros = 0L,
    )

    @Test
    fun aFrameMatchingTheEncoderIsStillAccepted() = runBlocking {
        val (sink, encoder) = openEncoder()
        try {
            // The guard must refuse mismatches without refusing the ordinary case, which is the
            // half a validation change is most likely to break.
            encoder.drive(flowOf(frame(RATE, CHANNELS, encoder.sampleFormat)))
        } finally {
            runCatching { sink.close() }
        }
    }

    @Test
    fun aFrameWithTheWrongChannelCountIsRefusedRatherThanReadPastItsEnd() = runBlocking {
        val (sink, encoder) = openEncoder()
        try {
            val refusal = assertFailsWith<FFmpegException> {
                encoder.drive(flowOf(frame(RATE, 1, encoder.sampleFormat)))
            }
            assertTrue(
                "channels" in (refusal.message ?: ""),
                "the refusal must name what was wrong, said: ${refusal.message}",
            )
        } finally {
            runCatching { sink.close() }
        }
    }

    @Test
    fun aFrameWithTheWrongSampleFormatIsRefusedRatherThanReadPastItsEnd() = runBlocking {
        val (sink, encoder) = openEncoder()
        try {
            val wrong = if (encoder.sampleFormat == SampleFormat.S16) SampleFormat.FltP else SampleFormat.S16
            val refusal = assertFailsWith<FFmpegException> {
                encoder.drive(flowOf(frame(RATE, CHANNELS, wrong)))
            }
            assertTrue(
                "sample format" in (refusal.message ?: ""),
                "the refusal must name what was wrong, said: ${refusal.message}",
            )
        } finally {
            runCatching { sink.close() }
        }
    }

    @Test
    fun aFrameAtTheWrongSampleRateIsRefusedRatherThanEncodedAtTheWrongSpeed() = runBlocking {
        // This one never crashed. It was accepted and encoded as if it were 44.1 kHz, so the
        // output played back at the wrong speed with nothing reporting it.
        val (sink, encoder) = openEncoder()
        try {
            val refusal = assertFailsWith<FFmpegException> {
                encoder.drive(flowOf(frame(48_000, CHANNELS, encoder.sampleFormat)))
            }
            assertTrue(
                "sample rate" in (refusal.message ?: ""),
                "the refusal must name what was wrong, said: ${refusal.message}",
            )
        } finally {
            runCatching { sink.close() }
        }
    }
}
