package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * An [OpenInterrupt] passed to [MediaSource.open]: raised before the open it fails the open at
 * once, and raised after it the source it returned stops like one [MediaSource.interrupt] was
 * called on.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class OpenInterruptContractTest {

    private fun mediaPath(): String = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)

    @Test
    fun aRequestRaisedBeforeTheOpenFailsItAtOnce() {
        val request = OpenInterrupt().apply { interrupt() }
        val failure = assertFailsWith<FFmpegException> { MediaSource.open(mediaPath(), emptyMap(), request) }
        assertIs<FFmpegError.Interrupted>(failure.error, "the failure must be typed, was ${failure.error}")
    }

    @Test
    fun aRequestRaisedAfterTheOpenStopsTheSourceAndCloseStaysLegal() {
        val request = OpenInterrupt()
        val source = MediaSource.open(mediaPath(), emptyMap(), request)
        try {
            val reader = source.openPacketReader(source.streams)
            try {
                assertNotNull(reader.read(), "the fixture must demux before the interrupt").close()
                request.interrupt()
                val failure = assertFailsWith<FFmpegException>("a read after the request must fail, not block") {
                    while (true) {
                        val packet = reader.read() ?: break
                        packet.close()
                    }
                }
                assertIs<FFmpegError.Interrupted>(failure.error, "the failure must be typed, was ${failure.error}")
            } finally {
                reader.close()
            }
        } finally {
            source.close()
        }
        // The source unbound itself at close, so a second request reaches nothing and is harmless.
        request.interrupt()
    }

    @Test
    fun aRequestRaisedAfterTheSourceClosedIsHarmless() {
        val request = OpenInterrupt()
        MediaSource.open(mediaPath(), emptyMap(), request).close()
        request.interrupt()
    }
}
