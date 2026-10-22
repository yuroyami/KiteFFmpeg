package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.DemuxOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
    fun aFrameAtAnotherRateIsRefusedByAnEncoderThatTakesFixedChunks() = runBlocking<Unit> {
        // A resampled frame no longer holds the 1024 samples AAC takes, so this is still refused.
        // Before the guard it was accepted and encoded as if it were 44.1 kHz, so the output
        // played back at the wrong speed with nothing reporting it.
        val (sink, encoder) = openEncoder()
        try {
            val refused = frame(48_000, CHANNELS, encoder.sampleFormat)
            val refusal = assertFailsWith<FFmpegException> {
                encoder.drive(flowOf(refused))
            }
            assertTrue(
                "sample rate" in (refusal.message ?: ""),
                "the refusal must name what was wrong, said: ${refusal.message}",
            )
            assertFailsWith<IllegalStateException>("the refused frame is still open") { refused.info }
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

    @Test
    fun aFrameNoConverterCanTakeIsClosedWhenItIsRefused() = runBlocking<Unit> {
        // Nine channels in no named order give FFmpeg nothing to downmix to stereo from, so it
        // refuses to build the converter. The refusal is right, but the frame used to stay open
        // with its buffers, because the converter was built before anything owned the frame (#127).
        val baseline = contractLiveHandleCount()
        val frame = nineChannelFrame()
        assertEquals(9, frame.info.channelCount)
        assertEquals(null, frame.info.channelLayoutMask, "the nine channels must be in no named order")
        val path = contractOutputPath("wav").also(paths::add)
        MediaSink.open(path).use { sink ->
            val encoder = sink.addAudioEncoder(
                AudioEncoderSpec(codec = CodecId.PcmS16, sampleRate = 48_000, channels = CHANNELS, sampleFormat = SampleFormat.S16),
            )
            assertFailsWith<FFmpegException> { encoder.drive(flowOf(frame)) }
        }
        assertFailsWith<IllegalStateException>("the refused frame is still open") { frame.info }
        assertEquals(baseline, contractLiveHandleCount(), "the refused frame left a native object open")
    }

    @Test
    fun aFrameIsClosedWhenTheEncoderFailsOnWhatTheOldConverterHeld() = runBlocking<Unit> {
        // A frame in a new shape retires the converter of the frame before it, and the samples that
        // converter still held go to the encoder first. An encoder that failed on them used to
        // leave the new frame open (#127).
        val second = frame(48_000, 1, SampleFormat.FltP)
        var taken = 0
        val failure = assertFailsWith<IllegalStateException> {
            audioForEncoder(
                flowOf(frame(48_000, CHANNELS, SampleFormat.FltP), second),
                SampleFormat.S16, RATE, CHANNELS, frameSize = 0, channelLayoutMask = null,
            ).collect { frame ->
                frame.close()
                taken += 1
                check(taken < 2) { "the encoder failed" }
            }
        }
        assertEquals("the encoder failed", failure.message)
        assertEquals(2, taken, "the held samples never reached the encoder")
        assertFailsWith<IllegalStateException>("the new frame is still open") { second.info }
    }

    @Test
    fun theSamplesAConverterHoldsComeBeforeAFrameThatNeedsNoConversion() = runBlocking {
        // A rate change holds samples back, and they used to wait in the converter until the input
        // ended, so they reached the encoder after every newer frame that needed no conversion.
        // The encoder repairs timestamps that go backwards, which hid the reversal (#128).
        val out = encoderInput(
            stereoS16(480, 48_000, 8192, 0L),
            stereoS16(441, RATE, 24576, 10_000L),
        )
        assertBlocksInOrder(out, listOf(8192 to 441, 24576 to 441))
    }

    @Test
    fun aConversionAfterADirectStretchIsDatedFromItsOwnFrame() = runBlocking {
        // A converter kept across a stretch of frames that needed none dated what it converted
        // next from the samples it had made before the stretch, so the third block came out
        // stamped before the second (#128).
        val out = encoderInput(
            stereoS16(480, 48_000, 8192, 0L),
            stereoS16(441, RATE, 24576, 10_000L),
            stereoS16(480, 48_000, -16384, 20_000L),
        )
        assertBlocksInOrder(out, listOf(8192 to 441, 24576 to 441, -16384 to 441))
        assertEquals(20_000L, out.first { it.firstSample < 0 }.ptsMicros, "the third block starts at its own time")
    }

    /** What one frame handed to the encoder held: its time, its length and its first sample. */
    private class Delivered(val ptsMicros: Long?, val samples: Int, val firstSample: Int)

    /** What [audioForEncoder] hands a PCM encoder at 44.1 kHz stereo for [frames], in order. */
    @OptIn(KiteFFmpegLowLevelApi::class)
    private suspend fun encoderInput(vararg frames: Frame): List<Delivered> {
        val out = mutableListOf<Delivered>()
        audioForEncoder(flowOf(*frames), SampleFormat.S16, RATE, CHANNELS, frameSize = 0, channelLayoutMask = 3L)
            .collect { frame ->
                frame.use {
                    val bytes = it.copyPlanesToByteArray()
                    val first = (bytes[0].toInt() and 0xFF) or (bytes[1].toInt() shl 8)
                    out += Delivered(it.ptsMicros, it.info.sampleCount, first)
                }
            }
        return out
    }

    /**
     * [out] is the [blocks] in order, each a run of frames whose first sample is near the block's
     * value and whose lengths add up to the block's count, with every timestamp after the last.
     */
    private fun assertBlocksInOrder(out: List<Delivered>, blocks: List<Pair<Int, Int>>) {
        val seen = out.map { delivered -> blocks.indexOfFirst { (value, _) -> kotlin.math.abs(delivered.firstSample - value) < 1024 } }
        val described = out.joinToString { "${it.ptsMicros}us ${it.samples} samples from ${it.firstSample}" }
        assertTrue(-1 !in seen, "a frame matches no block: $described")
        assertEquals(seen.sorted(), seen, "the blocks arrived out of order: $described")
        blocks.forEachIndexed { index, (_, samples) ->
            assertEquals(samples, out.filterIndexed { at, _ -> seen[at] == index }.sumOf { it.samples }, "block $index: $described")
        }
        val times = out.map { assertNotNull(it.ptsMicros, "a frame has no time: $described") }
        assertTrue(times.zipWithNext().all { (a, b) -> b > a }, "the times go backwards: $described")
    }

    /** [samples] of stereo S16 at [rate], every sample [value]. */
    private fun stereoS16(samples: Int, rate: Int, value: Int, ptsMicros: Long) = Frame.ofAudio(
        bytes = ByteArray(samples * CHANNELS * 2) { i -> (if (i % 2 == 0) value and 0xFF else value shr 8).toByte() },
        sampleCount = samples,
        sampleRate = rate,
        channels = CHANNELS,
        sampleFormat = SampleFormat.S16,
        ptsMicros = ptsMicros,
    )

    /** A decoded frame of nine channels in no named order, which [Frame.ofAudio] cannot build. */
    private suspend fun nineChannelFrame(): Frame {
        val options = DemuxOptions(format = "s16le", options = mapOf("sample_rate" to "48000", "ch_layout" to "9C"))
        return MediaSource.open(BytesSource(ByteArray(64 * 9 * 2)), options).use { source ->
            source.decodedFrames(source.primaryAudio!!).first()
        }
    }

    /** [bytes] as a seekable input. */
    private class BytesSource(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close(): Unit = Unit
    }
}
