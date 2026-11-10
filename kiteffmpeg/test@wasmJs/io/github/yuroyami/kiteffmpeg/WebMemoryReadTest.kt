package io.github.yuroyami.kiteffmpeg

import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Bytes copied out of codec memory cross into Kotlin per chunk, not per byte (#209).
 *
 * The module here is a heap whose `HEAPU8` getter counts its reads, so each crossing is counted:
 * the per-byte copy read it once for every byte.
 */
class WebMemoryReadTest {
    @OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
    @Test
    fun everyByteValueSurvivesAReadAcrossAChunkBoundaryInFewCrossings() {
        val length = READ_CHUNK + 300
        val module = countingHeap(length + 16)
        fillRamp(module, 8, length)
        val read = readBytes(module, 8, length)
        val expected = ByteArray(length) { (it and 0xFF).toByte() }
        assertContentEquals(expected, read, "a byte above 0x7F or across the chunk boundary came back changed")
        val crossings = heapReads(module)
        assertTrue(crossings <= 4, "copying $length bytes read the heap $crossings times")
    }

    @OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
    @Test
    fun aReadIntoTheStartOfALongerArrayLeavesTheRestAlone() {
        val module = countingHeap(64)
        fillRamp(module, 0, 64)
        val destination = ByteArray(10) { -1 }
        readBytesInto(module, 250 % 64, destination, 6)
        assertContentEquals(byteArrayOf(58, 59, 60, 61, 62, 63, -1, -1, -1, -1), destination)
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(n) => {
        const heap = new Uint8Array(n);
        const m = { reads: 0 };
        Object.defineProperty(m, 'HEAPU8', { get() { m.reads++; return heap; } });
        m.raw = heap;
        return m;
    }""",
)
private external fun countingHeap(bytes: Int): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, p, n) => { for (let i = 0; i < n; i++) m.raw[p + i] = i & 0xFF; }")
private external fun fillRamp(module: JsAny, pointer: Int, length: Int)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m) => m.reads")
private external fun heapReads(module: JsAny): Int
