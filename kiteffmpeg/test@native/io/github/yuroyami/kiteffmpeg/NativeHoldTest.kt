package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_packet_alloc
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertTrue
import platform.posix.usleep

class NativeHoldTest {
    /**
     * Ten frames and ten packets, half closed and half dropped (#242). The cleaner frees the
     * dropped ones once the collector finds them, each with a warning, and frees none twice.
     */
    @OptIn(NativeRuntimeApi::class, KiteFFmpegLowLevelApi::class)
    @Test
    fun aDroppedFrameOrPacketIsFreedWithAWarning() {
        val lines = mutableListOf<String>()
        val guard = kotlinx.atomicfu.locks.SynchronizedObject()
        FFmpeg.setLogSink(FFmpegLogLevel.Warning) { _, component, message ->
            if (component == "kiteffmpeg") kotlinx.atomicfu.locks.synchronized(guard) { lines += message }
        }
        try {
            val before = NativeLeaks.count.value
            makeAndDrop(10)
            var waited = 0
            while (NativeLeaks.count.value < before + 10 && waited < 20_000) {
                GC.collect()
                usleep(20_000u)
                waited += 20
            }
            val freed = NativeLeaks.count.value - before
            assertTrue(freed >= 10, "freed late: $freed")
            kotlinx.atomicfu.locks.synchronized(guard) {
                assertTrue(lines.count { "Frame" in it } >= 5, lines.toString())
                assertTrue(lines.count { "Packet" in it } >= 5, lines.toString())
            }
        } finally {
            FFmpeg.setLogSink(FFmpegLogLevel.Warning, null)
        }
    }

    @OptIn(ExperimentalForeignApi::class, KiteFFmpegLowLevelApi::class)
    private fun makeAndDrop(count: Int) {
        repeat(count) { i ->
            val frame = Frame.ofVideo(ByteArray(16 * 16 * 3 / 2), 16, 16, PixelFormat.Yuv420p, i.toLong())
            val packet = Packet(checkNotNull(ffkmp_packet_alloc()), Rational.Tb_us)
            if (i % 2 == 0) {
                frame.close()
                frame.close()
                packet.close()
            }
        }
    }
}
