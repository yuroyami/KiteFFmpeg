package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A text subtitle track can be written to its own file (#86).
 *
 * This is a macOS test for the same reason as [EditingFiltersTest]: only the macOS CI job bakes its
 * FFmpeg tree from the current recipe.
 */
class SubtitleWritingTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    @Test
    fun theTextSubtitleEncodersAndMuxersAreCompiledIn() {
        assertEquals(emptyList(), listOf("mov_text", "srt", "subrip", "ass", "webvtt").filterNot(FFmpeg::hasEncoder))
        val muxers = FFmpeg.components(FFmpegComponent.Muxers)
        assertEquals(emptyList(), listOf("srt", "ass", "webvtt").filterNot { it in muxers })
    }

    @Test
    fun aSubRipTrackInMatroskaRemuxesToAnSrtFileWithTheSameCues() = runTest {
        val srt = materializeContractMedia(CUE, sha256Hex(CUE)).also(paths::add)
        val mkv = contractOutputPath("mkv").also(paths::add)
        if (!MediaOracle.generate(listOf("-i", srt, "-c:s", "subrip"), mkv)) {
            return@runTest println("subtitle writing degraded: no ffmpeg")
        }
        val written = contractOutputPath("srt").also(paths::add)
        Remuxer.remux(mkv, written)
        val text = readContractBytes(written).decodeToString()
        assertTrue("00:00:00,000 --> 00:00:00,900\nFirst" in text, "the first cue keeps its times and text: $text")
        assertTrue("00:00:01,000 --> 00:00:02,500\nHello there" in text, "the second cue keeps its times and text: $text")
    }

    private companion object {
        // The remuxer shifts every timestamp by the input's start time, as ffmpeg does, so the
        // first cue starts at zero and the times of both must come out unchanged.
        val CUE: ByteArray =
            "1\n00:00:00,000 --> 00:00:00,900\nFirst\n\n2\n00:00:01,000 --> 00:00:02,500\nHello there\n\n".encodeToByteArray()
    }
}
