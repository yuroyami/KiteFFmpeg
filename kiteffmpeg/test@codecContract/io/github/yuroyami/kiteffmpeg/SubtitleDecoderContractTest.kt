package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The subtitle decoder, against a Blu-ray file whose every value is known and against the two
 * command-line tools: `ffprobe` for the timing, and `ffmpeg` drawing the subtitle over black for
 * where its pixels land. A text subtitle checks the text path.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
internal class SubtitleDecoderContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private companion object {
        /**
         * A PGS file: at one second a 1920x1080 composition with one forced 4x2 object at (100, 200),
         * rows 1 1 2 2 and 2 2 1 1, colour 1 opaque white and colour 2 half-transparent black; at two
         * seconds a composition with no object, which clears it. The same bytes as test_subtitle.c.
         */
        val PGS: ByteArray = (
            "504700015f9000000000160013078004381000008000000100000040006400c8504700015f900000000017000a" +
                "0100006400c800040002504700015f900000000014000c000001eb8080ff0210808080504700015f90000000" +
                "00150017000000c000001000040002010102020000020201010000504700015f9000000000800000504700" +
                "02bf200000000016000b078004381000010000000050470002bf2000000000800000"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        const val PGS_SHA256 = "7310d5c84aef602ef9798c7c7c6f91443314ae4697500f0ea124e25f4ccfa581"

        val SRT: ByteArray = "1\n00:00:01,000 --> 00:00:02,500\nHello there\n\n".encodeToByteArray()
        const val SRT_SHA256 = "dc47c4a3a4b464abbaff1cc5592ff5836f9560c504002948838a13f35bf600a7"

        private val W = byteArrayOf(-1, -1, -1, -1)
        private val B = byteArrayOf(0, 0, 0, -128)

        /** Opaque white and half-transparent black, premultiplied, in the object's pixel order. */
        val EXPECTED_RGBA: ByteArray = W + W + B + B + B + B + W + W
    }

    private fun materialize(bytes: ByteArray, sha256: String): String = materializeContractMedia(bytes, sha256).also(paths::add)

    /** Every subtitle [source]'s first subtitle stream decodes to, in order. */
    private fun decodeAll(path: String): List<Subtitle> = MediaSource.open(path).use { source ->
        val stream = source.streams.first { it.type == MediaType.Subtitle }
        source.openSubtitleDecoder(stream).use { decoder ->
            source.openPacketReader(listOf(stream)).use { reader ->
                buildList {
                    while (true) {
                        val packet = reader.read() ?: break
                        packet.use { decoder.decode(it)?.let(::add) }
                    }
                }
            }
        }
    }

    @Test
    fun aBluRaySubtitleDecodesToThePositionedImageItDescribes() {
        val subtitles = decodeAll(materialize(PGS, PGS_SHA256))
        assertEquals(2, subtitles.size, "one subtitle and one clear: $subtitles")
        val (shown, cleared) = subtitles
        assertEquals(1_000_000L, shown.startMicros)
        assertNull(shown.endMicros, "a Blu-ray subtitle has no end of its own")
        assertEquals(1920, shown.canvasWidth)
        assertEquals(1080, shown.canvasHeight)
        assertEquals(emptyList(), shown.texts)
        val image = shown.images.single()
        assertEquals(listOf(100, 200, 4, 2), listOf(image.x, image.y, image.width, image.height))
        assertTrue(image.forced, "the composition object is marked forced")
        assertContentEquals(EXPECTED_RGBA, image.rgba)
        assertEquals(2_000_000L, cleared.startMicros)
        assertEquals(emptyList(), cleared.images, "the second display set clears the screen")
    }

    @Test
    fun theTimingMatchesWhatFfprobeReports() {
        val path = materialize(PGS, PGS_SHA256)
        val frames = runMediaOracle("ffprobe", listOf("-v", "error", "-show_frames", "-of", "flat", path))
            ?: return println("subtitle decoder contract degraded: no ffprobe")
        fun field(index: Int, name: String) =
            Regex("""frames\.subtitle\.$index\.$name=(\S+)""").find(frames)?.groupValues?.get(1)
        val subtitles = decodeAll(path)
        subtitles.forEachIndexed { index, subtitle ->
            assertEquals(field(index, "pts")?.toLong(), subtitle.startMicros, "start of subtitle $index")
            assertEquals(field(index, "num_rects")?.toInt(), subtitle.images.size, "images of subtitle $index")
        }
    }

    @Test
    fun theImageLandsWhereFfmpegDrawsIt() {
        val sup = materialize(PGS, PGS_SHA256)
        val raw = contractOutputPath("gray").also(paths::add)
        // ffmpeg moves each input's first timestamp to zero, so in its output the subtitle shows
        // from 0 to 1 s, and half a second in is inside it.
        val drawn = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "color=black:size=1920x1080:duration=3:rate=10",
                "-i", sup,
                "-filter_complex", "[0][1:s]overlay",
                "-ss", "0.5", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "gray",
            ),
            raw,
        )
        if (!drawn) return println("subtitle decoder contract degraded: no ffmpeg")
        val gray = readContractBytes(raw)
        assertEquals(1920 * 1080, gray.size)
        // Only the opaque white pixels show over black, so they are what both answers must agree on.
        val lit = gray.indices.filter { (gray[it].toInt() and 0xFF) > 200 }.map { it % 1920 to it / 1920 }.toSet()
        val image = decodeAll(sup).first().images.single()
        val white = buildSet {
            for (row in 0 until image.height) for (col in 0 until image.width) {
                if (image.rgba[(row * image.width + col) * 4].toInt() and 0xFF == 255) add(image.x + col to image.y + row)
            }
        }
        assertEquals(white, lit, "ffmpeg lit other pixels than the decoded image names")
    }

    @Test
    fun aTextSubtitleDecodesToItsText() {
        val srt = materialize(SRT, SRT_SHA256)
        val mkv = contractOutputPath("mkv").also(paths::add)
        if (!MediaOracle.generate(listOf("-i", srt, "-c:s", "subrip"), mkv)) {
            return println("subtitle decoder contract degraded: no ffmpeg")
        }
        val subtitle = decodeAll(mkv).single()
        assertEquals(1_000_000L, subtitle.startMicros)
        assertEquals(2_500_000L, subtitle.endMicros)
        assertEquals(emptyList(), subtitle.images)
        assertTrue(subtitle.texts.single().endsWith("Hello there"), "the ASS event carries the line: ${subtitle.texts}")
    }

    @Test
    fun onlyASubtitleStreamOpensAndAClosedDecoderRefuses() {
        val path = materialize(PGS, PGS_SHA256)
        MediaSource.open(path).use { source ->
            val stream = source.streams.single()
            val forged = stream.copy(type = MediaType.Video)
            assertFailsWith<IllegalArgumentException> { source.openSubtitleDecoder(forged) }
            val decoder = source.openSubtitleDecoder(stream)
            decoder.close()
            decoder.close()
            source.openPacketReader(listOf(stream)).use { reader ->
                reader.read()!!.use { packet -> assertFailsWith<IllegalStateException> { decoder.decode(packet) } }
            }
        }
    }
}
