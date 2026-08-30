package io.github.yuroyami.kiteffmpeg

import kotlin.js.JsAny

/**
 * Hands a [MediaByteSource]'s bytes to FFmpeg through the callbacks it expects (17.14 X-06).
 *
 * FFmpeg's IO is synchronous: it calls read and seek and expects an answer before it returns. On
 * the browser's main thread nothing may block, so this bridge does the one thing that is both
 * correct and available today. It drains the source into the codec module's memory ONCE, and the
 * read and seek callbacks are pure JavaScript over that buffer, answering instantly and blocking
 * nothing.
 *
 * That is not a workaround for the common case, it is the shape of the data: a browser gets media
 * from a `File`, a `fetch` response or an `ArrayBuffer`, and all three are already whole. What it
 * does NOT support is a source larger than memory or one served by range requests, which needs the
 * Worker of X-08 where a blocking read is legal. Refused explicitly below rather than half-served.
 */
internal class WebIoBridge private constructor(
    private val module: JsAny,
    private val buffer: Int,
    val readPointer: Int,
    val seekPointer: Int,
) {

    fun release() {
        releaseCallbacks(module, readPointer, seekPointer)
        wasmFree(module, buffer)
    }

    companion object {
        /** Sources above this are refused rather than silently doubling the page's memory use. */
        private const val MAX_BYTES = 512L * 1024 * 1024

        /**
         * Stages [io] and takes ownership of it.
         *
         * The bridge closes the source on EVERY path, exactly once, because staging consumes it
         * whole: on return there is nothing left for a caller to read, and on a throw there is no
         * caller who could know how far it got. `MediaByteSource` promises close runs exactly once
         * and this backend used to never call it at all.
         */
        fun install(io: MediaByteSource): WebIoBridge {
            val bridge = try {
                stage(io)
            } catch (failure: Throwable) {
                // The real cause is already on its way up. A close that also fails here has nothing
                // to add and must not replace it; Kotlin common has no addSuppressed to chain them.
                runCatching { io.close() }
                throw failure
            }
            try {
                io.close()
            } catch (failure: Throwable) {
                // A source whose close throws is the source's defect, but the bridge must not leak
                // its registered callbacks and staging buffer over it.
                bridge.release()
                throw failure
            }
            return bridge
        }

        private fun stage(io: MediaByteSource): WebIoBridge {
            val module = requireModule()
            val size = io.size
                ?: throw FFmpegException(
                    FFmpegError.Unsupported(
                        0,
                        "The web backend needs a MediaByteSource that knows its size, because it " +
                            "stages the bytes for FFmpeg's synchronous IO. A source of unknown " +
                            "length has to stream, which needs the Worker (PLANNING.md).",
                    ),
                )
            if (size > MAX_BYTES) {
                throw FFmpegException(
                    FFmpegError.Unsupported(
                        0,
                        "This media is $size bytes and the web backend stages the whole source in " +
                            "memory, so it caps at $MAX_BYTES. Streaming larger media needs the " +
                            "Worker (PLANNING.md).",
                    ),
                )
            }
            val total = size.toInt()
            val buffer = wasmAlloc(module, total)
            if (buffer == 0) throw FFmpegException(FFmpegError.Internal("could not stage $total bytes"))
            try {
                drain(io, module, buffer, total)
            } catch (failure: Throwable) {
                wasmFree(module, buffer)
                throw failure
            }
            val callbacks = installCallbacks(module, buffer, total)
            return WebIoBridge(
                module = module,
                buffer = buffer,
                readPointer = callbackRead(callbacks),
                seekPointer = callbackSeek(callbacks),
            )
        }

        /** Copies the source in chunks rather than one huge Kotlin array beside the wasm copy. */
        private fun drain(io: MediaByteSource, module: JsAny, buffer: Int, total: Int) {
            val chunk = ByteArray(CHUNK)
            var written = 0
            // Only a source that says it is seekable may be rewound. `MediaByteSource` promises
            // seek is never called otherwise, and a non-seekable source that is mid-stream is
            // CORRUPTED by a rewind rather than merely unhelped by one; it stages from where it is.
            if (io.seekable) io.seek(0)
            while (written < total) {
                val want = minOf(CHUNK, total - written)
                val got = io.read(chunk, 0, want)
                if (got <= 0) {
                    throw FFmpegException(
                        FFmpegError.InvalidData(0, "the byte source ended at $written of $total bytes"),
                    )
                }
                writeBytes(module, buffer + written, chunk, got)
                written += got
            }
        }

        private const val CHUNK = 1 shl 16
    }
}

/**
 * Copies [length] bytes of [bytes] into codec memory at [pointer], in ONE crossing.
 *
 * This used to cross into JavaScript once per BYTE, so staging a 200 MB file made 200 million
 * calls. Kotlin/Wasm and the codec are separate modules with separate memories, so the bytes have
 * to travel as a JS value; they travel as a string of code units 0..255, and the loop that writes
 * them into the heap runs JS-side where it is one tight loop over a typed array.
 *
 * Latin-1 by construction, never text: every byte becomes exactly one code unit below 0x100, so
 * nothing is ever in the surrogate range and no encoding step can be tempted to reinterpret it.
 * `WebIoBridgeTest` stages a 0..255 ramp across a chunk boundary precisely to hold this true; an
 * ASCII fixture would pass while the high half of every byte value was mangled.
 */
internal fun writeBytes(module: JsAny, pointer: Int, bytes: ByteArray, length: Int) {
    if (length <= 0) return
    val packed = StringBuilder(length)
    for (i in 0 until length) packed.append(((bytes[i].toInt()) and 0xFF).toChar())
    writeChunk(module, pointer, packed.toString())
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, p, s) => { const n = s.length; for (let i = 0; i < n; i++) m.HEAPU8[p + i] = s.charCodeAt(i); }")
private external fun writeChunk(module: JsAny, pointer: Int, packed: String)

/**
 * Registers the two callbacks in the codec module's function table and returns both indices.
 *
 * Written in JavaScript on purpose. These close over a buffer that lives in the module's memory and
 * are called BY the module, synchronously, from inside `avformat_open_input`. Routing them back
 * through Kotlin would add a second module crossing to every read for no gain, and Kotlin/Wasm
 * cannot be handed to `addFunction` as a raw table entry anyway.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m, base, total) => {
        let pos = 0;
        const read = m.addFunction((opaque, dst, len) => {
            if (pos >= total) return -541478725;
            const n = Math.min(len, total - pos);
            m.HEAPU8.copyWithin(dst, base + pos, base + pos + n);
            pos += n;
            return n;
        }, 'iiii');
        const seek = m.addFunction((opaque, offset, whence) => {
            const off = Number(offset), w = Number(whence);
            if (w === 0x10000) return BigInt(total);
            if (w === 0) pos = off;
            else if (w === 1) pos += off;
            else if (w === 2) pos = total + off;
            else return -1n;
            if (pos < 0) pos = 0;
            if (pos > total) pos = total;
            return BigInt(pos);
        }, 'jiji');
        return { read, seek };
    }"""
)
private external fun installCallbacks(module: JsAny, buffer: Int, total: Int): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(c) => c.read")
private external fun callbackRead(callbacks: JsAny): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(c) => c.seek")
private external fun callbackSeek(callbacks: JsAny): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, r, s) => { m.removeFunction(r); m.removeFunction(s); }")
private external fun releaseCallbacks(module: JsAny, read: Int, seek: Int)
