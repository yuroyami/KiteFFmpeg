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
        assertFailsWith<FFmpegException> { refuseSeekBreakingOptions(mapOf("fflags" to "fastseek")) }
        assertFailsWith<FFmpegException> { refuseSeekBreakingOptions(mapOf("fflags" to "-fastseek+fastseek")) }
    }

    @Test
    fun anFflagsValueThatClearsFastSeekPasses() {
        refuseSeekBreakingOptions(mapOf("fflags" to "+genpts-fastseek"))
        refuseSeekBreakingOptions(mapOf("fflags" to "-fastseek"))
        refuseSeekBreakingOptions(mapOf("fflags" to "+fastseek-fastseek"))
    }

    @Test
    fun theReadTimeoutGoesUnderTheNameEachProtocolReads() {
        val options = DemuxOptions(ioTimeoutMicros = 5_000_000, probeSizeBytes = 32)
        for (address in listOf("http://h/a.ts", "HTTPS://h/a.ts", "tcp://h:1", "rtmp://h/app", "/media/a.mkv", "C:\\a.mkv")) {
            assertEquals(address to listOf("probesize" to "32", "rw_timeout" to "5000000"), options.compileFor(address), address)
        }
        for (address in listOf("rtsp://cam/stream", "udp://@:1234", "RTSP://cam/x")) {
            assertEquals(address to listOf("probesize" to "32", "timeout" to "5000000"), options.compileFor(address), address)
        }
        assertEquals("rtp://h:5004?timeout=5000000" to listOf("probesize" to "32"), options.compileFor("rtp://h:5004"))
        assertEquals("rtp://h:5004?ttl=2&timeout=5000000" to listOf("probesize" to "32"), options.compileFor("rtp://h:5004?ttl=2"))
        assertEquals("rtp://h:5004?timeout=1" to listOf("probesize" to "32"), options.compileFor("rtp://h:5004?timeout=1"))
    }

    @Test
    fun withNoReadTimeoutTheAddressAndOptionsStandAsTheyAre() {
        val options = DemuxOptions(probeSizeBytes = 32)
        assertEquals("rtp://h:5004" to listOf("probesize" to "32"), options.compileFor("rtp://h:5004"))
        assertEquals("rtsp://cam" to listOf("probesize" to "32"), options.compileFor("rtsp://cam"))
    }

    @Test
    fun aRawTimeoutStillWinsATie() {
        val compiled = DemuxOptions(ioTimeoutMicros = 5, options = mapOf("timeout" to "9")).compileFor("rtsp://cam").second
        assertEquals("9", compiled.toMap()["timeout"])
    }
}
