package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ErrorsTest {

    @Test
    fun ffErrTagValuesMatchFFmpeg() {
        // FFERRTAG('E','O','F',' ') and the related tags, computed the same way avutil/error.h does.
        assertEquals(-0x20464f45, FFmpegError.AVERROR_EOF)
        assertEquals(-0x41444e49, FFmpegError.AVERROR_INVALIDDATA)
    }

    @Test
    fun errnoCodesClassify() {
        assertIs<FFmpegError.FileNotFound>(FFmpegError.fromCode(-2, "no such file"))
        assertIs<FFmpegError.PermissionDenied>(FFmpegError.fromCode(-13, "denied"))
        assertIs<FFmpegError.OutOfMemory>(FFmpegError.fromCode(-12, "oom"))
        assertIs<FFmpegError.InvalidArgument>(FFmpegError.fromCode(-22, "einval"))
        assertIs<FFmpegError.Io>(FFmpegError.fromCode(-5, "eio"))
    }

    @Test
    fun tagCodesClassify() {
        assertIs<FFmpegError.EndOfFile>(FFmpegError.fromCode(FFmpegError.AVERROR_EOF, "eof"))
        assertIs<FFmpegError.InvalidData>(FFmpegError.fromCode(FFmpegError.AVERROR_INVALIDDATA, "bad"))
        assertIs<FFmpegError.EncoderNotFound>(FFmpegError.fromCode(FFmpegError.AVERROR_ENCODER_NOT_FOUND, "enc"))
        assertIs<FFmpegError.DecoderNotFound>(FFmpegError.fromCode(FFmpegError.AVERROR_DECODER_NOT_FOUND, "dec"))
        assertIs<FFmpegError.MuxerNotFound>(FFmpegError.fromCode(FFmpegError.AVERROR_MUXER_NOT_FOUND, "mux"))
        assertIs<FFmpegError.FilterNotFound>(FFmpegError.fromCode(FFmpegError.AVERROR_FILTER_NOT_FOUND, "fil"))
        assertIs<FFmpegError.ProtocolNotFound>(FFmpegError.fromCode(FFmpegError.AVERROR_PROTOCOL_NOT_FOUND, "pro"))
    }

    @Test
    fun unmappedCodeFallsBackToAvError() {
        val e = FFmpegError.fromCode(-9999, "mystery")
        assertIs<FFmpegError.AvError>(e)
        assertEquals(-9999, e.code)
        assertEquals("mystery", e.message)
    }

    @Test
    fun exceptionExposesErrorAndCode() {
        val ex = FFmpegException(FFmpegError.fromCode(-2, "gone"))
        assertEquals(-2, ex.code)
        assertIs<FFmpegError.FileNotFound>(ex.error)
        assertTrue(ex.message!!.contains("gone"))
    }

    @Test
    fun internalHasZeroCode() {
        assertEquals(0, FFmpegError.Internal("invariant").code)
    }
    /**
     * A missing decoder must name the CODEC, not a raw integer.
     *
     * The row was opened by the owner from a real incident: an iOS device threw FFmpeg's bare -78
     * on an AV1 file and nothing could say whether that build carried dav1d. It took an hour of
     * binary archaeology to learn the answer was "the installed app was stale". Three of the seven
     * refusal sites printed `codec id 226`, which is the number the caller already could not act
     * on, and the stream's own codec name was in scope at every one of them.
     */
    @Test
    fun aMissingDecoderNamesTheCodecRatherThanItsInteger() {
        val message = decoderNotFoundMessage(streamCodec = CodecId("av1"), requested = null)
        assertTrue("av1" in message, "the codec must be named: $message")
        assertTrue("codec id" !in message, "a raw id is what this row exists to remove: $message")
    }

    /** And it points at the query that answers the question without opening a file. */
    @Test
    fun aMissingDecoderNamesTheCallThatWouldHaveAnsweredItSooner() {
        val message = decoderNotFoundMessage(streamCodec = CodecId("av1"), requested = null)
        assertTrue("hasDecoder" in message, "name the runtime query: $message")
        assertTrue("identity" in message, "name what says WHICH build is loaded: $message")
    }

    /**
     * A decoder asked for BY NAME fails differently from a codec with no decoder at all.
     *
     * Conflating them is what makes "no decoder" unactionable: one means this build lacks the
     * codec entirely, the other means it lacks the specific implementation you asked for and may
     * still decode the stream perfectly well with its default.
     */
    @Test
    fun aRequestedDecoderRefusalNamesBothTheDecoderAndTheStreamCodec() {
        val message = decoderNotFoundMessage(streamCodec = CodecId("av1"), requested = CodecId("libdav1d"))
        assertTrue("libdav1d" in message, "the decoder actually asked for: $message")
        assertTrue("av1" in message, "and the stream codec it was asked for: $message")
        assertTrue("hasDecoder" in message, "the query takes the DECODER name here: $message")
    }

    /** The two cases must not render identically, or the distinction above buys nothing. */
    @Test
    fun theTwoRefusalsReadDifferently() {
        assertTrue(
            decoderNotFoundMessage(CodecId("av1"), null) !=
                decoderNotFoundMessage(CodecId("av1"), CodecId("libdav1d")),
        )
    }

}
