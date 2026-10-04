package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A stream reports the spherical mapping and the stereo layout its container states (#139):
 * Matroska's `Projection` and `StereoMode`, Google's spherical video and stereoscopic boxes in MP4,
 * and Apple's video extension box in MOV, and a stream with none of them reports neither.
 */
class SphericalContractTest {

    private fun videoOf(bytes: ByteArray, sha256: String): VideoStreamInfo =
        MediaSource.open(materializeContractMedia(bytes, sha256)).use { source ->
            source.streams.first { it.type == MediaType.Video }.video!!
        }

    private fun packed(type: Stereo3DType, inverted: Boolean): Stereo3D = Stereo3D(
        type = type,
        inverted = inverted,
        view = Stereo3DView.Packed,
        primaryEye = null,
        baselineMicrometres = null,
        horizontalDisparityAdjustment = Rational.Zero,
        horizontalFieldOfView = null,
    )

    @Test
    fun aMatroskaProjectionReadsWithItsTurnAndStereoModeTwoAsTopBottomInverted() {
        val video = videoOf(EquirectangularMatroska.bytes, EquirectangularMatroska.sha256)
        assertEquals(SphericalMapping(SphericalProjection.Equirectangular, yaw = 90.0, pitch = -30.0, roll = 15.0), video.spherical)
        assertEquals(packed(Stereo3DType.TopBottom, inverted = true), video.stereo3d)
    }

    @Test
    fun aMatroskaTileReadsItsFourBounds() {
        val video = videoOf(TileMatroska.bytes, TileMatroska.sha256)
        assertEquals(
            SphericalMapping(
                SphericalProjection.EquirectangularTile(left = 0.0625, top = 0.25, right = 0.03125, bottom = 0.125),
                yaw = 33.25,
                pitch = 7.25,
                roll = 0.0,
            ),
            video.spherical,
        )
        assertNull(video.stereo3d)
    }

    @Test
    fun aMatroskaCubeMapReadsItsPaddingAndStereoModeOneAsSideBySide() {
        val video = videoOf(CubemapMatroska.bytes, CubemapMatroska.sha256)
        assertEquals(SphericalMapping(SphericalProjection.Cubemap(padding = 2), yaw = 0.0, pitch = 0.0, roll = 0.0), video.spherical)
        assertEquals(packed(Stereo3DType.SideBySide, inverted = false), video.stereo3d)
    }

    @Test
    fun googleBoxesReadNegativeAndFractionalAnglesExactly() {
        val video = videoOf(GoogleBoxesMp4.bytes, GoogleBoxesMp4.sha256)
        assertEquals(SphericalMapping(SphericalProjection.Equirectangular, yaw = -12.5, pitch = 45.75, roll = -170.25), video.spherical)
        assertEquals(packed(Stereo3DType.TopBottom, inverted = false), video.stereo3d)
    }

    @Test
    fun applesBoxReadsHalfASphereAndEveryStereoField() {
        val video = videoOf(AppleBoxMov.bytes, AppleBoxMov.sha256)
        assertEquals(SphericalMapping(SphericalProjection.HalfEquirectangular, yaw = 0.0, pitch = 0.0, roll = 0.0), video.spherical)
        assertEquals(
            Stereo3D(
                type = Stereo3DType.Unspecified,
                inverted = true,
                view = Stereo3DView.Packed,
                primaryEye = StereoEye.Right,
                baselineMicrometres = 64_000,
                horizontalDisparityAdjustment = Rational(-150, 10_000),
                horizontalFieldOfView = Rational(110_500, 1_000),
            ),
            video.stereo3d,
        )
    }

    @Test
    fun aStreamWithNeitherReportsNeither() {
        val video = videoOf(ContractMedia.bytes, ContractMedia.sha256)
        assertNull(video.spherical)
        assertNull(video.stereo3d)
    }
}

/**
 * A 16x16 H.264 picture in Matroska whose track says an equirectangular projection turned by yaw
 * 90, pitch -30 and roll 15, and `StereoMode` 2, top and bottom with the right eye on top, set with
 * `mkvpropedit`.
 */
private object EquirectangularMatroska {
    const val sha256: String = "dbdf2bc90966e13df0d24acbcf51e7bb636aae2892776e13144808eecbf9bae8"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 712) { "EquirectangularMatroska fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "EquirectangularMatroska fixture digest changed" }
        }
    }

    private val DATA: String = """
GkXfo6NChoEBQveBAULygQRC84EIQoKIbWF0cm9za2FCh4EEQoWBAhhTgGcBAAAAAAAClBFNm3TBv4TTzLhYTbuLU6uEFUmpZlOs
gaFNu4xTq4QSVMNnU6yCAVhNu4xTq4QcU7trU6yCAdNNu4xTq4QWVK5rU6yCAe/sAQAAAAAAAFIAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFUmpZqa/hEdLJvQq
17GDD0JATYCETGF2ZldBhExhdmZEiYhARAAAAAAAAOwBAAAAAAAAgwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAElTDZ9e/hJlHJW9zc85jwItjxYgAAAAAAAAAAWfImUWjh0VOQ09ERVJEh4xMYXZjIGxpYngy
NjRnyKFFo4hEVVJBVElPTkSHkzAwOjAwOjAwLjA0MDAwMDAwMAAfQ7Z1mr+ESvLzGeeBAKOPgQAAgAAAAAdliIQ6JigOHFO7a5e/
hD5ygKq7j7OBALeK94EB8YIBtPCBCRZUrmtAn7+E9MuYuK4BAAAAAAAAkNeBAXPFgQGcgQAitZyDdW5kiIEAho9WX01QRUc0L0lT
Ty9BVkODgQEj44OEAmJaAOCpsIEQuoEQmoECdnCZdnGBAXZzhEK0AAB2dITB8AAAdnWEQXAAAFO4gQJV7oEA7AEAAAAAAAACAABj
oqMBQsAK/+EAFGdCwArd7ARAAAADAEAAAAyDxIngAQAEaM4PyA==
"""
}

/**
 * The same picture as a tile of an equirectangular picture whose bounds are a sixteenth at the
 * left, a quarter at the top, a thirty-second at the right and an eighth at the bottom, turned by
 * yaw 33.25 and pitch 7.25, set with `mkvpropedit`.
 */
private object TileMatroska {
    const val sha256: String = "8423dab15213ca15b4ca943f4c6ff91eda0e5534a1cd3c8346933e9bffe9590e"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 724) { "TileMatroska fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "TileMatroska fixture digest changed" }
        }
    }

    private val DATA: String = """
GkXfo6NChoEBQveBAULygQRC84EIQoKIbWF0cm9za2FCh4EEQoWBAhhTgGcBAAAAAAACoBFNm3TBv4TTzLhYTbuLU6uEFUmpZlOs
gaFNu4xTq4QSVMNnU6yCAVhNu4xTq4QcU7trU6yCAdNNu4xTq4QWVK5rU6yCAe/sAQAAAAAAAFIAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFUmpZqa/hEdLJvQq
17GDD0JATYCETGF2ZldBhExhdmZEiYhARAAAAAAAAOwBAAAAAAAAgwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAElTDZ9e/hJlHJW9zc85jwItjxYgAAAAAAAAAAWfImUWjh0VOQ09ERVJEh4xMYXZjIGxpYngy
NjRnyKFFo4hEVVJBVElPTkSHkzAwOjAwOjAwLjA0MDAwMDAwMAAfQ7Z1mr+ESvLzGeeBAKOPgQAAgAAAAAdliIQ6JigOHFO7a5e/
hD5ygKq7j7OBALeK94EB8YIBtPCBCRZUrmtAq7+EheA7Ba4BAAAAAAAAnNeBAXPFgQGcgQAitZyDdW5kiIEAho9WX01QRUc0L0lT
Ty9BVkODgQEj44OEAmJaAOC1sIEQuoEQmoECdnCpdnGBAXZylAAAAABAAAAAIAAAABAAAAAIAAAAdnOEQgUAAHZ0hEDoAABV7oEA
7AEAAAAAAAACAABjoqMBQsAK/+EAFGdCwArd7ARAAAADAEAAAAyDxIngAQAEaM4PyA==
"""
}

/**
 * The same picture as a cube map with 2 pixels of padding, side by side with the left eye on the
 * left, set with `mkvpropedit`.
 */
private object CubemapMatroska {
    const val sha256: String = "3c1fd386286129a1db791e760d8253372509556d6090330a5d061c8e13d1e68f"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 706) { "CubemapMatroska fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "CubemapMatroska fixture digest changed" }
        }
    }

    private val DATA: String = """
GkXfo6NChoEBQveBAULygQRC84EIQoKIbWF0cm9za2FCh4EEQoWBAhhTgGcBAAAAAAACjhFNm3TBv4TTzLhYTbuLU6uEFUmpZlOs
gaFNu4xTq4QSVMNnU6yCAVhNu4xTq4QcU7trU6yCAdNNu4xTq4QWVK5rU6yCAe/sAQAAAAAAAFIAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFUmpZqa/hEdLJvQq
17GDD0JATYCETGF2ZldBhExhdmZEiYhARAAAAAAAAOwBAAAAAAAAgwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAElTDZ9e/hJlHJW9zc85jwItjxYgAAAAAAAAAAWfImUWjh0VOQ09ERVJEh4xMYXZjIGxpYngy
NjRnyKFFo4hEVVJBVElPTkSHkzAwOjAwOjAwLjA0MDAwMDAwMAAfQ7Z1mr+ESvLzGeeBAKOPgQAAgAAAAAdliIQ6JigOHFO7a5e/
hD5ygKq7j7OBALeK94EB8YIBtPCBCRZUrmtAmb+Ej6dJra4BAAAAAAAAiteBAXPFgQGcgQAitZyDdW5kiIEAho9WX01QRUc0L0lT
Ty9BVkODgQEj44OEAmJaAOCjsIEQuoEQmoECdnCTdnGBAnZyjAAAAAAAAAAAAAAAAlO4gQFV7oEA7AEAAAAAAAACAABjoqMBQsAK
/+EAFGdCwArd7ARAAAADAEAAAAyDxIngAQAEaM4PyA==
"""
}

/**
 * The same picture remuxed to MP4 by FFmpeg 6.1, which writes Google's spherical video and
 * stereoscopic boxes: equirectangular, turned by yaw -12.5, pitch 45.75 and roll -170.25, top and
 * bottom.
 */
private object GoogleBoxesMp4 {
    const val sha256: String = "afcda04b46e93d7bd1ae7884c9a12f67e5efdac3d22a89f5f36e5acfae069a55"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 900) { "GoogleBoxesMp4 fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "GoogleBoxesMp4 fixture digest changed" }
        }
    }

    private val DATA: String = """
AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAAABNtZGF0AAAAB2WIhDomKA4AAANJbW9vdgAAAGxtdmhk
AAAAAAAAAAAAAAAAAAAD6AAAACgAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAph0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAACgAAAAAAAAAAAAA
AAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAABAAAAAQAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEA
AAAoAAAAAAABAAAAAAIQbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAA+gAAAAoBVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAA
AAAAAAAAAABWaWRlb0hhbmRsZXIAAAABu21pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAA
AAx1cmwgAAAAAQAAAXtzdGJsAAABF3N0c2QAAAAAAAAAAQAAAQdhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAABAAEABIAAAA
SAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGP//AAAAK2F2Y0MBQsAK/+EAFGdCwArd7ARAAAADAEAA
AAyDxIngAQAEaM4PyAAAAA1zdDNkAAAAAAEAAABVc3YzZAAAABFzdmhkAAAAAExhdmYAAAAAPHByb2oAAAAYcHJoZAAAAAD/84AA
AC3AAP9VwAAAAAAcZXF1aQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAAACJgAAAiYAAAA
GHN0dHMAAAAAAAAAAQAAAAEAAAKAAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAABAAAAAQAAABRzdHN6AAAAAAAAAAsAAAABAAAAFHN0
Y28AAAAAAAAAAQAAADAAAAA9dWR0YQAAADVtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAAAhpbHN0
"""
}

/**
 * The same picture remuxed to MOV by FFmpeg 9.0.2, which writes Apple's video extension box and
 * field of view box: half an equirectangular sphere, two views with the eyes reversed, the right
 * eye primary, a baseline of 64 mm, a disparity adjustment of -0.015 and a field of view of 110.5
 * degrees. The box says which eyes the picture holds but not how they are packed.
 */
private object AppleBoxMov {
    const val sha256: String = "4acf250ef97910d5bc732df722bda29a4afef5a81fe4b6b7b924c62fd46011d6"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 879) { "AppleBoxMov fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "AppleBoxMov fixture digest changed" }
        }
    }

    private val DATA: String = """
AAAAFGZ0eXBxdCAgAAACAHF0ICAAAAAId2lkZQAAABNtZGF0AAAAB2WIhDomKA4AAANAbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAA
AAAD6AAAACgAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAgAAAsx0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAACgAAAAAAAAAAAAAAAAAAAAAAAEAAAAA
AAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAABAAAAAQAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAAoAAAAAAABAAAA
AAJEbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAA+gAAAAoB//wAAAAAALWhkbHIAAAAAbWhscnZpZGUAAAAAAAAAAAAAAAAMVmlk
ZW9IYW5kbGVyAAAB721pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACxoZGxyAAAAAGRobHJ1cmwgAAAAAAAAAAAAAAAAC0RhdGFI
YW5kbGVyAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAABg3N0YmwAAAEfc3RzZAAAAAAAAAABAAABD2F2YzEA
AAAAAAAAAQAAAABGRk1QAAACAAAAAgAAEAAQAEgAAABIAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAY
//8AAAArYXZjQwFCwAr/4QAUZ0LACt3sBEAAAAMAQAAADIPEieABAARozg/IAAAAcnZleHUAAAAYcHJvagAAABBwcmppAAAAAGhl
cXUAAABSZXllcwAAAA1zdHJpAAAAAAsAAAANaGVybwAAAAACAAAAGGNhbXMAAAAQYmxpbgAAAAAAAPoAAAAAGGNtZnkAAAAQZGFk
agAAAAD///9qAAAADGhmb3YAAa+kAAAAEHBhc3AAAAABAAAAAQAAABhzdHRzAAAAAAAAAAEAAAABAAACgAAAABxzdHNjAAAAAAAA
AAEAAAABAAAAAQAAAAEAAAAUc3RzegAAAAAAAAALAAAAAQAAABRzdGNvAAAAAAAAAAEAAAAk
"""
}
