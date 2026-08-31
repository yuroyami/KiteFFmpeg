package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The custom AVIO bridge over the JNI adapter (M1): same proof as the native AvioBridgeTest,
 * through the JVM path this time, so the upcall trampolines in kj_format.c are what carry the
 * bytes. The counters prove the callbacks ran; the closed flag proves ownership.
 */
class JniAvioBridgeTest {

    private val tmpFiles = mutableListOf<File>()

    private fun tmp(name: String): File =
        File.createTempFile("kiteffmpeg-avio-", name).also { tmpFiles += it }

    @AfterTest
    fun cleanup() {
        tmpFiles.forEach { it.delete() }
        tmpFiles.clear()
    }

    /** Noisy luma keeps the file past the 64 KiB AVIO buffer so real seek traffic occurs. */
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
                    bitrateBps = 500_000,
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

    @Test
    fun aByteSourceOpenMatchesThePathOpenThroughTheJniUpcalls() {
        val file = tmp(".mp4")
        writeTestVideo(file.absolutePath, frames = 120, width = 320, height = 240)
        val bytes = file.readBytes()

        val pathFacts = MediaSource.open(file.absolutePath).use { it.streams.size to it.durationMicros }

        val source = MemorySource(bytes)
        MediaSource.open(source).use { src ->
            assertEquals(pathFacts.first, src.streams.size, "stream table differs from the path open")
            assertEquals(pathFacts.second, src.durationMicros, "duration differs from the path open")

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
