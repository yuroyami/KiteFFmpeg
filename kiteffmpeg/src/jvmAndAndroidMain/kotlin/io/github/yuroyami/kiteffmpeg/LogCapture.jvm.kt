package io.github.yuroyami.kiteffmpeg

internal actual fun beginLogCapture(): Boolean = Internals.logCaptureBegin()

internal actual fun endLogCapture(): List<FFmpegLogLine> = Internals.logCaptureEnd()
