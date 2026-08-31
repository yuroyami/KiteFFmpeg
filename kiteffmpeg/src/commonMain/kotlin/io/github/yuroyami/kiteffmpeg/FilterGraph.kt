package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.Flow

/** Per-input description for [FilterGraph.buildVideoMulti]. */
public data class VideoInput(
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val timeBase: Rational,
    val frameRate: Rational,
    val sampleAspectRatio: Rational = Rational(1, 1),
)

/** Per-input description for [FilterGraph.buildAudioMulti]. */
public data class AudioInput(
    val sampleRate: Int,
    val sampleFormat: SampleFormat,
    val channels: Int,
    val timeBase: Rational,
)

/**
 * A compiled `libavfilter` graph. Build with [FilterGraph.buildVideo] / [FilterGraph.buildAudio]
 * (single input) or [buildVideoMulti] / [buildAudioMulti] (N inputs: overlay, amix, …).
 * Feed frames through [process] (single input) or [feedInput] (any input). Close it when done.
 *
 * A graph is single-shot: once EOF has been flushed through it, it cannot accept more frames.
 * [process] closes the graph itself when its returned flow terminates; push-style users call
 * [close] (idempotent) when finished.
 *
 * Multi-input descriptions reference pads by label: inputs are `[in0]`…`[inN-1]`, the output is
 * `[out]`, e.g. `"[in0][in1]overlay=10:10[out]"` or `"[in0][in1]amix=inputs=2[out]"`.
 */
public expect class FilterGraph : AutoCloseable {

    /** Number of buffersrc inputs this graph was built with. */
    public val inputCount: Int

    /** Time-base of frames leaving the graph. Filters like `fps`/`atempo` may change it. */
    public val outputTimeBase: Rational

    /**
     * Fixed-size sample chunking for the buffersink (audio graphs only). Encoders such as AAC
     * accept exactly `frameSize` samples per call; setting this makes the graph emit that.
     */
    @Throws(FFmpegException::class)
    public fun setOutputFrameSize(samples: Int)

    /**
     * Push one frame into input [index]; every output frame that becomes available is handed
     * to [onOutput]. Closes [frame] (the graph keeps its own reference). Output frames are
     * valid only for the duration of the callback; [Frame.copy] to keep one.
     */
    @Throws(FFmpegException::class)
    public fun feedInput(index: Int, frame: Frame, onOutput: (Frame) -> Unit)

    /**
     * Signal EOF on input [index] and drain whatever the graph can produce. Filters like
     * `overlay` emit their final frames only once every input is flushed. Flush every input,
     * in any order. The last call delivers the remaining frames.
     */
    @Throws(FFmpegException::class)
    public fun flushInput(index: Int, onOutput: (Frame) -> Unit)

    /**
     * Drive [input] through the graph (single-input graphs only), emitting each processed frame
     * owned by the collector. Closes every input frame once consumed, and closes the graph when
     * the flow ends.
     *
     * @see Frame for the ownership rule every emitted frame is subject to
     */
    public fun process(input: Flow<Frame>): Flow<Frame>

    override fun close()

    public companion object {
        /**
         * @param description filter chain, e.g. `scale=1280:720,eq=brightness=0.1,format=yuv420p`
         * @param width  input frame width
         * @param height input frame height
         * @param pixelFormat input pixel format (e.g. [PixelFormat.Yuv420p])
         * @param timeBase pts time-base of input frames
         * @param frameRate frame rate of input, used by `setpts`/`fps`-style filters
         * @param sampleAspectRatio SAR of input frames; default 1:1 for square pixels
         */
        @Throws(FFmpegException::class)
        public fun buildVideo(
            description: String,
            width: Int,
            height: Int,
            pixelFormat: PixelFormat,
            timeBase: Rational,
            frameRate: Rational,
            sampleAspectRatio: Rational = Rational(1, 1),
        ): FilterGraph

        /**
         * Build a single-input audio graph. When the `output*` parameters are given, an
         * `aformat` stage is appended so emitted frames arrive encoder-ready (resampled /
         * reformatted / remixed inside the graph).
         *
         * The composed chain (the description plus any appended `aformat` stage) must fit in
         * 2048 bytes. A description that does not leave room is refused with
         * [FFmpegError.InvalidArgument]; it is never silently truncated.
         *
         * @param description filter chain, e.g. `volume=0.5,atempo=1.25`. Empty or `anull`
         *                    means passthrough.
         * @param sampleRate input sample rate
         * @param sampleFormat input sample format (decoder output, e.g. fltp)
         * @param channels input channel count
         * @param timeBase pts time-base of input frames
         */
        @Throws(FFmpegException::class)
        public fun buildAudio(
            description: String,
            sampleRate: Int,
            sampleFormat: SampleFormat,
            channels: Int,
            timeBase: Rational,
            outputSampleRate: Int = 0,
            outputSampleFormat: SampleFormat = SampleFormat.None,
            outputChannels: Int = 0,
        ): FilterGraph

        /**
         * N-input video graph for compositions. `"[in0][in1]overlay=W-w-10:H-h-10[out]"` puts
         * input 1 as a watermark in input 0's bottom-right corner.
         */
        @Throws(FFmpegException::class)
        public fun buildVideoMulti(description: String, inputs: List<VideoInput>): FilterGraph

        /**
         * N-input audio graph. `"[in0][in1]amix=inputs=2:duration=longest[out]"` mixes two
         * tracks.
         *
         * The `output*` parameters append an `aformat` stage exactly as [buildAudio] does, but
         * only when [description] does not carry an explicit `[out]` label. A description that
         * labels its own output controls its own formats, so nothing is appended.
         *
         * The same 2048 byte bound as [buildAudio] applies to the composed chain, with the same
         * refusal rather than truncation.
         */
        @Throws(FFmpegException::class)
        public fun buildAudioMulti(
            description: String,
            inputs: List<AudioInput>,
            outputSampleRate: Int = 0,
            outputSampleFormat: SampleFormat = SampleFormat.None,
            outputChannels: Int = 0,
        ): FilterGraph
    }
}
