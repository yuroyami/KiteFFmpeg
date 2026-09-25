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
 * Runs [open] with a native interrupt cell that [interrupt] raises.
 *
 * The cell is bound before the open starts, so a request raised while the open waits reaches
 * FFmpeg's poll, and it stays bound until the returned source has closed its context. A failed
 * open, including one the request stopped, unbinds and frees the cell before it rethrows. The
 * cell is freed only after the context that polls it is gone.
 */
internal inline fun <Cell : Any> openUnder(
    interrupt: OpenInterrupt?,
    newCell: () -> Cell,
    crossinline raise: (Cell) -> Unit,
    crossinline freeCell: (Cell) -> Unit,
    open: (Cell?) -> MediaSource,
): MediaSource {
    if (interrupt == null) return open(null)
    if (interrupt.isInterrupted) throw interruptedOpen("before it started")
    val cell = newCell()
    val target: () -> Unit = { raise(cell) }
    interrupt.bind(target)
    val source = try {
        open(cell)
    } catch (failure: Throwable) {
        interrupt.unbind(target)
        freeCell(cell)
        throw failure
    }
    source.releaseAtClose {
        interrupt.unbind(target)
        freeCell(cell)
    }
    return source
}

/**
 * The web's form of [openUnder]. Nothing can run while an open runs there, so the request is
 * checked before the open and bound to the returned source after it.
 */
internal inline fun openUnderSingleThreaded(interrupt: OpenInterrupt?, open: () -> MediaSource): MediaSource {
    if (interrupt == null) return open()
    if (interrupt.isInterrupted) throw interruptedOpen("before it started")
    val source = open()
    val target: () -> Unit = { source.interrupt() }
    interrupt.bind(target)
    source.releaseAtClose { interrupt.unbind(target) }
    return source
}

internal fun interruptedOpen(moment: String): FFmpegException =
    FFmpegException(FFmpegError.Interrupted(FFmpegError.AVERROR_EXIT, "the open was interrupted $moment"))
