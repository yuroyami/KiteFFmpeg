package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.time.measureTime

/**
 * What reading a frame's bytes costs, and that the bytes are right. The cost is printed and never
 * asserted, because a busy machine moves it; compare runs of this class alone.
 */
internal class FrameCopyCostContractTest {

    private fun reportCost(what: String, rounds: Int, copy: () -> ByteArray) {
        repeat(rounds / 10) { copy() }
        val elapsed = measureTime { repeat(rounds) { copy() } }
        println("frame copy cost: $what, ${elapsed.inWholeMicroseconds / rounds} us per copy")
    }

    @Test
    fun aVideoFrameCopiesToItsOwnBytes() {
        val width = 1920
        val height = 1080
        val bytes = ByteArray(width * height * 3 / 2) { (it * 31).toByte() }
        Frame.ofVideo(bytes, width, height, PixelFormat.Yuv420p, ptsMicros = 0L).use { frame ->
            assertContentEquals(bytes, frame.copyPlanesToByteArray())
            reportCost("1080p yuv420p", rounds = 200) { frame.copyPlanesToByteArray() }
        }
    }

    @Test
    fun anAudioFrameCopiesToItsOwnBytes() {
        val samples = 1024
        val bytes = ByteArray(samples * 2 * 4) { (it * 7).toByte() }
        Frame.ofAudio(bytes, samples, 48_000, 2, SampleFormat.FltP, ptsMicros = 0L).use { frame ->
            assertContentEquals(bytes, frame.copyPlanesToByteArray())
            reportCost("1024 samples of fltp stereo", rounds = 20_000) { frame.copyPlanesToByteArray() }
        }
    }
}
