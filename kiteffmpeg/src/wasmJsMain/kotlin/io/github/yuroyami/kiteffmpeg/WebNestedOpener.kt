package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.OpenerLayout
import kotlin.js.JsAny

/**
 * The five `kc_io_opener` callbacks for a [MediaByteOpener]. Workers and Node keep each child
 * source until FFmpeg closes it and read only what FFmpeg asks for. A browser page retains the
 * bounded staging fallback because a callback on the page cannot wait for a network response.
 *
 * The callbacks outlive FFmpeg's context: its close releases children through them, so [release]
 * runs after that close and releases any remaining children before removing the callbacks.
 */
internal class WebNestedOpener(private val module: JsAny, private val opener: MediaByteOpener) {
    private var failure: Throwable? = null
    private val closeFailures = mutableListOf<Throwable>()
    private val readers = mutableMapOf<Int, Reader>()
    private var nextHandle = 1
    private var released = false
    private val onDemand = WebIoBridge.readOnDemand ?: nestedBlockingReadsAllowed()
    private val callbacks = installNestedCallbacks(module, ::open, ::read, ::seek, ::closeReader)

    fun takeFailure(): Throwable? = failure.also { failure = null }

    /** The C side copies this struct during open; the caller frees it when open returns. */
    fun writeStruct(): Int {
        val struct = wasmAlloc(module, OpenerLayout.SIZE_OF)
        try {
            writeInt32(module, struct + OpenerLayout.opaque, 0)
            writeInt32(module, struct + OpenerLayout.openFn, callbackOf(callbacks, "open"))
            writeInt32(module, struct + OpenerLayout.readFn, callbackOf(callbacks, "read"))
            writeInt32(module, struct + OpenerLayout.seekFn, callbackOf(callbacks, "seek"))
            writeInt32(module, struct + OpenerLayout.closeFn, callbackOf(callbacks, "close"))
            writeInt32(module, struct + OpenerLayout.locationFn, callbackOf(callbacks, "location"))
            return struct
        } catch (thrown: Throwable) {
            wasmFree(module, struct)
            throw thrown
        }
    }

    /** Every child and callback is released even when a child's close throws. Idempotent. */
    fun release() {
        if (released) return
        released = true
        try {
            releaseNestedCallbacks(module, callbacks)
        } catch (thrown: Throwable) {
            closeFailures += thrown
        } finally {
            // Also covers a child whose JavaScript registration failed after Kotlin took it.
            readers.keys.toList().forEach(::closeReader)
        }
        closeFailures.firstOrNull()?.let { first ->
            closeFailures.drop(1).forEach { if (it !== first) first.addSuppressed(it) }
            throw first
        }
    }

    /** Exceptions cannot cross back into C, so retain the original object for its typed error. */
    private fun open(url: String): Int {
        if (released) return KC_IO_ERR
        val io = try {
            opener.open(url) ?: return KC_IO_REFUSED
        } catch (thrown: Throwable) {
            failure = thrown
            return KC_IO_ERR
        }
        var retained = false
        return try {
            val location = io.openedLocation()
            if (onDemand) {
                val size = checkedSize(io)
                val reader = Reader(io, io.seekable)
                check(nextHandle > 0) { "the nested byte source handle space is exhausted" }
                val handle = nextHandle++
                readers[handle] = reader
                try {
                    registerReader(callbacks, handle, (size ?: -1L).toString(), reader.seekable, location)
                } catch (thrown: Throwable) {
                    readers.remove(handle)
                    throw thrown
                }
                retained = true
                handle
            } else {
                stage(io, location)
            }
        } catch (thrown: Throwable) {
            failure = thrown
            KC_IO_ERR
        } finally {
            if (!retained) closeSource(io)
        }
    }

    private class Reader(val io: MediaByteSource, val seekable: Boolean) {
        val scratch = ByteArray(CHUNK)
        var position = 0L
    }

    private fun read(handle: Int, destination: Int, length: Int): Int {
        val reader = readers[handle] ?: return KC_IO_ERR
        if (length <= 0) return KC_IO_ERR
        return try {
            val want = minOf(length, CHUNK)
            val got = reader.io.read(reader.scratch, 0, want)
            if (got > want) throw byteSourceOverCount(got, want)
            if (got < 0) return KC_IO_EOF
            check(got > 0) { "the byte source answered a read with no bytes and no end" }
            check(reader.position <= Long.MAX_VALUE - got) { "the byte source position exceeds a signed 64-bit offset" }
            writeBytes(module, destination, reader.scratch, got)
            reader.position += got
            got
        } catch (thrown: Throwable) {
            failure = thrown
            KC_IO_ERR
        }
    }

    /** Decimal strings retain every int64 bit across Kotlin/Wasm's JavaScript callback boundary. */
    private fun seek(handle: Int, offsetText: String, whence: Int): String {
        val reader = readers[handle] ?: return KC_IO_ERR.toString()
        return try {
            if (whence == AVSEEK_SIZE) return (checkedSize(reader.io) ?: -1L).toString()
            if (!reader.seekable) return KC_IO_ERR.toString()
            val offset = offsetText.toLongOrNull() ?: return KC_IO_ERR.toString()
            val base = when (whence) {
                0 -> 0L
                1 -> reader.position
                2 -> checkedSize(reader.io) ?: return KC_IO_ERR.toString()
                else -> return KC_IO_ERR.toString()
            }
            // base is nonnegative. Avoid both overflow and a negative resulting position.
            if (offset < -base || offset > Long.MAX_VALUE - base) return KC_IO_ERR.toString()
            val position = base + offset
            reader.io.seek(position)
            reader.position = position
            position.toString()
        } catch (thrown: Throwable) {
            failure = thrown
            KC_IO_ERR.toString()
        }
    }

    private fun checkedSize(io: MediaByteSource): Long? = io.size.also {
        require(it == null || it >= 0L) { "a byte source size must be nonnegative or unknown" }
    }

    private fun closeReader(handle: Int) {
        readers.remove(handle)?.let { closeSource(it.io) }
    }

    private fun closeSource(io: MediaByteSource) {
        try {
            io.close()
        } catch (thrown: Throwable) {
            closeFailures += thrown
        }
    }

    /** Browser pages keep the existing finite, bounded staging behavior. */
    private fun stage(io: MediaByteSource, location: String?): Int {
        val declared = checkedSize(io)
        if (declared != null && declared > MAX_BYTES) throw tooLarge(declared)
        val chunk = ByteArray(CHUNK)
        val chunks = mutableListOf<ByteArray>()
        var total = 0L
        if (io.seekable) io.seek(0)
        while (declared == null || total < declared) {
            val want = if (declared == null) CHUNK else minOf(CHUNK.toLong(), declared - total).toInt()
            val got = io.read(chunk, 0, want)
            if (got > want) throw byteSourceOverCount(got, want)
            if (got < 0) break
            check(got > 0) { "the byte source answered a read with no bytes and no end" }
            chunks += chunk.copyOf(got)
            total += got
            if (total > MAX_BYTES) throw tooLarge(total)
        }
        val buffer = wasmAlloc(module, maxOf(total.toInt(), 1))
        try {
            var written = 0
            for (bytes in chunks) {
                writeBytes(module, buffer + written, bytes, bytes.size)
                written += bytes.size
            }
            registerStaged(callbacks, buffer, written, location)
        } catch (thrown: Throwable) {
            wasmFree(module, buffer)
            throw thrown
        }
        return buffer
    }

    private fun tooLarge(size: Long) = FFmpegException(
        FFmpegError.Unsupported(
            0,
            "A nested source of $size bytes is more than the web backend stages, which is $MAX_BYTES. " +
                "On a browser page each source a nested opener returns is held whole in memory.",
        ),
    )

    private companion object {
        const val KC_IO_EOF = -1
        const val KC_IO_ERR = -2
        const val KC_IO_REFUSED = -3
        const val AVSEEK_SIZE = 0x10000
        const val CHUNK = 1 shl 16
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => typeof window === 'undefined'")
private external fun nestedBlockingReadsAllowed(): Boolean

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m, open, read, seek, close) => {
        const sources = new Map();
        const state = { sources, indices: [] };
        state.closeSource = source => {
            const s = sources.get(source);
            if (!s) return;
            sources.delete(source);
            if (s.demand) close(source); else m._free(source);
        };
        const add = (name, fn, signature) => {
            const index = m.addFunction(fn, signature);
            if (!Number.isInteger(index) || index <= 0) throw new Error('invalid nested callback index');
            state.indices.push(index);
            state[name] = index;
        };
        try {
            add('open', (opaque, url, sourceOut, sizeOut, seekableOut) => {
                const source = open(m.UTF8ToString(url));
                if (source < 0) return source;
                const s = sources.get(source);
                if (!s) return -2;
                m.HEAP32[sourceOut >> 2] = source;
                new DataView(m.HEAPU8.buffer).setBigInt64(sizeOut, s.total, true);
                m.HEAP32[seekableOut >> 2] = s.seekable ? 1 : 0;
                return 0;
            }, 'iiiiii');
            add('read', (source, dst, len) => {
                const s = sources.get(source);
                if (!s || len <= 0) return -2;
                if (s.demand) return read(source, dst, len);
                if (s.pos >= s.total) return -1;
                const n = Number(s.total - s.pos < BigInt(len) ? s.total - s.pos : BigInt(len));
                const at = source + Number(s.pos);
                m.HEAPU8.copyWithin(dst, at, at + n);
                s.pos += BigInt(n);
                return n;
            }, 'iiii');
            add('seek', (source, offset, whence) => {
                const s = sources.get(source);
                if (!s) return -2n;
                if (s.demand) return BigInt(seek(source, offset.toString(), whence));
                if (whence === 0x10000) return s.total;
                let position;
                if (whence === 0) position = offset;
                else if (whence === 1) position = s.pos + offset;
                else if (whence === 2) position = s.total + offset;
                else return -2n;
                if (position < 0n) return -2n;
                s.pos = position > s.total ? s.total : position;
                return s.pos;
            }, 'jiji');
            add('close', (opaque, source) => state.closeSource(source), 'vii');
            add('location', (opaque, source, buf, cap) => {
                const s = sources.get(source);
                if (!s) return -2;
                if (s.location === null) return 0;
                const n = s.location.length;
                if (n < cap) {
                    m.HEAPU8.set(s.location, buf);
                    m.HEAPU8[buf + n] = 0;
                }
                return n;
            }, 'iiiii');
        } catch (failure) {
            for (const index of state.indices) {
                try { m.removeFunction(index); } catch (_) { /* Attempt every installed entry. */ }
            }
            throw failure;
        }
        return state;
    }""",
)
private external fun installNestedCallbacks(
    module: JsAny,
    open: (String) -> Int,
    read: (Int, Int, Int) -> Int,
    seek: (Int, String, Int) -> String,
    close: (Int) -> Unit,
): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(state, name) => state[name]")
private external fun callbackOf(callbacks: JsAny, name: String): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(state, handle, size, seekable, location) => {
        state.sources.set(handle, { demand: true, total: BigInt(size), seekable,
            location: location == null ? null : new TextEncoder().encode(location) });
    }""",
)
private external fun registerReader(callbacks: JsAny, handle: Int, size: String, seekable: Boolean, location: String?)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(state, base, total, location) => {
        state.sources.set(base, { demand: false, total: BigInt(total), pos: 0n, seekable: true,
            location: location == null ? null : new TextEncoder().encode(location) });
    }""",
)
private external fun registerStaged(callbacks: JsAny, base: Int, total: Int, location: String?)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m, state) => {
        let failure;
        for (const source of Array.from(state.sources.keys())) {
            try { state.closeSource(source); } catch (caught) { failure ??= caught; }
        }
        for (const index of state.indices) {
            try { m.removeFunction(index); } catch (caught) { failure ??= caught; }
        }
        state.indices.length = 0;
        if (failure !== undefined) throw failure;
    }""",
)
private external fun releaseNestedCallbacks(module: JsAny, callbacks: JsAny)
