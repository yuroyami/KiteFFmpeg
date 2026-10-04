package io.github.yuroyami.kiteffmpeg

/** This target has no FFmpeg, so nothing logs and nothing is captured. */
internal actual fun beginLogCapture(): Boolean = false

internal actual fun endLogCapture(): List<FFmpegLogLine> = emptyList()
