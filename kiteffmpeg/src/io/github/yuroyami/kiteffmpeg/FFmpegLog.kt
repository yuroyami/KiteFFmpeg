package io.github.yuroyami.kiteffmpeg

import kotlin.concurrent.Volatile

/** How severe an FFmpeg log line is, from the most severe to the least. */
public enum class FFmpegLogLevel(internal val code: Int) {
    Panic(0),
    Fatal(8),
    Error(16),
    Warning(24),
    Info(32),
    Verbose(40),
    Debug(48),
    Trace(56),
    ;

    internal companion object {
        /** The named level of a line FFmpeg logged at [code], which may sit between two of them. */
        fun of(code: Int): FFmpegLogLevel = entries.lastOrNull { it.code <= code } ?: Panic
    }
}

/** Receives FFmpeg's own log lines, as installed with [FFmpeg.setLogSink]. */
public fun interface FFmpegLogSink {
    /**
     * One line. [component] names what logged, such as `h264` or `mov,mp4,m4a,3gp,3g2,mj2`, and is
     * empty when FFmpeg names nothing. [message] is one call of FFmpeg's logger without its trailing
     * newline, so a line FFmpeg builds in parts arrives in parts.
     */
    public fun log(level: FFmpegLogLevel, component: String, message: String)
}

/**
 * One line FFmpeg logged, as [FFmpegException.logged] keeps it (#170).
 *
 * [component] names what logged, such as `mov,mp4,m4a,3gp,3g2,mj2` for the MP4 reader or `hls`, and
 * is empty when FFmpeg names nothing. [message] is one call of FFmpeg's logger without its trailing
 * newline, as an [FFmpegLogSink] receives it.
 */
public data class FFmpegLogLine(
    val level: FFmpegLogLevel,
    val component: String,
    val message: String,
) {
    /** `[component] message`, or the message alone when no component is named. */
    override fun toString(): String = if (component.isEmpty()) message else "[$component] $message"
}

/** The installed sink, shared by every backend's forwarder. */
internal object FFmpegLog {
    /** FFmpeg's AV_LOG_QUIET: no line at all. */
    const val QUIET: Int = -8

    @Volatile
    var sink: FFmpegLogSink? = null

    /** The C level for [level] and [sink]: QUIET when there is no sink. */
    fun code(level: FFmpegLogLevel, sink: FFmpegLogSink?): Int = if (sink == null) QUIET else level.code

    /** Hands one line to the sink. An exception the sink throws is dropped, because it would otherwise unwind into C. */
    fun deliver(code: Int, component: String, message: String) {
        val current = sink ?: return
        try {
            current.log(FFmpegLogLevel.of(code), component, message)
        } catch (dropped: Throwable) {
        }
    }
}
