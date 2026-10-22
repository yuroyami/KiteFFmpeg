package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How a [MediaSink] close meets other calls on the same sink: a close from inside the sink's own
 * [MediaByteSink] call, and two closes at once. Every backend answers the same way.
 */
internal class SinkCloseContractTest {

    private val spec = VideoEncoderSpec(
        codec = CodecId("mpeg4"),
        width = 32,
        height = 32,
        frameRate = Rational(25, 1),
        bitrateBps = 200_000,
    )

    private fun frame(): Frame {
        val y = ByteArray(32 * 32) { (it % 200).toByte() }
        val u = ByteArray(32 * 32 / 4) { 100.toByte() }
        val v = ByteArray(32 * 32 / 4) { 140.toByte() }
        return Frame.ofVideo(y + u + v, 32, 32, PixelFormat.Yuv420p, 0)
    }

    /**
     * The first write calls close on the sink that is writing. That close is refused, the write
     * goes on, and the close from outside afterwards writes a whole container.
     */
    @Test
    fun aCloseFromInsideTheByteSinkIsRefusedAndTheWriteGoesOn() {
        val bytes = ClosingSink()
        val sink = MediaSink.open(bytes, "matroska", mapOf("flush_packets" to "1"))
        bytes.owner = sink
        val encoder = sink.addVideoEncoder(spec)
        runBlocking { encoder.drive(flowOf(frame())) }
        sink.close()
        val refusal = assertIs<IllegalStateException>(bytes.refusal, "a close from inside write must be refused")
        assertTrue("inside its own MediaByteSink callback" in refusal.message.orEmpty(), refusal.message)
        assertEquals(1, bytes.closes, "the byte sink is closed once, by the close from outside")
        MediaSource.open(BytesSource(bytes.bytes)).use { written ->
            assertEquals(1, written.streams.size, "the bytes are a whole container")
        }
    }

    /** The first close waits in the byte sink's flush; the second must not return before it. */
    @Test
    fun aSecondCloseWaitsForTheFirstToFinish() = runBlocking {
        val bytes = SlowFlushSink()
        val sink = MediaSink.open(bytes, "matroska")
        sink.addVideoEncoder(spec).drive(flowOf(frame()))
        val state = CloseState()
        val first = launch(Dispatchers.Default) {
            sink.close()
            state.firstReturned = true
        }
        while (!bytes.flushEntered) delay(1)
        val second = launch(Dispatchers.Default) {
            sink.close()
            state.secondReturned = true
        }
        // A close that does not wait returns in well under a millisecond.
        delay(50)
        val returnedEarly = state.secondReturned
        bytes.releaseFlush = true
        first.join()
        second.join()
        assertFalse(returnedEarly, "the second close returned while the first was still flushing the byte sink")
        assertTrue(state.firstReturned && state.secondReturned, "both closes return")
        assertEquals(1, bytes.flushes, "the byte sink is flushed once")
        assertEquals(1, bytes.closes, "the byte sink is closed once")
    }

    /** Keeps what it is given, and on its first write tries to close [owner]. */
    private class ClosingSink : MediaByteSink {
        override val seekable: Boolean = false
        var owner: MediaSink? = null
        var refusal: Throwable? = null
        var bytes = ByteArray(0)
        var closes = 0

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (refusal == null) {
                refusal = runCatching { owner?.close() }.exceptionOrNull() ?: AssertionError("the close returned")
            }
            this.bytes += bytes.copyOfRange(offset, offset + length)
        }

        override fun seek(position: Long): Unit = error("a sink that cannot seek is never asked to")

        override fun close() {
            closes++
        }
    }

    /** Takes every write, and waits in [flush] until [releaseFlush] is set. */
    private class SlowFlushSink : MediaByteSink {
        override val seekable: Boolean = false
        @Volatile var flushEntered = false
        @Volatile var releaseFlush = false
        @Volatile var flushes = 0
        @Volatile var closes = 0

        override fun write(bytes: ByteArray, offset: Int, length: Int): Unit = Unit

        override fun seek(position: Long): Unit = error("a sink that cannot seek is never asked to")

        override fun flush() {
            flushes++
            flushEntered = true
            runBlocking { while (!releaseFlush) delay(1) }
        }

        override fun close() {
            closes++
        }
    }

    private class CloseState {
        @Volatile var firstReturned = false
        @Volatile var secondReturned = false
    }

    private class BytesSource(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close(): Unit = Unit
    }
}
