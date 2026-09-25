package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [FFmpeg.components] on the web reads the codec module's own list and sorts it. */
class WebComponentsTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun theModulesListComesBackSortedPerFamily() {
        useCodecModule(fakeCodecModule())
        assertEquals(listOf("aac", "h264", "libdav1d"), FFmpeg.components(FFmpegComponent.Decoders))
        assertEquals(listOf("matroska,webm", "mov,mp4,m4a,3gp,3g2,mj2"), FFmpeg.components(FFmpegComponent.Demuxers))
        assertEquals(emptyList(), FFmpeg.components(FFmpegComponent.Muxers))
        assertEquals(listOf("null"), FFmpeg.components(FFmpegComponent.BitstreamFilters))
    }
}
