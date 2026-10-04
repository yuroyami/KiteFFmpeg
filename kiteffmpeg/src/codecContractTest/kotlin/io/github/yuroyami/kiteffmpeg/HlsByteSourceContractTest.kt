package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
 * The Matroska and subtitle tests need two later FFmpeg patches as well, `0003` and `0004`, and the
 * two variable tests need `0011`, and each fails on a tree built before its patch. The location
 * tests need no patch of their own: the C layer hands FFmpeg the address, as its own http does.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class HlsByteSourceContractTest {

    /** Thrown by the test opener, so `assertSame` proves that this exact object arrived. */
    private class OpenerFailure(message: String) : RuntimeException(message)

    /**
     * Serves [resources] by address, each source naming its address in [redirects] as its location,
     * as after a redirect, and counts what it opened and what came back closed.
     */
    private class RecordingOpener(
        private val resources: Map<String, ByteArray>,
        private val failWith: Throwable? = null,
        private val redirects: Map<String, String> = emptyMap(),
        private val locationFailures: Map<String, Throwable> = emptyMap(),
    ) : MediaByteOpener {
        val asked = mutableListOf<String>()
        var opened = 0
        var closed = 0

        override fun open(url: String): MediaByteSource? {
            asked += url
            failWith?.let { throw it }
            val bytes = resources[url] ?: return null
            opened++
            return MemorySource(bytes, redirects[url], locationFailures[url]) { closed++ }
        }
    }

    private class MemorySource(
        private val bytes: ByteArray,
        private val redirectedTo: String? = null,
        private val locationFailure: Throwable? = null,
        private val onClose: () -> Unit = {},
    ) : MediaByteSource {
        private var position = 0

        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true
        override val location: String? get() = locationFailure?.let { throw it } ?: redirectedTo

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
    private fun openPlaylist(
        opener: MediaByteOpener,
        url: String = PLAYLIST_URL,
        mimeType: String? = HLS_TYPE,
        playlist: String = PLAYLIST,
        location: String? = null,
    ): MediaSource? =
        try {
            MediaSource.open(
                MemorySource(playlist.encodeToByteArray(), redirectedTo = location),
                url = url,
                mimeType = mimeType,
                nestedOpener = opener,
            )
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

    /**
     * A playlist of Matroska segments read to its end still seeks (#125). FFmpeg's Matroska reader
     * kept answering end of file after the HLS reader reset its input, so the seek returned and no
     * packet ever followed. WebM is the same reader.
     */
    @Test
    fun aMatroskaPlaylistReadToItsEndStillSeeks() {
        val opener = RecordingOpener(mapOf("$BASE_URL/seg0.mkv" to matroskaSegment))
        val media = openPlaylist(opener, playlist = mediaPlaylist("seg0.mkv", seconds = 4)) ?: return
        media.use {
            val video = media.primaryVideo ?: error("the playlist has no video stream")
            media.openPacketReader(listOf(video)).use { reader ->
                var packets = 0
                while (true) {
                    val packet = reader.read() ?: break
                    packet.close()
                    packets++
                }
                assertTrue(packets >= 100, "expected the whole segment before the seek, got $packets packets")
                reader.seek(2_000_000L, SeekDirection.Forward)
                val first = assertNotNull(reader.read(), "no packet after a seek from the end")
                val landed = first.use { (it.ptsMicros ?: 0L) - media.startTimeMicros }
                assertTrue(landed in 1_900_000L..2_100_000L, "the seek to 2 s landed at $landed us")
                var after = 1
                while (true) {
                    val packet = reader.read() ?: break
                    packet.close()
                    after++
                }
                assertTrue(after >= 50, "expected the last two seconds after the seek, got $after packets")
            }
        }
        assertEquals(opener.opened, opener.closed, "every nested source must be closed once")
    }

    /**
     * A subtitle rendition turned on in the middle of a cue delivers that cue (#126). FFmpeg reads
     * a subtitle rendition only once a reader asks for it, and catches it up to the newest packet
     * read. The catch-up dropped every cue that began before that moment, the one still showing
     * among them, so a viewer who turned subtitles on saw nothing until the next cue.
     */
    @Test
    fun aSubtitleRenditionTurnedOnMidCueDeliversTheCueShowing() {
        val opener = RecordingOpener(
            mapOf(
                "$BASE_URL/video.m3u8" to mediaPlaylist("v0.mkv", seconds = 4).encodeToByteArray(),
                "$BASE_URL/v0.mkv" to matroskaSegment,
                "$BASE_URL/subs.m3u8" to SUBTITLE_PLAYLIST.encodeToByteArray(),
                "$BASE_URL/s0.vtt" to cue("00:00:00.000", "00:00:01.900", "line 0"),
                "$BASE_URL/s1.vtt" to cue("00:00:02.000", "00:00:03.900", "line 1"),
            ),
        )
        val media = openPlaylist(opener, playlist = MASTER_PLAYLIST) ?: return
        media.use {
            val video = media.primaryVideo ?: error("the playlist has no video stream")
            val subtitle = media.streams.firstOrNull { it.type == MediaType.Subtitle } ?: error("the playlist has no subtitle stream")
            val cues = mutableListOf<Long>()
            media.openPacketReader(listOf(video)).use { reader ->
                // One second of video, then the subtitles: the first cue shows until 1.9 s.
                repeat(30) { assertNotNull(reader.read(), "the video ended early").close() }
                reader.reselect(listOf(video, subtitle))
                while (true) {
                    val packet = reader.read() ?: break
                    packet.use { if (it.streamIndex == subtitle.index) cues += (it.ptsMicros ?: 0L) - media.startTimeMicros }
                }
            }
            println("HLS contract: subtitle cues at $cues us")
            assertEquals(2, cues.size, "expected the cue showing at 1 s and the next one, got cues at $cues us")
            assertTrue(cues[0] in -100_000L..100_000L, "the cue showing at 1 s starts at 0 s, not at ${cues[0]} us")
        }
    }

    /**
     * A token handed down through playlist variables reaches every address it is written into
     * (#166). The master playlist takes the token from the query of its own address, a variant
     * names its media playlist with it, and the media playlist imports it for its segment. FFmpeg
     * read none of the definitions, so it asked for addresses that still held `{$token}`. The
     * token arrives percent-encoded and is written into the addresses decoded, as the HLS
     * specification has it.
     */
    @Test
    fun aTokenHandedDownThroughPlaylistVariablesReachesEveryAddress() {
        val opener = RecordingOpener(
            mapOf(
                "$BASE_URL/video.m3u8?token=t=1" to TOKEN_MEDIA_PLAYLIST.encodeToByteArray(),
                "$BASE_URL/seg0.ts?token=t=1" to segment,
            ),
        )
        val media = openPlaylist(opener, url = "$BASE_URL/master?token=t%3D1", playlist = TOKEN_MASTER_PLAYLIST) ?: return
        media.use {
            assertEquals(listOf("$BASE_URL/video.m3u8?token=t=1", "$BASE_URL/seg0.ts?token=t=1"), opener.asked.distinct())
            val video = media.primaryVideo ?: error("the playlist has no video stream")
            var packets = 0
            media.openPacketReader(listOf(video)).use { reader ->
                while (true) {
                    val packet = reader.read() ?: break
                    packet.close()
                    packets++
                }
            }
            assertTrue(packets >= 30, "expected at least 30 video packets from the segment, got $packets")
        }
        assertEquals(opener.opened, opener.closed, "every nested source must be closed once")
    }

    /**
     * A playlist that uses a variable no EXT-X-DEFINE gave fails the open, as the HLS specification
     * requires, and asks for no address with the variable's name in it (#166).
     */
    @Test
    fun aVariableNoPlaylistDefinesFailsTheOpenWithoutAskingForTheSegment() {
        val opener = RecordingOpener(mapOf(SEGMENT_URL to segment))
        val error = runCatching { openPlaylist(opener, playlist = mediaPlaylist("{\$name}.ts", seconds = 2)) }.exceptionOrNull()
        if (error == null && opener.asked.isEmpty()) return // degraded: the open was refused up front
        assertIs<FFmpegException>(error, "a playlist that uses an undefined variable must not open")
        assertEquals(emptyList(), opener.asked, "no address may be asked for once a variable is undefined")
    }

    /** Reads every packet of the video stream and returns how many there were. */
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

    /**
     * A playlist whose source followed a redirect to another host asks for its segment beside the
     * place it really is, as it would if FFmpeg's own http had followed the redirect (#167). Before,
     * the segment was asked for beside the address the playlist was requested at, where it is not.
     */
    @Test
    fun aRedirectedPlaylistAsksForItsSegmentWhereItCameFrom() {
        val opener = RecordingOpener(mapOf("$CDN_URL/seg0.ts" to segment))
        val media = openPlaylist(opener, location = "$CDN_URL/index") ?: return
        media.use {
            assertEquals(listOf("$CDN_URL/seg0.ts"), opener.asked.distinct())
            assertTrue(videoPackets(media) >= 30, "the segment holds at least 30 video packets")
        }
        assertEquals(opener.opened, opener.closed, "every nested source must be closed once")
    }

    /**
     * A master playlist whose source followed a redirect asks for its variants beside the place it
     * came from, and a variant with no location of its own resolves its segment beside itself (#167).
     */
    @Test
    fun aRedirectedMasterPlaylistAsksForItsVariantsWhereItCameFrom() {
        val opener = RecordingOpener(
            mapOf(
                "$CDN_URL/video.m3u8" to mediaPlaylist("seg0.ts", seconds = 2).encodeToByteArray(),
                "$CDN_URL/seg0.ts" to segment,
            ),
        )
        val media = openPlaylist(
            opener,
            url = "$BASE_URL/master",
            playlist = SINGLE_VARIANT_MASTER_PLAYLIST,
            location = "$CDN_URL/master",
        ) ?: return
        media.use {
            assertEquals(listOf("$CDN_URL/video.m3u8", "$CDN_URL/seg0.ts"), opener.asked.distinct())
            assertTrue(videoPackets(media) >= 30, "the segment holds at least 30 video packets")
        }
        assertEquals(opener.opened, opener.closed, "every nested source must be closed once")
    }

    /** A variant playlist that a nested opener followed to another host resolves its segment there (#167). */
    @Test
    fun aRedirectedVariantAsksForItsSegmentWhereItCameFrom() {
        val opener = RecordingOpener(
            mapOf(
                "$BASE_URL/video.m3u8" to mediaPlaylist("seg0.ts", seconds = 2).encodeToByteArray(),
                "$CDN_URL/seg0.ts" to segment,
            ),
            redirects = mapOf("$BASE_URL/video.m3u8" to "$CDN_URL/video.m3u8"),
        )
        val media = openPlaylist(opener, url = "$BASE_URL/master", playlist = SINGLE_VARIANT_MASTER_PLAYLIST) ?: return
        media.use {
            assertEquals(listOf("$BASE_URL/video.m3u8", "$CDN_URL/seg0.ts"), opener.asked.distinct())
            assertTrue(videoPackets(media) >= 30, "the segment holds at least 30 video packets")
        }
        assertEquals(opener.opened, opener.closed, "every nested source must be closed once")
    }

    /**
     * A variant whose location getter throws fails rather than resolving its segment against the
     * address it was asked for, and the open carries the getter's exception as its cause (#167).
     */
    @Test
    fun aVariantWhoseLocationThrowsFailsTheOpenWithThatCause() {
        val thrown = OpenerFailure("the variant could not say where its redirect led")
        val opener = RecordingOpener(
            mapOf(
                "$BASE_URL/video.m3u8" to mediaPlaylist("seg0.ts", seconds = 2).encodeToByteArray(),
                "$BASE_URL/seg0.ts" to segment,
            ),
            locationFailures = mapOf("$BASE_URL/video.m3u8" to thrown),
        )
        val error = runCatching {
            openPlaylist(opener, url = "$BASE_URL/master", playlist = SINGLE_VARIANT_MASTER_PLAYLIST)?.close()
        }.exceptionOrNull()
        if (error == null && opener.asked.isEmpty()) return // degraded: the open was refused up front
        assertIs<FFmpegException>(error, "a variant that cannot say where it came from must not open")
        assertSame(thrown, error.cause, "the open must carry the location getter's exception as its cause")
        assertEquals(listOf("$BASE_URL/video.m3u8"), opener.asked, "no segment may be asked for")
        assertEquals(opener.opened, opener.closed, "the variant must be closed once")
    }

    /** A source whose location getter throws fails its own open with that exception, and is closed once. */
    @Test
    fun aPlaylistWhoseLocationThrowsFailsTheOpenAndIsClosed() {
        val thrown = OpenerFailure("the playlist could not say where its redirect led")
        var closes = 0
        val source = MemorySource(PLAYLIST.encodeToByteArray(), locationFailure = thrown) { closes++ }
        val error = runCatching {
            MediaSource.open(source, url = PLAYLIST_URL, mimeType = HLS_TYPE, nestedOpener = RecordingOpener(emptyMap())).close()
        }.exceptionOrNull()
        if (error is FFmpegException && error.error is FFmpegError.Unsupported) return // degraded: no trust_io_open
        assertSame(thrown, error, "the getter's own exception must reach the caller")
        assertEquals(1, closes, "the open owns the source, so a failed open closes it once")
    }

    private companion object {
        const val BASE_URL = "https://media.example/live"
        const val CDN_URL = "https://cdn.example/edge/live"
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

        /** A media playlist of one [seconds] long segment at [segment]. */
        fun mediaPlaylist(segment: String, seconds: Int): String = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:$seconds
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:$seconds.0,
            $segment
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"

        /** A video variant with an English subtitle rendition, as KitePlayer writes one for DASH. */
        val MASTER_PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="subs.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,SUBTITLES="subs"
            video.m3u8
        """.trimIndent() + "\n"

        /** A master playlist of one video variant, named by a relative address. */
        val SINGLE_VARIANT_MASTER_PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            video.m3u8
        """.trimIndent() + "\n"

        /** A master playlist that takes a token from its own address and hands it to its variant. */
        val TOKEN_MASTER_PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:8
            #EXT-X-DEFINE:QUERYPARAM="token"
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            video.m3u8?token={${'$'}token}
        """.trimIndent() + "\n"

        /** The variant of [TOKEN_MASTER_PLAYLIST], which imports the token for its one segment. */
        val TOKEN_MEDIA_PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:8
            #EXT-X-DEFINE:IMPORT="token"
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:2.0,
            seg0.ts?token={${'$'}token}
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"

        /** Two WebVTT segments of two seconds, one cue each. */
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

        /** A WebVTT segment that holds one cue. */
        fun cue(start: String, end: String, text: String): ByteArray = "WEBVTT\n\n$start --> $end\n$text\n".encodeToByteArray()

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

        /** Four seconds of Matroska video: 120 mpeg4 frames at 320x240, with a keyframe every second. */
        val matroskaSegment: ByteArray by lazy {
            val path = contractOutputPath("mkv")
            try {
                MediaSink.open(path).use { sink ->
                    val encoder = sink.addVideoEncoder(
                        VideoEncoderSpec(
                            codec = CodecId("mpeg4"),
                            width = 320,
                            height = 240,
                            frameRate = Rational(30, 1),
                            bitrateBps = 800_000,
                            keyframeIntervalFrames = 30,
                        ),
                    )
                    runBlocking {
                        encoder.drive(
                            (0 until 120).asFlow().map { index ->
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
