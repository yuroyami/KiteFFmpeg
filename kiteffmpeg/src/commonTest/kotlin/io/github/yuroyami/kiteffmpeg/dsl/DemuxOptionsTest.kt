package io.github.yuroyami.kiteffmpeg.dsl

import io.github.yuroyami.kiteffmpeg.FFmpegError
import io.github.yuroyami.kiteffmpeg.FFmpegException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The pure half of [DemuxOptions]: the pairs it compiles to and the two refused keys. */
class DemuxOptionsTest {

    @Test
    fun everyTypedKnobCompilesToFFmpegsOwnKeyInAStableOrder() {
        val options = DemuxOptions(
            probeSizeBytes = 1_000_000,
            analyzeDurationMicros = 2_000_000,
            fpsProbeFrames = 12,
            flags = setOf(DemuxFlag.GeneratePts, DemuxFlag.DiscardCorrupt),
            formatWhitelist = linkedSetOf("mov", "matroska"),
            protocolWhitelist = linkedSetOf("file"),
            ioTimeoutMicros = 5_000_000,
            options = mapOf("max_delay" to "0"),
        )
        assertEquals(
            listOf(
                "probesize" to "1000000",
                "analyzeduration" to "2000000",
                "fpsprobesize" to "12",
                // Each flag carries a +, so it is added to FFmpeg's default flags instead of replacing them.
                "fflags" to "+discardcorrupt+genpts",
                "format_whitelist" to "mov,matroska",
                "protocol_whitelist" to "file",
                "rw_timeout" to "5000000",
                "max_delay" to "0",
            ),
            options.compile(),
        )
    }

    @Test
    fun aNamedFormatCompilesLastUnderTheKeyTheCOpenTakesOut() {
        val compiled = DemuxOptions(format = "s16le", options = mapOf("sample_rate" to "48000")).compile()
        assertEquals(listOf("sample_rate" to "48000", "kiteffmpeg_input_format" to "s16le"), compiled)
    }

    @Test
    fun emptyOptionsCompileToNothing() {
        assertEquals(emptyList(), DemuxOptions().compile())
    }

    @Test
    fun theLowLatencyPresetSkipsBufferingAndProbesBriefly() {
        val compiled = DemuxOptions.LowLatency.compile().toMap()
        assertEquals("+nobuffer", compiled["fflags"])
        assertEquals("32768", compiled["probesize"])
    }

    @Test
    fun usetocAndFastSeekAreRefusedWithAReason() {
        val toc = assertFailsWith<FFmpegException> { refuseSeekBreakingOptions(mapOf("usetoc" to "1")) }
        assertIs<FFmpegError.InvalidArgument>(toc.error)
        assertTrue("usetoc" in (toc.message ?: ""))
        val fast = assertFailsWith<FFmpegException> { refuseSeekBreakingOptions(mapOf("fflags" to "+genpts+fastseek")) }
        assertTrue("fastseek" in (fast.message ?: ""))
        refuseSeekBreakingOptions(mapOf("fflags" to "+genpts", "probesize" to "32"))
    }
}
