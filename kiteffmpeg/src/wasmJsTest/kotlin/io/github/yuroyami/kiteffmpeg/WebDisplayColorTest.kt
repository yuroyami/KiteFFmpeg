@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_convert_pixfmt
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_copy_to_buffer
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_pix_fmt_from_name
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WebRgbaConverter] against the linked codec module: an SDR clip draws as the plain conversion, a
 * YCgCo clip with the YCgCo matrix, and a PQ clip tone mapped. The YCgCo and PQ clips are 16x16
 * H.264 made with ffmpeg, a flat colour and testsrc2, with the colour tags written into the
 * bitstream by h264_metadata.
 */
class WebDisplayColorTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    private class BytesSource(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close(): Unit = Unit
    }

    private suspend fun withFirstFrame(clip: ByteArray, block: (Frame) -> Unit) {
        MediaSource.open(BytesSource(clip), emptyMap()).use { source ->
            val frames = source.decodedFrames(source.streams.single()).toList()
            try {
                block(frames.first())
            } finally {
                frames.forEach(Frame::close)
            }
        }
    }

    /** What the canvas receives: the converter's bytes, read back out of the JS array. */
    private fun drawn(frame: Frame): IntArray {
        val size = frame.info.width * frame.info.height * 4
        val canvas = newCanvasBytes(size)
        WebRgbaConverter().use { assertTrue(it.copyInto(frame, canvas), "copyInto refused the frame") }
        return IntArray(size) { byteAt(canvas, it) }
    }

    /** The plain conversion to rgba, which changes the encoding and nothing else. */
    private fun plain(frame: Frame): IntArray {
        val module = requireModule()
        val size = frame.info.width * frame.info.height * 4
        val rgba = withCString("rgba") { ffkmp_pix_fmt_from_name(module, it) }
        val converted = ffkmp_frame_convert_pixfmt(module, frame.pointer, rgba)
        assertTrue(converted != 0, "the plain conversion failed")
        val buffer = wasmAlloc(module, size)
        try {
            assertEquals(size, ffkmp_frame_copy_to_buffer(module, converted, buffer, size))
            return readBytes(module, buffer, size).map { it.toInt() and 0xFF }.toIntArray()
        } finally {
            wasmFree(module, buffer)
            ffkmp_frame_free(module, converted)
        }
    }

    @Test
    fun anSdrClipDrawsAsThePlainConversion() = runTest {
        if (!useLinkedCodecModule()) return@runTest
        withFirstFrame(RealCodecModuleTest.CLIP) { frame ->
            assertTrue(drawn(frame).contentEquals(plain(frame)), "the canvas bytes differ from the plain conversion")
        }
    }

    @Test
    fun aYCgCoClipDrawsWithTheYCgCoMatrix() = runTest {
        if (!useLinkedCodecModule()) return@runTest
        withFirstFrame(YCGCO_CLIP) { frame ->
            assertEquals(ColorMatrix.YCgCo, frame.info.color.matrix)
            assertTrue(frame.info.color.fullRange)
            val planes = frame.copyPlanesToByteArray().map { it.toInt() and 0xFF }
            // A flat colour, so every pixel reads the same luma and chroma pair.
            val y = planes[0]
            val cg = planes[16 * 16] - 128
            val co = planes[16 * 16 + 8 * 8] - 128
            val expected = listOf(y - cg + co, y + cg, y - cg - co).map { it.coerceIn(0, 255) }
            val canvas = drawn(frame)
            for (pixel in 0 until 16 * 16) {
                val actual = (0..2).map { canvas[pixel * 4 + it] }
                assertTrue(
                    actual.zip(expected).all { (a, e) -> abs(a - e) <= 1 },
                    "pixel $pixel is $actual, the YCgCo matrix gives $expected from Y $y, Cg $cg, Co $co",
                )
                assertEquals(255, canvas[pixel * 4 + 3])
            }
        }
    }

    @Test
    fun aPqClipIsToneMappedOnTheCanvas() = runTest {
        if (!useLinkedCodecModule()) return@runTest
        withFirstFrame(PQ_CLIP) { frame ->
            assertEquals(ColorTransfer.SmpteSt2084, frame.info.color.transfer)
            val before = plain(frame)
            val canvas = drawn(frame)
            var changed = 0
            for (pixel in 0 until 16 * 16) {
                val at = pixel * 4
                val expected = ToneLaw.pqBt2020(before[at], before[at + 1], before[at + 2])
                val actual = intArrayOf(canvas[at], canvas[at + 1], canvas[at + 2])
                assertTrue(
                    (0..2).all { abs(actual[it] - expected[it]) <= 1 },
                    "pixel $pixel: PQ (${before[at]}, ${before[at + 1]}, ${before[at + 2]}) drew as " +
                        "${actual.toList()}, the tone map gives ${expected.toList()}",
                )
                if (!actual.contentEquals(intArrayOf(before[at], before[at + 1], before[at + 2]))) changed++
            }
            assertTrue(changed > 16 * 16 / 2, "only $changed of 256 pixels changed, so the tone map did not run")
        }
    }

    /**
     * KitePlayer's HdrToneMap for PQ with BT.2020 primaries, lookup tables included, because the
     * tables round and a direct formula would not give the same bytes.
     */
    private object ToneLaw {
        private const val PEAK = 1000.0
        private const val WHITE = 203.0

        private fun pqEncode(y: Double): Double {
            val p = y.coerceAtLeast(0.0).pow(0.1593017578125)
            return ((0.8359375 + 18.8515625 * p) / (1.0 + 18.6875 * p)).pow(78.84375)
        }

        private fun pqDecode(e: Double): Double {
            val p = e.coerceAtLeast(0.0).pow(1.0 / 78.84375)
            return ((p - 0.8359375).coerceAtLeast(0.0) / (18.8515625 - 18.6875 * p)).pow(1.0 / 0.1593017578125)
        }

        private fun eetfNits(nits: Double): Double {
            val srcPq = pqEncode(PEAK / 10000.0)
            val dstPq = pqEncode(WHITE / 10000.0)
            val e1 = (pqEncode(nits / 10000.0) / srcPq).coerceIn(0.0, 1.0)
            val maxLum = dstPq / srcPq
            val ks = 1.5 * maxLum - 0.5
            val e2 = if (e1 <= ks) e1 else {
                val t = (e1 - ks) / (1.0 - ks)
                val t2 = t * t
                val t3 = t2 * t
                (2 * t3 - 3 * t2 + 1) * ks + (t3 - 2 * t2 + t) * (1 - ks) + (-2 * t3 + 3 * t2) * maxLum
            }
            return pqDecode(e2 * srcPq) * 10000.0
        }

        private val eotf = DoubleArray(256) { pqDecode(it / 255.0) * 10000.0 }
        private val eetfRatio = DoubleArray(1024) { i ->
            val nits = (i / 1023.0).let { it * it } * PEAK
            if (nits <= 1e-4) 1.0 else eetfNits(nits) / nits
        }
        private val encode = IntArray(4096) { i ->
            ((i / 4095.0).let { it * it }.pow(1.0 / 2.2) * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }

        private fun warp(value: Double, max: Double, top: Int): Int {
            val n = value / max
            if (n <= 0.0) return 0
            if (n >= 1.0) return top
            return (sqrt(n) * top + 0.5).toInt()
        }

        fun pqBt2020(red: Int, green: Int, blue: Int): IntArray {
            val r0 = eotf[red]
            val g0 = eotf[green]
            val b0 = eotf[blue]
            val r = (1.6605 * r0 - 0.5876 * g0 - 0.0728 * b0).coerceAtLeast(0.0)
            val g = (-0.1246 * r0 + 1.1329 * g0 - 0.0083 * b0).coerceAtLeast(0.0)
            val b = (-0.0182 * r0 - 0.1006 * g0 + 1.1187 * b0).coerceAtLeast(0.0)
            val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
            val clamped = if (luma > PEAK) PEAK else luma
            val mapped = eetfRatio[warp(clamped, PEAK, 1023)] * clamped
            val ratio = if (luma > 1e-4) mapped / luma / WHITE else 1.0 / WHITE
            return intArrayOf(encode[warp(r * ratio, 1.0, 4095)], encode[warp(g * ratio, 1.0, 4095)], encode[warp(b * ratio, 1.0, 4095)])
        }
    }

    private companion object {
        /** One flat colour, 16x16 H.264, full range, tagged YCgCo (matrix 8). */
        val YCGCO_CLIP: ByteArray = (
            "000000206674797069736f6d0000020069736f6d69736f32617663316d703431000003266d6f6f760000006c6d766864" +
            "000000000000000000000000000003e80000005000010000010000000000000000000000000100000000000000000000" +
            "000000000001000000000000000000000000000040000000000000000000000000000000000000000000000000000000" +
            "00000002000002757472616b0000005c746b686400000003000000000000000000000001000000000000005000000000" +
            "000000000000000000000000000100000000000000000000000000000001000000000000000000000000000040000000" +
            "001000000010000000000024656474730000001c656c73740000000000000001000000500000020000010000000001ed" +
            "6d646961000000206d646864000000000000000000000000000032000000040055c400000000002d68646c7200000000" +
            "0000000076696465000000000000000000000000566964656f48616e646c657200000001986d696e6600000014766d68" +
            "640000000100000000000000000000002464696e660000001c6472656600000000000000010000000c75726c20000000" +
            "01000001587374626c000000c0737473640000000000000001000000b061766331000000000000000100000000000000" +
            "0000000000000000000010001000480000004800000000000000010c4c617663206c6962783236340000000000000000" +
            "00000000000000000000000018ffff00000036617663430164000affe100196764000aace47b016e0202108000000300" +
            "800000190789128901000668ebe3cb22c0fdf8f800000000107061737000000001000000010000001462747274000000" +
            "000001194000000000000000187374747300000000000000010000000200000200000000147374737300000000000000" +
            "01000000010000001863747473000000000000000100000002000002000000001c737473630000000000000001000000" +
            "0100000002000000010000001c7374737a000000000000000000000002000002c40000000c000000147374636f000000" +
            "0000000001000003560000003d75647461000000356d657461000000000000002168646c7200000000000000006d6469" +
            "726170706c00000000000000000000000008696c73740000000866726565000002d86d646174000002aa0605ffffa6dc" +
            "45e9bde6d948b7962cd820d923eeef78323634202d20636f7265203136352072333232322062333536303561202d2048" +
            "2e3236342f4d5045472d342041564320636f646563202d20436f70796c65667420323030332d32303235202d20687474" +
            "703a2f2f7777772e766964656f6c616e2e6f72672f783236342e68746d6c202d206f7074696f6e733a2063616261633d" +
            "31207265663d33206465626c6f636b3d313a303a3020616e616c7973653d3078333a3078313133206d653d6865782073" +
            "75626d653d37207073793d31207073795f72643d312e30303a302e3030206d697865645f7265663d31206d655f72616e" +
            "67653d3136206368726f6d615f6d653d31207472656c6c69733d31203878386463743d312063716d3d3020646561647a" +
            "6f6e653d32312c313120666173745f70736b69703d31206368726f6d615f71705f6f66667365743d2d32207468726561" +
            "64733d31206c6f6f6b61686561645f746872656164733d3120736c696365645f746872656164733d30206e723d302064" +
            "6563696d6174653d3120696e7465726c616365643d3020626c757261795f636f6d7061743d3020636f6e73747261696e" +
            "65645f696e7472613d3020626672616d65733d3120625f707972616d69643d3020625f61646170743d3120625f626961" +
            "733d30206469726563743d3120776569676874623d31206f70656e5f676f703d3020776569676874703d32206b657969" +
            "6e743d32206b6579696e745f6d696e3d31207363656e656375743d343020696e7472615f726566726573683d30207263" +
            "5f6c6f6f6b61686561643d322072633d637266206d62747265653d31206372663d32332e302071636f6d703d302e3630" +
            "2071706d696e3d302071706d61783d3639207170737465703d342069705f726174696f3d312e34302061713d313a312e" +
            "303000800000001265888400bffedb5bf32c1546ab0c18faf32700000008419a25b10afffec0"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** testsrc2 at 16x16, 10-bit H.264, tagged PQ with BT.2020 primaries and matrix. */
        val PQ_CLIP: ByteArray = (
            "000000206674797069736f6d0000020069736f6d69736f32617663316d703431000003286d6f6f760000006c6d766864" +
            "000000000000000000000000000003e80000005000010000010000000000000000000000000100000000000000000000" +
            "000000000001000000000000000000000000000040000000000000000000000000000000000000000000000000000000" +
            "00000002000002777472616b0000005c746b686400000003000000000000000000000001000000000000005000000000" +
            "000000000000000000000000000100000000000000000000000000000001000000000000000000000000000040000000" +
            "001000000010000000000024656474730000001c656c73740000000000000001000000500000020000010000000001ef" +
            "6d646961000000206d646864000000000000000000000000000032000000040055c400000000002d68646c7200000000" +
            "0000000076696465000000000000000000000000566964656f48616e646c6572000000019a6d696e6600000014766d68" +
            "640000000100000000000000000000002464696e660000001c6472656600000000000000010000000c75726c20000000" +
            "010000015a7374626c000000c2737473640000000000000001000000b261766331000000000000000100000000000000" +
            "0000000000000000000010001000480000004800000000000000010c4c617663206c6962783236340000000000000000" +
            "00000000000000000000000018ffff0000003861766343016e000affe1001b676e000aa6ce47b016a122012800000300" +
            "0800000301907891289001000668ebe3cb22c0fdfafa0000000010706173700000000100000001000000146274727400" +
            "00000000014c080000000000000018737474730000000000000001000000020000020000000014737473730000000000" +
            "000001000000010000001863747473000000000000000100000002000002000000001c73747363000000000000000100" +
            "00000100000002000000010000001c7374737a000000000000000000000002000003460000000c000000147374636f00" +
            "00000000000001000003580000003d75647461000000356d657461000000000000002168646c7200000000000000006d" +
            "6469726170706c00000000000000000000000008696c737400000008667265650000035a6d646174000002aa0605ffff" +
            "a6dc45e9bde6d948b7962cd820d923eeef78323634202d20636f7265203136352072333232322062333536303561202d" +
            "20482e3236342f4d5045472d342041564320636f646563202d20436f70796c65667420323030332d32303235202d2068" +
            "7474703a2f2f7777772e766964656f6c616e2e6f72672f783236342e68746d6c202d206f7074696f6e733a2063616261" +
            "633d31207265663d33206465626c6f636b3d313a303a3020616e616c7973653d3078333a3078313133206d653d686578" +
            "207375626d653d37207073793d31207073795f72643d312e30303a302e3030206d697865645f7265663d31206d655f72" +
            "616e67653d3136206368726f6d615f6d653d31207472656c6c69733d31203878386463743d312063716d3d3020646561" +
            "647a6f6e653d32312c313120666173745f70736b69703d31206368726f6d615f71705f6f66667365743d2d3220746872" +
            "656164733d31206c6f6f6b61686561645f746872656164733d3120736c696365645f746872656164733d30206e723d30" +
            "20646563696d6174653d3120696e7465726c616365643d3020626c757261795f636f6d7061743d3020636f6e73747261" +
            "696e65645f696e7472613d3020626672616d65733d3120625f707972616d69643d3020625f61646170743d3120625f62" +
            "6961733d30206469726563743d3120776569676874623d31206f70656e5f676f703d3020776569676874703d32206b65" +
            "79696e743d32206b6579696e745f6d696e3d31207363656e656375743d343020696e7472615f726566726573683d3020" +
            "72635f6c6f6f6b61686561643d322072633d637266206d62747265653d31206372663d32332e302071636f6d703d302e" +
            "36302071706d696e3d302071706d61783d3831207170737465703d342069705f726174696f3d312e34302061713d313a" +
            "312e30300080000000946588840097d743bc4fd9fe7546777a6663ef1d382317fafab1b923f6ec146b9bcf0f9bc486a8" +
            "3020e24dc34edbdc12c853ab1394f3281161c9f7fe324266283317d82ef19168a5dcb9f53a9d036a7c82bc109854ebd2" +
            "1fd3ac5a752089dfa5f502debc20796dc260e495436ffef9f50458050dca038c701fda3684a5f231f55c92638b15cfd9" +
            "806e2f6ad268edef1982545e003100000008419a25b10b7fcc80"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

@JsFun("(n) => new Uint8ClampedArray(n)")
private external fun newCanvasBytes(length: Int): JsAny

@JsFun("(a, i) => a[i]")
private external fun byteAt(array: JsAny, index: Int): Int
