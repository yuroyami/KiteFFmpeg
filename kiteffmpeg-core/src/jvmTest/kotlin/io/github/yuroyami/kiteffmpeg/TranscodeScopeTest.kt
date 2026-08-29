package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a transcode is allowed to be, and whether it says how far it has got (audit P1-15, P1-16).
 *
 * Two defects that look unrelated and are the same shape: the transcoder measured itself only by
 * what it ENCODED. A run with nothing to encode was therefore either refused outright, or ran to
 * completion reporting zero percent the whole way.
 */
class TranscodeScopeTest {

    private val files = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        files.forEach { it.delete() }
        files.clear()
    }

    private fun tmp(name: String, suffix: String = ".mkv"): File =
        File.createTempFile("kiteffmpeg-scope-$name", suffix).also { files += it }

    /** A real file with video and audio, so a copy-only run has something to copy. */
    private fun writeInput(file: File, frames: Int = 50) {
        MediaSink.open(file.absolutePath).use { sink ->
            val video = sink.addVideoEncoder(
                VideoEncoderSpec(
                    codec = CodecId("mpeg4"),
                    width = 96, height = 96,
                    frameRate = Rational(25, 1),
                    bitrateBps = 300_000,
                ),
            )
            runBlocking {
                video.drive(
                    (0 until frames).asFlow().map { i ->
                        val w = 96
                        val h = 96
                        val y = ByteArray(w * h) { ((it + i * 3) % 200).toByte() }
                        val u = ByteArray(w * h / 4) { 100.toByte() }
                        val v = ByteArray(w * h / 4) { (140 + i % 40).toByte() }
                        Frame.ofVideo(y + u + v, w, h, PixelFormat.Yuv420p, i * 40_000L)
                    },
                )
            }
        }
    }

    /**
     * Red by reading progress from `primaryCore?.outputMicros ?: 0` again: every report is then
     * zero percent, from the first packet to the last, while the copy runs at full speed.
     */
    @Test
    fun `a copy only transcode reports real progress`() {
        val input = tmp("copy-in")
        val output = tmp("copy-out")
        writeInput(input)

        val seen = mutableListOf<TranscodeProgress>()
        runBlocking {
            Transcoder.transcode(
                input = input.absolutePath,
                output = output.absolutePath,
                videoCopy = true,
                onProgress = { seen += it },
            )
        }

        assertTrue(seen.isNotEmpty(), "a transcode with a progress callback must report something")
        val furthest = seen.maxOf { it.outputMicros }
        assertTrue(
            furthest > 0L,
            "every report said the output was 0 microseconds long while the whole file was " +
                "copied: ${seen.map { it.outputMicros }}",
        )
        val percent = seen.mapNotNull { it.percent }.maxOrNull()
        assertTrue(
            percent != null && percent > 0.5,
            "a completed copy must finish well past halfway, reported $percent",
        )
    }

    /**
     * Red by putting `subtitleCopy` back out of the "nothing to output" check: extracting the
     * subtitles from a file is then refused as if it were an empty request.
     */
    @Test
    fun `asking for subtitles alone is a real request and not nothing`() {
        val input = tmp("subs-in")
        val output = tmp("subs-out")
        writeInput(input)

        // This input carries no subtitle stream, so the transcode has nothing to write. What is
        // under test is the VALIDATION: the old code refused the arguments before ever opening the
        // file, saying there was nothing to output, which is a different and wrong answer.
        val failure = runCatching {
            runBlocking {
                Transcoder.transcode(
                    input = input.absolutePath,
                    output = output.absolutePath,
                    subtitleCopy = true,
                )
            }
        }.exceptionOrNull()

        assertTrue(
            failure !is IllegalArgumentException,
            "subtitleCopy alone must pass argument validation; it was rejected as an empty " +
                "request with: ${failure?.message}",
        )
    }
}
