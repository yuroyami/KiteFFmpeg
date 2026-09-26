package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * A byte source whose close throws still gets every other release of its source: the interrupt
 * binding and the native cell of the open, and the context itself (#112).
 */
class ThrowingSourceCloseContractTest {

    private val refusal = IllegalStateException("the byte source refused to close")

    private inner class ThrowingOnClose(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
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

        override fun close() = throw refusal
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
}
