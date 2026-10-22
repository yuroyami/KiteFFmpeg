package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A seek of the whole source runs with every stream selected (#155).
 *
 * The web seeks through a packet reader. That reader used to select only the first stream, so a
 * file whose first stream is sound seeked with its video turned off, and the check that the seek
 * landed on a keyframe in time, which looks at the video, never ran. The packets that check reads
 * ahead also belong to the streams selected for it.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class WebWholeSourceSeekTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun aSeekOfTheWholeSourceSelectsEveryStream() = runTest {
        val module = fakeDecodeCodecModule()
        useCodecModule(module)
        setFakeDecodeScript(module, "gG")
        val source = MediaSource.open(OneByteSource(), emptyMap())
        try {
            source.seekMicros(1_000_000L)
            assertEquals(1, fakeDecodeSeeks(module))
            assertEquals(3, fakeSelectionAtSeek(module), "both streams are selected while the source seeks")
            assertEquals(3, fakePacketReaderSelectionMask(module), "and both stay selected after it")
        } finally {
            source.close()
        }
    }

    /** The smallest byte source `MediaSource.open` accepts; the fake demuxer ignores its content. */
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
