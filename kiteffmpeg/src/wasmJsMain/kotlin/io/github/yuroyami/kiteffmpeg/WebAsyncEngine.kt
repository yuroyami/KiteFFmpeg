@file:OptIn(KiteFFmpegLowLevelApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_fmt_nested_io_available
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_interrupt_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_interrupt_new
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny

/**
 * The codec module the code that runs now belongs to, when an asynchronous runtime set one.
 *
 * The synchronous classes of this backend ask [requireModule] for their module. A runtime has a
 * module of its own, so its lane names that module here for each stretch of code that does not
 * wait, and takes the name away before every wait. The page's module is never replaced.
 */
internal var scopedModule: JsAny? = null

/**
 * The web engine of an [AsyncLane]: one codec module linked to wait for its byte sources,
 * `kite-jspi` or `kite-asyncify`.
 *
 * A demux call that can wait goes through `Module.kiteAsyncCall` and answers a Promise. While it
 * waits, the C stack is parked, and this engine never calls an export of the module: it raises an
 * interrupt by writing the cell in the module's memory. The byte sources are named to C by
 * numbers from this engine's own table.
 */
internal class WebAsyncEngine(private val module: JsAny) : AsyncEngine {
    lateinit var lane: AsyncLane

    /** The byte sources C knows, by the number it knows them by. */
    private val sources = HashMap<Int, ProviderLease>()
    private var nextNumber = 1

    override val identity: FFmpegIdentity = scoped { webIdentity() }

    /** Where a source of this engine gets its failures: the demux call that is running. */
    private val input = object : WebInput {
        override fun takeFailure(): Throwable? = lane.takeFailure()

        // Each demux call has a record of its own, which starts empty.
        override fun clearFailures() = Unit

        // The lane closes the byte sources, because their close may wait.
        override fun release() = Unit
    }

    /** Runs [block] with this engine's module as the one the synchronous classes use. */
    private inline fun <T> scoped(block: () -> T): T {
        scopedModule = module
        try {
            return block()
        } finally {
            scopedModule = null
        }
    }

    /**
     * Calls the export [name], which can park, and waits for it. Not cancellable: the C stack is
     * parked until the Promise settles, so nothing may give up on it.
     */
    private suspend fun parked(name: String, vararg args: Int): Int {
        val list = newArguments()
        args.forEach { addArgument(list, it) }
        scopedModule = null
        try {
            return suspendCoroutine { continuation ->
                callParking(
                    module, name, list,
                    { result -> continuation.resume(result) },
                    { reason ->
                        continuation.resumeWithException(
                            FFmpegException(FFmpegError.Internal("the codec module failed inside $name: $reason")),
                        )
                    },
                )
            }
        } finally {
            scopedModule = module
        }
    }

    private fun name(lease: ProviderLease): Int {
        check(nextNumber > 0) { "the byte source numbers of this runtime are used up" }
        val number = nextNumber++
        lease.number = number
        sources[number] = lease
        return number
    }

    /** Forgets every number of [input], after C has let go of its sources. */
    private fun forget(input: AsyncInput) {
        input.leases().forEach { sources.remove(it.number) }
    }

    /** Frees the interrupt cell of [input], once. The free takes the address of the cell's pointer. */
    private fun freeCell(input: AsyncInput) {
        val cell = input.engineData as? Int ?: return
        input.engineData = null
        val slot = wasmAlloc(module, 4)
        try {
            writeInt32(module, slot, cell)
            ffkmp_interrupt_free(module, slot)
        } finally {
            wasmFree(module, slot)
        }
    }

    override suspend fun <T> immediate(block: () -> T): T = scoped(block)

    override suspend fun open(request: AsyncOpen): MediaSource {
        val opened = request.input
        val root = opened.root
        // FFmpeg takes the size with the open, so the source is asked before C is entered.
        val size = lane.providerSize(root)
        if (size == ASYNC_IO_ERR.toLong()) {
            if (opened.isStopped) throw interruptedOpen("while it asked the byte source for its size")
            throw FFmpegException(FFmpegError.Io(0, "the byte source could not say its size"), lane.takeFailure())
        }
        val number = name(root)
        try {
            return scoped {
                val m = module
                if (opened.opener != null && ffkmp_fmt_nested_io_available(m) == 0) throw nestedOpenerNeedsPatchedFFmpeg()
                val cell = ffkmp_interrupt_new(m)
                if (cell == 0) throw outOfCodecMemory("an interrupt cell")
                opened.engineData = cell
                // A stop that came before the cell existed had nothing to raise.
                if (opened.isStopped) writeInt32(m, cell, 1)
                val flags = (if (root.source.seekable) FLAG_SEEKABLE else 0) or FLAG_TAGS or
                    (if (opened.opener != null) FLAG_NESTED else 0)
                // What FFmpeg logged while it refused rides on the exception (#170).
                val source = withLoggedReasonHeld {
                    openWebSource(
                        m, input, request.options,
                        releaseIo = {},
                        openInput = { slot, keys, values, unusedSlot ->
                            var urlPointer = 0
                            var locationPointer = 0
                            var mimePointer = 0
                            var sizePointer = 0
                            try {
                                urlPointer = request.url?.let { allocCString(m, it) } ?: 0
                                locationPointer = root.location?.let { allocCString(m, it) } ?: 0
                                mimePointer = request.mimeType?.let { allocCString(m, it) } ?: 0
                                // A 64-bit value reaches an export that can park through memory.
                                sizePointer = wasmAlloc(m, 8)
                                writeInt64(m, sizePointer, size)
                                parked(
                                    "ffkmp_async_open_input", slot, number, sizePointer, urlPointer, locationPointer,
                                    mimePointer, flags, keys, values, request.options.size, unusedSlot, cell,
                                )
                            } finally {
                                if (urlPointer != 0) wasmFree(m, urlPointer)
                                if (locationPointer != 0) wasmFree(m, locationPointer)
                                if (mimePointer != 0) wasmFree(m, mimePointer)
                                if (sizePointer != 0) wasmFree(m, sizePointer)
                            }
                        },
                        findStreamInfo = { context -> parked("ffkmp_fmt_find_stream_info", context) },
                        closeInput = { slot -> parked("ffkmp_fmt_close_input_io", slot) },
                    )
                }
                // After the context that polls the cell is gone.
                source.releaseAtClose { freeCell(opened) }
                source
            }
        } catch (failure: Throwable) {
            freeCell(opened)
            forget(opened)
            throw failure
        }
    }

    override suspend fun readPacket(reader: PacketReader): Packet? = scoped {
        reader.readUsing { _, context, packet -> parked("ffkmp_fmt_read_frame", context, packet) }
    }

    override suspend fun seek(reader: PacketReader, micros: Long, direction: SeekDirection, notEarlierThan: Long?) {
        scoped {
            reader.seekUsing(micros, direction, notEarlierThan) { m, context, min, target, max, flags ->
                // The least, the target and the most, which the export reads from memory.
                val range = wasmAlloc(m, 24)
                try {
                    writeInt64(m, range, min)
                    writeInt64(m, range + 8, target)
                    writeInt64(m, range + 16, max)
                    parked("ffkmp_async_seek_file", context, -1, range, flags)
                } finally {
                    wasmFree(m, range)
                }
            }
        }
    }

    override suspend fun pause(source: MediaSource): Boolean = scoped {
        source.pauseUsing { _, context -> parked("ffkmp_fmt_read_pause", context) }
    }

    override suspend fun resume(source: MediaSource): Boolean = scoped {
        source.resumeUsing { _, context -> parked("ffkmp_fmt_read_play", context) }
    }

    override suspend fun closeSource(input: AsyncInput, source: MediaSource) {
        try {
            scoped { source.closeUsing { _, slot -> parked("ffkmp_fmt_close_input_io", slot) } }
        } finally {
            forget(input)
        }
    }

    override fun abort(input: AsyncInput) {
        // Memory only. A call of this input may be parked, and a parked module takes no export call.
        val cell = input.engineData as? Int ?: return
        writeInt32(module, cell, 1)
    }

    override suspend fun close() {
        sources.clear()
        removeAsyncHost(module)
    }

    // ---- The imports of the module's bridge ----------------------------------------------------

    /** Gives the module its `kiteAsync` object. The module looks it up at each call. */
    fun install() {
        installAsyncHost(
            module,
            read = { source, buffer, length, done -> answer(done) { read(source, buffer, length) } },
            seek = { source, offset, whence, result, done -> answer(done) { seek(source, offset, whence, result) } },
            open = { url, source, size, seekable, done -> answer(done) { open(url, source, size, seekable) } },
            close = { source, done ->
                answer(done) {
                    sources.remove(source)?.let { lane.providerClose(it) }
                    0
                }
            },
            sleep = { micros, done ->
                answer(done) {
                    lane.providerSleep(micros.toLong())
                    0
                }
            },
            tags = ::tags,
            location = ::location,
        )
    }

    /**
     * Runs [body] for a suspending import and settles its Promise through [done]. An exception
     * never travels into C: it becomes the failure of the demux call and an error code.
     */
    private fun answer(done: JsAny, body: suspend () -> Int) {
        lane.imports.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = try {
                body()
            } catch (failure: Throwable) {
                lane.recordFailure(failure)
                ASYNC_IO_ERR
            }
            settle(done, result)
        }
    }

    private suspend fun read(number: Int, buffer: Int, length: Int): Int {
        val lease = sources[number] ?: return ASYNC_IO_ERR
        // The lane hands over the bytes only while the read is still the active one.
        return lane.providerRead(lease, length) { bytes, count -> writeBytes(module, buffer, bytes, count) }
    }

    private suspend fun seek(number: Int, offsetPointer: Int, whence: Int, resultPointer: Int): Int {
        val lease = sources[number] ?: return ASYNC_IO_ERR
        val offset = readInt64(module, offsetPointer)
        val target = when (whence and SEEK_FORCE.inv()) {
            SEEK_SIZE -> {
                val size = lane.providerSize(lease)
                if (size == ASYNC_IO_ERR.toLong()) return ASYNC_IO_ERR
                writeInt64(module, resultPointer, size)
                return 0
            }
            SEEK_SET -> offset
            SEEK_CUR -> lease.position + offset
            SEEK_END -> {
                val size = lane.providerSize(lease)
                if (size < 0) return ASYNC_IO_ERR
                size + offset
            }
            else -> return ASYNC_IO_ERR
        }
        if (lane.providerSeek(lease, target) < 0) return ASYNC_IO_ERR
        writeInt64(module, resultPointer, target)
        return 0
    }

    private suspend fun open(urlPointer: Int, sourcePointer: Int, sizePointer: Int, seekablePointer: Int): Int {
        val url = utf8OrNull(module, urlPointer) ?: return ASYNC_IO_ERR
        return when (val answer = lane.providerOpen(url)) {
            is AsyncChild -> {
                writeInt32(module, sourcePointer, name(answer.lease))
                writeInt64(module, sizePointer, answer.size)
                writeInt32(module, seekablePointer, if (answer.seekable) 1 else 0)
                0
            }
            ASYNC_IO_REFUSED -> ASYNC_IO_REFUSED
            else -> ASYNC_IO_ERR
        }
    }

    /** Hands FFmpeg the tags the last read of source [number] brought. Answers at once. */
    private fun tags(number: Int, tagsPointer: Int): Int = try {
        val lease = sources[number]
        val pairs = lease?.tags?.ffmpegTagPairs()
        lease?.tags = null
        if (pairs == null || handTags(module, tagsPointer, pairs)) 0 else ASYNC_IO_ERR
    } catch (failure: Throwable) {
        lane.recordFailure(failure)
        ASYNC_IO_ERR
    }

    /** Writes the location of source [number], or answers 0 for the address asked for. */
    private fun location(number: Int, buffer: Int, capacity: Int): Int = try {
        val location = sources[number]?.location
        if (location == null) 0 else writeUtf8(module, location, buffer, capacity)
    } catch (failure: Throwable) {
        lane.recordFailure(failure)
        ASYNC_IO_ERR
    }

    private companion object {
        /** The flags of `ffkmp_async_open_input`. */
        const val FLAG_SEEKABLE = 1
        const val FLAG_TAGS = 2
        const val FLAG_NESTED = 4

        const val SEEK_SET = 0
        const val SEEK_CUR = 1
        const val SEEK_END = 2

        /** FFmpeg's `AVSEEK_SIZE`: asks for the size and moves nothing. */
        const val SEEK_SIZE = 0x10000

        /** FFmpeg's `AVSEEK_FORCE`, a hint that rides on the other values. */
        const val SEEK_FORCE = 0x20000
    }
}

/** One 64-bit value written at [pointer], as [readInt64] reads one. */
@JsFun("(m, p, v) => { new DataView(m.HEAPU8.buffer).setBigInt64(p, BigInt(v), true); }")
internal external fun writeInt64(module: JsAny, pointer: Int, value: Long)

/**
 * Writes [text] as a NUL-terminated UTF-8 string at [buffer] when it fits in [capacity] bytes,
 * and answers its length without the NUL, as the bridge's location callback does.
 */
@JsFun("(m, s, p, cap) => { const n = m.lengthBytesUTF8(s); if (n < cap) m.stringToUTF8(s, p, cap); return n; }")
private external fun writeUtf8(module: JsAny, text: String, buffer: Int, capacity: Int): Int

@JsFun("() => []")
private external fun newArguments(): JsAny

@JsFun("(list, value) => { list.push(value); }")
private external fun addArgument(list: JsAny, value: Int)

/**
 * Calls an export that can park through `Module.kiteAsyncCall`, and reports how its Promise
 * settled. A function with no result answers 0.
 */
@JsFun(
    """(m, name, args, done, failed) => {
        let pending;
        try { pending = m.kiteAsyncCall(name, args); } catch (thrown) { failed(String(thrown)); return; }
        Promise.resolve(pending).then((value) => done(value | 0), (thrown) => failed(String(thrown)));
    }""",
)
private external fun callParking(module: JsAny, name: String, args: JsAny, done: (Int) -> Unit, failed: (String) -> Unit)

/** Settles the Promise of a suspending import with [value]. */
@JsFun("(done, value) => done(value)")
private external fun settle(done: JsAny, value: Int)

/**
 * Sets the module's `kiteAsync` object. The five members that can wait answer a Promise, which
 * the Kotlin side settles through the function it is handed. `tags` and `location` answer at once.
 */
@JsFun(
    """(m, read, seek, open, close, sleep, tags, location) => {
        m.kiteAsync = {
            read: (s, b, l) => new Promise((done) => read(s, b, l, done)),
            seek: (s, o, w, r) => new Promise((done) => seek(s, o, w, r, done)),
            open: (u, s, z, k) => new Promise((done) => open(u, s, z, k, done)),
            close: (s) => new Promise((done) => close(s, done)),
            sleep: (u) => new Promise((done) => sleep(u >>> 0, done)),
            tags, location,
        };
    }""",
)
private external fun installAsyncHost(
    module: JsAny,
    read: (Int, Int, Int, JsAny) -> Unit,
    seek: (Int, Int, Int, Int, JsAny) -> Unit,
    open: (Int, Int, Int, Int, JsAny) -> Unit,
    close: (Int, JsAny) -> Unit,
    sleep: (Double, JsAny) -> Unit,
    tags: (Int, Int) -> Int,
    location: (Int, Int, Int) -> Int,
)

@JsFun("(m) => { m.kiteAsync = undefined; }")
private external fun removeAsyncHost(module: JsAny)
