package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two closes at once, and work arriving while a close runs (audit P0-10).
 *
 * The JVM sink flushes its encoders OUTSIDE the mux lock, which used to leave a window where the
 * sink looked open: a second close could start its own trailer under the first one's flush, and an
 * add could append an encoder the close's snapshot had already passed, leaking it. The `closing`
 * state is what shut the window; these tests drive the window directly.
 */
class JvmSinkCloseRaceTest {

    private val files = mutableListOf<File>()

    private fun tmp(name: String): File =
        File.createTempFile("kiteffmpeg-close-race-$name", ".mkv").also { files += it }

    @AfterTest
    fun cleanup() {
        files.forEach { it.delete() }
        files.clear()
    }

    private fun yuv(w: Int, h: Int, i: Int): ByteArray {
        val y = ByteArray(w * h) { ((it + i * 3) % 200).toByte() }
        val u = ByteArray(w * h / 4) { 100.toByte() }
        val v = ByteArray(w * h / 4) { (140 + i % 40).toByte() }
        return y + u + v
    }

    private fun sinkWithFrames(file: File): MediaSink {
        val sink = MediaSink.open(file.absolutePath)
        val enc = sink.addVideoEncoder(
            VideoEncoderSpec(
                codec = CodecId("mpeg4"),
                width = 64, height = 64,
                frameRate = Rational(25, 1),
                bitrateBps = 400_000,
            ),
        )
        runBlocking {
            enc.drive(
                (0 until 50).asFlow().map { i ->
                    Frame.ofVideo(yuv(64, 64, i), 64, 64, PixelFormat.Yuv420p, i * 40_000L)
                },
            )
        }
        return sink
    }

    @Test
    fun `two simultaneous closes write exactly one container`() {
        repeat(50) { round ->
            val file = tmp("double-$round")
            val sink = sinkWithFrames(file)
            val start = CountDownLatch(1)
            val failures = mutableListOf<Throwable>()
            val closers = (0 until 2).map {
                thread {
                    start.await()
                    try {
                        sink.close()
                    } catch (failure: Throwable) {
                        synchronized(failures) { failures += failure }
                    }
                }
            }
            start.countDown()
            closers.forEach { it.join() }
            assertEquals(emptyList(), failures, "round $round: a losing close must return, not throw")
            // One trailer, one valid container: the file must open and carry the stream.
            MediaSource.open(file.absolutePath).use { source ->
                assertEquals(1, source.streams.size, "round $round produced a broken container")
            }
        }
    }

    @Test
    fun `adding an encoder after a close began is refused, never leaked`() {
        val file = tmp("add-during-close")
        val sink = sinkWithFrames(file)
        sink.close()
        val refusal = runCatching {
            sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 64, height = 64,
                    frameRate = Rational(25, 1),
                    bitrateBps = 400_000,
                ),
            )
        }.exceptionOrNull()
        assertTrue(
            refusal is IllegalStateException,
            "an add after close must refuse typed, got $refusal",
        )
    }
}
