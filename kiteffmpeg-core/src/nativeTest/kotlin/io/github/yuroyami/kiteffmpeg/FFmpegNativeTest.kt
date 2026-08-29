package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Runs against the actually-linked FFmpeg. Verifies the cinterop surface end to end. */
class FFmpegNativeTest {

    @Test
    fun versionUnpacking() {
        assertEquals("61.19.101", decodePackedVersion((61u shl 16) or (19u shl 8) or 101u))
        assertEquals("0.0.0", decodePackedVersion(0u))
    }

    @Test
    fun versionsLookSane() {
        val v = FFmpeg.versions
        listOf(v.avutil, v.avcodec, v.avformat, v.avfilter, v.swscale, v.swresample).forEach {
            assertTrue(Regex("""\d+\.\d+\.\d+""").matches(it), "bad version string: $it")
        }
    }

    @Test
    fun capabilityProbing() {
        assertTrue(FFmpeg.hasDecoder("h264"))
        assertTrue(FFmpeg.hasFilter("scale"))
        assertTrue(FFmpeg.hasFilter("anull"))
        assertTrue(!FFmpeg.hasEncoder("definitely_not_a_codec"))
    }

    @Test
    fun errorCodesAreDistinctAndNegative() {
        assertTrue(FFErrors.EAGAIN < 0)
        assertTrue(FFErrors.EOF < 0)
        assertNotEquals(FFErrors.EAGAIN, FFErrors.EOF)
    }

    @Test
    fun avErrorCarriesStrerrorText() {
        val err = avError(FFErrors.EOF)
        assertEquals(FFErrors.EOF, err.code)
        assertTrue(err.message!!.isNotBlank())
    }

    @Test
    fun pixelFormatRoundTrip() {
        assertEquals(PixelFormat.Yuv420p, pixelFormatFromAv(pixelFormatToAv(PixelFormat.Yuv420p)))
        assertEquals(SampleFormat.FltP, sampleFormatFromAv(sampleFormatToAv(SampleFormat.FltP)))
        assertEquals(PixelFormat.None, pixelFormatFromAv(-1))
    }

    @Test
    fun openMissingFileThrowsTypedError() {
        val ex = assertFailsWith<FFmpegException> { MediaSource.open("/definitely/not/here.mp4") }
        assertTrue(ex.code != 0)
    }

    @Test
    fun videoFilterGraphBuildsAndCloses() {
        val graph = FilterGraph.buildVideo(
            description = "scale=160:90,format=yuv420p",
            width = 320, height = 180,
            pixelFormat = PixelFormat.Yuv420p,
            timeBase = Rational(1, 30),
            frameRate = Rational(30, 1),
        )
        assertTrue(graph.outputTimeBase.den != 0)
        graph.close()
        graph.close()  // idempotent
    }

    @Test
    fun audioFilterGraphBuildsWithAformatPin() {
        val graph = FilterGraph.buildAudio(
            description = "volume=0.5",
            sampleRate = 48_000,
            sampleFormat = SampleFormat.FltP,
            channels = 2,
            timeBase = Rational(1, 48_000),
            outputSampleRate = 44_100,
            outputSampleFormat = SampleFormat.FltP,
            outputChannels = 2,
        )
        graph.setOutputFrameSize(1024)
        graph.close()
    }

    @Test
    fun badFilterDescriptionFailsCleanly() {
        assertFailsWith<FFmpegException> {
            FilterGraph.buildVideo(
                description = "thisfilterdoesnotexist=1",
                width = 320, height = 180,
                pixelFormat = PixelFormat.Yuv420p,
                timeBase = Rational(1, 30),
                frameRate = Rational(30, 1),
            )
        }
    }

    @Test
    fun overlayGraphBuildsWithTwoInputs() {
        val base = VideoInput(320, 180, PixelFormat.Yuv420p, Rational(1, 30), Rational(30, 1))
        val logo = VideoInput(64, 64, PixelFormat.Rgba, Rational(1, 30), Rational(30, 1))
        val graph = FilterGraph.buildVideoMulti(
            description = "[in0][in1]overlay=W-w-10:H-h-10[out]",
            inputs = listOf(base, logo),
        )
        assertEquals(2, graph.inputCount)
        assertTrue(graph.outputTimeBase.den != 0)
        graph.close()
    }

    @Test
    fun amixGraphBuildsWithTwoInputs() {
        val a = AudioInput(48_000, SampleFormat.FltP, 2, Rational(1, 48_000))
        val b = AudioInput(44_100, SampleFormat.FltP, 1, Rational(1, 44_100))
        val graph = FilterGraph.buildAudioMulti(
            description = "[in0][in1]amix=inputs=2:duration=longest[out]",
            inputs = listOf(a, b),
            outputSampleRate = 44_100,
            outputSampleFormat = SampleFormat.FltP,
            outputChannels = 2,
        )
        assertEquals(2, graph.inputCount)
        graph.setOutputFrameSize(1024)
        graph.close()
    }

    @Test
    fun multiInputIndexValidated() {
        val graph = FilterGraph.buildVideo(
            description = "null",
            width = 64, height = 64,
            pixelFormat = PixelFormat.Yuv420p,
            timeBase = Rational(1, 30),
            frameRate = Rational(30, 1),
        )
        assertFailsWith<IllegalArgumentException> { graph.flushInput(5) { } }
        graph.close()
    }
}
