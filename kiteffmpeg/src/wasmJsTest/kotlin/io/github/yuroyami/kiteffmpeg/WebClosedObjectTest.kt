package io.github.yuroyami.kiteffmpeg

import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * A closed packet, packet reader, stream decoder or media source throws [IllegalStateException] on
 * the web, with the message the JVM, Android and native backends use. The web used to throw
 * [FFmpegException] here, so a caller's catch clause behaved differently per platform.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class WebClosedObjectTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    private fun openSource(): Pair<JsAny, MediaSource> {
        val module = fakeDecodeCodecModule()
        useCodecModule(module)
        setFakeDecodeScript(module, "gg")
        return module to MediaSource.open(OneByteSource(), emptyMap())
    }

    @Test
    fun aClosedPacketThrowsIllegalStateException() {
        val (_, source) = openSource()
        try {
            val packet = source.openPacketReader(listOf(source.streams[0])).use { reader ->
                assertNotNull(reader.read(), "the fake container yields a packet")
            }
            packet.close()
            val refusal = assertFailsWith<IllegalStateException> { packet.pts }
            assertEquals("Packet is closed", refusal.message)
            assertFailsWith<IllegalStateException> { packet.copy() }
            assertFailsWith<IllegalStateException> { packet.copyBytes() }
        } finally {
            source.close()
        }
    }

    @Test
    fun aClosedPacketReaderThrowsIllegalStateException() {
        val (_, source) = openSource()
        try {
            val reader = source.openPacketReader(listOf(source.streams[0]))
            reader.close()
            val refusal = assertFailsWith<IllegalStateException> { reader.read() }
            assertEquals("PacketReader is closed", refusal.message)
            assertFailsWith<IllegalStateException> { reader.seek(0L, SeekDirection.Backward, null) }
            assertFailsWith<IllegalStateException> { reader.reselect(listOf(source.streams[0])) }
        } finally {
            source.close()
        }
    }

    @Test
    fun aClosedDecoderThrowsIllegalStateException() {
        val (_, source) = openSource()
        try {
            val decoder = source.openDecoder(source.streams[0])
            decoder.close()
            val refusal = assertFailsWith<IllegalStateException> { decoder.send(null) }
            assertEquals("StreamDecoder is closed", refusal.message)
            assertFailsWith<IllegalStateException> { decoder.receive() }
            assertFailsWith<IllegalStateException> { decoder.flush() }
        } finally {
            source.close()
        }
    }

    @Test
    fun sendingAClosedPacketThrowsIllegalStateException() {
        val (_, source) = openSource()
        try {
            val packet = source.openPacketReader(listOf(source.streams[0])).use { reader ->
                assertNotNull(reader.read(), "the fake container yields a packet")
            }
            packet.close()
            source.openDecoder(source.streams[0]).use { decoder ->
                val refusal = assertFailsWith<IllegalStateException> { decoder.send(packet) }
                assertEquals("Packet is closed", refusal.message)
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun aClosedSourceThrowsIllegalStateException() {
        val (_, source) = openSource()
        source.close()
        val refusal = assertFailsWith<IllegalStateException> { source.formatName }
        assertEquals("MediaSource is closed", refusal.message)
        assertFailsWith<IllegalStateException> { source.durationMicros }
        assertFailsWith<IllegalStateException> { source.openPacketReader(emptyList()) }
    }

    @Test
    fun aReaderThatOutlivedItsSourceThrowsIllegalStateException() {
        val (_, source) = openSource()
        val stream = source.streams[0]
        val reader = source.openPacketReader(listOf(stream))
        source.close()
        try {
            assertFailsWith<IllegalStateException> { reader.read() }
        } finally {
            reader.close()
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
