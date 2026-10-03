package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The subtitle decoder's drain (#149), against CEA-608 closed captions read from SCC files. The
 * caption decoder gives a caption only when the screen next changes, so the caption on screen at
 * the end of a stream comes out of the drain and of nothing else, as the `ffmpeg` command line,
 * which drains every decoder at the end of its input, shows for the same files.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
internal class SubtitleDrainContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private companion object {
        /**
         * One pop-on caption, HELLO at one second, that nothing erases: resume caption loading,
         * erase non-displayed memory, a row, the letters with odd parity and end of caption, each
         * control code doubled as broadcasters send it. The same bytes as test_subtitle.c.
         */
        const val HELD_TEXT = "Scenarist_SCC V1.0\n\n00:00:01:00\t9420 9420 94ae 94ae 9440 9440 c845 4c4c 4f80 942f 942f\n\n"
        const val HELD_SHA256 = "8506e358898c5808e9bf44f151983bc5c1ba098d3023f25725ccd7d8b5e30299"

        /** HELLO at one second, then BYE at three, which ends HELLO; nothing erases BYE. */
        const val TWO_TEXT = HELD_TEXT + "00:00:03:00\t9420 9420 94ae 94ae 9440 9440 c2d9 4580 942f 942f\n\n"
        const val TWO_SHA256 = "498b027f9de04ff843578e50f097007bdb889546e25f367d92ae42a94417ba2b"

        const val SRT_TEXT = "1\n00:00:01,000 --> 00:00:02,500\nHello there\n\n"
        const val SRT_SHA256 = "dc47c4a3a4b464abbaff1cc5592ff5836f9560c504002948838a13f35bf600a7"
    }

    private fun materialize(text: String, sha256: String): String =
        materializeContractMedia(text.encodeToByteArray(), sha256).also(paths::add)

    /** What decoding every packet gives, then what the drain gives, then what a second drain gives. */
    private fun decodeThenDrain(path: String): Triple<List<Subtitle>, Subtitle?, Subtitle?> = MediaSource.open(path).use { source ->
        val stream = source.streams.first { it.type == MediaType.Subtitle }
        source.openSubtitleDecoder(stream).use { decoder ->
            val decoded = source.openPacketReader(listOf(stream)).use { reader ->
                buildList {
                    while (true) {
                        val packet = reader.read() ?: break
                        packet.use { decoder.decode(it)?.let(::add) }
                    }
                }
            }
            val drained = decoder.drain()
            val again = decoder.drain()
            // A flush after a drain readies the decoder for packets again, as after a seek.
            decoder.flush()
            Triple(decoded, drained, again)
        }
    }

    private fun Subtitle.saying(word: String) =
        assertTrue(texts.single().contains(word), "the caption should read $word and reads $texts")

    @Test
    fun aCaptionNothingErasesComesOutOfTheDrainOnly() {
        val (decoded, drained, again) = decodeThenDrain(materialize(HELD_TEXT, HELD_SHA256))
        assertEquals(emptyList(), decoded, "a packet completed the held caption")
        val caption = assertNotNull(drained, "the drain gave nothing, so the caption is lost")
        assertEquals(1_000_000L, caption.startMicros)
        caption.saying("HELLO")
        assertNull(again, "a second drain gave the caption again")
    }

    @Test
    fun anEarlierCaptionIsDecodedAndOnlyTheLastIsDrained() {
        val (decoded, drained, _) = decodeThenDrain(materialize(TWO_TEXT, TWO_SHA256))
        val first = decoded.single()
        assertEquals(1_000_000L, first.startMicros)
        assertEquals(3_000_000L, first.endMicros)
        first.saying("HELLO")
        val last = assertNotNull(drained)
        assertEquals(3_000_000L, last.startMicros)
        last.saying("BYE")
    }

    @Test
    fun aTextSubtitleDrainsToNothing() {
        val (decoded, drained, _) = decodeThenDrain(materialize(SRT_TEXT, SRT_SHA256))
        assertEquals(1, decoded.size)
        assertNull(drained, "a decoder that delays nothing holds nothing")
    }
}
