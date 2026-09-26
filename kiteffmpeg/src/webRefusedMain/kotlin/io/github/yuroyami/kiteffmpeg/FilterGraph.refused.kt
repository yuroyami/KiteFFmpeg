package io.github.yuroyami.kiteffmpeg

// The web backends carry no filter graph: every build refuses, so no FilterGraph exists here.

internal actual val filterGraphRefusal: String? =
    "KiteFFmpeg builds no filter graph on the web. Decoding works there; filtering and encoding are not offered."

/** Every filter graph build on the web, with the same reason [filterGraphRefusal] gives. */
private fun refuseFilterGraph(operation: String): Nothing = throw FFmpegException(
    FFmpegError.Unsupported(FFmpegError.AVERROR_PATCHWELCOME, "$operation is unavailable. $filterGraphRefusal"),
)

internal actual fun buildVideoBackend(
    description: String,
    width: Int,
    height: Int,
    pixelFormat: PixelFormat,
    timeBase: Rational,
    frameRate: Rational,
    sampleAspectRatio: Rational,
): FilterBackend = refuseFilterGraph("Building a video filter graph")

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
): FilterBackend = refuseFilterGraph("Building an audio filter graph")

internal actual fun buildVideoMultiBackend(description: String, inputs: List<VideoInput>): FilterBackend =
    refuseFilterGraph("Building a multi-input video filter graph")

internal actual fun buildAudioMultiBackend(
    description: String,
    inputs: List<AudioInput>,
    outputSampleRate: Int,
    outputSampleFormat: SampleFormat,
    outputChannels: Int,
    outputChannelLayoutMask: Long?,
): FilterBackend = refuseFilterGraph("Building a multi-input audio filter graph")
