package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineDispatcher
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
 */
internal suspend fun <P> runTranscode(
    dispatcher: CoroutineDispatcher,
    onProgress: ((P) -> Unit)?,
    work: suspend (publish: ((P) -> Unit)?) -> Unit,
) {
    if (onProgress == null) {
        withContext(dispatcher) { work(null) }
        return
    }
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
