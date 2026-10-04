package io.github.yuroyami.kiteffmpeg

/**
 * How a stream's pictures map onto a sphere around the viewer, as its container states it: in MP4
 * or MOV through Google's spherical video boxes or Apple's video extension box, in Matroska
 * through the track's `Projection` element. FFmpeg reads it into the stream's
 * `AVSphericalMapping` and applies none of it, so a renderer that draws the picture flat shows a
 * 360 degree video as a stretched panorama.
 *
 * The picture is mapped onto the sphere by [projection], and the sphere is then turned around the
 * viewer, who stays at its centre: by [yaw], then [pitch], then [roll]. The axes are OpenGL's, x to
 * the viewer's right, y up and z out of the screen toward the viewer, so the turn is the rotation
 * `Ry(yaw) * Rx(pitch) * Rz(roll)`. Each angle is in degrees and is exactly the number FFmpeg
 * holds, which is 16.16 fixed point and fits a [Double] without rounding. FFmpeg documents yaw and
 * roll from -180 to 180 and pitch from -90 to 90, and passes on what the container says.
 */
public data class SphericalMapping(
    /** How the picture covers the sphere, with what that projection alone carries. */
    val projection: SphericalProjection,
    /** Degrees about the up axis. A positive yaw moves what is in front of the viewer to their right. */
    val yaw: Double,
    /** Degrees about the right axis. A positive pitch moves what is in front of the viewer up. */
    val pitch: Double,
    /** Degrees about the forward axis. A positive roll tilts what is in front of the viewer to their right. */
    val roll: Double,
)

/** How a picture covers the sphere, FFmpeg's `AVSphericalProjection`. */
public sealed class SphericalProjection {

    /**
     * The whole sphere: longitude across the width, from -180 degrees at the left edge to 180 at
     * the right, and latitude down the height, from 90 degrees at the top to -90 at the bottom.
     */
    public data object Equirectangular : SphericalProjection()

    /**
     * A part of an [Equirectangular] picture. Each bound is the share of the whole picture's width
     * or height that lies beyond this part's edge on that side, from 0 to 1, so the whole picture
     * is this one's width divided by `1 - left - right` and its height divided by
     * `1 - top - bottom`. The container stores each as 0.32 fixed point, and each is that number
     * over 2^32, exactly.
     */
    public data class EquirectangularTile(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) : SphericalProjection()

    /**
     * The six faces of a cube in a 3 by 2 layout: right, left and up in the top row, down, front
     * and back in the bottom one, the only layout FFmpeg reads. The front, left, right and back
     * faces stand upright, the top of the up face is toward the front and the top of the down face
     * is toward the back. [padding] is the number of pixels of padding at the edges of each face.
     */
    public data class Cubemap(val padding: Long) : SphericalProjection()

    /** Half the sphere, 180 degrees across, as an equirectangular picture. */
    public data object HalfEquirectangular : SphericalProjection()

    /** A flat picture, which Apple's video extension box can state outright. */
    public data object Rectilinear : SphericalProjection()

    /** Apple's fisheye projection. */
    public data object Fisheye : SphericalProjection()

    /** Apple's parametric immersive projection. */
    public data object ParametricImmersive : SphericalProjection()
}
