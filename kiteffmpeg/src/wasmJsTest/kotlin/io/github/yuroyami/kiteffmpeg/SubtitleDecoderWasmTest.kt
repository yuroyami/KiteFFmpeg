package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The web half of the subtitle decoder: what crosses the module boundary arrives intact. */
@OptIn(KiteFFmpegLowLevelApi::class)
class SubtitleDecoderWasmTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun aSubtitleCrossesTheModuleBoundaryIntact() {
        val module = fakeSubtitleCodecModule()
        useCodecModule(module)
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            val stream = source.streams[0]
            val decoder = source.openSubtitleDecoder(stream)
            try {
                source.openPacketReader(listOf(stream)).use { reader ->
                    assertNull(reader.read()!!.use(decoder::decode), "the first packet completes nothing")
                    val subtitle = assertNotNull(reader.read()!!.use(decoder::decode))
                    assertEquals(1_500_000L, subtitle.startMicros)
                    assertNull(subtitle.endMicros)
                    assertEquals(720, subtitle.canvasWidth)
                    assertEquals(576, subtitle.canvasHeight)
                    assertEquals(
                        listOf(SubtitleImage(10, 20, 2, 1, byteArrayOf(-1, 0, 0, -1, 0, 0, -1, -128), forced = true)),
                        subtitle.images,
                    )
                    assertEquals(listOf("0,0,Default,,0,0,0,,Hi"), subtitle.texts)
                    assertEquals(1, fakeSubtitleFrees(module), "the decoded subtitle is freed after it is read")
                }
            } finally {
                decoder.close()
            }
            assertEquals(1, fakeSubtitleContextFrees(module), "closing the decoder frees its context")
            assertFailsWith<IllegalStateException> { decoder.flush() }
        }
    }

    private class OneByteSource : MediaByteSource {
        override val size: Long = 1L
        override val seekable: Boolean = true
        private var consumed = false

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (consumed) return -1
            into[offset] = 0
            consumed = true
            return 1
        }

        override fun seek(position: Long) {
            consumed = position != 0L
        }

        override fun close(): Unit = Unit
    }
}
