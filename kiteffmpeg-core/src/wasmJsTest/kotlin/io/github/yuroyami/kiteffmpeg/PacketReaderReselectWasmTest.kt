package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Focused runtime coverage for the real Wasm packet-reader selection path. */
@OptIn(KiteFFmpegLowLevelApi::class)
class PacketReaderReselectWasmTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun liveReselectChangesTheExactGateWithoutReopenOrBackfill() {
        val module = fakePacketReaderCodecModule()
        useCodecModule(module)
        val source = MediaSource.open(OneByteSource(), emptyMap())
        try {
            val first = source.streams[0]
            val second = source.streams[1]
            val reader = source.openPacketReader(listOf(first))
            try {
                assertEquals(1, fakePacketReaderSelectionMask(module))
                assertPacketStream(reader, first.index)
                assertEquals(1, fakePacketReaderCursor(module))

                val opensBefore = fakePacketReaderOpenCount(module)
                reader.reselect(listOf(second))
                assertEquals(opensBefore, fakePacketReaderOpenCount(module), "reselect must not reopen the source")
                assertEquals(1, fakePacketReaderCursor(module), "reselect must not move the demux cursor")
                assertEquals(2, fakePacketReaderSelectionMask(module))

                // The fake still surfaces the deselected stream at cursor 1. PacketReader must
                // discard it and return stream 1 at cursor 2, without rewinding to its old packet.
                assertPacketStream(reader, second.index)
                assertEquals(3, fakePacketReaderCursor(module))

                assertFailsWith<IllegalArgumentException> { reader.reselect(emptyList()) }
                assertFailsWith<IllegalArgumentException> {
                    reader.reselect(listOf(second.copy(index = Int.MAX_VALUE)))
                }
                assertEquals(2, fakePacketReaderSelectionMask(module), "refused requests must be non-mutating")
                assertPacketStream(reader, second.index)
                assertEquals(5, fakePacketReaderCursor(module))
            } finally {
                reader.close()
            }

            assertEquals(3, fakePacketReaderSelectionMask(module), "close must restore both streams")
            source.openPacketReader(listOf(first)).close()
        } finally {
            source.close()
        }
    }

    private fun assertPacketStream(reader: PacketReader, expected: Int) {
        val packet = assertNotNull(reader.read())
        try {
            assertEquals(expected, packet.streamIndex)
        } finally {
            packet.close()
        }
    }

    private class OneByteSource : MediaByteSource {
        override val size: Long = 1L
        override val seekable: Boolean = true
        private var consumed = false

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (consumed) return -1
            into[offset] = 0
            consumed = true
            return 1
        }

        override fun seek(position: Long) {
            consumed = position != 0L
        }

        override fun close(): Unit = Unit
    }
}
