package io.github.yuroyami.kiteffmpeg

/**
 * The colour metadata a renderer must honour to draw a frame correctly.
 *
 * None of this is subtle once it is wrong, and all of it is already known at decode time:
 *
 * | Field | What a wrong value looks like |
 * |---|---|
 * | [matrix] | Hues shift. Faces go green or magenta, worst on saturated reds. |
 * | [fullRange] | Blacks turn grey and whites clip, or contrast is crushed. |
 * | [chromaLocation] | Colour bleeds half a pixel at sharp coloured edges. |
 * | [transfer] | High dynamic range content looks washed out or far too dark. |
 *
 * The values map one to one onto FFmpeg's own enumerations, so nothing is lost or guessed at the
 * boundary. [Unspecified] is a real and common answer: many containers declare nothing, and
 * [guessFor] gives the conventional interpretation.
 */
public data class ColorInfo(
    val matrix: ColorMatrix = ColorMatrix.Unspecified,
    val primaries: ColorPrimaries = ColorPrimaries.Unspecified,
    val transfer: ColorTransfer = ColorTransfer.Unspecified,
    /** True for 0 to 255. False for the 16 to 235 studio range, or when no range was declared. */
    val fullRange: Boolean = false,
    val chromaLocation: ChromaLocation = ChromaLocation.Unspecified,
    /** Distinguishes an explicitly declared studio range from an absent range declaration. */
    val rangeSpecified: Boolean = false,
) {
    /** True when the transfer function means high dynamic range. */
    public val isHdr: Boolean
        get() = transfer == ColorTransfer.SmpteSt2084 || transfer == ColorTransfer.AribStdB67

    /** True when nothing usable was declared, so [guessFor] should be applied. */
    public val isUnspecified: Boolean
        get() = matrix == ColorMatrix.Unspecified &&
            primaries == ColorPrimaries.Unspecified &&
            transfer == ColorTransfer.Unspecified &&
            chromaLocation == ChromaLocation.Unspecified &&
            !rangeSpecified

    public companion object {
        public val Unspecified: ColorInfo = ColorInfo()

        /**
         * The conventional reading when a container declares nothing, which is common.
         *
         * Standard definition is BT.601 and high definition is BT.709, and every player applies
         * that rule because using one default for both visibly wrongs half the world's video. Two
         * refinements over a single 576-line split (audit P1-25):
         *
         * - the two standard definition families do not share primaries. 525-line content (NTSC,
         *   480 lines and below) is SMPTE 170M; 625-line content (PAL and SECAM, up to 576 lines)
         *   is BT.470BG. Both use the same BT.601 matrix coefficients, which is why the matrix is
         *   the same for the pair and only the primaries move.
         * - a height that is zero or negative describes nothing, so it is answered with
         *   [Unspecified] rather than being swept into the high definition branch. A guess made
         *   from a size that does not exist is not a guess, it is noise.
         *
         * This stays a heuristic over one input. A caller that knows the frame rate, the container
         * or the origin of the media can do better, and should: nothing here overrides a real
         * declaration, it only fills a silence.
         */
        public fun guessFor(height: Int): ColorInfo = when {
            height <= 0 -> Unspecified
            height <= 480 -> ColorInfo(
                ColorMatrix.Smpte170m,
                ColorPrimaries.Smpte170m,
                ColorTransfer.Bt709,
            )
            height <= 576 -> ColorInfo(
                ColorMatrix.Bt470bg,
                ColorPrimaries.Bt470bg,
                ColorTransfer.Bt709,
            )
            else -> ColorInfo(ColorMatrix.Bt709, ColorPrimaries.Bt709, ColorTransfer.Bt709)
        }
    }
}

/** The YCbCr to RGB matrix. Values match FFmpeg's `AVColorSpace`. */
public enum class ColorMatrix(public val avValue: Int) {
    Rgb(0),
    Bt709(1),
    Unspecified(2),
    Fcc(4),
    /** BT.601 625 line, and what standard definition PAL content uses. */
    Bt470bg(5),
    /** BT.601 525 line, and what standard definition NTSC content uses. */
    Smpte170m(6),
    Smpte240m(7),
    YCgCo(8),
    /** BT.2020 non-constant luminance, which is what real BT.2020 content uses. */
    Bt2020Ncl(9),
    Bt2020Cl(10),
    ICtCp(14),
    ;

    public companion object {
        public fun fromAv(value: Int): ColorMatrix = entries.firstOrNull { it.avValue == value } ?: Unspecified
    }
}

/** The colour primaries. Values match FFmpeg's `AVColorPrimaries`. */
public enum class ColorPrimaries(public val avValue: Int) {
    Bt709(1),
    Unspecified(2),
    Bt470m(4),
    Bt470bg(5),
    Smpte170m(6),
    Smpte240m(7),
    Film(8),
    Bt2020(9),
    SmpteSt428(10),
    SmpteSt431(11),
    SmpteSt432(12),
    ;

    public companion object {
        public fun fromAv(value: Int): ColorPrimaries = entries.firstOrNull { it.avValue == value } ?: Unspecified
    }
}

/** The transfer function, meaning the gamma curve. Values match FFmpeg's `AVColorTransferCharacteristic`. */
public enum class ColorTransfer(public val avValue: Int) {
    Bt709(1),
    Unspecified(2),
    Gamma22(4),
    Gamma28(5),
    Smpte170m(6),
    Smpte240m(7),
    Linear(8),
    Log(9),
    LogSqrt(10),
    Iec6196624(11),
    Bt1361Ecg(12),
    Iec6196621(13),
    Bt2020Ten(14),
    Bt2020Twelve(15),
    /** Perceptual quantiser, which is HDR10 and Dolby Vision. */
    SmpteSt2084(16),
    SmpteSt428(17),
    /** Hybrid log gamma, which is broadcast HDR. */
    AribStdB67(18),
    ;

    public companion object {
        public fun fromAv(value: Int): ColorTransfer = entries.firstOrNull { it.avValue == value } ?: Unspecified
    }
}

/** Where a chroma sample sits relative to its luma samples. Values match FFmpeg's `AVChromaLocation`. */
public enum class ChromaLocation(public val avValue: Int) {
    Unspecified(0),
    /** MPEG-2 and H.264 default. */
    Left(1),
    /** JPEG and MPEG-1 default. */
    Center(2),
    TopLeft(3),
    Top(4),
    BottomLeft(5),
    Bottom(6),
    ;

    public companion object {
        public fun fromAv(value: Int): ChromaLocation = entries.firstOrNull { it.avValue == value } ?: Unspecified
    }
}

/**
 * What a container says a stream is for.
 *
 * A track menu needs this. Without [forced] a player cannot mark or auto-select forced subtitles,
 * which is the difference between showing a translation of one foreign line and showing every
 * subtitle in the film. Without [attachedPicture] album art is treated as a one frame video stream
 * and the player tries to synchronise to it.
 */
public data class Disposition(
    val default: Boolean = false,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
    val visualImpaired: Boolean = false,
    /** Cover art, not video. Exactly one frame, and never the synchronisation master. */
    val attachedPicture: Boolean = false,
) {
    public companion object {
        public val None: Disposition = Disposition()
    }
}
