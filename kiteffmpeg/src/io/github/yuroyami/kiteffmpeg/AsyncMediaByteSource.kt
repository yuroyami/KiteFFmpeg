package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * A resource whose release may wait.
 *
 * The handles of an [AsyncMediaRuntime] close through the runtime's lane, which is the one place
 * the runtime runs codec work. A close therefore waits for the work that holds the lane.
 */
public interface AsyncCloseable {

    /**
     * Releases the resource. A second call waits for the first and reports the same result.
     * A close finishes even when its caller is cancelled.
     */
    @Throws(Exception::class)
    public suspend fun close()
}

/**
 * Media bytes from caller code that may answer later: a Fetch response, a network client, a cache.
 * It is the suspending form of [MediaByteSource], opened through [AsyncMediaRuntime.open].
 *
 * Calls arrive one at a time. The runtime that opens the source owns it from the call of
 * [AsyncMediaRuntime.open], and calls [close] exactly once. One source object serves one open: the
 * runtime refuses an object that it already holds.
 *
 * A cancelled operation cancels the call in flight and calls [close] while that call may still be
 * finishing. [close] must therefore stop the transport, and a call that returns after its
 * cancellation must not write into the array it was given. Honor coroutine cancellation and put a
 * limit on every wait: the runtime waits for a call to finish before it runs other work.
 *
 * A source must not call the runtime that is reading it, from any of its methods.
 */
public interface AsyncMediaByteSource : AsyncCloseable {

    /** False makes the whole input unseekable. [seek] is then never called. */
    public val seekable: Boolean

    /**
     * The address the bytes came from when it differs from the address asked for, as after an HTTP
     * redirect, or null. It is read once, when the source is opened. See [MediaByteSource.location].
     */
    public val location: String? get() = null

    /**
     * The total size in bytes, or null when it is unknown. It is asked again each time FFmpeg asks
     * for the size. A null after a size keeps the last size.
     */
    @Throws(Exception::class)
    public suspend fun size(): Long?

    /**
     * Reads at most [length] bytes into [into] at [offset] and advances the cursor.
     *
     * @return the count of bytes read, at least 1, or -1 at the end. Never 0. A count above
     *   [length] fails the operation, and none of those bytes are used.
     */
    @Throws(Exception::class)
    public suspend fun read(into: ByteArray, offset: Int, length: Int): Int

    /** Moves the cursor to [position] bytes from the start. Called only when [seekable]. */
    @Throws(Exception::class)
    public suspend fun seek(position: Long)

    /**
     * The tags that the bytes of the last [read] brought, or null. It is asked after every read
     * that returned bytes, and only of the source given to [AsyncMediaRuntime.open]. See
     * [MediaByteSource.takeTags].
     */
    @Throws(Exception::class)
    public suspend fun takeTags(): Map<String, String>? = null
}

/**
 * Opens the other addresses that media read through an [AsyncMediaByteSource] refers to, such as
 * the segments and keys of an HLS playlist. It is the suspending form of [MediaByteOpener].
 *
 * The runtime owns every source [open] returns, from the moment it returns, and closes each once.
 * Return a new source object for every call. The addresses come from the media, which is untrusted
 * input: open only the schemes and hosts you expect, and return null for every other address.
 */
public fun interface AsyncMediaByteOpener {

    /** The bytes at [url], or null to refuse the address. */
    @Throws(Exception::class)
    public suspend fun open(url: String): AsyncMediaByteSource?
}

/**
 * Runs [block] with this resource and closes it afterwards, whatever [block] did. The close runs
 * even when the caller is cancelled. A failure of the close after a failure of [block] is added to
 * the first as suppressed.
 */
@Throws(Exception::class)
public suspend fun <T : AsyncCloseable, R> T.useAsync(block: suspend (T) -> R): R {
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (thrown: Throwable) {
        failure = thrown
        throw thrown
    } finally {
        withContext(NonCancellable) {
            val first = failure
            if (first == null) {
                close()
            } else {
                try {
                    close()
                } catch (closing: Throwable) {
                    if (closing !== first && closing !is CancellationException) first.addSuppressed(closing)
                }
            }
        }
    }
}

/**
 * The [AsyncMediaByteSource.location] handed to FFmpeg: null for none or an empty one. An address
 * that holds a NUL character is refused, as [MediaByteSource.location] is.
 */
internal fun AsyncMediaByteSource.openedLocation(): String? {
    val location = location?.takeIf { it.isNotEmpty() } ?: return null
    require('\u0000' !in location) { "a byte source's location cannot hold a NUL character" }
    return location
}
