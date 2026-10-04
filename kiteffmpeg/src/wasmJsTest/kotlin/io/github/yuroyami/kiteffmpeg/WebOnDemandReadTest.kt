package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The web reader a Worker uses: FFmpeg's read and seek call the [MediaByteSource] as FFmpeg asks,
 * so playback starts after the first bytes and nothing caps the size (#133). Every source used to be
 * staged whole first, up to 512 MiB, and a larger one, or one of unknown size, was refused.
 *
 * The fake module calls the registered callbacks the way the codec module does, so these run with no
 * FFmpeg build. The last test decodes through the linked one.
 */
class WebOnDemandReadTest {

    @BeforeTest fun start() {
        forgetCodecModule()
        WebIoBridge.readOnDemand = true
    }

    @AfterTest fun finish() = forgetCodecModule()

    /**
     * Serves [content], then zeros up to [size] when that is larger, the way a range request
     * against a long file would, and records what was asked of it.
     */
    private class RangeSource(
        private val content: ByteArray,
        private val declaredSize: Long? = content.size.toLong(),
        override val seekable: Boolean = true,
        var failReads: Boolean = false,
        private val overCount: Boolean = false,
        private val failSize: Boolean = false,
        private val onClose: () -> Unit = {},
    ) : MediaByteSource {
        val readFailure = IllegalStateException("the range request failed")
        val seekFailure = IllegalStateException("the range request for the new position failed")
        val sizeFailure = IllegalStateException("cannot ask the length")
        var failSeeks = false
        var bytesServed = 0L
        var closeCount = 0
        val seekCalls = mutableListOf<Long>()
        private var position = 0L
        private val end: Long get() = declaredSize ?: content.size.toLong()

        override val size: Long? get() = if (failSize) throw sizeFailure else declaredSize

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (failReads) throw readFailure
            if (position >= end) return -1
            val n = minOf(length.toLong(), end - position).toInt()
            for (i in 0 until n) {
                val at = position + i
                into[offset + i] = if (at < content.size) content[at.toInt()] else 0
            }
            position += n
            bytesServed += n
            return if (overCount) n + 1 else n
        }

        override fun seek(position: Long) {
            if (failSeeks) throw seekFailure
            seekCalls += position
            this.position = position
        }

        override fun close() {
            closeCount++
            onClose()
        }
    }

    private fun openThroughFake(source: MediaByteSource): Pair<JsAny, MediaSource> {
        val module = fakePacketReaderCodecModule()
        useCodecModule(module)
        return module to MediaSource.open(source, emptyMap())
    }

    @Test
    fun theOpenReadsNothingItWasNotAskedForAndTheSourceStaysOpenUntilTheMediaCloses() {
        val source = RangeSource(ByteArray(1000) { it.toByte() })
        val (module, media) = openThroughFake(source)
        assertEquals(0L, source.bytesServed, "a source read on demand is read only when FFmpeg asks")
        assertEquals(0, source.closeCount, "the media source owns the source until it closes")
        assertEquals(1000.0, fakeLastOpenSize(module), "FFmpeg is told the source's own size")
        media.close()
        assertEquals(1, source.closeCount, "closing the media closes the source, once")
        media.close()
        assertEquals(1, source.closeCount, "a second close closes nothing")
    }

    @Test
    fun aSourceLargerThanTheStagingCapOpens() {
        val large = 600L * 1024 * 1024
        val source = RangeSource(ByteArray(16), declaredSize = large)
        val (module, media) = openThroughFake(source)
        media.use {
            assertEquals(large.toDouble(), fakeLastOpenSize(module))
            assertEquals(0L, source.bytesServed)
        }
    }

    @Test
    fun aSourceOfUnknownSizeStreams() {
        val source = RangeSource(ByteArray(16), declaredSize = null)
        val (module, media) = openThroughFake(source)
        media.use { assertEquals(-1.0, fakeLastOpenSize(module), "an unknown size reaches FFmpeg as -1") }
    }

    /**
     * Every byte value, across the 64 KiB scratch boundary, byte-identical where FFmpeg asked for it,
     * then the end of the data as KC_IO_EOF.
     */
    @Test
    fun ffmpegsReadsReachTheSourceByteForByte() {
        val content = ByteArray(70_000) { (it % 256).toByte() }
        val source = RangeSource(content)
        val (module, media) = openThroughFake(source)
        media.use {
            val read = fakeLastOpenRead(module)
            val destination = wasmAlloc(module, content.size)
            var at = 0
            while (true) {
                val got = fakeCallRead(module, read, destination + at, content.size - at + 1)
                if (got == -1) break
                assertTrue(got > 0, "a read answered $got")
                at += got
            }
            assertEquals(content.size, at)
            assertContentEquals(content, readBytes(module, destination, content.size))
        }
    }

    @Test
    fun aSeekMovesTheSourceAndAnswersThePosition() {
        val content = ByteArray(1000) { it.toByte() }
        val source = RangeSource(content)
        val (module, media) = openThroughFake(source)
        media.use {
            val seek = fakeLastOpenSeek(module)
            assertTrue(seek != 0, "a seekable source must offer a seek")
            assertEquals(500.0, fakeCallSeek(module, seek, 500.0, 0), "SEEK_SET")
            assertEquals(510.0, fakeCallSeek(module, seek, 10.0, 1), "SEEK_CUR")
            assertEquals(990.0, fakeCallSeek(module, seek, -10.0, 2), "SEEK_END")
            assertEquals(-2.0, fakeCallSeek(module, seek, -2000.0, 0), "a position before the start")
            assertEquals(-2.0, fakeCallSeek(module, seek, 0.0, 7), "an unknown whence")
            assertEquals(listOf(500L, 510L, 990L), source.seekCalls)
            val destination = wasmAlloc(module, 4)
            assertEquals(4, fakeCallRead(module, fakeLastOpenRead(module), destination, 4))
            assertContentEquals(content.copyOfRange(990, 994), readBytes(module, destination, 4))
        }
    }

    @Test
    fun aSourceThatCannotSeekOffersNoSeekAndIsNeverSeeked() {
        val source = RangeSource(ByteArray(1000), seekable = false)
        val (module, media) = openThroughFake(source)
        media.use {
            assertEquals(0, fakeLastOpenSeek(module), "no seek callback is what tells FFmpeg the input cannot seek")
            assertTrue(source.seekCalls.isEmpty())
        }
    }

    /** FFmpeg only sees an error code, so the source's own exception is the cause of the failed open. */
    @Test
    fun aReadThatThrowsFailsTheOpenWithTheSourcesExceptionAsItsCause() {
        val module = fakePacketReaderCodecModule()
        fakeOpenReads(module)
        useCodecModule(module)
        val source = RangeSource(ByteArray(1000), failReads = true)
        val failure = assertFailsWith<FFmpegException> { MediaSource.open(source, emptyMap()) }
        assertSame(source.readFailure, failure.cause)
        assertEquals(1, source.closeCount, "a failed open still closes the source it took, once")
    }

    /**
     * After the open as during it: FFmpeg only sees an error code, so a packet read or a seek that
     * failed because the source threw carries that exception as its cause, typed by the code
     * FFmpeg's input bridge answered, as on the JVM and native (#169).
     */
    @Test
    fun aReadThatThrowsAfterTheOpenFailsThePacketReadWithTheSourcesExceptionAsItsCause() {
        val source = RangeSource(ByteArray(1000))
        val (module, media) = openThroughFake(source)
        fakeDemuxThroughSource(module)
        media.use {
            media.openPacketReader(media.streams).use { reader ->
                reader.read()!!.close()
                source.failReads = true
                val failure = assertFailsWith<FFmpegException> { reader.read() }
                assertIs<FFmpegError.Io>(failure.error)
                assertSame(source.readFailure, failure.cause)
                source.failReads = false
                reader.read()!!.close()
            }
        }
    }

    @Test
    fun aSeekThatThrowsFailsThePacketSeekWithTheSourcesExceptionAsItsCause() {
        val source = RangeSource(ByteArray(1000))
        val (module, media) = openThroughFake(source)
        fakeDemuxThroughSource(module)
        media.use {
            media.openPacketReader(media.streams).use { reader ->
                source.failSeeks = true
                val failure = assertFailsWith<FFmpegException> { reader.seek(0, SeekDirection.Backward, null) }
                assertIs<FFmpegError.Io>(failure.error)
                assertSame(source.seekFailure, failure.cause)
            }
        }
    }

    @Test
    fun aReadThatAnswersMoreThanItWasAskedIsRefused() {
        val module = fakePacketReaderCodecModule()
        fakeOpenReads(module)
        useCodecModule(module)
        val source = RangeSource(ByteArray(1000), overCount = true)
        val failure = assertFailsWith<FFmpegException> { MediaSource.open(source, emptyMap()) }
        val cause = assertIs<IllegalStateException>(failure.cause)
        assertTrue("answered a read of 16 bytes with 17" in cause.message.orEmpty(), cause.message)
        assertEquals(1, source.closeCount)
    }

    @Test
    fun closingTheMediaRemovesBothCallbacks() {
        val (module, media) = openThroughFake(RangeSource(ByteArray(1000)))
        val read = fakeLastOpenRead(module)
        val seek = fakeLastOpenSeek(module)
        assertTrue(fakeTableEntryLive(module, read) && fakeTableEntryLive(module, seek))
        media.close()
        assertTrue(!fakeTableEntryLive(module, read) && !fakeTableEntryLive(module, seek), "the callbacks outlived the media")
    }

    @Test
    fun aSizeThatThrowsFailsTheOpenAndClosesTheSourceOnce() {
        val closeFailure = IllegalStateException("the source refused to close")
        val source = RangeSource(ByteArray(16), failSize = true, onClose = { throw closeFailure })
        val module = fakePacketReaderCodecModule()
        useCodecModule(module)
        val thrown = assertFailsWith<IllegalStateException> { MediaSource.open(source, emptyMap()) }
        assertSame(source.sizeFailure, thrown, "the getter's own failure reaches the caller")
        assertTrue(closeFailure in thrown.suppressedExceptions, "a close that fails as well rides on it")
        assertEquals(1, source.closeCount)
    }

    @Test
    fun aNegativeSizeIsRefusedAndTheSourceClosed() {
        attachFakeForInstall()
        val source = RangeSource(ByteArray(16), declaredSize = -5L)
        val failure = assertFailsWith<FFmpegException> { WebIoBridge.install(source) }
        assertTrue(failure.error is FFmpegError.Io, "a negative size is a broken source")
        assertEquals(1, source.closeCount)
    }

    /** Where nothing says otherwise, a read is on demand exactly where it may block. */
    @Test
    fun theRuntimeChoosesTheReader() {
        WebIoBridge.readOnDemand = null
        val source = RangeSource(ByteArray(1000))
        val (_, media) = openThroughFake(source)
        media.use {
            val staged = source.closeCount == 1
            assertEquals(onPageMainThread(), staged, "a page stages the source and a Worker or Node reads it on demand")
        }
    }

    /**
     * The check the issue asks for, in miniature: a source that claims 600 MiB, more than staging
     * takes, serves a real clip and then zeros. It opens and decodes its first frame after a few
     * reads, and FFmpeg asks for a tiny part of it.
     */
    @Test
    fun aSourceLargerThanTheCapDecodesAfterItsFirstBytes() = runTest {
        if (!useLinkedCodecModule()) return@runTest
        val large = 600L * 1024 * 1024
        val source = RangeSource(RealCodecModuleTest.CLIP, declaredSize = large)
        MediaSource.open(source, emptyMap()).use { media ->
            val frame = media.decodedFrames(media.streams.first { it.type == MediaType.Video }).first()
            frame.close()
        }
        assertTrue(source.bytesServed < 1024 * 1024, "FFmpeg read ${source.bytesServed} bytes for the first frame")
        assertEquals(1, source.closeCount)
    }

    private fun attachFakeForInstall() {
        useCodecModule(fakeCodecModule())
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => typeof window !== 'undefined'")
private external fun onPageMainThread(): Boolean
