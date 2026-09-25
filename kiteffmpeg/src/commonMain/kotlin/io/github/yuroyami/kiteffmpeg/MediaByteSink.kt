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
 * then [close] once, and the instance must stay valid until then.
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
 * The error for a header that failed to write with [rc]. A container that has to seek fails with
 * EINVAL on a byte sink that cannot, and FFmpeg says why only in its log, so that case gets a
 * message naming the cause and the way out. Every other failure is [generic].
 */
internal fun headerFailure(rc: Int, sinkSeekable: Boolean?, generic: FFmpegError): FFmpegException {
    if (sinkSeekable != false || rc != AVERROR_EINVAL) return FFmpegException(generic)
    return FFmpegException(
        FFmpegError.InvalidArgument(
            rc,
            "This container has to seek back to finish its header, and the MediaByteSink cannot " +
                "seek. Use a seekable sink, or a streamable form of the container, such as " +
                "\"movflags\" to \"frag_keyframe+empty_moov\" for MP4.",
        ),
    )
}
