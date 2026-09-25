package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audio description and commentary reach the caller from a real file, and neither is played to
 * someone who did not ask for it.
 *
 * Matroska carries audio description as its visually-impaired flag, commentary as its
 * commentary flag, and descriptive subtitles as its text-descriptions flag. `ffmpeg` writes the
 * fixture, so the test is skipped where there is no command-line oracle, which is an Android
 * device.
 */
internal class DispositionContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private companion object {
        /** A one-cue SRT file, the descriptive subtitle stream of the fixture. */
        val SRT: ByteArray = "1\n00:00:00,000 --> 00:00:00,900\nA door creaks.\n\n".encodeToByteArray()
        const val SRT_SHA256 = "695b4ad8b5b232f966d772e46b76946f1150c8eba0f9ac7939fdd2627ad613ca"
    }

    @Test
    fun descriptiveAndCommentaryStreamsAreReadAndNeverAutoPicked() {
        val subtitles = materializeContractMedia(SRT, SRT_SHA256).also(paths::add)
        val output = contractOutputPath("mkv").also(paths::add)
        val written = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-f", "lavfi", "-i", "sine=frequency=880:duration=1",
                "-f", "lavfi", "-i", "sine=frequency=660:duration=1",
                "-i", subtitles,
                "-map", "0", "-map", "1", "-map", "2", "-map", "3",
                "-c:a", "flac", "-c:s", "srt",
                "-disposition:a:0", "visual_impaired",
                "-disposition:a:1", "comment",
                "-disposition:a:2", "0",
                "-disposition:s:0", "descriptions",
            ),
            output,
        )
        if (!written) {
            println("disposition contract degraded: no ffmpeg on this platform to write the fixture")
            return
        }
        MediaSource.open(output).use { source ->
            val streams = source.streams
            assertEquals(4, streams.size, "the fixture has three audio streams and one subtitle stream")
            assertTrue(streams[0].disposition.visualImpaired, "audio description was not read")
            assertTrue(streams[1].disposition.comment, "the commentary flag was not read")
            val ordinary = streams[2].disposition
            assertTrue(
                !ordinary.visualImpaired && !ordinary.comment && !ordinary.descriptions && !ordinary.hearingImpaired,
                "the ordinary audio stream reads as special: $ordinary",
            )
            assertTrue(streams[3].disposition.descriptions, "the descriptive subtitle flag was not read")
            assertEquals(streams[2], source.primaryAudio, "the ordinary audio stream must be the one played")
        }
    }
}
