@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A copy stream accepts packets the caller read itself.
 *
 * Until this, the only way a demuxed packet reached a muxer was through [Remuxer] or [Transcoder],
 * which own the read loop. A caller that wants to tee (write the packets it is already reading to a
 * second file, record while it plays, split on its own boundaries) had the reader and the sink and
 * no way to join them, although the internals to do it had existed all along.
 */
class PacketTeeTest {

    private val tmpFiles = mutableListOf<String>()

    private fun tmp(name: String): String =
        "${systemTempRoot()}/kiteffmpeg-test-$name".also { tmpFiles += it }

    @AfterTest
    fun cleanUp() {
        tmpFiles.forEach { remove(it) }
        tmpFiles.clear()
    }

    /** One tightly-packed yuv420p frame with per-frame varying luma. */
    private fun yuvFrame(width: Int, height: Int, index: Int): ByteArray {
        val y = ByteArray(width * height) { i -> ((i + index * 7) % 200 + 20).toByte() }
        val u = ByteArray(width * height / 4) { 100.toByte() }
        val v = ByteArray(width * height / 4) { (140 + index % 40).toByte() }
        return y + u + v
    }

    private fun writeTestVideo(path: String, frames: Int = 12, width: Int = 64, height: Int = 64) {
        MediaSink.open(path).use { sink ->
            val enc = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = width, height = height,
                    frameRate = Rational(30, 1),
                    bitrateBps = 500_000,
                ),
            )
            runBlocking {
                enc.drive(
                    (0 until frames).asFlow().map { i ->
                        Frame.ofVideo(
                            bytes = yuvFrame(width, height, i),
                            width = width, height = height,
                            pixelFormat = PixelFormat.Yuv420p,
                            ptsMicros = i * 1_000_000L / 30,
                        )
                    },
                )
            }
        }
    }

    @Test
    fun aCallerCanWriteThePacketsItReadItself() {
        val source = tmp("tee-in.mkv")
        val out = tmp("tee-out.mkv")
        writeTestVideo(source)

        var written = 0
        MediaSource.open(source).use { src ->
            val video = src.primaryVideo ?: error("no video stream")
            MediaSink.open(out).use { sink ->
                val copy = sink.addCopyStream(src, video)
                src.openPacketReader(listOf(video)).use { reader ->
                    while (true) {
                        val packet = reader.read() ?: break
                        packet.use {
                            copy.write(it)
                            written++
                        }
                    }
                }
            }
        }
        assertTrue(written > 0, "the fixture produced no packets to tee")

        // What came out is playable and carries what went in.
        MediaSource.open(out).use { result ->
            val video = result.primaryVideo ?: error("the teed file has no video stream")
            var readBack = 0
            result.openPacketReader(listOf(video)).use { reader ->
                while (true) {
                    val packet = reader.read() ?: break
                    packet.close()
                    readBack++
                }
            }
            assertEquals(written, readBack, "the teed file holds a different number of packets")
        }
    }

    @Test
    fun theCallerStillOwnsThePacketAfterWriting() {
        // The write must not consume the caller's packet: a tee writes the SAME packet to two
        // places, and the ordinary use is to keep reading from it afterwards.
        val source = tmp("owned-in.mkv")
        val out = tmp("owned-out.mkv")
        writeTestVideo(source)

        MediaSource.open(source).use { src ->
            val video = src.primaryVideo ?: error("no video stream")
            MediaSink.open(out).use { sink ->
                val copy = sink.addCopyStream(src, video)
                src.openPacketReader(listOf(video)).use { reader ->
                    val first = reader.read() ?: error("the fixture produced no packet")
                    first.use {
                        val sizeBefore = it.sizeBytes
                        copy.write(it)
                        // Readable afterwards, which is what "the packet stays the caller's" means.
                        assertEquals(sizeBefore, it.sizeBytes, "the write consumed the caller's payload")
                        assertEquals(video.index, it.streamIndex, "the write moved the caller's stream index")
                    }
                    while (true) (reader.read() ?: break).close()
                }
            }
        }
    }

    @Test
    fun aClosedPacketIsRefused() {
        val source = tmp("closed-in.mkv")
        val out = tmp("closed-out.mkv")
        writeTestVideo(source)

        MediaSource.open(source).use { src ->
            val video = src.primaryVideo ?: error("no video stream")
            MediaSink.open(out).use { sink ->
                val copy = sink.addCopyStream(src, video)
                src.openPacketReader(listOf(video)).use { reader ->
                    val packet = reader.read() ?: error("the fixture produced no packet")
                    packet.close()
                    assertFailsWith<IllegalStateException> { copy.write(packet) }
                    while (true) (reader.read() ?: break).close()
                }
            }
        }
    }
}
