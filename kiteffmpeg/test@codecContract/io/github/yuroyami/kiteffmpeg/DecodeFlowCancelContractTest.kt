package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A decode flow cancelled between two frames closes the copy it made for the next one instead of
 * leaking it (#199). The collector cancels its own job and returns normally, which is the shape a
 * cancel from another thread takes when it lands while the next packet decodes: the flow's next
 * hand-over then meets a collector that can no longer receive.
 */
class DecodeFlowCancelContractTest {

    @Test
    fun aCancelledDecodeFlowLeaksNoFrame() = runTest {
        val total = MediaSource.open(Bytes(DecodeContractMedia.bytes)).use { source ->
            var count = 0
            source.decodedFrames(assertNotNull(source.primaryVideo)).collect { it.close(); count++ }
            count
        }
        assertTrue(total > 2, "the fixture decodes to more than two pictures")
        val baseline = contractLiveHandleCount()
        // Every place a cancel can land: at the drain the decoder gives its held pictures one after
        // another, with no packet read between them and so no check but the hand-over's.
        for (cancelAt in 1 until total) {
            MediaSource.open(Bytes(DecodeContractMedia.bytes)).use { source ->
                val video = assertNotNull(source.primaryVideo)
                var received = 0
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    source.decodedFrames(video).collect { frame ->
                        frame.close()
                        if (++received == cancelAt) currentCoroutineContext().job.cancel()
                    }
                }
                job.join()
                assertTrue(job.isCancelled, "the collector's job was cancelled")
                assertEquals(cancelAt, received, "the cancelled collector received no frame after the cancel")
            }
        }
        assertEquals(baseline, contractLiveHandleCount(), "every frame the flow made was closed")
    }

    private class Bytes(private val bytes: ByteArray) : MediaByteSource {
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
