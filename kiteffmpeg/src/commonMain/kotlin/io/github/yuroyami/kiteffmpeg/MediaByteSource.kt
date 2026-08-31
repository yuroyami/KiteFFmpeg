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
     *         block until a byte exists or the stream ends.
     */
    public fun read(into: ByteArray, offset: Int, length: Int): Int

    /** Moves the cursor to [position] bytes from the start. Only called when [seekable]. */
    public fun seek(position: Long)
}
