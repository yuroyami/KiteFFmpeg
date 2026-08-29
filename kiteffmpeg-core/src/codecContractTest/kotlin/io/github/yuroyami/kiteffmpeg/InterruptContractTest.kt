package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * KC-CANCEL's observable contract, on every backend that runs this suite: after
 * [MediaSource.interrupt], blocking calls fail fast with a typed [FFmpegError.Interrupted],
 * and close remains legal. FFmpeg polls the interrupt seam at the top of every blocking loop,
 * which is what makes the fail-fast half testable without arranging a real wedge.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class InterruptContractTest {

    private fun mediaPath(): String = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)

    @Test
    fun interruptPoisonsBlockingCallsAndLeavesCloseLegal() {
        val source = MediaSource.open(mediaPath())
        try {
            val reader = source.openPacketReader(source.streams)
            try {
                val healthy = assertNotNull(reader.read(), "the fixture must demux before the interrupt")
                healthy.close()

                source.interrupt()

                val readFailure = assertFailsWith<FFmpegException>("an interrupted read must fail, not block") {
                    while (true) {
                        val packet = reader.read() ?: break
                        packet.close()
                    }
                }
                assertIs<FFmpegError.Interrupted>(
                    readFailure.error,
                    "the failure must be typed as Interrupted, was ${readFailure.error}",
                )
                if (source.isSeekable) {
                    val seekFailure = assertFailsWith<FFmpegException>("an interrupted seek must fail, not block") {
                        reader.seek(0, SeekDirection.Backward)
                    }
                    assertIs<FFmpegError.Interrupted>(seekFailure.error)
                }
            } finally {
                reader.close()
            }
        } finally {
            source.close()
        }
    }
}
