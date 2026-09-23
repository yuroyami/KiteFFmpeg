package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/**
 * Small inputs the transcode suites write through [MediaSink], and the readings they take back.
 *
 * Every video frame is one flat grey, and frame `i` has its own grey level, so a decoded frame
 * says which input frame it came from even after two rounds of lossy coding.
 */
internal object TranscodeFixtures {
    const val WIDTH: Int = 64
    const val HEIGHT: Int = 64

    /** Grey levels four apart: coding error is one or two levels, so the nearest level is the frame. */
    fun lumaOf(frame: Int): Int = 8 + 4 * (frame % 60)

    fun videoSpec(frameRate: Rational): VideoEncoderSpec = VideoEncoderSpec(
        codec = CodecId("mpeg4"),
        width = WIDTH,
        height = HEIGHT,
        frameRate = frameRate,
        bitrateBps = 2_000_000L,
    )

    /** Writes one flat mpeg4 frame per entry of [timestampsMicros] into [path]. */
    fun writeVideo(path: String, encoderRate: Rational, timestampsMicros: List<Long>) {
        MediaSink.open(path).use { sink ->
            val encoder = sink.addVideoEncoder(videoSpec(encoderRate))
            runBlocking {
                encoder.drive(
                    timestampsMicros.indices.asFlow().map { index ->
                        Frame.ofVideo(
                            bytes = flatPicture(lumaOf(index)),
                            width = WIDTH,
                            height = HEIGHT,
                            pixelFormat = PixelFormat.Yuv420p,
                            ptsMicros = timestampsMicros[index],
                        )
                    },
                )
            }
        }
    }

    /** Writes [frames] frames at a constant [rate], frame `i` at `i / rate` seconds. */
    fun writeConstantRateVideo(path: String, rate: Rational, frames: Int) {
        writeVideo(path, rate, List(frames) { i -> i * 1_000_000L * rate.den / rate.num })
    }

    /**
     * Writes [sampleCount] samples of s16 PCM into a WAV, in blocks of [blockSamples]. Sample `i`
     * of channel `c` is [sample]`(i, c)`, so a decoded sample says where in the input it was.
     */
    fun writePcm(
        path: String,
        sampleCount: Int,
        sampleRate: Int,
        channels: Int,
        blockSamples: Int = 960,
        sample: (index: Int, channel: Int) -> Short,
    ) {
        MediaSink.open(path).use { sink ->
            val encoder = sink.addAudioEncoder(pcmSpec(sampleRate, channels))
            runBlocking {
                encoder.drive(
                    (0 until sampleCount step blockSamples).asFlow().map { first ->
                        val count = minOf(blockSamples, sampleCount - first)
                        val bytes = ByteArray(count * channels * 2)
                        for (i in 0 until count) {
                            for (c in 0 until channels) {
                                val value = sample(first + i, c).toInt()
                                val at = (i * channels + c) * 2
                                bytes[at] = value.toByte()
                                bytes[at + 1] = (value shr 8).toByte()
                            }
                        }
                        Frame.ofAudio(
                            bytes = bytes,
                            sampleCount = count,
                            sampleRate = sampleRate,
                            channels = channels,
                            sampleFormat = SampleFormat.S16,
                            ptsMicros = first * 1_000_000L / sampleRate,
                        )
                    },
                )
            }
        }
    }

    fun pcmSpec(sampleRate: Int, channels: Int): AudioEncoderSpec = AudioEncoderSpec(
        codec = CodecId.PcmS16,
        sampleRate = sampleRate,
        channels = channels,
        sampleFormat = SampleFormat.S16,
    )

    /** Which input frame each decoded frame of [path] shows, in presentation order. */
    fun decodedFrameIndices(path: String): List<Int> = decodeVideo(path) { frame ->
        val luma = frame.copyPlanesToByteArray()
            .copyOfRange(0, WIDTH * HEIGHT)
            .map { it.toInt() and 0xFF }
            .average()
        (0 until 60).minBy { abs(lumaOf(it) - luma) }
    }

    /** Presentation times of [path]'s decoded video frames, relative to the start of the content. */
    fun decodedFrameTimes(path: String): List<Long> = MediaSource.open(path).use { source ->
        val video = checkNotNull(source.primaryVideo) { "$path has no video stream" }
        val frames = runBlocking { source.decodedFrames(video).toList() }
        try {
            frames.map { rescaleQ(it.info.pts, it.info.timeBase, Rational(1, 1_000_000)) - source.startTimeMicros }
        } finally {
            frames.forEach(Frame::close)
        }
    }

    /** Samples per channel in [path]'s first audio stream, decoded by this library. */
    fun decodedSampleCount(path: String): Long = MediaSource.open(path).use { source ->
        val audio = checkNotNull(source.primaryAudio) { "$path has no audio stream" }
        val frames = runBlocking { source.decodedFrames(audio).toList() }
        try {
            frames.sumOf { it.info.sampleCount.toLong() }
        } finally {
            frames.forEach(Frame::close)
        }
    }

    /** Every s16 sample of [path]'s first audio stream, channels interleaved. */
    fun decodedS16Samples(path: String): ShortArray = MediaSource.open(path).use { source ->
        val audio = checkNotNull(source.primaryAudio) { "$path has no audio stream" }
        val frames = runBlocking { source.decodedFrames(audio).toList() }
        try {
            val bytes = frames.fold(ByteArray(0)) { all, frame ->
                check(frame.info.sampleFormat == SampleFormat.S16) { "expected s16, got ${frame.info.sampleFormat.name}" }
                all + frame.copyPlanesToByteArray()
            }
            ShortArray(bytes.size / 2) { i ->
                ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
            }
        } finally {
            frames.forEach(Frame::close)
        }
    }

    private fun <T> decodeVideo(path: String, read: (Frame) -> T): List<T> = MediaSource.open(path).use { source ->
        val video = checkNotNull(source.primaryVideo) { "$path has no video stream" }
        val frames = runBlocking { source.decodedFrames(video).toList() }
        try {
            frames.map(read)
        } finally {
            frames.forEach(Frame::close)
        }
    }

    private fun flatPicture(luma: Int): ByteArray {
        val lumaPlane = ByteArray(WIDTH * HEIGHT) { luma.toByte() }
        val chromaPlanes = ByteArray(WIDTH * HEIGHT / 2) { 128.toByte() }
        return lumaPlane + chromaPlanes
    }
}
