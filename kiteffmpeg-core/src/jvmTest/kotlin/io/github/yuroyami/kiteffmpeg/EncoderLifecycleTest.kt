package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An encoder is a one-way object, and says so.
 *
 * Driving one to the end flushes its codec. Offering it a second flow used to LOOK like it worked:
 * every frame was consumed and closed, the encoded count went up, and nothing whatsoever reached
 * the file, because the codec had already been drained. A caller appending a second batch got a
 * silent no-op and a file missing everything it thought it had written.
 */
class EncoderLifecycleTest {

    private val files = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        files.forEach { it.delete() }
        files.clear()
    }

    private fun tmp(name: String): File =
        File.createTempFile("kiteffmpeg-lifecycle-$name", ".mkv").also { files += it }

    private fun frames(count: Int, from: Int = 0) = (0 until count).asFlow().map { i ->
        val n = from + i
        val w = 64
        val h = 64
        val y = ByteArray(w * h) { ((it + n * 3) % 200).toByte() }
        val u = ByteArray(w * h / 4) { 100.toByte() }
        val v = ByteArray(w * h / 4) { (140 + n % 40).toByte() }
        Frame.ofVideo(y + u + v, w, h, PixelFormat.Yuv420p, n * 40_000L)
    }

    private fun MediaSink.video() = addVideoEncoder(
        VideoEncoderSpec(
            codec = CodecId("mpeg4"),
            width = 64, height = 64,
            frameRate = Rational(25, 1),
            bitrateBps = 400_000,
        ),
    )

    /**
     * Red by removing the `drained` check from `EncoderCore.beginDrive`: the second drive then
     * returns normally having written nothing at all, which is the silent no-op this closes.
     */
    @Test
    fun `a second drive is refused instead of quietly encoding nothing`() {
        val file = tmp("second-drive")
        MediaSink.open(file.absolutePath).use { sink ->
            val encoder = sink.video()
            runBlocking { encoder.drive(frames(20)) }
            val encodedFirst = encoder.core.framesEncoded
            assertTrue(encodedFirst > 0, "the first drive must actually encode")

            val refusal = runCatching { runBlocking { encoder.drive(frames(20, from = 20)) } }
                .exceptionOrNull()

            assertTrue(
                refusal is IllegalStateException,
                "a drained encoder must refuse a second flow typed, got $refusal",
            )
            assertTrue(
                refusal.message?.contains("one-way") == true,
                "and say why: ${refusal.message}",
            )
        }
    }

    /** The frames of a refused second drive are not consumed, counted or closed. */
    @Test
    fun `a refused second drive does not touch the frames it was given`() {
        val file = tmp("untouched")
        MediaSink.open(file.absolutePath).use { sink ->
            val encoder = sink.video()
            runBlocking { encoder.drive(frames(10)) }
            val after = encoder.core.framesEncoded

            runCatching { runBlocking { encoder.drive(frames(10, from = 10)) } }

            assertEquals(
                after,
                encoder.core.framesEncoded,
                "the refused drive still counted frames as encoded, which is the lie itself",
            )
        }
    }

    /** And one full drive still produces a file that opens and carries its stream. */
    @Test
    fun `one drive still writes a whole file`() {
        val file = tmp("whole")
        MediaSink.open(file.absolutePath).use { sink ->
            val encoder = sink.video()
            runBlocking { encoder.drive(frames(30)) }
        }
        MediaSource.open(file.absolutePath).use { source ->
            assertEquals(1, source.streams.size)
        }
    }
}
