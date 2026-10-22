@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.refuseSeekBreakingOptions
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** What a byte source call answers to C at the end of the bytes. */
internal const val ASYNC_IO_EOF: Int = -1

/** What a byte source call answers to C when it failed or was stopped. */
internal const val ASYNC_IO_ERR: Int = -2

/** What a nested open answers to C when the opener declined the address. */
internal const val ASYNC_IO_REFUSED: Int = -3

/** The most bytes one read asks a byte source for. A larger request gets a short read. */
internal const val ASYNC_READ_SCRATCH: Int = 1 shl 16

/**
 * The platform half of an [AsyncLane]: how codec work runs, and how a demux call waits for a byte
 * source. The lane calls it with the lane held, one call at a time.
 *
 * The suspending calls here are not cancellable. A demux call returns when C has unwound, and
 * [abort] is the way to make that happen soon.
 */
internal interface AsyncEngine {

    /** The FFmpeg this runtime runs. */
    val identity: FFmpegIdentity

    /** Runs codec work that never waits for a byte source. */
    suspend fun <T> immediate(block: () -> T): T

    /** Opens [request]'s input and reads its stream information. */
    suspend fun open(request: AsyncOpen): MediaSource

    suspend fun readPacket(reader: PacketReader): Packet?

    suspend fun seek(reader: PacketReader, micros: Long, direction: SeekDirection, notEarlierThan: Long?)

    suspend fun pause(source: MediaSource): Boolean

    suspend fun resume(source: MediaSource): Boolean

    /** Closes [source], which closes the nested sources FFmpeg still holds. */
    suspend fun closeSource(input: AsyncInput, source: MediaSource)

    /**
     * Makes the demux call of [input] fail at FFmpeg's next check. It must be quick, and must not
     * enter the codec while a call is parked.
     */
    fun abort(input: AsyncInput)

    /** Releases the engine after every source is closed. */
    suspend fun close()
}

/** What [AsyncEngine.open] opens. */
internal class AsyncOpen(
    val input: AsyncInput,
    val options: Map<String, String>,
    val url: String?,
    val mimeType: String?,
)

/**
 * The runtimes whose byte source calls the current coroutine runs inside. A byte source that
 * called back into one of them would wait for a lane that waits for it.
 */
internal class AsyncAncestry(val lanes: Set<AsyncLane>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AsyncAncestry>
}

/** One close whose result every caller shares. */
internal class CloseOnce {
    private val lock = SynchronizedObject()
    private var record: CompletableDeferred<Throwable?>? = null

    /** True once a close began. */
    val started: Boolean get() = synchronized(lock) { record != null }

    /** Runs [body] for the first caller. Every caller waits for it and gets its failure. */
    suspend fun close(body: suspend () -> Unit) {
        var mine = false
        val shared = synchronized(lock) {
            record ?: CompletableDeferred<Throwable?>().also {
                record = it
                mine = true
            }
        }
        val failure = withContext(NonCancellable) {
            if (mine) {
                // The failure travels as a value, so every caller receives the same object.
                shared.complete(
                    try {
                        body()
                        null
                    } catch (thrown: Throwable) {
                        thrown
                    },
                )
            }
            shared.await()
        }
        if (failure != null) throw failure
    }
}

/** A byte source the runtime owns, with the one close every owner shares. */
internal class ProviderLease(val source: AsyncMediaByteSource, val isRoot: Boolean) {
    private val once = CloseOnce()

    /** Where the cursor is, for a seek relative to it. */
    var position: Long = 0L

    /** The last size the source gave, which a later null keeps. */
    var lastSize: Long? = null

    /** The tags the last read brought, until FFmpeg takes them. */
    var tags: Map<String, String>? = null

    /** The source's location, read when it was opened. */
    var location: String? = null

    /** The number the web engine named this source to C with. */
    var number: Int = 0

    /**
     * Closes the source once, away from the thread that waits for it. [ancestry] carries the
     * runtimes the close runs inside.
     */
    suspend fun close(ancestry: AsyncAncestry) {
        once.close { withContext(Dispatchers.Default + ancestry) { source.close() } }
    }
}

/** One opened input: its byte sources, its stop signal and the source that reads it. */
internal class AsyncInput(val root: ProviderLease, val opener: AsyncMediaByteOpener?, parent: Job) {
    private val lock = SynchronizedObject()
    private val children = LinkedHashSet<ProviderLease>()
    private var stopping = false
    private val closeFailures = ArrayList<Throwable>()

    /** Completes when the input stops. A stopped input never works again. */
    val stopped: CompletableDeferred<Unit> = CompletableDeferred()

    /** The parent of every byte source call of this input. */
    val providerJob: Job = SupervisorJob(parent)

    /** What the engine keeps for this input: the interrupt it raises in [AsyncEngine.abort]. */
    @kotlin.concurrent.Volatile
    var engineData: Any? = null

    @kotlin.concurrent.Volatile
    var source: MediaSource? = null

    /** True once the owner asked for the close. */
    @kotlin.concurrent.Volatile
    var closed: Boolean = false

    /**
     * What a byte source call of this input last threw, until a read works again. FFmpeg can
     * answer a failed read with the bytes it already had, and fail the next call without asking
     * the byte source again. That call then still names this as its cause.
     */
    @kotlin.concurrent.Volatile
    var lastFailure: Throwable? = null

    /** The packet reader of the source, which a source can have one of. Its close goes first. */
    @kotlin.concurrent.Volatile
    var reader: AsyncHandle? = null

    /** The caller's interrupt request and what it runs, until the input closes. */
    var bound: Pair<OpenInterrupt, () -> Unit>? = null

    val isStopped: Boolean get() = stopped.isCompleted

    /** True for the first caller only. */
    fun beginStop(): Boolean = synchronized(lock) {
        if (stopping) false else {
            stopping = true
            true
        }
    }

    fun addChild(lease: ProviderLease) {
        synchronized(lock) { children += lease }
    }

    fun removeChild(lease: ProviderLease) {
        synchronized(lock) { children -= lease }
    }

    /** The root and every nested source FFmpeg has not closed. */
    fun leases(): List<ProviderLease> = synchronized(lock) { listOf(root) + children }

    fun recordCloseFailure(failure: Throwable) {
        synchronized(lock) { closeFailures += failure }
    }

    fun takeCloseFailures(): List<Throwable> = synchronized(lock) { closeFailures.toList().also { closeFailures.clear() } }

    /** Refuses work on a source that is closed or stopped. */
    fun requireUsable() {
        check(!closed) { "AsyncMediaSource is closed" }
        if (isStopped) {
            throw FFmpegException(
                FFmpegError.Interrupted(
                    FFmpegError.AVERROR_EXIT,
                    "this source was interrupted, or one of its operations was cancelled, and it cannot " +
                        "read again. Close it and open the media again.",
                ),
            )
        }
    }
}

/** One demux call in C, with the byte source calls it made and the failure one of them recorded. */
internal class AsyncOperation(val input: AsyncInput, val ancestry: AsyncAncestry) {
    private val lock = SynchronizedObject()
    private var failure: Throwable? = null
    private val calls = ArrayList<Job>()

    fun fail(thrown: Throwable) {
        synchronized(lock) { failure = thrown }
        input.lastFailure = thrown
    }

    fun takeFailure(): Throwable? = synchronized(lock) { failure.also { failure = null } }

    /** True once this call asked a byte source for anything. */
    @kotlin.concurrent.Volatile
    var asked: Boolean = false
        private set

    fun track(call: Job) {
        asked = true
        synchronized(lock) { calls += call }
    }

    /** Waits until every byte source call of this operation has returned. */
    suspend fun settle() {
        while (true) {
            val pending = synchronized(lock) { calls.toList().also { calls.clear() } }
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }
}

/** What a nested open answered when the opener served the address. */
internal class AsyncChild(val lease: ProviderLease, val size: Long, val seekable: Boolean)

/**
 * The one place an [AsyncMediaRuntime] runs codec work. One operation holds the lane at a time.
 *
 * The lane owns the rules that are the same on every platform: who waits, what a cancelled caller
 * leaves behind, who closes a byte source, and what a closed runtime refuses. The [engine] under
 * it only knows how to run a call.
 */
internal class AsyncLane(val engine: AsyncEngine) {
    private val lock = SynchronizedObject()
    private val mutex = Mutex()
    private val closeOnce = CloseOnce()
    private val root = SupervisorJob()

    /** Runs the demux calls. A caller that is cancelled leaves its call here to unwind. */
    private val supervisor = CoroutineScope(SupervisorJob(root) + Dispatchers.Unconfined)

    /** Runs the byte source calls, away from the thread that waits for them. */
    private val providers = CoroutineScope(SupervisorJob(root) + Dispatchers.Default)

    /** Runs the closes that a stop starts. */
    private val cleanup = CoroutineScope(SupervisorJob(root) + Dispatchers.Default)

    /** Runs the imports of the web engine. */
    val imports: CoroutineScope = CoroutineScope(SupervisorJob(root) + Dispatchers.Default)

    private val ownAncestry = AsyncAncestry(setOf(this))
    private val leases = ArrayList<ProviderLease>()
    private val inputs = LinkedHashSet<AsyncInput>()
    private val handles = LinkedHashSet<AsyncHandle>()

    @kotlin.concurrent.Volatile
    private var closing = false

    /** The demux call that is in C now, if one is. */
    @kotlin.concurrent.Volatile
    private var current: AsyncOperation? = null

    /**
     * Refuses a call from inside one of this runtime's own byte source calls, and answers the
     * runtimes a byte source call made from here runs inside.
     */
    internal suspend fun enter(): AsyncAncestry {
        val inherited = currentCoroutineContext()[AsyncAncestry]
        check(inherited == null || this !in inherited.lanes) {
            "a byte source called the runtime that is reading it. The runtime waits for the byte " +
                "source, so the call could never finish."
        }
        return if (inherited == null) ownAncestry else AsyncAncestry(inherited.lanes + this)
    }

    private fun checkOpen() {
        check(!closing) { "AsyncMediaRuntime is closed" }
    }

    // ---- Codec work ---------------------------------------------------------------------------

    /**
     * Runs codec work that never waits for a byte source. A caller cancelled while it waits for
     * the lane starts nothing. A caller cancelled after the work began gets its result disposed
     * through [dispose].
     */
    suspend fun <T> immediate(dispose: ((T) -> Unit)? = null, block: () -> T): T {
        enter()
        checkOpen()
        mutex.lock()
        try {
            checkOpen()
            val result = withContext(NonCancellable) { engine.immediate(block) }
            if (dispose != null && !currentCoroutineContext().isActive) {
                withContext(NonCancellable) { engine.immediate { dispose(result) } }
                currentCoroutineContext().ensureActive()
            }
            return result
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Runs one demux call of [input], which may wait for its byte sources. A caller cancelled
     * while it waits for the lane starts nothing. A caller cancelled after the call entered C
     * stops the input for good, waits for C to unwind, and disposes a result that arrived.
     */
    suspend fun <T> demux(input: AsyncInput, dispose: ((T) -> Unit)? = null, call: suspend () -> T): T {
        val ancestry = enter()
        checkOpen()
        mutex.lock()
        try {
            checkOpen()
            input.requireUsable()
            return entered(input, ancestry, dispose, call)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun <T> entered(
        input: AsyncInput,
        ancestry: AsyncAncestry,
        dispose: ((T) -> Unit)?,
        call: suspend () -> T,
    ): T {
        val operation = AsyncOperation(input, ancestry)
        current = operation
        try {
            // The call belongs to the runtime, not to the caller, so a cancelled caller cannot
            // leave C parked with nobody to resume it. Its result travels as a value, so a
            // failure reaches the caller as the object that was thrown.
            val native = supervisor.async(start = CoroutineStart.UNDISPATCHED) { runCatching { call() } }
            val outcome = try {
                native.await()
            } catch (cancelled: CancellationException) {
                stop(input)
                withContext(NonCancellable) {
                    val late = native.await()
                    operation.settle()
                    if (dispose != null) late.onSuccess { result -> engine.immediate { dispose(result) } }
                }
                throw cancelled
            }
            withContext(NonCancellable) { operation.settle() }
            return outcome.getOrThrow()
        } finally {
            current = null
        }
    }

    // ---- Opening and closing an input ---------------------------------------------------------

    suspend fun open(
        io: AsyncMediaByteSource,
        options: Map<String, String>,
        interrupt: OpenInterrupt?,
        url: String?,
        mimeType: String?,
        nestedOpener: AsyncMediaByteOpener?,
    ): AsyncMediaSource {
        val ancestry = enter()
        checkOpen()
        // An object the runtime already holds is refused here, untouched. From the next line on
        // the runtime owns io, and every failure closes it.
        val lease = admit(io, isRoot = true)
        val input = AsyncInput(lease, nestedOpener, providers.coroutineContext.job)
        synchronized(lock) { inputs += input }
        var locked = false
        try {
            refuseSeekBreakingOptions(options)
            lease.location = io.openedLocation()
            if (interrupt != null) {
                if (interrupt.isInterrupted) throw interruptedOpen("before it started")
                val target: () -> Unit = { stop(input) }
                input.bound = interrupt to target
                interrupt.bind(target)
            }
            mutex.lock()
            locked = true
            checkOpen()
            return entered(input, ancestry, dispose = null) {
                val source = engine.open(AsyncOpen(input, options, url, mimeType))
                input.source = source
                engine.immediate { AsyncMediaSource(this, input, source) }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    closeInput(input, ancestry)
                } catch (closing: Throwable) {
                    if (closing !== failure) failure.addSuppressed(closing)
                }
            }
            throw failure
        } finally {
            if (locked) mutex.unlock()
        }
    }

    /**
     * Stops [input] for good: raises its interrupt, ends its byte source calls and starts the
     * close of its byte sources. Quick, and safe from any thread and from an [OpenInterrupt].
     */
    fun stop(input: AsyncInput) {
        if (!input.beginStop()) return
        // The signal first, so an engine that creates its interrupt at this moment sees the stop.
        input.stopped.complete(Unit)
        engine.abort(input)
        input.providerJob.cancel()
        // A byte source may ignore the cancellation of its call. Its close must stop the
        // transport, so it starts now, while the call may still be finishing.
        cleanup.launch {
            input.leases().forEach { lease ->
                try {
                    lease.close(ownAncestry)
                } catch (failure: Throwable) {
                    // The close of the source reports it, through the same record.
                }
            }
        }
    }

    /** Closes the source of [input]. The caller holds no lane. */
    suspend fun closeSource(input: AsyncInput) {
        val ancestry = enter()
        input.closed = true
        // An operation of this source that is in C now must unwind before its context is freed.
        if (current?.input === input) stop(input)
        withContext(NonCancellable) {
            mutex.withLock {
                if (synchronized(lock) { input in inputs }) closeInput(input, ancestry)
            }
        }
    }

    /**
     * Closes the native source of [input] and every byte source it still owns. Every step runs
     * whichever one fails. The lane is held when the input has a native source.
     */
    private suspend fun closeInput(input: AsyncInput, ancestry: AsyncAncestry) {
        val failures = CloseFailures()
        input.closed = true
        val source = input.source
        if (source != null) {
            input.source = null
            // FFmpeg refuses to close a container whose packet reader is open.
            input.reader?.let { reader ->
                input.reader = null
                released(reader)
                try {
                    engine.immediate { reader.releaseNow() }
                } catch (failure: Throwable) {
                    failures.record(failure)
                }
            }
            val operation = AsyncOperation(input, ancestry)
            current = operation
            try {
                engine.closeSource(input, source)
            } catch (failure: Throwable) {
                failures.record(failure)
            } finally {
                operation.settle()
                current = null
            }
        }
        for (lease in input.leases()) {
            try {
                lease.close(ancestry)
            } catch (failure: Throwable) {
                failures.record(failure)
            }
            forget(lease)
        }
        input.takeCloseFailures().forEach(failures::record)
        input.providerJob.cancel()
        input.bound?.let { (interrupt, target) -> interrupt.unbind(target) }
        input.bound = null
        synchronized(lock) { inputs -= input }
        failures.rethrow()
    }

    // ---- Handles --------------------------------------------------------------------------------

    /** Records a handle the runtime must close with itself. Called with the lane held. */
    fun register(handle: AsyncHandle) {
        synchronized(lock) { handles += handle }
    }

    /** Releases [handle] in the lane, unless the runtime already did. */
    suspend fun release(handle: AsyncHandle) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (synchronized(lock) { handles.remove(handle) }) engine.immediate { handle.releaseNow() }
            }
        }
    }

    /** Forgets [handle], which was released with the lane held. */
    fun released(handle: AsyncHandle) {
        synchronized(lock) { handles -= handle }
    }

    suspend fun close() {
        enter()
        closeOnce.close {
            closing = true
            synchronized(lock) { inputs.toList() }.forEach(::stop)
            val failures = CloseFailures()
            mutex.withLock {
                // Frames, packets, decoders and readers go before the sources they came from.
                val owned = synchronized(lock) { handles.toList().asReversed().also { handles.clear() } }
                for (handle in owned) {
                    try {
                        engine.immediate { handle.releaseNow() }
                    } catch (failure: Throwable) {
                        failures.record(failure)
                    }
                }
                for (input in synchronized(lock) { inputs.toList() }) {
                    try {
                        closeInput(input, ownAncestry)
                    } catch (failure: Throwable) {
                        failures.record(failure)
                    }
                }
                try {
                    engine.close()
                } catch (failure: Throwable) {
                    failures.record(failure)
                }
            }
            // Last, because the closes above ran in these scopes.
            root.cancel()
            failures.rethrow()
        }
    }

    // ---- Byte source calls, made by the engine from inside a demux call -------------------------

    private fun admit(source: AsyncMediaByteSource, isRoot: Boolean): ProviderLease = synchronized(lock) {
        require(leases.none { it.source === source }) {
            "this byte source object is already open in this runtime. One object serves one open: " +
                "give every open and every nested address its own object."
        }
        ProviderLease(source, isRoot).also { leases += it }
    }

    private fun forget(lease: ProviderLease) {
        synchronized(lock) { leases.remove(lease) }
    }

    /** The failure a byte source call of the current operation recorded, taken once. */
    fun takeFailure(): Throwable? {
        val operation = current ?: return null
        // The kept failure only for a call that failed without asking a byte source again.
        return operation.takeFailure() ?: operation.input.lastFailure.takeUnless { operation.asked }
    }

    /** Records what an engine callback threw, so it cannot travel into C. */
    fun recordFailure(failure: Throwable) {
        current?.fail(failure)
    }

    /**
     * Runs one byte source call of [operation] and waits for it, or for the stop of the input.
     * Null says that the input stopped or that the call failed. A failure is recorded.
     */
    private suspend fun <T> call(operation: AsyncOperation, body: suspend () -> T): Result<T>? {
        val input = operation.input
        if (input.isStopped) return null
        val running = providers.async(input.providerJob + operation.ancestry, CoroutineStart.UNDISPATCHED) {
            runCatching { body() }
        }
        operation.track(running)
        val answer = try {
            select<Result<T>?> {
                running.onAwait { it }
                input.stopped.onAwait { null }
            }
        } catch (cancelled: CancellationException) {
            // The input's job was cancelled before the call could start.
            null
        } ?: return null
        if (input.isStopped) return null
        val failure = answer.exceptionOrNull() ?: return answer
        operation.fail(failure)
        return null
    }

    /**
     * Reads at most [length] bytes of [lease]. [deliver] receives the bytes only while the
     * operation is still the active one. Answers the count, [ASYNC_IO_EOF] or [ASYNC_IO_ERR].
     */
    suspend fun providerRead(lease: ProviderLease, length: Int, deliver: (ByteArray, Int) -> Unit): Int {
        val operation = current ?: return ASYNC_IO_ERR
        if (length <= 0) return ASYNC_IO_ERR
        val want = minOf(length, ASYNC_READ_SCRATCH)
        // A new array for each request. A call that outlives its operation keeps writing into
        // an array nothing reads.
        val scratch = ByteArray(want)
        val got = call(operation) {
            val count = lease.source.read(scratch, 0, want)
            lease.tags = if (lease.isRoot && count in 1..want) lease.source.takeTags() else null
            count
        }?.getOrNull() ?: return ASYNC_IO_ERR
        return when {
            got > want -> {
                operation.fail(byteSourceOverCount(got, want))
                ASYNC_IO_ERR
            }
            got > 0 -> {
                if (operation.input.isStopped || current !== operation) return ASYNC_IO_ERR
                deliver(scratch, got)
                lease.position += got
                // The byte source works again, so an earlier failure explains nothing more.
                operation.input.lastFailure = null
                got
            }
            got < 0 -> ASYNC_IO_EOF
            else -> {
                operation.fail(IllegalStateException("the byte source answered a read with 0, and it must wait for a byte or answer -1"))
                ASYNC_IO_ERR
            }
        }
    }

    /** Moves [lease] to [position]. Answers 0 or [ASYNC_IO_ERR]. */
    suspend fun providerSeek(lease: ProviderLease, position: Long): Int {
        val operation = current ?: return ASYNC_IO_ERR
        if (position < 0 || !lease.source.seekable) return ASYNC_IO_ERR
        call(operation) { lease.source.seek(position) } ?: return ASYNC_IO_ERR
        lease.position = position
        return 0
    }

    /** The size of [lease], -1 when it is unknown, or [ASYNC_IO_ERR]. */
    suspend fun providerSize(lease: ProviderLease): Long {
        val operation = current ?: return ASYNC_IO_ERR.toLong()
        val answer = call(operation) { lease.source.size() } ?: return ASYNC_IO_ERR.toLong()
        val size = answer.getOrNull()
        if (size != null) {
            if (size < 0) {
                operation.fail(IllegalStateException("the byte source answered a size of $size"))
                return ASYNC_IO_ERR.toLong()
            }
            lease.lastSize = size
        }
        return lease.lastSize ?: -1L
    }

    /**
     * Opens [url] through the input's opener. Answers an [AsyncChild], [ASYNC_IO_REFUSED] when
     * the opener declined, or [ASYNC_IO_ERR]. A source that arrives after the input stopped is
     * closed, not kept.
     */
    suspend fun providerOpen(url: String): Any {
        val operation = current ?: return ASYNC_IO_ERR
        val input = operation.input
        val opener = input.opener ?: return ASYNC_IO_REFUSED
        val answer = call<Any>(operation) {
            val source = opener.open(url) ?: return@call ASYNC_IO_REFUSED
            // Refused untouched when the runtime already holds this object.
            val lease = admit(source, isRoot = false)
            var kept = false
            try {
                lease.location = source.openedLocation()
                val size = source.size()
                if (size != null) {
                    check(size >= 0) { "the byte source answered a size of $size" }
                    lease.lastSize = size
                }
                currentCoroutineContext().ensureActive()
                input.addChild(lease)
                kept = true
                AsyncChild(lease, size ?: -1L, source.seekable)
            } finally {
                if (!kept) {
                    withContext(NonCancellable) {
                        try {
                            lease.close(operation.ancestry)
                        } catch (failure: Throwable) {
                            input.recordCloseFailure(failure)
                        }
                        forget(lease)
                    }
                }
            }
        }
        return answer?.getOrNull() ?: ASYNC_IO_ERR
    }

    /**
     * Closes a nested source FFmpeg is done with. It runs on a stopped input too, because C
     * releases its sources while it unwinds. Answers what the close threw, or null.
     */
    suspend fun providerClose(lease: ProviderLease): Throwable? {
        val operation = current
        val input = operation?.input
        val failure = try {
            lease.close(operation?.ancestry ?: ownAncestry)
            null
        } catch (thrown: Throwable) {
            thrown
        }
        input?.removeChild(lease)
        forget(lease)
        if (failure != null) input?.recordCloseFailure(failure)
        return failure
    }

    /** Waits [micros] for FFmpeg, or until the input of the current operation stops. */
    suspend fun providerSleep(micros: Long) {
        val input = current?.input ?: return
        if (micros <= 0) return
        withTimeoutOrNull(maxOf(1L, micros / 1000L)) { input.stopped.await() }
    }
}

/**
 * The native resource behind one handle of an [AsyncMediaRuntime]. [free] releases it and runs in
 * the lane, once: at the handle's close, or at the runtime's close when that comes first.
 */
internal class AsyncHandle(internal val lane: AsyncLane, private val what: String, private val free: () -> Unit) {
    private val once = CloseOnce()
    private val stateLock = SynchronizedObject()
    private var released = false

    init {
        // Created with the lane held, so the runtime knows the resource from its first moment.
        lane.register(this)
    }

    /** True once a close began, or the runtime released the resource. */
    internal val isClosed: Boolean get() = once.started || synchronized(stateLock) { released }

    /** A closed handle refuses every operation and getter. */
    internal fun requireOpen() {
        check(!isClosed) { "$what is closed" }
    }

    /** Frees the native resource once. Runs with the lane held. */
    internal fun releaseNow() {
        val first = synchronized(stateLock) {
            if (released) false else {
                released = true
                true
            }
        }
        if (first) free()
    }

    /** Frees a resource that never reached its caller. Runs with the lane held. */
    internal fun discard() {
        lane.released(this)
        releaseNow()
    }

    internal suspend fun close() {
        lane.enter()
        once.close { lane.release(this) }
    }
}
