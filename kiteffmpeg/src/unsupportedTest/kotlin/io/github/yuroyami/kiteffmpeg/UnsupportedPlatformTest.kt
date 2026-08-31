package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UnsupportedPlatformTest {
    @Test
    fun diagnosticsAreReadableAndCapabilitiesAreEmpty() {
        val identity = FFmpeg.identity

        assertFalse(identity.isAcceptable)
        assertEquals(6, identity.libraries.size)
        assertTrue(identity.provisioning.contains("not implemented"))
        assertEquals("0.0.0", FFmpeg.versions.avcodec)
        assertFalse(FFmpeg.hasDecoder("h264"))
        assertFalse(FFmpeg.hasEncoder("aac"))
        assertFalse(FFmpeg.hasFilter("scale"))
    }

    @Test
    fun operationsFailImmediatelyWithTheTypedUnsupportedError() {
        val failure = assertFailsWith<FFmpegException> {
            MediaSource.open("https://example.invalid/video.mp4")
        }

        assertIs<FFmpegError.Unsupported>(failure.error)
        assertTrue(failure.message.orEmpty().contains("placeholder backend"))
    }
}
