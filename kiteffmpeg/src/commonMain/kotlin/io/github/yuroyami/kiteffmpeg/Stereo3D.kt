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
