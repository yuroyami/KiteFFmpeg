package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Dolby Vision on the web backend, against the codec module linkKiteFFmpegWasmModule links. */
class WebDolbyVisionTest {

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

    @Test
    fun aProfileFiveClipReadsAndComposesAsOnTheOtherBackends() = runTest {
        if (!useLinkedCodecModule()) return@runTest
        MediaSource.open(BytesSource(DolbyVisionFixtures.CLIP), emptyMap()).use { source ->
            val stream = source.streams.single()
            val config = assertNotNull(stream.video?.dolbyVision, "the stream's configuration")
            assertEquals(5, config.profile)
            assertFalse(config.baseLayerPlaysAlone)
            val frames = source.decodedFrames(stream).toList()
            try {
                assertEquals(DolbyVisionFixtures.FRAMES, frames.size, "decoded frames")
                frames.forEachIndexed { index, frame ->
                    val metadata = assertNotNull(frame.dolbyVision(), "the metadata of frame $index")
                    assertEquals(DolbyVisionFixtures.SCENE_BRIGHTNESS[index], metadata.sceneBrightness, "level 1 of frame $index")
                    assertNotNull(frame.composeDolbyVision(), "the composition of frame $index").use { composed ->
                        assertNull(composed.dolbyVision())
                        DolbyVisionFixtures.assertMatchesExpected(index, composed.copyPlanesToByteArray())
                    }
                }
            } finally {
                frames.forEach(Frame::close)
            }
        }
    }
}
