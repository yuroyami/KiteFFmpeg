package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mapping between a format and the implementations that write or read it, against the linked FFmpeg. */
internal class CodecMappingContractTest {
    private val paths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        paths.forEach(::deleteContractPath)
        paths.clear()
    }

    @Test
    fun anEncoderMapsToTheFormatItWrites() {
        assertEquals(CodecId.Mpeg4, FFmpeg.codecOf(EncoderId.Mpeg4))
        assertEquals(CodecId.Aac, FFmpeg.codecOf(EncoderId.Aac))
        assertEquals(CodecId.Png, FFmpeg.codecOf(EncoderId.Png))
        assertNull(FFmpeg.codecOf(EncoderId("no_such_encoder")))
        // Present only in a GPL build, and then it writes H.264.
        FFmpeg.codecOf(EncoderId.Libx264)?.let { assertEquals(CodecId.H264, it) }
        if (FFmpeg.hasEncoder(EncoderId.H264VideoToolbox.name)) {
            assertEquals(CodecId.H264, FFmpeg.codecOf(EncoderId.H264VideoToolbox))
        }
    }

    @Test
    fun aFormatListsItsEncodersWithFfmpegsDefaultFirst() {
        assertEquals(EncoderId.Mpeg4, FFmpeg.encodersFor(CodecId.Mpeg4).first())
        assertEquals(emptyList(), FFmpeg.encodersFor(CodecId("not_a_format")))
        // An encoder name is not a format, which is the confusion the split removes.
        assertEquals(emptyList(), FFmpeg.encodersFor(CodecId("libx264")))
        for (encoder in FFmpeg.encodersFor(CodecId.H264)) {
            assertEquals(CodecId.H264, FFmpeg.codecOf(encoder), "${encoder.name} is listed for H.264")
        }
    }

    @Test
    fun aDecoderMapsToTheFormatItReads() {
        assertEquals(CodecId.Av1, FFmpeg.codecOf(DecoderId.LibDav1d), "dav1d ships in every build")
        assertTrue(DecoderId.LibDav1d in FFmpeg.decodersFor(CodecId.Av1))
        assertEquals(DecoderId("h264"), FFmpeg.decodersFor(CodecId.H264).first())
        assertNull(FFmpeg.codecOf(DecoderId("no_such_decoder")))
    }

    private fun spec(codec: CodecId, encoder: EncoderId? = null) =
        VideoEncoderSpec(codec = codec, encoder = encoder, width = 64, height = 48, frameRate = Rational(25, 1))

    @Test
    fun aSpecOpensTheFormatsDefaultEncoderOrTheOneItNamesAndRefusesAMismatch() {
        val path = contractOutputPath("mkv").also(paths::add)
        MediaSink.open(path).use { sink ->
            sink.addVideoEncoder(spec(CodecId.Mpeg4))
            val mismatch = assertFailsWith<FFmpegException> { sink.addVideoEncoder(spec(CodecId.H264, EncoderId.Mpeg4)) }
            assertIs<FFmpegError.InvalidArgument>(mismatch.error)
            assertTrue("writes 'mpeg4'" in (mismatch.message ?: ""), "the refusal says what it writes: ${mismatch.message}")
        }
    }

    @Test
    fun anEncoderNameInTheFormatFieldIsRefusedWithTheWayOut() {
        val path = contractOutputPath("mkv").also(paths::add)
        MediaSink.open(path).use { sink ->
            val failure = assertFailsWith<FFmpegException> { sink.addVideoEncoder(spec(CodecId("libx264"))) }
            assertIs<FFmpegError.InvalidArgument>(failure.error)
            assertTrue("EncoderId(\"libx264\")" in (failure.message ?: ""), "the refusal names the fix: ${failure.message}")
            val missing = assertFailsWith<FFmpegException> { sink.addVideoEncoder(spec(CodecId.Mpeg4, EncoderId("no_such_encoder"))) }
            assertIs<FFmpegError.EncoderNotFound>(missing.error)
        }
    }
}
