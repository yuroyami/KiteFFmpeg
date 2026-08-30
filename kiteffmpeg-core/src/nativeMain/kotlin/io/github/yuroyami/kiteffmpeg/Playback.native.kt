@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_avseek_flag_any
import ffmpeg.ffkmp_avseek_flag_backward
import ffmpeg.ffkmp_codecctx_alloc
import ffmpeg.ffkmp_codecctx_flush
import ffmpeg.ffkmp_codecctx_free
import ffmpeg.ffkmp_codecctx_from_par
import ffmpeg.ffkmp_codecctx_open
import ffmpeg.ffkmp_codecctx_receive_frame
import ffmpeg.ffkmp_codecctx_send_packet
import ffmpeg.ffkmp_codecctx_set_low_delay
import ffmpeg.ffkmp_codecctx_set_opt
import ffmpeg.ffkmp_codecctx_use_videotoolbox
import ffmpeg.ffkmp_codecctx_set_threads
import ffmpeg.ffkmp_codec_id
import ffmpeg.ffkmp_codecpar_codec_id
import ffmpeg.ffkmp_find_decoder_by_id
import ffmpeg.ffkmp_find_decoder_by_name
import ffmpeg.ffkmp_fmt_read_frame
import ffmpeg.ffkmp_fmt_seek_file
import ffmpeg.ffkmp_fmt_stream
import ffmpeg.ffkmp_frame_alloc
import ffmpeg.ffkmp_frame_clone
import ffmpeg.ffkmp_frame_free
import ffmpeg.ffkmp_frame_linesize
import ffmpeg.ffkmp_frame_plane
import ffmpeg.ffkmp_frame_plane_count
import ffmpeg.ffkmp_frame_plane_height
import ffmpeg.ffkmp_frame_hw_surface
import ffmpeg.ffkmp_frame_use_best_effort_ts
import ffmpeg.ffkmp_packet_alloc
import ffmpeg.ffkmp_packet_duration
import ffmpeg.ffkmp_packet_dts
import ffmpeg.ffkmp_packet_free
import ffmpeg.ffkmp_packet_is_keyframe
import ffmpeg.ffkmp_packet_clone
import ffmpeg.ffkmp_packet_move_ref
import ffmpeg.ffkmp_packet_pos
import ffmpeg.ffkmp_packet_pts
import ffmpeg.ffkmp_packet_data
import kotlinx.cinterop.readBytes
import ffmpeg.ffkmp_packet_size
import ffmpeg.ffkmp_packet_stream_index
import ffmpeg.ffkmp_packet_unref
import ffmpeg.ffkmp_stream_codecpar
import ffmpeg.ffkmp_stream_discard_all
import ffmpeg.ffkmp_stream_discard_none
import ffmpeg.kc_codec_ctx
import ffmpeg.kc_fmt_ctx
import ffmpeg.kc_frame
import ffmpeg.kc_packet
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * A demuxed packet the caller owns.
 *
 * A player has to queue packets: it reads ahead of its decoders so a slow disk or a network stall
 * does not stop playback. So a packet must outlive the read that produced it, which the batch API's
 * packets deliberately do not.
 *
 * Taking ownership costs nothing. The payload is reference counted inside FFmpeg and moved rather
 * than copied, so this is a pointer swap and not a memcpy of the compressed data.
 *
 * Close it exactly once. An unclosed packet leaks its buffer, and reading anything off it after that
 * throws rather than dereferencing memory the allocator has taken back.
 */
@KiteFFmpegLowLevelApi
@OptIn(ExperimentalForeignApi::class)
public actual class Packet internal constructor(
    internal val native: CPointer<kc_packet>,
    /** The stream's time base, so the caller can convert [pts] without looking the stream up. */
    public actual val timeBase: Rational,
) : AutoCloseable {

    /**
     * Excludes [close] while a read is inside native code, the same per-object rule the JVM actual
     * has always had. The closed check alone was check-then-use.
     */
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()

    private var closed = false

    /**
     * Rejects use after [close].
     *
     * Every getter that reads native memory calls this first, and so does [StreamDecoder.send] for
     * the packet it is offered. [close] frees the `AVPacket`, so without the check a getter reads
     * memory the allocator is free to hand to something else and returns a plausible number instead
     * of failing. The derived getters ([hasPts], [ptsMicros], [dtsMicros], [durationMicros]) read
     * through the checked ones, so they are covered by those.
     */
    internal fun checkOpen() {
        check(!closed) { "Packet is closed" }
    }

    /** The operation lease: the pointer stays live for exactly the duration of [block]. */
    internal inline fun <R> locked(block: (CPointer<kc_packet>) -> R): R =
        kotlinx.atomicfu.locks.synchronized(lock) {
            checkOpen()
            block(native)
        }

    public actual val streamIndex: Int get() = locked { ffkmp_packet_stream_index(it) }

    /** Presentation timestamp in [timeBase] units, or [FrameInfo.NOPTS] when the container gave none. */
    public actual val pts: Long get() = locked { ffkmp_packet_pts(it) }

    public actual val dts: Long get() = locked { ffkmp_packet_dts(it) }

    /** Duration in [timeBase] units. 0 when unknown, which is common and not an error. */
    public actual val duration: Long get() = locked { ffkmp_packet_duration(it) }

    public actual val isKeyframe: Boolean get() = locked { ffkmp_packet_is_keyframe(it) != 0 }

    public actual val sizeBytes: Int get() = locked { ffkmp_packet_size(it) }

    /** Byte offset in the container, or -1. Useful for progress when timestamps are broken. */
    public actual val bytePosition: Long get() = locked { ffkmp_packet_pos(it) }

    public actual val hasPts: Boolean get() = pts != FrameInfo.NOPTS

    /**
     * [pts] converted to microseconds on the stream's own timeline. Null when there is no pts.
     *
     * The conversion is not `pts * 1_000_000 * num / den`. That form overflows a 64 bit signed
     * multiply on a fine time base: a nanosecond-timescale mp4 passes it after about two and a
     * half hours. This one goes through `av_rescale_q`, which carries a 128 bit intermediate.
     */
    public actual val ptsMicros: Long?
        get() = if (hasPts) ffmpeg.ffkmp_rescale_q(pts, timeBase.num, timeBase.den, 1, 1_000_000) else null

    /**
     * [dts] converted to microseconds on the stream's own timeline. Null when there is no dts.
     *
     * Decode timestamps run behind presentation timestamps wherever frames are reordered, which is
     * why a player watches them: they are what the demuxer's read position actually is. Overflow
     * safe on the same grounds as [ptsMicros].
     */
    public actual val dtsMicros: Long?
        get() = if (dts != FrameInfo.NOPTS) {
            ffmpeg.ffkmp_rescale_q(dts, timeBase.num, timeBase.den, 1, 1_000_000)
        } else null

    /**
     * [duration] converted to microseconds. Null when the container gave none.
     *
     * A duration is an interval, not a point on a timeline, so nothing about the container's start
     * offset applies to it. Zero means unknown, which is common and not an error, and reads as null
     * here rather than as an instantaneous packet.
     */
    public actual val durationMicros: Long?
        get() = if (duration > 0) {
            ffmpeg.ffkmp_rescale_q(duration, timeBase.num, timeBase.den, 1, 1_000_000)
        } else null

    @KiteFFmpegLowLevelApi
    @Throws(FFmpegException::class)
    public actual fun copy(): Packet = locked { live ->
        val cloned = ffkmp_packet_clone(live)
            ?: throw FFmpegException(FFmpegError.Internal("packet clone failed"))
        Packet(cloned, timeBase)
    }

    public actual fun copyBytes(): ByteArray = locked { live ->
        val size = ffkmp_packet_size(live)
        if (size <= 0) return@locked ByteArray(0)
        val data = ffkmp_packet_data(live) ?: return@locked ByteArray(0)
        data.readBytes(size)
    }

    actual override fun close(): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        if (closed) return
        closed = true
        ffkmp_packet_free(native)
    }
}

/** Which way a seek may land relative to the target. */
@KiteFFmpegLowLevelApi
public actual enum class SeekDirection {
    /** At or before the target, on a keyframe. What a player wants before decoding forward. */
    Backward,

    /** At or after the target. */
    Forward,

    /**
     * The nearest frame, keyframe or not.
     *
     * Only useful for a container whose index is trustworthy. On anything else the decoder cannot
     * produce output until the next keyframe anyway, so this lands somewhere unpredictable.
     */
    Any,
}

/**
 * Reads packets from a container, one at a time, under the caller's control.
 *
 * This is the demuxing half of a player, separated from decoding. The batch API fuses the two, which
 * is right for transcoding and wrong for playback: a player needs audio and video decoding to
 * proceed independently, so that a slow video decoder cannot stop the audio clock, and it needs to
 * seek while all of that is running.
 *
 * One reader per [MediaSource]. Reading, seeking and closing must all happen from one coroutine, or
 * be serialised by the caller. libavformat's context is a single cursor and is not thread safe.
 */
@KiteFFmpegLowLevelApi
@OptIn(ExperimentalForeignApi::class)
public actual class PacketReader internal constructor(
    private val source: MediaSource,
    private val ctx: CPointer<kc_fmt_ctx>,
    private var timeBaseByStream: Map<Int, Rational>,
) : AutoCloseable {

    private val scratch: CPointer<kc_packet> = ffkmp_packet_alloc()
        ?: throw FFmpegException(FFmpegError.Internal("av_packet_alloc returned NULL"))
    private var closed = false

    /**
     * Reads the next packet from a selected stream.
     *
     * @return an owned packet, or null at the end of the container. Null is the signal to start the
     *         decoder drain, by sending a null packet to each decoder.
     */
    @Throws(FFmpegException::class)
    public actual fun read(): Packet? {
        check(!closed) { "PacketReader is closed" }
        while (true) {
            val rc = ffkmp_fmt_read_frame(ctx, scratch)
            if (rc == FFErrors.EOF) return null
            if (rc < 0) throw FFmpegException(avError(rc))

            val index = ffkmp_packet_stream_index(scratch)
            val timeBase = timeBaseByStream[index]
            if (timeBase == null) {
                // A stream the caller did not select. AVDISCARD_ALL means libavformat usually skips
                // these before they get here, but a container can still deliver one.
                ffkmp_packet_unref(scratch)
                continue
            }

            val owned = ffkmp_packet_alloc()
                ?: throw FFmpegException(FFmpegError.Internal("av_packet_alloc returned NULL"))
            // Moves the reference. The compressed payload is not copied, so queueing packets ahead
            // of the decoders costs a pointer swap per packet and nothing else.
            ffkmp_packet_move_ref(owned, scratch)
            return Packet(owned, timeBase)
        }
    }

    /**
     * Moves the read cursor.
     *
     * This moves the cursor and nothing else. It does not flush the decoders and it does not clear
     * anything the caller has queued, because only the caller knows which generation those belong to.
     * Flushing here would let a packet read before the seek reach a decoder that was just reset.
     *
     * The window matters. [SeekDirection.Backward] with an unbounded lower limit lets libavformat
     * land arbitrarily early on a container without an index, and MPEG-TS resolves a seek by
     * searching byte positions, so it can also land *after* the target. Passing [notEarlierThan]
     * bounds the first case; the caller detects the second from the first decoded frame and retries.
     *
     * @param micros where to seek to, on the content-relative timeline the rest of the API uses.
     * @param notEarlierThan a lower bound on where the seek may land. Null means no bound.
     */
    @Throws(FFmpegException::class)
    public actual fun seek(
        micros: Long,
        direction: SeekDirection,
        notEarlierThan: Long?,
    ) {
        check(!closed) { "PacketReader is closed" }
        val target = source.toAbsoluteMicros(micros)
        val min = notEarlierThan?.let { source.toAbsoluteMicros(it) } ?: Long.MIN_VALUE
        // Any documents "the nearest indexed frame, whether or not it is a keyframe", which may
        // sit AFTER the target; capping max at the target made a closer later frame unreachable
        // and quietly turned Any into Backward-without-keyframes (audit KiteFFmpeg P1-13).
        val max = when (direction) {
            SeekDirection.Backward -> target
            SeekDirection.Forward, SeekDirection.Any -> Long.MAX_VALUE
        }
        val flags = when (direction) {
            SeekDirection.Backward -> ffkmp_avseek_flag_backward()
            SeekDirection.Forward -> 0
            SeekDirection.Any -> ffkmp_avseek_flag_any()
        }
        val rc = ffkmp_fmt_seek_file(ctx, -1, min, target, max, flags)
        if (rc < 0) throw FFmpegException(avError(rc))
    }

    @Throws(FFmpegException::class)
    public actual fun reselect(streams: List<StreamInfo>) {
        check(!closed) { "PacketReader is closed" }
        val next = canonicalPacketSelection(source.streams, streams)
        val previous = timeBaseByStream
        source.applyPacketReaderSelection(next.keys, previous.keys)
        // The Kotlin map is the exact delivery filter for demuxers that still surface packets from
        // streams marked AVDISCARD_ALL. Change it only after the native transaction succeeds.
        timeBaseByStream = next
    }

    actual override fun close() {
        if (closed) return
        closed = true
        ffkmp_packet_free(scratch)
        // Before releasing the source's reader slot, undo this reader's stream selection. The
        // discard flags belong to the demuxer and outlive the reader, so leaving them set would
        // make the batch decode API return zero frames for every stream this reader skipped, with
        // no error to explain it.
        try {
            source.restoreStreamDiscardDefaults()
        } finally {
            // Returning the cursor lease is owed even when restoring an FFmpeg discard flag
            // fails. Keep the restoration failure as the primary exception, but never strand the
            // source in reader-active state (closed is already true, so close cannot be retried).
            source.endPacketReader()
        }
    }
}

/**
 * One decoder, driven by the caller.
 *
 * The send and receive shape mirrors libavcodec, including the cases a simpler interface cannot
 * express: one packet producing no frames, one packet producing several, and a decoder that must be
 * drained before it accepts more input.
 *
 * Three rules that are easy to get wrong and expensive to debug:
 *
 * 1. Drain with [receive] until it returns null before assuming [send] will accept anything.
 * 2. When [send] returns false the packet was *not* consumed. Drain, then offer the same packet
 *    again. Discarding it loses frames silently.
 * 3. [flush] after every seek, and only after the caller has cleared its own queues. Flushing
 *    first lets a packet from the old position reach a freshly reset decoder.
 */
@KiteFFmpegLowLevelApi
@OptIn(ExperimentalForeignApi::class)
public actual class StreamDecoder internal constructor(
    public actual val stream: StreamInfo,
    private val codecCtx: CPointer<kc_codec_ctx>,
    private val corruptData: CorruptData = CorruptData.Skip,
) : AutoCloseable {

    private val landing: CPointer<kc_frame> = ffkmp_frame_alloc()
        ?: throw FFmpegException(FFmpegError.Internal("av_frame_alloc returned NULL"))
    private var closed = false

    /**
     * Excludes [close] while a decode call is inside native code, mirroring the JVM actual: the
     * closed check alone was check-then-use, and a concurrent close freed the codec context under
     * a running send or receive. Lock order is decoder then packet.
     */
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()

    /**
     * True once this decoder has emitted its last frame and will emit no more without a [flush].
     *
     * [receive] returns null for two different reasons and a player must tell them apart: the
     * decoder needs more input, or the stream is over. Without this the caller can only guess, and
     * the guess that ends playback on the first empty poll cuts the last frames off every file.
     * Set when [receive] sees end of stream, which happens after `send(null)` has been drained.
     * Cleared by [flush], because a flushed decoder accepts input again.
     */
    public actual var isDrained: Boolean = false
        private set

    public actual var corruptDataSkipped: Long = 0L
        private set

    /**
     * The one place damaged data is decided about.
     *
     * The tolerance below is still the default and still right: every seek into a stream carrying
     * its parameter sets in band lands before the next one, so the first packets after it decode
     * to nothing. What was wrong was doing it in silence, with nothing anywhere recording that the
     * output is short.
     */
    private fun noteCorruptData(rc: Int) {
        if (corruptData == CorruptData.Fail) throw FFmpegException(avError(rc))
        corruptDataSkipped++
    }

    /**
     * Offers a packet, or null to begin the end-of-stream drain.
     *
     * A closed packet is rejected. Its payload is gone, so sending it would hand the decoder a
     * dangling pointer, and a queue that closed a packet it still owed the decoder is a bug worth
     * hearing about at the call that made it rather than as corrupt output later.
     *
     * @return true when the packet was consumed. False means the decoder's output queue is full:
     *         call [receive] until it returns null, then offer this same packet again.
     */
    @Throws(FFmpegException::class)
    public actual fun send(packet: Packet?): Boolean = kotlinx.atomicfu.locks.synchronized(lock) {
        check(!closed) { "StreamDecoder is closed" }
        requireOwnStream(packet, stream)
        // The packet's own lease spans the native call, so ITS close waits too.
        val rc = packet?.locked { live -> ffkmp_codecctx_send_packet(codecCtx, live) }
            ?: ffkmp_codecctx_send_packet(codecCtx, null)
        return when {
            rc == 0 -> true
            rc == FFErrors.EOF -> true
            rc == FFErrors.EAGAIN -> false
            // A packet the decoder cannot use is not a reason to abandon the file. Every seek into a
            // stream that carries its parameter sets in band, MPEG-TS above all, lands before the
            // next parameter set, so the first packets after it decode to nothing. Treating that as
            // fatal would make seeking a broadcast capture impossible.
            rc == FFmpegError.AVERROR_INVALIDDATA -> {
                noteCorruptData(rc)
                true
            }
            else -> throw FFmpegException(avError(rc))
        }
    }

    /**
     * Takes the next decoded frame.
     *
     * @return an owned frame, or null when the decoder needs more input, or when it is drained and
     *         [isDrained] says so. The frame is an O(1) clone, so it shares its buffers with nothing
     *         the decoder will reuse, and the caller may queue it. Close it exactly once.
     */
    @Throws(FFmpegException::class)
    public actual fun receive(): Frame? = kotlinx.atomicfu.locks.synchronized(lock) {
        check(!closed) { "StreamDecoder is closed" }
        val rc = ffkmp_codecctx_receive_frame(codecCtx, landing)
        if (rc == FFErrors.EOF) {
            isDrained = true
            return null
        }
        if (rc == FFErrors.EAGAIN) return null
        // Same tolerance as the send side: a frame that could not be reconstructed from the packets
        // seen so far is skipped rather than fatal.
        if (rc == FFmpegError.AVERROR_INVALIDDATA) {
            noteCorruptData(rc)
            return null
        }
        if (rc < 0) throw FFmpegException(avError(rc))

        // Decoders fill best_effort_timestamp even for files with missing presentation timestamps.
        // Promoting it here means everything downstream sees one usable timestamp field.
        ffkmp_frame_use_best_effort_ts(landing)
        val cloned = ffkmp_frame_clone(landing)
            ?: throw FFmpegException(FFmpegError.Internal("av_frame_clone returned NULL"))
        return Frame(
            nativeFrame = cloned,
            ownsPointer = true,
            streamIndex = stream.index,
            streamType = stream.type,
            streamTimeBase = stream.timeBase,
        )
    }

    /**
     * Discards the decoder's internal state.
     *
     * Required after every seek. Without it the decoder emits frames reconstructed from packets
     * belonging to the position the viewer just left, which looks like a flash of the wrong picture.
     *
     * Clears [isDrained]: the decoder is ready for input again, wherever the caller seeks to.
     */
    public actual fun flush(): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        check(!closed) { "StreamDecoder is closed" }
        ffkmp_codecctx_flush(codecCtx)
        corruptDataSkipped = 0L
        isDrained = false
    }

    actual override fun close(): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        // Under the operation lock, so a close arriving during a send or receive waits for the
        // call to leave native code before freeing the context it is using.
        if (closed) return
        closed = true
        ffkmp_frame_free(landing)
        ffkmp_codecctx_free(codecCtx)
    }

    internal companion object {
        fun open(
            ctx: CPointer<kc_fmt_ctx>,
            stream: StreamInfo,
            threadCount: Int,
            lowDelay: Boolean,
            decoder: CodecId?,
            options: io.github.yuroyami.kiteffmpeg.dsl.DecoderOptions? = null,
            hardware: HardwareAccel? = null,
            corruptData: CorruptData = CorruptData.Skip,
        ): StreamDecoder {
            val streamPtr = ffkmp_fmt_stream(ctx, stream.index.toUInt())
                ?: throw FFmpegException(FFmpegError.Internal("Stream ${stream.index} disappeared"))
            val codecpar = ffkmp_stream_codecpar(streamPtr)
                ?: throw FFmpegException(FFmpegError.Internal("Stream ${stream.index} missing codecpar"))
            val codecId = ffkmp_codecpar_codec_id(codecpar)
            val codec = if (decoder == null) {
                ffkmp_find_decoder_by_id(codecId)
                    ?: throw FFmpegException(
                        FFmpegError.DecoderNotFound(0, decoderNotFoundMessage(stream.codec, requested = null)),
                    )
            } else {
                ffkmp_find_decoder_by_name(decoder.name)
                    ?: throw FFmpegException(
                        FFmpegError.DecoderNotFound(0, decoderNotFoundMessage(stream.codec, requested = decoder)),
                    )
            }
            if (decoder != null && ffkmp_codec_id(codec) != codecId) {
                throw FFmpegException(
                    FFmpegError.Internal(
                        "Decoder '${decoder.name}' cannot decode stream codec id $codecId",
                    ),
                )
            }

            val codecCtx = ffkmp_codecctx_alloc(codec)
                ?: throw FFmpegException(FFmpegError.Internal("avcodec_alloc_context3 returned NULL"))
            try {
                check0(ffkmp_codecctx_from_par(codecCtx, codecpar), "avcodec_parameters_to_context")
                // Frame-level threading for video is what makes real-time 4K possible at all. Low
                // delay for audio keeps the decoder from holding frames a player is waiting for.
                ffkmp_codecctx_set_threads(codecCtx, threadCount, if (stream.type == MediaType.Video) 1 else 0)
                ffkmp_codecctx_set_low_delay(codecCtx, if (lowDelay) 1 else 0)
                // KD-2 (KPKMP 17.10): typed options through the existing av_opt_set funnel,
                // between context creation and open, exactly where FFmpeg wants them.
                options?.compile()?.forEach { (key, value) ->
                    check0(ffkmp_codecctx_set_opt(codecCtx, key, value), "av_opt_set ('$key')")
                }
                // Window 3 (S2.a): the HWACCEL attach, in the same pre-open moment. A build
                // without the framework answers ENOSYS here, the typed capability refusal.
                when (hardware) {
                    HardwareAccel.VideoToolbox ->
                        check0(ffkmp_codecctx_use_videotoolbox(codecCtx), "videotoolbox device attach")
                    null -> Unit
                }
                check0(ffkmp_codecctx_open(codecCtx, codec), "avcodec_open2")
                // Construction allocates the landing frame. Keep it inside this ownership guard
                // so a landing-frame OOM cannot strand an already-open codec context.
                return StreamDecoder(stream, codecCtx, corruptData)
            } catch (t: Throwable) {
                ffkmp_codecctx_free(codecCtx)
                throw t
            }
        }
    }
}

/**
 * Reads a video frame's planes without copying them.
 *
 * [block] receives one pointer and one row pitch per plane. Both are valid only until it returns:
 * they point into the frame's own buffers, and the frame may be closed straight afterwards.
 *
 * The row pitch is not the width. It is almost never the width. A renderer that assumes otherwise
 * produces an image that skews diagonally, which is the most common first bug in every new video
 * pipeline. The pitch is given here rather than left to be computed for exactly that reason.
 *
 * The alternative is [Frame.copyPlanesToByteArray], which is correct and costs a full copy of the
 * frame: 3.11 MB for 1080p and 24.9 MB for 4K 10-bit, so between 187 MB/s and 1.5 GB/s at 60 frames
 * a second, plus one allocation per frame. That is right for a thumbnail and unusable for playback.
 *
 * @throws IllegalStateException when the frame is not a video frame. Rows and a row pitch are
 *         picture concepts: an audio frame's format is a SAMPLE format, so reading its planes as
 *         picture planes would report the geometry of whatever pixel format shares that ordinal.
 *         Use [Frame.copyPlanesToByteArray] for audio samples.
 * @throws IllegalStateException when the frame lives in hardware memory, which has no readable
 *         planes. Check [FrameInfo.isHardware] first, and use [Frame.hardwareSurface] instead.
 */
@KiteFFmpegLowLevelApi
@OptIn(ExperimentalForeignApi::class)
public fun <R> Frame.withPlanes(
    block: (planes: List<CPointer<UByteVar>>, strides: List<Int>, heights: List<Int>) -> R,
): R {
    check(info.type == MediaType.Video) {
        "withPlanes reads a video frame's picture planes, and this is a ${info.type} frame. Audio " +
            "samples are not laid out in rows: use copyPlanesToByteArray."
    }
    check(!info.isHardware) {
        "This frame lives in hardware memory and has no readable planes. Use hardwareSurface, or " +
            "download it first."
    }
    // The LEASE, not a check. checkedNative re-ran the open check but let the pointer escape the
    // lock immediately, so the plane addresses below were handed to the caller's block with nothing
    // holding the AVFrame alive: a concurrent close freed it under the block, on the render path
    // (audit P0-07). withNative holds the frame's lock for the whole call, block included, so a
    // close waits for the reader instead of racing it.
    return withNative { native ->
        val count = ffkmp_frame_plane_count(native)
        val planes = ArrayList<CPointer<UByteVar>>(count)
        val strides = ArrayList<Int>(count)
        val heights = ArrayList<Int>(count)
        for (i in 0 until count) {
            val plane = ffkmp_frame_plane(native, i) ?: break
            planes += plane
            strides += ffkmp_frame_linesize(native, i)
            heights += ffkmp_frame_plane_height(native, i)
        }
        block(planes, strides, heights)
    }
}

/**
 * The hardware surface behind this frame, or null when its pixels are in main memory.
 *
 * What the pointer is depends on the decoder: a `CVPixelBuffer` for VideoToolbox, a MediaCodec
 * output buffer, a VA-API surface. Only a renderer matched to that decoder can interpret it, which
 * is why it is returned untyped. It is valid until the frame is closed.
 */
@KiteFFmpegLowLevelApi
@OptIn(ExperimentalForeignApi::class)
public val Frame.hardwareSurface: COpaquePointer?
    // Under the lease for the same reason withPlanes is: checkedNative released the lock before the
    // call, so a close landing in that window freed the AVFrame this reads through.
    // The window is entirely inside this getter, so no test can open it; the guard is the lease
    // itself, which withPlanes' race test pins.
    get() = withNative { ffkmp_frame_hw_surface(it) }
