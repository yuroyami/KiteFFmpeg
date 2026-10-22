package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** What an encode copies from its source when the spec leaves a field open, and what it refuses. */
class EncodeIdentityTest {

    private val spec = VideoEncoderSpec(
        codec = CodecId("mpeg4"),
        width = 160,
        height = 120,
        frameRate = Rational(25, 1),
    )

    private val hdr = HdrMetadata(contentLight = ContentLightLevel(1000, 400))

    private val pq = ColorInfo(
        matrix = ColorMatrix.Bt2020Ncl,
        primaries = ColorPrimaries.Bt2020,
        transfer = ColorTransfer.SmpteSt2084,
        rangeSpecified = true,
        matrixSpecified = true,
        primariesSpecified = true,
        transferSpecified = true,
    )

    private fun stream(video: VideoStreamInfo) = StreamInfo(
        index = 0,
        type = MediaType.Video,
        codec = CodecId("hevc"),
        timeBase = Rational(1, 1000),
        durationMicros = null,
        bitrateBps = null,
        video = video,
    )

    private fun frame(color: ColorInfo, sar: Rational = Rational(1, 1), hdr: HdrMetadata? = null) = FrameInfo(
        streamIndex = 0,
        type = MediaType.Video,
        pts = 0,
        timeBase = Rational(1, 1000),
        width = 160,
        height = 120,
        color = color,
        sampleAspectRatio = sar,
        hdr = hdr,
    )

    private val sdrStream = stream(VideoStreamInfo(160, 120, PixelFormat.Yuv420p, Rational(25, 1), Rational(1, 1)))

    @Test
    fun aDeclaredColourAndShapeAndHdrAreCopiedFromTheFirstFrame() {
        val filled = spec.inheriting(frame(pq, Rational(4, 3), hdr), sdrStream)
        assertEquals(pq, filled.color)
        assertEquals(Rational(4, 3), filled.sampleAspectRatio)
        assertEquals(hdr, filled.hdr)
    }

    @Test
    fun aGuessedColourAndASquarePixelAreNotCopied() {
        // What resolveDeclaredColor makes of a 120-line frame that declares nothing: all guesses.
        val guessed = resolveDeclaredColor(ColorInfo.Unspecified, 120)
        val filled = spec.inheriting(frame(guessed), sdrStream)
        assertNull(filled.color)
        assertNull(filled.sampleAspectRatio)
    }

    @Test
    fun onlyTheDeclaredFieldsOfAColourAreCopied() {
        val half = resolveDeclaredColor(ColorInfo(transfer = ColorTransfer.SmpteSt2084), 1080)
        val filled = spec.inheriting(frame(half), sdrStream)
        assertEquals(ColorTransfer.SmpteSt2084, filled.color?.transfer)
        assertEquals(ColorPrimaries.Unspecified, filled.color?.primaries, "BT.709 was a guess for 1080 lines")
    }

    @Test
    fun aFrameWithoutHdrMetadataIsNotGivenTheContainersBack() {
        val hdrStream = stream(VideoStreamInfo(160, 120, PixelFormat.Yuv420p, Rational(25, 1), Rational(1, 1), hdr = hdr))
        assertNull(spec.inheriting(frame(pq), hdrStream).hdr, "a tone mapper dropped it on purpose")
        assertEquals(hdr, spec.inheriting(null, hdrStream).hdr, "with no frame, the container is all there is")
    }

    @Test
    fun whatTheCallerSetOrSaidInAnOptionIsLeftAlone() {
        val bare = spec.copy(color = ColorInfo.Unspecified, sampleAspectRatio = Rational(1, 1), hdr = HdrMetadata())
        assertEquals(bare, bare.inheriting(frame(pq, Rational(4, 3), hdr), sdrStream))
        assertEquals(false, bare.inheritsAnything)
        val byOption = spec.copy(options = mapOf("color_trc" to "smpte2084", "aspect" to "4/3"))
        val filled = byOption.inheriting(frame(pq, Rational(4, 3), hdr), sdrStream)
        assertNull(filled.color)
        assertNull(filled.sampleAspectRatio)
    }

    @Test
    fun anEncoderIsGivenFfmpegsOwnColourValues() {
        assertEquals(listOf(9, 16, 9, 1, 0), pq.encoderValues().toList())
        assertEquals(listOf(2, 2, 2, 0, 0), ColorInfo.Unspecified.encoderValues().toList())
        assertEquals(2, ColorInfo(fullRange = true).encoderValues()[3])
    }

    @Test
    fun theSourceLayoutIsCopiedOnlyWhenTheChannelCountAgrees() {
        val audio = AudioEncoderSpec(codec = CodecId.Aac, channels = 6)
        fun source(mask: Long?) = StreamInfo(
            index = 1,
            type = MediaType.Audio,
            codec = CodecId("ac3"),
            timeBase = Rational(1, 48000),
            durationMicros = null,
            bitrateBps = null,
            audio = AudioStreamInfo(48000, mask?.countOneBits() ?: 6, SampleFormat.FltP, mask),
        )
        assertEquals(0x60FL, audio.inheriting(source(0x60F)).channelLayoutMask)
        assertNull(audio.copy(channels = 2).inheriting(source(0x60F)).channelLayoutMask)
        assertSame(audio, audio.inheriting(source(null)))
        val byOption = audio.copy(options = mapOf("ch_layout" to "5.1(side)"))
        assertSame(byOption, byOption.inheriting(source(0x3F)))
    }

    @Test
    fun aMaskThatNamesAnotherChannelCountIsRefused() {
        val failure = assertFailsWith<FFmpegException> {
            requireLayoutMatchesChannels(AudioEncoderSpec(codec = CodecId.Aac, channels = 2, channelLayoutMask = 0x60F))
        }
        assertIs<FFmpegError.InvalidArgument>(failure.error)
    }

    @Test
    fun theColourAndShapeOptionsCollideOnlyOnceTheirFieldsAreSet() {
        val options = mapOf("color_primaries" to "bt2020", "aspect" to "4/3")
        requireNoTypedVideoOptionCollision(spec.copy(options = options))
        assertFailsWith<FFmpegException> { requireNoTypedVideoOptionCollision(spec.copy(options = options, color = pq)) }
        assertFailsWith<FFmpegException> {
            requireNoTypedVideoOptionCollision(spec.copy(options = options, sampleAspectRatio = Rational(4, 3)))
        }
        val audio = AudioEncoderSpec(codec = CodecId.Aac, channels = 6, options = mapOf("ch_layout" to "5.1(side)"))
        requireNoTypedAudioOptionCollision(audio)
        assertFailsWith<FFmpegException> { requireNoTypedAudioOptionCollision(audio.copy(channelLayoutMask = 0x60F)) }
    }

    @Test
    fun aMasteringDisplayCrossesAsIntsAndBack() {
        val display = MasteringDisplay(
            primaries = DisplayPrimaries(
                Rational(17, 25), Rational(8, 25), Rational(53, 200), Rational(69, 100),
                Rational(3, 20), Rational(3, 50), Rational(3127, 10000), Rational(329, 1000),
            ),
            luminance = null,
        )
        val (values, flags) = display.toInts()
        assertEquals(HDR_HAS_PRIMARIES, flags)
        assertEquals(display, masteringDisplayOf(values, 0, flags))
        assertNull(masteringDisplayOf(values, 0, 0), "flags that name neither half describe nothing")
        assertNull(hdrFromInts(null))
    }
}
