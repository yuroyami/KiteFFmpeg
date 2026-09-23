package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An open option that FFmpeg does not use comes back in [MediaSource.unusedOpenOptions] with its
 * exact text, including a character outside the basic multilingual plane.
 *
 * The bridge sends the key to FFmpeg as standard UTF-8. The key used to come back through the
 * JVM's modified UTF-8 reader, which reads one four-byte character as four unrelated characters.
 */
class JvmUnusedOptionKeyTest {

    /** U+1F600, an emoji, which takes four bytes in standard UTF-8. */
    private val key = "kite-😀-unused"

    @Test
    fun aPathOpenReportsAnUnusedKeyAboveTheBasicPlaneUnchanged() {
        val media = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        try {
            MediaSource.open(media, mapOf(key to "1")).use { source ->
                assertEquals(listOf(key), source.unusedOpenOptions)
            }
        } finally {
            deleteContractPath(media)
        }
    }

    @Test
    fun aByteSourceOpenReportsAnUnusedKeyAboveTheBasicPlaneUnchanged() {
        MediaSource.open(BytesSource(ContractMedia.bytes), mapOf(key to "1")).use { source ->
            assertEquals(listOf(key), source.unusedOpenOptions)
        }
    }

    private class BytesSource(private val bytes: ByteArray) : MediaByteSource {
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

        override fun close() = Unit
    }
}
