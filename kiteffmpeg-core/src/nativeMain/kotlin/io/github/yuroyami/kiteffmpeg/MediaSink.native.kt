package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_codec_first_sample_fmt
import ffmpeg.ffkmp_codecctx_alloc
import ffmpeg.ffkmp_codecctx_channels
import ffmpeg.ffkmp_codecctx_frame_size
import ffmpeg.ffkmp_codecctx_free
import ffmpeg.ffkmp_codecctx_height
import ffmpeg.ffkmp_codecctx_open
import ffmpeg.ffkmp_codecctx_pix_fmt
import ffmpeg.ffkmp_codecctx_receive_packet
import ffmpeg.ffkmp_codecctx_sample_rate
import ffmpeg.ffkmp_codecctx_send_frame
import ffmpeg.ffkmp_codecctx_set_audio
import ffmpeg.ffkmp_codecctx_set_global_header
import ffmpeg.ffkmp_codecctx_set_opt
import ffmpeg.ffkmp_codecctx_set_video
import ffmpeg.ffkmp_codecctx_time_base
import ffmpeg.ffkmp_codecctx_width
import ffmpeg.ffkmp_codecpar_copy_for_mux
import ffmpeg.ffkmp_codecpar_from_context
import ffmpeg.ffkmp_find_encoder_by_name
import ffmpeg.ffkmp_frame_convert_pixfmt
import ffmpeg.ffkmp_frame_format
import ffmpeg.ffkmp_frame_free
import ffmpeg.ffkmp_frame_height
import ffmpeg.ffkmp_frame_nb_samples
import ffmpeg.ffkmp_frame_pts
import ffmpeg.ffkmp_frame_set_pts
import ffmpeg.ffkmp_frame_width
import ffmpeg.ffkmp_packet_alloc
import ffmpeg.ffkmp_packet_dts
import ffmpeg.ffkmp_packet_free
import ffmpeg.ffkmp_packet_pts
import ffmpeg.ffkmp_packet_rescale_ts
import ffmpeg.ffkmp_packet_set_dts
import ffmpeg.ffkmp_packet_set_pts
import ffmpeg.ffkmp_packet_set_stream_index
import ffmpeg.ffkmp_packet_unref
import ffmpeg.ffkmp_fmt_alloc_output2
import ffmpeg.ffkmp_fmt_avoid_negative_ts
import ffmpeg.ffkmp_fmt_free_output
import ffmpeg.ffkmp_fmt_set_opt
import ffmpeg.ffkmp_fmt_io_open
import ffmpeg.ffkmp_fmt_new_stream
import ffmpeg.ffkmp_fmt_set_metadata
import ffmpeg.ffkmp_fmt_write_frame
import ffmpeg.ffkmp_fmt_write_header
import ffmpeg.ffkmp_fmt_write_trailer
import ffmpeg.ffkmp_oformat_global_header
import ffmpeg.ffkmp_rescale_q
import ffmpeg.ffkmp_stream_codecpar
import ffmpeg.ffkmp_stream_index
import ffmpeg.ffkmp_stream_set_time_base
import ffmpeg.ffkmp_stream_time_base
import ffmpeg.kc_codec
import ffmpeg.kc_codec_ctx
import ffmpeg.kc_fmt_ctx
import ffmpeg.kc_frame
import ffmpeg.kc_packet
import ffmpeg.kc_stream
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow

public actual class MediaSink internal constructor(
    private val ctx: CPointer<kc_fmt_ctx>,
    private val outputPath: String,
) : AutoCloseable {

    private enum class HeaderState { NotWritten, Written, Failed }

    private val encoderCores = mutableListOf<EncoderCore>()

    /** Streams declared on this sink; close() owes a real container once this is nonzero. */
    private var declaredStreams = 0
    private var headerState = HeaderState.NotWritten

    /**
     * Two flags because the close WRITES on its way out. [closeBegun] flips at the
     * top of close, under [muxLock], and is what a second close and every add checks: nothing new
     * may start once a close exists. [closed] flips only after the muxer is freed, and is what the
     * write path checks: the close's own flush writes are legal, and an outside writer that was
     * blocked on [muxLock] resumes only after the whole close released it, when this flag already
     * refuses the freed muxer for it.
     */
    private var closeBegun = false
    private var closed = false

    /**
     * The failure that made this sink unusable, if one has.
     *
     * `avformat_new_stream` MUTATES the format context and runs before the setup that can still
     * fail, so a refused encoder used to leave a real, half-configured stream inside the muxer and
     * hand back an ordinary-looking sink. Adding another stream on top of that, or writing a
     * header over it, produces a container whose stream table does not describe its contents.
     * FFmpeg offers no way to take a stream back out, so the sink is finished and says so.
     */
    private var failure: Throwable? = null

    private fun checkUsable() {
        failure?.let {
            throw IllegalStateException(
                "This MediaSink failed while a stream was being added and cannot be used again. " +
                    "Its muxer already holds a stream that could not be configured, and FFmpeg " +
                    "offers no way to remove one. Close it, delete the output, and open a new " +
                    "sink. The original failure is attached.",
                it,
            )
        }
    }

    /** Records a failure that struck after the muxer was mutated, and rethrows it unchanged. */
    private fun poison(error: Throwable): Nothing {
        if (failure == null) failure = error
        throw error
    }

    /**
     * Reentrant lock serializing everything that touches the shared muxer state: header
     * write, `av_interleaved_write_frame`, and the shared timeline base. Encoding itself
     * (separate AVCodecContexts) stays lock-free, so a video and an audio encoder driven
     * from concurrent coroutines only serialize at the muxer boundary, which libavformat
     * requires anyway.
     */
    private val muxLock = SynchronizedObject()

    /**
     * One timeline base for the WHOLE output, claimed by the first stream that produces a
     * timestamp. Rebasing every stream by its own first ts would erase the relative offset
     * between streams (video's first packet after a seek is the keyframe, audio's is later)
     * and desync A/V by up to a GOP.
     */
    private var sharedBaseMicros = Long.MIN_VALUE

    /** First caller wins; everyone rebases against the same origin. */
    internal fun claimBaseMicros(candidateMicros: Long): Long = synchronized(muxLock) {
        if (sharedBaseMicros == Long.MIN_VALUE) sharedBaseMicros = candidateMicros
        sharedBaseMicros
    }

    private val headerWritten: Boolean get() = synchronized(muxLock) { headerState == HeaderState.Written }

    @Throws(FFmpegException::class)
    public actual fun addVideoEncoder(spec: VideoEncoderSpec): VideoEncoder = synchronized(muxLock) {
        check(!closeBegun) { "MediaSink is closed" }
        checkUsable()
        val codecCtx = newEncoderContext(spec.codec.name) { codec, cc ->
            ffkmp_codecctx_set_video(
                cc,
                spec.width, spec.height,
                pixelFormatToAv(spec.pixelFormat),
                spec.frameRate.num, spec.frameRate.den,
                spec.frameRate.den, spec.frameRate.num,  // time_base = inverse of frame rate
                spec.bitrateBps,
                spec.keyframeIntervalFrames,
            )
            spec.options.forEach { (k, v) -> check0(ffkmp_codecctx_set_opt(cc, k, v), "av_opt_set ('$k')") }
        }
        val stream = newStreamFor(codecCtx)
        val core = EncoderCore(
            sink = this,
            codecCtx = codecCtx,
            stream = stream,
            // Read back from the OPENED context, never assumed from the spec: avcodec_open2 is
            // free to adjust time_base, and a stale value here would rescale every packet
            // against the wrong base (wrong playback speed) while newStreamFor, which reads it
            // properly, told the muxer the real one.
            codecTimeBase = codecCtxTimeBase(codecCtx),
            isAudio = false,
        )
        encoderCores += core
        VideoEncoder(core)
    }

    @Throws(FFmpegException::class)
    public actual fun addCopyStream(source: MediaSource, stream: StreamInfo): CopyStream = synchronized(muxLock) {
        check(!closeBegun) { "MediaSink is closed" }
        checkUsable()
        check(!headerWritten) { "Cannot add streams after the muxer has started writing." }

        val sourcePar = source.codecparOf(stream)
            ?: throw FFmpegException(FFmpegError.Internal("Stream ${stream.index} has no codec parameters"))
        val outStream = ffkmp_fmt_new_stream(ctx, null)
            ?: throw FFmpegException(FFmpegError.Internal("avformat_new_stream returned NULL"))
        // From here the format context HAS a new stream in it and FFmpeg cannot take one back, so
        // every failure below leaves a half-configured stream in the muxer. Without the poison the
        // sink still looked usable and the next call wrote against it. newStreamFor has poisoned
        // since P1-10; this path mutates identically and was left out.
        try {
            // KC-EVIDENCE-MUX: the only way to reach the poison below from a test. Inert unless a
            // test armed it, and self-disarming, so production always takes the false branch.
            MuxFaults.failIfArmed("addCopyStream/native")
            val outPar = ffkmp_stream_codecpar(outStream)
                ?: throw FFmpegException(FFmpegError.Internal("New stream missing codecpar"))
            check0(ffkmp_codecpar_copy_for_mux(outPar, sourcePar), "avcodec_parameters_copy")
            // Seed the output time-base with the input's; the muxer may still rewrite it in
            // avformat_write_header, which is why writeCopyPacket re-reads it per packet.
            ffkmp_stream_set_time_base(outStream, stream.timeBase.num, stream.timeBase.den)
        } catch (error: Throwable) {
            poison(error)
        }

        declaredStreams += 1
        CopyStream(sink = this, stream = outStream, sourceTimeBase = stream.timeBase, sourceIndex = stream.index)
    }

    @Throws(FFmpegException::class)
    public actual fun addAudioEncoder(spec: AudioEncoderSpec): AudioEncoder = synchronized(muxLock) {
        check(!closeBegun) { "MediaSink is closed" }
        checkUsable()
        var negotiatedFormat = spec.sampleFormat
        val codecCtx = newEncoderContext(spec.codec.name) { codec, cc ->
            if (negotiatedFormat == SampleFormat.None) {
                val first = ffkmp_codec_first_sample_fmt(codec)
                if (first < 0) throw FFmpegException(
                    FFmpegError.Internal("Encoder '${spec.codec.name}' does not advertise sample formats; set AudioEncoderSpec.sampleFormat explicitly")
                )
                negotiatedFormat = sampleFormatFromAv(first)
            }
            ffkmp_codecctx_set_audio(
                cc,
                spec.sampleRate,
                sampleFormatToAv(negotiatedFormat),
                spec.channels,
                spec.bitrateBps,
            )
            spec.options.forEach { (k, v) -> check0(ffkmp_codecctx_set_opt(cc, k, v), "av_opt_set ('$k')") }
        }
        val stream = newStreamFor(codecCtx)
        val core = EncoderCore(
            sink = this,
            codecCtx = codecCtx,
            stream = stream,
            codecTimeBase = codecCtxTimeBase(codecCtx),
            isAudio = true,
        )
        encoderCores += core
        AudioEncoder(
            core = core,
            frameSize = ffkmp_codecctx_frame_size(codecCtx),
            sampleFormat = negotiatedFormat,
            // Report what the encoder actually opened with, not what was asked for, because callers
            // (Transcoder builds its aformat pin from these) must resample to the real values.
            sampleRate = ffkmp_codecctx_sample_rate(codecCtx).takeIf { it > 0 } ?: spec.sampleRate,
            channels = ffkmp_codecctx_channels(codecCtx).takeIf { it > 0 } ?: spec.channels,
        )
    }

    /** The time-base the OPENED context settled on; authoritative for all pts math. */
    private fun codecCtxTimeBase(codecCtx: CPointer<kc_codec_ctx>): Rational = memScoped {
        val n = alloc<IntVar>(); val d = alloc<IntVar>()
        ffkmp_codecctx_time_base(codecCtx, n.ptr, d.ptr)
        Rational(n.value.takeIf { it != 0 } ?: 1, d.value.takeIf { it != 0 } ?: 1)
    }

    /** Find + configure + open one encoder context; [configure] sets type-specific fields. */
    private inline fun newEncoderContext(
        codecName: String,
        configure: (codec: CPointer<kc_codec>, cc: CPointer<kc_codec_ctx>) -> Unit,
    ): CPointer<kc_codec_ctx> {
        check(!headerWritten) { "Cannot add encoders after the muxer has started writing." }
        check(!closed) { "MediaSink is closed" }

        // Typed, not Internal. A missing encoder is a condition a caller handles (fall back to
        // software, pick another codec, name the build), and `when (error)` can only reach it if the
        // kind survives. The JVM twin was converted; this half was missed.
        val codec = ffkmp_find_encoder_by_name(codecName)
            ?: throw FFmpegException(FFmpegError.EncoderNotFound(0, "No encoder named '$codecName'"))
        val codecCtx = ffkmp_codecctx_alloc(codec)
            ?: throw FFmpegException(FFmpegError.Internal("avcodec_alloc_context3 returned NULL"))
        try {
            configure(codec, codecCtx)
            if (ffkmp_oformat_global_header(ctx) == 1) {
                ffkmp_codecctx_set_global_header(codecCtx)
            }
            check0(ffkmp_codecctx_open(codecCtx, codec), "avcodec_open2 (encoder)")
            return codecCtx
        } catch (t: Throwable) {
            ffkmp_codecctx_free(codecCtx)
            throw t
        }
    }

    /** Create the muxer stream for an opened encoder context and copy its parameters over. */
    private fun newStreamFor(codecCtx: CPointer<kc_codec_ctx>): CPointer<kc_stream> {
        var mutated = false
        try {
            val stream = ffkmp_fmt_new_stream(ctx, null)
                ?: throw FFmpegException(FFmpegError.Internal("avformat_new_stream returned NULL"))
            // From here the format context HAS a new stream in it and nothing can take it back.
            mutated = true
            val par = ffkmp_stream_codecpar(stream)
                ?: throw FFmpegException(FFmpegError.Internal("New stream missing codecpar"))
            check0(ffkmp_codecpar_from_context(par, codecCtx), "avcodec_parameters_from_context")
            val tb = codecCtxTimeBase(codecCtx)
            ffkmp_stream_set_time_base(stream, tb.num, tb.den)
            declaredStreams += 1
            return stream
        } catch (t: Throwable) {
            ffkmp_codecctx_free(codecCtx)
            if (mutated) poison(t)
            throw t
        }
    }

    @Throws(FFmpegException::class)
    public actual fun setMetadata(metadata: Map<String, String>): Unit = synchronized(muxLock) {
        check(!headerWritten) { "Metadata must be set before the muxer writes its header." }
        check(!closed) { "MediaSink is closed" }
        // A POISONED sink refuses here too, matching the JVM backend, whose setMetadata has always
        // gone through its usability check. The two disagreed until 2026-08-30: this one asked only
        // whether the sink was closed, so a sink holding a half-configured stream accepted metadata
        // and went on looking usable, which is the exact impression the poison exists to remove.
        // Writing a title into a container whose stream table will never describe its contents buys
        // nothing, and the conservative reading is the one worth having on both backends.
        //
        // Under muxLock for the same reason every other muxer touch is: av_dict_set mutates the
        // format context, and this was the one entry point that read the poison flag and the header
        // state without holding the lock that guards them.
        checkUsable()
        metadata.forEach { (k, v) ->
            check0(ffkmp_fmt_set_metadata(ctx, k, v), "av_dict_set (metadata '$k')")
        }
    }

    internal fun ensureHeaderWritten(): Unit = synchronized(muxLock) {
        check(!closed) { "MediaSink is closed" }
        when (headerState) {
            HeaderState.Written -> return
            HeaderState.Failed -> throw FFmpegException(
                FFmpegError.Internal("The muxer header failed to write earlier, so this sink is unusable")
            )
            HeaderState.NotWritten -> {}
        }
        // Flip to Failed BEFORE attempting: if avio_open/write_header throws, close() must
        // NOT call av_write_trailer on a context whose header never landed (undefined
        // behavior in FFmpeg). Only a successful write_header reaches Written.
        headerState = HeaderState.Failed
        check0(ffkmp_fmt_io_open(ctx, outputPath), "avio_open")
        check0(ffkmp_fmt_write_header(ctx), "avformat_write_header")
        headerState = HeaderState.Written
    }

    internal fun writePacket(packet: CPointer<kc_packet>): Unit = synchronized(muxLock) {
        // The close-vs-write race: a writer that blocked on this lock while close ran
        // used to resume and hand the packet to a FREED muxer, because nothing here re-read the
        // closed flag after the wait. close() holds this same lock for its entire flush, trailer
        // and free, so the flag is the whole fix: a late writer now fails typed instead.
        check(!closed) { "MediaSink is closed" }
        ensureHeaderWritten()
        val rc = ffkmp_fmt_write_frame(ctx, packet)
        if (rc < 0) throw FFmpegException(avError(rc))
    }

    actual override fun close() {
        var firstFailure: Throwable? = null
        val trailerRc = synchronized(muxLock) {
            if (closeBegun) return
            closeBegun = true
            var rc = 0
            try {
                // Flush BEFORE freeing the contexts. Encoders buffer (libx264's lookahead holds
                // tens of frames); freeing without an EOF drain silently truncates the tail. The
                // FIRST flush error is retained and thrown after cleanup: tail frames lost while
                // close reports success was the audit's P1-6, and one encoder failing is not a
                // reason to skip draining the others or the trailer for what did land.
                if (encoderCores.isNotEmpty()) {
                    withPacket { packet ->
                        encoderCores.forEach { core ->
                            runCatching { core.finish(packet) }.exceptionOrNull()?.let { failure ->
                                if (firstFailure == null) firstFailure = failure
                                else firstFailure?.addSuppressed(failure)
                            }
                        }
                    }
                }
                encoderCores.forEach { runCatching { it.close() } }
                encoderCores.clear()
                // Declared streams and no packet means the header never wrote itself on demand.
                // The sink still owes a real container: header now, trailer below, or an explicit
                // failure. Declaring streams and closing used to perform no I/O and report
                // success.
                if (headerState == HeaderState.NotWritten && declaredStreams > 0) {
                    runCatching { ensureHeaderWritten() }.exceptionOrNull()?.let { failure ->
                        if (firstFailure == null) firstFailure = failure
                        else firstFailure?.addSuppressed(failure)
                    }
                }
                if (headerState == HeaderState.Written) {
                    rc = ffkmp_fmt_write_trailer(ctx)
                }
            } finally {
                memScoped {
                    val pp = alloc<CPointerVar<kc_fmt_ctx>>().also { it.value = ctx }
                    // The output file's own close. A trailer that wrote fine can still be lost
                    // when the final flush hits a full disk, and discarding this reported that
                    // file as written. The trailer's own error wins when both fail:
                    // it happened first and describes the container, not the medium.
                    val closeRc = ffkmp_fmt_free_output(pp.ptr)
                    if (rc >= 0 && closeRc < 0) rc = closeRc
                }
                closed = true
            }
            rc
        }
        firstFailure?.let { throw it }
        // A failed trailer means the file on disk is broken (e.g. mp4 moov never written), and
        // surfacing that beats handing the caller a corrupt output with a green checkmark.
        if (trailerRc < 0) throw FFmpegException(avError(trailerRc))
    }

    public actual companion object {
        @Throws(FFmpegException::class)
        public actual fun open(path: String, format: String?, options: Map<String, String>): MediaSink {
            // The FFmpeg identity gate, register item B1-02. Before the first allocation.
            requireCompatibleFFmpeg()
            val arena = kotlinx.cinterop.Arena()
            val ctxVar = arena.allocPointerTo<kc_fmt_ctx>()
            val rc = ffkmp_fmt_alloc_output2(ctxVar.ptr, path, format)
            if (rc < 0) { arena.clear(); throw FFmpegException(avError(rc)) }
            val ctx = ctxVar.value
            arena.clear()
            if (ctx == null) throw FFmpegException(FFmpegError.Internal("alloc_output returned NULL"))
            // Streams are rebased against ONE shared origin (claimBaseMicros), which preserves the
            // relative A/V offset but lets a stream that begins before the claiming one land at a
            // negative timestamp (AAC priming samples are the usual source). Pin the muxer policy
            // rather than inherit each format's default: MAKE_ZERO shifts the whole output by one
            // common amount, so nothing is negative and the offset survives.
            ffkmp_fmt_avoid_negative_ts(ctx)
            try {
                options.forEach { (k, v) ->
                    check0(ffkmp_fmt_set_opt(ctx, k, v), "av_opt_set (muxer option '$k')")
                }
            } catch (t: Throwable) {
                memScoped {
                    val pp = alloc<CPointerVar<kc_fmt_ctx>>().also { it.value = ctx }
                    ffkmp_fmt_free_output(pp.ptr)
                }
                throw t
            }
            return MediaSink(ctx, path)
        }
    }
}

/**
 * Shared encode loop for video + audio: pts normalization, EAGAIN-correct send/receive
 * interleaving, packet rescale to the muxer's stream time-base, flush.
 */
internal class EncoderCore(
    private val sink: MediaSink,
    private val codecCtx: CPointer<kc_codec_ctx>,
    private val stream: CPointer<kc_stream>,
    private val codecTimeBase: Rational,
    private val isAudio: Boolean,
) {
    private var closed = false
    private val streamIndex = ffkmp_stream_index(stream)
    private var lastPts = Long.MIN_VALUE
    private var basePts = Long.MIN_VALUE

    /**
     * Sample count of the audio frame that produced [lastPts], which is how far the timeline runs
     * past it. Kept in a field because the step a synthetic timestamp needs is the PREVIOUS frame's
     * duration, and by the time the next frame arrives that number is gone.
     */
    private var lastSampleCount = 0
    var framesEncoded: Long = 0; private set

    /**
     * Where this encoder is in its one-way life.
     *
     * A driven-to-the-end encoder has flushed its codec. Offering it a second flow used to look
     * like it worked: every frame was consumed and closed, the count went up, and nothing at all
     * was written, because the codec was already drained.
     */
    private var driving = false
    private var drained = false

    fun beginDrive() {
        check(!closed) { "Encoder is closed" }
        check(!drained) {
            "This encoder has already been driven to its end and cannot encode again. An encoder " +
                "is one-way: it flushes its codec when its input finishes. Add another encoder to " +
                "the sink for another stream, or collect everything into one flow."
        }
        check(!driving) {
            "This encoder is already being driven. One flow at a time: two concurrent drives " +
                "interleave their frames into one stream."
        }
        driving = true
    }

    /**
     * Marks the encoder spent. Called only after a drive REACHED the end of its input and flushed.
     *
     * Not in a `finally`: a drive that failed before the codec was ever touched, a refused header
     * above all, has drained nothing, and marking it spent would replace that real failure with a
     * misleading "already driven" on the retry. The contract suite pins exactly that.
     */
    fun endDrive() {
        drained = true
    }

    /** Releases the one-drive-at-a-time guard, on every path including failure. */
    fun releaseDrive() {
        driving = false
    }

    /**
     * Where the output timeline currently ENDS, in microseconds. 0 until the first frame.
     * The last frame's own extent is included: measuring only its start left successful work
     * finishing below 100 percent (audit KiteFFmpeg P1-14).
     */
    val outputMicros: Long
        get() = if (lastPts == Long.MIN_VALUE) 0
        else ffkmp_rescale_q(lastPts + stepPastLastPts(), codecTimeBase.num, codecTimeBase.den, 1, 1_000_000)

    /** Encode one frame, converting its pixel format to the encoder's if needed. Closes [frame]. */
    fun encode(packet: CPointer<kc_packet>, frame: Frame) {
        check(!closed) { "Encoder is closed" }
        check(!drained) { "This encoder was already drained; its codec cannot accept more frames" }
        try {
            // Inside the ownership scope, not before it. This function consumes the frame on every
            // path, and a restamp that threw used to escape before the `finally` existed, leaking
            // the frame it had promised to close.
            // The media type this encoder was built for. A video frame handed to an audio encoder
            // reached FFmpeg and was interpreted as samples, which is a wrong answer rather than a
            // refusal.
            val wanted = if (isAudio) MediaType.Audio else MediaType.Video
            require(frame.info.type == wanted) {
                "this encoder encodes $wanted and was given a ${frame.info.type} frame"
            }
            // The frame's lease spans the restamp, the conversion and the whole send/drain, so a
            // concurrent close waits at the frame's lock instead of freeing the AVFrame under the
            // encoder. Lock order is frame, then the mux lock inside the
            // drain's writes; nothing takes them the other way round.
            frame.withNative { native ->
                restampPts(frame)
                val converted = if (isAudio) null else conversionFor(native)
                if (converted != null) {
                    try {
                        sendAndDrain(packet, converted)
                    } finally {
                        ffkmp_frame_free(converted)
                    }
                } else {
                    sendAndDrain(packet, native)
                }
            }
        } finally {
            frame.close()
        }
        framesEncoded += 1
    }

    /**
     * Video frames do NOT necessarily arrive in the encoder's pixel format: a filter graph's
     * buffersink is left unconstrained on purpose (so `scale`/`overlay` can negotiate freely), and
     * an unfiltered passthrough hands over whatever the decoder produced, and 10-bit sources are the
     * common case. Converting here is what makes "no videoFilter" and "filter chain without a
     * trailing `format=`" work instead of failing with a bare EINVAL from avcodec_send_frame.
     *
     * Returns a freshly allocated frame the caller must free, or null when no conversion is needed.
     * Geometry mismatches are NOT silently rescaled; that is a caller error worth a clear message.
     */
    private fun conversionFor(native: CPointer<kc_frame>): CPointer<kc_frame>? {
        // Geometry is checked FIRST and unconditionally. Folding it into the pixel-format branch
        // would skip it whenever the formats already agree, and several encoders (mpeg4 among
        // them) happily accept a wrong-sized frame and produce a corrupt stream rather than
        // failing, so there is no backstop behind this check.
        val frameW = ffkmp_frame_width(native)
        val frameH = ffkmp_frame_height(native)
        val encW = ffkmp_codecctx_width(codecCtx)
        val encH = ffkmp_codecctx_height(codecCtx)
        if (frameW > 0 && frameH > 0 && (frameW != encW || frameH != encH)) {
            throw FFmpegException(
                FFmpegError.InvalidArgument(
                    0,
                    "Frame is ${frameW}x$frameH but the encoder was opened for ${encW}x$encH. " +
                        "KiteFFmpeg converts pixel formats for you but never silently rescales, so " +
                        "match VideoEncoderSpec.width/height to the filter graph's output, or add " +
                        "a scale= stage to the filter chain.",
                ),
            )
        }

        val want = ffkmp_codecctx_pix_fmt(codecCtx)
        val have = ffkmp_frame_format(native)
        if (want < 0 || have < 0 || want == have) return null
        return ffkmp_frame_convert_pixfmt(native, want)
            ?: throw FFmpegException(
                FFmpegError.Internal(
                    "Could not convert frame from ${pixelFormatFromAv(have).name} to " +
                        "${pixelFormatFromAv(want).name} for the encoder",
                ),
            )
    }

    /** Signal EOF to the encoder and write out everything it still buffers. */
    fun finish(packet: CPointer<kc_packet>) {
        // No-op on a spent encoder: MediaSink.close finishes every core, including ones already
        // driven to completion and closed, and that must not read as a lost tail.
        if (closed || drained) return
        sendAndDrain(packet, null)
    }

    /**
     * Rescale the frame's pts from its own time-base onto the encoder's and rebase the
     * timeline to start at zero (trimmed inputs would otherwise produce a file whose first
     * frame sits at the trim offset). The base offset is SHARED across the sink's streams
     * ([MediaSink.claimBaseMicros]) so the relative A/V offset survives the rebase. Frames
     * with no pts fall back to a synthetic timeline: one tick per frame for video, and for audio
     * each frame starting where the previous one ended ([stepPastLastPts]). Output is forced
     * strictly monotonic because both libx264 and muxers reject non-increasing pts.
     */
    private fun restampPts(frame: Frame) {
        val raw = ffkmp_frame_pts(frame.checkedNative)
        val sourceTb = frame.streamTimeBase
        val sampleCount = if (isAudio) ffkmp_frame_nb_samples(frame.checkedNative) else 0
        var pts = if (raw == FrameInfo.NOPTS) {
            if (lastPts == Long.MIN_VALUE) 0
            else lastPts + stepPastLastPts()
        } else {
            val rescaled = ffkmp_rescale_q(raw, sourceTb.num, sourceTb.den, codecTimeBase.num, codecTimeBase.den)
            if (basePts == Long.MIN_VALUE) {
                val rawMicros = ffkmp_rescale_q(raw, sourceTb.num, sourceTb.den, 1, 1_000_000)
                val baseMicros = sink.claimBaseMicros(rawMicros)
                basePts = ffkmp_rescale_q(baseMicros, 1, 1_000_000, codecTimeBase.num, codecTimeBase.den)
            }
            rescaled - basePts
        }
        // Force strictly increasing output. The step matters: the codec time-base is 1/sample_rate
        // for audio, so a one-TICK bump would collapse a whole 1024-sample AAC frame into a single
        // sample and desync everything after it. Advance by the duration of the frame already
        // written instead, which is exactly where this one begins.
        if (lastPts != Long.MIN_VALUE && pts <= lastPts) {
            pts = lastPts + stepPastLastPts()
        }
        lastPts = pts
        lastSampleCount = sampleCount
        ffkmp_frame_set_pts(frame.checkedNative, pts)
    }

    /**
     * Where the timeline stands after [lastPts], in codec time-base ticks: the duration of the
     * frame that SET lastPts, never the duration of the one now being stamped. Audio measures that
     * in samples and its codec time-base is 1/sample_rate, so 960 samples followed by 1024 must
     * land at 0 and 960, because 960 is where the first frame ended; stepping by the current
     * frame's count would put the second at 1024 and shift everything after it. Video steps one
     * tick, its time-base being the inverse of the frame rate.
     */
    private fun stepPastLastPts(): Long =
        if (isAudio) lastSampleCount.toLong().coerceAtLeast(1) else 1L

    /**
     * `avcodec_send_frame` returning EAGAIN means the output queue is full; drain packets and
     * retry the SAME frame. The previous implementation dropped the frame on EAGAIN.
     */
    private fun sendAndDrain(packet: CPointer<kc_packet>, frame: CPointer<kc_frame>?) {
        val eagain = FFErrors.EAGAIN
        val eof = FFErrors.EOF
        while (true) {
            val sendRc = ffkmp_codecctx_send_frame(codecCtx, frame)
            when {
                sendRc == 0 || sendRc == eof -> { drain(packet); return }
                sendRc == eagain -> drain(packet)  // output full → drain, then resend
                else -> throw FFmpegException(avError(sendRc))
            }
        }
    }

    private fun drain(packet: CPointer<kc_packet>) {
        val eagain = FFErrors.EAGAIN
        val eof = FFErrors.EOF
        while (true) {
            val recRc = ffkmp_codecctx_receive_packet(codecCtx, packet)
            if (recRc == eagain || recRc == eof) return
            if (recRc < 0) throw FFmpegException(avError(recRc))
            // The muxer may rewrite stream time-bases inside avformat_write_header (mp4 turns
            // 1/30 into 1/15360). The header MUST be on disk before we read the stream tb below,
            // or the first packet gets rescaled against the stale pre-header value.
            sink.ensureHeaderWritten()
            ffkmp_packet_set_stream_index(packet, streamIndex)
            // Packet pts is in the codec time-base. Convert into the muxer's stream time-base,
            // which avformat_write_header may have rewritten; read it fresh each pass.
            memScoped {
                val n = alloc<IntVar>(); val d = alloc<IntVar>()
                ffkmp_stream_time_base(stream, n.ptr, d.ptr)
                ffkmp_packet_rescale_ts(
                    packet,
                    codecTimeBase.num, codecTimeBase.den,
                    n.value, d.value,
                )
            }
            try {
                sink.writePacket(packet)
            } finally {
                ffkmp_packet_unref(packet)
            }
        }
    }

    fun ensureHeaderWritten() = sink.ensureHeaderWritten()

    fun close() {
        if (closed) return
        closed = true
        ffkmp_codecctx_free(codecCtx)
    }
}

/** Allocate a packet, run [block] with it, free it after. */
internal inline fun <T> withPacket(block: (CPointer<kc_packet>) -> T): T {
    val packet = ffkmp_packet_alloc()
        ?: throw FFmpegException(FFmpegError.Internal("av_packet_alloc returned NULL"))
    try {
        return block(packet)
    } finally {
        ffkmp_packet_free(packet)
    }
}

public actual class CopyStream internal constructor(
    private val sink: MediaSink,
    private val stream: CPointer<kc_stream>,
    private val sourceTimeBase: Rational,
    internal val sourceIndex: Int,
) {
    private val streamIndex = ffkmp_stream_index(stream)
    private var baseTs = FrameInfo.NOPTS

    /**
     * Write one demuxed packet through to the muxer: rebase timestamps so the output starts
     * at ~0 (matters for trimmed copies, since players choke on a stream starting at 95s), then
     * rescale from the source stream's time-base onto whatever the output muxer chose.
     * `av_interleaved_write_frame` takes ownership of the payload; the packet comes back blank.
     */
    internal fun writeCopyPacket(packet: CPointer<kc_packet>) {
        // Header first, because avformat_write_header may rewrite the stream time-base we read below.
        sink.ensureHeaderWritten()
        ffkmp_packet_set_stream_index(packet, streamIndex)

        // Rebase on the sink's SHARED origin (first timestamp any stream produced) so the
        // relative offset between copied and encoded streams survives. Claim with dts when
        // available (it's ≤ pts, keeping both non-negative after shift for the first stream).
        val pts = ffkmp_packet_pts(packet)
        val dts = ffkmp_packet_dts(packet)
        if (baseTs == FrameInfo.NOPTS) {
            val ref = when {
                dts != FrameInfo.NOPTS -> dts
                pts != FrameInfo.NOPTS -> pts
                else -> 0
            }
            val refMicros = ffkmp_rescale_q(ref, sourceTimeBase.num, sourceTimeBase.den, 1, 1_000_000)
            val baseMicros = sink.claimBaseMicros(refMicros)
            baseTs = ffkmp_rescale_q(baseMicros, 1, 1_000_000, sourceTimeBase.num, sourceTimeBase.den)
        }
        if (pts != FrameInfo.NOPTS) ffkmp_packet_set_pts(packet, pts - baseTs)
        if (dts != FrameInfo.NOPTS) ffkmp_packet_set_dts(packet, dts - baseTs)

        memScoped {
            val n = alloc<IntVar>(); val d = alloc<IntVar>()
            ffkmp_stream_time_base(stream, n.ptr, d.ptr)
            ffkmp_packet_rescale_ts(
                packet,
                sourceTimeBase.num, sourceTimeBase.den,
                n.value, d.value,
            )
        }
        sink.writePacket(packet)
    }
}

public actual class VideoEncoder internal constructor(
    internal val core: EncoderCore,
) : AutoCloseable {

    public actual suspend fun drive(input: Flow<Frame>, onProgress: ((framesEncoded: Long) -> Unit)?, progressEveryNFrames: Int) {
        require(progressEveryNFrames > 0) { "progressEveryNFrames must be positive" }
        core.beginDrive()
        try {
            core.ensureHeaderWritten()
            withPacket { packet ->
                input.collect { frame ->
                    core.encode(packet, frame)
                    if (onProgress != null && core.framesEncoded % progressEveryNFrames == 0L) {
                        onProgress(core.framesEncoded)
                    }
                }
                core.finish(packet)
                onProgress?.invoke(core.framesEncoded)
            }
            core.endDrive()
        } finally {
            core.releaseDrive()
        }
    }

    actual override fun close(): Unit = core.close()
}

public actual class AudioEncoder internal constructor(
    internal val core: EncoderCore,
    public actual val frameSize: Int,
    public actual val sampleFormat: SampleFormat,
    public actual val sampleRate: Int,
    public actual val channels: Int,
) : AutoCloseable {

    public actual suspend fun drive(input: Flow<Frame>) {
        core.beginDrive()
        try {
            core.ensureHeaderWritten()
            withPacket { packet ->
                input.collect { frame ->
                    requireEncodableAudio(frame, sampleFormat, sampleRate, channels)
                    core.encode(packet, frame)
                }
                core.finish(packet)
            }
            core.endDrive()
        } finally {
            core.releaseDrive()
        }
    }

    actual override fun close(): Unit = core.close()
}
