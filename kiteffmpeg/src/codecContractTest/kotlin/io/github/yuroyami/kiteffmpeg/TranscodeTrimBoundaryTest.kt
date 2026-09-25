package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Where a trim cuts, and what it keeps at each end.
 *
 * Re-encoded audio is cut to the sample, the way FFmpeg's `atrim` cuts it: the samples from
 * `startMicros` up to, and not including, `endMicros`. The trim used to keep or drop whole decoded
 * blocks by the time of their first sample, so a window shorter than one block kept nothing at
 * all. Re-encoded video keeps the frames from `startMicros` up to, and not including,
 * `endMicros`, so a clip and its audio cover the same time.
 *
 * `ffmpeg` with `atrim` or `trim` over the same input is the oracle for every count.
 */
internal class TranscodeTrimBoundaryTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private companion object {
        const val RATE = 48_000

        /** Sample `i` of channel 0 is `i` folded under 30 000, and channel 1 is its negative. */
        fun ramp(index: Int, channel: Int): Short = (if (channel == 0) index % 30_000 else -(index % 30_000)).toShort()
    }

    /** One second of s16 PCM with [channels] channels, written in blocks of 960 samples. */
    private fun oneSecondOfPcm(channels: Int): String = path("wav").also {
        TranscodeFixtures.writePcm(it, sampleCount = RATE, sampleRate = RATE, channels = channels, sample = ::ramp)
    }

    private fun trimAudio(input: String, output: String, spec: AudioEncoderSpec, startMicros: Long, endMicros: Long) {
        runBlocking {
            Transcoder.transcode(
                input = input,
                output = output,
                audioSpec = spec,
                startMicros = startMicros,
                endMicros = endMicros,
            )
        }
    }

    /** Asserts the samples per channel in [output], and that `ffmpeg`'s `atrim` kept as many. */
    private fun assertSampleCount(expected: Long, output: String, input: String, atrim: String, codec: String) {
        assertEquals(expected, TranscodeFixtures.decodedSampleCount(output), "samples decoded from the output")
        MediaOracle.audioSampleCount(output)?.let { assertEquals(expected, it, "samples ffprobe decodes from the output") }
        val reference = path(output.substringAfterLast('.'))
        if (MediaOracle.reference(input, listOf("-af", atrim, "-c:a", codec), reference)) {
            assertEquals(expected, MediaOracle.audioSampleCount(reference), "samples ffmpeg kept with $atrim")
        }
    }

    /**
     * The issue's reproduction. 10 ms to 30 ms of 48 kHz audio is 960 samples, all inside the
     * first decoded block. Red with whole-block trimming: 0 samples and no error.
     */
    @Test
    fun aWindowInsideOneDecodedBlockKeepsExactlyItsSamples() {
        val input = oneSecondOfPcm(channels = 1)
        val output = path("wav")
        trimAudio(input, output, TranscodeFixtures.pcmSpec(RATE, 1), startMicros = 10_000L, endMicros = 30_000L)
        assertSampleCount(960L, output, input, "atrim=start=0.01:end=0.03", "pcm_s16le")
        val expected = ShortArray(960) { ramp(480 + it, 0) }
        assertContentEquals(expected, TranscodeFixtures.decodedS16Samples(output), "the kept samples are input samples 480 to 1439")
    }

    /**
     * 30 ms to 70 ms of interleaved stereo: the start falls inside one decoded block and the end
     * inside a later one. Both blocks are cut, and each channel keeps its own samples.
     */
    @Test
    fun aWindowCrossingDecodedBlocksKeepsExactlyItsSamples() {
        val input = oneSecondOfPcm(channels = 2)
        val output = path("wav")
        trimAudio(input, output, TranscodeFixtures.pcmSpec(RATE, 2), startMicros = 30_000L, endMicros = 70_000L)
        assertSampleCount(1_920L, output, input, "atrim=start=0.03:end=0.07", "pcm_s16le")
        val expected = ShortArray(2 * 1_920) { ramp(1_440 + it / 2, it % 2) }
        assertContentEquals(expected, TranscodeFixtures.decodedS16Samples(output), "each channel keeps input samples 1440 to 3359")
    }

    /** AAC decodes to planar floats, one plane per channel: 30 ms to 70 ms is 1920 samples of each. */
    @Test
    fun planarAudioKeepsExactlyItsSamples() {
        val input = path("m4a")
        writeAacTone(input)
        val output = path("wav")
        trimAudio(input, output, TranscodeFixtures.pcmSpec(RATE, 2), startMicros = 30_000L, endMicros = 70_000L)
        assertSampleCount(1_920L, output, input, "atrim=start=0.03:end=0.07", "pcm_s16le")
    }

    /**
     * An AAC encoder puts 1024 priming samples in front of the content. `ffmpeg` marks them in
     * the file it writes, and the decoder drops them, so the content starts at zero. The trim
     * counts on that timeline, as `atrim` does: 30 ms to 70 ms is 1920 samples. The source comes
     * from `ffmpeg` itself, so this case needs the oracle and does nothing on a device.
     */
    @Test
    fun aSourceWithEncoderDelayIsTrimmedOnItsContentTimeline() {
        val input = path("m4a")
        val tone = listOf("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1", "-ac", "2", "-c:a", "aac")
        if (!MediaOracle.generate(tone, input)) return
        val output = path("wav")
        trimAudio(input, output, TranscodeFixtures.pcmSpec(RATE, 2), startMicros = 30_000L, endMicros = 70_000L)
        assertSampleCount(1_920L, output, input, "atrim=start=0.03:end=0.07", "pcm_s16le")
    }

    /**
     * AC-3 decodes six channels as 5.1 with side surrounds, which is not FFmpeg's default layout
     * for six channels. A cut inside a decoded block keeps the block's own layout: 30 ms to 70 ms
     * is 1920 samples, written as 5.1 with side surrounds again.
     */
    @Test
    fun aCutInsideASurroundBlockKeepsItsLayout() {
        val input = path("ac3")
        val tone = listOf(
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1",
            "-af", "pan=5.1(side)|FL=c0|FR=c0|FC=c0|LFE=c0|SL=c0|SR=c0", "-c:a", "ac3",
        )
        if (!MediaOracle.generate(tone, input)) return
        val output = path("wav")
        trimAudio(input, output, TranscodeFixtures.pcmSpec(RATE, 6), startMicros = 30_000L, endMicros = 70_000L)
        assertSampleCount(1_920L, output, input, "atrim=start=0.03:end=0.07", "pcm_s16le")
        val layout = MediaSource.open(output).use { it.streams.single().audio?.channelLayoutMask }
        assertEquals(0x60FL, layout, "the output keeps 5.1 with side surrounds")
    }

    /** 0 to 1 s of 25 fps video is 25 frames; the frame that starts at 1 s is not in it. */
    @Test
    fun aVideoFrameThatStartsAtTheTrimEndIsNotKept() {
        val input = path("mkv")
        TranscodeFixtures.writeConstantRateVideo(input, Rational(25, 1), frames = 50)
        val output = path("mkv")
        runBlocking {
            Transcoder.transcode(
                input = input,
                output = output,
                spec = TranscodeFixtures.videoSpec(Rational(25, 1)),
                startMicros = 400_000L,
                endMicros = 1_000_000L,
            )
        }
        val frames = TranscodeFixtures.decodedFrameIndices(output)
        assertEquals((10 until 25).toList(), frames, "the frames from 400 ms up to 1 s")
        MediaOracle.videoFrameCount(output)?.let { assertEquals(15, it, "frames ffprobe counts in the output") }
        val reference = path("mkv")
        if (MediaOracle.reference(input, listOf("-vf", "trim=start=0.4:end=1", "-c:v", "mpeg4"), reference)) {
            assertEquals(15, MediaOracle.videoFrameCount(reference), "frames ffmpeg kept with trim")
        }
    }

    /** One trim over a file with both: the video and the audio cover the same second. */
    @Test
    fun videoAndAudioCoverTheSameSelection() {
        val input = path("mkv")
        writeVideoWithAudio(input)
        val output = path("mkv")
        runBlocking {
            Transcoder.transcode(
                input = input,
                output = output,
                spec = TranscodeFixtures.videoSpec(Rational(25, 1)),
                audioSpec = TranscodeFixtures.pcmSpec(RATE, 1),
                startMicros = 500_000L,
                endMicros = 1_500_000L,
            )
        }
        assertEquals(25, TranscodeFixtures.decodedFrameIndices(output).size, "frames from 0.5 s up to 1.5 s")
        assertEquals(RATE.toLong(), TranscodeFixtures.decodedSampleCount(output), "samples from 0.5 s up to 1.5 s")
    }

    /** One second of a stereo tone through this library's AAC encoder, in 1024-sample blocks. */
    private fun writeAacTone(path: String) {
        MediaSink.open(path).use { sink ->
            val encoder = sink.addAudioEncoder(AudioEncoderSpec(codec = CodecId.Aac, sampleRate = RATE, channels = 2))
            val block = encoder.frameSize
            runBlocking {
                encoder.drive(
                    (0 until RATE / block).asFlow().map { index ->
                        val bytes = ByteArray(block * 2 * 4)
                        for (channel in 0 until 2) {
                            for (i in 0 until block) {
                                val value = 0.25f * sin(2.0 * PI * 440.0 * (index * block + i) / RATE).toFloat()
                                val bits = value.toRawBits()
                                val at = (channel * block + i) * 4
                                for (byte in 0 until 4) bytes[at + byte] = (bits shr (8 * byte)).toByte()
                            }
                        }
                        Frame.ofAudio(bytes, block, RATE, 2, encoder.sampleFormat, index * block * 1_000_000L / RATE)
                    },
                )
            }
        }
    }

    /** Two seconds of 25 fps video and 48 kHz mono audio, interleaved in one file. */
    private fun writeVideoWithAudio(path: String) {
        MediaSink.open(path).use { sink ->
            val video = sink.addVideoEncoder(TranscodeFixtures.videoSpec(Rational(25, 1)))
            val audio = sink.addAudioEncoder(TranscodeFixtures.pcmSpec(RATE, 1))
            runBlocking {
                video.drive(
                    (0 until 50).asFlow().map { index ->
                        Frame.ofVideo(
                            bytes = ByteArray(TranscodeFixtures.WIDTH * TranscodeFixtures.HEIGHT * 3 / 2) { 100 },
                            width = TranscodeFixtures.WIDTH,
                            height = TranscodeFixtures.HEIGHT,
                            pixelFormat = PixelFormat.Yuv420p,
                            ptsMicros = index * 40_000L,
                        )
                    },
                )
                audio.drive(
                    (0 until 100).asFlow().map { index ->
                        Frame.ofAudio(ByteArray(960 * 2), 960, RATE, 1, SampleFormat.S16, index * 20_000L)
                    },
                )
            }
        }
    }
}
