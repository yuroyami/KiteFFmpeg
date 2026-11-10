package io.github.yuroyami.kiteffmpeg

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeReaperTest {
    /**
     * Ten frames and ten packets, half closed and half dropped (#242). The dropped ones are freed
     * once the collector finds them, each with a warning, and the native ledger returns to where it
     * started; the closed ones free once only.
     */
    @OptIn(KiteFFmpegLowLevelApi::class)
    @Test
    fun aDroppedFrameOrPacketIsFreedWithAWarning() {
        val lines = Collections.synchronizedList(mutableListOf<FFmpegLogLine>())
        FFmpeg.setLogSink(FFmpegLogLevel.Warning) { level, component, message ->
            if (component == "kiteffmpeg") lines += FFmpegLogLine(level, component, message)
        }
        try {
            val baseline = Internals.liveHandles()
            val leaksBefore = NativeReaper.leakCount()
            makeAndDrop(closeEvery = 2, count = 10)
            assertEquals(baseline + 10, Internals.liveHandles(), "the five dropped frames and five dropped packets")
            val deadline = System.nanoTime() + 20_000_000_000L
            while ((Internals.liveHandles() > baseline || NativeReaper.leakCount() < leaksBefore + 10) &&
                System.nanoTime() < deadline
            ) {
                System.gc()
                Thread.sleep(20)
            }
            assertEquals(baseline, Internals.liveHandles())
            assertTrue(NativeReaper.leakCount() >= leaksBefore + 10, "leaks counted: ${NativeReaper.leakCount() - leaksBefore}")
            assertTrue(lines.count { "Frame" in it.message } >= 5, lines.toString())
            assertTrue(lines.count { "Packet" in it.message } >= 5, lines.toString())
        } finally {
            FFmpeg.setLogSink(FFmpegLogLevel.Warning, null)
        }
    }

    @OptIn(KiteFFmpegLowLevelApi::class)
    private fun makeAndDrop(closeEvery: Int, count: Int) {
        repeat(count) { i ->
            val frame = Frame.ofVideo(ByteArray(16 * 16 * 3 / 2), 16, 16, PixelFormat.Yuv420p, i.toLong())
            val packet = Packet(Internals.packetAlloc(), Rational.Tb_us)
            if (i % closeEvery == 0) {
                frame.close()
                frame.close()
                packet.close()
            }
        }
    }
}
