package io.github.yuroyami.kiteffmpeg

/**
 * The static HDR metadata a stream carries beside its colour tags. A tone mapper uses it to learn
 * how bright the content and the display it was graded on actually were, instead of assuming.
 */
public data class HdrMetadata(
    /** The display the content was graded on, or null when the stream did not say. */
    val masteringDisplay: MasteringDisplay? = null,
    /** The brightest pixel and brightest frame of the content, or null when the stream did not say. */
    val contentLight: ContentLightLevel? = null,
)

/**
 * The mastering display an HDR stream was graded on, as SMPTE ST 2086 describes it. Either half may
 * be missing, because a stream may declare one without the other.
 */
public data class MasteringDisplay(
    /** The display's primaries and white point, or null when the stream did not say. */
    val primaries: DisplayPrimaries?,
    /** The display's luminance range, or null when the stream did not say. */
    val luminance: LuminanceRange?,
)

/** Red, green and blue primaries and a white point, as CIE 1931 xy chromaticity coordinates. */
public data class DisplayPrimaries(
    val redX: Rational,
    val redY: Rational,
    val greenX: Rational,
    val greenY: Rational,
    val blueX: Rational,
    val blueY: Rational,
    val whiteX: Rational,
    val whiteY: Rational,
)

/** A luminance range in candelas per square metre. */
public data class LuminanceRange(val min: Rational, val max: Rational)

/**
 * Content light level, as CTA-861.3 describes it: [maxCll] is the brightest single pixel and
 * [maxFall] the brightest frame average, both in candelas per square metre.
 */
public data class ContentLightLevel(val maxCll: Int, val maxFall: Int)
