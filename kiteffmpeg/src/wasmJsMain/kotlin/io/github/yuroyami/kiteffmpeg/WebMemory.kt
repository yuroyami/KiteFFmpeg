package io.github.yuroyami.kiteffmpeg

import kotlin.js.JsAny

/*
 * The four things Kotlin needs to do to the codec module's memory, and nothing more.
 *
 * Kotlin/Wasm and the codec are separate wasm modules with separate linear memories, so every one
 * of these crosses through JS. They are small on purpose: the binding stays a set of calls over
 * opaque Int addresses, and this file is the only place that treats an address as memory.
 */

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, n) => m._malloc(n)")
internal external fun wasmAlloc(module: JsAny, bytes: Int): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, p) => m._free(p)")
internal external fun wasmFree(module: JsAny, pointer: Int)

/** One 32-bit signed value at [pointer], which must be 4-byte aligned as every struct field is. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, p) => m.HEAP32[p >> 2]")
internal external fun readInt32(module: JsAny, pointer: Int): Int

/**
 * A NUL-terminated string at [pointer], bounded by [limit] bytes.
 *
 * Bounded because these read fixed-size `char[]` fields: an unterminated field would otherwise run
 * into the next one and return it as part of the value.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, p, n) => { const b = m.HEAPU8.subarray(p, p + n); const z = b.indexOf(0); return new TextDecoder().decode(z < 0 ? b : b.subarray(0, z)); }")
internal external fun readFixedString(module: JsAny, pointer: Int, limit: Int): String

/** Copies [text] into codec memory as a NUL-terminated C string, runs [body], then frees it. */
internal fun <R> withCString(text: String, body: (Int) -> R): R {
    val module = requireModule()
    val pointer = allocCString(module, text)
    try {
        return body(pointer)
    } finally {
        wasmFree(module, pointer)
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, s) => { const n = m.lengthBytesUTF8(s) + 1; const p = m._malloc(n); m.stringToUTF8(s, p, n); return p; }")
internal external fun allocCString(module: JsAny, text: String): Int

/** Copies [length] bytes out of codec memory into a Kotlin array. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
internal fun readBytes(module: JsAny, pointer: Int, length: Int): ByteArray {
    val out = ByteArray(length)
    for (i in 0 until length) out[i] = readByte(module, pointer + i).toByte()
    return out
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, p) => m.HEAPU8[p]")
internal external fun readByte(module: JsAny, pointer: Int): Int

/** The FFmpeg name of a pixel or sample format, which is what the Kotlin value classes hold. */
internal fun pixelFormatOf(module: JsAny, id: Int): PixelFormat =
    utf8OrNull(module, io.github.yuroyami.kiteffmpeg.wasm.ffkmp_pix_fmt_name(module, id))
        ?.let { PixelFormat(it) } ?: PixelFormat.None

internal fun sampleFormatOf(module: JsAny, id: Int): SampleFormat =
    utf8OrNull(module, io.github.yuroyami.kiteffmpeg.wasm.ffkmp_sample_fmt_name(module, id))
        ?.let { SampleFormat(it) } ?: SampleFormat.None

/** Writes one 32-bit value at [pointer], which must be 4-byte aligned. */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, p, v) => { m.HEAP32[p >> 2] = v; }")
internal external fun writeInt32(module: JsAny, pointer: Int, value: Int)

/**
 * One 64-bit signed value at [pointer], for the out-slots the C side fills with timestamps.
 *
 * A `DataView` rather than a `BigInt64Array` view: the typed array constructor demands 8-byte
 * alignment and throws when it does not get it, while a DataView reads any offset. The values here
 * come from FFmpeg structs that are aligned in practice, and paying nothing to stop caring is the
 * better trade.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, p) => new DataView(m.HEAPU8.buffer).getBigInt64(p, true)")
internal external fun readInt64(module: JsAny, pointer: Int): Long
