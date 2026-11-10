package io.github.yuroyami.kiteffmpeg

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * The native object one owning [Frame] or [Packet] holds, and how to free it (#242).
 *
 * It is also the owner's lock. Every native call through the owner runs while holding it, and the
 * reaper frees under it too, so a free can never land inside a call that is still using the token,
 * even when the call's own frame no longer keeps the owner reachable.
 */
internal class NativeClaim(
    @JvmField internal var token: Long,
    private val kind: String,
    private val free: (Long) -> Unit,
) {
    private var watch: NativeReaper.Watch? = null

    /** Frees the token if it is still held. Called with this claim's lock held by [close]. */
    internal fun release() {
        val held = token
        if (held == 0L) return
        token = 0L
        watch?.let { NativeReaper.forget(it) }
        watch = null
        free(held)
    }

    /** Asks the reaper to free the token when [owner] is collected with it still held. */
    internal fun watch(owner: Any) {
        watch = NativeReaper.watch(owner, this)
    }

    /** The reaper's half: the owner is gone, so free what it left behind and say so. */
    internal fun reap() {
        val leaked = synchronized(this) {
            val held = token
            if (held == 0L) return
            token = 0L
            watch = null
            free(held)
            true
        }
        if (leaked) {
            NativeReaper.countLeak()
            FFmpegLog.warn(
                "kiteffmpeg",
                "A $kind was collected without close(); its native memory was freed late. Close every $kind you receive.",
            )
        }
    }
}

/**
 * Frees the native memory of a [Frame] or [Packet] that was collected without `close()` (#242).
 *
 * `close()` stays the contract, because the garbage collector does not see native memory and may
 * run long after a leaked 4K frame's 25 MB. This only bounds the damage. A phantom reference rather
 * than `java.lang.ref.Cleaner`, because Android has that only from API 33. One daemon thread waits
 * on the queue and starts with the first watched object.
 */
internal object NativeReaper {
    private val queue = ReferenceQueue<Any>()

    /** Keeps each watch reachable until its owner is collected or closed. */
    private val watches: MutableSet<Watch> = ConcurrentHashMap.newKeySet()

    internal class Watch(owner: Any, val claim: NativeClaim) : PhantomReference<Any>(owner, queue)

    @Volatile
    private var leaks = 0L

    /** How many frames and packets this process has freed for an owner that never closed them. */
    internal fun leakCount(): Long = leaks

    internal fun countLeak() {
        synchronized(this) { leaks++ }
    }

    @Volatile
    private var started = false

    internal fun watch(owner: Any, claim: NativeClaim): Watch {
        if (!started) start()
        return Watch(owner, claim).also { watches += it }
    }

    internal fun forget(watch: Watch) {
        watches -= watch
        watch.clear()
    }

    @Synchronized
    private fun start() {
        if (started) return
        Thread({
            while (true) {
                val next = try {
                    queue.remove() as Watch
                } catch (stop: InterruptedException) {
                    continue
                }
                watches -= next
                try {
                    next.claim.reap()
                } catch (ignored: Throwable) {
                }
            }
        }, "kiteffmpeg-reaper").apply {
            isDaemon = true
            start()
        }
        started = true
    }
}
