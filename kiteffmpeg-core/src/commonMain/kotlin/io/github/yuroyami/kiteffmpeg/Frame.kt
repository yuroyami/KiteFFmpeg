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
 * **Buffering caveat.** `toList()` is safe: every frame reaches you. Operators that hold frames
 * in an intermediate channel, `buffer()` above all, are only safe when the flow is collected to
 * completion. Cancelling mid-stream (for example `buffer().take(1)`) strands the frames still
 * queued inside the operator, and the standard library gives this flow no hook to close them,
 * so they leak. When you need early termination, collect without `buffer()`, or apply `take`
 * BEFORE any buffering operator so only frames you will actually receive are ever cloned.
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
     * The measured software download of a hardware frame (window 3, S2.a): copies the pixels out
     * of GPU memory into a new ordinary frame and carries the presentation properties (pts,
     * colour, rotation side data) with them. This is D-2's fallback path made explicit: a
     * renderer that cannot take the hardware surface calls this once per frame and pays the copy
     * knowingly, which is exactly what `HardwareWithDownload` reports upstream.
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
