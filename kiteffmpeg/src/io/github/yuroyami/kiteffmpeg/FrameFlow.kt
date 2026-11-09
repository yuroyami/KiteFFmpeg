package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Buffers a frame flow without stranding the frames it is still holding.
 *
 * ### Why this exists instead of `buffer()`
 *
 * A [Frame] owns native memory and must be closed. The standard library's `buffer()`, `flowOn`,
 * `conflate` and `produceIn` all move elements through a channel they create themselves, and none
 * of them offers a hook for elements that never reach the collector. So a flow cancelled part way
 * through, `buffer().take(1)` being the ordinary case, drops whatever is still queued on the floor:
 * with the default capacity that is up to 64 undelivered frames, which for 1080p is hundreds of
 * megabytes that nothing will ever free.
 *
 * This operator owns its channel and closes every element the collector does not receive. Use it
 * anywhere you would have reached for `buffer()`, and pass [context] anywhere you would have
 * reached for `flowOn`: both hazards are the same hazard, and `flowOn` is the easier one to write
 * by accident.
 *
 * ```kotlin
 * source.decodedFrames(video)
 *     .bufferFrames(context = Dispatchers.Default)   // decode off the collector's thread
 *     .take(10)                                      // the other 54 are closed, not leaked
 *     .collect { frame -> frame.use { render(it) } }
 * ```
 *
 * Frames that DO reach the collector are still the collector's to close, exactly as they are
 * without this operator. A frame reaches the collector when the collector's `emit` is called with
 * it, even if that call then throws: `first()` and `take()` end a flow that way after they have
 * the frame. This changes what happens to the ones that do not arrive, nothing else.
 *
 * @param capacity the same values `buffer()` takes; [Channel.BUFFERED] is the default 64.
 * @param context where the upstream runs, the way `flowOn` would place it. Empty keeps it in the
 *        collector's context, so the buffer alone decouples the two ends.
 */
public fun Flow<Frame>.bufferFrames(
    capacity: Int = Channel.BUFFERED,
    context: CoroutineContext = EmptyCoroutineContext,
): Flow<Frame> = FrameBufferFlow(this, capacity, context)

/**
 * Implements [Flow] directly rather than through the `flow` builder. The builder's `emit` checks
 * for cancellation before it passes the value on, so a frame already taken from the channel could
 * be refused there and reach no one (#115). Here the frame stays ours until the collector's own
 * `emit` is called with it.
 */
private class FrameBufferFlow(
    private val upstream: Flow<Frame>,
    private val capacity: Int,
    private val context: CoroutineContext,
) : Flow<Frame> {

    override suspend fun collect(collector: FlowCollector<Frame>): Unit = coroutineScope {
        // The whole point: a channel that is TOLD what an undelivered element costs. Everything
        // below is the plumbing that guarantees each frame is either delivered or closed, on
        // every abandonment path.
        val channel = Channel<Frame>(capacity, onUndeliveredElement = { it.close() })
        val producer = launch(context, start = CoroutineStart.ATOMIC) {
            try {
                upstream.collect { channel.send(it) }
                channel.close()
            } catch (failure: Throwable) {
                // Carried to the collector rather than swallowed; the frames already queued are
                // still released by the cancel below.
                channel.close(failure)
            }
        }
        try {
            for (frame in channel) {
                // A buffered frame is received without a suspension, so a cancelled collector
                // can still take one. It is not delivered, so it is closed here.
                if (!isActive) {
                    frame.close()
                    ensureActive()
                }
                collector.emit(frame)
            }
        } finally {
            // Reached on EVERY exit, and the ordinary one is not an error: `take` ends a flow by
            // throwing out of `emit` once it has what it asked for. Cancelling releases whatever
            // is still queued through the callback above, and cancelling a channel already drained
            // by a normal completion has nothing to release.
            channel.cancel()
            producer.cancel()
        }
    }
}

/**
 * A flow of owned frames built like `flow { }`, whose [FrameHandOver.handOver] closes a frame the
 * collector can no longer receive.
 *
 * The builder's `emit` checks for cancellation before it passes the value on, so a decode flow that
 * made a fresh copy of a frame and was then cancelled, by `withTimeout` or a job cancelled from
 * another thread while a packet decoded, threw from `emit` with the copy reaching no one. A frame
 * has no finalizer, so that was one leaked picture per cancel: 3.1 MB at 1080p (#199). Like
 * [FrameBufferFlow], this implements [Flow] by hand, so the frame stays ours until the collector's
 * own `emit` is called with it.
 */
internal fun frameFlow(block: suspend FrameHandOver.() -> Unit): Flow<Frame> = object : Flow<Frame> {
    override suspend fun collect(collector: FlowCollector<Frame>) {
        FrameHandOver(collector).block()
    }
}

/** The emitting side of [frameFlow]. */
internal class FrameHandOver(private val collector: FlowCollector<Frame>) {
    /**
     * Delivers [frame], which is the caller's until this is called. A cancelled collector gets
     * nothing: the frame is closed here and the cancellation thrown. Once the collector's `emit`
     * is called with it the frame is the collector's, also when that call then throws.
     */
    suspend fun handOver(frame: Frame) {
        val context = currentCoroutineContext()
        if (!context.isActive) {
            frame.close()
            context.ensureActive()
        }
        collector.emit(frame)
    }
}
