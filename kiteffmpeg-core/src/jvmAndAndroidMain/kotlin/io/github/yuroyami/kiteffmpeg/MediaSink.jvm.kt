package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.Flow

public actual class MediaSink internal constructor(
    private var formatToken: Long,
    private val outputPath: String,
) : AutoCloseable {
    private enum class HeaderState { NotWritten, Written, Failed }

    private val muxLock = Any()
    private val encoderCores = mutableListOf<EncoderCore>()
    private var headerState = HeaderState.NotWritten
    private var sharedBaseMicros = Long.MIN_VALUE

    /**
     * True from the moment a close begins until the sink dies, read and written under [muxLock].
     *
     * The close flushes its encoders OUTSIDE the lock, because the flush takes each encoder's own
     * lock and holding both invites an inversion. That leaves a window where the sink looks open:
     * a second close could start its own trailer, and an add could append an encoder the snapshot
     * had already passed, leaking it. This flag is what the window checks.
     */
    private var closing = false

    /** Streams declared on this sink; close() owes a real container once this is nonzero. */
    private var declaredStreams = 0

    /**
     * The failure that made this sink unusable, if one has.
     *
     * `avformat_new_stream` MUTATES the format context, and it runs before the setup that can
     * still fail. A refused encoder therefore used to leave a real, half-configured stream inside
     * the muxer and hand the caller back an ordinary-looking sink: adding another stream on top of
     * it, or writing a header over it, produces a container whose stream table does not describe
     * its contents. There is no way to take that stream back out, so the sink is finished, and
     * saying so is the only honest answer.
     */
    private var failure: Throwable? = null

    private fun checkOpen(): Long {
        check(formatToken != 0L) { "MediaSink is closed" }
        check(!closing) { "MediaSink is closing" }
        failure?.let {
            throw IllegalStateException(
                "This MediaSink failed while a stream was being added and cannot be used again. " +
                    "Its muxer already holds a stream that could not be configured, and FFmpeg " +
                    "offers no way to remove one. Close it, delete the output, and open a new " +
                    "sink. The original failure is attached.",
                it,
            )
        }
        return formatToken
    }

    /**
     * Records a failure that struck AFTER the muxer had been mutated, and rethrows it.
     *
     * The caller still gets its own typed error; what changes is that the sink stops pretending to
     * be usable afterwards.
     */
    private fun poison(error: Throwable): Nothing {
        if (failure == null) failure = error
        throw error
    }

    internal fun claimBaseMicros(candidate: Long): Long = synchronized(muxLock) {
        if (sharedBaseMicros == Long.MIN_VALUE) sharedBaseMicros = candidate
        sharedBaseMicros
    }

    /** Runs [block] under the mux lock so a concurrent [close] cannot free the format mid-call. */
    internal fun <T> withMuxLock(block: () -> T): T = synchronized(muxLock) { block() }

    private val headerWritten: Boolean
        get() = synchronized(muxLock) { headerState == HeaderState.Written }

    @Throws(FFmpegException::class)
    public actual fun addVideoEncoder(spec: VideoEncoderSpec): VideoEncoder = synchronized(muxLock) {
        val context = newEncoderContext(spec.codec.name) { _, codecContext ->
            Internals.codecCtxSetVideo(
                codecContext,
                spec.width,
                spec.height,
                pixelFormatToAv(spec.pixelFormat),
                spec.frameRate,
                spec.frameRate.inverse,
                spec.bitrateBps,
                spec.keyframeIntervalFrames,
            )
            spec.options.forEach { (key, value) ->
                check0(Internals.codecCtxSetOpt(codecContext, key, value), "av_opt_set ('$key')")
            }
        }
        val stream = newStreamFor(context)
        val core = EncoderCore(this, context, stream, Internals.codecCtxTimeBase(context), false)
        encoderCores += core
        VideoEncoder(core)
    }

    @Throws(FFmpegException::class)
    public actual fun addAudioEncoder(spec: AudioEncoderSpec): AudioEncoder = synchronized(muxLock) {
        var negotiated = spec.sampleFormat
        val context = newEncoderContext(spec.codec.name) { codec, codecContext ->
            if (negotiated == SampleFormat.None) {
                val first = Internals.codecFirstSampleFormat(codec)
                if (first < 0) {
                    throw FFmpegException(
                        FFmpegError.Internal(
                            "Encoder '${spec.codec.name}' does not advertise sample formats; " +
                                "set AudioEncoderSpec.sampleFormat explicitly",
                        ),
                    )
                }
                negotiated = sampleFormatFromAv(first)
            }
            Internals.codecCtxSetAudio(
                codecContext,
                spec.sampleRate,
                sampleFormatToAv(negotiated),
                spec.channels,
                spec.bitrateBps,
            )
            spec.options.forEach { (key, value) ->
                check0(Internals.codecCtxSetOpt(codecContext, key, value), "av_opt_set ('$key')")
            }
        }
        val stream = newStreamFor(context)
        val core = EncoderCore(this, context, stream, Internals.codecCtxTimeBase(context), true)
        encoderCores += core
        AudioEncoder(
            core,
            Internals.codecCtxFrameSize(context),
            negotiated,
            Internals.codecCtxSampleRate(context).takeIf { it > 0 } ?: spec.sampleRate,
            Internals.codecCtxChannels(context).takeIf { it > 0 } ?: spec.channels,
        )
    }

    @Throws(FFmpegException::class)
    public actual fun addCopyStream(source: MediaSource, stream: StreamInfo): CopyStream = synchronized(muxLock) {
        check(!headerWritten) { "Cannot add streams after the muxer has started writing." }
        val format = checkOpen()
        source.withCodecParameters(stream) { sourceParameters ->
            val outputStream = Internals.fmtNewStream(format)
            var outputParameters = 0L
            try {
                // KC-EVIDENCE-MUX: see the native twin. Inert unless a test armed it.
                MuxFaults.failIfArmed("addCopyStream/jvm")
                outputParameters = Internals.streamCodecPar(outputStream)
                check0(
                    Internals.codecParCopy(outputParameters, sourceParameters),
                    "avcodec_parameters_copy",
                )
                Internals.streamSetTimeBase(outputStream, stream.timeBase)
                declaredStreams += 1
                CopyStream(this, outputStream, stream.timeBase, stream.index)
            } catch (error: Throwable) {
                Internals.borrowedRelease(outputStream, Internals.KIND_STREAM)
                // fmtNewStream above already put a stream into the format context, and FFmpeg offers
                // no way to take one back. Releasing the borrowed reference does not undo that, so
                // without the poison the sink kept looking usable while its muxer held a stream that
                // was never configured. newStreamFor has poisoned since P1-10; this path, which
                // mutates exactly the same way, was left out.
                poison(error)
            } finally {
                if (outputParameters != 0L) {
                    Internals.borrowedRelease(outputParameters, Internals.KIND_CODEC_PAR)
                }
            }
        }
    }

    private inline fun newEncoderContext(
        codecName: String,
        configure: (codec: Long, context: Long) -> Unit,
    ): Long {
        check(!headerWritten) { "Cannot add encoders after the muxer has started writing." }
        checkOpen()
        val codec = Internals.findEncoderByName(codecName)
        if (codec == 0L) throw FFmpegException(FFmpegError.EncoderNotFound(0, "No encoder named '$codecName'"))
        try {
            val context = Internals.codecCtxAlloc(codec)
            try {
                configure(codec, context)
                if (Internals.fmtGlobalHeader(checkOpen())) Internals.codecCtxGlobalHeader(context)
                check0(Internals.codecCtxOpen(context, codec), "avcodec_open2 (encoder)")
                return context
            } catch (error: Throwable) {
                Internals.codecCtxFree(context)
                throw error
            }
        } finally {
            Internals.codecRelease(codec)
        }
    }

    private fun newStreamFor(context: Long): Long {
        var stream = 0L
        var parameters = 0L
        var mutated = false
        try {
            stream = Internals.fmtNewStream(checkOpen())
            // From here the format context HAS a new stream in it, and nothing can take it back.
            mutated = true
            parameters = Internals.streamCodecPar(stream)
            check0(Internals.codecParFromContext(parameters, context), "avcodec_parameters_from_context")
            Internals.streamSetTimeBase(stream, Internals.codecCtxTimeBase(context))
            declaredStreams += 1
            return stream
        } catch (error: Throwable) {
            if (stream != 0L) Internals.borrowedRelease(stream, Internals.KIND_STREAM)
            Internals.codecCtxFree(context)
            // `parameters` is released by the finally below on every path, this one included.
            if (mutated) poison(error)
            throw error
        } finally {
            if (parameters != 0L) Internals.borrowedRelease(parameters, Internals.KIND_CODEC_PAR)
        }
    }

    @Throws(FFmpegException::class)
    public actual fun setMetadata(metadata: Map<String, String>): Unit = synchronized(muxLock) {
        check(!headerWritten) { "Metadata must be set before the muxer writes its header." }
        val format = checkOpen()
        metadata.forEach { (key, value) ->
            check0(Internals.fmtSetMetadata(format, key, value), "av_dict_set (metadata '$key')")
        }
    }

    internal fun ensureHeaderWritten(): Unit = synchronized(muxLock) {
        // Deliberately NOT checkOpen(): the closer itself calls this to write a header it still
        // owes, after closing is already true. The token check alone is the right gate here.
        check(formatToken != 0L) { "MediaSink is closed" }
        when (headerState) {
            HeaderState.Written -> return
            HeaderState.Failed -> throw FFmpegException(
                FFmpegError.Internal("The muxer header failed to write earlier, so this sink is unusable"),
            )
            HeaderState.NotWritten -> Unit
        }
        headerState = HeaderState.Failed
        val format = formatToken
        check0(Internals.fmtIoOpen(format, outputPath), "avio_open")
        check0(Internals.fmtWriteHeader(format), "avformat_write_header")
        headerState = HeaderState.Written
    }

    internal fun writePacket(packet: Long): Unit = synchronized(muxLock) {
        // The token gate, not checkOpen: writes stay legal while the sink is CLOSING, because the
        // close's own encoder flush is writes, through this very function. What a write may never
        // do is run after the trailer section zeroed the token, and that is what this refuses.
        check(formatToken != 0L) { "MediaSink is closed" }
        ensureHeaderWritten()
        val rc = Internals.fmtWriteFrame(formatToken, packet)
        if (rc < 0) throw FFmpegException(avError(rc))
    }

    actual override fun close() {
        // Cores are finished and closed BEFORE muxLock is taken for the trailer. An encode on
        // another thread holds core.lock and then takes muxLock to write its packets, so closing
        // cores from inside muxLock would invert that order and deadlock. finish/close below take
        // each core's own lock, which also serializes against any in-flight encode.
        val cores = synchronized(muxLock) {
            // One closer only. The first close owns the flush and the trailer; a second, from any
            // thread, returns and lets it finish rather than writing a second trailer or freeing
            // the muxer under the first one's flush.
            if (formatToken == 0L || closing) return
            closing = true
            encoderCores.toList().also { encoderCores.clear() }
        }
        // The FIRST flush error is retained and thrown after cleanup: tail frames
        // lost while close reports success is a silent truncation, and one encoder failing is
        // not a reason to skip draining the others or the trailer for what did land.
        var firstFailure: Throwable? = null
        try {
            if (cores.isNotEmpty()) {
                withPacket { packet ->
                    cores.forEach { core ->
                        runCatching { core.finish(packet) }.exceptionOrNull()?.let { failure ->
                            if (firstFailure == null) firstFailure = failure
                            else firstFailure?.addSuppressed(failure)
                        }
                    }
                }
            }
        } finally {
            cores.forEach { runCatching { it.close() } }
        }
        var closeRc = 0
        val trailerRc = synchronized(muxLock) {
            val format = formatToken
            if (format == 0L) return
            var result = 0
            try {
                // Declared streams and no packet means the header never wrote itself on demand;
                // the sink still owes a real container or an explicit failure.
                if (headerState == HeaderState.NotWritten && declaredStreams > 0) {
                    runCatching { ensureHeaderWritten() }.exceptionOrNull()?.let { failure ->
                        if (firstFailure == null) firstFailure = failure
                        else firstFailure?.addSuppressed(failure)
                    }
                }
                if (headerState == HeaderState.Written) result = Internals.fmtWriteTrailer(format)
            } finally {
                formatToken = 0L
                // The output file's own close, which is the last thing that can fail and the place
                // a full disk reports itself. Kept and raised below rather than discarded, so a
                // truncated file is not reported as a written one.
                closeRc = Internals.fmtFreeOutput(format)
            }
            result
        }
        firstFailure?.let { throw it }
        if (trailerRc < 0) throw FFmpegException(avError(trailerRc))
        if (closeRc < 0) throw FFmpegException(avError(closeRc))
    }

    public actual companion object {
        @Throws(FFmpegException::class)
        public actual fun open(path: String, format: String?, options: Map<String, String>): MediaSink {
            Internals.requireCompatible()
            val token = Internals.fmtAllocOutput(path, format)
            try {
                Internals.fmtAvoidNegativeTs(token)
                options.forEach { (key, value) ->
                    check0(Internals.fmtSetOpt(token, key, value), "av_opt_set (muxer option '$key')")
                }
                return MediaSink(token, path)
            } catch (error: Throwable) {
                Internals.fmtFreeOutput(token)
                throw error
            }
        }
    }
}

internal class EncoderCore(
    private val sink: MediaSink,
    private var codecContext: Long,
    private var streamToken: Long,
    private val codecTimeBase: Rational,
    private val audio: Boolean,
) {
    private val streamIndex = Internals.streamIndex(streamToken)
    private var lastPts = Long.MIN_VALUE
    private var basePts = Long.MIN_VALUE
    private var lastSampleCount = 0

    // Excludes MediaSink.close() (which finishes and closes cores) while an encode is inside
    // native code. Lock order is core → frame → muxLock; MediaSink.close honors it by closing
    // cores before taking muxLock for the trailer.
    private val lock = Any()

    var framesEncoded: Long = 0L
        private set

    /**
     * Where this encoder is in its one-way life.
     *
     * An encoder that has been driven to its end has flushed its codec and written its last
     * packet. Offering it a second flow used to look like it worked: every frame was consumed and
     * closed, `framesEncoded` went up, and NOTHING was written, because the codec was already
     * drained. The caller got a silent no-op and a file missing everything it thought it had
     * added.
     */
    private var driving = false
    private var drained = false

    fun beginDrive() = synchronized(lock) {
        check(codecContext != 0L) { "Encoder is closed" }
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
    fun endDrive() = synchronized(lock) {
        drained = true
    }

    /** Releases the one-drive-at-a-time guard, on every path including failure. */
    fun releaseDrive() = synchronized(lock) {
        driving = false
    }

    // The last frame's own extent is included, mirroring the native side: measuring only its
    // start left successful work finishing below 100 percent.
    val outputMicros: Long
        get() = if (lastPts == Long.MIN_VALUE) 0L
        else Internals.rescaleQ(lastPts + stepPastLastPts(), codecTimeBase, Rational.Tb_us)

    fun encode(packet: Long, frame: Frame): Unit = synchronized(lock) {
        check(codecContext != 0L) { "Encoder is closed" }
        check(!drained) { "This encoder was already drained; its codec cannot accept more frames" }
        try {
            // The media type this encoder was built for. A video frame handed to an audio encoder
            // reached FFmpeg and was interpreted as samples, which is a wrong answer rather than a
            // refusal. Native guards this in its own encode; the JVM half was missed and the row
            // was closed on the strength of the other one.
            val wanted = if (audio) MediaType.Audio else MediaType.Video
            require(frame.info.type == wanted) {
                "this encoder encodes $wanted and was given a ${frame.info.type} frame"
            }
            frame.locked { source ->
                restampPts(frame, source)
                val converted = if (audio) 0L else conversionFor(source)
                if (converted != 0L) {
                    try {
                        sendAndDrain(packet, converted)
                    } finally {
                        Internals.frameFree(converted)
                    }
                } else {
                    sendAndDrain(packet, source)
                }
            }
        } finally {
            frame.close()
        }
        framesEncoded += 1
    }

    private fun conversionFor(frame: Long): Long {
        val frameWidth = Internals.frameWidth(frame)
        val frameHeight = Internals.frameHeight(frame)
        val encoderWidth = Internals.codecCtxWidth(codecContext)
        val encoderHeight = Internals.codecCtxHeight(codecContext)
        if (frameWidth > 0 && frameHeight > 0 &&
            (frameWidth != encoderWidth || frameHeight != encoderHeight)
        ) {
            throw FFmpegException(
                FFmpegError.InvalidArgument(
                    0,
                    "Frame is ${frameWidth}x$frameHeight but the encoder was opened for " +
                        "${encoderWidth}x$encoderHeight. Match the encoder dimensions or add scale=.",
                ),
            )
        }
        val wanted = Internals.codecCtxPixelFormat(codecContext)
        val actual = Internals.frameFormat(frame)
        if (wanted < 0 || actual < 0 || wanted == actual) return 0L
        return Internals.frameConvert(frame, wanted)
    }

    fun finish(packet: Long): Unit = synchronized(lock) {
        // A closed core has nothing left to flush: MediaSink.close finishes every core it ever
        // created, including ones the caller already drove to completion and closed, and that
        // must stay a no-op rather than an error mistaken for a lost tail.
        if (codecContext == 0L || drained) return
        sendAndDrain(packet, 0L)
    }

    private fun restampPts(frame: Frame, token: Long) {
        val raw = Internals.framePts(token)
        val sampleCount = if (audio) Internals.frameSampleCount(token) else 0
        var pts = if (raw == FrameInfo.NOPTS) {
            if (lastPts == Long.MIN_VALUE) 0L else lastPts + stepPastLastPts()
        } else {
            val rescaled = Internals.rescaleQ(raw, frame.streamTimeBase, codecTimeBase)
            if (basePts == Long.MIN_VALUE) {
                val rawMicros = Internals.rescaleQ(raw, frame.streamTimeBase, Rational.Tb_us)
                basePts = Internals.rescaleQ(sink.claimBaseMicros(rawMicros), Rational.Tb_us, codecTimeBase)
            }
            rescaled - basePts
        }
        if (lastPts != Long.MIN_VALUE && pts <= lastPts) pts = lastPts + stepPastLastPts()
        lastPts = pts
        lastSampleCount = sampleCount
        Internals.frameSetPts(token, pts)
    }

    private fun stepPastLastPts(): Long = if (audio) lastSampleCount.toLong().coerceAtLeast(1L) else 1L

    private fun sendAndDrain(packet: Long, frame: Long) {
        while (true) {
            val rc = Internals.codecCtxSendFrame(codecContext, frame)
            when (rc) {
                0, Internals.errorEof -> {
                    drain(packet)
                    return
                }
                Internals.errorEagain -> drain(packet)
                else -> throw FFmpegException(avError(rc))
            }
        }
    }

    private fun drain(packet: Long) {
        while (true) {
            val rc = Internals.codecCtxReceivePacket(codecContext, packet)
            if (rc == Internals.errorEagain || rc == Internals.errorEof) return
            if (rc < 0) throw FFmpegException(avError(rc))
            sink.ensureHeaderWritten()
            Internals.packetSetStreamIndex(packet, streamIndex)
            Internals.packetRescale(packet, codecTimeBase, Internals.streamTimeBase(streamToken))
            try {
                sink.writePacket(packet)
            } finally {
                Internals.packetUnref(packet)
            }
        }
    }

    fun ensureHeaderWritten() = sink.ensureHeaderWritten()

    fun close() {
        synchronized(lock) {
            val context = codecContext
            if (context == 0L) return
            codecContext = 0L
            Internals.codecCtxFree(context)
            val stream = streamToken
            streamToken = 0L
            if (stream != 0L) Internals.borrowedRelease(stream, Internals.KIND_STREAM)
        }
    }
}

internal inline fun <T> withPacket(block: (Long) -> T): T {
    val packet = Internals.packetAlloc()
    try {
        return block(packet)
    } finally {
        Internals.packetFree(packet)
    }
}

public actual class CopyStream internal constructor(
    private val sink: MediaSink,
    private var streamToken: Long,
    private val sourceTimeBase: Rational,
    internal val sourceIndex: Int,
) {
    private val streamIndex = Internals.streamIndex(streamToken)
    private var baseTimestamp = FrameInfo.NOPTS

    internal fun writeCopyPacket(packet: Long): Unit = sink.withMuxLock {
        check(streamToken != 0L) { "CopyStream is closed with its MediaSink" }
        sink.ensureHeaderWritten()
        Internals.packetSetStreamIndex(packet, streamIndex)
        val pts = Internals.packetPts(packet)
        val dts = Internals.packetDts(packet)
        if (baseTimestamp == FrameInfo.NOPTS) {
            val reference = when {
                dts != FrameInfo.NOPTS -> dts
                pts != FrameInfo.NOPTS -> pts
                else -> 0L
            }
            val micros = Internals.rescaleQ(reference, sourceTimeBase, Rational.Tb_us)
            baseTimestamp = Internals.rescaleQ(
                sink.claimBaseMicros(micros),
                Rational.Tb_us,
                sourceTimeBase,
            )
        }
        if (pts != FrameInfo.NOPTS) Internals.packetSetPts(packet, pts - baseTimestamp)
        if (dts != FrameInfo.NOPTS) Internals.packetSetDts(packet, dts - baseTimestamp)
        Internals.packetRescale(packet, sourceTimeBase, Internals.streamTimeBase(streamToken))
        sink.writePacket(packet)
    }
}

public actual class VideoEncoder internal constructor(internal val core: EncoderCore) : AutoCloseable {
    public actual suspend fun drive(
        input: Flow<Frame>,
        onProgress: ((framesEncoded: Long) -> Unit)?,
        progressEveryNFrames: Int,
    ) {
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
                input.collect { frame -> core.encode(packet, frame) }
                core.finish(packet)
            }
            core.endDrive()
        } finally {
            core.releaseDrive()
        }
    }

    actual override fun close(): Unit = core.close()
}
