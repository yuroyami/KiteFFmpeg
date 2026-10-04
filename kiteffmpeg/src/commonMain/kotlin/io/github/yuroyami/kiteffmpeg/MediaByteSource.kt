package io.github.yuroyami.kiteffmpeg

/**
 * Media bytes from caller code instead of a path: the custom I/O door. A player streams through its own HTTP client with its own TLS and auth, reads from
 * an encrypted store, a torrent, a cache, or a byte array it already holds, and FFmpeg demuxes
 * those bytes exactly as it would a file's.
 *
 * Threading and blocking. Every call arrives on the thread driving the demuxer, one call at a
 * time, never concurrently. [read] MUST block until it has at least one byte; a source with
 * nothing more to give returns -1. Failures are thrown, not encoded: any exception from these
 * methods surfaces to FFmpeg as an I/O error on the operation that triggered the call.
 *
 * Lifetime. The [MediaSource] opened over this owns it: [close] runs exactly once when that
 * source closes, and the instance must stay valid until then.
 */
public interface MediaByteSource : AutoCloseable {

    /** Total size in bytes, or null when unknown (a live stream). */
    public val size: Long?

    /** False makes the whole input unseekable; [seek] is then never called. */
    public val seekable: Boolean

    /**
     * Reads at most [length] bytes into [into] at [offset], advancing the cursor.
     *
     * @return how many bytes were read (at least 1), or -1 at the end of the stream. Never 0:
     *         block until a byte exists or the stream ends. A count above [length] fails the
     *         operation as an I/O error, and none of those bytes are used.
     */
    public fun read(into: ByteArray, offset: Int, length: Int): Int

    /** Moves the cursor to [position] bytes from the start. Only called when [seekable]. */
    public fun seek(position: Long)

    /**
     * The address these bytes came from when that is not the address they were asked for, as
     * after an HTTP redirect, or null, the default, when they came from that address (#167).
     *
     * FFmpeg's HLS reader resolves the relative addresses inside a playlist against it, as it does
     * after a redirect that FFmpeg's own `http` follows, so the segments, keys and variants of a
     * redirected playlist are asked for beside the place it really is. It is read once, when the
     * source is opened: by [MediaSource.open] for the source it is given, and as soon as a
     * [MediaByteOpener] returns it for every other one. A source that follows its redirects only as
     * it reads must have followed them by then. An empty address is the same as null.
     */
    public val location: String? get() = null
}

/** What a bridge records when [MediaByteSource.read] answered with more bytes than it was asked for. */
internal fun byteSourceOverCount(returned: Int, asked: Int): IllegalStateException = IllegalStateException(
    "the byte source answered a read of $asked bytes with $returned, and MediaByteSource.read " +
        "may return at most the length it was given",
)
