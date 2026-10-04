package io.github.yuroyami.kiteffmpeg

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A web open that FFmpeg refuses is typed by the code FFmpeg returned, as on the JVM and native,
 * rather than as invalid data or an internal failure whatever the code said.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
class WebOpenFailureTypeTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    private companion object {
        /** AVERROR(EIO), what the input bridge answers when the caller's source throws. */
        const val EIO = -5
        /** AVERROR(ENOMEM). */
        const val ENOMEM = -12
        /** AVERROR(EINVAL). */
        const val EINVAL = -22
    }

    /** A source that throws at its first read, so the open's probe fails inside it. */
    private class ThrowingSource : MediaByteSource {
        val thrown = IllegalStateException("the source failed while the open probed it")
        override val size: Long = 1_000L
        override val seekable: Boolean = true
        override fun read(into: ByteArray, offset: Int, length: Int): Int = throw thrown
        override fun seek(position: Long): Unit = Unit
        override fun close(): Unit = Unit
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

    @Test
    fun anOpenWhoseSourceThrewIsAnIoErrorWithTheSourcesException() {
        val module = fakePacketReaderCodecModule()
        fakeOpenReads(module)
        useCodecModule(module)
        val source = ThrowingSource()
        val failure = assertFailsWith<FFmpegException> { MediaSource.open(source, emptyMap()) }
        assertIs<FFmpegError.Io>(failure.error, "typed ${failure.error}")
        assertEquals(EIO, failure.code)
        assertTrue(failure.cause === source.thrown, "the cause was ${failure.cause}")
    }

    @Test
    fun aStreamDiscoveryFailureKeepsItsCode() {
        val module = fakePacketReaderCodecModule()
        setFakeReturn(module, "__streamInfoRc", ENOMEM)
        useCodecModule(module)
        val failure = assertFailsWith<FFmpegException> { MediaSource.open(OneByteSource(), emptyMap()) }
        assertIs<FFmpegError.OutOfMemory>(failure.error, "typed ${failure.error}")
        assertEquals(ENOMEM, failure.code)
        assertTrue("error text for $ENOMEM" in failure.message, failure.message)
    }

    @Test
    fun aDecoderOpenFailureKeepsItsCode() {
        val module = fakeDecodeCodecModule()
        useCodecModule(module)
        setFakeDecodeScript(module, "g")
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            setFakeReturn(module, "__codecOpenRc", EINVAL)
            val failure = assertFailsWith<FFmpegException> { source.openDecoder(source.streams[0]) }
            assertIs<FFmpegError.InvalidArgument>(failure.error, "typed ${failure.error}")
            assertEquals(EINVAL, failure.code)
            assertEquals(0, fakeLiveDecoders(module), "the decoder that failed to open was not freed")
        }
    }

    @Test
    fun aSubtitleDecoderOpenFailureKeepsItsCode() {
        val module = fakeSubtitleCodecModule()
        useCodecModule(module)
        MediaSource.open(OneByteSource(), emptyMap()).use { source ->
            setFakeReturn(module, "__subtitleOpenRc", ENOMEM)
            val failure = assertFailsWith<FFmpegException> { source.openSubtitleDecoder(source.streams[0]) }
            assertIs<FFmpegError.OutOfMemory>(failure.error, "typed ${failure.error}")
            assertEquals(ENOMEM, failure.code)
        }
    }
}
