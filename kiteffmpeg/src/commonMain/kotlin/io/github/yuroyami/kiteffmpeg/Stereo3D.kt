package io.github.yuroyami.kiteffmpeg

/**
 * How a stream's pictures hold the views of two eyes, as its container states it: in MP4 or MOV
 * through the stereoscopic video box or Apple's video extension box, in Matroska through the
 * track's `StereoMode`. FFmpeg reads it into the stream's `AVStereo3D` and splits nothing, so a
 * renderer that draws the picture whole shows both views side by side or one above the other.
 */
public data class Stereo3D(
    /** How the two views are packed into each picture. */
    val type: Stereo3DType,
    /** True when the right or bottom view is the left eye's, the reverse of what [type] draws. */
    val inverted: Boolean,
    /** Which views the pictures hold. */
    val view: Stereo3DView,
    /** The eye whose view to show when drawing in 2D, or null when the container names neither. */
    val primaryEye: StereoEye?,
    /** The distance between the centres of the camera's two lenses in micrometres, or null when unstated. */
    val baselineMicrometres: Long?,
    /**
     * How far to shift the two views against each other, which moves the plane that appears at
     * the screen's depth, from -1 to 1. Zero when the container states none.
     */
    val horizontalDisparityAdjustment: Rational,
    /** The horizontal field of view in degrees, or null when unstated. */
    val horizontalFieldOfView: Rational?,
)

/** How two views are packed into each picture, FFmpeg's `AVStereo3DType`. */
public enum class Stereo3DType {
    /** One view, as the container states outright. */
    Monoscopic,

    /** The left eye's view in the left half and the right eye's in the right half. */
    SideBySide,

    /** The left eye's view in the top half and the right eye's in the bottom half. */
    TopBottom,

    /** Whole pictures for each eye in turn. */
    FrameSequence,

    /** The two views in alternate pixels, as a checkerboard. */
    Checkerboard,

    /** Side by side, to be scaled up in a checkerboard pattern. */
    SideBySideQuincunx,

    /** The two views in alternate rows. */
    Lines,

    /** The two views in alternate columns. */
    Columns,

    /** Two views, packed in a way the container does not say. */
    Unspecified,
}

/** Which views a stream's pictures hold, FFmpeg's `AVStereo3DView`. */
public enum class Stereo3DView {
    /** Both, packed as [Stereo3D.type] says. */
    Packed,

    /** Only the left eye's. */
    Left,

    /** Only the right eye's. */
    Right,

    /** The container does not say. */
    Unspecified,
}

/** One eye. */
public enum class StereoEye {
    Left,
    Right,
}

/** How many ints `ffkmp_codecpar_stereo3d` writes. */
internal const val STEREO3D_INTS: Int = 9

/**
 * The layout the C helper wrote, every backend's one reading of it: FFmpeg's packing, 1 when the
 * views are inverted, the view, the primary eye, the bits of the unsigned baseline, and the
 * numerator and denominator of the disparity adjustment and of the field of view. Null for a value
 * it does not know. FFmpeg's one reader of the two fractions gives each a fixed positive
 * denominator, so one that is not positive reads as unstated rather than as a [Rational] that may
 * not exist.
 */
internal fun stereo3dOf(ints: IntArray): Stereo3D? {
    if (ints.size < STEREO3D_INTS) return null
    val type = when (ints[0]) {
        0 -> Stereo3DType.Monoscopic
        1 -> Stereo3DType.SideBySide
        2 -> Stereo3DType.TopBottom
        3 -> Stereo3DType.FrameSequence
        4 -> Stereo3DType.Checkerboard
        5 -> Stereo3DType.SideBySideQuincunx
        6 -> Stereo3DType.Lines
        7 -> Stereo3DType.Columns
        8 -> Stereo3DType.Unspecified
        else -> return null
    }
    val view = when (ints[2]) {
        0 -> Stereo3DView.Packed
        1 -> Stereo3DView.Left
        2 -> Stereo3DView.Right
        3 -> Stereo3DView.Unspecified
        else -> return null
    }
    val primaryEye = when (ints[3]) {
        0 -> null
        1 -> StereoEye.Left
        2 -> StereoEye.Right
        else -> return null
    }
    val baseline = ints[4].toLong() and 0xFFFFFFFFL
    return Stereo3D(
        type = type,
        inverted = ints[1] != 0,
        view = view,
        primaryEye = primaryEye,
        baselineMicrometres = baseline.takeIf { it != 0L },
        horizontalDisparityAdjustment = if (ints[6] > 0) Rational(ints[5], ints[6]) else Rational.Zero,
        horizontalFieldOfView = if (ints[7] != 0 && ints[8] > 0) Rational(ints[7], ints[8]) else null,
    )
}
