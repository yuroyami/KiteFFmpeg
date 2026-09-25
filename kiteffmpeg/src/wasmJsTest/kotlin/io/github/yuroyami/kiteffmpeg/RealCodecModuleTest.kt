package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The codec module that linkKiteFFmpegWasmModule builds, loaded the way a page loads it and asked
 * to decode a real clip. Every other web test runs against a scripted fake, which proves that the
 * binding reads the right fields and nothing about a built module. The build points
 * KITEFFMPEG_WEB_MODULE at the linked kite.mjs; when there is none, the test says so and passes.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class RealCodecModuleTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    private companion object {
        /** Five frames of solid red, 64x64 H.264 in MP4: Y 81, U 90 and V 240 in every pixel. */
        val CLIP: ByteArray = (
            "000000206674797069736f6d0000020069736f6d69736f32617663316d7034310000031a6d6f6f760000006c6d766864" +
            "000000000000000000000000000003e8000000c800010000010000000000000000000000000100000000000000000000" +
            "000000000001000000000000000000000000000040000000000000000000000000000000000000000000000000000000" +
            "00000002000002697472616b0000005c746b68640000000300000000000000000000000100000000000000c800000000" +
            "000000000000000000000000000100000000000000000000000000000001000000000000000000000000000040000000" +
            "004000000040000000000024656474730000001c656c73740000000000000001000000c80000000000010000000001e1" +
            "6d646961000000206d6468640000000000000000000000000000320000000a0055c400000000002d68646c7200000000" +
            "0000000076696465000000000000000000000000566964656f48616e646c6572000000018c6d696e6600000014766d68" +
            "640000000100000000000000000000002464696e660000001c6472656600000000000000010000000c75726c20000000" +
            "010000014c7374626c000000c0737473640000000000000001000000b061766331000000000000000100000000000000" +
            "0000000000000000000040004000480000004800000000000000010c4c617663206c6962783236340000000000000000" +
            "00000000000000000000000018ffff00000036617663430164000affe100186764000aac4c2221360220000003002000" +
            "000641e244c23001000768e843044b22c0fdf8f800000000107061737000000001000000010000001462747274000000" +
            "000000721000000000000000187374747300000000000000010000000500000200000000147374737300000000000000" +
            "01000000010000001c737473630000000000000001000000010000000500000001000000287374737a00000000000000" +
            "0000000005000002990000000d000000110000001100000012000000147374636f00000000000000010000034a000000" +
            "3d75647461000000356d657461000000000000002168646c7200000000000000006d6469726170706c00000000000000" +
            "000000000008696c73740000000866726565000002e26d6461740000026f0605ffff6bdc45e9bde6d948b7962cd820d9" +
            "23eeef78323634202d20636f7265203136352072333232322062333536303561202d20482e3236342f4d5045472d3420" +
            "41564320636f646563202d20436f70796c65667420323030332d32303235202d20687474703a2f2f7777772e76696465" +
            "6f6c616e2e6f72672f783236342e68746d6c202d206f7074696f6e733a2063616261633d31207265663d313620646562" +
            "6c6f636b3d313a303a3020616e616c7973653d3078333a3078313333206d653d756d68207375626d653d313020707379" +
            "3d31207073795f72643d312e30303a302e3030206d697865645f7265663d31206d655f72616e67653d3234206368726f" +
            "6d615f6d653d31207472656c6c69733d32203878386463743d312063716d3d3020646561647a6f6e653d32312c313120" +
            "666173745f70736b69703d31206368726f6d615f71705f6f66667365743d2d3220746872656164733d32206c6f6f6b61" +
            "686561645f746872656164733d3120736c696365645f746872656164733d30206e723d3020646563696d6174653d3120" +
            "696e7465726c616365643d3020626c757261795f636f6d7061743d3020636f6e73747261696e65645f696e7472613d30" +
            "20626672616d65733d3020776569676874703d32206b6579696e743d35206b6579696e745f6d696e3d31207363656e65" +
            "6375743d343020696e7472615f726566726573683d302072635f6c6f6f6b61686561643d352072633d637266206d6274" +
            "7265653d31206372663d33302e302071636f6d703d302e36302071706d696e3d302071706d61783d3639207170737465" +
            "703d342069705f726174696f3d312e34302061713d313a312e3030008000000022658882067ffea72fe052decf14e1d2" +
            "2ec77e382bc5211466cadb5772023f2e789b4d00000009419a1d8867fffa5bb60000000d419a2bf0419329842ffff327" +
            "150000000d419a393c1079329810bff327140000000e419a497c107e4ca6010affe44df9"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /** [bytes] as a seekable input. */
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

    @Test
    fun theLinkedModuleDecodesAnH264Clip() = runTest {
        val url = linkedModuleUrl() ?: return@runTest println("real codec module test skipped: no linked kite.mjs")
        KiteFFmpegWeb.load(url)
        assertTrue(FFmpeg.identity.isAcceptable, FFmpeg.identity.describe())
        MediaSource.open(BytesSource(CLIP), emptyMap()).use { source ->
            val video = source.streams.single()
            assertEquals("h264", video.codec.name)
            assertEquals(64, video.video?.width)
            val frames = source.decodedFrames(video).toList()
            try {
                assertEquals(5, frames.size, "decoded frames")
                val planes = frames.first().copyPlanesToByteArray()
                assertEquals(64 * 64 * 3 / 2, planes.size)
                val luma = planes.copyOfRange(0, 64 * 64).map { it.toInt() and 0xFF }.average()
                val red = planes.copyOfRange(64 * 64 + 32 * 32, planes.size).map { it.toInt() and 0xFF }.average()
                assertTrue(luma in 78.0..84.0, "luma $luma, not red's 81")
                assertTrue(red in 236.0..244.0, "red difference $red, not red's 240")
            } finally {
                frames.forEach(Frame::close)
            }
        }
    }
}

/** A file URL for the linked module the build named, or null when there is none. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """() => {
        const p = globalThis.process;
        const file = p && p.env ? p.env.KITEFFMPEG_WEB_MODULE : undefined;
        if (!file) return null;
        if (!p.getBuiltinModule) return null;
        if (!p.getBuiltinModule("node:fs").existsSync(file)) return null;
        // pathToFileURL encodes a '#' in the path, which a plain "file://" + path reads as a fragment.
        return p.getBuiltinModule("node:url").pathToFileURL(file).href;
    }""",
)
private external fun linkedModuleUrl(): String?
