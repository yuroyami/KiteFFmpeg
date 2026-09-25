package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [FFmpeg.components] lists what a shipped build is known to contain, and agrees with the probes. */
class ComponentsContractTest {

    private fun assertListed(kind: FFmpegComponent, vararg names: String) {
        val listed = FFmpeg.components(kind)
        assertEquals(listed.sorted(), listed, "$kind must come back sorted")
        for (name in names) assertTrue(name in listed, "$kind does not list $name: $listed")
    }

    @Test
    fun everyFamilyListsWhatTheShippedProfileCarries() {
        assertListed(FFmpegComponent.Decoders, "h264", "hevc", "aac", "libdav1d")
        assertListed(FFmpegComponent.Encoders, "aac", "mpeg4")
        assertListed(FFmpegComponent.Demuxers, "matroska,webm", "mov,mp4,m4a,3gp,3g2,mj2")
        assertListed(FFmpegComponent.Muxers, "mp4", "matroska")
        assertListed(FFmpegComponent.Filters, "scale", "aresample", "yadif")
        assertListed(FFmpegComponent.InputProtocols, "file")
        assertListed(FFmpegComponent.BitstreamFilters, "h264_mp4toannexb")
    }

    @Test
    fun everyListedCodecAnswersTheMatchingProbe() {
        FFmpeg.components(FFmpegComponent.Decoders).forEach { assertTrue(FFmpeg.hasDecoder(it), "listed decoder $it fails hasDecoder") }
        FFmpeg.components(FFmpegComponent.Encoders).forEach { assertTrue(FFmpeg.hasEncoder(it), "listed encoder $it fails hasEncoder") }
        FFmpeg.components(FFmpegComponent.Filters).forEach { assertTrue(FFmpeg.hasFilter(it), "listed filter $it fails hasFilter") }
    }
}
