package io.github.yuroyami.kiteffmpeg

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * The native object one [Frame] or [Packet] holds, its open state, and how it is freed (#242).
 *
 * It is also the owner's lock: every native call through the owner holds it, and a free from the
 * cleaner takes it too, so the cleaner can never free under a call still using the pointer. The
 * cleaner's block captures only this hold, never the owner, or the owner could not be collected.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NativeHold<T : CPointed>(
    private val pointer: CPointer<T>,
    private val kind: String,
    private val free: (CPointer<T>) -> Unit,
) : SynchronizedObject() {
    /** Read and written only while holding this lock. */
    var closed: Boolean = false
        private set

    /** Frees the pointer once. The caller holds this lock, as the owner's close does. */
    fun release() {
        if (closed) return
        closed = true
        free(pointer)
    }

    /** The cleaner's half: the owner was collected, so free what it left behind and say so. */
    fun reap() {
        val leaked = synchronized(this) {
            if (closed) {
                false
            } else {
                closed = true
                free(pointer)
                true
            }
        }
        if (leaked) {
            NativeLeaks.count.incrementAndGet()
            FFmpegLog.warn(
                "kiteffmpeg",
                "A $kind was collected without close(); its native memory was freed late. Close every $kind you receive.",
            )
        }
    }
}

/** How many frames and packets the cleaner freed for an owner that never closed them. */
internal object NativeLeaks {
    val count = atomic(0L)
}
