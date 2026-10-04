package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A stream's BCP 47 language survives a remux into a container whose field holds a three-letter
 * ISO 639-2 code (#156), a Matroska track reports its `LanguageBCP47` (#150), and an MP4 track its
 * `elng` (#157).
 *
 * An HLS rendition, a DASH representation and a Matroska track written by MKVToolNix name their
 * language with a BCP 47 tag such as `pt-BR`, or `en` for English. MP4 and MPEG-TS used to drop
 * such a tag and Matroska wrote it where a code belongs; they now write the code of the language
 * it names. Matroska also writes the whole tag as `LanguageBCP47`, and MP4 as `elng` where the tag
 * says more than the code, and each reader now prefers the tag to the code. `ffmpeg` writes the
 * fixture, whose Matroska `Language` elements it fills with the tags as they are, so the test is
 * skipped where there is no command-line oracle, which is an Android device.
 */
internal class LanguageTagContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    /** Three AAC tracks tagged `pt-BR`, `en` and `zh-Hant`, or null without an oracle. */
    private fun source(): String? {
        val output = path("mkv")
        val written = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-f", "lavfi", "-i", "sine=frequency=880:duration=1",
                "-f", "lavfi", "-i", "sine=frequency=660:duration=1",
                "-map", "0", "-map", "1", "-map", "2", "-c:a", "aac",
                "-metadata:s:a:0", "language=pt-BR",
                "-metadata:s:a:1", "language=en",
                "-metadata:s:a:2", "language=zh-Hant",
            ),
            output,
        )
        return output.takeIf { written }
    }

    private fun languages(file: String): List<String?> =
        MediaSource.open(file).use { source -> source.streams.map { it.language } }

    private fun remuxed(extension: String, expected: List<String?>) {
        val input = source() ?: return println("language tag contract degraded: no ffmpeg")
        assertEquals(listOf("pt-BR", "en", "zh-Hant"), languages(input), "the fixture lacks its tags, so the test proves nothing")
        val output = path(extension)
        runBlocking { Remuxer.remux(input = input, output = output) }
        assertEquals(expected, languages(output), "the languages of a remux into $extension")
    }

    /** `en` says no more than `eng`, so it writes no `elng` and reads back as the code. */
    @Test
    fun anMp4RemuxKeepsEachTagThatSaysMoreThanItsCode() = remuxed("mp4", listOf("pt-BR", "eng", "zh-Hant"))

    @Test
    fun anMpegTsRemuxWritesTheCodeOfEachLanguage() = remuxed("ts", listOf("por", "eng", "chi"))

    @Test
    fun aMatroskaRemuxKeepsEachWholeTag() = remuxed("mkv", listOf("pt-BR", "en", "zh-Hant"))
}
