package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A caller's language preference picks the audio track from a real file.
 *
 * `ffmpeg` writes the fixture, so the test is skipped where there is no command-line oracle,
 * which is an Android device.
 */
internal class TrackSelectionContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    @Test
    fun thePreferredLanguagePicksTheAudioTrack() {
        val output = contractOutputPath("mkv").also(paths::add)
        val written = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-f", "lavfi", "-i", "sine=frequency=880:duration=1",
                "-map", "0", "-map", "1", "-c:a", "flac",
                "-metadata:s:a:0", "language=eng",
                "-metadata:s:a:1", "language=jpn",
            ),
            output,
        )
        if (!written) {
            println("track selection contract degraded: no ffmpeg on this platform to write the fixture")
            return
        }
        MediaSource.open(output).use { source ->
            val (english, japanese) = source.streams
            assertEquals("eng", english.language)
            assertEquals("jpn", japanese.language)
            assertEquals(english, source.primaryAudio, "with no preference the first track plays")
            assertEquals(japanese, TrackSelector(listOf("jpn")).selectAudio(source.streams))
            assertEquals(japanese, TrackSelector(listOf("fre", "jpn", "eng")).selectAudio(source.streams))
        }
    }
}
