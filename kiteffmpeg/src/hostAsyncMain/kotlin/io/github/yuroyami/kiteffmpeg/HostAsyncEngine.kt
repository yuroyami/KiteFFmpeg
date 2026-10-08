@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Creates the runtime of the JVM, Android and native backends, which owns one thread. */
internal fun createHostAsyncRuntime(): AsyncMediaRuntime {
    val engine = HostAsyncEngine()
    val lane = AsyncLane(engine)
    engine.lane = lane
    return AsyncMediaRuntime(lane)
}

/**
 * Runs FFmpeg on one thread the runtime owns. A byte source call from C waits on that thread
 * while the byte source runs in a coroutine elsewhere, so the wait holds nobody else's thread.
 *
 * One real thread, not a dispatcher limited to one task: FFmpeg's log capture is local to the
 * thread, and a call must begin and end its capture on the same one.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class HostAsyncEngine : AsyncEngine {
    lateinit var lane: AsyncLane
    private val thread = newSingleThreadContext("kiteffmpeg-async")

    override val identity: FFmpegIdentity get() = FFmpeg.identity

    override suspend fun <T> immediate(block: () -> T): T = withContext(thread) { block() }

    override suspend fun open(request: AsyncOpen): MediaSource = withContext(thread) {
        val input = request.input
        // The lane stops the input through this request, also while the open itself waits.
        val gate = OpenInterrupt()
        input.engineData = gate
        if (input.isStopped) gate.interrupt()
        MediaSource.open(
            io = Reader(input.root),
            options = request.options,
            interrupt = gate,
            url = request.url,
            mimeType = request.mimeType,
            nestedOpener = input.opener?.let { Opener() },
        )
    }

    override suspend fun readPacket(reader: PacketReader): Packet? = withContext(thread) { reader.read() }

    override suspend fun seek(reader: PacketReader, micros: Long, direction: SeekDirection, notEarlierThan: Long?) {
        withContext(thread) { reader.seek(micros, direction, notEarlierThan) }
    }

    override suspend fun pause(source: MediaSource): Boolean = withContext(thread) { source.pause() }

    override suspend fun resume(source: MediaSource): Boolean = withContext(thread) { source.resume() }

    override suspend fun closeSource(input: AsyncInput, source: MediaSource) {
        withContext(thread) { source.close() }
    }

    override fun abort(input: AsyncInput) {
        (input.engineData as? OpenInterrupt)?.interrupt()
    }

    override suspend fun close() {
        thread.close()
    }

    /** What a call that failed or was stopped throws into the synchronous reader, as its cause. */
    private fun failure(): Throwable =
        lane.takeFailure() ?: IllegalStateException("the byte source call was stopped")

    /** Waits on the lane's thread for a byte source call that runs elsewhere. */
    private fun <T> waitFor(call: suspend () -> T): T = runBlocking { call() }

    /** One asynchronous byte source, as the synchronous source FFmpeg's thread reads. */
    private inner class Reader(private val lease: ProviderLease) : MediaByteSource {
        override val seekable: Boolean get() = lease.source.seekable

        override val location: String? get() = lease.location

        override val size: Long?
            get() {
                val size = waitFor { lane.providerSize(lease) }
                if (size == ASYNC_IO_ERR.toLong()) throw failure()
                return size.takeIf { it >= 0 }
            }

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            val count = waitFor {
                lane.providerRead(lease, length) { bytes, got -> bytes.copyInto(into, offset, 0, got) }
            }
            if (count == ASYNC_IO_ERR) throw failure()
            return count
        }

        override fun seek(position: Long) {
            if (waitFor { lane.providerSeek(lease, position) } < 0) throw failure()
        }

        override fun takeTags(): Map<String, String>? = lease.tags.also { lease.tags = null }

        override fun close() {
            waitFor { lane.providerClose(lease) }?.let { throw it }
        }
    }

    /** The asynchronous opener of the current input, as the synchronous opener FFmpeg's thread calls. */
    private inner class Opener : MediaByteOpener {
        override fun open(url: String): MediaByteSource? = when (val answer = waitFor { lane.providerOpen(url) }) {
            is AsyncChild -> Reader(answer.lease)
            ASYNC_IO_REFUSED -> null
            else -> throw failure()
        }
    }
}
