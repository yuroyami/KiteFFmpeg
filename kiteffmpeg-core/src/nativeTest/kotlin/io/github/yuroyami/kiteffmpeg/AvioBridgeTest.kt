package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The custom AVIO bridge (M1, KitePlayer KPKMP 17.12) proved end to end: a real mp4 is
 * synthesized through the encode pipeline, read back ENTIRELY through a [MediaByteSource]
 * over its in-memory bytes, and the demux/decode results must match the path open's. The
 * call counters prove the bytes actually flowed through the Kotlin callbacks rather than
 * any path fallback.
 */
class AvioBridgeTest {

    private val tmpFiles = mutableListOf<String>()

    private fun tmp(name: String): String {
        val root = systemTempRoot()
        return "$root/kiteffmpeg-avio-$name".also { tmpFiles += it }
    }

    @AfterTest
    fun cleanup() {
        tmpFiles.forEach { remove(it) }
        tmpFiles.clear()
    }

    /** Noisy luma on purpose: incompressible frames keep the file well past the 64 KiB AVIO
     *  buffer, which is what forces real seek traffic through the bridge. */
    private fun yuvFrame(width: Int, height: Int, index: Int): ByteArray {
        val y = ByteArray(width * height) { i -> (((i * 1103515245 + index * 12345) ushr 13) and 0xFF).toByte() }
        val u = ByteArray(width * height / 4) { 100.toByte() }
        val v = ByteArray(width * height / 4) { (140 + index % 40).toByte() }
        return y + u + v
    }

    private fun writeTestVideo(path: String, frames: Int = 30, width: Int = 64, height: Int = 64) {
        MediaSink.open(path).use { sink ->
            val enc = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = width, height = height,
                    frameRate = Rational(30, 1),
                    bitrateBps = 2_000_000,
                )
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
                    }
                )
            }
        }
    }

    /** An in-memory byte source with call counters and a closed flag. */
    private class MemorySource(private val bytes: ByteArray) : MediaByteSource {
        var position = 0
        var reads = 0
        var seeks = 0
        var closed = false
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean = true
        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }
        override fun seek(position: Long) {
            seeks++
            require(position in 0..bytes.size.toLong()) { "seek out of range: $position" }
            this.position = position.toInt()
        }
        override fun close() { closed = true }
    }

    /** Plain posix read keeps the fixture free of the API under test. */
    private fun readFileBytes(path: String): ByteArray {
        val file = platform.posix.fopen(path, "rb") ?: error("cannot open $path")
        try {
            platform.posix.fseek(file, 0, platform.posix.SEEK_END)
            val size = platform.posix.ftell(file).toInt()
            platform.posix.fseek(file, 0, platform.posix.SEEK_SET)
            val bytes = ByteArray(size)
            bytes.usePinned { pinned ->
                check(platform.posix.fread(pinned.addressOf(0), 1u, size.toULong(), file).toInt() == size) {
                    "short read of the fixture file"
                }
            }
            return bytes
        } finally {
            platform.posix.fclose(file)
        }
    }

    @Test
    fun aByteSourceOpenMatchesThePathOpenAndFlowsThroughTheCallbacks() {
        val path = tmp("bridge.mp4")
        // Noisy 320x240 frames: comfortably larger than the bridge's 64 KiB AVIO buffer, so
        // the mp4 trailing-moov probe and the mid-file seek below MUST call the seek callback
        // instead of being satisfied inside the buffered window.
        writeTestVideo(path, frames = 120, width = 320, height = 240)
        val bytes = readFileBytes(path)

        val pathStreams = MediaSource.open(path).use { it.streams.size to it.durationMicros }

        val source = MemorySource(bytes)
        MediaSource.open(source).use { src ->
            assertEquals(pathStreams.first, src.streams.size, "stream table differs from the path open")
            assertEquals(pathStreams.second, src.durationMicros, "duration differs from the path open")
            assertTrue(src.isSeekable, "a seekable byte source must yield a seekable input")

            val video = src.primaryVideo ?: error("no video stream through the bridge")
            val decoded = runBlocking { src.decodedFrames(video).toList() }
            try {
                assertTrue(decoded.size >= 118, "expected ~120 decoded frames, got ${decoded.size}")
            } finally {
                decoded.forEach { it.close() }
            }
            runBlocking { src.seekMicros(3_000_000) }
            assertTrue(source.reads > 0, "no read ever reached the byte source: the bridge is dead")
            assertTrue(source.seeks > 0, "no seek ever reached the byte source")
        }
        assertTrue(source.closed, "MediaSource.close must close the byte source it owns")
    }

    @Test
    fun anUnseekableByteSourceRefusesSeeksTyped() {
        // mpegts, not mp4: the mov demuxer needs a seekable input for its trailing moov, while
        // a transport stream demuxes forward-only, which is exactly this case's point.
        val path = tmp("bridge-pipe.ts")
        writeTestVideo(path, frames = 10)
        val bytes = readFileBytes(path)

        val source = object : MediaByteSource {
            var position = 0
            override val size: Long? = null
            override val seekable: Boolean = false
            override fun read(into: ByteArray, offset: Int, length: Int): Int {
                if (position >= bytes.size) return -1
                val count = minOf(length, bytes.size - position)
                bytes.copyInto(into, offset, position, position + count)
                position += count
                return count
            }
            override fun seek(position: Long) = error("seek must never be called on an unseekable source")
            override fun close() = Unit
        }
        MediaSource.open(source).use { src ->
            assertTrue(!src.isSeekable, "an unseekable byte source must yield an unseekable input")
            assertFailsWith<FFmpegException> { runBlocking { src.seekMicros(500_000) } }
        }
    }

    /**
     * P1-01. Ownership of the byte source transfers at the adapter, so an open that FAILS still
     * owes the caller a close. Both failure paths used to release only the internal reference and
     * leave the source open for ever.
     */
    @Test
    fun aByteSourceIsClosedExactlyOnceWhenTheOpenFails() {
        // Not media, so FFmpeg refuses it after reading: the failure happens inside the open.
        val source = CountingCloseSource(ByteArray(4096) { 0x7A })
        assertFailsWith<FFmpegException> { MediaSource.open(source) }
        assertEquals(1, source.closes, "a failed open must close the source it took ownership of, once")
    }

    @Test
    fun aByteSourceThatCannotBeReadIsStillClosed() {
        val source = object : MediaByteSource {
            var closes = 0
            override val size: Long get() = 1024
            override val seekable: Boolean = true
            override fun read(into: ByteArray, offset: Int, length: Int): Int = error("this source always fails")
            override fun seek(position: Long) = Unit
            override fun close() { closes++ }
        }
        assertFailsWith<FFmpegException> { MediaSource.open(source) }
        assertEquals(1, source.closes, "a source that throws on read must still be closed once")
    }

    /** Counts closes rather than recording a flag, so a double close is a failure too. */
    private class CountingCloseSource(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        var closes = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean = true
        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }
        override fun seek(position: Long) { this.position = position.toInt() }
        override fun close() { closes++ }
    }
}
