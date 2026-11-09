package io.github.yuroyami.kiteffmpeg

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the blocking [work] of a transcode or a remux on [dispatcher], and hands every progress
 * report [P] the work publishes to [onProgress] in the caller's own coroutine context.
 *
 * The reports travel through a conflated channel, so a busy caller gets the newest one, and the
 * last one reaches it before this returns. Cancelling the caller cancels the work, and this
 * returns only once the work has finished unwinding, so every native object it opened is closed
 * by then. An exception from [onProgress] cancels the work the same way before it is rethrown.
 *
 * A cancel also raises [interrupt], the one the work's inputs open with. A coroutine cancel
 * cannot reach an FFmpeg call that blocks, so without it a cancel waited for a stalled open or
 * read until FFmpeg gave up by itself, or for ever (#250).
 */
internal suspend fun <P> runTranscode(
    dispatcher: CoroutineDispatcher,
    onProgress: ((P) -> Unit)?,
    interrupt: OpenInterrupt? = null,
    work: suspend (publish: ((P) -> Unit)?) -> Unit,
): Unit = coroutineScope {
    val finished = atomic(false)
    val watcher = interrupt?.let { request ->
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.value) request.interrupt()
            }
        }
    }
    try {
        if (onProgress == null) {
            withContext(dispatcher) { work(null) }
        } else {
            coroutineScope {
                val reports = Channel<P>(Channel.CONFLATED)
                launch(dispatcher) {
                    try {
                        work { report -> reports.trySend(report) }
                    } finally {
                        reports.close()
                    }
                }
                for (report in reports) onProgress(report)
            }
        }
    } finally {
        finished.value = true
        watcher?.cancel()
    }
}
