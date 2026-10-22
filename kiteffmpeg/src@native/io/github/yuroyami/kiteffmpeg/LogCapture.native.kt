package io.github.yuroyami.kiteffmpeg

import cnames.structs.kc_log_capture
import ffmpeg.ffkmp_log_capture_begin
import ffmpeg.ffkmp_log_capture_component
import ffmpeg.ffkmp_log_capture_count
import ffmpeg.ffkmp_log_capture_end
import ffmpeg.ffkmp_log_capture_free
import ffmpeg.ffkmp_log_capture_level
import ffmpeg.ffkmp_log_capture_message
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value

internal actual fun beginLogCapture(): Boolean = ffkmp_log_capture_begin() == 0

/** Reads the capture's lines and frees it, whatever the reading does. */
internal actual fun endLogCapture(): List<FFmpegLogLine> = memScoped {
    val slot = alloc<CPointerVar<kc_log_capture>>()
    val capture = ffkmp_log_capture_end() ?: return@memScoped emptyList()
    slot.value = capture
    try {
        (0 until ffkmp_log_capture_count(capture)).map { line ->
            FFmpegLogLine(
                level = FFmpegLogLevel.of(ffkmp_log_capture_level(capture, line)),
                component = ffkmp_log_capture_component(capture, line)?.toKString().orEmpty(),
                message = ffkmp_log_capture_message(capture, line)?.toKString().orEmpty(),
            )
        }
    } finally {
        ffkmp_log_capture_free(slot.ptr)
    }
}
