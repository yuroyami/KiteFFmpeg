package io.github.yuroyami.kiteffmpeg

/** The JVM half of a [FilterGraph]: the graph's own calls over its JNI handle tokens. */
internal class JvmFilterBackend(
    private val graphToken: Long,
    private val sources: LongArray,
    private val sinkToken: Long,
    private val inputType: MediaType,
) : FilterBackend {

    override val inputCount: Int get() = sources.size

    override val outputTimeBase: Rational = Internals.graphTimeBase(sinkToken)

    /** Reusable landing frame for buffersink output; allocated on first use, freed in [free]. */
    private var landing: Frame? = null

    override fun setOutputFrameSize(samples: Int) {
        Internals.graphSetFrameSize(sinkToken, samples)
    }

    override fun send(index: Int, frame: Frame?): Int =
        if (frame == null) Internals.graphSend(sources[index], 0L)
        else frame.locked { Internals.graphSend(sources[index], it) }

    /**
     * The landing frame is released after every receive by closing the wrapper around it, and what
     * leaves is a clone: an O(1) reference the caller owns.
     */
    override fun receive(): Frame? {
        val into = landing ?: FrameOps.acquire(streamType = inputType, timeBase = outputTimeBase).also { landing = it }
        val rc = Internals.graphReceive(sinkToken, into.checkOpen())
        if (rc == Internals.errorEagain || rc == Internals.errorEof) return null
        if (rc < 0) throw FFmpegException(avError(rc))
        val view = FrameOps.wrap(into.checkOpen(), -1, inputType, outputTimeBase)
        try {
            return view.copy()
        } finally {
            view.close()
        }
    }

    override fun failedRequests(index: Int): Int = Internals.graphFailedRequests(sources[index])

    override fun isAgain(rc: Int): Boolean = rc == Internals.errorEagain
    override fun isEof(rc: Int): Boolean = rc == Internals.errorEof
    override fun error(rc: Int): FFmpegError = avError(rc)

    override fun free() {
        landing?.close()
        landing = null
        Internals.graphFree(graphToken)
    }
}

@Throws(FFmpegException::class)
internal actual fun buildVideoBackend(
    description: String,
    width: Int,
    height: Int,
    pixelFormat: PixelFormat,
    timeBase: Rational,
    frameRate: Rational,
    sampleAspectRatio: Rational,
): FilterBackend {
    Internals.requireCompatible()
    return fromTokens(
        Internals.graphBuildVideo(
            description,
            width,
            height,
            pixelFormatToAv(pixelFormat),
            timeBase,
            frameRate,
            sampleAspectRatio,
        ),
        1,
        MediaType.Video,
    )
}

@Throws(FFmpegException::class)
internal actual fun buildAudioBackend(
    description: String,
    sampleRate: Int,
    sampleFormat: SampleFormat,
    channels: Int,
    timeBase: Rational,
    outputSampleRate: Int,
    outputSampleFormat: SampleFormat,
    outputChannels: Int,
    channelLayoutMask: Long?,
    outputChannelLayoutMask: Long?,
): FilterBackend {
    Internals.requireCompatible()
    val outFormat = if (outputSampleFormat == SampleFormat.None) {
        -1
    } else sampleFormatToAv(outputSampleFormat)
    return fromTokens(
        Internals.graphBuildAudio(
            description,
            sampleRate,
            sampleFormatToAv(sampleFormat),
            channels,
            timeBase,
            outFormat,
            outputSampleRate,
            outputChannels,
            channelLayoutMask ?: 0L,
            outputChannelLayoutMask ?: 0L,
        ),
        1,
        MediaType.Audio,
    )
}

@Throws(FFmpegException::class)
internal actual fun buildVideoMultiBackend(description: String, inputs: List<VideoInput>): FilterBackend {
    Internals.requireCompatible()
        return fromTokens(
        Internals.graphBuildVideoMulti(
            description = description,
            count = inputs.size,
            widths = inputs.map { it.width }.toIntArray(),
            heights = inputs.map { it.height }.toIntArray(),
            formats = inputs.map { pixelFormatToAv(it.pixelFormat) }.toIntArray(),
            tbns = inputs.map { it.timeBase.num }.toIntArray(),
            tbds = inputs.map { it.timeBase.den }.toIntArray(),
            frns = inputs.map { it.frameRate.num }.toIntArray(),
            frds = inputs.map { it.frameRate.den }.toIntArray(),
            sarns = inputs.map { it.sampleAspectRatio.num }.toIntArray(),
            sards = inputs.map { it.sampleAspectRatio.den }.toIntArray(),
        ),
        inputs.size,
        MediaType.Video,
    )
}

@Throws(FFmpegException::class)
internal actual fun buildAudioMultiBackend(
    description: String,
    inputs: List<AudioInput>,
    outputSampleRate: Int,
    outputSampleFormat: SampleFormat,
    outputChannels: Int,
    outputChannelLayoutMask: Long?,
): FilterBackend {
    Internals.requireCompatible()
        val outFormat = if (outputSampleFormat == SampleFormat.None) {
        -1
    } else sampleFormatToAv(outputSampleFormat)
    return fromTokens(
        Internals.graphBuildAudioMulti(
            description = description,
            count = inputs.size,
            rates = inputs.map { it.sampleRate }.toIntArray(),
            formats = inputs.map { sampleFormatToAv(it.sampleFormat) }.toIntArray(),
            channels = inputs.map { it.channels }.toIntArray(),
            tbns = inputs.map { it.timeBase.num }.toIntArray(),
            tbds = inputs.map { it.timeBase.den }.toIntArray(),
            outFormat = outFormat,
            outRate = outputSampleRate,
            outChannels = outputChannels,
            layoutMasks = inputs.map { it.channelLayoutMask ?: 0L }.toLongArray(),
            outLayoutMask = outputChannelLayoutMask ?: 0L,
        ),
        inputs.size,
        MediaType.Audio,
    )
}

private fun fromTokens(tokens: LongArray, count: Int, type: MediaType): FilterBackend {
    if (tokens.size != count + 2 || tokens.any { it == 0L }) {
        tokens.firstOrNull()?.takeIf { it != 0L }?.let(Internals::graphFree)
        throw FFmpegException(FFmpegError.Internal("Malformed filter graph handle result"))
    }
    return JvmFilterBackend(
        graphToken = tokens[0],
        sources = tokens.copyOfRange(1, count + 1),
        sinkToken = tokens.last(),
        inputType = type,
    )
}
