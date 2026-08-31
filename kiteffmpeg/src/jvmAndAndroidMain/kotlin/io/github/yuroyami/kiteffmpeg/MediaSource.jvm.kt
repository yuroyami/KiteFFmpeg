package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

public actual class MediaSource internal constructor(
    private var formatToken: Long,
    public actual val streams: List<StreamInfo>,
    public actual val durationMicros: Long?,
    public actual val formatName: String,
    public actual val metadata: Map<String, String>,
    public actual val startTimeMicros: Long,
    public actual val chapters: List<Chapter> = emptyList(),
    public actual val unusedOpenOptions: List<String> = emptyList(),
    /** Non-null exactly for custom-io opens (M1): close then routes through the io close. */
    private val jniIo: JniByteIo? = null,
) : AutoCloseable {
    private val stateLock = Any()
    private var demuxing = false
    private var readerActive = false

    internal fun checkOpen(): Long = synchronized(stateLock) {
        check(formatToken != 0L) { "MediaSource is closed" }
        formatToken
    }

    internal fun toRelativeMicros(timestamp: Long, timeBase: Rational): Long =
        Internals.rescaleQ(timestamp, timeBase, Rational.Tb_us) - startTimeMicros

    internal fun toAbsoluteMicros(relativeMicros: Long): Long = relativeMicros + startTimeMicros

    private fun beginDemux(): Long = synchronized(stateLock) {
        check(formatToken != 0L) { "MediaSource is closed" }
        check(!demuxing) {
            "Another decode flow is already collecting on this MediaSource. The demuxer is a " +
                "single cursor. Use decodeStreams(listOf(a, b)) to read several streams in one pass."
        }
        check(!readerActive) {
            "A PacketReader is open on this MediaSource and owns the demuxer cursor. Close it " +
                "before using the batch decode API."
        }
        demuxing = true
        formatToken
    }

    private fun endDemux() = synchronized(stateLock) { demuxing = false }
    internal fun endPacketReader() = synchronized(stateLock) { readerActive = false }

    /**
     * Applies a live reader selection as one source-state transaction. The reader publishes its
     * matching Kotlin delivery map only after this returns, so a failed flag change can never make
     * it hand a caller an unrequested packet. Restore the previous FFmpeg flags before surfacing a
     * setup failure; JNI's discard setter is currently infallible for a live stream token, but the
     * rollback keeps that invariant true if the bridge later gains a fallible implementation.
     */
    internal fun applyPacketReaderSelection(selected: Set<Int>, previous: Set<Int>): Unit =
        synchronized(stateLock) {
            check(formatToken != 0L) { "MediaSource is closed" }
            check(readerActive) { "This MediaSource has no active PacketReader" }
            try {
                setStreamDiscardSelection(formatToken, selected)
            } catch (failure: Throwable) {
                try {
                    setStreamDiscardSelection(formatToken, previous)
                } catch (rollback: Throwable) {
                    failure.addSuppressed(rollback)
                }
                throw failure
            }
        }

    private fun setStreamDiscardSelection(context: Long, selected: Set<Int>) {
        streams.forEach { info ->
            val stream = Internals.fmtStream(context, info.index)
            try {
                Internals.streamDiscard(stream, info.index !in selected)
            } finally {
                Internals.borrowedRelease(stream, Internals.KIND_STREAM)
            }
        }
    }

    internal fun restoreStreamDiscardDefaults(): Unit = synchronized(stateLock) {
        val context = checkOpen()
        setStreamDiscardSelection(context, streams.mapTo(HashSet()) { it.index })
    }

    public actual val isSeekable: Boolean = Internals.fmtIsSeekable(formatToken)
    // Attached pictures (album art) are excluded first, exactly as the native side does.
    public actual val primaryVideo: StreamInfo?
        get() = streams.firstOrNull { it.type == MediaType.Video && !it.disposition.attachedPicture }
            ?: streams.firstOrNull { it.type == MediaType.Video }
    public actual val primaryAudio: StreamInfo? get() = streams.firstOrNull { it.type == MediaType.Audio }

    @Volatile
    public actual var corruptData: CorruptData = CorruptData.Skip

    @Volatile
    public actual var corruptDataSkipped: Long = 0L
        private set

    /**
     * The one place the batch flows decide about damaged data.
     *
     * Under [CorruptData.Fail] it throws; otherwise it counts the loss so a caller can tell a
     * clean decode from an incomplete one, which used to be impossible.
     */
    private fun noteCorruptData(rc: Int) {
        if (corruptData == CorruptData.Fail) throw FFmpegException(avError(rc))
        corruptDataSkipped++
    }

    public actual fun decodedFrames(stream: StreamInfo): Flow<Frame> = decodeStreams(listOf(stream))

    public actual fun decodeStreams(streams: List<StreamInfo>): Flow<Frame> = flow {
        demuxRouted(
            decode = streams,
            copy = emptyList(),
            onFrame = { emit(it.copy()) },
            onPacket = { _, _ -> },
        )
    }

    internal suspend fun demuxRouted(
        decode: List<StreamInfo>,
        copy: List<StreamInfo>,
        onFrame: suspend (Frame) -> Unit,
        onPacket: (Long, StreamInfo) -> Unit,
    ) {
        val all = decode + copy
        require(all.isNotEmpty()) { "Need at least one stream to demux" }
        require(decode.all { it.type.isAv }) { "Only video/audio streams can be decoded" }
        require(all.distinctBy { it.index }.size == all.size) { "Duplicate stream indices" }
        all.forEach(::requireOwnStream)

        val context = beginDemux()
        val decoders = mutableListOf<DecoderState>()
        try {
            val copyByIndex = copy.associateBy { it.index }
            decode.forEach { stream -> decoders += DecoderState.open(context, stream) }
            val frame = FrameOps.acquire()
            try {
                withPacket { packet ->
                    try {
                        pump(context, decoders, copyByIndex, packet, frame, onFrame, onPacket)
                    } catch (_: StopDemux) {
                        // The consumer reached a trim bound or found its requested frame.
                    }
                }
            } finally {
                frame.close()
            }
        } finally {
            // A decoder can fail while the requested set is still being opened. Keeping the
            // incrementally acquired owners outside the construction loop makes this single
            // cleanup cover both partial-open failure and the normal path.
            decoders.forEach(DecoderState::close)
            endDemux()
        }
    }

    private suspend fun pump(
        context: Long,
        decoders: List<DecoderState>,
        copyByIndex: Map<Int, StreamInfo>,
        packet: Long,
        frame: Frame,
        onFrame: suspend (Frame) -> Unit,
        onPacket: (Long, StreamInfo) -> Unit,
    ) {
        val decoderByIndex = decoders.associateBy { it.stream.index }
        while (true) {
            currentCoroutineContext().ensureActive()
            val rc = Internals.fmtReadFrame(context, packet)
            if (rc == Internals.errorEof) break
            if (rc < 0) throw FFmpegException(avError(rc))
            val index = Internals.packetStreamIndex(packet)
            val decoder = decoderByIndex[index]
            val copyInfo = if (decoder == null) copyByIndex[index] else null
            try {
                when {
                    decoder != null -> sendAndDrain(decoder, packet, frame, onFrame)
                    copyInfo != null -> onPacket(packet, copyInfo)
                }
            } finally {
                Internals.packetUnref(packet)
            }
        }
        decoders.forEach { sendAndDrain(it, 0L, frame, onFrame) }
    }

    private suspend fun sendAndDrain(
        decoder: DecoderState,
        packet: Long,
        frame: Frame,
        onFrame: suspend (Frame) -> Unit,
    ) {
        while (true) {
            val rc = Internals.codecCtxSendPacket(decoder.context, packet)
            when (rc) {
                0, Internals.errorEof -> {
                    drain(decoder, frame, onFrame)
                    return
                }
                Internals.errorEagain -> drain(decoder, frame, onFrame)
                FFmpegError.AVERROR_INVALIDDATA -> {
                    noteCorruptData(rc)
                    drain(decoder, frame, onFrame)
                    return
                }
                else -> throw FFmpegException(avError(rc))
            }
        }
    }

    private suspend fun drain(
        decoder: DecoderState,
        frame: Frame,
        onFrame: suspend (Frame) -> Unit,
    ) {
        while (true) {
            val rc = Internals.codecCtxReceiveFrame(decoder.context, frame.checkOpen())
            if (rc == Internals.errorEagain || rc == Internals.errorEof) return
            if (rc == FFmpegError.AVERROR_INVALIDDATA) {
                noteCorruptData(rc)
                return
            }
            if (rc < 0) throw FFmpegException(avError(rc))
            Internals.frameUseBestEffort(frame.checkOpen())
            val callbackFrame = FrameOps.wrap(
                frame.checkOpen(),
                decoder.stream.index,
                decoder.stream.type,
                decoder.stream.timeBase,
            )
            try {
                onFrame(callbackFrame)
            } finally {
                callbackFrame.close()
            }
        }
    }

    public actual suspend fun seekMicros(micros: Long) {
        // The native seek runs while stateLock is held: releasing it after the token read would
        // let a concurrent close() free the format context mid-seek. A close arriving during the
        // seek now blocks until the seek returns, which is the safe ordering.
        synchronized(stateLock) {
            check(formatToken != 0L) { "MediaSource is closed" }
            check(!demuxing) { "Cannot seek while a decode flow is collecting: the demuxer cursor is shared" }
            check(!readerActive) {
                "Cannot seek this MediaSource while a PacketReader is open: the reader owns the " +
                    "demuxer cursor. Use PacketReader.seek instead."
            }
            val rc = Internals.fmtSeekMicros(formatToken, -1, toAbsoluteMicros(micros))
            if (rc < 0) throw FFmpegException(avError(rc))
        }
    }

    internal suspend fun seekForDecode(micros: Long) {
        seekMicros((micros - DECODE_SEEK_BACKOFF_MICROS).coerceAtLeast(0L))
    }

    internal fun <T> withCodecParameters(stream: StreamInfo, block: (Long) -> T): T {
        // Before the index is trusted, not after. This is the entry point addCopyStream uses, so
        // without the check a StreamInfo from a DIFFERENT source resolved to whatever lives at the
        // same index here, and the remux wrote these codec parameters under the other file's time
        // base. Native canonicalizes in codecparOf; this half was missed.
        requireOwnStream(stream)
        return synchronized(stateLock) {
            val streamToken = Internals.fmtStream(checkOpen(), stream.index)
            var parameters = 0L
            try {
                parameters = Internals.streamCodecPar(streamToken)
                block(parameters)
            } finally {
                if (parameters != 0L) Internals.borrowedRelease(parameters, Internals.KIND_CODEC_PAR)
                Internals.borrowedRelease(streamToken, Internals.KIND_STREAM)
            }
        }
    }

    public actual suspend fun extractFrame(atMicros: Long, stream: StreamInfo?): Frame {
        val target = stream ?: primaryVideo
            ?: throw FFmpegException(FFmpegError.Internal("No video stream to extract a frame from"))
        seekForDecode(atMicros)
        var result: Frame? = null
        demuxRouted(
            decode = listOf(target),
            copy = emptyList(),
            onFrame = { frame ->
                val timestamp = if (frame.info.hasPts) {
                    toRelativeMicros(frame.info.pts, target.timeBase)
                } else Long.MAX_VALUE
                if (timestamp >= atMicros) {
                    result = frame.copy()
                    throw StopDemux()
                }
            },
            onPacket = { _, _ -> },
        )
        return result
            ?: throw FFmpegException(FFmpegError.Internal("No frame at ${atMicros}us (beyond end of stream?)"))
    }

    @KiteFFmpegLowLevelApi
    public actual fun openPacketReader(streams: List<StreamInfo>): PacketReader {
        val selection = canonicalPacketSelection(this.streams, streams)
        val context = synchronized(stateLock) {
            check(formatToken != 0L) { "MediaSource is closed" }
            check(!demuxing) { "A decode flow is collecting on this MediaSource" }
            check(!readerActive) { "A PacketReader is already open on this MediaSource" }
            readerActive = true
            formatToken
        }
        try {
            applyPacketReaderSelection(
                selected = selection.keys,
                previous = this.streams.mapTo(HashSet()) { it.index },
            )
            return PacketReader(this, context, selection)
        } catch (error: Throwable) {
            try {
                restoreStreamDiscardDefaults()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            } finally {
                // A failed restoration must not permanently strand the source in reader-active
                // state or replace the setup failure the caller actually needs to diagnose.
                endPacketReader()
            }
            throw error
        }
    }

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
        require(stream.type.isAv) { "Only video and audio streams can be decoded, got ${stream.type}" }
        requireOwnStream(stream)
        return synchronized(stateLock) {
            StreamDecoder.open(
                checkOpen(), stream, threadCount, lowDelay, decoder, options, hardware, corruptData,
            )
        }
    }

    /**
     * Canonicalizes a caller-supplied [StreamInfo] against this source's own table; StreamInfo is
     * a public data class and therefore forgeable (audit KiteFFmpeg P1-8). Same rule as native.
     */
    private fun requireOwnStream(supplied: StreamInfo) {
        val own = streams.firstOrNull { it.index == supplied.index }
        require(own == supplied) {
            "StreamInfo(index=${supplied.index}) does not belong to this MediaSource. Pass entries " +
                "from THIS source's streams list; stream identity is source-bound."
        }
    }

    public actual fun interrupt() {
        /* Deliberately NOT under the demux lock: the whole point is reaching a context another
           thread is blocked on. The handle table resolves or refuses a stale token, so this
           cannot dereference a closed context. */
        Internals.fmtInterrupt(formatToken)
    }

    actual override fun close() {
        val context = synchronized(stateLock) {
            if (formatToken == 0L) return
            check(!readerActive) {
                "Cannot close MediaSource while a PacketReader is open. Close the reader first."
            }
            check(!demuxing) {
                "Cannot close MediaSource while a decode flow is collecting. Cancel or finish collection first."
            }
            formatToken.also { formatToken = 0L }
        }
        if (jniIo != null) {
            Internals.fmtCloseInputIo(context)
            jniIo.closeSource()
        } else {
            Internals.fmtCloseInput(context)
        }
    }

    public actual companion object {
        @Throws(FFmpegException::class)
        public actual fun open(path: String): MediaSource {
            Internals.requireCompatible()
            return openMediaSource(path)
        }

        @Throws(FFmpegException::class)
        public actual fun open(path: String, options: Map<String, String>): MediaSource {
            Internals.requireCompatible()
            return openMediaSource(path, options)
        }

        @Throws(FFmpegException::class)
        public actual fun open(io: MediaByteSource, options: Map<String, String>): MediaSource {
            Internals.requireCompatible()
            return openMediaSourceIo(io, options)
        }

        private const val DECODE_SEEK_BACKOFF_MICROS = 5_000_000L
    }
}

internal class StopDemux : Throwable()

private class DecoderState(val stream: StreamInfo, var context: Long) {
    fun close() {
        val owned = context
        if (owned == 0L) return
        context = 0L
        Internals.codecCtxFree(owned)
    }

    companion object {
        fun open(format: Long, stream: StreamInfo): DecoderState {
            val streamToken = Internals.fmtStream(format, stream.index)
            var parameters = 0L
            var codec = 0L
            try {
                parameters = Internals.streamCodecPar(streamToken)
                val codecId = Internals.codecParId(parameters)
                codec = Internals.findDecoderById(codecId)
                if (codec == 0L) {
                    throw FFmpegException(
                        FFmpegError.DecoderNotFound(0, decoderNotFoundMessage(stream.codec, requested = null)),
                    )
                }
                val context = Internals.codecCtxAlloc(codec)
                try {
                    check0(Internals.codecCtxFromPar(context, parameters), "avcodec_parameters_to_context")
                    check0(Internals.codecCtxOpen(context, codec), "avcodec_open2")
                    return DecoderState(stream, context)
                } catch (error: Throwable) {
                    Internals.codecCtxFree(context)
                    throw error
                }
            } finally {
                if (codec != 0L) Internals.codecRelease(codec)
                if (parameters != 0L) Internals.borrowedRelease(parameters, Internals.KIND_CODEC_PAR)
                Internals.borrowedRelease(streamToken, Internals.KIND_STREAM)
            }
        }
    }
}

private fun openMediaSource(path: String, options: Map<String, String> = emptyMap()): MediaSource {
    var unusedKeys: List<String> = emptyList()
    val context = if (options.isEmpty()) {
        Internals.fmtOpenInput(path)
    } else {
        // KD-4: the unconsumed remainder crosses the bridge as one unit-separated string.
        val unusedSlot = arrayOfNulls<String>(1)
        val token = Internals.fmtOpenInput2(
            path,
            options.keys.toTypedArray(),
            options.values.toTypedArray(),
            unusedSlot,
        )
        unusedKeys = unusedSlot[0]?.takeIf { it.isNotEmpty() }?.split('\u001f') ?: emptyList()
        token
    }
    try {
        check0(Internals.fmtFindStreamInfo(context), "avformat_find_stream_info")
        return MediaSource(
            formatToken = context,
            streams = buildStreams(context),
            durationMicros = Internals.fmtDuration(context).takeIf { it > 0L },
            formatName = Internals.fmtInputName(context).ifEmpty { "unknown" },
            metadata = readMetadata(Internals.fmtMetadata(context)),
            startTimeMicros = Internals.fmtStartTime(context),
            chapters = readChapters(context),
            unusedOpenOptions = unusedKeys,
        )
    } catch (error: Throwable) {
        Internals.fmtCloseInput(context)
        throw error
    }
}

/** M1: the custom AVIO open. The JNI bridge holds global refs to [JniByteIo] until the paired
 *  close, so the adapter object outlives any Kotlin-side reference by construction. */
private fun openMediaSourceIo(io: MediaByteSource, options: Map<String, String>): MediaSource {
    val adapter = JniByteIo(io)
    var unusedKeys: List<String> = emptyList()
    val unusedSlot = arrayOfNulls<String>(1)
    // Ownership of the byte source transfers here, at the adapter, so the open ITSELF has to sit
    // inside a scope that closes it. A throw from fmtOpenInputIo escaped before the try below
    // began and left the caller's source open for ever.
    val context = try {
        Internals.fmtOpenInputIo(
            adapter,
            io.seekable,
            io.size ?: -1L,
            options.keys.toTypedArray().takeIf { it.isNotEmpty() },
            options.values.toTypedArray().takeIf { it.isNotEmpty() },
            unusedSlot,
        )
    } catch (error: Throwable) {
        adapter.closeSource()
        throw error
    }
    unusedKeys = unusedSlot[0]?.takeIf { it.isNotEmpty() }?.split('\u001f') ?: emptyList()
    try {
        check0(Internals.fmtFindStreamInfo(context), "avformat_find_stream_info")
        return MediaSource(
            formatToken = context,
            streams = buildStreams(context),
            durationMicros = Internals.fmtDuration(context).takeIf { it > 0L },
            formatName = Internals.fmtInputName(context).ifEmpty { "unknown" },
            metadata = readMetadata(Internals.fmtMetadata(context)),
            startTimeMicros = Internals.fmtStartTime(context),
            chapters = readChapters(context),
            unusedOpenOptions = unusedKeys,
            jniIo = adapter,
        )
    } catch (error: Throwable) {
        Internals.fmtCloseInputIo(context)
        adapter.closeSource()
        throw error
    }
}

/** KD-5: the chapter table, bounds already in microseconds from the C side. */
private fun readChapters(format: Long): List<Chapter> {
    val count = Internals.fmtChapterCount(format)
    if (count <= 0) return emptyList()
    val fields = LongArray(3)
    return buildList {
        for (index in 0 until count) {
            if (Internals.fmtChapterGet(format, index, fields) < 0) continue
            val dict = Internals.fmtChapterMetadata(format, index)
            add(Chapter(fields[0], fields[1], fields[2], readMetadata(dict)))
        }
    }
}

private fun buildStreams(format: Long): List<StreamInfo> = buildList {
    repeat(Internals.fmtNbStreams(format)) { index ->
        val stream = Internals.fmtStream(format, index)
        var parameters = 0L
        try {
            parameters = Internals.streamCodecPar(stream)
            val type = when (Internals.codecParType(parameters)) {
                Internals.mediaTypeVideo -> MediaType.Video
                Internals.mediaTypeAudio -> MediaType.Audio
                Internals.mediaTypeSubtitle -> MediaType.Subtitle
                Internals.mediaTypeData -> MediaType.Data
                Internals.mediaTypeAttachment -> MediaType.Attachment
                else -> MediaType.Unknown
            }
            val timeBase = Internals.streamTimeBase(stream)
            val codecId = Internals.codecParId(parameters)
            val codecName = Internals.codecIdName(codecId)
            val sar = Internals.codecParSar(parameters).let { if (it.num == 0) Rational(1, 1) else it }
            add(
                StreamInfo(
                    index = Internals.streamIndex(stream),
                    type = type,
                    codec = CodecId(codecName),
                    timeBase = timeBase,
                    durationMicros = Internals.streamDuration(stream).takeIf { it > 0L },
                    bitrateBps = Internals.codecParBitrate(parameters).takeIf { it > 0L },
                    video = if (type == MediaType.Video) VideoStreamInfo(
                        width = Internals.codecParWidth(parameters),
                        height = Internals.codecParHeight(parameters),
                        pixelFormat = pixelFormatFromAv(Internals.codecParFormat(parameters)),
                        frameRate = Internals.streamFrameRate(stream),
                        sampleAspectRatio = sar,
                        color = readCodecParameterColor(parameters),
                        vp9 = if (codecName == "vp9") {
                            readVp9CodecInfo(parameters)
                        } else null,
                    ) else null,
                    audio = if (type == MediaType.Audio) AudioStreamInfo(
                        sampleRate = Internals.codecParSampleRate(parameters),
                        channels = Internals.codecParChannels(parameters),
                        sampleFormat = sampleFormatFromAv(Internals.codecParFormat(parameters)),
                        channelLayoutMask = Internals.codecParChannelLayout(parameters).takeIf { it != 0L },
                    ) else null,
                    metadata = readMetadata(Internals.streamMetadata(stream)),
                    disposition = readDisposition(Internals.streamDisposition(stream)),
                    rotationDegrees = Internals.streamRotation(stream),
                    startTimeMicros = Internals.streamStartTime(stream)
                        .takeIf { it != Long.MIN_VALUE }
                        ?.let { Internals.rescaleQ(it, timeBase, Rational.Tb_us) }
                        ?: 0L,
                    codecExtradata = Internals.codecParExtradata(parameters),
                ),
            )
        } finally {
            if (parameters != 0L) Internals.borrowedRelease(parameters, Internals.KIND_CODEC_PAR)
            Internals.borrowedRelease(stream, Internals.KIND_STREAM)
        }
    }
}

private fun readCodecParameterColor(parameters: Long): ColorInfo {
    val range = Internals.codecParColorRange(parameters)
    val declared = ColorInfo(
        matrix = ColorMatrix.fromAv(Internals.codecParColorSpace(parameters)),
        primaries = ColorPrimaries.fromAv(Internals.codecParColorPrimaries(parameters)),
        transfer = ColorTransfer.fromAv(Internals.codecParColorTransfer(parameters)),
        fullRange = range == 2,
        chromaLocation = ChromaLocation.fromAv(Internals.codecParChromaLocation(parameters)),
        rangeSpecified = range == 1 || range == 2,
    )
    return resolveDeclaredColor(declared, Internals.codecParHeight(parameters))
}

private fun readVp9CodecInfo(parameters: Long): Vp9CodecInfo = Vp9CodecInfo(
    profile = Vp9Profile.fromNumber(Internals.codecParProfile(parameters)),
    level = Vp9Level.fromCode(Internals.codecParLevel(parameters)),
    bitDepth = Vp9BitDepth.fromBits(Internals.codecParBitDepth(parameters)),
    chromaSubsampling = Vp9ChromaSubsampling.fromCode(Internals.codecParChromaSubsampling(parameters)),
)

private fun readDisposition(flags: Int): Disposition = Disposition(
    default = flags and Internals.dispositionDefault != 0,
    forced = flags and Internals.dispositionForced != 0,
    hearingImpaired = flags and Internals.dispositionHearingImpaired != 0,
    visualImpaired = flags and Internals.dispositionVisualImpaired != 0,
    attachedPicture = flags and Internals.dispositionAttachedPic != 0,
)

private fun readMetadata(dictionary: Long): Map<String, String> {
    if (dictionary == 0L) return emptyMap()
    val result = LinkedHashMap<String, String>()
    var previous = 0L
    try {
        while (true) {
            val next = Internals.dictNext(dictionary, previous)
            if (previous != 0L) Internals.borrowedRelease(previous, Internals.KIND_DICT_ENTRY)
            previous = next
            if (next == 0L) break
            result[Internals.dictKey(next)] = Internals.dictValue(next)
        }
    } finally {
        if (previous != 0L) Internals.borrowedRelease(previous, Internals.KIND_DICT_ENTRY)
        Internals.borrowedRelease(dictionary, Internals.KIND_DICT)
    }
    return result
}
