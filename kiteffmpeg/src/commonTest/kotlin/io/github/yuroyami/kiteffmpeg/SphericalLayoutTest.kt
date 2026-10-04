package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The reads of `ffkmp_codecpar_spherical`'s and `ffkmp_codecpar_stereo3d`'s layouts, written here
 * from the layouts their header comments state, with a distinct value in each slot so that a field
 * read from its neighbour's place, or an unsigned field read as signed, comes out wrong.
 */
class SphericalLayoutTest {

    private fun spherical(projection: Int, vararg rest: Int): IntArray =
        intArrayOf(projection, *rest).copyOf(SPHERICAL_INTS)

    @Test
    fun everyProjectionReadsAsItsOwnAndAnUnknownOneAsNone() {
        assertEquals(SphericalProjection.Equirectangular, sphericalMappingOf(spherical(0))?.projection)
        assertEquals(SphericalProjection.Cubemap(padding = 0), sphericalMappingOf(spherical(1))?.projection)
        assertEquals(SphericalProjection.EquirectangularTile(0.0, 0.0, 0.0, 0.0), sphericalMappingOf(spherical(2))?.projection)
        assertEquals(SphericalProjection.HalfEquirectangular, sphericalMappingOf(spherical(3))?.projection)
        assertEquals(SphericalProjection.Rectilinear, sphericalMappingOf(spherical(4))?.projection)
        assertEquals(SphericalProjection.Fisheye, sphericalMappingOf(spherical(5))?.projection)
        assertEquals(SphericalProjection.ParametricImmersive, sphericalMappingOf(spherical(6))?.projection)
        assertNull(sphericalMappingOf(spherical(7)))
        assertNull(sphericalMappingOf(spherical(-1)))
        assertNull(sphericalMappingOf(IntArray(SPHERICAL_INTS - 1)))
    }

    @Test
    fun theTurnIsSixteenSixteenFixedPointInYawPitchRollOrder() {
        val mapping = sphericalMappingOf(spherical(0, -819_200, 2_998_272, -11_157_504))
        assertEquals(SphericalMapping(SphericalProjection.Equirectangular, yaw = -12.5, pitch = 45.75, roll = -170.25), mapping)
        assertEquals(Int.MIN_VALUE / 65536.0, sphericalMappingOf(spherical(0, Int.MIN_VALUE))?.yaw)
    }

    @Test
    fun tileBoundsAreUnsignedSharesInLeftTopRightBottomOrder() {
        val tile = sphericalMappingOf(spherical(2, 0, 0, 0, 0x10000000, 0x40000000, Int.MIN_VALUE, -1))?.projection
        assertEquals(
            SphericalProjection.EquirectangularTile(left = 0.0625, top = 0.25, right = 0.5, bottom = 4294967295.0 / 4294967296.0),
            tile,
        )
    }

    @Test
    fun cubeMapPaddingIsUnsignedAndOnlyTheCubeMapReadsIt() {
        assertEquals(SphericalProjection.Cubemap(padding = 4294967294L), sphericalMappingOf(spherical(1, 0, 0, 0, 0, 0, 0, 0, -2))?.projection)
        assertEquals(SphericalProjection.Equirectangular, sphericalMappingOf(spherical(0, 0, 0, 0, 7, 7, 7, 7, 7))?.projection)
    }

    private fun stereo(
        type: Int = 1,
        inverted: Int = 0,
        view: Int = 0,
        eye: Int = 0,
        baseline: Int = 0,
        disparityNum: Int = 0,
        disparityDen: Int = 1,
        fieldNum: Int = 0,
        fieldDen: Int = 1,
    ): Stereo3D? = stereo3dOf(intArrayOf(type, inverted, view, eye, baseline, disparityNum, disparityDen, fieldNum, fieldDen))

    @Test
    fun everyPackingReadsAsItsOwnAndAnUnknownOneAsNone() {
        Stereo3DType.entries.forEachIndexed { code, type -> assertEquals(type, stereo(type = code)?.type) }
        assertEquals(9, Stereo3DType.entries.size)
        assertNull(stereo(type = 9))
        assertNull(stereo(type = -1))
    }

    @Test
    fun everyViewAndEyeReadsAsItsOwnAndAnUnknownOneAsNone() {
        Stereo3DView.entries.forEachIndexed { code, view -> assertEquals(view, stereo(view = code)?.view) }
        assertEquals(4, Stereo3DView.entries.size)
        assertNull(stereo(view = 4))
        assertNull(stereo(eye = 0)?.primaryEye)
        assertEquals(StereoEye.Left, stereo(eye = 1)?.primaryEye)
        assertEquals(StereoEye.Right, stereo(eye = 2)?.primaryEye)
        assertNull(stereo(eye = 3))
    }

    @Test
    fun eachStereoFieldReadsFromItsOwnSlot() {
        assertEquals(
            Stereo3D(
                type = Stereo3DType.Columns,
                inverted = true,
                view = Stereo3DView.Right,
                primaryEye = StereoEye.Left,
                baselineMicrometres = 64_000,
                horizontalDisparityAdjustment = Rational(-150, 10_000),
                horizontalFieldOfView = Rational(110_500, 1_000),
            ),
            stereo(7, 1, 2, 1, 64_000, -150, 10_000, 110_500, 1_000),
        )
    }

    @Test
    fun theBaselineIsUnsignedAndZeroIsUnstated() {
        assertEquals(4294967280L, stereo(baseline = -16)?.baselineMicrometres)
        assertNull(stereo(baseline = 0)?.baselineMicrometres)
    }

    @Test
    fun aFractionWithoutAPositiveDenominatorIsUnstated() {
        assertEquals(Rational.Zero, stereo(disparityNum = 5, disparityDen = 0)?.horizontalDisparityAdjustment)
        assertEquals(Rational.Zero, stereo(disparityNum = Int.MIN_VALUE, disparityDen = -1)?.horizontalDisparityAdjustment)
        assertNull(stereo(fieldNum = 5, fieldDen = 0)?.horizontalFieldOfView)
        assertNull(stereo(fieldNum = Int.MIN_VALUE, fieldDen = -1)?.horizontalFieldOfView)
        assertNull(stereo(fieldNum = 0, fieldDen = 1_000)?.horizontalFieldOfView)
        assertEquals(Rational.Zero, stereo(disparityNum = 0, disparityDen = 10_000)?.horizontalDisparityAdjustment)
    }

    @Test
    fun aShortLayoutIsNone() {
        assertNull(stereo3dOf(IntArray(STEREO3D_INTS - 1)))
    }
}
