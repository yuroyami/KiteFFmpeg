package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_io_tag
import kotlin.js.JsAny

/**
 * Hands a [MediaByteSource]'s bytes to FFmpeg through the callbacks it expects, in one of two ways.
 *
 * FFmpeg's IO is synchronous: it calls read and seek and expects an answer before it returns.
 *
 * - **On demand**, wherever a read may block: in a Worker, and in Node. FFmpeg's read and seek call
 *   the source there and then, so a source that answers with a synchronous range request plays
 *   after its first bytes, memory does not grow with the file, and there is no size cap (#133). The
 *   source stays open until the media source closes, as on the JVM and native, a source that does
 *   not know its size streams, and one that cannot seek makes an input that cannot either. The
 *   source is asked for [MediaByteSource.takeTags] after each read that brought bytes, as there.
 * - **Staged**, on a page's main thread, where nothing may block. The bridge drains the source into
 *   the codec module's memory once, closes it, and the read and seek callbacks are pure JavaScript
 *   over that buffer, answering instantly. A browser gets media there from a `File`, a `fetch`
 *   response or an `ArrayBuffer`, which are all whole already. A source larger than
 *   [MAX_BYTES], or of unknown size, is refused explicitly rather than half served. The source is
 *   asked for its tags as it drains, and each answer waits at the first byte of the read that
 *   brought it: FFmpeg's reads stop at that byte, and the read that starts there hands it the tags,
 *   every time a read starts there (#168).
 */
internal class WebIoBridge private constructor(
    private val module: JsAny,
    /** The staged bytes, or 0 when the source is read on demand. */
    private val buffer: Int,
    val readPointer: Int,
    /** 0 when FFmpeg may not seek, which the C side takes for an input that cannot seek. */
    val seekPointer: Int,
    /** 0 when FFmpeg is asked for no tags: a staged source whose reads brought none (#168). */
    val tagsPointer: Int,
    /**
     * What FFmpeg is told the size is: the staged byte count, or the source's own size, -1 when it
     * has none. A staged source is closed by then, so nothing may ask it again (#114).
     */
    val size: Long,
    /** The reader of an on-demand source, which closes it at [release]. Null when staged. */
    private val reader: OnDemandReader?,
) {

    /** The exception the source threw inside a read or seek FFmpeg made, which explains the error it got. */
    fun takeFailure(): Throwable? = reader?.takeFailure()

    /** Removes the callbacks, frees the staged bytes, and closes a source read on demand. */
    fun release() {
        releaseCallbacks(module, readPointer, seekPointer, tagsPointer)
        if (buffer != 0) wasmFree(module, buffer)
        reader?.io?.close()
    }

    companion object {
        /** Staged sources above this are refused rather than silently doubling the page's memory use. */
        internal const val MAX_BYTES = 512L * 1024 * 1024

        /**
         * Which way [install] reads a source when its caller does not say: true on demand, false
         * staged, and null to ask the runtime, which is what every caller but a test does.
         */
        internal var readOnDemand: Boolean? = null

        /**
         * Takes ownership of [io] and hands it to FFmpeg, on demand where a read may block and
         * staged where it may not; see the class.
         */
        fun install(io: MediaByteSource, onDemand: Boolean = readOnDemand ?: blockingReadsAllowed()): WebIoBridge =
            if (onDemand) attach(io) else stageAndClose(io)

        /**
         * Lets FFmpeg read [io] as it goes. The bridge owns the source from here and closes it at
         * [release], so every failure before the bridge exists closes it here, once.
         */
        private fun attach(io: MediaByteSource): WebIoBridge {
            val module = requireModule()
            try {
                val seekable = io.seekable
                val declared = io.size
                if (declared != null && declared < 0) {
                    throw FFmpegException(FFmpegError.Io(0, "the byte source reported a size of $declared bytes"))
                }
                val size = declared ?: -1L
                val reader = OnDemandReader(module, io)
                val callbacks = installOnDemandCallbacks(
                    module,
                    seekable,
                    { destination, length -> reader.read(destination, length) },
                    { offset, whence -> reader.seek(offset, whence) },
                    { tags -> reader.tags(tags) },
                )
                return WebIoBridge(
                    module, 0, callbackRead(callbacks), callbackSeek(callbacks), callbackTags(callbacks), size, reader,
                )
            } catch (failure: Throwable) {
                // The real cause is already on its way up; a close that also fails rides on it.
                try {
                    io.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }

        /**
         * Stages [io] and takes ownership of it.
         *
         * The bridge closes the source on EVERY path, exactly once, because staging consumes it
         * whole: on return there is nothing left for a caller to read, and on a throw there is no
         * caller who could know how far it got. `MediaByteSource` promises close runs exactly once
         * and this backend used to never call it at all.
         */
        private fun stageAndClose(io: MediaByteSource): WebIoBridge {
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
                        "On a page's main thread the web backend needs a MediaByteSource that " +
                            "knows its size, because it stages the bytes for FFmpeg's synchronous " +
                            "IO. A source of unknown length streams in a Worker, where it is read " +
                            "on demand.",
                    ),
                )
            if (size < 0) {
                throw FFmpegException(FFmpegError.Io(0, "the byte source reported a size of $size bytes"))
            }
            if (size > MAX_BYTES) {
                throw FFmpegException(
                    FFmpegError.Unsupported(
                        0,
                        "This media is $size bytes and on a page's main thread the web backend " +
                            "stages the whole source in memory, so it caps at $MAX_BYTES. In a " +
                            "Worker the source is read on demand, with no cap.",
                    ),
                )
            }
            val total = size.toInt()
            val buffer = wasmAlloc(module, total)
            val callbacks = try {
                val marks = drain(io, module, buffer, total)
                val stops = newOffsetList()
                marks.keys.forEach { addOffset(stops, it) }
                installCallbacks(module, buffer, total, stops) { tags, at ->
                    marks[at]?.let { handTagsOrFail(module, tags, it) } ?: 0
                }
            } catch (failure: Throwable) {
                wasmFree(module, buffer)
                throw failure
            }
            return WebIoBridge(
                module = module,
                buffer = buffer,
                readPointer = callbackRead(callbacks),
                seekPointer = callbackSeek(callbacks),
                tagsPointer = callbackTags(callbacks),
                size = total.toLong(),
                reader = null,
            )
        }

        /**
         * Copies the source in chunks rather than one huge Kotlin array beside the wasm copy, and
         * returns the tags its reads brought, each at the offset where the read that brought it
         * started, in byte order.
         */
        private fun drain(io: MediaByteSource, module: JsAny, buffer: Int, total: Int): Map<Int, Array<String>> {
            val chunk = ByteArray(CHUNK)
            val marks = LinkedHashMap<Int, Array<String>>()
            var written = 0
            // Only a source that says it is seekable may be rewound. `MediaByteSource` promises
            // seek is never called otherwise, and a non-seekable source that is mid-stream is
            // CORRUPTED by a rewind rather than merely unhelped by one; it stages from where it is.
            if (io.seekable) callSource { io.seek(0) }
            while (written < total) {
                val want = minOf(CHUNK, total - written)
                val got = callSource { io.read(chunk, 0, want) }
                // Checked before the write: the staging block ends at total, and this chunk at want.
                if (got > want) {
                    throw FFmpegException(
                        FFmpegError.Io(0, "the byte source failed while its bytes were staged"),
                        byteSourceOverCount(got, want),
                    )
                }
                if (got <= 0) {
                    throw FFmpegException(
                        FFmpegError.InvalidData(0, "the byte source ended at $written of $total bytes"),
                    )
                }
                writeBytes(module, buffer + written, chunk, got)
                callSource { io.takeTags() }?.ffmpegTagPairs()?.let { marks[written] = it }
                written += got
            }
            return marks
        }

        /** [handTags] on the tags_fn contract: 0, or KC_IO_ERR when FFmpeg refused a pair or memory ran out. */
        private fun handTagsOrFail(module: JsAny, tags: Int, pairs: Array<String>): Int = try {
            if (handTags(module, tags, pairs)) 0 else KC_IO_ERR
        } catch (failure: Throwable) {
            KC_IO_ERR
        }

        /**
         * Runs one call into the caller's source. An exception it throws becomes the cause of an
         * I/O error, which is what the JVM and native backends report when their source fails.
         */
        private inline fun <T> callSource(call: () -> T): T = try {
            call()
        } catch (failure: Throwable) {
            throw FFmpegException(FFmpegError.Io(0, "the byte source failed while its bytes were staged"), failure)
        }

        private const val CHUNK = 1 shl 16
        private const val KC_IO_ERR = -2
    }
}

/**
 * Hands FFmpeg [pairs], keys and values in turn, as the tags of the read it just made, through the
 * [tags] handle its tags_fn was given (#168). False when FFmpeg refused one, which can only be for
 * want of memory, because [ffmpegTagPairs] leaves no empty key and no NUL. Throws when codec memory
 * for a string runs out.
 */
private fun handTags(module: JsAny, tags: Int, pairs: Array<String>): Boolean {
    var index = 0
    while (index + 1 < pairs.size) {
        val rc = withCString(module, pairs[index]) { key ->
            withCString(module, pairs[index + 1]) { value -> ffkmp_io_tag(module, tags, key, value) }
        }
        if (rc < 0) return false
        index += 2
    }
    return true
}

/**
 * Reads [io] for FFmpeg one request at a time, called by the module from inside its own read and
 * seek, on the contract the native trampolines keep: a read returns its byte count, KC_IO_EOF at
 * the end and KC_IO_ERR on a failure, and a seek returns the new position or KC_IO_ERR. An
 * exception must never travel back into the module, so each one is kept for the error it causes.
 */
private class OnDemandReader(private val module: JsAny, val io: MediaByteSource) {
    private val scratch = ByteArray(CHUNK)
    private var position = 0L
    private var failure: Throwable? = null

    fun takeFailure(): Throwable? = failure.also { failure = null }

    fun read(destination: Int, length: Int): Int = try {
        if (length <= 0) {
            KC_IO_ERR
        } else {
            val want = minOf(length, scratch.size)
            val got = io.read(scratch, 0, want)
            when {
                // Checked before the copy: FFmpeg's buffer holds length bytes and the scratch want.
                got > want -> {
                    failure = byteSourceOverCount(got, want)
                    KC_IO_ERR
                }
                got > 0 -> {
                    writeBytes(module, destination, scratch, got)
                    position += got
                    got
                }
                got < 0 -> KC_IO_EOF
                // 0 breaks the block-or-end contract of MediaByteSource.read.
                else -> KC_IO_ERR
            }
        }
    } catch (thrown: Throwable) {
        failure = thrown
        KC_IO_ERR
    }

    /**
     * Hands FFmpeg the tags the last read brought, on the contract the native tags_fn keeps: 0, or
     * KC_IO_ERR, which fails that read. An exception from [MediaByteSource.takeTags] is kept for the
     * error it causes, as one from [read] is.
     */
    fun tags(tags: Int): Int = try {
        val pairs = io.takeTags()?.ffmpegTagPairs()
        if (pairs == null || handTags(module, tags, pairs)) 0 else KC_IO_ERR
    } catch (thrown: Throwable) {
        failure = thrown
        KC_IO_ERR
    }

    /** Offsets cross as JavaScript numbers, which hold every byte position below 8 PiB exactly. */
    fun seek(offset: Double, whence: Int): Double = try {
        val target = when (whence) {
            // The current size, without moving, so a source that grows answers what it holds now (#177).
            SEEK_SIZE -> return io.size?.toDouble() ?: -1.0
            SEEK_SET -> offset.toLong()
            SEEK_CUR -> position + offset.toLong()
            SEEK_END -> io.size?.let { it + offset.toLong() } ?: -1L
            else -> -1L
        }
        if (target < 0) {
            KC_IO_ERR.toDouble()
        } else {
            io.seek(target)
            position = target
            target.toDouble()
        }
    } catch (thrown: Throwable) {
        failure = thrown
        KC_IO_ERR.toDouble()
    }

    private companion object {
        const val CHUNK = 1 shl 16
        const val KC_IO_EOF = -1
        const val KC_IO_ERR = -2
        const val SEEK_SET = 0
        const val SEEK_CUR = 1
        const val SEEK_END = 2

        /** FFmpeg's `AVSEEK_SIZE`: asks for the size and moves nothing. */
        const val SEEK_SIZE = 0x10000
    }
}

/**
 * True where a read may block the thread it runs on: anywhere but a page's main thread, which is
 * the one place with a `window`. A Worker has none, and neither has Node.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => typeof window === 'undefined'")
private external fun blockingReadsAllowed(): Boolean

/**
 * Registers read, seek and tags callbacks that call [read], [seek] and [tags] in Kotlin, and returns
 * the three table indices. The seek index is 0, which the C side reads as no seek, when the source
 * cannot seek.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m, seekable, read, seek, tags) => {
        const r = m.addFunction((opaque, dst, len) => read(dst, len), 'iiii');
        const s = seekable
            ? m.addFunction((opaque, offset, whence) => BigInt(seek(Number(offset), whence)), 'jiji')
            : 0;
        const t = m.addFunction((opaque, handle) => tags(handle), 'iii');
        return { read: r, seek: s, tags: t };
    }"""
)
private external fun installOnDemandCallbacks(
    module: JsAny,
    seekable: Boolean,
    read: (Int, Int) -> Int,
    seek: (Double, Int) -> Double,
    tags: (Int) -> Int,
): JsAny

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
 * Registers the read and seek callbacks in the codec module's function table, and a tags callback
 * when [stops] holds an offset, and returns the three indices, the tags one 0 when there is none.
 *
 * Written in JavaScript on purpose. These close over a buffer that lives in the module's memory and
 * are called BY the module, synchronously, from inside `avformat_open_input`. Routing them back
 * through Kotlin would add a second module crossing to every read for no gain, and Kotlin/Wasm
 * cannot be handed to `addFunction` as a raw table entry anyway.
 *
 * [stops] are the offsets where the staged source's tags wait, in byte order. A read never runs past
 * the next one, so a read starts at each, and the tags callback calls [hand] only for a read that
 * started at one, so every other read costs no crossing (#168).
 *
 * The end of the data is KC_IO_EOF, -1, as the C bridge defines it. FFmpeg's own AVERROR_EOF is
 * not: the bridge maps every other negative value to an I/O error, so every playback ended failing.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m, base, total, stops, hand) => {
        let pos = 0, start = -1;
        const marked = new Set(stops);
        // The first offset after p where tags wait, or the end.
        const nextStop = (p) => {
            let lo = 0, hi = stops.length;
            while (lo < hi) {
                const mid = (lo + hi) >> 1;
                if (stops[mid] <= p) lo = mid + 1; else hi = mid;
            }
            return lo < stops.length ? stops[lo] : total;
        };
        const read = m.addFunction((opaque, dst, len) => {
            if (pos >= total) return -1;
            const n = Math.min(len, nextStop(pos) - pos);
            m.HEAPU8.copyWithin(dst, base + pos, base + pos + n);
            start = pos;
            pos += n;
            return n;
        }, 'iiii');
        const tags = stops.length === 0 ? 0 : m.addFunction(
            (opaque, handle) => marked.has(start) ? hand(handle, start) : 0, 'iii');
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
        return { read, seek, tags };
    }"""
)
private external fun installCallbacks(
    module: JsAny,
    buffer: Int,
    total: Int,
    stops: JsAny,
    hand: (Int, Int) -> Int,
): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => []")
private external fun newOffsetList(): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(list, offset) => { list.push(offset); }")
private external fun addOffset(list: JsAny, offset: Int)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(c) => c.read")
private external fun callbackRead(callbacks: JsAny): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(c) => c.seek")
private external fun callbackSeek(callbacks: JsAny): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(c) => c.tags")
private external fun callbackTags(callbacks: JsAny): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(m, r, s, t) => { m.removeFunction(r); if (s) m.removeFunction(s); if (t) m.removeFunction(t); }")
private external fun releaseCallbacks(module: JsAny, read: Int, seek: Int, tags: Int)
