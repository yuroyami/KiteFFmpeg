package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A byte source whose close throws still gets every other release of its source: the interrupt
 * binding and the native cell of the open, and the context itself (#112).
 */
class ThrowingSourceCloseContractTest {

    private val refusal = IllegalStateException("the byte source refused to close")

    private inner class ThrowingOnClose(
        private val bytes: ByteArray,
        private val sizeFailure: Throwable? = null,
    ) : MediaByteSource {
        private var position = 0
        var closes = 0
        override val size: Long get() = sizeFailure?.let { throw it } ?: bytes.size.toLong()
        override val seekable: Boolean = true

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

        override fun close() {
            closes++
            throw refusal
        }
    }

    @Test
    fun aThrowingByteSourceCloseStillReleasesTheOpensInterrupt() {
        val baseline = contractLiveHandleCount()
        val cancel = OpenInterrupt()
        val source = MediaSource.open(ThrowingOnClose(ContractMedia.bytes), emptyMap(), cancel)
        assertEquals(1, cancel.boundCount, "the open binds its request to the source")

        val thrown = assertFailsWith<IllegalStateException> { source.close() }
        assertSame(refusal, thrown, "the caller receives the byte source's own failure")
        assertEquals(0, cancel.boundCount, "the request is unbound although the byte source threw")
        assertEquals(baseline, contractLiveHandleCount(), "the context and the interrupt cell are freed")
        source.close()
    }

    @Test
    fun aFailedOpenKeepsItsOwnFailureWhenTheCloseThrowsToo() {
        val sizeFailure = IllegalStateException("size")
        val unreadable = ThrowingOnClose(ContractMedia.bytes, sizeFailure)
        val thrown = assertFails { MediaSource.open(unreadable) }
        assertSame(sizeFailure, thrown, "the caller sees why the open failed, not the close")
        assertEquals(1, unreadable.closes, "the open still closes the source once")
        assertTrue(refusal in thrown.suppressedExceptions, "the close failure rides on it")
    }

    @Test
    fun aFailedProbeKeepsItsTypedFailureWhenTheCloseThrowsToo() {
        val garbage = ThrowingOnClose(ByteArray(64 * 1024) { (it * 131 + 7).toByte() })
        val thrown = assertFailsWith<FFmpegException> { MediaSource.open(garbage) }
        assertEquals(1, garbage.closes, "the open still closes the source once")
        assertTrue(refusal in thrown.suppressedExceptions, "the close failure rides on the probe's")
    }
}
