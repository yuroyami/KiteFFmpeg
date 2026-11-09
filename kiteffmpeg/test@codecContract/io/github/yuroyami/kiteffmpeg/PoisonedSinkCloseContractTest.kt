package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * A sink whose stream setup failed after the muxer was mutated writes nothing more: an existing
 * copy stream refuses, and the close starts no header and leaves the output as it was, while still
 * releasing everything once (#216).
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class PoisonedSinkCloseContractTest {

    private class CountingSink : MediaByteSink {
        var written = 0L
        var closes = 0
        override val seekable: Boolean = true
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            written += length
        }
        override fun seek(position: Long) = Unit
        override fun close() {
            closes++
        }
    }

    @Test
    fun aPoisonedSinkWritesNothingAndClosesItsOutputOnce() {
        val input = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        val output = CountingSink()
        MediaSource.open(input).use { source ->
            val audio = assertNotNull(source.primaryAudio)
            val video = assertNotNull(source.primaryVideo)
            val baseline = contractLiveHandleCount()
            val sink = MediaSink.open(output, "matroska")
            val good = sink.addCopyStream(source, audio)
            MuxFaults.armOnce()
            try {
                assertFailsWith<FFmpegException> { sink.addCopyStream(source, video) }
            } finally {
                MuxFaults.disarm()
            }
            source.openPacketReader(listOf(audio)).use { reader ->
                val packet = assertNotNull(reader.read())
                packet.use { assertFailsWith<IllegalStateException> { good.write(it) } }
            }
            sink.close()
            assertEquals(0L, output.written, "the close of a poisoned sink wrote a header")
            assertEquals(1, output.closes, "the output is still closed once")
            assertEquals(baseline, contractLiveHandleCount(), "the sink released what it held")
        }
    }
}
