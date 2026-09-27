package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Closed captions: the CEA-608 bytes a decoder attaches to the video frames that carry them, and a
 * caption track stored on its own decoding to text.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
internal class ClosedCaptionsContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private fun materialize(bytes: ByteArray, sha256: String): String =
        materializeContractMedia(bytes, sha256).also(paths::add)

    @Test
    fun everyFrameAnswersWithTheCaptionsItsBitstreamCarried() = runTest {
        MediaSource.open(materialize(ClosedCaptionFixtures.CAPTIONED, ClosedCaptionFixtures.CAPTIONED_SHA256)).use { source ->
            val frames = source.decodedFrames(source.streams.single()).toList()
            try {
                assertEquals(3, frames.size, "decoded frames")
                frames.forEachIndexed { index, frame ->
                    assertContentEquals(ClosedCaptionFixtures.captionsOf(index), frame.closedCaptions(), "captions of frame $index")
                }
            } finally {
                frames.forEach(Frame::close)
            }
        }
    }

    @Test
    fun aFrameWithoutCaptionsAnswersNull() {
        Frame.ofVideo(ByteArray(16 * 16 * 3 / 2), 16, 16, PixelFormat.Yuv420p).use { assertNull(it.closedCaptions()) }
        Frame.ofAudio(ByteArray(64 * 2), 64, 48_000, 1, SampleFormat.S16).use { assertNull(it.closedCaptions()) }
    }

    @Test
    fun anEia608TrackDecodesToItsTextAtItsTime() {
        val subtitles = MediaSource.open(materialize(CAPTION_TRACK, CAPTION_TRACK_SHA256)).use { source ->
            val stream = source.streams.single()
            assertEquals("eia_608", stream.codec.name)
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
        val shown = subtitles.firstOrNull { it.texts.isNotEmpty() }
        assertTrue(shown != null, "no subtitle carried text: $subtitles")
        assertEquals(330_000L, shown.startMicros, "the caption starts at 0.33 s, where the SCC line puts it")
        assertEquals(1_330_000L, shown.endMicros, "the caption ends at the erase at 1.33 s")
        assertTrue(shown.texts.single().endsWith("HELLO"), "the caption text: ${shown.texts}")
    }

    private companion object {

        /**
         * A MOV whose only track is EIA-608 (c608), made with ffmpeg from Scenarist SCC lines that
         * pop up HELLO at 0.33 s and erase it at 1.33 s. A last line of padding keeps the erase in
         * the file, because the MOV muxer drops a final packet that has no duration.
         */
        val CAPTION_TRACK: ByteArray = (
            "00000014667479707174202000000200717420200000000877696465000000466d64617400000026636461749420" +
            "942094ae94ae9452945297a197a1c8454c4c4f80942c942c942f942f0000000c63646174942c942c0000000c6364" +
            "617480808080000002756d6f6f760000006c6d766864000000000000000000000000000003e80000091a00010000" +
            "01000000000000000000000000010000000000000000000000000000000100000000000000000000000000004000" +
            "000000000000000000000000000000000000000000000000000000000002000002017472616b0000005c746b6864" +
            "00000003000000000000000000000001000000000000091a00000000000000000000000300000000000100000000" +
            "00000000000000000000000100000000000000000000000000004000000000000000000000000000003065647473" +
            "00000028656c737400000000000000020000014affffffff00010000000007d000000000000100000000016d6d64" +
            "6961000000206d646864000000000000000000000000000003e8000007d07fff00000000003568646c7200000000" +
            "6d686c72636c637000000000000000000000000014436c6f73656443617074696f6e48616e646c6572000001106d" +
            "696e6600000020676d686400000018676d696e000000000040800080008000000000000000002c68646c72000000" +
            "0064686c7275726c200000000000000000000000000b4461746148616e646c65720000002464696e660000001c64" +
            "72656600000000000000010000000c75726c2000000001000000987374626c000000207374736400000000000000" +
            "01000000106336303800000000000000010000002073747473000000000000000200000002000003e80000000100" +
            "0000000000001c737473630000000000000001000000010000000300000001000000207374737a00000000000000" +
            "0000000003000000260000000c0000000c000000147374636f000000000000000100000024"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        const val CAPTION_TRACK_SHA256 = "7a38ca690ac62c8f6086a355c5086ceb5c9bd223969eccac094a065afa91b671"
    }
}
