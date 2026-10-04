package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A codec module out of memory answers an allocation with zero, and zero is a real address in its
 * memory. Every allocation the web backend makes must refuse it with [FFmpegError.OutOfMemory]
 * before anything is written there or read from there, and must give back what it took before the
 * one that failed (#142).
 */
class WebAllocationFailureTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    private class Source(private val bytes: ByteArray) : MediaByteSource {
        var closes = 0
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

        override fun close() {
            closes++
        }
    }

    private fun assertOutOfMemory(block: () -> Unit) {
        val thrown = assertFailsWith<FFmpegException> { block() }
        assertIs<FFmpegError.OutOfMemory>(thrown.error, thrown.message)
    }

    @Test
    fun aFailedAllocationThrowsAndWritesNothingAtAddressZero() {
        val module = withFailingAllocations(fakeCodecModule())
        useCodecModule(module)
        failAllocationsFrom(module, 1)
        assertOutOfMemory { wasmAlloc(module, 16) }
        assertOutOfMemory { allocCString(module, "mpeg4") }
        var ran = false
        assertOutOfMemory { withCString("rgba") { ran = true } }
        assertFalse(ran, "the body ran with no string to read")
        assertTrue(lowMemoryIntact(module), "something was written at address zero")
        assertEquals(0, liveAllocations(module))
    }

    @Test
    fun aRequestForNothingStillGetsAnAddressOfItsOwn() {
        val module = withFailingAllocations(fakeCodecModule())
        useCodecModule(module)
        val pointer = wasmAlloc(module, 0)
        assertTrue(pointer != 0)
        wasmFree(module, pointer)
        assertOutOfMemory { wasmAlloc(module, -1) }
    }

    /**
     * Fails each allocation of an open with options, a URL and a MIME type, then of reading the
     * container model, in turn. Whichever one fails, the caller sees OutOfMemory or a finished
     * read, nothing lands at address zero, the source is closed once, and every allocation and
     * callback is given back.
     */
    @Test
    fun anOpenThatRunsOutAnywhereGivesBackEverythingItTook() = runTest {
        val allocations = run(failFrom = null) ?: fail("the clean run failed")
        assertTrue(allocations >= 8, "the clean run made only $allocations allocations")
        for (failFrom in 1..allocations) run(failFrom)
    }

    /** One open and model read, failing from allocation [failFrom]. Returns how many it made. */
    private fun run(failFrom: Int?): Int? {
        val module = withFailingAllocations(fakeModelCodecModule())
        useCodecModule(module)
        // Counted from here, so what attaching the module took is the baseline, not a leak.
        failAllocationsFrom(module, failFrom ?: Int.MAX_VALUE)
        val liveBefore = liveAllocations(module)
        val tableBefore = liveTableEntries(module)
        val source = Source(ByteArray(64) { it.toByte() })
        val at = "failing from allocation $failFrom"
        val media = try {
            MediaSource.open(
                source,
                mapOf("probesize" to "32", "analyzeduration" to "0"),
                url = "memory://clip.mkv",
                mimeType = "video/x-matroska",
            )
        } catch (thrown: FFmpegException) {
            assertIs<FFmpegError.OutOfMemory>(thrown.error, "$at: ${thrown.message}")
            null
        }
        if (media != null) {
            try {
                media.streams.forEach { it.toString() }
                media.chapters.forEach { it.toString() }
                media.metadata.size
            } catch (thrown: FFmpegException) {
                assertIs<FFmpegError.OutOfMemory>(thrown.error, "$at: ${thrown.message}")
            } finally {
                media.close()
            }
        }
        assertEquals(1, source.closes, "$at: source closes")
        assertTrue(lowMemoryIntact(module), "$at: something was written at address zero")
        assertEquals(liveBefore, liveAllocations(module), "$at: allocations never given back")
        assertEquals(tableBefore, liveTableEntries(module), "$at: callbacks never removed")
        return if (media != null && failFrom == null) allocationCalls(module) else null
    }

    @Test
    fun aFrameOutOfMemoryReadsNoCaptionsFromAddressZero() {
        val module = withCaptionedFrame(withFailingAllocations(fakeCodecModule()))
        useCodecModule(module)
        val frame = Frame(0x900, 0, MediaType.Video, Rational.of(1, 25))
        try {
            assertEquals(6, frame.closedCaptions()?.size, "the clean read")
            failAllocationsFrom(module, 1)
            assertOutOfMemory { frame.closedCaptions() }
            assertEquals(0, captionCopiesIntoZero(module), "the captions were asked for at address zero")
            assertTrue(lowMemoryIntact(module), "something was written at address zero")
        } finally {
            frame.close()
        }
    }
}

/**
 * Wraps [module]'s allocator so a test can make it fail from the Nth call on, and counts what was
 * taken and not given back. Address zero to seven are filled with a pattern a test checks, which
 * the fake's own allocator never hands out.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m) => {
        const real = m._malloc;
        const live = new Set();
        let calls = 0;
        let failFrom = Infinity;
        m._malloc = (n) => {
            calls++;
            if (calls >= failFrom) return 0;
            const p = real(n);
            live.add(p);
            return p;
        };
        m._free = (p) => { live.delete(p); };
        m.__failFrom = (k) => { failFrom = k; calls = 0; };
        m.__calls = () => calls;
        m.__live = () => live.size;
        for (let i = 0; i < 8; i++) m.HEAPU8[i] = 0xA5;
        return m;
    }""",
)
private external fun withFailingAllocations(module: JsAny): JsAny

/** Makes allocation [n] and every one after it fail, counting from the next one as the first. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, n) => m.__failFrom(n)")
private external fun failAllocationsFrom(module: JsAny, n: Int)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__calls()")
private external fun allocationCalls(module: JsAny): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__live()")
private external fun liveAllocations(module: JsAny): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => { for (let i = 0; i < 8; i++) if (m.HEAPU8[i] !== 0xA5) return false; return true; }")
private external fun lowMemoryIntact(module: JsAny): Boolean

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__table.filter((f) => f !== null && f !== undefined).length")
private external fun liveTableEntries(module: JsAny): Int

/**
 * Gives [module] one frame that carries six bytes of captions. Its reader answers a size when
 * handed a null destination, as the C helper does, so a copy asked for at address zero is the
 * bug; the fake counts those.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m) => {
        const captions = [0xFC, 0x94, 0x20, 0xFC, 0x94, 0xAE];
        let copiesIntoZero = 0;
        m._ffkmp_frame_a53_cc = (f, buf, cap) => {
            if (buf === 0) {
                if (cap > 0) copiesIntoZero++;
                return captions.length;
            }
            if (cap < captions.length) return -22;
            m.HEAPU8.set(captions, buf);
            return captions.length;
        };
        m._ffkmp_frame_free = () => {};
        m.__captionCopiesIntoZero = () => copiesIntoZero;
        return m;
    }""",
)
private external fun withCaptionedFrame(module: JsAny): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.__captionCopiesIntoZero()")
private external fun captionCopiesIntoZero(module: JsAny): Int
