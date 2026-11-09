package io.github.yuroyami.kiteffmpeg

/**
 * Where an output's bytes go instead of a path: the custom I/O door for writing, and the output
 * half of [MediaByteSource]. An in-memory buffer, encrypted storage, a virtual file system or an
 * upload stream takes the bytes a muxer writes, through [MediaSink.open].
 *
 * Threading and blocking. Every call arrives on the thread that writes the output, one call at a
 * time, never concurrently. A call that blocks holds the muxer, and that is how a slow consumer
 * bounds the buffering: the muxer holds at most 64 KiB it has not handed over. Failures are
 * thrown, not encoded: an exception fails the write that triggered it with an
 * [FFmpegException] whose cause is that exception, and the sink cannot write again after it.
 *
 * Lifetime. The [MediaSink] opened over this owns it: after the last byte it calls [flush] once,
 * then [close] once, and the instance must stay valid until then. Closing that [MediaSink] from
 * inside [write] or [seek] is refused, because the muxer is still using it.
 */
public interface MediaByteSink : AutoCloseable {

    /**
     * False when the sink cannot go back, and then [seek] is never called. A container that has to
     * seek, such as MP4 without fragments, is refused when its header is written; pass
     * `"movflags" to "frag_keyframe+empty_moov"` to write fragmented MP4 instead.
     */
    public val seekable: Boolean

    /** Takes all [length] bytes of [bytes] from [offset] at the current position, and moves past them. */
    public fun write(bytes: ByteArray, offset: Int, length: Int)

    /** Moves the position to [position] bytes from the start. Only called when [seekable]. */
    public fun seek(position: Long)

    /** Hands on whatever the sink still buffers. Called once, after the last write. */
    public fun flush() {}
}

/** FFmpeg's AVERROR(EINVAL), the same on every target this library builds for. */
private const val AVERROR_EINVAL = -22

/**
 * The error for an output that could not be set up for [format], or, with no format, for the
 * extension of [path]. Once its arguments are checked, FFmpeg's output allocation fails with
 * EINVAL only when no muxer in the build writes that format, so that case is
 * [FFmpegError.MuxerNotFound], as the docs promise (#200). Every other failure is [failure].
 */
internal fun outputAllocFailure(failure: FFmpegException, format: String?, path: String?): FFmpegException {
    if (failure.code != AVERROR_EINVAL) return failure
    val what = when {
        format != null -> "the output format \"$format\""
        path != null && '.' in path.substringAfterLast('/') -> "the extension \".${path.substringAfterLast('.')}\""
        else -> "an output with no format and no extension"
    }
    return retyped(failure, FFmpegError.MuxerNotFound(failure.code, "This build has no muxer for $what"))
}

/**
 * The error for a header that failed, [failure], with FFmpeg's log lines attached. FFmpeg answers
 * EINVAL for several causes and says which only in its log, so this reads the lines (#200). A
 * container that cannot hold one of the streams' codecs is [FFmpegError.MuxerNotFound]. A
 * container that has to seek back to finish its header, on a byte sink that cannot seek, gets a
 * message naming the cause and the way out. Every other failure is [failure].
 */
internal fun headerFailure(failure: FFmpegException, sinkSeekable: Boolean?): FFmpegException {
    if (failure.code != AVERROR_EINVAL) return failure
    val lines = failure.logged.map { it.message.trim() }
    lines.firstOrNull(::refusesCodec)?.let { line ->
        return retyped(failure, FFmpegError.MuxerNotFound(failure.code, "The container cannot hold one of the streams: $line"))
    }
    if (sinkSeekable == false && lines.any { "seekable" in it.lowercase() }) {
        return retyped(
            failure,
            FFmpegError.InvalidArgument(
                failure.code,
                "This container has to seek back to finish its header, and the MediaByteSink cannot " +
                    "seek. Use a seekable sink, or a streamable form of the container, such as " +
                    "\"movflags\" to \"frag_keyframe+empty_moov\" for MP4.",
            ),
        )
    }
    return failure
}

/**
 * Whether [line] is a muxer refusing a stream's codec: the generic "codec not currently supported
 * in container" of FFmpeg's muxer setup, or a muxer's own "codec not supported" kind of line.
 */
private fun refusesCodec(line: String): Boolean {
    val lower = line.lowercase()
    return "not currently supported in container" in lower || "codec" in lower && "supported" in lower
}

/** [failure] as [error], with its cause and its log lines kept. */
private fun retyped(failure: FFmpegException, error: FFmpegError): FFmpegException =
    FFmpegException(error, failure.cause).also { it.attachLogged(failure.logged) }
