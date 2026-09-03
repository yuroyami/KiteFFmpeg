package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.Flow

/**
 * An open output file (muxer). Add every encoder first, video and audio, because the muxer's
 * header freezes the stream list. Then push frames through the encoders. Close the sink to
 * write the trailer and flush buffers.
 */
public expect class MediaSink : AutoCloseable {

    /**
     * Add a video encoder. Must be called before any frame is written.
     *
     * Frames whose pixel format differs from [VideoEncoderSpec.pixelFormat] are converted on the
     * way in. This handles a decoder that produces 10-bit frames, or a filter chain that does
     * not end in `format=`, rather than rejecting it. Frame dimensions are not touched: a size
     * mismatch is a configuration error and throws, since silently rescaling would hide it.
     */
    @Throws(FFmpegException::class)
    public fun addVideoEncoder(spec: VideoEncoderSpec): VideoEncoder

    /** Add an audio encoder. Must be called before any frame is written. */
    @Throws(FFmpegException::class)
    public fun addAudioEncoder(spec: AudioEncoderSpec): AudioEncoder

    /**
     * Add an output stream that copies [stream]'s packets verbatim from [source]: no decode,
     * no re-encode, only timestamp rescaling into the output's time-base (`ffmpeg -c copy`).
     * Bitstream filters are not applied, so format pairs that need one (e.g. h264 in mp4 →
     * MPEG-TS Annex B) are not yet supported.
     *
     * Must be called before any frame/packet is written. Packets are pulled by [Remuxer] or
     * [Transcoder]; this only declares the mapping.
     */
    @Throws(FFmpegException::class)
    public fun addCopyStream(source: MediaSource, stream: StreamInfo): CopyStream

    /**
     * Container-level metadata tags (`title`, `artist`, `comment`, …). Call this before any
     * frame or packet is written, because the tags are stored in the header.
     */
    @Throws(FFmpegException::class)
    public fun setMetadata(metadata: Map<String, String>)

    /**
     * Flushes every encoder, writes the trailer, and frees the muxer.
     *
     * The flush matters: encoders buffer (x264's lookahead holds tens of frames), so closing
     * without draining them would silently truncate the end of the output. The flush is
     * best-effort (if an encoder fails here, a trailer is still written for the data already
     * muxed). It does nothing for encoders already drained by [VideoEncoder.drive] or
     * [AudioEncoder.drive].
     *
     * @throws FFmpegException when the trailer fails. The file on disk is then broken, for
     *         example an mp4 whose moov atom was never written.
     */
    override fun close()

    public companion object {
        /**
         * Open a sink writing to [path].
         *
         * @param format container short name (`mp4`, `matroska`, `mpegts`, …). Null infers the
         *               format from the [path] extension.
         * @param options muxer private options applied before the header is written, such as
         *                `"movflags" to "+faststart"` (mp4 web-ready) or `"movie_timescale"`
         */
        @Throws(FFmpegException::class)
        public fun open(path: String, format: String? = null, options: Map<String, String> = emptyMap()): MediaSink
    }
}

/** An output stream fed by stream-copy. Opaque handle; packets flow through [Remuxer]/[Transcoder]. */
public expect class CopyStream {
    /**
     * Writes one demuxed packet through to the muxer, rebasing and rescaling its timestamps the
     * way [Remuxer] does. For a caller driving its own read loop: a tee, a recording taken while
     * something else plays, a split on the caller's own boundaries.
     *
     * The packet stays the caller's, unchanged and open. A REFERENCE is what reaches the muxer,
     * because `av_interleaved_write_frame` takes ownership of the payload it is given and the
     * rebase rewrites the stream index and the timestamps: handing it the caller's packet would
     * blank the very thing a tee still needs.
     *
     * Ordering is the caller's to get right. Packets must arrive in the order the muxer expects
     * for the stream, which for a copy of a demuxed stream means the order they were read in.
     *
     * @throws IllegalStateException when [packet] is closed, or when this stream's sink is.
     */
    @KiteFFmpegLowLevelApi
    public fun write(packet: Packet)
}

public data class VideoEncoderSpec(
    val codec: CodecId,
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat = PixelFormat.Yuv420p,
    val frameRate: Rational,
    val bitrateBps: Long = 4_000_000L,
    val keyframeIntervalFrames: Int = (frameRate.asDouble * 2).toInt().coerceAtLeast(1),
    /**
     * Encoder-specific options, passed through as `av_opt_set` strings: `"preset" to "veryfast"`,
     * `"crf" to "23"` (libx264), `"allow_sw" to "1"` (videotoolbox), etc.
     */
    val options: Map<String, String> = emptyMap(),
)

public data class AudioEncoderSpec(
    val codec: CodecId,
    val sampleRate: Int = 44_100,
    val channels: Int = 2,
    /** [SampleFormat.None] picks the encoder's preferred format (e.g. fltp for aac). */
    val sampleFormat: SampleFormat = SampleFormat.None,
    val bitrateBps: Long = 128_000L,
    /** Encoder-specific options, passed through as `av_opt_set` strings. */
    val options: Map<String, String> = emptyMap(),
)

/**
 * One configured and opened video encoder. Pull from a `Flow<Frame>` via [drive]. That call
 * pushes each frame into the encoder, pulls packets, hands them to the sink's muxer, and
 * flushes when the flow completes.
 *
 * Incoming frame pts are rescaled from the frame's own time-base onto the encoder's. Frames
 * without pts fall back to a frame counter. Output timestamps stay monotonic either way.
 */
public expect class VideoEncoder : AutoCloseable {
    /**
     * Drain [input] into this encoder + the parent muxer. Returns when the flow completes.
     * Reports progress every [progressEveryNFrames] via [onProgress] (the encoded-frame count).
     */
    public suspend fun drive(input: Flow<Frame>, onProgress: ((framesEncoded: Long) -> Unit)? = null, progressEveryNFrames: Int = 30)
    override fun close()
}

/**
 * One configured and opened audio encoder. Some codecs require a fixed input chunk size
 * (AAC: 1024 samples). For those, route frames through [FilterGraph.buildAudio] and call
 * [FilterGraph.setOutputFrameSize] with [frameSize] (Transcoder does this automatically).
 */
public expect class AudioEncoder : AutoCloseable {
    /** Samples the codec wants per frame; 0 when the codec takes arbitrary chunk sizes. */
    public val frameSize: Int
    /** The sample format actually negotiated (resolves [AudioEncoderSpec.sampleFormat] = None). */
    public val sampleFormat: SampleFormat
    public val sampleRate: Int
    public val channels: Int

    /** Drain [input] into this encoder + the parent muxer. Returns when the flow completes. */
    public suspend fun drive(input: Flow<Frame>)
    override fun close()
}
