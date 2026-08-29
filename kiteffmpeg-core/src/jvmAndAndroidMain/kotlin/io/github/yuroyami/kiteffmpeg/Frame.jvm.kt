package io.github.yuroyami.kiteffmpeg

public actual class Frame internal constructor(
    internal var token: Long,
    private val ownsToken: Boolean,
    internal val streamIndex: Int,
    internal val streamType: MediaType,
    internal val streamTimeBase: Rational,
) : AutoCloseable {
    /**
     * Guards the token for the full duration of every native call against a concurrent [close].
     * The JNI handle table only guarantees a non-torn lookup; it does not keep the native object
     * alive across an operation, so the mutual exclusion has to happen here, at the boundary where
     * both the operation and the close enter native code.
     */
    internal val lock: Any = Any()

    internal fun checkOpen(): Long {
        check(token != 0L) { "Frame is closed, its native buffers are gone" }
        return token
    }

    /** Run [block] with the open token while holding [lock], excluding a concurrent [close]. */
    internal inline fun <R> locked(block: (Long) -> R): R = synchronized(lock) { block(checkOpen()) }

    private var cachedInfo: FrameInfo? = null
    public actual val info: FrameInfo
        get() = locked { open ->
            cachedInfo ?: readInfo(open).also { cachedInfo = it }
        }

    private fun readInfo(open: Long): FrameInfo = FrameInfo(
        streamIndex = streamIndex,
        type = streamType,
        pts = Internals.framePts(open),
        timeBase = streamTimeBase,
        width = Internals.frameWidth(open),
        height = Internals.frameHeight(open),
        pixelFormat = if (streamType == MediaType.Video) {
            pixelFormatFromAv(Internals.frameFormat(open))
        } else PixelFormat.None,
        sampleCount = Internals.frameSampleCount(open),
        sampleRate = Internals.frameSampleRate(open),
        channelCount = if (streamType == MediaType.Audio) Internals.frameChannels(open) else 0,
        sampleFormat = if (streamType == MediaType.Audio) {
            sampleFormatFromAv(Internals.frameFormat(open))
        } else SampleFormat.None,
        channelLayoutMask = if (streamType == MediaType.Audio) {
            Internals.frameChannelLayout(open).takeIf { it != 0L }
        } else null,
        duration = Internals.frameDuration(open),
        isKeyframe = Internals.frameIsKeyframe(open),
        color = if (streamType == MediaType.Video) readColorInfo(open) else ColorInfo.Unspecified,
        sampleAspectRatio = if (streamType == MediaType.Video) {
            Internals.frameSampleAspectRatio(open).let { if (it.num == 0) Rational(1, 1) else it }
        } else Rational(1, 1),
        isHardware = Internals.frameIsHardware(open),
    )

    private fun readColorInfo(open: Long): ColorInfo {
        val range = Internals.frameColorRange(open)
        val declared = ColorInfo(
            matrix = ColorMatrix.fromAv(Internals.frameColorSpace(open)),
            primaries = ColorPrimaries.fromAv(Internals.frameColorPrimaries(open)),
            transfer = ColorTransfer.fromAv(Internals.frameColorTransfer(open)),
            fullRange = range == 2,
            chromaLocation = ChromaLocation.fromAv(Internals.frameChromaLocation(open)),
            rangeSpecified = range == 1 || range == 2,
        )
        val guessed = ColorInfo.guessFor(Internals.frameHeight(open))
        return declared.copy(
            matrix = declared.matrix.takeUnless { it == ColorMatrix.Unspecified } ?: guessed.matrix,
            primaries = declared.primaries.takeUnless { it == ColorPrimaries.Unspecified } ?: guessed.primaries,
            transfer = declared.transfer.takeUnless { it == ColorTransfer.Unspecified } ?: guessed.transfer,
        )
    }

    @Throws(FFmpegException::class)
    public actual fun copyPlanesToByteArray(): ByteArray = locked { Internals.frameCopyPlanes(it) }

    @Throws(FFmpegException::class)
    public actual fun copy(): Frame = locked { open ->
        Frame(
            token = Internals.frameClone(open),
            ownsToken = true,
            streamIndex = streamIndex,
            streamType = streamType,
            streamTimeBase = streamTimeBase,
        )
    }

    @Throws(FFmpegException::class)
    public actual fun downloadFromHardware(): Frame = locked { source ->
        val downloaded = Internals.frameAlloc()
        val rc = Internals.frameHwDownload(source, downloaded)
        if (rc < 0) {
            Internals.frameFree(downloaded)
            throw FFmpegException(avError(rc))
        }
        Frame(
            token = downloaded,
            ownsToken = true,
            streamIndex = streamIndex,
            streamType = streamType,
            streamTimeBase = streamTimeBase,
        )
    }

    @Throws(FFmpegException::class)
    public actual fun encodeImage(codec: CodecId): ByteArray = locked { source ->
        encodeImageLocked(source, codec)
    }

    private fun encodeImageLocked(source: Long, codec: CodecId): ByteArray {
        if (streamType != MediaType.Video) {
            throw FFmpegException(
                FFmpegError.Internal("encodeImage works on video frames, this is a $streamType frame"),
            )
        }
        val width = Internals.frameWidth(source)
        val height = Internals.frameHeight(source)
        val format = Internals.frameFormat(source)
        if (width <= 0 || height <= 0 || format < 0) {
            throw FFmpegException(FFmpegError.Internal("Frame carries no image data"))
        }

        val encoder = Internals.findEncoderByName(codec.name)
        if (encoder == 0L) {
            throw FFmpegException(FFmpegError.EncoderNotFound(0, "No encoder named '${codec.name}'"))
        }
        var converted = 0L
        try {
            val sendFrame = if (!Internals.codecSupportsPixelFormat(encoder, format)) {
                val target = Internals.codecFirstPixelFormat(encoder)
                if (target < 0) {
                    throw FFmpegException(
                        FFmpegError.Internal("Encoder '${codec.name}' advertises no pixel formats"),
                    )
                }
                Internals.frameConvert(source, target).also { converted = it }
            } else source

            val context = Internals.codecCtxAlloc(encoder)
            try {
                Internals.codecCtxSetVideo(
                    context = context,
                    width = width,
                    height = height,
                    format = Internals.frameFormat(sendFrame),
                    frameRate = Rational(25, 1),
                    timeBase = Rational(1, 25),
                    bitrate = 8_000_000,
                    gop = 1,
                )
                Internals.codecCtxFullRange(context)
                check0(Internals.codecCtxOpen(context, encoder), "avcodec_open2 (image encoder)")
                val packet = Internals.packetAlloc()
                try {
                    val originalPts = Internals.framePts(sendFrame)
                    try {
                        Internals.frameSetPts(sendFrame, 0L)
                        var bytes: ByteArray? = null
                        fun drainAll() {
                            while (true) {
                                val rc = Internals.codecCtxReceivePacket(context, packet)
                                if (rc == Internals.errorEagain || rc == Internals.errorEof) return
                                check0(rc, "avcodec_receive_packet (image)")
                                if (bytes == null) bytes = Internals.packetBytes(packet)
                                Internals.packetUnref(packet)
                            }
                        }
                        while (true) {
                            val rc = Internals.codecCtxSendFrame(context, sendFrame)
                            if (rc == 0) break
                            if (rc == Internals.errorEagain) {
                                drainAll()
                                continue
                            }
                            check0(rc, "avcodec_send_frame (image)")
                        }
                        while (true) {
                            val rc = Internals.codecCtxSendFrame(context, 0L)
                            if (rc == 0 || rc == Internals.errorEof) break
                            if (rc == Internals.errorEagain) {
                                drainAll()
                                continue
                            }
                            check0(rc, "avcodec_send_frame (image flush)")
                        }
                        drainAll()
                        return bytes
                            ?: throw FFmpegException(FFmpegError.Internal("Image encoder produced no packet"))
                    } finally {
                        Internals.frameSetPts(sendFrame, originalPts)
                    }
                } finally {
                    Internals.packetFree(packet)
                }
            } finally {
                Internals.codecCtxFree(context)
            }
        } finally {
            if (converted != 0L) Internals.frameFree(converted)
            Internals.codecRelease(encoder)
        }
    }

    actual override fun close() {
        synchronized(lock) {
            val owned = token
            if (owned == 0L) return
            token = 0L
            if (ownsToken) Internals.frameFree(owned) else Internals.frameUnref(owned)
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
            Internals.requireCompatible()
            require(width > 0 && height > 0) { "Invalid dimensions ${width}x$height" }
            val format = pixelFormatToAv(pixelFormat)
            if (format < 0) {
                throw FFmpegException(FFmpegError.Internal("Unknown pixel format '${pixelFormat.name}'"))
            }
            val token = Internals.frameAlloc()
            try {
                Internals.frameSetWidth(token, width)
                Internals.frameSetHeight(token, height)
                Internals.frameSetFormat(token, format)
                check0(Internals.frameGetBuffer(token, 0), "av_frame_get_buffer (video)")
                check0(
                    Internals.frameFillVideo(token, bytes.copyOf()),
                    "frame_fill_video (need packed ${pixelFormat.name} planes for ${width}x$height)",
                )
                Internals.frameSetPts(token, ptsMicros)
                return Frame(token, true, -1, MediaType.Video, Rational.Tb_us)
            } catch (error: Throwable) {
                Internals.frameFree(token)
                throw error
            }
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
            Internals.requireCompatible()
            require(sampleCount > 0) { "sampleCount must be positive" }
            require(sampleRate > 0) { "sampleRate must be positive" }
            require(channels in 1..8) { "channels must be 1..8 (got $channels)" }
            val format = sampleFormatToAv(sampleFormat)
            if (format < 0) {
                throw FFmpegException(FFmpegError.Internal("Unknown sample format '${sampleFormat.name}'"))
            }
            val token = Internals.frameAlloc()
            try {
                Internals.frameSetSampleCount(token, sampleCount)
                Internals.frameSetSampleRate(token, sampleRate)
                Internals.frameSetFormat(token, format)
                Internals.frameSetChannels(token, channels)
                check0(Internals.frameGetBuffer(token, 0), "av_frame_get_buffer (audio)")
                check0(
                    Internals.frameFillAudio(token, bytes.copyOf()),
                    "frame_fill_audio (need $sampleCount ${sampleFormat.name} samples x $channels ch)",
                )
                Internals.frameSetPts(token, ptsMicros)
                return Frame(token, true, -1, MediaType.Audio, Rational.Tb_us)
            } catch (error: Throwable) {
                Internals.frameFree(token)
                throw error
            }
        }
    }
}

internal object FrameOps {
    fun acquire(
        streamIndex: Int = -1,
        streamType: MediaType = MediaType.Video,
        timeBase: Rational = Rational.Tb_us,
    ): Frame = Frame(Internals.frameAlloc(), true, streamIndex, streamType, timeBase)

    fun wrap(token: Long, streamIndex: Int, streamType: MediaType, timeBase: Rational): Frame =
        Frame(token, false, streamIndex, streamType, timeBase)
}
