package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FFmpeg copies ID3v1 and RIFF INFO tag bytes unchanged, and they are rarely UTF-8. Such a file
 * opens on every backend, and each malformed sequence reads as U+FFFD (#75).
 */
class NonUtf8TagContractTest {

    @Test
    fun aLatin1Id3v1TitleOpensAndReadsAsAReplacementCharacter() {
        // 40 silent MPEG-1 Layer III frames (128 kb/s, 44.1 kHz, 417 bytes each), then an ID3v1
        // tag whose title is "Björk" in ISO-8859-1.
        val frame = ByteArray(417).also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = 0x90.toByte() }
        val tag = ByteArray(128).also {
            "TAG".encodeToByteArray().copyInto(it, 0)
            byteArrayOf(0x42, 0x6A, 0xF6.toByte(), 0x72, 0x6B).copyInto(it, 3)
            it[127] = 0xFF.toByte()
        }
        val bytes = ByteArray(40 * frame.size + tag.size)
        repeat(40) { frame.copyInto(bytes, it * frame.size) }
        tag.copyInto(bytes, 40 * frame.size)
        MediaSource.open(BytesSource(bytes)).use { source ->
            assertEquals("Bj�rk", source.metadata["title"])
        }
    }

    @Test
    fun aLatin1RiffInfoTitleOpensAndReadsAsAReplacementCharacter() {
        MediaSource.open(BytesSource(wavWithTitle(byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte())))).use { source ->
            assertEquals("Caf�", source.metadata["title"])
        }
    }

    @Test
    fun aUtf8RiffInfoTitleStillReadsExactly() {
        MediaSource.open(BytesSource(wavWithTitle("Café".encodeToByteArray()))).use { source ->
            assertEquals("Café", source.metadata["title"])
        }
    }

    /** One second of 8 kHz mono 16-bit silence with a LIST/INFO chunk whose INAM holds [title]. */
    private fun wavWithTitle(title: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        fun bytes(value: ByteArray) = value.forEach { out += it }
        fun text(value: String) = bytes(value.encodeToByteArray())
        fun int(value: Int) = repeat(4) { out += ((value shr (8 * it)) and 0xFF).toByte() }
        fun short(value: Int) = repeat(2) { out += ((value shr (8 * it)) and 0xFF).toByte() }
        val name = title + 0
        val namePadded = if (name.size % 2 == 0) name else name + 0
        val listSize = 4 + 8 + namePadded.size
        val dataSize = 8_000 * 2
        text("RIFF"); int(4 + (8 + 16) + (8 + listSize) + (8 + dataSize)); text("WAVE")
        text("fmt "); int(16); short(1); short(1); int(8_000); int(16_000); short(2); short(16)
        text("LIST"); int(listSize); text("INFO"); text("INAM"); int(name.size); bytes(namePadded)
        text("data"); int(dataSize); bytes(ByteArray(dataSize))
        return out.toByteArray()
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

        override fun close() {}
    }
}
