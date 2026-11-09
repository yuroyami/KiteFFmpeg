package io.github.yuroyami.kiteffmpeg

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A playlist handed over as bytes reaches nothing by itself (#239). Without a nested opener,
 * FFmpeg used to open the local files and http addresses it named through its own protocols,
 * behind the app's client, proxy and policy.
 */
class ByteSourceProtocolsTest {

    private class Bytes(private val bytes: ByteArray) : MediaByteSource {
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
        override fun close() = Unit
    }

    private fun playlist(segment: String) = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:2
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXTINF:2.0,
        $segment
        #EXT-X-ENDLIST
    """.trimIndent() + "\n"

    @OptIn(KiteFFmpegLowLevelApi::class)
    @Test
    fun aPlaylistAsBytesCannotOpenALocalFileItNames() {
        val segment = File.createTempFile("kiteffmpeg-239-", ".ts").apply { deleteOnExit() }
        val made = runMediaOracle(
            "ffmpeg",
            listOf("-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10:duration=2", "-c:v", "mpeg4", "-f", "mpegts", segment.path),
        ) != null
        if (!made) return println("byte source protocols degraded: no ffmpeg")
        val text = playlist(segment.toURI().toString())
        assertFailsWith<FFmpegException>("the playlist opened the local file it names") {
            MediaSource.open(Bytes(text.encodeToByteArray()), url = "https://cdn.test/live.m3u8", mimeType = "application/vnd.apple.mpegurl", nestedOpener = null).use { media ->
                // Where the open itself succeeds, the segment must still be refused when read.
                val video = media.primaryVideo ?: throw FFmpegException(FFmpegError.InvalidData(0, "no video was reached"))
                media.openPacketReader(listOf(video)).use { reader -> assertTrue(reader.read() == null, "a packet came from the local file") }
                throw FFmpegException(FFmpegError.InvalidData(0, "no packet was reached"))
            }
        }
        // The caller can still widen the list on purpose.
        MediaSource.open(
            Bytes(text.encodeToByteArray()),
            options = mapOf("protocol_whitelist" to "file"),
            url = "https://cdn.test/live.m3u8",
            mimeType = "application/vnd.apple.mpegurl",
            nestedOpener = null,
        ).use { media -> assertTrue(media.primaryVideo != null, "a playlist the caller let read files did not open") }
    }
}
