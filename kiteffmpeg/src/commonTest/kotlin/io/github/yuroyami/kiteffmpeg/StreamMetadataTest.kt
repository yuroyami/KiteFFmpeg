package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamMetadataTest {
    @Test
    fun vp9MetadataUsesTypedKnownValuesAndNullForUnknownValues() {
        assertEquals(Vp9Profile.Profile0, Vp9Profile.fromNumber(0))
        assertEquals(Vp9Profile.Profile3, Vp9Profile.fromNumber(3))
        assertNull(Vp9Profile.fromNumber(-99))
        assertNull(Vp9Profile.fromNumber(4))

        assertEquals(Vp9Level.Level1, Vp9Level.fromCode(10))
        assertEquals(Vp9Level.Level6_2, Vp9Level.fromCode(62))
        assertNull(Vp9Level.fromCode(-99))
        assertNull(Vp9Level.fromCode(42))

        assertEquals(Vp9BitDepth.Eight, Vp9BitDepth.fromBits(8))
        assertEquals(Vp9BitDepth.Twelve, Vp9BitDepth.fromBits(12))
        assertNull(Vp9BitDepth.fromBits(9))

        assertEquals(Vp9ChromaSubsampling.Yuv420, Vp9ChromaSubsampling.fromCode(420))
        assertEquals(Vp9ChromaSubsampling.Yuv444, Vp9ChromaSubsampling.fromCode(444))
        assertNull(Vp9ChromaSubsampling.fromCode(440))
    }

    @Test
    fun colorDeclarationDistinguishesAbsentAndExplicitLimitedRange() {
        assertTrue(ColorInfo.Unspecified.isUnspecified)
        assertFalse(ColorInfo(transfer = ColorTransfer.SmpteSt2084).isUnspecified)
        assertFalse(ColorInfo(chromaLocation = ChromaLocation.Center).isUnspecified)

        val limited = ColorInfo(fullRange = false, rangeSpecified = true)
        assertFalse(limited.fullRange)
        assertTrue(limited.rangeSpecified)
        assertFalse(limited.isUnspecified)
    }
}

/**
 * P1-32. A stream description carrying an extradata record used to compare by array REFERENCE, so
 * two probes of the same file disagreed about whether they had described the same stream.
 */
class StreamInfoEqualityTest {

    private fun stream(extradata: ByteArray?) = StreamInfo(
        index = 0,
        type = MediaType.Video,
        codec = CodecId("h264"),
        timeBase = Rational(1, 1000),
        durationMicros = 1_000_000,
        bitrateBps = 1_000,
        codecExtradata = extradata,
    )

    @Test
    fun equalBytesInDifferentArraysAreEqualStreams() {
        val a = stream(byteArrayOf(1, 2, 3))
        val b = stream(byteArrayOf(1, 2, 3))
        assertEquals(a, b, "the same record in two arrays describes the same stream")
        assertEquals(a.hashCode(), b.hashCode(), "equal streams must hash alike or a map loses them")
        assertEquals(setOf(a), setOf(a, b), "a set must see one stream, not two")
    }

    @Test
    fun differentBytesAreDifferentStreams() {
        assertTrue(stream(byteArrayOf(1, 2, 3)) != stream(byteArrayOf(1, 2, 4)))
        assertTrue(stream(byteArrayOf(1, 2, 3)) != stream(null))
        assertTrue(stream(null) != stream(byteArrayOf()))
    }

    @Test
    fun streamsWithoutExtradataStillCompareOnEveryOtherField() {
        assertEquals(stream(null), stream(null))
        assertTrue(stream(null) != stream(null).copy(index = 1))
        assertTrue(stream(null) != stream(null).copy(rotationDegrees = 90))
    }
}

/** P1-25. The undeclared-colour guess, at the sizes where one rule cannot serve every file. */
class ColorGuessTest {

    @Test
    fun ntscAndPalStandardDefinitionDoNotSharePrimaries() {
        // 525-line content: SMPTE 170M primaries.
        assertEquals(ColorPrimaries.Smpte170m, ColorInfo.guessFor(480).primaries)
        assertEquals(ColorMatrix.Smpte170m, ColorInfo.guessFor(480).matrix)
        // 625-line content: BT.470BG primaries, same BT.601 matrix family.
        assertEquals(ColorPrimaries.Bt470bg, ColorInfo.guessFor(576).primaries)
        assertEquals(ColorMatrix.Bt470bg, ColorInfo.guessFor(576).matrix)
    }

    @Test
    fun highDefinitionAndAboveIsBt709() {
        assertEquals(ColorPrimaries.Bt709, ColorInfo.guessFor(720).primaries)
        assertEquals(ColorPrimaries.Bt709, ColorInfo.guessFor(1080).primaries)
        assertEquals(ColorPrimaries.Bt709, ColorInfo.guessFor(2160).primaries)
    }

    @Test
    fun aHeightThatDescribesNothingIsGuessedAsNothing() {
        // Zero and negative used to fall through to the high definition branch and claim BT.709
        // about a frame whose size was never known.
        assertTrue(ColorInfo.guessFor(0).isUnspecified, "a zero height is not high definition")
        assertTrue(ColorInfo.guessFor(-1080).isUnspecified, "a negative height is not high definition")
    }
}
