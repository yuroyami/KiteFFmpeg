package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A remux carries what names each stream, and the chapters, not only the packets. A chapter keeps
 * its place against the video, which is what a viewer sees of it (#144).
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

        /** Where the trimmed remux cuts, which is where the Ending chapter starts in the source. */
        const val CUT_MICROS = 1_000_000L

        /** The ffprobe keys this test compares, for the first two streams and every chapter. */
        val COMPARED = Regex(
            """streams\.stream\.[01]\.(tags\.(language|title)|disposition\.(default|comment)|side_data_list\.side_data\.0\.rotation)|""" +
                """streams\.stream\.0\.start_time|chapters\.chapter\.\d+\.(start_time|tags\.title)""",
        )

        /** Where a chapter starts, which is compared from the start of the video. */
        val CHAPTER_START = Regex("""chapters\.chapter\.\d+\.start_time""")

        const val VIDEO_START = "streams.stream.0.start_time"
    }

    /** ffprobe's flat description of [file], restricted to [COMPARED], or null without an oracle. */
    private fun probe(file: String): Map<String, String>? = runMediaOracle(
        "ffprobe",
        listOf(
            "-v", "error",
            "-show_entries", "stream=index,start_time:stream_tags=language,title:stream_disposition=default,comment:stream_side_data=rotation",
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
            if (CHAPTER_START.matches(key)) {
                val was = fromVideoStart(before, key)
                val now = fromVideoStart(after, key)
                assertTrue(abs(was - now) <= 0.001, "$key was ${before[key]} with the video at ${before[VIDEO_START]} and is ${after[key]} with it at ${after[VIDEO_START]}")
            } else {
                assertEquals(before[key], after[key], "$key did not survive the remux")
            }
        }
    }

    private fun fromVideoStart(probed: Map<String, String>, key: String): Double =
        checkNotNull(probed[key]).toDouble() - checkNotNull(probed[VIDEO_START]) { "no video start: $probed" }.toDouble()

    /** The presentation time of each keyframe of [file]'s video, in microseconds, or null without an oracle. */
    private fun videoKeyframesMicros(file: String): List<Long>? = runMediaOracle(
        "ffprobe",
        listOf("-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", file),
    )?.lineSequence()
        ?.map { it.trim().split(',') }
        ?.filter { it.size >= 2 && it[1].startsWith("K") }
        ?.map { (it[0].toDouble() * 1_000_000.0).roundToLong() }
        ?.toList()

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

    /**
     * The copy starts at the keyframe at or before the cut, so the Ending chapter starts as far
     * into the output as that keyframe is before it, and the Opening covers the frames in between.
     */
    @Test
    fun aTrimmedRemuxKeepsTheChaptersOnTheFramesItCopies() {
        val input = base("mkv") ?: return println("remux identity contract degraded: no ffmpeg")
        val landing = videoKeyframesMicros(input)?.lastOrNull { it <= CUT_MICROS } ?: return
        val output = path("mkv")
        runBlocking { Remuxer.remux(input = input, output = output, startMicros = CUT_MICROS) }
        val videoStart = checkNotNull(probe(output)?.get(VIDEO_START)).toDouble().let { (it * 1_000_000.0).roundToLong() }
        MediaSource.open(output).use { trimmed ->
            val titles = trimmed.chapters.map { it.title }
            assertEquals(if (landing < CUT_MICROS) listOf("Opening", "Ending") else listOf("Ending"), titles, "the copy starts at $landing us")
            val ending = trimmed.chapters.single { it.title == "Ending" }
            assertTrue(
                abs(ending.startMicros - videoStart - (CUT_MICROS - landing)) <= 1_000L,
                "the Ending starts at ${ending.startMicros} us, the video at $videoStart us and the copy at the keyframe at $landing us",
            )
        }
    }
}
