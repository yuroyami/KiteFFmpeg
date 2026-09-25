package io.github.yuroyami.kiteffmpeg

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A cancel request that can reach [MediaSource.open] while it runs.
 *
 * [MediaSource.interrupt] exists only on the source an open returns, so a caller could not stop an
 * open that stalls, for example on a server that accepts the connection and then sends nothing.
 * Create an [OpenInterrupt] before the open, pass it in, and call [interrupt] from any thread:
 *
 * ```kotlin
 * val cancel = OpenInterrupt()
 * // From another thread, when the user gives up: cancel.interrupt()
 * val source = MediaSource.open("https://example.com/film.mkv", emptyMap(), cancel)
 * ```
 *
 * The open then fails with [FFmpegError.Interrupted] at FFmpeg's next check. The source the open
 * returns keeps the same request, so an interrupt after the open stops every later read, exactly
 * like [MediaSource.interrupt]. A request raised before the open starts makes the open fail at
 * once. One request may serve several opens. It is one-way: an interrupted request stays
 * interrupted.
 *
 * What it can reach: FFmpeg checks for it inside its network protocols and during stream
 * discovery, and this library checks it between the reads of a [MediaByteSource]. A wait anywhere
 * else, such as inside a caller's own [MediaByteSource.read], finishes before the request is seen.
 * On the web nothing can run while an open runs, so there the request is seen only before and
 * after the open.
 */
public class OpenInterrupt {
    private val lock = SynchronizedObject()
    private var raised = false
    private val targets = ArrayList<() -> Unit>()

    /** True once [interrupt] was called. */
    public val isInterrupted: Boolean get() = synchronized(lock) { raised }

    /** Requests that every open and source using this request stop. Safe from any thread. */
    public fun interrupt() {
        synchronized(lock) {
            if (raised) return
            raised = true
            targets.forEach { it() }
        }
    }

    /**
     * Registers [target] to run when [interrupt] is called, and runs it now when it already was.
     *
     * Targets run under the lock, so [unbind] waits for a target that is running, and the caller
     * may free what the target touches as soon as [unbind] returns. A target must be quick and must
     * not call back into this request; every target here only raises a native flag.
     */
    internal fun bind(target: () -> Unit) {
        synchronized(lock) {
            targets.add(target)
            if (raised) target()
        }
    }

    /** Removes [target]; it is not run by any later [interrupt]. */
    internal fun unbind(target: () -> Unit) {
        synchronized(lock) { targets.remove(target) }
    }
}

/**
 * Runs [open] under [interrupt]: a request raised before the open fails it at once, a request
 * raised while it ran closes the new source and fails it, and a request raised later reaches the
 * source through [MediaSource.adoptOpenInterrupt].
 */
internal inline fun openUnder(interrupt: OpenInterrupt?, open: () -> MediaSource): MediaSource {
    if (interrupt == null) return open()
    if (interrupt.isInterrupted) throw interruptedOpen("before it started")
    val source = open()
    if (interrupt.isInterrupted) {
        source.close()
        throw interruptedOpen("while it ran")
    }
    source.adoptOpenInterrupt(interrupt)
    return source
}

internal fun interruptedOpen(moment: String): FFmpegException =
    FFmpegException(FFmpegError.Interrupted(FFmpegError.AVERROR_EXIT, "the open was interrupted $moment"))
