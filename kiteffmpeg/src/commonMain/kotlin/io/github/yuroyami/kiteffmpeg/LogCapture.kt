package io.github.yuroyami.kiteffmpeg

/**
 * Begins collecting the error lines FFmpeg logs on this thread (#170). False when nothing began,
 * as when the C layer could not allocate a capture or the backend has none, and then
 * [endLogCapture] must not be called for it.
 */
internal expect fun beginLogCapture(): Boolean

/** Ends this thread's innermost capture and hands back its lines, oldest first. */
internal expect fun endLogCapture(): List<FFmpegLogLine>

/**
 * Runs [block], and when it throws an [FFmpegException], attaches to it the error lines FFmpeg
 * logged on this thread while it ran (#170). The capture opens and closes on one thread, so [block]
 * is not inline and cannot suspend: a coroutine that resumed elsewhere would close another
 * thread's capture, and on the web, where every coroutine shares one thread, another call's.
 */
internal fun <T> withLoggedReason(block: () -> T): T {
    val capturing = try {
        beginLogCapture()
    } catch (unavailable: Throwable) {
        false
    }
    if (!capturing) return block()
    val result = try {
        block()
    } catch (failure: Throwable) {
        val lines = endOrNothing()
        if (failure is FFmpegException) failure.attachLogged(lines)
        throw failure
    }
    endOrNothing()
    return result
}

/** The capture's lines, or none when ending it failed, which must not replace what [block] did. */
private fun endOrNothing(): List<FFmpegLogLine> = try {
    endLogCapture()
} catch (lost: Throwable) {
    emptyList()
}
