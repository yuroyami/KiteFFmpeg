package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_codec_first_pix_fmt
import ffmpeg.ffkmp_codec_supports_pix_fmt
import ffmpeg.ffkmp_codecctx_alloc
import ffmpeg.ffkmp_codecctx_free
import ffmpeg.ffkmp_codecctx_open
import ffmpeg.ffkmp_codecctx_receive_packet
import ffmpeg.ffkmp_codecctx_send_frame
import ffmpeg.ffkmp_codecctx_set_full_range
import ffmpeg.ffkmp_codecctx_set_video
import ffmpeg.ffkmp_find_encoder_by_name
import ffmpeg.ffkmp_frame_alloc
import ffmpeg.ffkmp_frame_channels
import ffmpeg.ffkmp_frame_clone
import ffmpeg.ffkmp_frame_convert_pixfmt
import ffmpeg.ffkmp_frame_copy_to_buffer
import ffmpeg.ffkmp_frame_fill_audio
import ffmpeg.ffkmp_frame_fill_video
import ffmpeg.ffkmp_frame_format
import ffmpeg.ffkmp_frame_free
import ffmpeg.ffkmp_frame_get_buffer
import ffmpeg.ffkmp_frame_height
import ffmpeg.ffkmp_frame_width
import ffmpeg.ffkmp_frame_nb_samples
import ffmpeg.ffkmp_frame_pts
import ffmpeg.ffkmp_frame_sample_rate
import ffmpeg.ffkmp_frame_set_ch_layout_default
import ffmpeg.ffkmp_frame_set_format
import ffmpeg.ffkmp_frame_set_height
import ffmpeg.ffkmp_frame_set_nb_samples
import ffmpeg.ffkmp_frame_set_pts
import ffmpeg.ffkmp_frame_set_sample_rate
import ffmpeg.ffkmp_frame_set_width
import ffmpeg.ffkmp_frame_unref
import ffmpeg.ffkmp_image_get_buffer_size
import ffmpeg.ffkmp_packet_alloc
import ffmpeg.ffkmp_packet_data
import ffmpeg.ffkmp_packet_free
import ffmpeg.ffkmp_packet_size
import ffmpeg.ffkmp_packet_unref
import ffmpeg.ffkmp_rescale_q
import ffmpeg.ffkmp_samples_copy_to_buffer
import ffmpeg.ffkmp_samples_get_buffer_size
import ffmpeg.ffkmp_frame_ch_layout_mask
import ffmpeg.ffkmp_frame_chroma_location
import ffmpeg.ffkmp_frame_color_primaries
import ffmpeg.ffkmp_frame_color_range
import ffmpeg.ffkmp_frame_color_trc
import ffmpeg.ffkmp_frame_colorspace
import ffmpeg.ffkmp_frame_duration
import ffmpeg.ffkmp_frame_hw_download
import ffmpeg.ffkmp_frame_is_hardware
import ffmpeg.ffkmp_frame_is_keyframe
import ffmpeg.ffkmp_frame_sample_aspect_ratio
import ffmpeg.kc_frame
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

/**
 * AVFrame-backed [Frame] implementation. The native pointer ([nativeFrame]) is `internal`:
 * users go through [info] / [copyPlanesToByteArray]; the filter graph & encoder modules in
 * this package read the pointer directly for zero-copy hand-offs.
 *
 * Construction: see [Frame.acquire] (alloc) and [Frame.wrap] (when an existing AVFrame should
 * be adopted, e.g. from a decoder).
 */
public actual class Frame internal constructor(
    internal val nativeFrame: CPointer<kc_frame>,
    private val ownsPointer: Boolean,
    internal val streamIndex: Int,
    internal val streamType: MediaType,
    internal val streamTimeBase: Rational,
) : AutoCloseable {

    /**
     * Excludes [close] while an operation is inside native code, exactly as the JVM backend's
     * per-object locks do. A closed check alone was check-then-use: a concurrent close between the
     * check and the FFI call freed the AVFrame under the running operation. The lock
     * is reentrant, so a helper under [withNative] may read [checkedNative] again freely.
     */
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()

    private var closed = false

    private fun checkOpen() = check(!closed) { "Frame is closed, its native buffers are gone" }

    /**
     * The operation lease: runs [block] with the live pointer while holding the frame's lock, so a
     * concurrent [close] waits for the operation instead of freeing the buffers under it. Every
     * native operation in this class and every zero-copy hand-off outside it goes through this.
     */
    internal inline fun <R> withNative(block: (CPointer<kc_frame>) -> R): R =
        kotlinx.atomicfu.locks.synchronized(lock) {
            checkOpen()
            block(nativeFrame)
        }

    /**
     * The native pointer, gated on the open state at the moment of the read.
     *
     * This alone is not a lease: the pointer outlives the check the instant it is returned. It
     * exists for code already running under [withNative], where the reentrant lock makes the second
     * check cheap and the pointer provably live. Code NOT under the lease uses [withNative].
     */
    internal val checkedNative: CPointer<kc_frame>
        get() = kotlinx.atomicfu.locks.synchronized(lock) {
            checkOpen()
            nativeFrame
        }

    private var cachedInfo: FrameInfo? = null

    public actual val info: FrameInfo
        get() = withNative {
            // The metadata snapshot may be cached, but the open check must run on every read:
            // a warm cache must never stand in for proof that the native frame is still alive.
            cachedInfo ?: buildInfo().also { cachedInfo = it }
        }

    private fun buildInfo(): FrameInfo =
        FrameInfo(
            streamIndex   = streamIndex,
            type          = streamType,
            pts           = ffkmp_frame_pts(nativeFrame),
            timeBase      = streamTimeBase,
            width         = ffkmp_frame_width(nativeFrame),
            height        = ffkmp_frame_height(nativeFrame),
            pixelFormat   = if (streamType == MediaType.Video) pixelFormatFromAv(ffkmp_frame_format(nativeFrame)) else PixelFormat.None,
            sampleCount   = ffkmp_frame_nb_samples(nativeFrame),
            sampleRate    = ffkmp_frame_sample_rate(nativeFrame),
            channelCount  = if (streamType == MediaType.Audio) ffkmp_frame_channels(nativeFrame) else 0,
            sampleFormat  = if (streamType == MediaType.Audio) sampleFormatFromAv(ffkmp_frame_format(nativeFrame)) else SampleFormat.None,
            // 0 from the helper means there is no mask to report (unspecified, custom or
            // ambisonic order), which is what null says here.
            channelLayoutMask = if (streamType == MediaType.Audio) {
                ffkmp_frame_ch_layout_mask(nativeFrame).takeIf { it != 0L }
            } else null,
            duration      = ffkmp_frame_duration(nativeFrame),
            isKeyframe    = ffkmp_frame_is_keyframe(nativeFrame) != 0,
            color         = if (streamType == MediaType.Video) readColorInfo() else ColorInfo.Unspecified,
            sampleAspectRatio = if (streamType == MediaType.Video) readFrameSar() else Rational(1, 1),
            isHardware    = ffkmp_frame_is_hardware(nativeFrame) != 0,
        )

    private fun readColorInfo(): ColorInfo {
        val range = ffkmp_frame_color_range(nativeFrame)
        val declared = ColorInfo(
            matrix = ColorMatrix.fromAv(ffkmp_frame_colorspace(nativeFrame)),
            primaries = ColorPrimaries.fromAv(ffkmp_frame_color_primaries(nativeFrame)),
            transfer = ColorTransfer.fromAv(ffkmp_frame_color_trc(nativeFrame)),
            // AVCOL_RANGE_JPEG is 2 and means full range. Anything else, including unspecified,
            // means the studio range, which is what almost all video uses.
            fullRange = range == 2,
            chromaLocation = ChromaLocation.fromAv(ffkmp_frame_chroma_location(nativeFrame)),
            rangeSpecified = range == 1 || range == 2,
        )
        // A container that declares nothing is common. Handing the renderer "unspecified" would make
        // it guess, and it would guess without knowing the height. Guess here, where the height is
        // known, and by the rule every player uses.
        return resolveDeclaredColor(declared, ffkmp_frame_height(nativeFrame))
    }

    private fun readFrameSar(): Rational = memScoped {
        val n = alloc<IntVar>(); val d = alloc<IntVar>()
        ffkmp_frame_sample_aspect_ratio(nativeFrame, n.ptr, d.ptr)
        Rational(n.value, d.value.takeIf { it != 0 } ?: 1)
    }

    @Throws(FFmpegException::class)
    public actual fun copyPlanesToByteArray(): ByteArray = withNative {
        when (streamType) {
            MediaType.Video -> copyVideoPlanes()
            MediaType.Audio -> copyAudioSamples()
            else -> ByteArray(0)
        }
    }

    private fun copyVideoPlanes(): ByteArray = memScoped {
        val width = ffkmp_frame_width(nativeFrame)
        val height = ffkmp_frame_height(nativeFrame)
        val format = ffkmp_frame_format(nativeFrame)
        if (width <= 0 || height <= 0 || format < 0) return@memScoped ByteArray(0)

        val needed = ffkmp_image_get_buffer_size(format, width, height, 1)
        check0(needed, "av_image_get_buffer_size")
        val buf = allocArray<ByteVar>(needed)
        val written = ffkmp_frame_copy_to_buffer(nativeFrame, buf.reinterpret(), needed)
        check0(written, "av_image_copy_to_buffer")
        buf.readBytes(written)
    }

    private fun copyAudioSamples(): ByteArray = memScoped {
        if (ffkmp_frame_nb_samples(nativeFrame) <= 0) return@memScoped ByteArray(0)
        val needed = ffkmp_samples_get_buffer_size(nativeFrame)
        check0(needed, "av_samples_get_buffer_size")
        val buf = allocArray<ByteVar>(needed)
        val written = ffkmp_samples_copy_to_buffer(nativeFrame, buf.reinterpret(), needed)
        check0(written, "samples_copy_to_buffer")
        buf.readBytes(written)
    }

    @Throws(FFmpegException::class)
    public actual fun copy(): Frame = withNative {
        val cloned = ffkmp_frame_clone(nativeFrame)
            ?: throw FFmpegException(FFmpegError.Internal("av_frame_clone returned NULL"))
        Frame(cloned, ownsPointer = true, streamIndex = streamIndex, streamType = streamType, streamTimeBase = streamTimeBase)
    }

    @Throws(FFmpegException::class)
    public actual fun downloadFromHardware(): Frame = withNative {
        val downloaded = ffkmp_frame_alloc()
            ?: throw FFmpegException(FFmpegError.Internal("av_frame_alloc returned NULL"))
        val rc = ffkmp_frame_hw_download(nativeFrame, downloaded)
        if (rc < 0) {
            ffkmp_frame_free(downloaded)
            throw FFmpegException(avError(rc))
        }
        Frame(downloaded, ownsPointer = true, streamIndex = streamIndex, streamType = streamType, streamTimeBase = streamTimeBase)
    }

    @Throws(FFmpegException::class)
    public actual fun encodeImage(codec: CodecId): ByteArray = withNative {
        if (streamType != MediaType.Video) {
            throw FFmpegException(FFmpegError.Internal("encodeImage works on video frames, this is a $streamType frame"))
        }
        val width = ffkmp_frame_width(nativeFrame)
        val height = ffkmp_frame_height(nativeFrame)
        val format = ffkmp_frame_format(nativeFrame)
        if (width <= 0 || height <= 0 || format < 0) {
            throw FFmpegException(FFmpegError.Internal("Frame carries no image data"))
        }
        val encoder = ffkmp_find_encoder_by_name(codec.name)
            ?: throw FFmpegException(FFmpegError.EncoderNotFound(0, "No encoder named '${codec.name}'"))

        // Image codecs are picky about input pixel format (png: rgb*, mjpeg: yuvj*), so
        // convert when the frame's own format isn't accepted.
        val needsConvert = ffkmp_codec_supports_pix_fmt(encoder, format) == 0
        val sendFrame: CPointer<kc_frame>
        var converted: CPointer<kc_frame>? = null
        if (needsConvert) {
            val targetFmt = ffkmp_codec_first_pix_fmt(encoder)
            if (targetFmt < 0) throw FFmpegException(FFmpegError.Internal("Encoder '${codec.name}' advertises no pixel formats"))
            converted = ffkmp_frame_convert_pixfmt(nativeFrame, targetFmt)
                ?: throw FFmpegException(FFmpegError.Internal("Pixel format conversion failed"))
            sendFrame = converted
        } else {
            sendFrame = nativeFrame
        }

        try {
            val ctx = ffkmp_codecctx_alloc(encoder)
                ?: throw FFmpegException(FFmpegError.Internal("avcodec_alloc_context3 returned NULL"))
            try {
                ffkmp_codecctx_set_video(
                    ctx, width, height,
                    ffkmp_frame_format(sendFrame),
                    25, 1, 1, 25,  // dummy frame rate / time base, single image, irrelevant
                    8_000_000, 1,  // bitrate steers mjpeg quality; png ignores it
                )
                ffkmp_codecctx_set_full_range(ctx)  // mjpeg refuses limited-range yuv since FFmpeg 7
                check0(ffkmp_codecctx_open(ctx, encoder), "avcodec_open2 (image encoder)")
                val packet = ffkmp_packet_alloc()
                    ?: throw FFmpegException(FFmpegError.Internal("av_packet_alloc returned NULL"))
                // A single image needs pts 0, but when no conversion happened sendFrame IS the
                // caller's frame, and stamping it would silently destroy the timestamp of a frame
                // the caller may still want to encode into a video. Restore it below.
                val originalPts = ffkmp_frame_pts(sendFrame)
                try {
                    ffkmp_frame_set_pts(sendFrame, 0)
                    val eagain = FFErrors.EAGAIN
                    val eof = FFErrors.EOF
                    var bytes: ByteArray? = null
                    // EAGAIN-correct send/drain, same shape as the codec loops elsewhere.
                    // Image codecs emit one packet, but nothing in the API guarantees it.
                    fun drainAll() {
                        while (true) {
                            val rc = ffkmp_codecctx_receive_packet(ctx, packet)
                            if (rc == eagain || rc == eof) return
                            check0(rc, "avcodec_receive_packet (image)")
                            if (bytes == null) {
                                val size = ffkmp_packet_size(packet)
                                bytes = ffkmp_packet_data(packet)?.readBytes(size)
                            }
                            ffkmp_packet_unref(packet)
                        }
                    }
                    while (true) {
                        val rc = ffkmp_codecctx_send_frame(ctx, sendFrame)
                        if (rc == 0) break
                        if (rc == eagain) { drainAll(); continue }
                        check0(rc, "avcodec_send_frame (image)")
                    }
                    while (true) {
                        val rc = ffkmp_codecctx_send_frame(ctx, null)
                        if (rc == 0 || rc == eof) break
                        if (rc == eagain) { drainAll(); continue }
                        check0(rc, "avcodec_send_frame (image flush)")
                    }
                    drainAll()
                    return@withNative bytes
                        ?: throw FFmpegException(FFmpegError.Internal("Image encoder produced no packet"))
                } finally {
                    ffkmp_frame_set_pts(sendFrame, originalPts)
                    ffkmp_packet_free(packet)
                }
            } finally {
                ffkmp_codecctx_free(ctx)
            }
        } finally {
            converted?.let { ffkmp_frame_free(it) }
        }
    }

    actual override fun close(): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        // Under the same lock every operation holds, so a close arriving mid-operation WAITS for
        // it instead of freeing the buffers under it. Idempotent, exactly as before.
        if (closed) return
        closed = true
        if (ownsPointer) {
            ffkmp_frame_free(nativeFrame)
        } else {
            ffkmp_frame_unref(nativeFrame)
        }
    }

    public actual companion object {

        @Throws(FFmpegException::class)
        public actual fun ofVideo(
            bytes: ByteArray,
            width: Int,
            height: Int,
            pixelFormat: PixelFormat,
            ptsMicros: Long,
        ): Frame {
            // The FFmpeg identity gate. Before the first allocation.
            requireCompatibleFFmpeg()
            require(width > 0 && height > 0) { "Invalid dimensions ${width}x$height" }
            // Before the pin: addressOf(0) on an empty array throws its own index error, which is
            // not the short-buffer diagnostic this factory promises (audit KiteFFmpeg P1-15).
            require(bytes.isNotEmpty()) {
                "bytes is empty; a ${width}x$height ${pixelFormat.name} frame needs its packed planes"
            }
            val fmt = pixelFormatToAv(pixelFormat)
            if (fmt < 0) throw FFmpegException(FFmpegError.Internal("Unknown pixel format '${pixelFormat.name}'"))
            val raw = ffkmp_frame_alloc()
                ?: throw FFmpegException(FFmpegError.Internal("av_frame_alloc returned NULL"))
            try {
                ffkmp_frame_set_width(raw, width)
                ffkmp_frame_set_height(raw, height)
                ffkmp_frame_set_format(raw, fmt)
                check0(ffkmp_frame_get_buffer(raw, 0), "av_frame_get_buffer (video)")
                bytes.usePinned { pinned ->
                    check0(
                        ffkmp_frame_fill_video(raw, pinned.addressOf(0).reinterpret(), bytes.size),
                        "frame_fill_video (need packed ${pixelFormat.name} planes for ${width}x$height)",
                    )
                }
                ffkmp_frame_set_pts(raw, ptsMicros)
            } catch (t: Throwable) {
                ffkmp_frame_free(raw)
                throw t
            }
            return Frame(raw, ownsPointer = true, streamIndex = -1, streamType = MediaType.Video, streamTimeBase = Rational.Tb_us)
        }

        @Throws(FFmpegException::class)
        public actual fun ofAudio(
            bytes: ByteArray,
            sampleCount: Int,
            sampleRate: Int,
            channels: Int,
            sampleFormat: SampleFormat,
            ptsMicros: Long,
        ): Frame {
            // The FFmpeg identity gate. Before the first allocation.
            requireCompatibleFFmpeg()
            require(sampleCount > 0) { "sampleCount must be positive" }
            require(sampleRate > 0) { "sampleRate must be positive" }
            require(channels in 1..8) { "channels must be 1..8 (got $channels)" }
            require(bytes.isNotEmpty()) {
                "bytes is empty; $sampleCount ${sampleFormat.name} samples x $channels channels were promised"
            }
            val fmt = sampleFormatToAv(sampleFormat)
            if (fmt < 0) throw FFmpegException(FFmpegError.Internal("Unknown sample format '${sampleFormat.name}'"))
            val raw = ffkmp_frame_alloc()
                ?: throw FFmpegException(FFmpegError.Internal("av_frame_alloc returned NULL"))
            try {
                ffkmp_frame_set_nb_samples(raw, sampleCount)
                ffkmp_frame_set_sample_rate(raw, sampleRate)
                ffkmp_frame_set_format(raw, fmt)
                ffkmp_frame_set_ch_layout_default(raw, channels)
                check0(ffkmp_frame_get_buffer(raw, 0), "av_frame_get_buffer (audio)")
                bytes.usePinned { pinned ->
                    check0(
                        ffkmp_frame_fill_audio(raw, pinned.addressOf(0).reinterpret(), bytes.size),
                        "frame_fill_audio (need $sampleCount ${sampleFormat.name} samples x $channels ch)",
                    )
                }
                ffkmp_frame_set_pts(raw, ptsMicros)
            } catch (t: Throwable) {
                ffkmp_frame_free(raw)
                throw t
            }
            return Frame(raw, ownsPointer = true, streamIndex = -1, streamType = MediaType.Audio, streamTimeBase = Rational.Tb_us)
        }
    }
}

internal actual fun rescaleQ(value: Long, source: Rational, destination: Rational): Long =
    ffkmp_rescale_q(value, source.num, source.den, destination.num, destination.den)

internal object FrameOps {
    /** Allocate a fresh AVFrame wrapper that owns its pointer. */
    fun acquire(
        streamIndex: Int = -1,
        streamType: MediaType = MediaType.Video,
        timeBase: Rational = Rational(1, 1_000_000),
    ): Frame {
        val raw = ffkmp_frame_alloc()
            ?: throw FFmpegException(FFmpegError.Internal("av_frame_alloc returned NULL"))
        return Frame(raw, ownsPointer = true, streamIndex = streamIndex, streamType = streamType, streamTimeBase = timeBase)
    }

    /** Wrap an externally-owned AVFrame pointer (caller frees). */
    fun wrap(
        raw: CPointer<kc_frame>,
        streamIndex: Int,
        streamType: MediaType,
        timeBase: Rational,
    ): Frame = Frame(raw, ownsPointer = false, streamIndex = streamIndex, streamType = streamType, streamTimeBase = timeBase)
}
