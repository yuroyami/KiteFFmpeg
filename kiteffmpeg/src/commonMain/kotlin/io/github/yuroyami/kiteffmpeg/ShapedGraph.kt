package io.github.yuroyami.kiteffmpeg

/**
 * The input shape a filter graph is built for. A frame of another shape needs another graph: an
 * audio graph refuses it, and a video graph filters it by the old shape.
 */
internal sealed interface GraphShape {
    /** Whether this shape is enough to build a graph before any frame arrives. */
    val isComplete: Boolean

    /** True when [info] has this shape. It runs for every frame, so it allocates nothing. */
    fun fits(info: FrameInfo): Boolean
}

/** A video graph's input: the size, the pixel format and the pixel shape. */
internal data class VideoShape(
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val sampleAspectRatio: Rational,
) : GraphShape {
    override val isComplete: Boolean
        get() = width > 0 && height > 0 && pixelFormat != PixelFormat.None

    override fun fits(info: FrameInfo): Boolean =
        info.width == width && info.height == height && info.pixelFormat == pixelFormat &&
            info.sampleAspectRatio == sampleAspectRatio

    companion object {
        fun of(info: FrameInfo): VideoShape = VideoShape(info.width, info.height, info.pixelFormat, info.sampleAspectRatio)

        /** What [stream] declares, which is incomplete when it has no video information. */
        fun declaredBy(stream: StreamInfo): VideoShape = stream.video
            ?.let { VideoShape(it.width, it.height, it.pixelFormat, it.sampleAspectRatio) }
            ?: VideoShape(0, 0, PixelFormat.None, Rational(1, 1))
    }
}

/** An audio graph's input: the rate, the sample format, the channel count and the channel layout. */
internal data class AudioShape(
    val sampleRate: Int,
    val sampleFormat: SampleFormat,
    val channels: Int,
    val channelLayoutMask: Long?,
) : GraphShape {
    override val isComplete: Boolean
        get() = sampleRate > 0 && channels > 0 && sampleFormat != SampleFormat.None

    override fun fits(info: FrameInfo): Boolean =
        info.sampleRate == sampleRate && info.sampleFormat == sampleFormat && info.channelCount == channels &&
            info.channelLayoutMask == channelLayoutMask

    companion object {
        fun of(info: FrameInfo): AudioShape = AudioShape(info.sampleRate, info.sampleFormat, info.channelCount, info.channelLayoutMask)

        /** What [stream] declares, which is incomplete when it has no audio information. */
        fun declaredBy(stream: StreamInfo): AudioShape = stream.audio
            ?.let { AudioShape(it.sampleRate, it.sampleFormat, it.channels, it.channelLayoutMask) }
            ?: AudioShape(0, SampleFormat.None, 0, null)
    }
}

/**
 * The filter graph of one decoded stream, under one build law on every backend.
 *
 * The graph is built from the [declared] shape when that shape is complete, so a description that
 * cannot build fails before the first frame. It is rebuilt from a frame whenever the frame's shape
 * differs from the one the graph was built for. The frames the old graph still holds belong to the
 * old shape, so a rebuild first flushes them to the same callback.
 */
internal class ShapedGraph<S : GraphShape>(
    declared: S,
    private val shapeOf: (FrameInfo) -> S,
    private val build: (S) -> FilterGraph,
) : AutoCloseable {
    private var shape: S = declared
    private var graph: FilterGraph? = if (declared.isComplete) build(declared) else null

    /** Whether [graph] has taken a frame, which is when a rebuild has something to flush. */
    private var fed = false

    /**
     * Feeds [frame] to a graph built for its shape, or for the shape of [shapeFrom] when given, and
     * hands every output to [onOutput]. Closes [frame]. A copy cut from a decoded frame passes the
     * decoded frame's info: where the decoder left the layout unspecified, the cut names FFmpeg's
     * default layout, which the graph for the decoded frame takes too.
     */
    fun feed(frame: Frame, onOutput: (Frame) -> Unit, shapeFrom: FrameInfo? = null) {
        val target = try {
            val info = shapeFrom ?: frame.info
            graph?.takeIf { shape.fits(info) } ?: rebuild(shapeOf(info), onOutput)
        } catch (failure: Throwable) {
            frame.close()
            throw failure
        }
        fed = true
        target.feedFrame(frame, onOutput)
    }

    /** Flushes the graph at the end of the stream. After this it takes no more frames. */
    fun flush(onOutput: (Frame) -> Unit) {
        graph?.flushInto(onOutput)
    }

    private fun rebuild(next: S, onOutput: (Frame) -> Unit): FilterGraph {
        graph?.let { old ->
            graph = null
            try {
                if (fed) old.flushInto(onOutput)
            } finally {
                old.close()
            }
        }
        return build(next).also {
            graph = it
            shape = next
            fed = false
        }
    }

    override fun close() {
        graph?.close()
        graph = null
    }
}

/** The video graph of a transcode: [description] over the frames of [stream]. */
internal fun transcodeVideoGraph(description: String, stream: StreamInfo): ShapedGraph<VideoShape> =
    ShapedGraph(VideoShape.declaredBy(stream), { VideoShape.of(it) }) { shape ->
        FilterGraph.buildVideo(
            description = description,
            width = shape.width,
            height = shape.height,
            pixelFormat = shape.pixelFormat,
            timeBase = stream.timeBase,
            frameRate = stream.video?.frameRate ?: Rational(25, 1),
            sampleAspectRatio = shape.sampleAspectRatio,
        )
    }

/**
 * The audio graph of a transcode: [description], or a plain conversion when it is null, from the
 * frames of [stream] to exactly what [encoder] takes, in chunks of its frame size.
 */
internal fun transcodeAudioGraph(description: String?, stream: StreamInfo, encoder: AudioEncoder): ShapedGraph<AudioShape> =
    ShapedGraph(AudioShape.declaredBy(stream), { AudioShape.of(it) }) { shape ->
        val graph = FilterGraph.buildAudio(
            description = description ?: "anull",
            sampleRate = shape.sampleRate,
            sampleFormat = shape.sampleFormat,
            channels = shape.channels,
            timeBase = stream.timeBase,
            outputSampleRate = encoder.sampleRate,
            outputSampleFormat = encoder.sampleFormat,
            outputChannels = encoder.channels,
            // The frames' own layout in, the encoder's exact layout out.
            channelLayoutMask = shape.channelLayoutMask,
            outputChannelLayoutMask = encoder.channelLayoutMask,
        )
        if (encoder.frameSize > 0) {
            try {
                graph.setOutputFrameSize(encoder.frameSize)
            } catch (failure: Throwable) {
                graph.close()
                throw failure
            }
        }
        graph
    }
