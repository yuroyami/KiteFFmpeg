package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.firstOrNull

/** [ColorInfo] as the five values an encoder takes: primaries, transfer, matrix, range, chroma location. */
internal fun ColorInfo.encoderValues(): IntArray = intArrayOf(
    primaries.avValue,
    transfer.avValue,
    matrix.avValue,
    // AVCOL_RANGE_MPEG is 1 and AVCOL_RANGE_JPEG is 2; 0 declares no range.
    when {
        fullRange -> 2
        rangeSpecified -> 1
        else -> 0
    },
    chromaLocation.avValue,
)

/** Decoded frames the peek reads at most before it settles for the stream's own declaration. */
private const val PEEK_FRAME_LIMIT = 250

/**
 * The first frame the encoder will receive: the first frame of [stream] at or after [startMicros],
 * decoded from a second open of [input] and run through [videoFilter] when there is one. Null when
 * nothing comes out within [PEEK_FRAME_LIMIT] decoded frames.
 */
internal suspend fun firstEncodedFrameInfo(
    input: String,
    stream: StreamInfo,
    videoFilter: String?,
    startMicros: Long,
): FrameInfo? = MediaSource.open(input).use { source ->
    val video = source.streams.firstOrNull { it.index == stream.index } ?: return null
    if (startMicros > 0) source.seekMicros(startMicros)
    val trim = TrimWindow(startMicros, Long.MAX_VALUE, source.startTimeMicros)
    var graph: FilterGraph? = null
    var found: FrameInfo? = null
    var decoded = 0
    try {
        // Every frame the predicate sees is closed here or by the graph, including the last one.
        source.decodedFrames(video).firstOrNull { frame ->
            decoded += 1
            val info = frame.info
            when {
                decoded > PEEK_FRAME_LIMIT -> frame.close().let { true }
                trim.startsBeforeStart(frame) -> frame.close().let { false }
                videoFilter == null -> {
                    found = info
                    frame.close()
                    true
                }
                else -> {
                    val filter = graph ?: FilterGraph.buildVideo(
                        description = videoFilter,
                        width = info.width,
                        height = info.height,
                        pixelFormat = info.pixelFormat,
                        timeBase = video.timeBase,
                        frameRate = video.video?.frameRate ?: Rational(25, 1),
                        sampleAspectRatio = info.sampleAspectRatio,
                    ).also { graph = it }
                    filter.feedInput(0, frame) { out -> if (found == null) found = out.info }
                    found != null
                }
            }
        }
        if (found == null) graph?.flushInput(0) { out -> if (found == null) found = out.info }
    } finally {
        graph?.close()
    }
    found
}

/**
 * This spec with the colour, pixel shape and HDR metadata it leaves null copied from [first], the
 * first frame the encoder receives, or from [stream] when there is no such frame. Only what the
 * source declares is copied: a guessed colour field stays unset, and so does a square pixel. A
 * field a raw option already sets is left alone, because the option guard would refuse both.
 */
internal fun VideoEncoderSpec.inheriting(first: FrameInfo?, stream: StreamInfo): VideoEncoderSpec {
    val video = stream.video
    val sourceColor = (first?.color ?: video?.color)?.withoutGuesses()?.takeUnless { it.isUnspecified }
    val sourceShape = (first?.sampleAspectRatio ?: video?.sampleAspectRatio)?.takeUnless { it == Rational(1, 1) }
    // A frame is the authority once there is one: a filter that drops the HDR metadata, such as
    // a tone mapper, must not have it put back from the container.
    val sourceHdr = if (first != null) first.hdr else video?.hdr
    return copy(
        color = color ?: sourceColor.takeUnless { options.keys.any { it in COLOR_OPTION_KEYS } },
        sampleAspectRatio = sampleAspectRatio ?: sourceShape.takeUnless { SAR_OPTION_KEY in options },
        hdr = hdr ?: sourceHdr,
    )
}

/** True when [inheriting] has a field to fill, so the peek is worth a second open. */
internal val VideoEncoderSpec.inheritsAnything: Boolean
    get() = (color == null && options.keys.none { it in COLOR_OPTION_KEYS }) ||
        (sampleAspectRatio == null && SAR_OPTION_KEY !in options) ||
        hdr == null

/**
 * This spec with the channel layout it leaves null copied from [stream], when the stream has as
 * many channels as the spec asks for. A raw `ch_layout` option is left alone.
 */
internal fun AudioEncoderSpec.inheriting(stream: StreamInfo?): AudioEncoderSpec {
    if (channelLayoutMask != null || LAYOUT_OPTION_KEY in options) return this
    val mask = stream?.audio?.channelLayoutMask ?: return this
    return if (mask.countOneBits() == channels) copy(channelLayoutMask = mask) else this
}

/** Refuses a [AudioEncoderSpec.channelLayoutMask] that is not a layout of [AudioEncoderSpec.channels] channels. */
internal fun requireLayoutMatchesChannels(spec: AudioEncoderSpec) {
    val mask = spec.channelLayoutMask ?: return
    if (mask > 0 && mask.countOneBits() == spec.channels) return
    throw FFmpegException(
        FFmpegError.InvalidArgument(
            0,
            "AudioEncoderSpec.channelLayoutMask 0x${mask.toString(16)} names ${mask.countOneBits()} " +
                "channels, but channels is ${spec.channels}.",
        ),
    )
}
