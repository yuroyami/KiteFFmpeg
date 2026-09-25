package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An audio frame the encoder cannot read as it is gets converted, or refused, and is never read
 * anyway.
 *
 * This is not a tidiness test. Measured on 2026-08-30, before the guard existed: a frame whose
 * channel count or sample format disagreed with the encoder **crashed the process with SIGSEGV**,
 * because FFmpeg reads `nb_samples * channels * bytes_per_sample` using the ENCODER's idea of both
 * and runs off the end of a narrower or differently-typed buffer. A sample-rate mismatch did not
 * crash: it was accepted silently and encoded at the wrong rate. Such frames now go through a
 * Resampler, except a rate change into an encoder that takes fixed chunks, which is refused.
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
    fun aFrameWithAnotherChannelCountIsConvertedRatherThanReadPastItsEnd() = runBlocking {
        val path = contractOutputPath("mkv").also(paths::add)
        MediaSink.open(path).use { sink ->
            val encoder = sink.addAudioEncoder(AudioEncoderSpec(codec = CodecId("aac"), sampleRate = RATE, channels = CHANNELS))
            encoder.drive(flowOf(frame(RATE, 1, encoder.sampleFormat), frame(RATE, 1, encoder.sampleFormat)))
        }
        MediaSource.open(path).use { source ->
            assertEquals(CHANNELS, source.primaryAudio?.audio?.channels, "the mono frames must reach the file as stereo")
        }
        assertTrue(TranscodeFixtures.decodedSampleCount(path) > 0, "the converted frames were not encoded")
    }

    @Test
    fun aFrameInAnotherSampleFormatIsConvertedRatherThanReadPastItsEnd() = runBlocking {
        val path = contractOutputPath("mkv").also(paths::add)
        MediaSink.open(path).use { sink ->
            val encoder = sink.addAudioEncoder(AudioEncoderSpec(codec = CodecId("aac"), sampleRate = RATE, channels = CHANNELS))
            val other = if (encoder.sampleFormat == SampleFormat.S16) SampleFormat.FltP else SampleFormat.S16
            encoder.drive(flowOf(frame(RATE, CHANNELS, other), frame(RATE, CHANNELS, other)))
        }
        assertTrue(TranscodeFixtures.decodedSampleCount(path) > 0, "the converted frames were not encoded")
    }

    @Test
    fun aFrameAtAnotherRateIsRefusedByAnEncoderThatTakesFixedChunks() = runBlocking {
        // A resampled frame no longer holds the 1024 samples AAC takes, so this is still refused.
        // Before the guard it was accepted and encoded as if it were 44.1 kHz, so the output
        // played back at the wrong speed with nothing reporting it.
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

    @Test
    fun aFrameAtAnotherRateIsResampledForAnEncoderThatTakesAnyChunk() = runBlocking {
        val path = contractOutputPath("wav").also(paths::add)
        MediaSink.open(path).use { sink ->
            val encoder = sink.addAudioEncoder(
                AudioEncoderSpec(codec = CodecId.PcmS16, sampleRate = RATE, channels = CHANNELS, sampleFormat = SampleFormat.S16),
            )
            assertEquals(0, encoder.frameSize, "PCM takes any chunk size, which is what this case needs")
            encoder.drive(flowOf(*Array(10) { frame(48_000, CHANNELS, SampleFormat.FltP) }))
        }
        // 10 frames of 1024 samples at 48 kHz are 10240 * 44100 / 48000 = 9408 samples at 44.1 kHz.
        val decoded = TranscodeFixtures.decodedSampleCount(path)
        assertTrue(decoded in 9_406L..9_410L, "10240 samples at 48 kHz decoded to $decoded at 44.1 kHz")
    }
}
