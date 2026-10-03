package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.OpenerLayout
import kotlin.js.JsAny

/**
 * A [MediaByteOpener] as the codec module's `kc_io_opener`: four callbacks in its function table
 * that serve the playlists, segments and keys an HLS playlist names (#123).
 *
 * FFmpeg asks for an address from inside a read and wants the bytes before that read returns, and
 * nothing on the web may wait for a network answer in the middle of one. So open runs the caller's
 * opener there and then, reads the source it returns whole into the module's memory, as
 * [WebIoBridge] does for the playlist, and closes it. Read, seek and close are JavaScript over
 * those bytes and answer at once. The opener itself has to answer at once too, which on a page
 * means bytes it already holds, and in a Worker can mean a synchronous request.
 *
 * The callbacks stay registered until the paired close, because FFmpeg releases the sources it
 * still holds inside that close, so [release] runs after it.
 */
internal class WebNestedOpener(private val module: JsAny, private val opener: MediaByteOpener) {

    /** The exception the last failed open swallowed, kept for the error the open then reports. */
    private var failure: Throwable? = null

    /** Every exception a staged source's close threw. FFmpeg could not hear them, so close reports them. */
    private val closeFailures = mutableListOf<Throwable>()

    private val callbacks: JsAny = installNestedCallbacks(module) { url -> open(url) }

    fun takeFailure(): Throwable? = failure.also { failure = null }

    /**
     * Writes a `kc_io_opener` that names these callbacks and returns its address. The C side copies
     * it during the open, so the caller frees it as soon as the open returns.
     */
    fun writeStruct(): Int {
        val struct = wasmAlloc(module, OpenerLayout.SIZE_OF)
        if (struct == 0) throw FFmpegException(FFmpegError.Internal("could not allocate the nested opener"))
        writeInt32(module, struct + OpenerLayout.opaque, 0)
        writeInt32(module, struct + OpenerLayout.openFn, callbackOf(callbacks, "open"))
        writeInt32(module, struct + OpenerLayout.readFn, callbackOf(callbacks, "read"))
        writeInt32(module, struct + OpenerLayout.seekFn, callbackOf(callbacks, "seek"))
        writeInt32(module, struct + OpenerLayout.closeFn, callbackOf(callbacks, "close"))
        return struct
    }

    /**
     * Frees whatever FFmpeg left staged and removes the callbacks. Runs after the paired close, and
     * then reports the first close failure of a staged source, as the other backends do at close.
     */
    fun release() {
        releaseNestedCallbacks(module, callbacks)
        closeFailures.firstOrNull()?.let { throw it }
    }

    /**
     * One open, called by the module through the open callback. Returns the staged bytes' address,
     * KC_IO_REFUSED (-3) for a declined address, or KC_IO_ERR (-2) for a failure. An exception must
     * never travel back into the module, so every one is kept for the error it causes.
     */
    private fun open(url: String): Int {
        val io = try {
            opener.open(url) ?: return KC_IO_REFUSED
        } catch (thrown: Throwable) {
            failure = thrown
            return KC_IO_ERR
        }
        val staged = try {
            stage(io)
        } catch (thrown: Throwable) {
            failure = thrown
            null
        }
        try {
            io.close()
        } catch (thrown: Throwable) {
            closeFailures += thrown
        }
        return staged ?: KC_IO_ERR
    }

    /** Reads [io] to its end into the module's memory and registers the bytes. Returns their address. */
    private fun stage(io: MediaByteSource): Int {
        val declared = io.size
        if (declared != null && declared > MAX_BYTES) throw tooLarge(declared)
        val chunk = ByteArray(CHUNK)
        val chunks = mutableListOf<ByteArray>()
        var total = 0L
        // A source that knows its size is staged up to that size, as the playlist is. One that does
        // not, such as a response without a length, is staged to its end.
        if (io.seekable) io.seek(0)
        while (declared == null || total < declared) {
            val want = if (declared == null) CHUNK else minOf(CHUNK.toLong(), declared - total).toInt()
            val got = io.read(chunk, 0, want)
            if (got > want) throw byteSourceOverCount(got, want)
            if (got < 0) break
            if (got == 0) throw IllegalStateException("the byte source answered a read with no bytes and no end")
            chunks += chunk.copyOf(got)
            total += got
            if (total > MAX_BYTES) throw tooLarge(total)
        }
        // A zero-byte source still needs an address of its own, because the address is its name.
        val buffer = wasmAlloc(module, maxOf(total.toInt(), 1))
        if (buffer == 0) throw FFmpegException(FFmpegError.Internal("could not stage $total bytes"))
        try {
            var written = 0
            for (bytes in chunks) {
                writeBytes(module, buffer + written, bytes, bytes.size)
                written += bytes.size
            }
            registerStaged(callbacks, buffer, written)
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
                "Each source a nested opener returns is held whole in memory.",
        ),
    )

    private companion object {
        const val KC_IO_ERR = -2
        const val KC_IO_REFUSED = -3
        const val CHUNK = 1 shl 16

        /** As [WebIoBridge] caps the playlist. A segment is a few megabytes. */
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}

/**
 * Registers the four `kc_io_opener` callbacks and returns their state: the table indices, and the
 * staged sources by address.
 *
 * Read and seek are the playlist bridge's, answered from the staged bytes of the source FFmpeg
 * names. Open asks [open] for the address and fills FFmpeg's three out-slots, and close frees the
 * staged bytes, once, whatever FFmpeg does after.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m, open) => {
        const sources = new Map();
        const state = { sources };
        state.open = m.addFunction((opaque, url, sourceOut, sizeOut, seekableOut) => {
            const base = open(m.UTF8ToString(url));
            if (base < 0) return base;
            const s = sources.get(base);
            if (!s) return -2;
            m.HEAP32[sourceOut >> 2] = base;
            new DataView(m.HEAPU8.buffer).setBigInt64(sizeOut, BigInt(s.total), true);
            m.HEAP32[seekableOut >> 2] = 1;
            return 0;
        }, 'iiiiii');
        state.read = m.addFunction((source, dst, len) => {
            const s = sources.get(source);
            if (!s) return -2;
            if (s.pos >= s.total) return -1;
            const n = Math.min(len, s.total - s.pos);
            m.HEAPU8.copyWithin(dst, source + s.pos, source + s.pos + n);
            s.pos += n;
            return n;
        }, 'iiii');
        state.seek = m.addFunction((source, offset, whence) => {
            const s = sources.get(source);
            if (!s) return -2n;
            const off = Number(offset), w = Number(whence);
            let pos;
            if (w === 0) pos = off;
            else if (w === 1) pos = s.pos + off;
            else if (w === 2) pos = s.total + off;
            else return -2n;
            if (pos < 0) return -2n;
            s.pos = Math.min(pos, s.total);
            return BigInt(s.pos);
        }, 'jiji');
        state.close = m.addFunction((opaque, source) => {
            if (sources.delete(source)) m._free(source);
        }, 'vii');
        return state;
    }"""
)
private external fun installNestedCallbacks(module: JsAny, open: (String) -> Int): JsAny

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(state, name) => state[name]")
private external fun callbackOf(callbacks: JsAny, name: String): Int

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(state, base, total) => { state.sources.set(base, { total: total, pos: 0 }); }")
private external fun registerStaged(callbacks: JsAny, base: Int, total: Int)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(m, state) => {
        for (const base of state.sources.keys()) m._free(base);
        state.sources.clear();
        m.removeFunction(state.open);
        m.removeFunction(state.read);
        m.removeFunction(state.seek);
        m.removeFunction(state.close);
    }"""
)
private external fun releaseNestedCallbacks(module: JsAny, callbacks: JsAny)
