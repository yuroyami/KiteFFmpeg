package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real HLS demux, H.264 decode and seek with a tiny range in a virtual file larger than 512 MiB. */
@OptIn(KiteFFmpegLowLevelApi::class)
class WebNestedRangeTest {
    @BeforeTest fun start() = forgetCodecModule()
    @AfterTest fun finish() = forgetCodecModule()

    private class Playlist : MediaByteSource {
        private val bytes = ("""
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:2
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:2.0,
            #EXT-X-BYTERANGE:${WebNestedOpenerTest.SEGMENT.size}@$SEGMENT_OFFSET
            shared.ts
            #EXT-X-ENDLIST
        """.trimIndent() + "\n").encodeToByteArray()
        private var position = 0
        var closes = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true
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

    private class SparseOpener(private val readFailure: Throwable? = null) : MediaByteOpener {
        var bytes = 0L
        var opens = 0
        var closes = 0
        val seeks = mutableListOf<Long>()
        val reads = mutableListOf<Long>()
        override fun open(url: String): MediaByteSource? {
            if (url != "https://media.example/shared.ts") return null
            opens++
            return object : MediaByteSource {
                private var position = 0L
                private var closed = false
                override val size: Long get() = RESOURCE_SIZE
                override val seekable: Boolean get() = true
                override fun read(into: ByteArray, offset: Int, length: Int): Int {
                    check(!closed) { "nested read after close" }
                    readFailure?.let { throw it }
                    reads += position
                    if (position >= RESOURCE_SIZE) return -1
                    val count = minOf(length.toLong(), RESOURCE_SIZE - position).toInt()
                    // Fail promptly if a regression scans the virtual file; never allocate its size.
                    check(bytes + count <= READ_BUDGET) { "read ${bytes + count} bytes from a small HLS range" }
                    for (i in 0 until count) {
                        val local = position + i - SEGMENT_OFFSET
                        into[offset + i] = if (local >= 0 && local < WebNestedOpenerTest.SEGMENT.size) {
                            WebNestedOpenerTest.SEGMENT[local.toInt()]
                        } else 0
                    }
                    bytes += count
                    position += count
                    return count
                }
                override fun seek(position: Long) {
                    check(!closed) { "nested seek after close" }
                    seeks += position
                    this.position = position
                }
                override fun close() {
                    check(!closed) { "nested source closed twice" }
                    closed = true
                    closes++
                }
            }
        }
    }

    private suspend fun loadDemandModule(): Boolean {
        if (!useLinkedCodecModule()) return false
        WebIoBridge.readOnDemand = true
        return true
    }

    private fun open(playlist: Playlist, opener: SparseOpener): MediaSource = MediaSource.open(
        playlist,
        url = "https://media.example/index.m3u8",
        mimeType = "application/vnd.apple.mpegurl",
        nestedOpener = opener,
    )

    private fun assertRed(frame: Frame) {
        assertEquals(64, frame.info.width)
        assertEquals(64, frame.info.height)
        assertEquals(PixelFormat.Yuv420p, frame.info.pixelFormat)
        val planes = frame.copyPlanesToByteArray()
        assertEquals(64 * 64 * 3 / 2, planes.size)
        assertTrue(planes.take(4096).all { (it.toInt() and 255) in 79..83 }, "decoded luma is not the red fixture")
        assertTrue(planes.drop(4096).take(1024).all { (it.toInt() and 255) in 88..92 }, "decoded U is not the red fixture")
        assertTrue(planes.drop(5120).all { (it.toInt() and 255) in 238..242 }, "decoded V is not the red fixture")
    }

    @Test fun aHighOffsetByteRangeDecodesAndSeeksWithBoundedReads() = runTest {
        if (!loadDemandModule()) return@runTest
        val playlist = Playlist()
        val opener = SparseOpener()
        open(playlist, opener).use { media ->
            assertEquals("hls", media.formatName)
            val video = checkNotNull(media.primaryVideo)
            var frames = 0
            media.decodedFrames(video).collect { frame ->
                frame.use {
                    if (frames == 0) {
                        assertRed(it)
                        assertTrue(opener.bytes <= READ_BUDGET, "first picture read ${opener.bytes} bytes")
                    }
                    frames++
                }
            }
            assertEquals(60, frames, "the existing fixture has 60 frames")
            // Seek after EOF so a no-op seek cannot pass by continuing the first decode.
            media.seekMicros(1_000_000L)
            media.decodedFrames(video).first { frame ->
                val relative = checkNotNull(frame.ptsMicros) - media.startTimeMicros
                (relative >= 1_000_000L).also { keep -> if (!keep) frame.close() }
            }.use { frame ->
                assertRed(frame)
                val relative = checkNotNull(frame.ptsMicros) - media.startTimeMicros
                assertTrue(relative in 1_000_000L..1_100_000L, "the seek decoded timestamp $relative")
            }
            assertTrue(opener.seeks.any { it == SEGMENT_OFFSET }, "FFmpeg never sought to the declared byte range: ${opener.seeks}")
            assertTrue(opener.reads.any { it >= SEGMENT_OFFSET }, "no read reached the high offset")
            assertTrue(opener.bytes in 1..READ_BUDGET, "${opener.bytes} bytes served")
        }
        assertEquals(1, playlist.closes)
        assertTrue(opener.opens > 0)
        assertEquals(opener.opens, opener.closes)
        println("nested range: size=$RESOURCE_SIZE offset=$SEGMENT_OFFSET bytes=${opener.bytes} opens=${opener.opens} closes=${opener.closes}")
    }

    @Test fun aFailedRangeReadClosesTheChildrenAndPreservesTheOriginalCause() = runTest {
        if (!loadDemandModule()) return@runTest
        val thrown = IllegalStateException("range request failed")
        val playlist = Playlist()
        val opener = SparseOpener(thrown)
        val failure = assertFailsWith<FFmpegException> { open(playlist, opener).close() }
        assertSame(thrown, failure.cause)
        assertTrue(opener.opens > 0)
        assertEquals(opener.opens, opener.closes)
        assertEquals(1, playlist.closes)
    }

    @Test fun interruptionStopsReadsAndStillClosesEveryOwnedChild() = runTest {
        if (!loadDemandModule()) return@runTest
        val playlist = Playlist()
        val opener = SparseOpener()
        open(playlist, opener).use { media ->
            val before = opener.reads.size
            media.interrupt()
            media.openPacketReader(media.streams).use { reader ->
                val failure = assertFailsWith<FFmpegException> { reader.read() }
                assertIs<FFmpegError.Interrupted>(failure.error)
            }
            assertEquals(before, opener.reads.size, "interruption must stop before another child read")
        }
        assertEquals(opener.opens, opener.closes)
        assertEquals(1, playlist.closes)
    }

    private companion object {
        const val RESOURCE_SIZE = 600L * 1024 * 1024
        const val SEGMENT_OFFSET = 550L * 1024 * 1024
        const val READ_BUDGET = 1024L * 1024
    }
}
