package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Buffers a flow of [AsyncFrame] and closes every frame the collector does not receive. It is the
 * suspending form of [bufferFrames], which cannot serve here: the close of an [AsyncFrame] waits
 * for the runtime's lane, and a channel's own hook for a dropped element cannot wait.
 *
 * A frame reaches the collector when the collector's `emit` is called with it, even when that call
 * then throws. The collector closes those frames. When the collection ends, this operator stops
 * the upstream, closes every frame that is still buffered, and only then completes.
 *
 * @param capacity [Channel.BUFFERED], or a count of zero or more, where zero hands each frame over
 *   directly. [Channel.UNLIMITED], [Channel.CONFLATED] and every other negative value are refused.
 * @param context where the upstream runs, as `flowOn` would place it. The codec work itself stays
 *   in the runtime's lane.
 */
public fun Flow<AsyncFrame>.bufferAsyncFrames(
    capacity: Int = Channel.BUFFERED,
    context: CoroutineContext = EmptyCoroutineContext,
): Flow<AsyncFrame> {
    require(capacity == Channel.BUFFERED || (capacity >= 0 && capacity != Channel.UNLIMITED)) {
        "bufferAsyncFrames takes Channel.BUFFERED or a count of zero or more, not $capacity. A buffer " +
            "without a bound, or one that drops frames, would hold or lose native memory."
    }
    return AsyncFrameBufferFlow(this, capacity, context)
}

/** Implements [Flow] directly, for the reason [bufferFrames] does: no check between take and emit. */
private class AsyncFrameBufferFlow(
    private val upstream: Flow<AsyncFrame>,
    private val capacity: Int,
    private val context: CoroutineContext,
) : Flow<AsyncFrame> {

    override suspend fun collect(collector: FlowCollector<AsyncFrame>): Unit = coroutineScope {
        val channel = Channel<AsyncFrame>(capacity)
        val producer = launch(context, start = CoroutineStart.ATOMIC) {
            try {
                upstream.collect { frame ->
                    try {
                        channel.send(frame)
                    } catch (refused: Throwable) {
                        // A send that throws did not hand the frame over, so it is still ours.
                        withContext(NonCancellable) { closeQuietly(frame) }
                        throw refused
                    }
                }
                channel.close()
            } catch (failure: Throwable) {
                channel.close(failure)
            }
        }
        try {
            for (frame in channel) {
                // A buffered frame is received without a suspension, so a cancelled collector
                // can still take one. It is not delivered, so it is closed here.
                if (!isActive) {
                    withContext(NonCancellable) { closeQuietly(frame) }
                    ensureActive()
                }
                collector.emit(frame)
            }
        } finally {
            // Reached on every exit. The producer stops first, so nothing new arrives, and every
            // frame still in the channel is closed before the collection completes.
            withContext(NonCancellable) {
                producer.cancelAndJoin()
                while (true) closeQuietly(channel.tryReceive().getOrNull() ?: break)
                channel.cancel()
            }
        }
    }
}

/** Closes [frame]. A failure here must not replace the outcome of the collection. */
private suspend fun closeQuietly(frame: AsyncFrame) {
    try {
        frame.close()
    } catch (ignored: Throwable) {
    }
}
