package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.OpenerLayout
import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Calls the real registered callbacks with C-shaped arguments, without a codec build. */
class WebNestedDemandReadTest {
    @BeforeTest fun start() {
        forgetCodecModule()
        WebIoBridge.readOnDemand = true
    }

    @AfterTest fun finish() = forgetCodecModule()

    private class Source(
        var declared: Long? = 600L * 1024 * 1024,
        override val seekable: Boolean = true,
        private val end: Long = declared ?: 70_000L,
    ) : MediaByteSource {
        var position = 0L
        var bytes = 0L
        var reads = 0
        var closed = 0
        var readFailure: Throwable? = null
        var seekFailure: Throwable? = null
        var sizeFailure: Throwable? = null
        var closeFailure: Throwable? = null
        var locationFailure: Throwable? = null
        var badRead: Int? = null
        var resolvedLocation: String? = null
        val seeks = mutableListOf<Long>()
        override val size: Long? get() {
            sizeFailure?.let { throw it }
            return declared
        }
        override val location: String? get() {
            locationFailure?.let { throw it }
            return resolvedLocation
        }
        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            check(closed == 0) { "read after close" }
            reads++
            readFailure?.let { throw it }
            badRead?.let { return it }
            if (position >= end) return -1
            val count = minOf(length.toLong(), end - position).toInt()
            repeat(count) { into[offset + it] = ((position + it) % 251).toByte() }
            position += count
            bytes += count
            return count
        }
        override fun seek(position: Long) {
            check(closed == 0 && seekable) { "invalid source seek" }
            seekFailure?.let { throw it }
            seeks += position
            this.position = position
        }
        override fun close() {
            closed++
            closeFailure?.let { throw it }
        }
    }

    private class Bridge(opener: MediaByteOpener) : AutoCloseable {
        val module = fakeCodecModule()
        val nested: WebNestedOpener
        private val struct: Int
        val destination: Int
        init {
            useCodecModule(module)
            nested = WebNestedOpener(module, opener)
            struct = nested.writeStruct()
            destination = wasmAlloc(module, 70_001)
        }
        private fun callback(field: Int) = readInt32(module, struct + field)
        fun open(url: String = "https://media.example/segment"): JsAny =
            nestedProbeOpen(module, callback(OpenerLayout.openFn), url)
        fun read(source: Int, length: Int) = nestedProbeRead(module, callback(OpenerLayout.readFn), source, destination, length)
        fun seek(source: Int, offset: Long, whence: Int): Long =
            nestedProbeSeek(module, callback(OpenerLayout.seekFn), source, offset.toString(), whence).toLong()
        fun closeChild(source: Int) = nestedProbeClose(module, callback(OpenerLayout.closeFn), source)
        fun location(source: Int) = nestedProbeLocation(module, callback(OpenerLayout.locationFn), source)
        override fun close() {
            try { nested.release() } finally {
                wasmFree(module, destination)
                wasmFree(module, struct)
            }
        }
    }

    @Test fun aLargeChildOpensWithoutReadingOrClosingIt() {
        val source = Source()
        Bridge { source }.use { bridge ->
            val opened = bridge.open()
            assertEquals(0, nestedProbeStatus(opened))
            assertEquals(source.size.toString(), nestedProbeSize(opened))
            assertEquals(1, nestedProbeSeekable(opened))
            assertEquals(0, source.reads)
            assertTrue(source.seeks.isEmpty())
            assertEquals(0, source.closed)
            assertEquals(3, bridge.read(nestedProbeHandle(opened), 3))
            assertContentEquals(byteArrayOf(0, 1, 2), readBytes(bridge.module, bridge.destination, 3))
        }
        assertEquals(1, source.closed)
    }

    @Test fun readsAreBoundedAndByteExactAcrossTheScratchBoundary() {
        val source = Source(70_000L)
        Bridge { source }.use { bridge ->
            val handle = nestedProbeHandle(bridge.open())
            val first = bridge.read(handle, 70_001)
            assertEquals(65_536, first)
            assertContentEquals(ByteArray(first) { (it % 251).toByte() }, readBytes(bridge.module, bridge.destination, first))
            val second = bridge.read(handle, 70_001)
            assertEquals(70_000 - first, second)
            assertContentEquals(ByteArray(second) { ((first + it) % 251).toByte() }, readBytes(bridge.module, bridge.destination, second))
            assertEquals(-1, bridge.read(handle, 1))
            assertEquals(70_000L, source.bytes)
        }
    }

    @Test fun everyInt64SeekBitReachesTheSourceWithoutNumberRounding() {
        val size = 9_007_199_254_741_099L
        val source = Source(size)
        Bridge { source }.use { bridge ->
            val opened = bridge.open()
            assertEquals(size.toString(), nestedProbeSize(opened))
            val handle = nestedProbeHandle(opened)
            val high = 9_007_199_254_740_993L
            assertEquals(high, bridge.seek(handle, high, 0))
            assertEquals(high + 3, bridge.seek(handle, 3, 1))
            assertEquals(size - 7, bridge.seek(handle, -7, 2))
            assertEquals(listOf(high, high + 3, size - 7), source.seeks)
            assertEquals(1, bridge.read(handle, 1))
            assertEquals(((size - 7) % 251).toByte(), readBytes(bridge.module, bridge.destination, 1)[0])
        }
    }

    @Test fun anOverflowOrNegativeSeekNeverMovesTheSource() {
        val source = Source(Long.MAX_VALUE)
        Bridge { source }.use { bridge ->
            val handle = nestedProbeHandle(bridge.open())
            assertEquals(Long.MAX_VALUE, bridge.seek(handle, Long.MAX_VALUE, 0))
            assertEquals(-2L, bridge.seek(handle, 1, 1))
            assertEquals(-2L, bridge.seek(handle, Long.MIN_VALUE, 1))
            assertEquals(-2L, bridge.seek(handle, -1, 0))
            assertEquals(-2L, bridge.seek(handle, 0, 7))
            assertEquals(listOf(Long.MAX_VALUE), source.seeks)
        }
    }

    @Test fun sizeQueriesObserveGrowthAndUnknownSizeWithoutMovingTheCursor() {
        val source = Source(null)
        Bridge { source }.use { bridge ->
            val opened = bridge.open()
            assertEquals("-1", nestedProbeSize(opened))
            val handle = nestedProbeHandle(opened)
            assertEquals(-1L, bridge.seek(handle, 0, 0x10000))
            source.declared = 123L
            assertEquals(123L, bridge.seek(handle, 0, 0x10000))
            source.declared = 456L
            assertEquals(456L, bridge.seek(handle, 0, 0x10000))
            source.declared = null
            assertEquals(-1L, bridge.seek(handle, 0, 0x10000))
            assertTrue(source.seeks.isEmpty())
            assertEquals(1, bridge.read(handle, 1))
            assertEquals(0, readBytes(bridge.module, bridge.destination, 1)[0].toInt())
        }
    }

    @Test fun aForwardOnlyUnknownChildIsNeverSeeked() {
        val source = Source(null, seekable = false)
        Bridge { source }.use { bridge ->
            val opened = bridge.open()
            assertEquals(0, nestedProbeStatus(opened))
            assertEquals(0, nestedProbeSeekable(opened))
            assertEquals("-1", nestedProbeSize(opened))
            val handle = nestedProbeHandle(opened)
            assertEquals(-2L, bridge.seek(handle, 1, 0))
            assertEquals(8, bridge.read(handle, 8))
            assertTrue(source.seeks.isEmpty())
        }
    }

    @Test fun readAndSeekFailuresKeepTheirOriginalObjectsAndDoNotCloseEarly() {
        val source = Source()
        Bridge { source }.use { bridge ->
            val handle = nestedProbeHandle(bridge.open())
            val readFailure = IllegalStateException("read failure")
            source.readFailure = readFailure
            assertEquals(-2, bridge.read(handle, 1))
            assertSame(readFailure, bridge.nested.takeFailure())
            assertNull(bridge.nested.takeFailure())
            source.readFailure = null
            val seekFailure = IllegalStateException("seek failure")
            source.seekFailure = seekFailure
            assertEquals(-2L, bridge.seek(handle, 7, 0))
            assertSame(seekFailure, bridge.nested.takeFailure())
            assertEquals(0, source.closed)
        }
        assertEquals(1, source.closed)
    }

    @Test fun zeroAndOverCountReadsAreErrorsInsteadOfEofOrHeapCopies() {
        for (answer in listOf(0, 2)) {
            val source = Source().also { it.badRead = answer }
            Bridge { source }.use { bridge ->
                val handle = nestedProbeHandle(bridge.open())
                writeBytes(bridge.module, bridge.destination, byteArrayOf(99, 98), 2)
                assertEquals(-2, bridge.read(handle, 1))
                assertIs<IllegalStateException>(bridge.nested.takeFailure())
                assertContentEquals(byteArrayOf(99, 98), readBytes(bridge.module, bridge.destination, 2))
            }
        }
    }

    @Test fun failedMetadataClosesTheTransferredSourceOnceAndRetainsTheCause() {
        for (field in listOf("size", "location", "seekable")) {
            val thrown = IllegalStateException(field)
            val source = Source().also {
                if (field == "size") it.sizeFailure = thrown
                if (field == "location") it.locationFailure = thrown
            }
            val returned = if (field == "seekable") object : MediaByteSource by source {
                override val seekable: Boolean get() = throw thrown
            } else source
            Bridge { returned }.use { bridge ->
                assertEquals(-2, nestedProbeStatus(bridge.open()))
                assertSame(thrown, bridge.nested.takeFailure())
                assertEquals(1, source.closed)
                assertEquals(0, source.reads)
            }
            assertEquals(1, source.closed)
        }
    }

    @Test fun aLaterSizeFailureReachesTheDemuxErrorWithoutMovingOrClosingTheChild() {
        val source = Source()
        Bridge { source }.use { bridge ->
            val handle = nestedProbeHandle(bridge.open())
            val thrown = IllegalStateException("length request failed")
            source.sizeFailure = thrown
            assertEquals(-2L, bridge.seek(handle, 0, 0x10000))
            assertSame(thrown, bridge.nested.takeFailure())
            assertEquals(0, source.closed)
            assertTrue(source.seeks.isEmpty())
        }
    }

    @Test fun theRuntimeDefaultKeepsThePageFallbackAndUsesDemandReadsElsewhere() {
        WebIoBridge.readOnDemand = null
        val source = Source(17L)
        Bridge { source }.use { bridge ->
            assertEquals(0, nestedProbeStatus(bridge.open()))
            assertEquals(nestedProbeIsPage(), source.closed == 1)
            assertEquals(nestedProbeIsPage(), source.bytes == 17L)
        }
        assertEquals(1, source.closed)
    }

    @Test fun negativeSizeAndNulLocationAreRejectedWithoutReading() {
        for (source in listOf(Source(-1), Source().also { it.resolvedLocation = "https://example/a\u0000b" })) {
            Bridge { source }.use { bridge ->
                assertEquals(-2, nestedProbeStatus(bridge.open()))
                assertIs<IllegalArgumentException>(bridge.nested.takeFailure())
                assertEquals(0, source.reads)
            }
            assertEquals(1, source.closed)
        }
    }

    @Test fun aRedirectedChildRetainsItsUtf8LocationWhileItIsOpen() {
        val source = Source().also { it.resolvedLocation = "https://example/final/café/index.m3u8" }
        Bridge { source }.use { bridge ->
            val handle = nestedProbeHandle(bridge.open())
            assertEquals(source.resolvedLocation, bridge.location(handle))
            assertEquals(0, source.closed)
        }
    }

    @Test fun ffmpegCloseAndParentCloseReleaseEveryChildExactlyOnceEvenWhenCloseThrows() {
        val failure = IllegalStateException("first close")
        val first = Source().also { it.closeFailure = failure }
        val second = Source()
        var calls = 0
        val bridge = Bridge { if (calls++ == 0) first else second }
        val firstHandle = nestedProbeHandle(bridge.open())
        val secondHandle = nestedProbeHandle(bridge.open())
        assertTrue(firstHandle != secondHandle)
        bridge.closeChild(firstHandle)
        bridge.closeChild(firstHandle)
        assertEquals(1, first.closed)
        assertEquals(0, second.closed)
        assertSame(failure, assertFails { bridge.close() })
        bridge.nested.release()
        assertEquals(1, first.closed)
        assertEquals(1, second.closed)
        assertEquals(0, nestedProbeLiveCallbacks(bridge.module))
    }

    @Test fun aRefusedOrThrowingOpenerDoesNotCreateAChild() {
        Bridge { null }.use { bridge ->
            assertEquals(-3, nestedProbeStatus(bridge.open()))
            assertNull(bridge.nested.takeFailure())
        }
        val thrown = IllegalStateException("open failed")
        Bridge { throw thrown }.use { bridge ->
            assertEquals(-2, nestedProbeStatus(bridge.open()))
            assertSame(thrown, bridge.nested.takeFailure())
        }
    }

    @Test fun partialCallbackInstallationRemovesEveryEntryAlreadyAcquired() {
        for (failureAt in 1..5) {
            val module = fakeCodecModule()
            nestedProbeFailInstallation(module, failureAt)
            assertFails { WebNestedOpener(module) { error("must not open while installing") } }
            assertEquals(0, nestedProbeLiveCallbacks(module), "registration $failureAt leaked an earlier callback")
        }
    }

    @Test fun thePageFallbackStillStagesFiniteUnknownSourcesAndClosesThemImmediately() {
        WebIoBridge.readOnDemand = false
        val source = Source(null, seekable = false, end = 17)
        Bridge { source }.use { bridge ->
            val opened = bridge.open()
            assertEquals(0, nestedProbeStatus(opened))
            assertEquals("17", nestedProbeSize(opened))
            assertEquals(1, nestedProbeSeekable(opened))
            assertEquals(17L, source.bytes)
            assertEquals(1, source.closed)
            assertEquals(17, bridge.read(nestedProbeHandle(opened), 20))
            assertContentEquals(ByteArray(17) { it.toByte() }, readBytes(bridge.module, bridge.destination, 17))
        }
        assertEquals(1, source.closed)
    }

    @Test fun thePageFallbackStillRefusesLargeSourcesBeforeReading() {
        WebIoBridge.readOnDemand = false
        val source = Source()
        Bridge { source }.use { bridge ->
            assertEquals(-2, nestedProbeStatus(bridge.open()))
            assertIs<FFmpegError.Unsupported>(assertIs<FFmpegException>(bridge.nested.takeFailure()).error)
            assertEquals(0, source.reads)
            assertEquals(1, source.closed)
        }
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("""(m, callback, url) => {
    const encoded = new TextEncoder().encode(url);
    const pointer = m._malloc(encoded.length + 1), out = m._malloc(24);
    try {
        m.HEAPU8.set(encoded, pointer); m.HEAPU8[pointer + encoded.length] = 0;
        const status = m.__table[callback](0, pointer, out, out + 8, out + 16);
        return { status, handle: m.HEAP32[out >> 2],
            size: new DataView(m.HEAPU8.buffer).getBigInt64(out + 8, true).toString(),
            seekable: m.HEAP32[(out + 16) >> 2] };
    } finally { m._free(pointer); m._free(out); }
}""")
private external fun nestedProbeOpen(module: JsAny, callback: Int, url: String): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(s) => s.status")
private external fun nestedProbeStatus(state: JsAny): Int
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(s) => s.handle")
private external fun nestedProbeHandle(state: JsAny): Int
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(s) => s.size")
private external fun nestedProbeSize(state: JsAny): String
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(s) => s.seekable")
private external fun nestedProbeSeekable(state: JsAny): Int
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, fn, handle, destination, length) => m.__table[fn](handle, destination, length)")
private external fun nestedProbeRead(module: JsAny, callback: Int, handle: Int, destination: Int, length: Int): Int
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, fn, handle, offset, whence) => m.__table[fn](handle, BigInt(offset), whence).toString()")
private external fun nestedProbeSeek(module: JsAny, callback: Int, handle: Int, offset: String, whence: Int): String
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, fn, handle) => { m.__table[fn](0, handle); }")
private external fun nestedProbeClose(module: JsAny, callback: Int, handle: Int)
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("""(m, fn, handle) => {
    const length = m.__table[fn](0, handle, 0, 0), buffer = m._malloc(length + 1);
    try { m.__table[fn](0, handle, buffer, length + 1); return m.UTF8ToString(buffer); }
    finally { m._free(buffer); }
}""")
private external fun nestedProbeLocation(module: JsAny, callback: Int, handle: Int): String
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__table.slice(1).filter(x => x !== null).length")
private external fun nestedProbeLiveCallbacks(module: JsAny): Int
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("""(m, failureAt) => {
    const add = m.addFunction.bind(m); let attempts = 0;
    m.addFunction = (fn, signature) => {
        if (++attempts === failureAt) throw new Error('nested callback registration failed');
        return add(fn, signature);
    };
}""")
private external fun nestedProbeFailInstallation(module: JsAny, failureAt: Int)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => typeof window !== 'undefined'")
private external fun nestedProbeIsPage(): Boolean
