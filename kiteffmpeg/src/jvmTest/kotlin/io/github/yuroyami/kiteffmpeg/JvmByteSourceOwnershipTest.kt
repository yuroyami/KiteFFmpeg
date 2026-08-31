package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Custom byte sources on the real JVM backend. The JNI harness suite that mirrors these only
 * compiles under the local phone scope, so the ownership rules live here too, where the ordinary
 * jvmTest task runs them.
 */
class JvmByteSourceOwnershipTest {

    /**
     * P1-01 on the JNI path. The open itself sat outside the scope that owns the source, so a throw
     * from it left the caller's source open for ever.
     */
    @Test
    fun aByteSourceIsClosedExactlyOnceWhenTheOpenFails() {
        val source = CountingCloseSource(ByteArray(4096) { 0x7A })
        assertFailsWith<FFmpegException> { MediaSource.open(source) }
        assertEquals(1, source.closes, "a failed open must close the source it took ownership of, once")
    }

    @Test
    fun aByteSourceThatCannotBeReadIsStillClosed() {
        val source = object : MediaByteSource {
            var closes = 0
            override val size: Long get() = 1024
            override val seekable: Boolean = true
            override fun read(into: ByteArray, offset: Int, length: Int): Int = error("this source always fails")
            override fun seek(position: Long) = Unit
            override fun close() { closes++ }
        }
        assertFailsWith<FFmpegException> { MediaSource.open(source) }
        assertEquals(1, source.closes, "a source that throws on read must still be closed once")
    }

    /** Counts closes rather than recording a flag, so a double close fails too. */
    private class CountingCloseSource(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        var closes = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean = true
        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }
        override fun seek(position: Long) { this.position = position.toInt() }
        override fun close() { closes++ }
    }
}
