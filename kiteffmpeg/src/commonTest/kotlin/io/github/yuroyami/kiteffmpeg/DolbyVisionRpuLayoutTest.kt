package io.github.yuroyami.kiteffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The read of `ffkmp_frame_dovi_rpu`'s layout, written here on its own from the layout its header
 * comment states, with values chosen so that a field read from its neighbour's place, or a 64-bit
 * number with its halves swapped or its sign lost, comes out wrong.
 */
class DolbyVisionRpuLayoutTest {
    private class Layout(nlqMethod: Int, pivotsExported: Boolean) {
        val ints = IntArray(DOLBY_VISION_RPU_INTS)
        private var at = 0

        fun int(value: Int) {
            ints[at++] = value
        }

        fun long(value: Long) {
            int((value ushr 32).toInt())
            int(value.toInt())
        }

        init {
            listOf(2, 18, 1, 3, 1, 1, 23, 2, 0, 10, 11, 12, 1, 0, 0).forEach(::int)
            listOf(4, 1, 2, nlqMethod, 5, 70000).forEach(::int)
            curve(listOf(0, 512, 1023)) { i ->
                if (i == 0) polynomial(listOf(-(1L shl 40) - 5, Long.MAX_VALUE)) else mmr(Long.MIN_VALUE + 3, MMR_ROWS)
            }
            curve(listOf(0, 1023)) { polynomial(listOf(1, -2, 3)) }
            curve(List(9) { it * 127 }) { i -> if (i % 2 == 0) mmr(i.toLong(), MMR_ROWS.take(1 + i % 3)) else polynomial(listOf(i.toLong(), -i.toLong())) }
            for (c in 0 until 3) {
                int(if (nlqMethod == 0) 1000 + c else 0)
                long(if (nlqMethod == 0) VDR_IN_MAX else 0)
                long(if (nlqMethod == 0) 1234L + c else 0)
                long(if (nlqMethod == 0) 0x500000003L else 0)
            }
            int(if (pivotsExported) 1 else 0)
            int(if (nlqMethod == 0) 64 else 0)
            int(if (nlqMethod == 0) 940 else 0)
            int(6)
            int(1)
            YCC.forEach { int(it); int(8192) }
            int(-3); int(7)
            int(1 shl 27); int(1 shl 28)
            int(1 shl 27); int(1 shl 28)
            LMS.forEach { int(it); int(16384) }
            listOf(65535, 0x8001, 2, -16, 12, 2, 1, 3, 7, 3079, 42).forEach(::int)
            check(at == DOLBY_VISION_RPU_INTS) { "the test's layout wrote $at ints" }
        }

        private fun curve(pivots: List<Int>, piece: (Int) -> Unit) {
            val start = at
            int(pivots.size)
            pivots.forEach(::int)
            for (i in pivots.indices.drop(1)) {
                at = start + 10 + (i - 1) * PIECE
                piece(i - 1)
            }
            at = start + 10 + 8 * PIECE
        }

        private fun polynomial(coefficients: List<Long>) {
            val piece = at
            int(0)
            int(coefficients.size - 1)
            coefficients.forEach(::long)
            at = piece + PIECE
        }

        private fun mmr(constant: Long, rows: List<List<Long>>) {
            val piece = at
            int(1)
            at = piece + 8
            int(rows.size)
            long(constant)
            rows.flatten().forEach(::long)
            at = piece + PIECE
        }
    }

    private fun expected(quantization: DolbyVisionNonlinearQuantization?): DolbyVisionRpu = DolbyVisionRpu(
        header = DolbyVisionRpuHeader(
            rpuType = 2,
            rpuFormat = 18,
            vdrRpuProfile = 1,
            vdrRpuLevel = 3,
            chromaResamplingExplicitFilter = true,
            coefficientDataType = 1,
            coefficientLog2Denominator = 23,
            vdrRpuNormalizedIdc = 2,
            baseLayerFullRange = false,
            baseLayerBitDepth = 10,
            enhancementLayerBitDepth = 11,
            vdrBitDepth = 12,
            spatialResamplingFilter = true,
            enhancementLayerSpatialResamplingFilter = false,
            disableResidual = false,
        ),
        mapping = DolbyVisionMapping(
            vdrRpuId = 4,
            mappingColorSpace = 1,
            mappingChromaFormat = 2,
            curves = listOf(
                DolbyVisionCurve(
                    listOf(0, 512, 1023),
                    listOf(
                        DolbyVisionPiece.Polynomial(listOf(-(1L shl 40) - 5, Long.MAX_VALUE)),
                        DolbyVisionPiece.Mmr(Long.MIN_VALUE + 3, MMR_ROWS),
                    ),
                ),
                DolbyVisionCurve(listOf(0, 1023), listOf(DolbyVisionPiece.Polynomial(listOf(1, -2, 3)))),
                DolbyVisionCurve(
                    List(9) { it * 127 },
                    List(8) { i ->
                        if (i % 2 == 0) {
                            DolbyVisionPiece.Mmr(i.toLong(), MMR_ROWS.take(1 + i % 3))
                        } else {
                            DolbyVisionPiece.Polynomial(listOf(i.toLong(), -i.toLong()))
                        }
                    },
                ),
            ),
            nonlinearQuantization = quantization,
            xPartitions = 5,
            yPartitions = 70000,
        ),
        color = DolbyVisionColor(
            dmMetadataId = 6,
            sceneRefresh = 1,
            yccToRgbMatrix = YCC.map { Rational(it, 8192) },
            yccToRgbOffset = listOf(Rational(-3, 7), Rational(1, 2), Rational(1, 2)),
            rgbToLmsMatrix = LMS.map { Rational(it, 16384) },
            signalEotf = 65535,
            signalEotfParam0 = 0x8001,
            signalEotfParam1 = 2,
            signalEotfParam2 = 0xFFFFFFF0L,
            signalBitDepth = 12,
            signalColorSpace = 2,
            signalChromaFormat = 1,
            signalFullRange = 3,
            sourceMinPq = 7,
            sourceMaxPq = 3079,
            sourceDiagonal = 42,
        ),
    )

    private val quantization = DolbyVisionNonlinearQuantization(
        pivots = listOf(64, 940),
        components = List(3) { DolbyVisionNlqComponent(offset = 1000 + it, vdrInMax = VDR_IN_MAX, deadZoneSlope = 1234L + it, deadZoneThreshold = 0x500000003L) },
    )

    @Test
    fun everyFieldReadsFromItsOwnPlace() {
        assertEquals(expected(quantization), dolbyVisionRpuOf(Layout(nlqMethod = 0, pivotsExported = true).ints))
    }

    @Test
    fun anRpuWithoutAResidualHasNoInverseQuantization() {
        val rpu = dolbyVisionRpuOf(Layout(nlqMethod = -1, pivotsExported = true).ints)
        assertNull(rpu.mapping.nonlinearQuantization)
        assertEquals(expected(null), rpu)
    }

    @Test
    fun anFFmpegThatExportsNoPivotsReadsThemAsNull() {
        val rpu = dolbyVisionRpuOf(Layout(nlqMethod = 0, pivotsExported = false).ints)
        assertEquals(expected(quantization.copy(pivots = null)), rpu)
    }

    @Test
    fun aCoefficientIsANumberOverTwoToTheDenominator() {
        val rpu = dolbyVisionRpuOf(Layout(nlqMethod = -1, pivotsExported = true).ints)
        assertEquals(1.0, rpu.coefficientValue(1L shl 23))
        assertEquals(-0.5, rpu.coefficientValue(-(1L shl 22)))
        val piece = assertNotNull(rpu.mapping.curves[1].pieces.single() as? DolbyVisionPiece.Polynomial)
        assertEquals(3.0 / (1 shl 23), rpu.coefficientValue(piece.coefficients[2]))
    }

    private companion object {
        const val PIECE = 53
        const val VDR_IN_MAX = -0x7FFFFFFFFFFFFFFFL
        val YCC = listOf(8192, 799, 1681, 8192, -933, 1091, 8192, 267, -5545)
        val LMS = listOf(17081, -349, -349, -349, 17081, -349, -349, -349, 17081)
        val MMR_ROWS: List<List<Long>> = List(3) { row -> List(7) { t -> (row * 7 + t + 1).toLong() * if (t % 2 == 0) 1 else -(1L shl 33) } }
    }
}
