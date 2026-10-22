package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An HLS playlist read through a [MediaByteSource] loads its segments through the caller's
 * [MediaByteOpener] in the linked codec module (#123), as `HlsByteSourceContractTest` proves on the
 * JVM and the native backends. That suite writes its segment with `MediaSink`, which the web does
 * not have, so the segment here is a fixed clip.
 *
 * The browser page fallback reads each source whole, at once, and closes it, so a segment is
 * closed before FFmpeg reads its first packet. The native suite's test that a segment FFmpeg still
 * holds closes with the MediaSource therefore becomes [aSegmentIsReadWholeAndClosedWhenTheOpenerReturnsIt].
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class WebNestedOpenerTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    /** Thrown by the test opener, so `assertSame` proves that this exact object arrived. */
    private class OpenerFailure(message: String) : RuntimeException(message)

    /** Serves [resources] by address, and counts what it opened and what came back closed. */
    private class RecordingOpener(
        private val resources: Map<String, ByteArray>,
        private val failWith: Throwable? = null,
        private val sizeKnown: Boolean = true,
        private val failReadWith: Throwable? = null,
    ) : MediaByteOpener {
        val asked = mutableListOf<String>()
        var opened = 0
        var closed = 0

        override fun open(url: String): MediaByteSource? {
            asked += url
            failWith?.let { throw it }
            val bytes = resources[url] ?: return null
            opened++
            return MemorySource(bytes, sizeKnown, failReadWith) { closed++ }
        }
    }

    private class MemorySource(
        private val bytes: ByteArray,
        sizeKnown: Boolean = true,
        private val failReadWith: Throwable? = null,
        private val onClose: () -> Unit = {},
    ) : MediaByteSource {
        private var position = 0

        override val size: Long? = if (sizeKnown) bytes.size.toLong() else null
        override val seekable: Boolean get() = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            failReadWith?.let { throw it }
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

    private fun openPlaylist(opener: MediaByteOpener, mimeType: String? = HLS_TYPE): MediaSource =
        MediaSource.open(MemorySource(PLAYLIST.encodeToByteArray()), url = PLAYLIST_URL, mimeType = mimeType, nestedOpener = opener)

    /** Reads every packet of the playlist's video stream and returns how many there were. */
    private fun videoPackets(media: MediaSource): Int {
        val video = media.primaryVideo ?: error("the playlist has no video stream")
        var packets = 0
        media.openPacketReader(listOf(video)).use { reader ->
            while (true) {
                val packet = reader.read() ?: break
                packet.close()
                packets++
            }
        }
        return packets
    }

    @Test
    fun anHttpsPlaylistLoadsItsSegmentThroughTheNestedOpener() = runTest {
        if (!useStagedModule()) return@runTest
        val opener = RecordingOpener(mapOf(SEGMENT_URL to SEGMENT))
        openPlaylist(opener).use { media ->
            assertEquals("hls", media.formatName)
            assertTrue(SEGMENT_URL in opener.asked, "the opener was never asked for the segment: ${opener.asked}")
            assertEquals(CodecId("h264"), media.primaryVideo?.codec)
            assertEquals(60, videoPackets(media), "the segment holds 60 frames")
        }
        assertEquals(opener.opened, opener.closed, "every nested source must be closed once")
    }

    @Test
    fun aSegmentOfUnknownSizeIsReadToItsEnd() = runTest {
        if (!useStagedModule()) return@runTest
        val opener = RecordingOpener(mapOf(SEGMENT_URL to SEGMENT), sizeKnown = false)
        openPlaylist(opener).use { media -> assertEquals(60, videoPackets(media), "the segment holds 60 frames") }
        assertEquals(opener.opened, opener.closed, "every nested source must be closed once")
    }

    @Test
    fun aSegmentIsReadWholeAndClosedWhenTheOpenerReturnsIt() = runTest {
        if (!useStagedModule()) return@runTest
        val opener = RecordingOpener(mapOf(SEGMENT_URL to SEGMENT))
        openPlaylist(opener).use {
            assertTrue(opener.opened > 0, "the open never asked for the segment")
            assertEquals(opener.opened, opener.closed, "the web closes a segment once it holds its bytes")
        }
        assertEquals(opener.opened, opener.closed, "no segment may be closed twice")
    }

    @Test
    fun aRefusedSegmentFailsTheOpenAndOpensNothing() = runTest {
        if (!useStagedModule()) return@runTest
        val opener = RecordingOpener(emptyMap())
        val error = runCatching { openPlaylist(opener).close() }.exceptionOrNull()
        assertIs<FFmpegException>(error, "a playlist whose only segment is refused must not open")
        assertTrue(SEGMENT_URL in opener.asked, "the opener was never asked for the segment: ${opener.asked}")
        assertEquals(0, opener.opened)
    }

    @Test
    fun anOpenerExceptionIsTheCauseOfTheFailedOpen() = runTest {
        if (!useStagedModule()) return@runTest
        val thrown = OpenerFailure("the opener failed while the open loaded a segment")
        val error = runCatching { openPlaylist(RecordingOpener(emptyMap(), failWith = thrown)).close() }.exceptionOrNull()
        assertIs<FFmpegException>(error)
        assertSame(thrown, error.cause, "the open must carry the opener's exception as its cause")
    }

    /** The read fails while the segment is staged, which the web does before FFmpeg reads a byte. */
    @Test
    fun aSegmentWhoseReadFailsFailsTheOpenAndIsClosed() = runTest {
        if (!useStagedModule()) return@runTest
        val thrown = OpenerFailure("the segment failed while it was read")
        val opener = RecordingOpener(mapOf(SEGMENT_URL to SEGMENT), failReadWith = thrown)
        val error = runCatching { openPlaylist(opener).close() }.exceptionOrNull()
        assertIs<FFmpegException>(error)
        assertSame(thrown, error.cause, "the open must carry the read's exception as its cause")
        assertEquals(1, opener.opened)
        assertEquals(1, opener.closed, "a segment that could not be read is still closed")
    }

    /**
     * A subtitle rendition is a WebVTT stream of the playlist (#184). FFmpeg's HLS reader probes
     * the first segment of every playlist at the open, so a module with no WebVTT reader fails
     * the whole open, not only the subtitles.
     */
    @Test
    fun aPlaylistWithAWebVttRenditionOpensWithItsSubtitleStream() = runTest {
        if (!useStagedModule()) return@runTest
        val opener = RecordingOpener(
            mapOf(
                PLAYLIST_URL to PLAYLIST.encodeToByteArray(),
                SEGMENT_URL to SEGMENT,
                SUBTITLE_PLAYLIST_URL to SUBTITLE_PLAYLIST.encodeToByteArray(),
                FIRST_CUE_URL to FIRST_CUE.encodeToByteArray(),
                SECOND_CUE_URL to SECOND_CUE.encodeToByteArray(),
            ),
        )
        MediaSource.open(MemorySource(MASTER.encodeToByteArray()), url = MASTER_URL, mimeType = HLS_TYPE, nestedOpener = opener).use { media ->
            assertEquals(CodecId("h264"), media.primaryVideo?.codec)
            val subtitle = media.streams.singleOrNull { it.type == MediaType.Subtitle }
                ?: error("the rendition is not a stream: ${media.streams.map { it.type to it.codec }}")
            assertEquals(CodecId("webvtt"), subtitle.codec)
            assertEquals("de", subtitle.metadata["language"])
            // FFmpeg reads a subtitle rendition beside the streams it follows, so both are read.
            val video = media.primaryVideo ?: error("the playlist has no video stream")
            val cues = mutableListOf<Long?>()
            var pictures = 0
            media.openPacketReader(listOf(video, subtitle)).use { reader ->
                while (true) {
                    val packet = reader.read() ?: break
                    if (packet.streamIndex == subtitle.index) cues += packet.ptsMicros else pictures++
                    packet.close()
                }
            }
            assertEquals(60, pictures, "the segment holds 60 frames")
            assertTrue(2_000_000L in cues, "the cue of the second WebVTT file arrives at its own time, 2 s: $cues")
        }
        assertTrue(FIRST_CUE_URL in opener.asked, "the opener was never asked for the WebVTT file: ${opener.asked}")
        assertEquals(opener.opened, opener.closed, "every nested source must be closed once")
    }

    @Test
    fun withoutTheMimeTypeANameWithoutM3u8DoesNotOpen() = runTest {
        if (!useStagedModule()) return@runTest
        val opener = RecordingOpener(mapOf(SEGMENT_URL to SEGMENT))
        val error = runCatching { openPlaylist(opener, mimeType = null).close() }.exceptionOrNull()
        assertIs<FFmpegException>(error, "FFmpeg's probe must refuse a playlist it cannot recognise")
        assertEquals(0, opener.opened)
    }

    private suspend fun useStagedModule(): Boolean {
        if (!useLinkedCodecModule()) return false
        WebIoBridge.readOnDemand = false
        return true
    }

    internal companion object {
        const val PLAYLIST_URL = "https://media.example/live/index"
        const val SEGMENT_URL = "https://media.example/live/seg0.ts"
        const val HLS_TYPE = "application/vnd.apple.mpegurl"
        const val MASTER_URL = "https://media.example/live/master"
        const val SUBTITLE_PLAYLIST_URL = "https://media.example/live/subs.m3u8"
        const val FIRST_CUE_URL = "https://media.example/live/s0.vtt"
        const val SECOND_CUE_URL = "https://media.example/live/s1.vtt"

        /** One variant, which is [PLAYLIST], and one subtitle rendition. */
        val MASTER = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Deutsch",LANGUAGE="de",URI="subs.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=400000,CODECS="avc1.42c00a",SUBTITLES="subs"
            index
        """.trimIndent() + "\n"

        val SUBTITLE_PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:2.0,
            s0.vtt
            #EXTINF:2.0,
            s1.vtt
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"

        /** The two WebVTT segments, one cue each. [SEGMENT] shows from 1.4 s to 3.4 s. */
        val FIRST_CUE = "WEBVTT\n\n00:00:00.000 --> 00:00:01.900\nZeile 1\n"
        val SECOND_CUE = "WEBVTT\n\n00:00:02.000 --> 00:00:03.900\nZeile 2\n"

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

        /**
         * Two seconds of MPEG-TS video: 60 frames of solid red, 64x64 H.264 at 30 frames a second.
         * Hex, where `/n/` stands for n bytes of 0xff, the stuffing that fills most of each
         * 188-byte packet.
         */
        val SEGMENT: ByteArray = unpack(
            "474011100042f0250001c10000ff01ff0001fc80144812010646466d70656709536572766963653031777c43ca/143/" +
            "474000100000b00d0001c100000001f0002ab104b2/167/475000100002b0120001c10000e100f0001be100f00015bd" +
            "4d56/162/474100305f5000007b0c7e00/88/000001e00000808005210007d8610000000109f0000000016742c00ada" +
            "109b0110000003001000000303c0f1226a0000000168ce0fc80000016588843a118a000218f1c00040f63800087949c9" +
            "c9d75d75d75d75d75d75e0474100319900/152/000001e00000808005210007efd10000000109f000000001419a2036" +
            "8230474100329900/152/000001e0000080800521000907410000000109f000000001419a403a823047410033991000" +
            "008ca07e00/146/000001e000008080052100091eb10000000109f000000001419a603a8230474100349900/152/0000" +
            "01e0000080800521000936210000000109f000000001419a803a8230474100359900/152/000001e000008080052100" +
            "094d910000000109f000000001419aa03a823047410036991000009e347e00/146/000001e000008080052100096501" +
            "0000000109f000000001419ac03e8230474100379900/152/000001e000008080052100097c710000000109f0000000" +
            "01419ae03e8230474100389900/152/000001e0000080800521000993e10000000109f000000001419b003e82304741" +
            "003999100000afc87e00/146/000001e00000808005210009ab510000000109f000000001419b203e82304741003a99" +
            "00/152/000001e00000808005210009c2c10000000109f000000001419b403e82304741003b9900/152/000001e00000" +
            "808005210009da310000000109f000000001419b603e82304741003c99100000c15c7e00/146/000001e00000808005" +
            "210009f1a10000000109f000000001419b803e82304741003d9900/152/000001e0000080800521000b091100000001" +
            "09f000000001419ba03e82304741003e9900/152/000001e0000080800521000b20810000000109f000000001419bc0" +
            "3e82304741003f99100000d2f07e00/146/000001e0000080800521000b37f10000000109f000000001419be03e8230" +
            "474100309900/152/000001e0000080800521000b4f610000000109f000000001419a003e8230474100319900/152/00" +
            "0001e0000080800521000b66d10000000109f000000001419a203e82304741003299100000e4847e00/146/000001e0" +
            "000080800521000b7e410000000109f000000001419a403e8230474100339900/152/000001e0000080800521000b95" +
            "b10000000109f000000001419a603e8230474100349900/152/000001e0000080800521000bad210000000109f00000" +
            "0001419a803e82304741003599100000f6187e00/146/000001e0000080800521000bc4910000000109f00000000141" +
            "9aa03e8230474100369900/152/000001e0000080800521000bdc010000000109f000000001419ac03e823047410037" +
            "9900/152/000001e0000080800521000bf3710000000109f000000001419ae03e8230474100389910000107ac7e00" +
            "/146/000001e0000080800521000d0ae10000000109f000000001419b003e8230474100399900/152/000001e0000080" +
            "800521000d22510000000109f000000001419b203e82304741003a9900/152/000001e0000080800521000d39c10000" +
            "000109f000000001419b403e82304741003b9910000119407e00/146/000001e0000080800521000d51310000000109" +
            "f000000001419b603e82304741003c9900/152/000001e0000080800521000d68a10000000109f000000001419b803e" +
            "82304741003d9900/152/000001e0000080800521000d80110000000109f000000001419ba03e8230474000110000b0" +
            "0d0001c100000001f0002ab104b2/167/475000110002b0120001c10000e100f0001be100f00015bd4d56/162/474100" +
            "3e5e5000012ad47e00/87/000001e0000080800521000d97810000000109f0000000016742c00ada109b011000000300" +
            "1000000303c0f1226a0000000168ce0fc8000001658882011a118a00029231c000470638000ab949c9c9d75d75d75d75" +
            "d75d75e04741003f9900/152/000001e0000080800521000daef10000000109f000000001419a203a82304741003099" +
            "00/152/000001e0000080800521000dc6610000000109f000000001419a403a823047410031991000013c687e00/146/" +
            "000001e0000080800521000dddd10000000109f000000001419a603a8230474100329900/152/000001e00000808005" +
            "21000df5410000000109f000000001419a803a8230474100339900/152/000001e0000080800521000f0cb100000001" +
            "09f000000001419aa03a823047410034991000014dfc7e00/146/000001e0000080800521000f24210000000109f000" +
            "000001419ac03e8230474100359900/152/000001e0000080800521000f3b910000000109f000000001419ae03e8230" +
            "474100369900/152/000001e0000080800521000f53010000000109f000000001419b003e823047410037991000015f" +
            "907e00/146/000001e0000080800521000f6a710000000109f000000001419b203e8230474100389900/152/000001e0" +
            "000080800521000f81e10000000109f000000001419b403e8230474100399900/152/000001e0000080800521000f99" +
            "510000000109f000000001419b603e82304741003a9910000171247e00/146/000001e0000080800521000fb0c10000" +
            "000109f000000001419b803e82304741003b9900/152/000001e0000080800521000fc8310000000109f00000000141" +
            "9ba03e82304741003c9900/152/000001e0000080800521000fdfa10000000109f000000001419bc03e82304741003d" +
            "9910000182b87e00/146/000001e0000080800521000ff7110000000109f000000001419be03e82304741003e9900" +
            "/152/000001e000008080052100110e810000000109f000000001419a003e82304741003f9900/152/000001e0000080" +
            "800521001125f10000000109f000000001419a203e82304741003099100001944c7e00/146/000001e0000080800521" +
            "00113d610000000109f000000001419a403e8230474100319900/152/000001e0000080800521001154d10000000109" +
            "f000000001419a603e8230474100329900/152/000001e000008080052100116c410000000109f000000001419a803e" +
            "82304741003399100001a5e07e00/146/000001e0000080800521001183b10000000109f000000001419aa03e823047" +
            "4100349900/152/000001e000008080052100119b210000000109f000000001419ac03e8230474100359900/152/0000" +
            "01e00000808005210011b2910000000109f000000001419ae03e82304741003699100001b7747e00/146/000001e000" +
            "00808005210011ca010000000109f000000001419b003e8230474100379900/152/000001e00000808005210011e171" +
            "0000000109f000000001419b203e8230474100389900/152/000001e00000808005210011f8e10000000109f0000000" +
            "01419b403e82304741003999100001c9087e00/146/000001e0000080800521001310510000000109f000000001419b" +
            "603e82304741003a9900/152/000001e0000080800521001327c10000000109f000000001419b803e82304741003b99" +
            "00/152/000001e000008080052100133f310000000109f000000001419ba03e8230",
        )

        fun unpack(text: String): ByteArray {
            val out = mutableListOf<Byte>()
            var i = 0
            while (i < text.length) {
                if (text[i] == '/') {
                    val end = text.indexOf('/', i + 1)
                    repeat(text.substring(i + 1, end).toInt()) { out += 0xff.toByte() }
                    i = end + 1
                } else {
                    out += text.substring(i, i + 2).toInt(16).toByte()
                    i += 2
                }
            }
            return out.toByteArray()
        }
    }
}
