package io.github.yuroyami.kiteffmpeg.dsl

import io.github.yuroyami.kiteffmpeg.SampleFormat
import io.github.yuroyami.kiteffmpeg.AudioEncoderSpec
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.PixelFormat
import io.github.yuroyami.kiteffmpeg.Rational
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * KD-8: every KD compilation golden in one host suite. These pin EXACT strings,
 * including escaping and ordering, because law 5 makes compilation a pure function and law 4
 * makes its output the thing a bug report carries.
 */
class KdGoldensTest {

    // --- KD-1, the filter DSL -------------------------------------------------------------

    @Test
    fun videoChainCompilesEveryTypedStepExactly() {
        val chain = videoFilters {
            scale(1280, 720)
            crop(640, 360, 10, 20)
            pad(1920, 1080, color = "black")
            transpose(QuarterTurn.Clockwise)
            fps(Rational(30, 1))
            format(PixelFormat("yuv420p"))
            eq(brightness = 0.1)
            deinterlace(Deinterlacer.Yadif)
            drawBox(0, 0, 100, 50)
        }
        assertEquals(
            "scale=1280:720," +
                "crop=640:360:10:20," +
                "pad=1920:1080:(ow-iw)/2:(oh-ih)/2:black," +
                "transpose=clock," +
                "fps=30/1," +
                "format=yuv420p," +
                "eq=brightness=0.1," +
                "yadif," +
                "drawbox=0:0:100:50:red:3",
            chain.compile(),
        )
    }

    @Test
    fun audioChainCompilesEveryTypedStepExactly() {
        val chain = audioFilters {
            volume(0.5)
            atempo(1.5)
            aresample(48_000)
            pan("stereo", "c0=FL+0.7*FC", "c1=FR+0.7*FC")
            aformat(sampleFormat = SampleFormat.FltP, sampleRate = 48_000)
            loudnorm()
        }
        assertEquals(
            "volume=0.5," +
                "atempo=1.5," +
                "aresample=48000," +
                "pan='stereo|c0=FL+0.7*FC|c1=FR+0.7*FC'," +
                "aformat=sample_fmts=fltp:sample_rates=48000," +
                "loudnorm=I=-24.0:TP=-2.0:LRA=7.0",
            chain.compile(),
        )
    }

    @Test
    fun escapingQuotesExactlyTheStructuralAlphabet() {
        assertEquals("plain", escapeFilterValue("plain"))
        assertEquals("0x11FF22", escapeFilterValue("0x11FF22"))
        assertEquals("'red:blue'", escapeFilterValue("red:blue"))
        assertEquals("'a,b'", escapeFilterValue("a,b"))
        assertEquals("'a\\'b'", escapeFilterValue("a'b"))
        assertEquals("'a\\\\b'", escapeFilterValue("a\\b"))
        assertEquals("'two words'", escapeFilterValue("two words"))
    }

    @Test
    fun aSampleFormatCannotAppendAnExtraFilter() {
        // `sample_fmts=$it` was interpolated raw, one line above a neighbour that escaped,
        // so a value carrying a comma closed the aformat step and opened a filter of its own.
        assertEquals(
            "aformat=sample_fmts='fltp,volume=0'",
            AudioFormat(sampleFormat = SampleFormat("fltp,volume=0")).compile(),
        )
        assertEquals(
            "aformat=sample_fmts='a:b'",
            AudioFormat(sampleFormat = SampleFormat("a:b")).compile(),
        )
        // The ordinary value is unchanged, which is why the golden above did not have to move:
        // escapeFilterValue quotes only values that carry a structural character.
        assertEquals("aformat=sample_fmts=fltp", AudioFormat(sampleFormat = SampleFormat.FltP).compile())
    }

    @Test
    fun rawPassesVerbatimAndBlankRefuses() {
        assertEquals("frei0r=glow:0.5", Raw("frei0r=glow:0.5").compile())
        assertFailsWith<IllegalArgumentException> { Raw("  ") }
    }

    @Test
    fun degenerateStepsRefuseTyped() {
        assertFailsWith<IllegalArgumentException> { Scale(0, 720) }
        assertFailsWith<IllegalArgumentException> { Atempo(0.4) }
        assertFailsWith<IllegalArgumentException> { Eq().compile() }
        assertFailsWith<IllegalArgumentException> { AudioFormat().compile() }
        assertFailsWith<IllegalArgumentException> { Pan("stereo", emptyList()) }
        assertFailsWith<IllegalArgumentException> { FilterChain(emptyList()) }
    }

    // --- KD-2, decoder options ------------------------------------------------------------

    @Test
    fun decoderOptionsCompileInStableOrderTypedFirst() {
        val options = DecoderOptions(
            skipLoopFilter = DecoderSkip.All,
            skipFrame = DecoderSkip.NonKey,
            errorDetection = setOf(ErrorDetection.Explode, ErrorDetection.CrcCheck),
            threadType = DecoderThreadType.Frame,
            options = mapOf("lowres" to "1"),
        )
        assertEquals(
            listOf(
                "skip_loop_filter" to "all",
                "skip_frame" to "nokey",
                "err_detect" to "crccheck+explode",
                "thread_type" to "frame",
                "lowres" to "1",
            ),
            options.compile(),
        )
    }

    @Test
    fun theScrubbingPresetIsExactlyTheSkipPair() {
        assertEquals(
            listOf("skip_loop_filter" to "all", "skip_frame" to "nokey"),
            DecoderOptions.Scrubbing.compile(),
        )
    }

    @Test
    fun emptyDecoderOptionsCompileToNothing() {
        assertEquals(emptyList(), DecoderOptions().compile())
    }

    // --- KD-3, encoder tuning -------------------------------------------------------------

    private fun videoSpec(
        options: Map<String, String> = emptyMap(),
        codec: CodecId = CodecId.Libx264,
    ) = VideoEncoderSpec(
        codec = codec,
        width = 1280,
        height = 720,
        frameRate = Rational(30, 1),
        options = options,
    )

    @Test
    fun constantQualityLandsAsCrfAndZeroesTheBitrate() {
        val tuned = VideoEncoderTuning(
            preset = EncoderPreset.VeryFast,
            rateControl = RateControl.ConstantQuality(23),
        ).applyTo(videoSpec())
        assertEquals("23", tuned.options["crf"])
        assertEquals("veryfast", tuned.options["preset"])
        assertEquals(0L, tuned.bitrateBps)
    }

    @Test
    fun constantBitrateShapesTheCappedPipeAndAsksX264ToHoldIt() {
        val tuned = VideoEncoderTuning(
            rateControl = RateControl.ConstantBitrate(4_000_000),
        ).applyTo(videoSpec())
        assertEquals("4000000", tuned.options["maxrate"])
        assertEquals("4000000", tuned.options["minrate"])
        assertEquals("2000000", tuned.options["bufsize"])
        assertEquals(4_000_000L, tuned.bitrateBps)
        // The triple alone is a capped pipe: the ceiling holds and an undershoot is not filled.
        // nal-hrd is what makes it conformant CBR, and x264 is the one encoder here that has it.
        assertEquals("cbr", tuned.options["nal-hrd"])
    }

    @Test
    fun constantBitrateOnAnEncoderWithoutHrdIsTheCappedPipeAlone() {
        val tuned = VideoEncoderTuning(
            rateControl = RateControl.ConstantBitrate(4_000_000),
        ).applyTo(videoSpec(codec = CodecId.H264VideoToolbox))
        // maxrate/minrate/bufsize are AVCodecContext fields, so the shape is legal everywhere.
        assertEquals("4000000", tuned.options["maxrate"])
        assertNull(tuned.options["nal-hrd"])
    }

    @Test
    fun anX264KnobIsRefusedForAnEncoderThatDoesNotHaveIt() {
        // The encoders this project actually SHIPS are mpeg4, mjpeg, png and the platform
        // hardware ones. Not one of them has a preset, a tune or a crf, so this tuning used to
        // compile into options that were dropped at open and changed nothing at all.
        for (codec in listOf(CodecId.H264VideoToolbox, CodecId.H264MediaCodec, CodecId.Mjpeg)) {
            val spec = videoSpec(codec = codec)
            assertFailsWith<IllegalArgumentException> {
                VideoEncoderTuning(preset = EncoderPreset.Slow).applyTo(spec)
            }
            assertFailsWith<IllegalArgumentException> {
                VideoEncoderTuning(tune = "film").applyTo(spec)
            }
            assertFailsWith<IllegalArgumentException> {
                VideoEncoderTuning(rateControl = RateControl.ConstantQuality(23)).applyTo(spec)
            }
        }
    }

    @Test
    fun profileIsGenericAndPassesForAnyEncoder() {
        // The one knob here that is an AVCodecContext field rather than an x264 option.
        val tuned = VideoEncoderTuning(profile = "main")
            .applyTo(videoSpec(codec = CodecId.H264VideoToolbox))
        assertEquals("main", tuned.options["profile"])
    }

    @Test
    fun crfIsAcceptedByTheWiderFamilyThatHasIt() {
        // libvpx-vp9 and libaom-av1 have crf and no preset, so the two sets are not the same set.
        val vp9 = VideoEncoderTuning(rateControl = RateControl.ConstantQuality(31))
            .applyTo(videoSpec(codec = CodecId("libvpx-vp9")))
        assertEquals("31", vp9.options["crf"])
        assertFailsWith<IllegalArgumentException> {
            VideoEncoderTuning(preset = EncoderPreset.Slow).applyTo(videoSpec(codec = CodecId("libvpx-vp9")))
        }
    }

    @Test
    fun degenerateTuningValuesRefuse() {
        assertFailsWith<IllegalArgumentException> { VideoEncoderTuning(profile = " ") }
        assertFailsWith<IllegalArgumentException> { VideoEncoderTuning(tune = "") }
        assertFailsWith<IllegalArgumentException> { RateControl.AverageBitrate(0) }
        assertFailsWith<IllegalArgumentException> { RateControl.ConstantBitrate(-1) }
        assertFailsWith<IllegalArgumentException> { AudioEncoderTuning(bitrateBps = 0) }
        assertFailsWith<IllegalArgumentException> { AudioEncoderTuning(profile = "  ") }
    }

    @Test
    fun aTypedKnobCollidingWithTheEscapeHatchRefuses() {
        assertFailsWith<IllegalArgumentException> {
            VideoEncoderTuning(rateControl = RateControl.ConstantQuality(20))
                .applyTo(videoSpec(options = mapOf("crf" to "18")))
        }
        assertFailsWith<IllegalArgumentException> {
            AudioEncoderTuning(profile = "aac_low")
                .applyTo(AudioEncoderSpec(codec = CodecId.Aac, options = mapOf("profile" to "aac_he")))
        }
    }

    @Test
    fun audioTuningMergesProfileAndBitrate() {
        val tuned = AudioEncoderTuning(profile = "aac_low", bitrateBps = 192_000)
            .applyTo(AudioEncoderSpec(codec = CodecId.Aac))
        assertEquals("aac_low", tuned.options["profile"])
        assertEquals(192_000L, tuned.bitrateBps)
    }
}
