package io.github.yuroyami.kiteffmpeg

import kotlin.js.JsAny

/**
 * FFmpeg's log lines on the web. The codec module calls one JS function per line, registered in
 * its function table once per module, and that function hands the line to [FFmpegLog].
 */
internal object WebLog {
    private var level: Int = FFmpegLog.QUIET
    private var forwarderModule: JsAny? = null
    private var forwarder: Int = 0

    fun set(level: FFmpegLogLevel, sink: FFmpegLogSink?) {
        FFmpegLog.sink = sink
        this.level = FFmpegLog.code(level, sink)
        KiteFFmpegWeb.module?.let(::apply)
    }

    /** Applies the current level to [module]. A module linked before the log sink existed keeps printing. */
    fun apply(module: JsAny) {
        if (!hasLogSink(module)) return
        if (forwarderModule !== module) {
            forwarder = addLogForwarder(module) { code, component, message -> FFmpegLog.deliver(code, component, message) }
            forwarderModule = module
        }
        setLogSinkIn(module, if (level < 0) 0 else forwarder, level)
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => typeof m._ffkmp_log_set_sink === 'function'")
private external fun hasLogSink(module: JsAny): Boolean

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m, deliver) => m.addFunction(
        (level, component, message) => deliver(level, m.UTF8ToString(component), m.UTF8ToString(message)),
        'viii',
    )"""
)
private external fun addLogForwarder(module: JsAny, deliver: (Int, String, String) -> Unit): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, fn, level) => m._ffkmp_log_set_sink(fn, level)")
private external fun setLogSinkIn(module: JsAny, fn: Int, level: Int)
