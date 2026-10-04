package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_log_capture_begin
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_log_capture_component
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_log_capture_count
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_log_capture_end
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_log_capture_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_log_capture_level
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_log_capture_message
import kotlin.js.JsAny

/** A module linked before the capture existed has nothing to open, so its failures keep their message alone. */
internal actual fun beginLogCapture(): Boolean {
    val m = KiteFFmpegWeb.module ?: return false
    if (!hasLogCapture(m)) return false
    return ffkmp_log_capture_begin(m) == 0
}

internal actual fun endLogCapture(): List<FFmpegLogLine> {
    val m = KiteFFmpegWeb.module ?: return emptyList()
    if (!hasLogCapture(m)) return emptyList()
    val capture = ffkmp_log_capture_end(m)
    if (capture == 0) return emptyList()
    try {
        return List(ffkmp_log_capture_count(m, capture)) { i ->
            FFmpegLogLine(
                FFmpegLogLevel.of(ffkmp_log_capture_level(m, capture, i)),
                utf8OrNull(m, ffkmp_log_capture_component(m, capture, i)) ?: "",
                utf8OrNull(m, ffkmp_log_capture_message(m, capture, i)) ?: "",
            )
        }
    } finally {
        // The free takes the address of the pointer, so the pointer goes through a slot.
        val slot = wasmAlloc(m, 4)
        try {
            writeInt32(m, slot, capture)
            ffkmp_log_capture_free(m, slot)
        } finally {
            wasmFree(m, slot)
        }
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => typeof m._ffkmp_log_capture_begin === 'function'")
private external fun hasLogCapture(module: JsAny): Boolean
