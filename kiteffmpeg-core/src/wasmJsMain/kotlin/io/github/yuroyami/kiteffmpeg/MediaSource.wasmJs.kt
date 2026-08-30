package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.DecoderOptions
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_alloc
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_disposition_attached_pic
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_disposition_default
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_disposition_forced
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_disposition_hearing_impaired
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_disposition_visual_impaired
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_from_par
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_open
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_set_low_delay
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_bit_rate
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_channels
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_codec_id
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_codec_type
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_format
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_height
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_sample_aspect_ratio
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_sample_rate
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_width
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codec_id_name
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codec_id
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_set_opt
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_set_threads
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_dict_entry_key
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_dict_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_dict_get
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_find_decoder_by_id
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_find_decoder_by_name
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_close_input_io
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_interrupt
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_duration
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_find_stream_info
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_iformat_name
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_is_seekable
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_nb_streams
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_start_time
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_stream
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_media_type_audio
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_media_type_subtitle
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_media_type_video
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_stream_avg_frame_rate
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_stream_codecpar
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_stream_discard_all
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_stream_discard_none
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_stream_disposition
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_stream_duration_micros
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_stream_index
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_stream_rotation_degrees
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_stream_time_base
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_ch_layout_mask
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_chroma_location
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_color_primaries
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_color_range
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_color_space
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_color_transfer
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecpar_extradata
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_dict_entry_value
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_chapter_count
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_chapter_get
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_chapter_metadata
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_metadata
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_media_type_attachment
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_media_type_data
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_rescale_q
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_stream_metadata
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_stream_start_time
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * An open container, over the codec module (17.14 X-07).
 *
 * Opened from a [MediaByteSource] only. There is no filesystem in a browser, so the `open(path)`
 * overloads refuse rather than pretending: whatever the caller has is already bytes.
 */
public actual class MediaSource internal constructor(
    private val contextSlot: Int,
    private val context: Int,
    private val bridge: WebIoBridge,
    private val unused: List<String>,
) : AutoCloseable {

    private var closed = false

    /** Cleared on close, checked by every reader and decoder that borrows this container. */
    private val lifetime = SourceLifetime()

    private fun alive(): Int =
        if (!closed) context else throw FFmpegException(FFmpegError.Internal("this media source is closed"))

    public actual val streams: List<StreamInfo> by lazy { readStreams(requireModule(), alive()) }

    public actual val durationMicros: Long?
        get() = ffkmp_fmt_duration(requireModule(), alive()).takeIf { it > 0 }

    public actual val formatName: String
        get() = utf8OrNull(requireModule(), ffkmp_fmt_iformat_name(requireModule(), alive())).orEmpty()

    public actual val metadata: Map<String, String>
        get() = requireModule().let { m -> readMetadata(m, ffkmp_fmt_metadata(m, alive())) }

    public actual val chapters: List<Chapter>
        get() {
            val m = requireModule()
            val context = alive()
            val count = ffkmp_fmt_chapter_count(m, context)
            if (count <= 0) return emptyList()
            // Three int64 out-slots in ONE allocation: the C side fills id, start and end together,
            // and three separate mallocs through a JS boundary would cost three crossings to say
            // the same thing.
            val slots = wasmAlloc(m, 24)
            try {
                return buildList {
                    for (index in 0 until count) {
                        if (ffkmp_fmt_chapter_get(m, context, index, slots, slots + 8, slots + 16) < 0) continue
                        add(
                            Chapter(
                                id = readInt64(m, slots),
                                startMicros = readInt64(m, slots + 8),
                                endMicros = readInt64(m, slots + 16),
                                metadata = readMetadata(m, ffkmp_fmt_chapter_metadata(m, context, index)),
                            ),
                        )
                    }
                }
            } finally {
                wasmFree(m, slots)
            }
        }

    /**
     * The keys FFmpeg did not consume, which is a real answer now.
     *
     * It answered `emptyList()` before, while `open` was also discarding every option unread. That
     * pair is the worst of both: a caller probing for option support reads "none unused" as "all
     * supported". The options are forwarded now and this reports what came back.
     */
    public actual val unusedOpenOptions: List<String> get() = unused

    public actual val startTimeMicros: Long
        get() = ffkmp_fmt_start_time(requireModule(), alive()).takeIf { it != Long.MIN_VALUE } ?: 0L

    public actual val isSeekable: Boolean
        get() = ffkmp_fmt_is_seekable(requireModule(), alive()) != 0

    public actual val primaryVideo: StreamInfo? get() = streams.firstOrNull { it.type == MediaType.Video }
    public actual val primaryAudio: StreamInfo? get() = streams.firstOrNull { it.type == MediaType.Audio }

    /**
     * True while a packet reader holds the demux cursor.
     *
     * A container has ONE read position. Two readers, or a reader and a batch decode flow, moving
     * it at the same time interleave each other's packets and each other's seeks, and the caller
     * sees a stream that jumps about for no reason. The JVM and Native backends have always
     * refused the second one; this backend let them all through.
     */
    private var readerActive = false

    internal fun beginPacketReader() {
        check(!readerActive) {
            "This MediaSource already has an open packet reader. A container has one demux cursor, " +
                "so close the first reader before opening another, or read several streams through " +
                "one reader with openPacketReader(streams)."
        }
        readerActive = true
    }

    internal fun endPacketReader() {
        readerActive = false
    }

    /** Applies all FFmpeg discard flags, rolling back to [previous] before surfacing a failure. */
    internal fun applyPacketReaderSelection(selected: Set<Int>, previous: Set<Int>) {
        alive()
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
        val m = requireModule()
        val live = alive()
        streams.forEach { info ->
            val stream = ffkmp_fmt_stream(m, live, info.index)
            if (stream == 0) {
                throw FFmpegException(
                    FFmpegError.Internal("FFmpeg lost stream index ${info.index} from this MediaSource"),
                )
            }
            if (info.index in selected) {
                ffkmp_stream_discard_none(m, stream)
            } else {
                ffkmp_stream_discard_all(m, stream)
            }
        }
    }

    /** Restores defaults before returning the demux-cursor lease. Safe after source close. */
    internal fun closePacketReader() {
        try {
            if (!closed) setStreamDiscardSelection(streams.mapTo(HashSet()) { it.index })
        } finally {
            endPacketReader()
        }
    }

    public actual var corruptData: CorruptData = CorruptData.Skip

    /**
     * Summed from the decoders the batch flows built, because this backend's flows drive real
     * [StreamDecoder] instances rather than a private decode loop.
     */
    public actual var corruptDataSkipped: Long = 0L
        private set

    public actual fun decodedFrames(stream: StreamInfo): Flow<Frame> = decodeStreams(listOf(stream))

    public actual fun decodeStreams(streams: List<StreamInfo>): Flow<Frame> = flow {
        // Staged, because `associate` built them all and dropped the ones it had already built if
        // a later open threw, leaking one codec context each.
        val decoders = LinkedHashMap<Int, StreamDecoder>()
        // The counter accumulates for this source's LIFETIME, as it does on JVM and Native. It used
        // to be zeroed here and written only in the finally below, so a caller reading it mid-flow
        // always saw zero and a second pass erased the first one's total, which is not what the
        // commonMain KDoc promises.
        val skippedBefore = corruptDataSkipped
        var reader: PacketReader? = null
        try {
            // One try owning both, because openPacketReader takes the cursor lease and can throw.
            // It used to sit BETWEEN the catch that unwound the decoders and the try that owns
            // their cleanup, so a throw there leaked every codec context just built, which is the
            // exact defect P0-05 was opened to fix.
            streams.forEach { decoders[it.index] = openDecoder(it, corruptData = corruptData) }
            val live = openPacketReader(streams).also { reader = it }
            while (true) {
                val packet = live.read()
                if (packet == null) {
                    // Drain every decoder before finishing: frames can still be queued inside them.
                    decoders.values.forEach { decoder ->
                        // The drain signal is an input like any other and can be refused. Sending it
                        // once and assuming it landed ended the stream while the decoder was still
                        // full, which on a buffered codec is its whole tail.
                        while (!decoder.send(null)) {
                            while (true) emit(decoder.receive() ?: break)
                        }
                        while (true) emit(decoder.receive() ?: break)
                    }
                    corruptDataSkipped = skippedBefore + decoders.values.sumOf { it.corruptDataSkipped }
                    return@flow
                }
                packet.use { p ->
                    val decoder = decoders[p.streamIndex]
                    if (decoder != null) {
                        // Send, drain, retry the SAME packet: false means the decoder did not take
                        // it, and the old code dropped it instead of offering it again. Every
                        // B-frame codec loses frames that way. FFmpeg guarantees a send is accepted
                        // once its output has been drained, which is what ends this loop.
                        while (!decoder.send(p)) {
                            while (true) emit(decoder.receive() ?: break)
                        }
                        while (true) emit(decoder.receive() ?: break)
                    }
                }
                // Live, per packet: a caller watching a long decode learns it is losing data while
                // it can still act on that, not only once the flow has finished.
                corruptDataSkipped = skippedBefore + decoders.values.sumOf { it.corruptDataSkipped }
            }
        } finally {
            // Read the decoders' counts BEFORE closing them: closed decoders answer nothing, and
            // this total is the only record that the decode was short.
            corruptDataSkipped = skippedBefore + decoders.values.sumOf { it.corruptDataSkipped }
            reader?.close()
            decoders.values.forEach { runCatching { it.close() } }
        }
    }

    public actual suspend fun seekMicros(micros: Long) {
        val anchor = streams.firstOrNull()
            ?: throw FFmpegException(FFmpegError.InvalidArgument(0, "cannot seek media with no streams"))
        openPacketReader(listOf(anchor)).use { it.seek(micros, SeekDirection.Backward, null) }
    }

    public actual suspend fun extractFrame(atMicros: Long, stream: StreamInfo?): Frame {
        val target = stream ?: primaryVideo
            ?: throw FFmpegException(FFmpegError.Internal("this media has no video stream to extract from"))
        // Back off to a safe earlier point and decode forward. A seek lands on a keyframe at or
        // before the target, so the first frame that comes out of it is the KEYFRAME, not the frame
        // asked for: returning it answered a sparse-keyframe file with a picture seconds early
        // (audit P0-04). Backward, so the target is never overshot before the walk begins.
        val landing = (atMicros - DECODE_SEEK_BACKOFF_MICROS).coerceAtLeast(0L)
        openPacketReader(listOf(target)).use { it.seek(landing, SeekDirection.Backward, null) }
        // Both opened INSIDE the try. They used to sit outside it, so a throwing openDecoder left
        // the reader open and readerActive true for ever: that MediaSource could never open another
        // reader again, and the leak was permanent for the object's life.
        var reader: PacketReader? = null
        var decoder: StreamDecoder? = null
        try {
            val liveReader = openPacketReader(listOf(target)).also { reader = it }
            val liveDecoder = openDecoder(target).also { decoder = it }
            // The first frame whose own timestamp reaches the target. An untimed frame cannot be
            // compared, so it is passed over rather than guessed at.
            fun Frame.reachesTarget(): Boolean {
                val pts = info.takeIf { it.hasPts }?.pts ?: return false
                return rescaleQ(pts, target.timeBase, Rational.Tb_us) - startTimeMicros >= atMicros
            }
            while (true) {
                val packet = liveReader.read() ?: break
                packet.use { p ->
                    while (!liveDecoder.send(p)) {
                        while (true) {
                            val frame = liveDecoder.receive() ?: break
                            if (frame.reachesTarget()) return frame
                            frame.close()
                        }
                    }
                    while (true) {
                        val frame = liveDecoder.receive() ?: break
                        if (frame.reachesTarget()) return frame
                        frame.close()
                    }
                }
            }
            while (!liveDecoder.send(null)) {
                while (true) {
                    val frame = liveDecoder.receive() ?: break
                    if (frame.reachesTarget()) return frame
                    frame.close()
                }
            }
            while (true) {
                val frame = liveDecoder.receive() ?: break
                if (frame.reachesTarget()) return frame
                frame.close()
            }
            throw FFmpegException(
                FFmpegError.Internal("no frame at ${atMicros}us (beyond the end of the stream?)"),
            )
        } finally {
            reader?.close()
            decoder?.close()
        }
    }

    public actual fun openPacketReader(streams: List<StreamInfo>): PacketReader {
        val context = alive()
        val selection = canonicalPacketSelection(this.streams, streams)
        beginPacketReader()
        try {
            applyPacketReaderSelection(
                selected = selection.keys,
                previous = this.streams.mapTo(HashSet()) { it.index },
            )
            return PacketReader(
                source = this,
                context = context,
                timeBases = this.streams.associate { it.index to it.timeBase },
                wanted = selection.keys,
                startTimeMicros = startTimeMicros,
                lifetime = lifetime,
                onClosed = ::closePacketReader,
            )
        } catch (failure: Throwable) {
            // The lease was taken and the reader that would return it never existed.
            try {
                if (!closed) setStreamDiscardSelection(this.streams.mapTo(HashSet()) { it.index })
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            } finally {
                endPacketReader()
            }
            throw failure
        }
    }

    public actual fun openDecoder(
        stream: StreamInfo,
        threadCount: Int,
        lowDelay: Boolean,
        decoder: CodecId?,
        options: DecoderOptions?,
        hardware: HardwareAccel?,
        corruptData: CorruptData,
    ): StreamDecoder {
        val m = requireModule()
        // Applied or refused, never ignored. Every parameter below used to be
        // accepted and dropped, so a caller who asked for a particular decoder, a particular
        // option, or hardware decoding ran something else and was never told.
        if (hardware != null) {
            throw FFmpegException(
                FFmpegError.Unsupported(
                    0,
                    "the web backend has no hardware decoding, so $hardware cannot be honoured. " +
                        "Open the decoder without one, or ask the browser to decode instead.",
                ),
            )
        }
        // Zero is FFmpeg's own "decide for me" and one is what this artifact can actually do.
        // Anything above that is a request for threads the default web build has no pthreads for.
        if (threadCount > 1) {
            throw FFmpegException(
                FFmpegError.Unsupported(
                    0,
                    "the web artifact is built without pthreads, so $threadCount decoding threads " +
                        "cannot be honoured. Pass 0 to let FFmpeg decide, or 1 for single threaded.",
                ),
            )
        }
        val native = ffkmp_fmt_stream(m, alive(), stream.index)
        val par = ffkmp_stream_codecpar(m, native)
        val codecId = ffkmp_codecpar_codec_id(m, par)
        // The exact decoder seam the common contract describes, implemented rather than skipped.
        val codec = if (decoder == null) {
            ffkmp_find_decoder_by_id(m, codecId)
        } else {
            val named = withCString(m, decoder.name) { ffkmp_find_decoder_by_name(m, it) }
            if (named == 0) {
                throw FFmpegException(
                    FFmpegError.DecoderNotFound(0, decoderNotFoundMessage(stream.codec, requested = decoder)),
                )
            }
            if (ffkmp_codec_id(m, named) != codecId) {
                throw FFmpegException(
                    FFmpegError.InvalidArgument(
                        0,
                        "decoder '${decoder.name}' cannot decode ${stream.codec.name}",
                    ),
                )
            }
            named
        }
        if (codec == 0) {
            throw FFmpegException(
                FFmpegError.DecoderNotFound(0, decoderNotFoundMessage(stream.codec, requested = null)),
            )
        }
        val ctx = ffkmp_codecctx_alloc(m, codec)
        if (ctx == 0) throw FFmpegException(FFmpegError.Internal("allocating a decoder failed"))
        try {
            if (ffkmp_codecctx_from_par(m, ctx, par) < 0) {
                throw FFmpegException(FFmpegError.Internal("copying codec parameters failed"))
            }
            if (lowDelay) ffkmp_codecctx_set_low_delay(m, ctx, 1)
            if (threadCount == 1) ffkmp_codecctx_set_threads(m, ctx, 1, 0)
            // Typed options through the same av_opt_set funnel the other backends use, between
            // context creation and open, which is where FFmpeg wants them.
            options?.compile()?.forEach { (key, value) ->
                val rc = withCString(m, key) { k -> withCString(m, value) { v -> ffkmp_codecctx_set_opt(m, ctx, k, v) } }
                if (rc < 0) {
                    throw FFmpegException(
                        FFmpegError.InvalidArgument(rc, "av_opt_set ('$key') was refused with $rc"),
                    )
                }
            }
            if (ffkmp_codecctx_open(m, ctx, codec) < 0) {
                throw FFmpegException(FFmpegError.Internal("opening the decoder failed"))
            }
        } catch (failure: Throwable) {
            ffkmp_codecctx_free(m, ctx)
            throw failure
        }
        return StreamDecoder(ctx, stream, lifetime, corruptData)
    }

    public actual fun interrupt() {
        /* Single-threaded runtime: nothing can be blocked while this runs, so the flag only
           makes later calls fail fast, which is still the honest half of the contract. */
        if (closed) return
        ffkmp_fmt_interrupt(requireModule(), context)
    }

    actual override fun close() {
        if (closed) return
        closed = true
        // Before the context goes: every reader and decoder holding it raw must stop using it.
        lifetime.closed()
        val m = requireModule()
        ffkmp_fmt_close_input_io(m, contextSlot)
        wasmFree(m, contextSlot)
        bridge.release()
    }

    public actual companion object {
        public actual fun open(path: String): MediaSource = throw noFilesystem(path)

        public actual fun open(path: String, options: Map<String, String>): MediaSource =
            throw noFilesystem(path)

        public actual fun open(io: MediaByteSource, options: Map<String, String>): MediaSource {
            val m = requireModule()
            val bridge = WebIoBridge.install(io)
            val slot = wasmAlloc(m, 4)
            // The unused-option dictionary FFmpeg hands back, as its own out-slot. The binding
            // used to pass a null pointer here and then guess the answer from the key array, which
            // the C side never writes to, so every option was reported unused on every open
            // (audit S-W1). Ask for the dictionary and read it instead.
            val unusedSlot = wasmAlloc(m, 4)
            writeInt32(m, unusedSlot, 0)
            val opts = CStringArrays.of(m, options)
            val rc = try {
                openInputIo(
                    m, slot, bridge.readPointer, bridge.seekPointer, io.size ?: 0L,
                    opts.keys, opts.values, options.size, unusedSlot,
                )
            } catch (failure: Throwable) {
                // The option arrays are released by the finally below on this path too, so they
                // are deliberately absent here: freeing them twice corrupts the module's heap.
                wasmFree(m, unusedSlot); wasmFree(m, slot); bridge.release(); throw failure
            } finally {
                // Input only, and consumed by the call: FFmpeg copied what it wanted into its own
                // dictionary, so these go back whatever the outcome was.
                opts.free(m)
            }
            if (rc < 0) {
                // Nothing to release: on failure the C side frees the dictionary it built and
                // leaves the out-slot at the NULL it wrote on entry.
                wasmFree(m, unusedSlot); wasmFree(m, slot); bridge.release()
                throw FFmpegException(FFmpegError.InvalidData(rc, "could not open this media ($rc)"))
            }
            val leftover = drainUnusedKeys(m, unusedSlot)
            wasmFree(m, unusedSlot)
            val ctx = readInt32(m, slot)
            if (ffkmp_fmt_find_stream_info(m, ctx) < 0) {
                ffkmp_fmt_close_input_io(m, slot)
                wasmFree(m, slot)
                bridge.release()
                throw FFmpegException(FFmpegError.InvalidData(0, "could not read stream information"))
            }
            return MediaSource(slot, ctx, bridge, leftover)
        }

        /**
         * How far before the wanted position an extraction seek aims.
         *
         * The same figure the JVM and Native backends use, for the same reason: a seek lands on a
         * keyframe at or before its target, and a file whose keyframes are seconds apart needs the
         * walk to start before the one that covers the target.
         */
        private const val DECODE_SEEK_BACKOFF_MICROS = 5_000_000L

        private fun noFilesystem(path: String) = FFmpegException(
            FFmpegError.Unsupported(
                0,
                "A browser has no filesystem, so MediaSource.open(\"$path\") cannot work. Open a " +
                    "MediaByteSource instead: whatever the page has is already bytes, from a File, " +
                    "a fetch response or an ArrayBuffer.",
            ),
        )
    }
}

private fun readStreams(m: kotlin.js.JsAny, context: Int): List<StreamInfo> {
    val video = ffkmp_media_type_video(m)
    val audio = ffkmp_media_type_audio(m)
    val subtitle = ffkmp_media_type_subtitle(m)
    // Hoisted like the media-type constants: one call per open, not one per stream.
    val dispositionDefault = ffkmp_disposition_default(m)
    val dispositionForced = ffkmp_disposition_forced(m)
    val dispositionHearingImpaired = ffkmp_disposition_hearing_impaired(m)
    val dispositionVisualImpaired = ffkmp_disposition_visual_impaired(m)
    val dispositionAttachedPic = ffkmp_disposition_attached_pic(m)
    return (0 until ffkmp_fmt_nb_streams(m, context)).map { i ->
        val native = ffkmp_fmt_stream(m, context, i)
        val par = ffkmp_stream_codecpar(m, native)
        val kind = when (ffkmp_codecpar_codec_type(m, par)) {
            video -> MediaType.Video
            audio -> MediaType.Audio
            subtitle -> MediaType.Subtitle
            ffkmp_media_type_data(m) -> MediaType.Data
            ffkmp_media_type_attachment(m) -> MediaType.Attachment
            // Everything else is genuinely unknown. Calling it Data erased the difference between
            // a timed-metadata stream, a font the container carries, and a type this build does
            // not know, which are three different answers to "can you play this".
            else -> MediaType.Unknown
        }
        val timeBase = readTimeBase(m, native)
        StreamInfo(
            index = ffkmp_stream_index(m, native),
            type = kind,
            codec = CodecId(utf8OrNull(m, ffkmp_codec_id_name(m, ffkmp_codecpar_codec_id(m, par))).orEmpty()),
            timeBase = timeBase,
            durationMicros = ffkmp_stream_duration_micros(m, native).takeIf { it > 0 },
            bitrateBps = ffkmp_codecpar_bit_rate(m, par).takeIf { it > 0 },
            video = if (kind == MediaType.Video) {
                VideoStreamInfo(
                    width = ffkmp_codecpar_width(m, par),
                    height = ffkmp_codecpar_height(m, par),
                    pixelFormat = pixelFormatOf(m, ffkmp_codecpar_format(m, par)),
                    frameRate = readRational(m) { n, d -> ffkmp_stream_avg_frame_rate(m, native, n, d) },
                    sampleAspectRatio = readRational(m, fallbackNum = 1, fallbackDen = 1) { n, d ->
                        ffkmp_codecpar_sample_aspect_ratio(m, par, n, d)
                    },
                    color = readParameterColor(m, par),
                )
            } else {
                null
            },
            audio = if (kind == MediaType.Audio) {
                AudioStreamInfo(
                    sampleRate = ffkmp_codecpar_sample_rate(m, par),
                    channels = ffkmp_codecpar_channels(m, par),
                    sampleFormat = sampleFormatOf(m, ffkmp_codecpar_format(m, par)),
                    channelLayoutMask = ffkmp_codecpar_ch_layout_mask(m, par).takeIf { it != 0L },
                )
            } else {
                null
            },
            rotationDegrees = ffkmp_stream_rotation_degrees(m, native),
            disposition = ffkmp_stream_disposition(m, native).let { flags ->
                Disposition(
                    default = flags and dispositionDefault != 0,
                    forced = flags and dispositionForced != 0,
                    hearingImpaired = flags and dispositionHearingImpaired != 0,
                    visualImpaired = flags and dispositionVisualImpaired != 0,
                    attachedPicture = flags and dispositionAttachedPic != 0,
                )
            },
            metadata = readMetadata(m, ffkmp_stream_metadata(m, native)),
            startTimeMicros = ffkmp_stream_start_time(m, native)
                .takeIf { it != Long.MIN_VALUE }
                ?.let { ffkmp_rescale_q(m, it, timeBase.num, timeBase.den, 1, 1_000_000) }
                ?: 0L,
            codecExtradata = readCodecExtradata(m, par),
        )
    }
}

private fun readTimeBase(m: kotlin.js.JsAny, stream: Int): Rational =
    readRational(m, fallbackNum = 1, fallbackDen = 1_000_000) { n, d ->
        ffkmp_stream_time_base(m, stream, n, d)
    }

/**
 * Reads any `(int *num, int *den)` pair out of the codec module.
 *
 * Several entry points answer through two out-parameters, which JavaScript cannot pass directly, so
 * each needs a scratch pair in codec memory. One helper rather than three copies of the same eight
 * lines. A zero denominator means FFmpeg declared nothing and the caller's fallback applies: an
 * undeclared frame rate is 0/1 ("unknown"), an undeclared aspect ratio is 1/1 ("square"), and an
 * undeclared time base is microseconds.
 */
private inline fun readRational(
    m: kotlin.js.JsAny,
    fallbackNum: Long = 0,
    fallbackDen: Long = 1,
    read: (numPtr: Int, denPtr: Int) -> Unit,
): Rational {
    val scratch = wasmAlloc(m, 8)
    try {
        read(scratch, scratch + 4)
        val num = readInt32(m, scratch)
        val den = readInt32(m, scratch + 4)
        return if (den == 0) Rational.of(fallbackNum, fallbackDen) else Rational.of(num.toLong(), den.toLong())
    } finally {
        wasmFree(m, scratch)
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    "(m, out, readFn, seekFn, size, keys, values, n, unused) => " +
        "m._ffkmp_fmt_open_input_io(out, 0, readFn, seekFn, BigInt(size), keys, values, n, unused)",
)
private external fun openInputIo(
    module: kotlin.js.JsAny,
    out: Int,
    readFn: Int,
    seekFn: Int,
    size: Long,
    keys: Int,
    values: Int,
    count: Int,
    unused: Int,
): Int

/**
 * Runs [block] with [text] staged as a NUL-terminated C string, and frees it afterwards.
 *
 * Every string crossing into the module needs codec memory of its own, and every one of them has
 * to come back whether the call succeeded or not.
 */
private inline fun <T> withCString(m: kotlin.js.JsAny, text: String, block: (Int) -> T): T {
    val pointer = allocCString(m, text)
    try {
        return block(pointer)
    } finally {
        wasmFree(m, pointer)
    }
}

/**
 * Walks the unused-option dictionary at [slot] into its key names, then frees it.
 *
 * The dictionary is FFmpeg's own answer about which options it did not consume, and this is the
 * only place that answer exists: the key array the caller passed in is input only. Empty when the
 * slot holds NULL, which is what an open with no options or a failed open leaves behind.
 */
/**
 * The declared colour, with FFmpeg's Unspecified values filled in by the same guess the other
 * backends make, so the three agree about a stream that declares nothing.
 *
 * The guessing itself is a known wart: a guessed value is indistinguishable from a declared one on
 * every backend except for RANGE, which carries `rangeSpecified`. Extending that provenance to the
 * other three fields is its own item; matching the other backends is this one's job, and a wasm
 * answer that was better but different would leave them disagreeing, which is the actual defect.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun readParameterColor(m: kotlin.js.JsAny, par: Int): ColorInfo {
    val range = ffkmp_codecpar_color_range(m, par)
    val declared = ColorInfo(
        matrix = ColorMatrix.fromAv(ffkmp_codecpar_color_space(m, par)),
        primaries = ColorPrimaries.fromAv(ffkmp_codecpar_color_primaries(m, par)),
        transfer = ColorTransfer.fromAv(ffkmp_codecpar_color_transfer(m, par)),
        fullRange = range == 2,
        chromaLocation = ChromaLocation.fromAv(ffkmp_codecpar_chroma_location(m, par)),
        rangeSpecified = range == 1 || range == 2,
    )
    return resolveDeclaredColor(declared, ffkmp_codecpar_height(m, par))
}

/**
 * The codec's own configuration bytes (SPS/PPS and friends), asked for by size and then copied.
 *
 * Two calls, because the C side reports the size when handed a null destination. A caller that
 * decodes a raw stream elsewhere, WebCodecs above all, cannot configure its decoder without these.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun readCodecExtradata(m: kotlin.js.JsAny, par: Int): ByteArray? {
    val size = ffkmp_codecpar_extradata(m, par, 0, 0)
    if (size <= 0) return null
    val buffer = wasmAlloc(m, size)
    try {
        val copied = ffkmp_codecpar_extradata(m, par, buffer, size)
        if (copied != size) return null
        return readBytes(m, buffer, size)
    } finally {
        wasmFree(m, buffer)
    }
}

/**
 * Walks an FFmpeg metadata dictionary into a map, in the order the container wrote it.
 *
 * Borrowed, not owned: these dictionaries belong to the format context or the stream, so this
 * reads and never frees. Contrast [drainUnusedKeys] below, which owns the dictionary it walks.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun readMetadata(m: kotlin.js.JsAny, dict: Int): Map<String, String> {
    if (dict == 0) return emptyMap()
    val out = LinkedHashMap<String, String>()
    var entry = 0
    while (true) {
        entry = ffkmp_dict_get(m, dict, entry)
        if (entry == 0) break
        val key = utf8OrNull(m, ffkmp_dict_entry_key(m, entry)) ?: continue
        out[key] = utf8OrNull(m, ffkmp_dict_entry_value(m, entry)).orEmpty()
    }
    return out
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun drainUnusedKeys(m: kotlin.js.JsAny, slot: Int): List<String> {
    val dict = readInt32(m, slot)
    if (dict == 0) return emptyList()
    val keys = mutableListOf<String>()
    var entry = 0
    while (true) {
        entry = ffkmp_dict_get(m, dict, entry)
        if (entry == 0) break
        utf8OrNull(m, ffkmp_dict_entry_key(m, entry))?.let { keys += it }
    }
    // Takes the slot, not the value: the helper NULLs the caller's pointer as it frees.
    ffkmp_dict_free(m, slot)
    return keys
}

/** Two NULL-terminated `char *` arrays in codec memory, which is how the C surface takes options. */
private class CStringArrays(val keys: Int, val values: Int, private val strings: List<Int>) {

    fun free(m: kotlin.js.JsAny) {
        strings.forEach { wasmFree(m, it) }
        if (keys != 0) wasmFree(m, keys)
        if (values != 0) wasmFree(m, values)
    }

    companion object {
        fun of(m: kotlin.js.JsAny, options: Map<String, String>): CStringArrays {
            if (options.isEmpty()) return CStringArrays(0, 0, emptyList())
            val strings = mutableListOf<Int>()
            val keys = wasmAlloc(m, options.size * 4)
            val values = wasmAlloc(m, options.size * 4)
            options.entries.forEachIndexed { index, (key, value) ->
                val keyPtr = allocCString(m, key)
                val valuePtr = allocCString(m, value)
                strings += keyPtr
                strings += valuePtr
                writeInt32(m, keys + index * 4, keyPtr)
                writeInt32(m, values + index * 4, valuePtr)
            }
            return CStringArrays(keys, values, strings)
        }
    }
}
