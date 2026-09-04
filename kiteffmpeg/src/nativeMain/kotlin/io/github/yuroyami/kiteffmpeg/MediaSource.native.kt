package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_codec_id_name
import ffmpeg.ffkmp_codecctx_alloc
import ffmpeg.ffkmp_codecctx_free
import ffmpeg.ffkmp_codecctx_from_par
import ffmpeg.ffkmp_codecctx_open
import ffmpeg.ffkmp_codecctx_receive_frame
import ffmpeg.ffkmp_codecctx_send_packet
import ffmpeg.ffkmp_codecpar_bit_rate
import ffmpeg.ffkmp_fmt_bit_rate
import ffmpeg.ffkmp_codecpar_field_order
import ffmpeg.ffkmp_codecpar_bit_depth
import ffmpeg.ffkmp_codecpar_ch_layout_mask
import ffmpeg.ffkmp_codecpar_chroma_location
import ffmpeg.ffkmp_codecpar_chroma_subsampling
import ffmpeg.ffkmp_codecpar_channels
import ffmpeg.ffkmp_codecpar_codec_id
import ffmpeg.ffkmp_codecpar_codec_type
import ffmpeg.ffkmp_codecpar_color_primaries
import ffmpeg.ffkmp_codecpar_color_range
import ffmpeg.ffkmp_codecpar_color_space
import ffmpeg.ffkmp_codecpar_color_transfer
import ffmpeg.ffkmp_codecpar_extradata
import ffmpeg.ffkmp_codecpar_format
import ffmpeg.ffkmp_codecpar_height
import ffmpeg.ffkmp_codecpar_level
import ffmpeg.ffkmp_codecpar_profile
import ffmpeg.ffkmp_codecpar_sample_aspect_ratio
import ffmpeg.ffkmp_codecpar_sample_rate
import ffmpeg.ffkmp_codecpar_width
import ffmpeg.ffkmp_find_decoder_by_id
import ffmpeg.ffkmp_frame_use_best_effort_ts
import ffmpeg.ffkmp_media_type_attachment
import ffmpeg.ffkmp_media_type_audio
import ffmpeg.ffkmp_media_type_data
import ffmpeg.ffkmp_media_type_subtitle
import ffmpeg.ffkmp_media_type_video
import ffmpeg.ffkmp_packet_alloc
import ffmpeg.ffkmp_packet_free
import ffmpeg.ffkmp_packet_stream_index
import ffmpeg.ffkmp_packet_unref
import ffmpeg.ffkmp_rescale_q
import ffmpeg.ffkmp_fmt_close_input
import ffmpeg.ffkmp_fmt_close_input_io
import ffmpeg.ffkmp_fmt_open_input_io
import ffmpeg.ffkmp_fmt_duration
import ffmpeg.ffkmp_fmt_find_stream_info
import ffmpeg.ffkmp_fmt_iformat_name
import ffmpeg.ffkmp_fmt_is_seekable
import ffmpeg.ffkmp_dict_free
import ffmpeg.ffkmp_dict_get
import ffmpeg.ffkmp_dict_entry_key
import ffmpeg.ffkmp_fmt_chapter_count
import ffmpeg.ffkmp_fmt_chapter_get
import ffmpeg.ffkmp_fmt_chapter_metadata
import ffmpeg.ffkmp_fmt_open_input2
import ffmpeg.ffkmp_fmt_metadata
import ffmpeg.ffkmp_fmt_nb_streams
import ffmpeg.ffkmp_fmt_open_input
import ffmpeg.ffkmp_fmt_interrupt
import ffmpeg.ffkmp_fmt_read_frame
import ffmpeg.ffkmp_fmt_seek_micros
import ffmpeg.ffkmp_fmt_start_time
import ffmpeg.ffkmp_fmt_stream
import ffmpeg.ffkmp_stream_avg_frame_rate
import ffmpeg.ffkmp_stream_codecpar
import ffmpeg.ffkmp_stream_duration_micros
import ffmpeg.ffkmp_stream_index
import ffmpeg.ffkmp_stream_metadata
import ffmpeg.ffkmp_stream_discard_all
import ffmpeg.ffkmp_stream_discard_none
import ffmpeg.ffkmp_stream_disposition
import ffmpeg.ffkmp_stream_rotation_degrees
import ffmpeg.ffkmp_stream_start_time
import ffmpeg.ffkmp_disposition_attached_pic
import ffmpeg.ffkmp_disposition_default
import ffmpeg.ffkmp_disposition_forced
import ffmpeg.ffkmp_disposition_hearing_impaired
import ffmpeg.ffkmp_disposition_visual_impaired
import ffmpeg.ffkmp_stream_time_base
import ffmpeg.ffkmp_dict_entry_key
import ffmpeg.ffkmp_dict_entry_value
import ffmpeg.ffkmp_dict_get
import ffmpeg.kc_codec_ctx
import ffmpeg.kc_codec_par
import ffmpeg.kc_dict
import ffmpeg.kc_fmt_ctx
import ffmpeg.kc_packet
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.cstr
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.Arena
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

public actual class MediaSource internal constructor(
    private val ctx: CPointer<kc_fmt_ctx>,
    public actual val streams: List<StreamInfo>,
    public actual val durationMicros: Long?,
    public actual val formatName: String,
    public actual val metadata: Map<String, String>,
    /**
     * Where the container's timeline begins, in microseconds. This is the offset between the
     * absolute timeline libavformat uses and the content-relative timeline the public API uses.
     * The expect declaration in commonMain describes both timelines in full.
     *
     * [toRelativeMicros] and [toAbsoluteMicros] are the only two places that apply this offset.
     */
    public actual val startTimeMicros: Long,
    public actual val chapters: List<Chapter> = emptyList(),
    public actual val unusedOpenOptions: List<String> = emptyList(),
    /**
     * Non-null exactly for custom-io opens (M1): close then uses ffkmp_fmt_close_input_io,
     * and this runs AFTER it to dispose the StableRef and close the caller's byte source.
     */
    private val ioCleanup: (() -> Unit)? = null,
) : AutoCloseable {

    /** Absolute timestamp [ts] (in [timeBase]) → media-relative microseconds. */
    internal fun toRelativeMicros(ts: Long, timeBase: Rational): Long =
        ffkmp_rescale_q(ts, timeBase.num, timeBase.den, 1, 1_000_000) - startTimeMicros

    /** Media-relative microseconds → the absolute microsecond timestamp the demuxer speaks. */
    internal fun toAbsoluteMicros(relativeMicros: Long): Long = relativeMicros + startTimeMicros

    /**
     * Guards the {closed, demuxing} state machine. The demuxer is a single cursor, so exactly
     * one decode pass may run at a time. Seek is rejected while a pass runs. Close is rejected
     * until the pass ends. These checks turn native crashes into [IllegalStateException]s.
     */
    private val stateLock = SynchronizedObject()
    private var closed = false
    private var demuxing = false
    private var readerActive = false

    private fun beginDemux() = synchronized(stateLock) {
        check(!closed) { "MediaSource is closed" }
        check(!demuxing) {
            "Another decode flow is already collecting on this MediaSource. The demuxer is a " +
                "single cursor. Use decodeStreams(listOf(a, b)) to read several streams in one pass."
        }
        check(!readerActive) {
            "A PacketReader is open on this MediaSource and owns the demuxer cursor. Close it " +
                "before using the batch decode API."
        }
        demuxing = true
    }

    private fun endDemux() = synchronized(stateLock) { demuxing = false }

    internal fun endPacketReader() = synchronized(stateLock) { readerActive = false }

    /** Applies a new live-reader selection and restores [previous] if any backend step fails. */
    internal fun applyPacketReaderSelection(selected: Set<Int>, previous: Set<Int>): Unit =
        synchronized(stateLock) {
            check(!closed) { "MediaSource is closed" }
            check(readerActive) { "This MediaSource has no active PacketReader" }
            try {
                setStreamDiscardSelection(selected)
            } catch (failure: Throwable) {
                try {
                    setStreamDiscardSelection(previous)
                } catch (rollback: Throwable) {
                    failure.addSuppressed(rollback)
                }
                throw failure
            }
        }

    private fun setStreamDiscardSelection(selected: Set<Int>) {
        for (info in streams) {
            val stream = ffkmp_fmt_stream(ctx, info.index.toUInt())
                ?: throw FFmpegException(
                    FFmpegError.Internal("FFmpeg lost stream index ${info.index} from this MediaSource"),
                )
            if (info.index in selected) {
                ffkmp_stream_discard_none(stream)
            } else {
                ffkmp_stream_discard_all(stream)
            }
        }
    }

    /**
     * Undoes the stream selection [openPacketReader] applied, putting every stream back on
     * AVDISCARD_DEFAULT.
     *
     * The discard flags live on the demuxer, not on the reader, so a reader that did not restore
     * them would leave the source permanently unable to read the streams it had not selected: the
     * batch decode API would return zero frames for them and report no error, because a discarded
     * packet is skipped inside libavformat and looks exactly like a stream with nothing in it.
     */
    internal fun restoreStreamDiscardDefaults(): Unit = synchronized(stateLock) {
        check(!closed) { "MediaSource is closed" }
        setStreamDiscardSelection(streams.mapTo(HashSet()) { it.index })
    }

    private val isClosed: Boolean get() = synchronized(stateLock) { closed }

    /**
     * Read once at open, because it cannot change afterwards and because reading it lazily would
     * mean touching the format context, which [close] frees.
     */
    public actual val bitrateBps: Long? = ffkmp_fmt_bit_rate(ctx).takeIf { it > 0L }
    public actual val isSeekable: Boolean = ffkmp_fmt_is_seekable(ctx) != 0

    // Attached pictures (album art) are excluded first: they are video streams by type but not
    // moving pictures, and "primary video" choosing the cover of an mp3 was the defect. A file
    // whose ONLY video is its art still returns it, because a picture beats nothing.
    public actual val primaryVideo: StreamInfo?
        get() = streams.firstOrNull { it.type == MediaType.Video && !it.disposition.attachedPicture }
            ?: streams.firstOrNull { it.type == MediaType.Video }
    public actual val primaryAudio: StreamInfo? get() = streams.firstOrNull { it.type == MediaType.Audio }

    public actual var corruptData: CorruptData = CorruptData.Skip

    public actual var corruptDataSkipped: Long = 0L
        private set

    /** The one place the batch flows decide about damaged data. */
    private val divergences = DivergenceRecorder()

    public actual val streamDivergences: List<StreamDivergence> get() = divergences.found

    private fun noteCorruptData(rc: Int) {
        if (corruptData == CorruptData.Fail) throw FFmpegException(avError(rc))
        corruptDataSkipped++
    }

    public actual fun decodedFrames(stream: StreamInfo): Flow<Frame> = decodeStreams(listOf(stream))

    public actual fun decodeStreams(streams: List<StreamInfo>): Flow<Frame> = flow {
        // Emit OWNED clones (O(1) refcount bumps): collectors may buffer or hold frames and
        // must close each one. The internal reusable landing frame never escapes this call.
        demuxRouted(decode = streams, copy = emptyList(), onFrame = { emit(it.copy()) }, onPacket = { _, _ -> })
    }

    /**
     * The one demuxer pass everything builds on. Packets for [decode] streams go through a
     * decoder and surface as [onFrame] callbacks. Packets for [copy] streams surface raw via
     * [onPacket] for stream-copy and remux, and are valid only during the callback.
     * Decoders are flushed at container EOF.
     */
    internal suspend fun demuxRouted(
        decode: List<StreamInfo>,
        copy: List<StreamInfo>,
        onFrame: suspend (Frame) -> Unit,
        onPacket: (CPointer<kc_packet>, StreamInfo) -> Unit,
    ) {
        val all = decode + copy
        require(all.isNotEmpty()) { "Need at least one stream to demux" }
        require(decode.all { it.type.isAv }) { "Only video/audio streams can be decoded" }
        require(all.distinctBy { it.index }.size == all.size) { "Duplicate stream indices" }
        all.forEach(::requireOwnStream)

        beginDemux()
        val decoders = mutableListOf<DecoderState>()
        try {
            val copyByIndex = copy.associateBy { it.index }
            decode.forEach { stream -> decoders += DecoderState.open(ctx, stream) }
            // Frame first, packet second: if the second alloc throws, the frame remains visible
            // to this try/finally and is released.
            val frame = FrameOps.acquire()
            try {
                withPacket { packet ->
                    try {
                        pump(decoders, copyByIndex, packet, frame, onFrame, onPacket)
                    } catch (_: StopDemux) {
                        // A callback decided it has everything it needs (trim end bound,
                        // frame found). Buffered decoder frames sit after the stop point,
                        // so they are deliberately not flushed. Continue to normal teardown.
                    }
                }
            } finally {
                frame.close()
            }
        } finally {
            // Preserve already-opened contexts when a later decoder fails to construct. This
            // single cleanup also owns the normal path; native decoder frees are not repeated.
            decoders.forEach { it.free() }
            endDemux()
        }
    }

    private suspend fun pump(
        decoders: List<DecoderState>,
        copyByIndex: Map<Int, StreamInfo>,
        packet: CPointer<kc_packet>,
        frame: Frame,
        onFrame: suspend (Frame) -> Unit,
        onPacket: (CPointer<kc_packet>, StreamInfo) -> Unit,
    ) {
        val decoderByIndex = decoders.associateBy { it.stream.index }
        val eof = FFErrors.EOF
        while (true) {
            // Honor cancellation between packets: copy-only pipelines (Remuxer) otherwise
            // never hit a suspension point and would run to completion after cancel.
            currentCoroutineContext().ensureActive()
            val readRc = ffkmp_fmt_read_frame(ctx, packet)
            if (readRc == eof) break
            if (readRc < 0) throw FFmpegException(avError(readRc))
            val index = ffkmp_packet_stream_index(packet)
            val decoder = decoderByIndex[index]
            val copyInfo = if (decoder == null) copyByIndex[index] else null
            try {
                when {
                    decoder != null -> sendAndDrain(decoder, packet, frame, onFrame)
                    copyInfo != null -> onPacket(packet, copyInfo)
                }
            } finally {
                ffkmp_packet_unref(packet)
            }
        }
        // EOF on the container. Flush each decoder's buffered frames.
        for (decoder in decoders) {
            sendAndDrain(decoder, null, frame, onFrame)
        }
    }

    /**
     * Correct send/receive interleaving: `avcodec_send_packet` returning EAGAIN means the
     * decoder's output queue is full. Drain the queue, then retry the same packet. The previous
     * implementation dropped the packet on EAGAIN, silently losing frames.
     */
    private suspend fun sendAndDrain(
        decoder: DecoderState,
        packet: CPointer<kc_packet>?,
        frame: Frame,
        onFrame: suspend (Frame) -> Unit,
    ) {
        val eagain = FFErrors.EAGAIN
        val eof = FFErrors.EOF
        while (true) {
            val sendRc = ffkmp_codecctx_send_packet(decoder.codecCtx, packet)
            when {
                sendRc == 0 || sendRc == eof -> { drain(decoder, frame, onFrame); return }
                sendRc == eagain -> drain(decoder, frame, onFrame)  // output full → drain, then resend
                // A packet the decoder cannot use is not a reason to abandon the file. Every seek
                // into a stream that carries its parameter sets in band (MPEG-TS above all) lands
                // before the next SPS/PPS, so the first packets after it decode to nothing:
                // "non-existing PPS 0 referenced". Aborting there would make trimming a broadcast
                // capture impossible. Skip the packet and continue, which is what ffmpeg's own
                // CLI does. If the stream is genuinely broken, no frames arrive and the callers
                // that need output report it.
                sendRc == FFmpegError.AVERROR_INVALIDDATA -> {
                    noteCorruptData(sendRc)
                    drain(decoder, frame, onFrame)
                    return
                }
                else -> throw FFmpegException(avError(sendRc))
            }
        }
    }

    private suspend fun drain(decoder: DecoderState, frame: Frame, onFrame: suspend (Frame) -> Unit) {
        val eagain = FFErrors.EAGAIN
        val eof = FFErrors.EOF
        while (true) {
            val rc = ffkmp_codecctx_receive_frame(decoder.codecCtx, frame.nativeFrame)
            if (rc == eagain || rc == eof) return
            // Same tolerance as the send side: a frame that could not be reconstructed from the
            // packets seen so far is skipped, not fatal.
            if (rc == FFmpegError.AVERROR_INVALIDDATA) {
                noteCorruptData(rc)
                return
            }
            if (rc < 0) throw FFmpegException(avError(rc))
            // Decoders fill best_effort_timestamp even for files with missing pts. Promote it
            // so everything downstream (filters, encoders) sees a usable timestamp.
            ffkmp_frame_use_best_effort_ts(frame.nativeFrame)
            // The wrap'd Frame is reference-only; the callback reads info / pixels and a consumer
            // that needs the data afterwards takes a copy(). The underlying AVFrame pointer is
            // reused for the next iteration, so the wrapper is force-closed here (idempotently):
            // a callback that retained it without closing must never see it as still open.
            val view = FrameOps.wrap(frame.nativeFrame, decoder.stream.index, decoder.stream.type, decoder.stream.timeBase)
            // First frame of this stream only; the recorder short-circuits before info is read.
            divergences.observe(decoder.stream) { view.info }
            try {
                onFrame(view)
            } finally {
                view.close()
            }
        }
    }

    public actual suspend fun seekMicros(micros: Long) {
        synchronized(stateLock) {
            check(!closed) { "MediaSource is closed" }
            // A batch decode flow owns the cursor for its whole run, so seeking under it would make
            // the frames it is emitting come from somewhere else half way through. A PacketReader is
            // different: seeking is part of how a player uses it, and the caller serialises reads and
            // seeks itself, which is stated in PacketReader's own contract.
            check(!demuxing) { "Cannot seek while a decode flow is collecting: the demuxer cursor is shared" }
            // A PacketReader owns the cursor too, and it reads through its own scratch packet. A
            // seek behind its back would move the cursor while its caller believes it is reading
            // forward from where it left off, and nothing would tell the caller the packets after
            // it belong to another position. PacketReader.seek exists so the caller can seek and
            // know it happened, at the point where it also drops the packets it had queued.
            check(!readerActive) {
                "Cannot seek this MediaSource while a PacketReader is open: the reader owns the " +
                    "demuxer cursor. Use PacketReader.seek instead."
            }
        }
        // [micros] is media-relative; av_seek_frame with stream_index = -1 wants an absolute
        // AV_TIME_BASE timestamp. On a container that starts at a nonzero time (MPEG-TS) the
        // two differ by exactly startTimeMicros.
        val rc = ffkmp_fmt_seek_micros(ctx, -1, toAbsoluteMicros(micros))
        if (rc < 0) throw FFmpegException(avError(rc))
    }

    /**
     * Seek for a pipeline that will decode forward to the exact target.
     *
     * [seekMicros] asks libavformat for the keyframe at or before the target, but that is a
     * preference, not a guarantee. Indexless containers (MPEG-TS above all) resolve a seek by
     * binary-searching byte positions, so the demuxer can return a position slightly past the
     * keyframe it aimed at. The video decoder cannot output anything until it sees the next IDR,
     * so it then silently starts a whole GOP late. Landing early costs a few discarded frames.
     * Landing late is unrecoverable, because nothing downstream can rewind.
     *
     * So aim deliberately early by [DECODE_SEEK_BACKOFF_MICROS] and let the caller's existing
     * decode-and-discard loop move forward to the frame it actually wants. Callers that
     * stream-copy instead of decoding ([Remuxer]) must not use this, because for them the extra
     * pre-roll would appear in the output as real content.
     */
    internal suspend fun seekForDecode(micros: Long) {
        seekMicros((micros - DECODE_SEEK_BACKOFF_MICROS).coerceAtLeast(0L))
    }

    /** Native codec parameters of a stream, for stream-copy setups ([MediaSink.addCopyStream]). */
    internal fun codecparOf(stream: StreamInfo): CPointer<kc_codec_par>? {
        check(!isClosed) { "MediaSource is closed" }
        // The copy path reached this with any index at all, so a StreamInfo belonging to ANOTHER
        // source with a valid index here copied this file's codec parameters under that file's
        // time base and metadata. Every other entry point already canonicalizes; the
        // one the muxer uses did not.
        requireOwnStream(stream)
        return ffkmp_fmt_stream(ctx, stream.index.toUInt())?.let { ffkmp_stream_codecpar(it) }
    }

    public actual suspend fun extractFrame(atMicros: Long, stream: StreamInfo?): Frame {
        val target = stream ?: primaryVideo
            ?: throw FFmpegException(FFmpegError.Internal("No video stream to extract a frame from"))
        // [atMicros] is media-relative; seekForDecode and toRelativeMicros both apply the
        // container's start offset, so the comparison below stays in one consistent timeline.
        // seekForDecode (not seekMicros) because the loop below decodes forward to the target and
        // a seek that overshoots would silently return a much later frame.
        seekForDecode(atMicros)
        var result: Frame? = null
        demuxRouted(
            decode = listOf(target),
            copy = emptyList(),
            onFrame = { frame ->
                val ptsMicros = if (frame.info.hasPts) {
                    toRelativeMicros(frame.info.pts, target.timeBase)
                } else Long.MAX_VALUE  // no pts → accept the first frame we see
                if (ptsMicros >= atMicros) {
                    result = frame.copy()
                    frame.close()
                    throw StopDemux()
                }
                frame.close()  // decode-discard: frames between keyframe and target
            },
            onPacket = { _, _ -> },
        )
        return result
            ?: throw FFmpegException(FFmpegError.Internal("No frame at ${atMicros}µs (beyond end of stream?)"))
    }

    /**
     * Opens a reader that hands out owned packets, one at a time, under your control.
     *
     * This is the demuxing half of a player. The batch API fuses demuxing and decoding, which is
     * right for transcoding and wrong for playback: a player needs audio and video to decode
     * independently, so a slow video decoder cannot stop the audio clock, and it needs to seek while
     * all of that runs.
     *
     * Streams outside [streams] are marked for the demuxer to discard, so their packets are skipped
     * inside libavformat instead of being read and thrown away here. On a file with ten audio tracks
     * that is most of the read work. Closing the reader puts every stream back, so a later reader or
     * decode pass over a stream this one skipped still sees its packets.
     *
     * One reader at a time, and it excludes the batch decode API for as long as it is open. Close it
     * when done.
     */
    @KiteFFmpegLowLevelApi
    public actual fun openPacketReader(streams: List<StreamInfo>): PacketReader {
        val selection = canonicalPacketSelection(this.streams, streams)

        synchronized(stateLock) {
            check(!closed) { "MediaSource is closed" }
            check(!demuxing) { "A decode flow is collecting on this MediaSource" }
            check(!readerActive) { "A PacketReader is already open on this MediaSource" }
            readerActive = true
        }
        try {
            applyPacketReaderSelection(
                selected = selection.keys,
                previous = this.streams.mapTo(HashSet()) { it.index },
            )
            return PacketReader(this, ctx, selection)
        } catch (t: Throwable) {
            // Same reason close() restores them: a half-opened reader must not leave the demuxer
            // skipping streams nobody selected.
            try {
                restoreStreamDiscardDefaults()
            } catch (cleanup: Throwable) {
                t.addSuppressed(cleanup)
            } finally {
                endPacketReader()
            }
            throw t
        }
    }

    /**
     * Opens one decoder for one stream, for you to drive.
     *
     * Independent of every other decoder and of the reader, which is the point: this is what lets a
     * player decode audio and video at their own rates.
     *
     * @param threadCount 0 lets libavcodec choose, which is usually the core count. Video gets
     *        frame-level threading, without which real-time 4K is not possible.
     * @param lowDelay stops the decoder holding frames back for reordering. Right for audio in a
     *        player, wrong for video, where it costs decode efficiency.
    */
    @KiteFFmpegLowLevelApi
    @Throws(FFmpegException::class)
    public actual fun openDecoder(
        stream: StreamInfo,
        threadCount: Int,
        lowDelay: Boolean,
        decoder: CodecId?,
        options: io.github.yuroyami.kiteffmpeg.dsl.DecoderOptions?,
        hardware: HardwareAccel?,
        corruptData: CorruptData,
    ): StreamDecoder {
        check(!isClosed) { "MediaSource is closed" }
        require(stream.type.isAv) { "Only video and audio streams can be decoded, got ${stream.type}" }
        requireOwnStream(stream)
        return StreamDecoder.open(ctx, stream, threadCount, lowDelay, decoder, options, hardware, corruptData)
    }

    /**
     * Canonicalizes a caller-supplied [StreamInfo] against this source's own table. StreamInfo is
     * a public data class and therefore forgeable: stream zero of another source has a valid index
     * here but foreign timing and type metadata, and accepting it would interpret this source's
     * packets with another file's time base (audit KiteFFmpeg P1-8). Structural equality against
     * the entry this source itself published is the check.
     */
    private fun requireOwnStream(supplied: StreamInfo) {
        val own = streams.firstOrNull { it.index == supplied.index }
        require(own == supplied) {
            "StreamInfo(index=${supplied.index}) does not belong to this MediaSource. Pass entries " +
                "from THIS source's streams list; stream identity is source-bound."
        }
    }

    public actual fun interrupt() {
        /* Deliberately NOT under stateLock: the whole point is reaching a context another thread
           is blocked on. The contract forbids calling this concurrently with or after close, so
           the pointer is live for the duration of the call. */
        ffkmp_fmt_interrupt(ctx)
    }

    actual override fun close() {
        synchronized(stateLock) {
            if (closed) return
            check(!readerActive) {
                "Cannot close MediaSource while a PacketReader is open. Close the reader first: " +
                    "freeing the demuxer under it would leave a dangling cursor."
            }
            check(!demuxing) {
                "Cannot close MediaSource while a decode flow is collecting. Cancel or finish " +
                    "collection first (freeing the demuxer under an active decode would crash)."
            }
            closed = true
        }
        memScoped {
            val pp = alloc<CPointerVar<kc_fmt_ctx>>()
            pp.value = ctx
            if (ioCleanup != null) ffkmp_fmt_close_input_io(pp.ptr) else ffkmp_fmt_close_input(pp.ptr)
        }
        ioCleanup?.invoke()
    }

    public actual companion object {
        @Throws(FFmpegException::class)
        public actual fun open(path: String): MediaSource {
            // The FFmpeg identity gate. First statement, before anything
            // allocates: an incompatible runtime is rejected here rather than corrupting memory
            // through a struct field offset that moved.
            requireCompatibleFFmpeg()
            return openMediaSource(path)
        }

        @Throws(FFmpegException::class)
        public actual fun open(path: String, options: Map<String, String>): MediaSource {
            requireCompatibleFFmpeg()
            return openMediaSource(path, options)
        }

        @Throws(FFmpegException::class)
        public actual fun open(io: MediaByteSource, options: Map<String, String>): MediaSource {
            requireCompatibleFFmpeg()
            return openMediaSourceIo(io, options)
        }

        /**
         * How far before the requested time [seekForDecode] aims. Must comfortably exceed one GOP:
         * broadcast MPEG-TS typically uses 0.5–2s, and file-based content rarely exceeds 10s. The
         * only cost of overshooting backwards is decoding frames that are then discarded.
         */
        private const val DECODE_SEEK_BACKOFF_MICROS = 5_000_000L
    }
}

/**
 * Thrown by a demux callback to stop the pump early (trim end bound reached, target frame
 * found). Caught inside [MediaSource.demuxRouted]; never escapes to user code.
 */
internal class StopDemux : Throwable()

/** One opened decoder bound to one input stream. */
private class DecoderState(
    val stream: StreamInfo,
    val codecCtx: CPointer<kc_codec_ctx>,
) {
    fun free() = ffkmp_codecctx_free(codecCtx)

    companion object {
        fun open(ctx: CPointer<kc_fmt_ctx>, stream: StreamInfo): DecoderState {
            val streamPtr = ffkmp_fmt_stream(ctx, stream.index.toUInt())
                ?: throw FFmpegException(FFmpegError.Internal("Stream ${stream.index} disappeared"))
            val codecpar = ffkmp_stream_codecpar(streamPtr)
                ?: throw FFmpegException(FFmpegError.Internal("Stream ${stream.index} missing codecpar"))
            val codecId = ffkmp_codecpar_codec_id(codecpar)
            val codec = ffkmp_find_decoder_by_id(codecId)
                ?: throw FFmpegException(
                    FFmpegError.DecoderNotFound(0, decoderNotFoundMessage(stream.codec, requested = null)),
                )

            val codecCtx = ffkmp_codecctx_alloc(codec)
                ?: throw FFmpegException(FFmpegError.Internal("avcodec_alloc_context3 returned NULL"))
            try {
                check0(ffkmp_codecctx_from_par(codecCtx, codecpar), "avcodec_parameters_to_context")
                check0(ffkmp_codecctx_open(codecCtx, codec), "avcodec_open2")
            } catch (t: Throwable) {
                ffkmp_codecctx_free(codecCtx)
                throw t
            }
            return DecoderState(stream, codecCtx)
        }
    }
}

internal fun openMediaSource(path: String, options: Map<String, String> = emptyMap()): MediaSource {
    val arena = Arena()
    val ctxVar = arena.allocPointerTo<kc_fmt_ctx>()
    var unusedKeys: List<String> = emptyList()
    val openRc = if (options.isEmpty()) {
        ffkmp_fmt_open_input(ctxVar.ptr, path)
    } else {
        // KD-4: pairs applied between allocation and open; the unconsumed remainder comes back
        // as an OWNED dictionary this block walks and frees before anything can throw past it.
        memScoped {
            val keys = allocArray<CPointerVar<ByteVar>>(options.size)
            val values = allocArray<CPointerVar<ByteVar>>(options.size)
            options.entries.forEachIndexed { index, (key, value) ->
                keys[index] = key.cstr.ptr
                values[index] = value.cstr.ptr
            }
            val unusedVar = allocPointerTo<ffmpeg.kc_dict>()
            val rc = ffkmp_fmt_open_input2(ctxVar.ptr, path, keys, values, options.size, unusedVar.ptr)
            if (rc >= 0) {
                val dict = unusedVar.value
                if (dict != null) {
                    unusedKeys = buildList {
                        var entry = ffkmp_dict_get(dict, null)
                        while (entry != null) {
                            ffkmp_dict_entry_key(entry)?.toKString()?.let(::add)
                            entry = ffkmp_dict_get(dict, entry)
                        }
                    }
                    ffkmp_dict_free(unusedVar.ptr)
                }
            }
            rc
        }
    }
    if (openRc < 0) { arena.clear(); throw FFmpegException(avError(openRc)) }
    val ctx = ctxVar.value
        ?: run { arena.clear(); throw FFmpegException(FFmpegError.Internal("open_input returned NULL")) }
    arena.clear()  // ctxVar was just used to read out the ctx; the ctx pointer is now standalone.

    return assembleMediaSource(ctx, unusedKeys, ioCleanup = null)
}

/** Everything between a successfully opened ctx and a constructed MediaSource, shared by the
 *  path open and the custom-io open. On failure the ctx is closed with the RIGHT close. */
private fun assembleMediaSource(
    ctx: CPointer<kc_fmt_ctx>,
    unusedKeys: List<String>,
    ioCleanup: (() -> Unit)?,
): MediaSource {
    /** Closes the container and the caller's byte source, in that order. */
    fun unwind() {
        memScoped {
            val pp = alloc<CPointerVar<kc_fmt_ctx>>().also { it.value = ctx }
            if (ioCleanup != null) ffkmp_fmt_close_input_io(pp.ptr) else ffkmp_fmt_close_input(pp.ptr)
        }
        ioCleanup?.invoke()
    }

    val infoRc = ffkmp_fmt_find_stream_info(ctx)
    if (infoRc < 0) {
        unwind()
        throw FFmpegException(avError(infoRc))
    }

    // Everything from here to the constructor can throw, and until it was wrapped a probe that had
    // ALREADY succeeded left the format context and the caller's byte source stranded when reading
    // the streams, the metadata or the chapters failed.
    val streams: List<StreamInfo>
    val durationFromHeader: Long?
    val formatName: String
    val metadata: Map<String, String>
    val startTime: Long
    val chapters: List<Chapter>
    try {
        streams = buildStreams(ctx)
        durationFromHeader = ffkmp_fmt_duration(ctx).takeIf { it > 0L }
        formatName = ffkmp_fmt_iformat_name(ctx)?.toKString() ?: "unknown"
        metadata = readMetadata(ffkmp_fmt_metadata(ctx))
        startTime = ffkmp_fmt_start_time(ctx)
        chapters = readChapters(ctx)
    } catch (failure: Throwable) {
        unwind()
        throw failure
    }

    return MediaSource(
        ctx, streams, durationFromHeader, formatName, metadata, startTime,
        chapters = chapters,
        unusedOpenOptions = unusedKeys,
        ioCleanup = ioCleanup,
    )
}

/** The per-open state the AVIO trampolines reach through the C bridge's opaque. */
private class ByteSourceState(val io: MediaByteSource) {
    var position: Long = 0
    val scratch = ByteArray(64 * 1024)
    var failure: Throwable? = null
}

/* The C-callable trampolines. Non-capturing by staticCFunction's rule: all state arrives
 * through the opaque StableRef. Contract with the C side (kitecodec_helpers.h): read returns
 * bytes read > 0, KC_IO_EOF (-1) at end, KC_IO_ERR (-2) on failure; seek returns the new
 * absolute position or KC_IO_ERR. Exceptions must NEVER unwind into C. */
private val byteSourceRead = staticCFunction { opaque: COpaquePointer?, buf: CPointer<UByteVar>?, len: Int ->
    val state = opaque!!.asStableRef<ByteSourceState>().get()
    try {
        val want = minOf(len, state.scratch.size)
        val r = state.io.read(state.scratch, 0, want)
        when {
            r > 0 -> {
                // Element copy, not memcpy: posix memcpy's size_t is 32-bit on androidNativeArm32
                // and 64-bit everywhere else, and the shared-native metadata compile refuses a
                // commonized declaration whose widths differ (hit on the first full 11-target
                // publish). A counted loop has no width to disagree about, and at media
                // bitrates its cost is noise next to the decode this feeds.
                val dst = buf!!
                val src = state.scratch
                var i = 0
                while (i < r) {
                    dst[i] = src[i].toUByte()
                    i++
                }
                state.position += r
                r
            }
            r < 0 -> -1
            else -> -2  // 0 breaks the documented block-or-end contract
        }
    } catch (failure: Throwable) {
        state.failure = failure
        -2
    }
}

private val byteSourceSeek = staticCFunction { opaque: COpaquePointer?, offset: Long, whence: Int ->
    val state = opaque!!.asStableRef<ByteSourceState>().get()
    try {
        val target = when (whence) {
            0 -> offset                                       // SEEK_SET
            1 -> state.position + offset                      // SEEK_CUR
            2 -> (state.io.size ?: -1L).let { if (it < 0) return@staticCFunction -2L else it + offset }
            else -> return@staticCFunction -2L
        }
        if (target < 0) return@staticCFunction -2L
        state.io.seek(target)
        state.position = target
        target
    } catch (failure: Throwable) {
        state.failure = failure
        -2L
    }
}

internal fun openMediaSourceIo(io: MediaByteSource, options: Map<String, String> = emptyMap()): MediaSource {
    val state = ByteSourceState(io)
    val stableRef = StableRef.create(state)
    val cleanup: () -> Unit = {
        stableRef.dispose()
        io.close()
    }
    var unusedKeys: List<String> = emptyList()
    val ctx: CPointer<kc_fmt_ctx> = memScoped {
        val ctxVar = allocPointerTo<kc_fmt_ctx>()
        val n = options.size
        val keys = allocArray<CPointerVar<ByteVar>>(n)
        val values = allocArray<CPointerVar<ByteVar>>(n)
        options.entries.forEachIndexed { index, (key, value) ->
            keys[index] = key.cstr.ptr
            values[index] = value.cstr.ptr
        }
        val unusedVar = allocPointerTo<ffmpeg.kc_dict>()
        val rc = ffkmp_fmt_open_input_io(
            ctxVar.ptr,
            stableRef.asCPointer(),
            byteSourceRead,
            if (io.seekable) byteSourceSeek else null,
            io.size ?: -1L,
            keys, values, n, unusedVar.ptr,
        )
        if (rc < 0) {
            // The FULL cleanup, not just the reference: ownership of the byte source transfers to
            // this function the moment the adapter is built, so an open that fails still owes the
            // caller a close. Disposing the StableRef alone left the source open for ever
            // (audit P1-01).
            cleanup()
            // The byte source's own exception is the truer diagnosis than FFmpeg's EIO shell.
            state.failure?.let { throw FFmpegException(FFmpegError.Internal("custom io failed: ${it.message}")) }
            throw FFmpegException(avError(rc))
        }
        val dict = unusedVar.value
        if (dict != null) {
            unusedKeys = buildList {
                var entry = ffkmp_dict_get(dict, null)
                while (entry != null) {
                    ffkmp_dict_entry_key(entry)?.toKString()?.let(::add)
                    entry = ffkmp_dict_get(dict, entry)
                }
            }
            ffkmp_dict_free(unusedVar.ptr)
        }
        ctxVar.value ?: run {
            cleanup()
            throw FFmpegException(FFmpegError.Internal("open_input_io returned NULL"))
        }
    }
    return assembleMediaSource(ctx, unusedKeys, ioCleanup = cleanup)
}

/** KD-5: the chapter table, bounds already in microseconds from the C side. */
private fun readChapters(ctx: CPointer<kc_fmt_ctx>): List<Chapter> {
    val count = ffkmp_fmt_chapter_count(ctx)
    if (count <= 0) return emptyList()
    return memScoped {
        val id = alloc<LongVar>()
        val start = alloc<LongVar>()
        val end = alloc<LongVar>()
        buildList {
            for (index in 0 until count) {
                if (ffkmp_fmt_chapter_get(ctx, index, id.ptr, start.ptr, end.ptr) < 0) continue
                val metadata = readMetadata(ffkmp_fmt_chapter_metadata(ctx, index))
                add(Chapter(id.value, start.value, end.value, metadata))
            }
        }
    }
}

private fun buildStreams(ctx: CPointer<kc_fmt_ctx>): List<StreamInfo> {
    val nb = ffkmp_fmt_nb_streams(ctx).toInt()
    val out = ArrayList<StreamInfo>(nb)
    for (i in 0 until nb) {
        val s = ffkmp_fmt_stream(ctx, i.toUInt()) ?: continue
        val par = ffkmp_stream_codecpar(s) ?: continue

        val typeRaw = ffkmp_codecpar_codec_type(par)
        val type = when (typeRaw) {
            ffkmp_media_type_video() -> MediaType.Video
            ffkmp_media_type_audio() -> MediaType.Audio
            ffkmp_media_type_subtitle() -> MediaType.Subtitle
            ffkmp_media_type_data() -> MediaType.Data
            ffkmp_media_type_attachment() -> MediaType.Attachment
            else -> MediaType.Unknown
        }

        val codecId = ffkmp_codecpar_codec_id(par)
        // The CODEC's canonical name, not whichever decoder this build registers for it: an AV1
        // stream must read "av1" whether or not libdav1d is compiled in, and a subtitle or
        // attachment stream with no decoder at all must still name its codec.
        val codecName = ffkmp_codec_id_name(codecId)?.toKString() ?: "codec_$codecId"

        val timeBase = readRational { num, den -> ffkmp_stream_time_base(s, num, den) }
        val avgFr    = readRational { num, den -> ffkmp_stream_avg_frame_rate(s, num, den) }
        val sar      = readRational { num, den -> ffkmp_codecpar_sample_aspect_ratio(par, num, den) }
            .let { if (it.num == 0) Rational(1, 1) else it }

        out += StreamInfo(
            index = ffkmp_stream_index(s),
            type = type,
            codec = CodecId(codecName),
            timeBase = timeBase,
            durationMicros = ffkmp_stream_duration_micros(s).takeIf { it > 0L },
            bitrateBps = ffkmp_codecpar_bit_rate(par).takeIf { it > 0L },
            video = if (type == MediaType.Video) VideoStreamInfo(
                width = ffkmp_codecpar_width(par),
                height = ffkmp_codecpar_height(par),
                pixelFormat = pixelFormatFromAv(ffkmp_codecpar_format(par)),
                frameRate = avgFr,
                sampleAspectRatio = sar,
                color = readCodecParameterColor(par),
                vp9 = if (codecName == "vp9") readVp9CodecInfo(par) else null,
                fieldOrder = FieldOrder.ofCode(ffkmp_codecpar_field_order(par)),
            ) else null,
            audio = if (type == MediaType.Audio) AudioStreamInfo(
                sampleRate = ffkmp_codecpar_sample_rate(par),
                channels = ffkmp_codecpar_channels(par),
                sampleFormat = sampleFormatFromAv(ffkmp_codecpar_format(par)),
                // 0 from the helper means there is no mask to report, which is what null says here.
                channelLayoutMask = ffkmp_codecpar_ch_layout_mask(par).takeIf { it != 0L },
            ) else null,
            metadata = readMetadata(ffkmp_stream_metadata(s)),
            disposition = readDisposition(ffkmp_stream_disposition(s)),
            rotationDegrees = ffkmp_stream_rotation_degrees(s),
            startTimeMicros = ffkmp_stream_start_time(s)
                .takeIf { it != Long.MIN_VALUE }
                ?.let { ffkmp_rescale_q(it, timeBase.num, timeBase.den, 1, 1_000_000) }
                ?: 0L,
            codecExtradata = readCodecExtradata(par),
        )
    }
    return out
}

private fun readCodecParameterColor(parameters: CPointer<kc_codec_par>): ColorInfo {
    val range = ffkmp_codecpar_color_range(parameters)
    val declared = ColorInfo(
        matrix = ColorMatrix.fromAv(ffkmp_codecpar_color_space(parameters)),
        primaries = ColorPrimaries.fromAv(ffkmp_codecpar_color_primaries(parameters)),
        transfer = ColorTransfer.fromAv(ffkmp_codecpar_color_transfer(parameters)),
        fullRange = range == 2,
        chromaLocation = ChromaLocation.fromAv(ffkmp_codecpar_chroma_location(parameters)),
        rangeSpecified = range == 1 || range == 2,
    )
    return resolveDeclaredColor(declared, ffkmp_codecpar_height(parameters))
}

private fun readVp9CodecInfo(parameters: CPointer<kc_codec_par>): Vp9CodecInfo = Vp9CodecInfo(
    profile = Vp9Profile.fromNumber(ffkmp_codecpar_profile(parameters)),
    level = Vp9Level.fromCode(ffkmp_codecpar_level(parameters)),
    bitDepth = Vp9BitDepth.fromBits(ffkmp_codecpar_bit_depth(parameters)),
    chromaSubsampling = Vp9ChromaSubsampling.fromCode(ffkmp_codecpar_chroma_subsampling(parameters)),
)

private fun readCodecExtradata(parameters: CPointer<kc_codec_par>): ByteArray? {
    val size = ffkmp_codecpar_extradata(parameters, null, 0)
    check0(size, "codec parameter extradata size")
    if (size == 0) return null

    val bytes = ByteArray(size)
    val copied = bytes.usePinned { pinned ->
        ffkmp_codecpar_extradata(parameters, pinned.addressOf(0).reinterpret(), size)
    }
    check0(copied, "codec parameter extradata copy")
    check(copied == size) { "Codec extradata changed while stream metadata was being copied" }
    return bytes
}

private fun readDisposition(flags: Int): Disposition = Disposition(
    default = flags and ffkmp_disposition_default() != 0,
    forced = flags and ffkmp_disposition_forced() != 0,
    hearingImpaired = flags and ffkmp_disposition_hearing_impaired() != 0,
    visualImpaired = flags and ffkmp_disposition_visual_impaired() != 0,
    attachedPicture = flags and ffkmp_disposition_attached_pic() != 0,
)

/** Call [block] with two IntVar out-params, return the resulting [Rational]. */
private inline fun readRational(block: (CPointer<IntVar>, CPointer<IntVar>) -> Unit): Rational = memScoped {
    val n = alloc<IntVar>(); val d = alloc<IntVar>()
    block(n.ptr, d.ptr)
    Rational(n.value, d.value.takeIf { it != 0 } ?: 1)
}

private fun readMetadata(dict: CPointer<kc_dict>?): Map<String, String> {
    if (dict == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    var entry = ffkmp_dict_get(dict, null)
    while (entry != null) {
        val k = ffkmp_dict_entry_key(entry)?.toKString()
        val v = ffkmp_dict_entry_value(entry)?.toKString()
        if (k != null) out[k] = v ?: ""
        entry = ffkmp_dict_get(dict, entry)
    }
    return out
}
