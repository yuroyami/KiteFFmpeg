package io.github.yuroyami.kiteffmpeg

/**
 * One decoded frame (video or audio), backed by an opaque native owner. Close it to release the
 * buffers.
 *
 * **Ownership rule.** Frames emitted by the public `Flow` APIs ([MediaSource.decodedFrames],
 * [MediaSource.decodeStreams], [FilterGraph.process]) are owned by the collector. Each frame
 * stays valid until you [close] it. Close every collected frame. An unclosed frame leaks its
 * native buffers. Frames handed to a callback ([FilterGraph.feedInput]'s `onOutput`) are valid
 * only for that call. Call [copy] to take an owned snapshot of one.
 *
 * **Buffering rule.** `toList()` is safe: every frame reaches you. Do not put frames through a
 * channel the standard library made: `buffer()`, `flowOn`, `conflate` and `produceIn` all queue
 * elements they cannot close, so cancelling part way through (`buffer().take(1)` is the ordinary
 * case) drops whatever is still queued and leaks it. Use [bufferFrames] instead, which owns its
 * channel and closes what you never receive; it takes a `context` for the `flowOn` case too.
 *
 * The native representation is intentionally not exposed. Pipeline operators ([FilterGraph],
 * encoders) accept Frames directly and resolve their platform handle internally.
 */
public expect class Frame : AutoCloseable {

    public val info: FrameInfo

    /**
     * Copy the frame's pixel planes (video) or samples (audio) into a flat ByteArray.
     * Planar formats stay planar and packed formats stay packed, with **no linesize padding**
     * (`align=1`, tightly packed). For yuv420p at WxH this returns `W*H*3/2` bytes
     * (Y plane, then U, then V); for fltp stereo it returns the left plane followed by
     * the right. [Frame.ofVideo] / [Frame.ofAudio] accept exactly this layout back.
     *
     * @return the frame's bytes, or an empty array for a frame that genuinely carries no
     *         data (an unreferenced frame, for example)
     * @throws FFmpegException if the copy fails
     */
    @Throws(FFmpegException::class)
    public fun copyPlanesToByteArray(): ByteArray

    /**
     * The number of bytes [copyPlanesToByteArray] returns for this frame, which is the size
     * [copyPlanesInto] needs.
     *
     * @throws FFmpegException if FFmpeg cannot describe the frame's layout
     */
    @Throws(FFmpegException::class)
    public fun planesByteCount(): Int

    /**
     * Copies the same bytes as [copyPlanesToByteArray] into [destination], from index 0, and
     * leaves the rest of [destination] as it was. A converter that keeps one array for every
     * frame of a stream allocates nothing per frame. Size the array with [planesByteCount].
     *
     * @return the number of bytes written, 0 for a frame that carries no data
     * @throws FFmpegException if [destination] is shorter than [planesByteCount], or if the copy
     *         fails
     */
    @Throws(FFmpegException::class)
    public fun copyPlanesInto(destination: ByteArray): Int

    /**
     * The CEA-608 and CEA-708 closed captions this video frame carries: the cc_data of its ATSC
     * A/53 part 4 side data, three bytes per caption pair. In each triplet the first byte holds
     * the valid flag and the caption type, and the other two hold the caption data.
     *
     * A decoder exports these from the video bitstream, such as the SEI messages of H.264 and HEVC
     * or the user data of MPEG-2, and attaches them to the frame they arrived with.
     *
     * @return a new array with the bytes, or null when the frame carries no captions
     * @throws FFmpegException if the side data cannot be read
     */
    @Throws(FFmpegException::class)
    public fun closedCaptions(): ByteArray?

    /**
     * An owned snapshot of this frame. Use it to keep a callback-scoped frame past that call.
     * O(1): it takes new references to the same refcounted buffers, with no pixel copy.
     * The returned frame survives the source being recycled. Close it yourself.
     *
     * @return a new owned frame sharing this one's buffers
     * @throws FFmpegException if the frame cannot be referenced
     */
    @Throws(FFmpegException::class)
    public fun copy(): Frame

    /**
     * The software download of a hardware frame: copies the pixels out of GPU memory into a new
     * ordinary frame and carries the presentation properties (pts, colour, rotation side data)
     * with them. It is the fallback made explicit: a renderer that cannot take the hardware
     * surface calls this once per frame and pays the copy knowingly, and can report that it did.
     *
     * The source frame is untouched and both frames are closed independently. A frame that is
     * not hardware ([FrameInfo.isHardware] false) is refused rather than copied, because reaching
     * the download on one means the caller's bookkeeping is wrong.
     *
     * @return a new owned software frame with the same timestamps and stream identity
     * @throws FFmpegException on a non-hardware source or when the transfer fails
     */
    @Throws(FFmpegException::class)
    public fun downloadFromHardware(): Frame

    /**
     * Encode this (video) frame as a standalone compressed image: MJPEG (`.jpg`) by default,
     * or [CodecId.Png]. This converts the pixel format automatically when the image codec does
     * not accept the frame's own (e.g. yuv420p → rgb24 for PNG). It leaves this frame untouched,
     * timestamp included, so a frame can be thumbnailed and still encoded into a video.
     *
     * @throws FFmpegException on audio frames, frames without image data, or encode failure
     */
    @Throws(FFmpegException::class)
    public fun encodeImage(codec: CodecId = CodecId.Mjpeg): ByteArray

    override fun close()

    public companion object {
        /**
         * Build a video frame from raw pixel bytes. This is the entry point for generative
         * use: images-to-video, procedural frames, and pixels produced by other libraries.
         *
         * [bytes] must be tightly packed planes in [pixelFormat]'s layout. That is exactly what
         * [copyPlanesToByteArray] produces (yuv420p: Y then U then V, no padding; rgba:
         * interleaved). Size must be at least the format's buffer size for [width]x[height].
         *
         * The frame owns its buffers (close it or hand it to an encoder/filter, which closes
         * it for you). [ptsMicros] sets the timestamp on a 1/1_000_000 time-base;
         * [FrameInfo.NOPTS] leaves it unset (encoders then fall back to a frame counter).
         *
         * @throws FFmpegException on unknown pixel format, allocation failure, or short [bytes]
         */
        @Throws(FFmpegException::class)
        public fun ofVideo(
            bytes: ByteArray,
            width: Int,
            height: Int,
            pixelFormat: PixelFormat,
            ptsMicros: Long = FrameInfo.NOPTS,
        ): Frame

        /**
         * Build an audio frame from raw sample bytes. [bytes] layout: planar formats are
         * plane-after-plane (all of channel 0, then channel 1, …), packed formats are
         * interleaved. Both match what [copyPlanesToByteArray] produces. Channels beyond 8
         * are unsupported.
         *
         * @throws FFmpegException on unknown sample format, allocation failure, or short [bytes]
         */
        @Throws(FFmpegException::class)
        public fun ofAudio(
            bytes: ByteArray,
            sampleCount: Int,
            sampleRate: Int,
            channels: Int,
            sampleFormat: SampleFormat,
            ptsMicros: Long = FrameInfo.NOPTS,
        ): Frame
    }
}

/**
 * [FrameInfo.pts] converted to microseconds on the stream's own timeline. Null when the frame
 * carries no timestamp.
 *
 * The conversion uses FFmpeg's overflow-safe rational rescale rather than multiplying a timestamp
 * by one million directly. The value still includes the container's start offset; a player that
 * presents a zero-based position subtracts [MediaSource.startTimeMicros] at its timeline boundary.
 */
@KiteFFmpegLowLevelApi
public val Frame.ptsMicros: Long?
    get() = info.let {
        if (it.hasPts) rescaleQ(it.pts, it.timeBase, Rational.Tb_us) else null
    }

/**
 * [FrameInfo.duration] converted to microseconds. Null when the decoder supplied no duration.
 * A duration is an interval, so no container start offset applies to it.
 */
@KiteFFmpegLowLevelApi
public val Frame.durationMicros: Long?
    get() = info.let {
        if (it.duration > 0L) rescaleQ(it.duration, it.timeBase, Rational.Tb_us) else null
    }

/** Platform-backed overflow-safe equivalent of FFmpeg's `av_rescale_q`. */
internal expect fun rescaleQ(value: Long, source: Rational, destination: Rational): Long

/** The refusal every backend gives [Frame.copyPlanesInto] for a destination shorter than the frame. */
internal fun destinationTooShort(destination: Int, needed: Int): FFmpegException = FFmpegException(
    FFmpegError.InvalidArgument(0, "The destination holds $destination bytes and this frame needs $needed; size it with planesByteCount()"),
)
