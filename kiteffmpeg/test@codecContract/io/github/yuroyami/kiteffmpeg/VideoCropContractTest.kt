package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A stream reports the crop its container states (#147): Matroska's `PixelCrop` elements and an
 * MP4 clean aperture both read as the rows and columns to leave out, and a stream without either
 * reports none.
 */
class VideoCropContractTest {

    private fun cropOf(bytes: ByteArray, sha256: String): VideoCrop? =
        MediaSource.open(materializeContractMedia(bytes, sha256)).use { source ->
            source.streams.first { it.type == MediaType.Video }.video?.crop
        }

    @Test
    fun aMatroskaPixelCropReadsAsItsRows() {
        assertEquals(VideoCrop(top = 0, bottom = 8, left = 0, right = 0), cropOf(CroppedMatroska.bytes, CroppedMatroska.sha256))
    }

    @Test
    fun anMp4CleanApertureReadsAsTheRowsItLeavesOut() {
        assertEquals(VideoCrop(top = 0, bottom = 8, left = 0, right = 0), cropOf(CleanApertureMp4.bytes, CleanApertureMp4.sha256))
    }

    @Test
    fun aStreamWithoutOneHasNone() {
        assertNull(cropOf(ContractMedia.bytes, ContractMedia.sha256))
    }

    @Test
    fun theHelpersFourCountsAreTopBottomLeftRight() {
        assertEquals(VideoCrop(top = 1, bottom = 2, left = 3, right = 4), videoCropOf(intArrayOf(1, 2, 3, 4)))
        assertNull(videoCropOf(intArrayOf(1, 2, 3)))
    }
}

/** A 16x16 H.264 picture in Matroska whose track says `PixelCropBottom` 8, set with `mkvpropedit`. */
private object CroppedMatroska {
    const val sha256: String = "7c490572a387d4d4b6fd0e62e61508076e2b3bddde1489e18ae2cd84cf7fe458"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 548) { "CroppedMatroska fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "CroppedMatroska fixture digest changed" }
        }
    }

    private val DATA: String = """
GkXfo6NChoEBQveBAULygQRC84EIQoKIbWF0cm9za2FCh4EEQoWBAhhTgGcBAAAAAAAB8BFNm3TAv4SnWuvCTbuLU6uEFUmpZlOs
gaFNu4xTq4QSVMNnU6yCAVlNu4xTq4QcU7trU6yCAdRNu4tTq4QWVK5rU6yBzOwBAAAAAAAAUwAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFUmpZqa/hFqKZDUq
17GDD0JATYCETGF2ZldBhExhdmZEiYhAj0AAAAAAABZUrmtAhL+Ebd3mMK4BAAAAAAAAddeBAXPFgQGcgQAitZyDdW5kiIEAho9W
X01QRUc0L0lTTy9BVkODgQEj44OEO5rKAOCNsIEQuoEQmoECVKqBCFXugQDsAQAAAAAAAAIAAGOipAFCwAr/4QAVZ0LACtp7ARAA
AAMAEAAAAwAg8SJqAQAEaM4PyOyBABJUw2fXv4T6Wmypc3POY8CLY8WIAAAAAAAAAAFnyJlFo4dFTkNPREVSRIeMTGF2YyBsaWJ4
MjY0Z8ihRaOIRFVSQVRJT05Eh5MwMDowMDowMS4wMDAwMDAwMDAAH0O2dZq/hEry8xnngQCjj4EAAIAAAAAHZYiEOiYoDhxTu2uX
v4RbFTwSu4+zgQC3iveBAfGCAbXwgQk=
"""
}

/** The same picture remuxed to MP4 by FFmpeg 9.0.2, which writes the crop as a clean aperture: 16 by 8, moved up by 4. */
private object CleanApertureMp4 {
    const val sha256: String = "9e3de7b289eb3e8d4b7c9db937e523f6019b722e36e0a48a3525e412e946ad71"

    val bytes: ByteArray by lazy {
        decodeBase64(DATA.filterNot { it.isWhitespace() }).also { decoded ->
            check(decoded.size == 843) { "CleanApertureMp4 fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "CleanApertureMp4 fixture digest changed" }
        }
    }

    private val DATA: String = """
AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAAABNtZGF0AAAAB2WIhDomKA4AAAMQbW9vdgAAAGxtdmhk
AAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAl90cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAA
AAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAABAAAAAQAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEA
AAPoAAAAAAABAAAAAAHXbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAA+gAAAPoBVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAA
AAAAAAAAAABWaWRlb0hhbmRsZXIAAAABgm1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAA
AAx1cmwgAAAAAQAAAUJzdGJsAAAA3nN0c2QAAAAAAAAAAQAAAM5hdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAABAAEABIAAAA
SAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGP//AAAALGF2Y0MBQsAK/+EAFWdCwAraewEQAAADABAA
AAMAIPEiagEABGjOD8gAAAAQcGFzcAAAAAEAAAABAAAAKGNsYXAAAAAQAAAAAQAAAAgAAAABAAAAAAAAAAH////8AAAAAQAAABRi
dHJ0AAAAAAAAAFgAAABYAAAAGHN0dHMAAAAAAAAAAQAAAAEAAD6AAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAABAAAAAQAAABRzdHN6
AAAAAAAAAAsAAAABAAAAFHN0Y28AAAAAAAAAAQAAADAAAAA9dWR0YQAAADVtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBw
bAAAAAAAAAAAAAAAAAhpbHN0
"""
}
