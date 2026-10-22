package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Transcoder's subtitleCodec: a subtitle stream decoded and encoded again with another codec (#86). */
@OptIn(KiteFFmpegLowLevelApi::class)
internal class SubtitleConversionContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private fun materialize(bytes: ByteArray): String = materializeContractMedia(bytes, sha256Hex(bytes)).also(paths::add)

    @Test
    fun aSubRipTrackBecomesMovTextInAnMp4WithTheSameCues() = runTest {
        // The trees released before the recipe gained the text subtitle encoders have none.
        if (!FFmpeg.hasEncoder("mov_text")) return@runTest println("subtitle conversion degraded: this build has no mov_text encoder")
        val mkv = contractOutputPath("mkv").also(paths::add)
        if (!MediaOracle.generate(listOf("-i", materialize(CUES), "-c:s", "subrip"), mkv)) {
            return@runTest println("subtitle conversion degraded: no ffmpeg")
        }
        val mp4 = contractOutputPath("mp4").also(paths::add)
        Transcoder.transcode(mkv, mp4, subtitleCodec = CodecId.MovText)

        val subtitles = MediaSource.open(mp4).use { source ->
            val stream = source.streams.single()
            assertEquals(CodecId.MovText, stream.codec)
            decodeAll(source, stream)
        }
        assertEquals(listOf(0L to 900_000L, 1_000_000L to 2_500_000L), subtitles.map { it.startMicros to it.endMicros })
        assertTrue(subtitles[0].texts.single().endsWith("First"), "the first cue carries its text: ${subtitles[0].texts}")
        assertTrue(subtitles[1].texts.single().endsWith("Hello there"), "the second cue carries its text: ${subtitles[1].texts}")
    }

    @Test
    fun anImageSubtitleCannotBecomeTextAndFailsTyped() = runTest {
        val failure = assertFailsWith<FFmpegException> {
            Transcoder.transcode(materialize(PGS), contractOutputPath("mkv").also(paths::add), subtitleCodec = CodecId.SubRip)
        }
        assertIs<FFmpegError.Unsupported>(failure.error, failure.message)
        assertTrue("image subtitle" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun copyingAndConvertingSubtitlesAreExclusive() = runTest {
        assertFailsWith<IllegalArgumentException> {
            Transcoder.transcode("in.mkv", "out.mp4", subtitleCopy = true, subtitleCodec = CodecId.MovText)
        }
    }

    /** Every subtitle the [stream] of [source] decodes to, in order. */
    private fun decodeAll(source: MediaSource, stream: StreamInfo): List<Subtitle> =
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

    private companion object {
        // A first cue at zero, so the remux's shift to the input's start time moves nothing.
        val CUES: ByteArray =
            "1\n00:00:00,000 --> 00:00:00,900\nFirst\n\n2\n00:00:01,000 --> 00:00:02,500\nHello there\n\n".encodeToByteArray()

        /** The PGS file of SubtitleDecoderContractTest: one image at one second, cleared at two. */
        val PGS: ByteArray = (
            "504700015f9000000000160013078004381000008000000100000040006400c8504700015f900000000017000a" +
                "0100006400c800040002504700015f900000000014000c000001eb8080ff0210808080504700015f90000000" +
                "00150017000000c000001000040002010102020000020201010000504700015f9000000000800000504700" +
                "02bf200000000016000b078004381000010000000050470002bf2000000000800000"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
