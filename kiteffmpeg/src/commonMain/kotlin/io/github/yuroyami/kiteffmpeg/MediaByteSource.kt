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

    /**
     * Total size in bytes, or null when unknown (a live stream).
     *
     * Read again each time FFmpeg asks for the size, as FFmpeg's own file reader looks at the file
     * again, so a source that grows while it is read, such as a recording in progress, answers what
     * it holds now and a seek reaches the part that arrived after the open (#177). Null after a
     * size keeps the last one FFmpeg was given at open.
     */
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

    /**
     * The tags that the bytes of the last [read] brought, such as the song an internet radio
     * station names in a title block between its audio bytes, or null, the default, when that read
     * brought none (#168).
     *
     * It is asked after every read that returned bytes, on the same thread, and only of the source
     * given to [MediaSource.open]. A source a [MediaByteOpener] returns is never asked, because
     * FFmpeg reads no tags from one: the HLS reader reads each segment through an input of its own.
     * On a page's main thread, where the web backend reads a source whole during the open, it is
     * asked after each of those reads, and each answer reaches FFmpeg when FFmpeg reads the byte it
     * belongs at, every time FFmpeg reads that byte.
     *
     * The tags belong at the first byte of the read that reported them, and FFmpeg merges them into
     * the container's tags, exactly as it does with the titles its own `http` reads from a station.
     * They reach a caller as [Packet.newContainerTags] on the first packet a reader hands out after
     * FFmpeg read past that byte, and in [MediaSource.metadata] from then on. Tags reported during
     * the open's own reads are in [MediaSource.metadata] when the open returns. A source that stops
     * each read at the byte where its next tags belong, as FFmpeg's `http` stops at every title
     * block, places them on the packet that holds the bytes after them, and one that reads past
     * that byte places them early by what it read past.
     *
     * Report each change once. Keys go through as given: FFmpeg's `http` names a station's fields
     * `StreamTitle` and `StreamUrl`, and a source that wants a song to read as the title reports
     * `title`. A key or a value ends at its first NUL character, as the C string it crosses as does,
     * and a pair whose key is then empty is ignored. Finding the titles is the source's work, since
     * asking a station for them and finding its blocks needs the HTTP headers: send
     * `Icy-MetaData: 1`, read the block interval from `icy-metaint`, and take every block out of the
     * bytes before [read] hands them over. An exception thrown here fails the read that came before
     * it, as one thrown by [read] does.
     */
    public fun takeTags(): Map<String, String>? = null
}

/**
 * The [MediaByteSource.location] a bridge hands FFmpeg: null for none or an empty one. An address
 * holding a NUL character is refused, because the C string it crosses as would end there and FFmpeg
 * would resolve against a different place than the one named.
 */
internal fun MediaByteSource.openedLocation(): String? {
    val location = location?.takeIf { it.isNotEmpty() } ?: return null
    require('\u0000' !in location) { "a byte source's location cannot hold a NUL character" }
    return location
}

/**
 * The tags a [MediaByteSource.takeTags] answer hands FFmpeg, as keys and values in turn, or null
 * when none is left. Each key and value ends at its first NUL character, as the C string it crosses
 * as would, and a pair whose key is then empty is left out. An unpaired surrogate becomes U+FFFD, so
 * every backend hands FFmpeg the same UTF-8 rather than each encoder's own repair, or a refusal.
 */
internal fun Map<String, String>.ffmpegTagPairs(): Array<String>? {
    if (isEmpty()) return null
    val pairs = ArrayList<String>(size * 2)
    for ((key, value) in this) {
        val cutKey = key.forCString()
        if (cutKey.isEmpty()) continue
        pairs += cutKey
        pairs += value.forCString()
    }
    return if (pairs.isEmpty()) null else pairs.toTypedArray()
}

private fun String.forCString(): String {
    val end = indexOf('\u0000').let { if (it < 0) length else it }
    var clean = true
    var i = 0
    while (i < end) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < end && this[i + 1].isLowSurrogate()) {
            i += 2
            continue
        }
        if (c.isSurrogate()) {
            clean = false
            break
        }
        i++
    }
    if (clean) return if (end == length) this else substring(0, end)
    val out = StringBuilder(end)
    i = 0
    while (i < end) {
        val c = this[i]
        when {
            c.isHighSurrogate() && i + 1 < end && this[i + 1].isLowSurrogate() -> {
                out.append(c).append(this[i + 1])
                i += 2
                continue
            }
            c.isSurrogate() -> out.append('�')
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

/** What a bridge records when [MediaByteSource.read] answered with more bytes than it was asked for. */
internal fun byteSourceOverCount(returned: Int, asked: Int): IllegalStateException = IllegalStateException(
    "the byte source answered a read of $asked bytes with $returned, and MediaByteSource.read " +
        "may return at most the length it was given",
)
