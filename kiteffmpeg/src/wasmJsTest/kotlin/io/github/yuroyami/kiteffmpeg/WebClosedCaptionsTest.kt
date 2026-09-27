package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** [Frame.closedCaptions] on the web backend, against the codec module linkKiteFFmpegWasmModule links. */
class WebClosedCaptionsTest {

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
    fun everyFrameAnswersWithTheCaptionsItsBitstreamCarried() = runTest {
        if (!useLinkedCodecModule()) return@runTest
        MediaSource.open(BytesSource(ClosedCaptionFixtures.CAPTIONED), emptyMap()).use { source ->
            val frames = source.decodedFrames(source.streams.single()).toList()
            try {
                assertEquals(3, frames.size, "decoded frames")
                frames.forEachIndexed { index, frame ->
                    assertContentEquals(ClosedCaptionFixtures.captionsOf(index), frame.closedCaptions(), "captions of frame $index")
                }
            } finally {
                frames.forEach(Frame::close)
            }
        }
    }
}
