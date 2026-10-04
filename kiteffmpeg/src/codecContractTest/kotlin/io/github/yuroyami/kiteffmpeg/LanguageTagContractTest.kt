package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A stream's BCP 47 language survives a remux into a container whose field holds a three-letter
 * ISO 639-2 code (#156), and a Matroska track reports its `LanguageBCP47` (#150).
 *
 * An HLS rendition, a DASH representation and a Matroska track written by MKVToolNix name their
 * language with a BCP 47 tag such as `pt-BR`, or `en` for English. MP4 and MPEG-TS used to drop
 * such a tag and Matroska wrote it where a code belongs; they now write the code of the language
 * it names, and Matroska also writes the whole tag as `LanguageBCP47`, which its reader now
 * prefers to the code. `ffmpeg` writes the fixture, whose Matroska `Language` elements it fills
 * with the tags as they are, so the test is skipped where there is no command-line oracle, which
 * is an Android device.
 */
internal class LanguageTagContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** Two AAC tracks tagged `pt-BR` and `en`, or null without an oracle. */
    private fun source(): String? {
        val output = path("mkv")
        val written = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-f", "lavfi", "-i", "sine=frequency=880:duration=1",
                "-map", "0", "-map", "1", "-c:a", "aac",
                "-metadata:s:a:0", "language=pt-BR",
                "-metadata:s:a:1", "language=en",
            ),
            output,
        )
        return output.takeIf { written }
    }

    private fun languages(file: String): List<String?> =
        MediaSource.open(file).use { source -> source.streams.map { it.language } }

    private fun remuxed(extension: String, expected: List<String?>) {
        val input = source() ?: return println("language tag contract degraded: no ffmpeg")
        assertEquals(listOf("pt-BR", "en"), languages(input), "the fixture lacks its tags, so the test proves nothing")
        val output = path(extension)
        runBlocking { Remuxer.remux(input = input, output = output) }
        assertEquals(expected, languages(output), "the languages of a remux into $extension")
    }

    @Test
    fun anMp4RemuxWritesTheCodeOfEachLanguage() = remuxed("mp4", listOf("por", "eng"))

    @Test
    fun anMpegTsRemuxWritesTheCodeOfEachLanguage() = remuxed("ts", listOf("por", "eng"))

    @Test
    fun aMatroskaRemuxKeepsEachWholeTag() = remuxed("mkv", listOf("pt-BR", "en"))
}
