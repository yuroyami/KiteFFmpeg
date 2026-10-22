package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The comparison behind [MediaSource.streamDivergences], on its own.
 *
 * The wiring is four lines per backend; the judgement is all here, and the judgement is where this
 * gets it wrong. Two ways to be wrong, and only one of them is obvious:
 *
 *  - missing a real contradiction, which is the feature not working;
 *  - calling silence a contradiction, which is worse. Most containers say nothing about audio
 *    sample format (Matroska has no field for it), so a comparison that treats an undeclared value
 *    as a disagreement reports nearly every file and nobody ever reads the report again.
 *
 * Every case below is one of those two.
 */
class StreamDivergenceTest {

    private fun videoStream(width: Int = 1920, height: Int = 1080, format: PixelFormat = PixelFormat.Yuv420p) =
        StreamInfo(
            index = 0,
            type = MediaType.Video,
            codec = CodecId("h264"),
            timeBase = Rational(1, 1000),
            durationMicros = null,
            bitrateBps = null,
            video = VideoStreamInfo(
                width = width,
                height = height,
                pixelFormat = format,
                frameRate = Rational(25, 1),
                sampleAspectRatio = Rational(1, 1),
            ),
        )

    private fun audioStream(rate: Int = 48_000, channels: Int = 2, format: SampleFormat = SampleFormat.FltP) =
        StreamInfo(
            index = 1,
            type = MediaType.Audio,
            codec = CodecId("aac"),
            timeBase = Rational(1, 48_000),
            durationMicros = null,
            bitrateBps = null,
            audio = AudioStreamInfo(sampleRate = rate, channels = channels, sampleFormat = format),
        )

    private fun videoFrame(width: Int = 1920, height: Int = 1080, format: PixelFormat = PixelFormat.Yuv420p) =
        FrameInfo(
            streamIndex = 0,
            type = MediaType.Video,
            pts = 0,
            timeBase = Rational(1, 1000),
            width = width,
            height = height,
            pixelFormat = format,
        )

    private fun audioFrame(rate: Int = 48_000, channels: Int = 2, format: SampleFormat = SampleFormat.FltP) =
        FrameInfo(
            streamIndex = 1,
            type = MediaType.Audio,
            pts = 0,
            timeBase = Rational(1, 48_000),
            sampleRate = rate,
            channelCount = channels,
            sampleFormat = format,
        )

    @Test
    fun aStreamThatMatchesReportsNothing() {
        assertEquals(emptyList(), divergencesOf(videoStream(), videoFrame()))
        assertEquals(emptyList(), divergencesOf(audioStream(), audioFrame()))
    }

    @Test
    fun aMislabelledResolutionIsCaughtOnBothAxes() {
        val found = divergencesOf(videoStream(width = 1920, height = 1080), videoFrame(width = 1440, height = 1080))
        assertEquals(1, found.size, "only the width differs: $found")
        assertEquals(DivergentField.Width, found[0].field)
        assertEquals("1920", found[0].declared)
        assertEquals("1440", found[0].decoded)
        assertTrue(found[0].message.contains("1920") && found[0].message.contains("1440"), found[0].message)

        val both = divergencesOf(videoStream(width = 1920, height = 1080), videoFrame(width = 1440, height = 720))
        assertEquals(
            listOf(DivergentField.Width, DivergentField.Height),
            both.map { it.field },
            "both axes differ and both are worth naming",
        )
    }

    @Test
    fun aMislabelledPixelFormatIsCaught() {
        val found = divergencesOf(
            videoStream(format = PixelFormat.Yuv420p),
            videoFrame(format = PixelFormat.Yuv422p),
        )
        assertEquals(listOf(DivergentField.PixelFormat), found.map { it.field })
    }

    @Test
    fun aMislabelledAudioStreamIsCaughtOnEveryField() {
        val found = divergencesOf(
            audioStream(rate = 48_000, channels = 2, format = SampleFormat.FltP),
            audioFrame(rate = 44_100, channels = 6, format = SampleFormat.S16),
        )
        assertEquals(
            listOf(DivergentField.SampleRate, DivergentField.Channels, DivergentField.SampleFormat),
            found.map { it.field },
        )
        assertTrue(found.all { it.streamIndex == 1 }, "every row names the stream it came from")
    }

    @Test
    fun aContainerThatDeclaredNothingIsNotLying() {
        // The case that decides whether this feature is usable. Matroska stores no audio sample
        // format, so codecpar carries None and the decoder fills in the truth. If that counted,
        // nearly every file in the fixture set would report a divergence.
        assertEquals(
            emptyList(),
            divergencesOf(audioStream(format = SampleFormat.None), audioFrame(format = SampleFormat.FltP)),
        )
        assertEquals(
            emptyList(),
            divergencesOf(audioStream(rate = 0, channels = 0), audioFrame(rate = 44_100, channels = 6)),
        )
        assertEquals(
            emptyList(),
            divergencesOf(videoStream(width = 0, height = 0), videoFrame(width = 1440, height = 720)),
        )
        assertEquals(
            emptyList(),
            divergencesOf(videoStream(format = PixelFormat.None), videoFrame(format = PixelFormat.Yuv420p)),
        )
    }

    @Test
    fun aDecoderThatSaidNothingIsNotEvidenceEither() {
        // The other direction, and it happens: a hardware frame carries no readable pixel format
        // until it is downloaded. Comparing against that would report a lie the file never told.
        assertEquals(
            emptyList(),
            divergencesOf(videoStream(format = PixelFormat.Yuv420p), videoFrame(width = 0, height = 0, format = PixelFormat.None)),
        )
        assertEquals(
            emptyList(),
            divergencesOf(audioStream(), audioFrame(rate = 0, channels = 0, format = SampleFormat.None)),
        )
    }

    @Test
    fun theWrongKindOfFieldIsNeverCompared() {
        // A video stream has no sample rate to be wrong about. Reading one off the frame anyway
        // would report every video stream in existence.
        assertEquals(emptyList(), divergencesOf(videoStream(), videoFrame()))
        val videoWithAudioShapedFrame = divergencesOf(
            videoStream(),
            videoFrame().copy(sampleRate = 44_100, channelCount = 6, sampleFormat = SampleFormat.S16),
        )
        assertEquals(emptyList(), videoWithAudioShapedFrame)
    }
}
