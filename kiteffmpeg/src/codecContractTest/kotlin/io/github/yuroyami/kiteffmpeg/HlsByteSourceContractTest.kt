package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An HLS playlist read through a [MediaByteSource] loads its segments through the caller's
 * [MediaByteOpener], on the JVM and on the native backends alike.
 *
 * The playlist names its one segment by a relative address, and the playlist's own address has no
 * `.m3u8` name, so the open needs both the url and the MIME type. The segment is real MPEG-TS video
 * that [MediaSink] writes, and it sits behind an https address, which FFmpeg itself cannot open.
 *
 * A linked FFmpeg without the trust_io_open patch, such as a prebuilt tree from an older release,
 * refuses the opener with [FFmpegError.Unsupported]. Each test then checks that refusal and stops.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class HlsByteSourceContractTest {

    /** Thrown by the test opener, so `assertSame` proves that this exact object arrived. */
    private class OpenerFailure(message: String) : RuntimeException(message)

    /** Serves [resources] by address, and counts what it opened and what came back closed. */
    private class RecordingOpener(
        private val resources: Map<String, ByteArray>,
        private val failWith: Throwable? = null,
    ) : MediaByteOpener {
        val asked = mutableListOf<String>()
        var opened = 0
        var closed = 0

        override fun open(url: String): MediaByteSource? {
            asked += url
            failWith?.let { throw it }
            val bytes = resources[url] ?: return null
            opened++
            return MemorySource(bytes) { closed++ }
        }
    }

    private class MemorySource(private val bytes: ByteArray, private val onClose: () -> Unit = {}) : MediaByteSource {
        private var position = 0

        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close() = onClose()
    }

    /** Opens the playlist through a byte source, or null when the linked FFmpeg lacks the patch. */
    private fun openPlaylist(opener: MediaByteOpener, url: String = PLAYLIST_URL, mimeType: String? = HLS_TYPE): MediaSource? =
        try {
            MediaSource.open(MemorySource(PLAYLIST.encodeToByteArray()), url = url, mimeType = mimeType, nestedOpener = opener)
        } catch (error: FFmpegException) {
            if (error.error !is FFmpegError.Unsupported || "trust_io_open" !in error.message.orEmpty()) throw error
            println("HLS contract degraded: the linked FFmpeg lacks the trust_io_open patch")
            null
        }

    @Test
    fun anHttpsPlaylistLoadsItsSegmentThroughTheNestedOpener() {
        val opener = RecordingOpener(mapOf(SEGMENT_URL to segment))
        val media = openPlaylist(opener) ?: return
        media.use {
            assertEquals("hls", media.formatName)
            assertTrue(SEGMENT_URL in opener.asked, "the opener was never asked for the segment: ${opener.asked}")
            val video = media.primaryVideo ?: error("the playlist has no video stream")
            assertEquals(CodecId("mpeg4"), video.codec)
            var packets = 0
            media.openPacketReader(listOf(video)).use { reader ->
                while (true) {
                    val packet = reader.read() ?: break
                    packet.close()
                    packets++
                }
            }
            println("HLS contract: $packets video packets through the nested opener")
            assertTrue(packets >= 30, "expected at least 30 video packets from the segment, got $packets")
        }
        assertEquals(opener.opened, opener.closed, "every nested source must be closed once")
    }

    @Test
    fun aRefusedSegmentFailsTheOpenAndOpensNothing() {
        val opener = RecordingOpener(emptyMap())
        val error = runCatching { openPlaylist(opener) }.exceptionOrNull()
        if (error == null && opener.asked.isEmpty()) return // degraded: the open was refused up front
        assertIs<FFmpegException>(error, "a playlist whose only segment is refused must not open")
        assertTrue(SEGMENT_URL in opener.asked, "the opener was never asked for the segment: ${opener.asked}")
        assertEquals(0, opener.opened)
    }

    @Test
    fun anOpenerExceptionIsTheCauseOfTheFailedOpen() {
        val thrown = OpenerFailure("the opener failed while the open loaded a segment")
        val opener = RecordingOpener(emptyMap(), failWith = thrown)
        val error = runCatching { openPlaylist(opener) }.exceptionOrNull()
        if (error == null && opener.asked.isEmpty()) return // degraded: the open was refused up front
        assertIs<FFmpegException>(error)
        assertSame(thrown, error.cause, "the open must carry the opener's exception as its cause")
    }

    @Test
    fun aNestedSourceStillOpenClosesWithTheMediaSource() {
        val opener = RecordingOpener(mapOf(SEGMENT_URL to segment))
        val media = openPlaylist(opener) ?: return
        media.use {
            val video = media.primaryVideo ?: error("the playlist has no video stream")
            media.openPacketReader(listOf(video)).use { reader -> reader.read()?.close() }
            assertTrue(opener.opened > opener.closed, "the segment should still be open after one packet")
        }
        assertEquals(opener.opened, opener.closed, "closing the source must close the segment FFmpeg still held")
    }

    @Test
    fun withoutTheMimeTypeANameWithoutM3u8DoesNotOpen() {
        val opener = RecordingOpener(mapOf(SEGMENT_URL to segment))
        val error = runCatching { openPlaylist(opener, mimeType = null) }.exceptionOrNull()
        if (error == null && opener.asked.isEmpty()) return // degraded: the open was refused up front
        assertIs<FFmpegException>(error, "FFmpeg's probe must refuse a playlist it cannot recognise")
        assertEquals(0, opener.opened)
    }

    private companion object {
        const val PLAYLIST_URL = "https://media.example/live/index"
        const val SEGMENT_URL = "https://media.example/live/seg0.ts"
        const val HLS_TYPE = "application/vnd.apple.mpegurl"

        val PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:2.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"

        /** A 320x240 YUV 4:2:0 frame whose bytes change with [index], so every frame costs the encoder work. */
        fun frameBytes(index: Int): ByteArray = ByteArray(320 * 240 * 3 / 2) { ((it * 31 + index * 17) % 251).toByte() }

        /** Two seconds of MPEG-TS video: 60 mpeg4 frames at 320x240. */
        val segment: ByteArray by lazy {
            val path = contractOutputPath("ts")
            try {
                MediaSink.open(path).use { sink ->
                    val encoder = sink.addVideoEncoder(
                        VideoEncoderSpec(
                            codec = CodecId("mpeg4"),
                            width = 320,
                            height = 240,
                            frameRate = Rational(30, 1),
                            bitrateBps = 800_000,
                        ),
                    )
                    runBlocking {
                        encoder.drive(
                            (0 until 60).asFlow().map { index ->
                                Frame.ofVideo(frameBytes(index), 320, 240, PixelFormat.Yuv420p, index * 1_000_000L / 30)
                            },
                        )
                    }
                }
                readContractBytes(path)
            } finally {
                deleteContractPath(path)
            }
        }
    }
}
