package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A remux carries what names each stream, and the chapters, not only the packets.
 *
 * `ffmpeg` writes the fixtures and `ffprobe` compares input and output, so the test is skipped
 * where there is no command-line oracle, which is an Android device. MP4 keeps a display matrix,
 * languages, dispositions and chapters but no stream titles; Matroska keeps titles and has no
 * display matrix. So each container is checked for what it can carry.
 */
internal class RemuxIdentityContractTest {
    private val paths = mutableListOf<String>()

    private fun path(extension: String): String = contractOutputPath(extension).also(paths::add)

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    private companion object {
        val CHAPTERS: ByteArray = (
            ";FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=1000\ntitle=Opening\n" +
                "[CHAPTER]\nTIMEBASE=1/1000\nSTART=1000\nEND=2000\ntitle=Ending\n"
            ).encodeToByteArray()
        const val CHAPTERS_SHA256 = "f27ce2eedbf2febf2baaf24351cffbeabeb97c43f7cb6593f89d96a3606c5c35"

        /** The ffprobe keys this test compares, for the first two streams and every chapter. */
        val COMPARED = Regex(
            """streams\.stream\.[01]\.(tags\.(language|title)|disposition\.(default|comment)|side_data_list\.side_data\.0\.rotation)|""" +
                """chapters\.chapter\.\d+\.(start_time|tags\.title)""",
        )
    }

    /** ffprobe's flat description of [file], restricted to [COMPARED], or null without an oracle. */
    private fun probe(file: String): Map<String, String>? = runMediaOracle(
        "ffprobe",
        listOf(
            "-v", "error",
            "-show_entries", "stream=index:stream_tags=language,title:stream_disposition=default,comment:stream_side_data=rotation",
            "-show_chapters", "-of", "flat", file,
        ),
    )?.lineSequence()
        ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
        ?.filter { (key, _) -> COMPARED.matches(key) }
        ?.associate { (key, value) -> key to value.trim('"') }

    /** Writes the fixture base: two seconds of video and audio, tagged, with two chapters. */
    private fun base(extension: String): String? {
        val chapters = materializeContractMedia(CHAPTERS, CHAPTERS_SHA256).also(paths::add)
        val output = path(extension)
        val written = MediaOracle.generate(
            listOf(
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-f", "ffmetadata", "-i", chapters,
                "-map", "0:v", "-map", "1:a", "-map_chapters", "2",
                "-c:v", "mpeg4", "-c:a", "aac",
                "-metadata:s:v:0", "language=jpn", "-metadata:s:v:0", "title=Main",
                "-metadata:s:a:0", "language=eng", "-metadata:s:a:0", "title=Commentary",
                "-disposition:v:0", "default", "-disposition:a:0", "default+comment",
            ),
            output,
        )
        return output.takeIf { written }
    }

    private fun assertCarried(input: String, output: String, expectedKeys: List<String>) {
        val before = probe(input) ?: return
        val after = probe(output) ?: return
        for (key in expectedKeys) {
            assertTrue(key in before, "the fixture lacks $key, so the test proves nothing: $before")
            assertEquals(before[key], after[key], "$key did not survive the remux")
        }
    }

    @Test
    fun anMp4RemuxKeepsRotationLanguagesDispositionsAndChapters() {
        val base = base("mp4") ?: return println("remux identity contract degraded: no ffmpeg")
        val rotated = path("mp4")
        // The display matrix is an input option in ffmpeg, so it takes a second, copying pass.
        val rotatedOk = MediaOracle.generate(
            listOf("-display_rotation", "90", "-i", base, "-map", "0:v", "-map", "0:a", "-c", "copy", "-map_chapters", "0"),
            rotated,
        )
        if (!rotatedOk) return
        val output = path("mp4")
        runBlocking { Remuxer.remux(input = rotated, output = output) }
        assertCarried(
            rotated,
            output,
            listOf(
                "streams.stream.0.side_data_list.side_data.0.rotation",
                "streams.stream.0.tags.language",
                "streams.stream.1.tags.language",
                "streams.stream.0.disposition.default",
                "streams.stream.1.disposition.comment",
                "chapters.chapter.0.tags.title",
                "chapters.chapter.1.tags.title",
                "chapters.chapter.1.start_time",
            ),
        )
    }

    @Test
    fun aMatroskaRemuxKeepsTitlesLanguagesDispositionsAndChapters() {
        val input = base("mkv") ?: return println("remux identity contract degraded: no ffmpeg")
        val output = path("mkv")
        runBlocking { Remuxer.remux(input = input, output = output) }
        assertCarried(
            input,
            output,
            listOf(
                "streams.stream.0.tags.language",
                "streams.stream.0.tags.title",
                "streams.stream.1.tags.language",
                "streams.stream.1.tags.title",
                "streams.stream.1.disposition.comment",
                "chapters.chapter.0.tags.title",
                "chapters.chapter.1.tags.title",
                "chapters.chapter.1.start_time",
            ),
        )
    }

    @Test
    fun aTrimmedRemuxMovesTheChaptersOntoItsOwnTimeline() {
        val input = base("mkv") ?: return println("remux identity contract degraded: no ffmpeg")
        val output = path("mkv")
        runBlocking { Remuxer.remux(input = input, output = output, startMicros = 1_000_000L) }
        MediaSource.open(output).use { trimmed ->
            val titles = trimmed.chapters.map { it.title }
            assertEquals(listOf("Ending"), titles, "only the chapter inside the window survives")
            assertEquals(0L, trimmed.chapters.single().startMicros - trimmed.startTimeMicros)
        }
    }
}
