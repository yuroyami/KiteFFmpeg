package io.github.yuroyami.kiteffmpeg

/**
 * Where a transcode or a remux reads. [open] is called once for each open, because a transcode
 * opens its input a second time when the encoder must see the first frame. [name] is how an error
 * names the input, and [path] is set only for a path, which the same-file check needs.
 *
 * Every open shares [interrupt], which a cancel of the call raises, so an open or a read blocked
 * on a server that stopped answering returns instead of waiting for FFmpeg's own timeout (#250).
 */
internal class InputEnd(val name: String, val path: String?, private val opener: (OpenInterrupt) -> MediaSource) {
    val interrupt: OpenInterrupt = OpenInterrupt()

    /**
     * False for a descriptor or a pipe. FFmpeg's `fd` and `pipe` protocols duplicate the
     * descriptor and never rewind it, and a duplicate shares the file offset, so a second open
     * reads from wherever the first left it and moves the first one's reads: a Matroska peek then
     * failed with invalid data, and an MP4 transcode wrote a short file (#197).
     */
    val reopensSafely: Boolean = path == null || !(path == "fd:" || path.startsWith("pipe:") || path == "pipe")

    fun open(): MediaSource = opener(interrupt)
}

/**
 * Where a transcode or a remux writes. [path] is set only for a path.
 *
 * A caller's byte sink is the call's from the moment the call starts, but a [MediaSink] owns it
 * only once [open] has made one, after the input has been opened, its streams chosen and the seek
 * done. [owning] closes it once on every exit before that: a failed input open, a refused stream
 * selection, a failed seek, a cancel, and an [open] that fails itself (#85).
 */
internal class OutputEnd(
    val path: String?,
    private val opener: () -> MediaSink,
    private val unadopted: AutoCloseable? = null,
) {
    /** Set once [open] was called or [unadopted] was closed; either way, never again. */
    private var settled = false

    /** Opens the output. From here a [MediaSink] owns it, or, when the open fails, it is closed. */
    fun open(): MediaSink {
        check(!settled) { "the output was already opened or released" }
        settled = true
        return try {
            opener()
        } catch (failure: Throwable) {
            closeOnto(failure)
            throw failure
        }
    }

    /**
     * Runs [block], the whole call, owning the output until [open] hands it over. A failure stays
     * the one the caller sees, and a close that fails too rides on it as suppressed.
     */
    suspend fun <R> owning(block: suspend () -> R): R {
        val result = try {
            block()
        } catch (failure: Throwable) {
            if (!settled) {
                settled = true
                closeOnto(failure)
            }
            throw failure
        }
        if (!settled) {
            settled = true
            unadopted?.close()
        }
        return result
    }

    private fun closeOnto(failure: Throwable) {
        try {
            unadopted?.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
    }
}

internal fun inputAt(path: String, options: Map<String, String>): InputEnd =
    InputEnd(path, path) { interrupt -> MediaSource.open(path, options, interrupt) }

internal fun inputFrom(source: () -> MediaByteSource): InputEnd =
    InputEnd("the caller's byte source", null) { interrupt -> MediaSource.open(source(), interrupt = interrupt) }

internal fun outputAt(path: String): OutputEnd = OutputEnd(path, opener = { MediaSink.open(path) })

internal fun outputInto(sink: MediaByteSink, format: String, options: Map<String, String>): OutputEnd =
    OutputEnd(null, opener = { MediaSink.open(sink, format, options) }, unadopted = sink)
