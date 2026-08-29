package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.Flow

public actual class FilterGraph private constructor() : AutoCloseable {
    public actual val inputCount: Int
        get() = placeholderBackendUnavailable("Reading filter inputs")
    public actual val outputTimeBase: Rational
        get() = placeholderBackendUnavailable("Reading a filter time base")

    @Throws(FFmpegException::class)
    public actual fun setOutputFrameSize(samples: Int): Unit =
        placeholderBackendUnavailable("Setting filter output frame size")

    @Throws(FFmpegException::class)
    public actual fun feedInput(index: Int, frame: Frame, onOutput: (Frame) -> Unit): Unit =
        placeholderBackendUnavailable("Feeding a filter graph")

    @Throws(FFmpegException::class)
    public actual fun flushInput(index: Int, onOutput: (Frame) -> Unit): Unit =
        placeholderBackendUnavailable("Flushing a filter graph")

    public actual fun process(input: Flow<Frame>): Flow<Frame> =
        placeholderBackendUnavailable("Processing a filter graph")

    actual override fun close(): Unit = Unit

    public actual companion object {
        @Throws(FFmpegException::class)
        public actual fun buildVideo(
            description: String,
            width: Int,
            height: Int,
            pixelFormat: PixelFormat,
            timeBase: Rational,
            frameRate: Rational,
            sampleAspectRatio: Rational,
        ): FilterGraph = placeholderBackendUnavailable("Building a video filter graph")

        @Throws(FFmpegException::class)
        public actual fun buildAudio(
            description: String,
            sampleRate: Int,
            sampleFormat: SampleFormat,
            channels: Int,
            timeBase: Rational,
            outputSampleRate: Int,
            outputSampleFormat: SampleFormat,
            outputChannels: Int,
        ): FilterGraph = placeholderBackendUnavailable("Building an audio filter graph")

        @Throws(FFmpegException::class)
        public actual fun buildVideoMulti(description: String, inputs: List<VideoInput>): FilterGraph =
            placeholderBackendUnavailable("Building a multi-input video filter graph")

        @Throws(FFmpegException::class)
        public actual fun buildAudioMulti(
            description: String,
            inputs: List<AudioInput>,
            outputSampleRate: Int,
            outputSampleFormat: SampleFormat,
            outputChannels: Int,
        ): FilterGraph = placeholderBackendUnavailable("Building a multi-input audio filter graph")
    }
}
