package io.github.yuroyami.kitecodec

@KiteCodecLowLevelApi
public actual class Packet internal constructor(
    internal var token: Long,
    public actual val timeBase: Rational,
) : AutoCloseable {
    // Guards the token across each native call, excluding a concurrent close(): the JNI handle
    // table only guarantees a non-torn lookup, not object lifetime for the whole operation.
    internal val lock: Any = Any()

    internal fun checkOpen(): Long {
        check(token != 0L) { "Packet is closed" }
        return token
    }

    internal inline fun <R> locked(block: (Long) -> R): R = synchronized(lock) { block(checkOpen()) }

    public actual val streamIndex: Int get() = locked { Internals.packetStreamIndex(it) }
    public actual val pts: Long get() = locked { Internals.packetPts(it) }
    public actual val dts: Long get() = locked { Internals.packetDts(it) }
    public actual val duration: Long get() = locked { Internals.packetDuration(it) }
    public actual val isKeyframe: Boolean get() = locked { Internals.packetIsKeyframe(it) }
    public actual val sizeBytes: Int get() = locked { Internals.packetSize(it) }
    public actual val bytePosition: Long get() = locked { Internals.packetPosition(it) }
    public actual val hasPts: Boolean get() = pts != FrameInfo.NOPTS
    public actual val ptsMicros: Long?
        get() = if (hasPts) Internals.rescaleQ(pts, timeBase, Rational.Tb_us) else null
    public actual val dtsMicros: Long?
        get() = dts.takeIf { it != FrameInfo.NOPTS }?.let { Internals.rescaleQ(it, timeBase, Rational.Tb_us) }
    public actual val durationMicros: Long?
        get() = duration.takeIf { it > 0L }?.let { Internals.rescaleQ(it, timeBase, Rational.Tb_us) }

    @KiteCodecLowLevelApi
    @Throws(FFmpegException::class)
    public actual fun copy(): Packet = locked { Packet(Internals.packetClone(it), timeBase) }

    public actual fun copyBytes(): ByteArray = locked { Internals.packetBytes(it) }

    actual override fun close() {
        synchronized(lock) {
            val owned = token
            if (owned == 0L) return
            token = 0L
            Internals.packetFree(owned)
        }
    }
}

@KiteCodecLowLevelApi
public actual enum class SeekDirection { Backward, Forward, Any }

@KiteCodecLowLevelApi
public actual class PacketReader internal constructor(
    private val source: MediaSource,
    private val formatToken: Long,
    private val timeBaseByStream: Map<Int, Rational>,
) : AutoCloseable {
    private var scratch = Internals.packetAlloc()

    // Excludes close() while a read or seek is inside native code. A close arriving mid-read
    // blocks until the read returns instead of freeing the scratch packet under it.
    private val lock = Any()

    @Throws(FFmpegException::class)
    public actual fun read(): Packet? {
        synchronized(lock) {
            check(scratch != 0L) { "PacketReader is closed" }
            while (true) {
                val rc = Internals.fmtReadFrame(formatToken, scratch)
                if (rc == Internals.errorEof) return null
                if (rc < 0) throw FFmpegException(avError(rc))
                val timeBase = timeBaseByStream[Internals.packetStreamIndex(scratch)]
                if (timeBase == null) {
                    Internals.packetUnref(scratch)
                    continue
                }
                val owned = Internals.packetAlloc()
                try {
                    Internals.packetMoveRef(owned, scratch)
                    return Packet(owned, timeBase)
                } catch (error: Throwable) {
                    Internals.packetFree(owned)
                    throw error
                }
            }
        }
    }

    @Throws(FFmpegException::class)
    public actual fun seek(micros: Long, direction: SeekDirection, notEarlierThan: Long?): Unit = synchronized(lock) {
        check(scratch != 0L) { "PacketReader is closed" }
        val target = source.toAbsoluteMicros(micros)
        val min = notEarlierThan?.let(source::toAbsoluteMicros) ?: Long.MIN_VALUE
        // Same bound rule as the native actual: Any may land after the target by contract.
        val max = when (direction) {
            SeekDirection.Backward -> target
            SeekDirection.Forward, SeekDirection.Any -> Long.MAX_VALUE
        }
        val flags = when (direction) {
            SeekDirection.Backward -> Internals.seekFlagBackward
            SeekDirection.Forward -> 0
            SeekDirection.Any -> Internals.seekFlagAny
        }
        val rc = Internals.fmtSeekFile(formatToken, -1, min, target, max, flags)
        if (rc < 0) throw FFmpegException(avError(rc))
    }

    actual override fun close() {
        synchronized(lock) {
            val owned = scratch
            if (owned == 0L) return
            scratch = 0L
            try {
                Internals.packetFree(owned)
            } finally {
                try {
                    source.restoreStreamDiscardDefaults()
                } finally {
                    source.endPacketReader()
                }
            }
        }
    }
}

@KiteCodecLowLevelApi
public actual class StreamDecoder internal constructor(
    public actual val stream: StreamInfo,
    private var codecContext: Long,
    private val corruptData: CorruptData = CorruptData.Skip,
) : AutoCloseable {
    private var landing = Internals.frameAlloc()

    // Excludes close() while a decode call is inside native code; lock order is decoder → packet.
    private val lock = Any()

    public actual var isDrained: Boolean = false
        private set

    public actual var corruptDataSkipped: Long = 0L
        private set

    /**
     * The one place damaged data is decided about (audit P1-05).
     *
     * Under [CorruptData.Fail] it throws; otherwise it counts the loss and lets the caller carry
     * on. What it must never do again is return quietly having recorded nothing, which is what
     * every backend did.
     */
    private fun noteCorruptData(rc: Int) {
        if (corruptData == CorruptData.Fail) throw FFmpegException(avError(rc))
        corruptDataSkipped++
    }

    @Throws(FFmpegException::class)
    public actual fun send(packet: Packet?): Boolean = synchronized(lock) {
        check(codecContext != 0L) { "StreamDecoder is closed" }
        requireOwnStream(packet, stream)
        val rc = if (packet == null) {
            Internals.codecCtxSendPacket(codecContext, 0L)
        } else {
            packet.locked { Internals.codecCtxSendPacket(codecContext, it) }
        }
        when (rc) {
            0, Internals.errorEof -> true
            FFmpegError.AVERROR_INVALIDDATA -> {
                noteCorruptData(rc)
                true
            }
            Internals.errorEagain -> false
            else -> if (rc < 0) throw FFmpegException(avError(rc)) else true
        }
    }

    @Throws(FFmpegException::class)
    public actual fun receive(): Frame? = synchronized(lock) {
        check(codecContext != 0L) { "StreamDecoder is closed" }
        val rc = Internals.codecCtxReceiveFrame(codecContext, landing)
        when (rc) {
            Internals.errorEof -> {
                isDrained = true
                return null
            }
            Internals.errorEagain -> return null
            FFmpegError.AVERROR_INVALIDDATA -> {
                noteCorruptData(rc)
                return null
            }
        }
        if (rc < 0) throw FFmpegException(avError(rc))
        Internals.frameUseBestEffort(landing)
        return Frame(
            token = Internals.frameClone(landing),
            ownsToken = true,
            streamIndex = stream.index,
            streamType = stream.type,
            streamTimeBase = stream.timeBase,
        )
    }

    public actual fun flush(): Unit = synchronized(lock) {
        check(codecContext != 0L) { "StreamDecoder is closed" }
        corruptDataSkipped = 0L
        Internals.codecCtxFlush(codecContext)
        isDrained = false
    }

    actual override fun close() {
        synchronized(lock) {
            val context = codecContext
            if (context == 0L) return
            codecContext = 0L
            val frame = landing
            landing = 0L
            if (frame != 0L) Internals.frameFree(frame)
            Internals.codecCtxFree(context)
        }
    }

    internal companion object {
        fun open(
            formatToken: Long,
            stream: StreamInfo,
            threadCount: Int,
            lowDelay: Boolean,
            requestedDecoder: CodecId?,
            options: io.github.yuroyami.kitecodec.dsl.DecoderOptions? = null,
            hardware: HardwareAccel? = null,
            corruptData: CorruptData = CorruptData.Skip,
        ): StreamDecoder {
            val streamToken = Internals.fmtStream(formatToken, stream.index)
            var parameters = 0L
            var codec = 0L
            try {
                parameters = Internals.streamCodecPar(streamToken)
                val codecId = Internals.codecParId(parameters)
                codec = if (requestedDecoder == null) {
                    Internals.findDecoderById(codecId)
                } else {
                    Internals.findDecoderByName(requestedDecoder.name)
                }
                if (codec == 0L) {
                    throw FFmpegException(
                        FFmpegError.DecoderNotFound(0, decoderNotFoundMessage(stream.codec, requestedDecoder)),
                    )
                }
                if (requestedDecoder != null && Internals.codecId(codec) != codecId) {
                    throw FFmpegException(
                        FFmpegError.Internal(
                            "Decoder '${requestedDecoder.name}' cannot decode stream codec id $codecId",
                        ),
                    )
                }

                val context = Internals.codecCtxAlloc(codec)
                try {
                    check0(Internals.codecCtxFromPar(context, parameters), "avcodec_parameters_to_context")
                    Internals.codecCtxSetThreads(context, threadCount, stream.type == MediaType.Video)
                    Internals.codecCtxSetLowDelay(context, lowDelay)
                    // KD-2 (KPKMP 17.10): typed options through the existing av_opt_set funnel,
                    // between context creation and open, exactly where FFmpeg wants them.
                    options?.compile()?.forEach { (key, value) ->
                        check0(Internals.codecCtxSetOpt(context, key, value), "av_opt_set ('$key')")
                    }
                    // Window 3 (S2.a): the HWACCEL attach, in the same pre-open moment. On macOS
                    // JVM this works, because the C archive links VideoToolbox there; elsewhere
                    // FFmpeg answers ENOSYS and the refusal is typed, never silent.
                    when (hardware) {
                        HardwareAccel.VideoToolbox ->
                            check0(Internals.codecCtxUseVideoToolbox(context), "videotoolbox device attach")
                        null -> Unit
                    }
                    check0(Internals.codecCtxOpen(context, codec), "avcodec_open2")
                    return StreamDecoder(stream, context, corruptData)
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
