package io.github.yuroyami.kiteffmpeg

// The web backends carry no filter graph: every build refuses, so no FilterGraph exists here.

internal actual fun buildVideoBackend(
    description: String,
    width: Int,
    height: Int,
    pixelFormat: PixelFormat,
    timeBase: Rational,
    frameRate: Rational,
    sampleAspectRatio: Rational,
): FilterBackend = placeholderBackendUnavailable("Building a video filter graph")

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
): FilterBackend = placeholderBackendUnavailable("Building an audio filter graph")

internal actual fun buildVideoMultiBackend(description: String, inputs: List<VideoInput>): FilterBackend =
    placeholderBackendUnavailable("Building a multi-input video filter graph")

internal actual fun buildAudioMultiBackend(
    description: String,
    inputs: List<AudioInput>,
    outputSampleRate: Int,
    outputSampleFormat: SampleFormat,
    outputChannels: Int,
    outputChannelLayoutMask: Long?,
): FilterBackend = placeholderBackendUnavailable("Building a multi-input audio filter graph")
