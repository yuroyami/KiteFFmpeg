package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Close racing a running operation, for the objects Group 1 promises about (audit P0-07).
 *
 * What a PASS proves is bounded and stated: with the operation leases in place these loops must
 * complete with every refusal typed as IllegalStateException and the process alive. WITHOUT the
 * leases the same loops dereference freed AVFrames and codec contexts, which is a crash that is
 * real but probabilistic, so a red run of this suite on the old code is likely rather than
 * guaranteed. The leases are additionally pinned by the deterministic closed-object tests that
 * already exist; this suite is the concurrent half.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class CloseRaceTest {

    private fun smallFrame(): Frame {
        val w = 32
        val h = 32
        val y = ByteArray(w * h) { (it % 200).toByte() }
        val u = ByteArray(w * h / 4) { 100.toByte() }
        val v = ByteArray(w * h / 4) { 140.toByte() }
        return Frame.ofVideo(y + u + v, w, h, PixelFormat.Yuv420p, 0)
    }

    @Test
    fun closingAFrameWhileAnotherThreadReadsItNeverCrashes() = runBlocking {
        repeat(300) {
            val frame = smallFrame()
            val reader = launch(Dispatchers.Default) {
                try {
                    while (true) {
                        frame.copyPlanesToByteArray()
                        frame.info
                    }
                } catch (_: IllegalStateException) {
                    // The typed refusal, which is the correct outcome once close lands.
                }
            }
            frame.close()
            reader.join()
        }
        assertTrue(true, "300 close-vs-read races completed without the process dying")
    }

    @Test
    fun closingADecoderWhileAnotherThreadReceivesNeverCrashes() = runBlocking {
        // One real file, reused: the race is per decoder, not per container.
        val path = tmpPath("close-race.mp4")
        writeTinyVideo(path)
        MediaSource.open(path).use { source ->
            val video = source.streams.first { it.type == MediaType.Video }
            repeat(200) {
                val decoder = source.openDecoder(video)
                val worker = launch(Dispatchers.Default) {
                    try {
                        while (true) {
                            decoder.receive()?.close()
                        }
                    } catch (_: IllegalStateException) {
                    }
                }
                decoder.close()
                worker.join()
            }
        }
        platform.posix.remove(path)
        assertTrue(true, "200 close-vs-receive races completed without the process dying")
    }

    @Test
    fun closingAGraphWhileAnotherThreadFeedsItNeverCrashes() = runBlocking {
        repeat(200) {
            val graph = FilterGraph.buildVideo(
                description = "null",
                width = 32, height = 32,
                pixelFormat = PixelFormat.Yuv420p,
                timeBase = Rational(1, 25),
                frameRate = Rational(25, 1),
                sampleAspectRatio = Rational(1, 1),
            )
            val feeder = launch(Dispatchers.Default) {
                try {
                    while (true) {
                        graph.feedInput(0, smallFrame()) { }
                    }
                } catch (_: IllegalStateException) {
                } catch (_: FFmpegException) {
                    // A feed that lost the race inside FFmpeg's own return codes is a refusal too.
                }
            }
            graph.close()
            feeder.join()
        }
        assertTrue(true, "200 close-vs-feed races completed without the process dying")
    }

    private fun tmpPath(name: String): String {
        val root = systemTempRoot()
        return "$root/kiteffmpeg-$name"
    }

    private fun writeTinyVideo(path: String) {
        MediaSink.open(path).use { sink ->
            val enc = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 32, height = 32,
                    frameRate = Rational(25, 1),
                    bitrateBps = 200_000,
                ),
            )
            runBlocking {
                enc.drive(
                    kotlinx.coroutines.flow.flow {
                        repeat(25) { i ->
                            val w = 32; val h = 32
                            val y = ByteArray(w * h) { ((it + i * 3) % 200).toByte() }
                            val u = ByteArray(w * h / 4) { 100.toByte() }
                            val v = ByteArray(w * h / 4) { 140.toByte() }
                            emit(Frame.ofVideo(y + u + v, w, h, PixelFormat.Yuv420p, i * 40_000L))
                        }
                    },
                )
            }
        }
    }
}
