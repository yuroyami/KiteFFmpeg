package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.OpenerLayout
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
 * The staged web reader against its own written contract. It is what a page's main thread uses, so
 * every test here asks for it, whatever the runtime the suite runs in; [WebOnDemandReadTest] covers
 * the reader a Worker uses.
 *
 * `MediaByteSource`'s KDoc promises two things this backend did not keep: close runs exactly once,
 * and seek is never called on a source that says it is not seekable. JVM honours both, Native
 * honours seekable, and this one honoured neither and said nothing. Those are contract breaks
 * rather than quality bars, which is why they are the arms that come first.
 */
class WebIoBridgeTest {

    @BeforeTest fun start() {
        forgetCodecModule()
        WebIoBridge.readOnDemand = false
    }

    @AfterTest fun finish() = forgetCodecModule()

    /**
     * Records what the bridge did to it.
     *
     * [seekable] false must mean [seek] is never called at all, not that its argument is ignored:
     * a source that cannot rewind may still be mid-stream, and rewinding it is what corrupts it.
     */
    private class FakeByteSource(
        private val content: ByteArray,
        override val seekable: Boolean = true,
        override val size: Long? = content.size.toLong(),
        private val failAfterBytes: Int = -1,
        /** From this many bytes on, each read reports one byte more than it read. */
        private val overCountAfterBytes: Int = -1,
        private val givenLocation: String? = null,
        private val locationFailure: Throwable? = null,
    ) : MediaByteSource {
        override val location: String?
            get() = locationFailure?.let { throw it } ?: givenLocation

        var closeCount: Int = 0
            private set
        val seekCalls: MutableList<Long> = mutableListOf()
        private var position = 0
        private var served = 0

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (failAfterBytes >= 0 && served >= failAfterBytes) {
                throw IllegalStateException("scripted read failure at $served bytes")
            }
            if (position >= content.size) return -1
            val n = minOf(length, content.size - position)
            content.copyInto(into, offset, position, position + n)
            position += n
            val overCounts = overCountAfterBytes >= 0 && served >= overCountAfterBytes
            served += n
            return if (overCounts) n + 1 else n
        }

        override fun seek(position: Long) {
            seekCalls += position
            this.position = position.toInt()
        }

        override fun close() {
            closeCount++
        }
    }

    /** Answers its size only while open, like a source over a handle that close released. */
    private class ClosingSizeSource(private val content: ByteArray) : MediaByteSource {
        var closeCount: Int = 0
            private set
        var sizeReadsAfterClose: Int = 0
            private set
        private var position = 0

        override val seekable: Boolean = true

        override val size: Long?
            get() {
                if (closeCount > 0) {
                    sizeReadsAfterClose++
                    throw IllegalStateException("size read after close")
                }
                return content.size.toLong()
            }

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= content.size) return -1
            val n = minOf(length, content.size - position)
            content.copyInto(into, offset, position, position + n)
            position += n
            return n
        }

        override fun seek(position: Long) {
            this.position = position.toInt()
        }

        override fun close() {
            closeCount++
        }
    }

    private fun attachFake(): JsAnyHolder {
        val module = fakeCodecModule()
        useCodecModule(module)
        return JsAnyHolder(module)
    }

    private class JsAnyHolder(val module: kotlin.js.JsAny)

    @Test
    fun theSourceIsClosedExactlyOnceAfterStaging() {
        attachFake()
        val source = FakeByteSource(ByteArray(1000) { it.toByte() })
        WebIoBridge.install(source)
        assertEquals(1, source.closeCount, "staging consumes the source whole; it must then close it")
    }

    /** The path that actually leaks in practice: the source is gone and the handle is not. */
    @Test
    fun theSourceIsClosedWhenStagingFailsPartWay() {
        attachFake()
        val source = FakeByteSource(ByteArray(200_000) { it.toByte() }, failAfterBytes = 65_536)
        val failure = assertFailsWith<FFmpegException> { WebIoBridge.install(source) }
        assertTrue(failure.error is FFmpegError.Io, "a source that throws is an I/O error, as on the other backends")
        assertIs<IllegalStateException>(failure.cause, "the source's own exception is the cause")
        assertEquals(1, source.closeCount, "a source that threw mid-stage must still be closed once")
    }

    @Test
    fun openingUsesTheStagedSizeAndNeverAsksTheClosedSource() {
        val module = fakePacketReaderCodecModule()
        useCodecModule(module)
        val source = ClosingSizeSource(ByteArray(1000) { it.toByte() })
        val media = MediaSource.open(source, emptyMap())
        try {
            assertEquals(0, source.sizeReadsAfterClose, "every size read must come before the close")
            assertEquals(1, source.closeCount, "staging closes the source exactly once")
            assertEquals(1000.0, fakeLastOpenSize(module), "FFmpeg must be told the staged byte count")
        } finally {
            media.close()
        }
    }

    @Test
    fun theUrlAndTheMimeTypeReachTheOpen() {
        val module = fakePacketReaderCodecModule()
        useCodecModule(module)
        val media = MediaSource.open(
            FakeByteSource(ByteArray(1000) { it.toByte() }),
            url = "https://cdn.example/live/index",
            mimeType = "application/vnd.apple.mpegurl",
        )
        try {
            assertEquals(
                "https://cdn.example/live/index application/vnd.apple.mpegurl 0",
                fakeLastOpenHints(module),
                "the url and the MIME type must reach the C open, with no opener",
            )
        } finally {
            media.close()
        }
    }

    /** Even with no url, no MIME type and no opener, the location is enough to reach the open that takes it. */
    @Test
    fun theSourceLocationReachesTheOpen() {
        val module = fakePacketReaderCodecModule()
        useCodecModule(module)
        val media = MediaSource.open(
            FakeByteSource(ByteArray(1000) { it.toByte() }, givenLocation = "https://cdn.example/x/master.m3u8"),
        )
        try {
            assertEquals("https://cdn.example/x/master.m3u8", fakeLastOpenLocation(module))
        } finally {
            media.close()
        }
    }

    @Test
    fun anEmptyLocationReachesTheOpenAsNone() {
        val module = fakePacketReaderCodecModule()
        useCodecModule(module)
        val media = MediaSource.open(
            FakeByteSource(ByteArray(1000) { it.toByte() }, givenLocation = ""),
            url = "https://cdn.example/live/index",
        )
        try {
            assertEquals(null, fakeLastOpenLocation(module), "an empty location must be the same as none")
        } finally {
            media.close()
        }
    }

    @Test
    fun aLocationThatThrowsFailsTheOpenAndClosesTheSource() {
        useCodecModule(fakePacketReaderCodecModule())
        val thrown = IllegalStateException("the redirect could not be followed")
        val source = FakeByteSource(ByteArray(1000) { it.toByte() }, locationFailure = thrown)
        val failure = assertFailsWith<IllegalStateException> { MediaSource.open(source, url = "https://cdn.example/a") }
        assertSame(thrown, failure, "the getter's own exception must reach the caller")
        assertEquals(1, source.closeCount, "the open owns the source, so a failed open closes it once")
    }

    @Test
    fun aNestedSourceAnswersItsLocationAsFFmpegAsksForIt() {
        val module = fakePacketReaderCodecModule()
        useCodecModule(module)
        val location = "https://cdn.example/x/v1/index.m3u8"
        val nested = FakeByteSource(ByteArray(64), givenLocation = location)
        probeNested(module, small = 4)
        MediaSource.open(FakeByteSource(ByteArray(1000)), url = "https://origin.example/v1/index.m3u8", nestedOpener = { nested })
            .close()
        assertEquals(
            "${location.length} true ${location.length} $location",
            fakeNestedProbeResult(module),
            "a buffer too small must be left alone and the length answered, then the address written whole",
        )
        assertEquals(1, nested.closeCount)
    }

    @Test
    fun aNestedSourceWithoutALocationAnswersNone() {
        val module = fakePacketReaderCodecModule()
        useCodecModule(module)
        probeNested(module, small = 64)
        MediaSource.open(FakeByteSource(ByteArray(1000)), url = "https://origin.example/v1/index.m3u8", nestedOpener = {
            FakeByteSource(ByteArray(64), givenLocation = "")
        }).close()
        assertEquals("0 true 0 null", fakeNestedProbeResult(module))
    }

    @Test
    fun aNestedLocationThatThrowsFailsThatAddress() {
        val module = fakePacketReaderCodecModule()
        useCodecModule(module)
        val nested = FakeByteSource(ByteArray(64), locationFailure = IllegalStateException("no redirect answer"))
        probeNested(module, small = 64)
        MediaSource.open(FakeByteSource(ByteArray(1000)), url = "https://origin.example/v1/index.m3u8", nestedOpener = { nested })
            .close()
        assertEquals("open -2", fakeNestedProbeResult(module), "the address must fail rather than resolve against the wrong place")
        assertEquals(1, nested.closeCount, "a source the opener returned is closed even when its address fails")
    }

    private fun probeNested(module: JsAny, small: Int) {
        withNestedIo(module)
        fakeProbeNestedLocation(
            module,
            "https://origin.example/v1/media.m3u8",
            OpenerLayout.openFn,
            OpenerLayout.locationFn,
            OpenerLayout.closeFn,
            small,
        )
    }

    /**
     * A module linked from an FFmpeg without the trust_io_open patch refuses a nested opener before
     * any work, as the other backends do, and the source the open was handed is still closed once.
     */
    @Test
    fun aNestedOpenerIsRefusedByAModuleWithoutThePatch() {
        val module = fakePacketReaderCodecModule()
        withoutNestedIo(module)
        useCodecModule(module)
        val source = FakeByteSource(ByteArray(1000) { it.toByte() })
        val failure = assertFailsWith<FFmpegException> { MediaSource.open(source, nestedOpener = { null }) }
        assertIs<FFmpegError.Unsupported>(failure.error)
        assertTrue("trust_io_open" in failure.message.orEmpty(), "the refusal must name the patch: ${failure.message}")
        assertEquals(1, source.closeCount, "the open owns the source, so a refusal closes it")
    }

    @Test
    fun aNonSeekableSourceIsNeverSeeked() {
        attachFake()
        val source = FakeByteSource(ByteArray(1000) { it.toByte() }, seekable = false)
        WebIoBridge.install(source)
        assertTrue(
            source.seekCalls.isEmpty(),
            "MediaByteSource promises seek is never called when seekable is false, was ${source.seekCalls}",
        )
    }

    @Test
    fun aSeekableSourceIsRewoundExactlyOnce() {
        attachFake()
        val source = FakeByteSource(ByteArray(1000) { it.toByte() })
        WebIoBridge.install(source)
        assertEquals(listOf(0L), source.seekCalls, "a seekable source is staged from the start, once")
    }

    /**
     * Every byte value, across a chunk boundary, byte-identical in codec memory.
     *
     * This is the arm that guards the crossing-count fix. Packing a chunk into one JS call is only
     * correct while every value 0..255 survives it; anything that treats the payload as text
     * mangles the high half and leaves the low half looking perfect, which is the failure a ramp
     * catches and an ASCII fixture does not.
     */
    @Test
    fun everyByteValueSurvivesStagingAcrossAChunkBoundary() {
        val holder = attachFake()
        val content = ByteArray(70_000) { (it % 256).toByte() }
        val source = FakeByteSource(content)
        WebIoBridge.install(source)
        val staged = readBytes(holder.module, lastMallocPointer(holder.module), content.size)
        assertContentEquals(content, staged, "the staged bytes must equal the source's, value for value")
    }

    @Test
    fun anOversizeSourceRefusesBeforeAllocatingAndStillCloses() {
        val holder = attachFake()
        val before = mallocCount(holder.module)
        val source = FakeByteSource(ByteArray(16), size = 513L * 1024 * 1024)
        val failure = assertFailsWith<FFmpegException> { WebIoBridge.install(source) }
        assertTrue(failure.error is FFmpegError.Unsupported, "an oversize source is a typed refusal")
        assertEquals(before, mallocCount(holder.module), "the refusal must not have staged anything")
        assertEquals(1, source.closeCount, "a refused source is still the bridge's to close")
    }

    @Test
    fun aSourceOfUnknownSizeRefusesBeforeAllocatingAndStillCloses() {
        val holder = attachFake()
        val before = mallocCount(holder.module)
        val source = FakeByteSource(ByteArray(16), size = null)
        val failure = assertFailsWith<FFmpegException> { WebIoBridge.install(source) }
        assertTrue(failure.error is FFmpegError.Unsupported, "a live stream is a typed refusal")
        assertEquals(before, mallocCount(holder.module), "the refusal must not have staged anything")
        assertEquals(1, source.closeCount, "a refused source is still the bridge's to close")
    }

    /** The last chunk asks for exactly the bytes that remain. */
    @Test
    fun anOverCountOnTheLastChunkIsRefusedBeforeItIsWritten() {
        attachFake()
        val source = FakeByteSource(ByteArray(70_000) { it.toByte() }, overCountAfterBytes = 65_536)
        val failure = assertFailsWith<FFmpegException> { WebIoBridge.install(source) }
        assertTrue(failure.error is FFmpegError.Io, "a miscounting source is an I/O error, as on the other backends")
        val cause = assertIs<IllegalStateException>(failure.cause, "the refusal names the count")
        assertTrue("answered a read of 4464 bytes with 4465" in cause.message.orEmpty(), cause.message)
        assertEquals(1, source.closeCount, "a refused source is still the bridge's to close")
    }

    @Test
    fun aNegativeSizeRefusesBeforeAllocatingAndStillCloses() {
        val holder = attachFake()
        val before = mallocCount(holder.module)
        val source = FakeByteSource(ByteArray(16), size = -5L)
        val failure = assertFailsWith<FFmpegException> { WebIoBridge.install(source) }
        assertTrue(failure.error is FFmpegError.Io, "a negative size is a broken source")
        assertEquals(before, mallocCount(holder.module), "the refusal must not have staged anything")
        assertEquals(1, source.closeCount, "a refused source is still the bridge's to close")
    }
}
