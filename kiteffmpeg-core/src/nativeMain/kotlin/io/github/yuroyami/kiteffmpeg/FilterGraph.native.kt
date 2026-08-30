package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_buffersink_set_frame_size
import ffmpeg.ffkmp_buffersink_time_base
import ffmpeg.ffkmp_frame_unref
import ffmpeg.ffkmp_graph_build_audio
import ffmpeg.ffkmp_graph_build_audio_multi
import ffmpeg.ffkmp_graph_build_video
import ffmpeg.ffkmp_graph_build_video_multi
import ffmpeg.ffkmp_graph_free
import ffmpeg.ffkmp_graph_receive
import ffmpeg.ffkmp_graph_send
import ffmpeg.kc_filter_ctx
import ffmpeg.kc_filter_graph
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.Arena
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * Consecutive send attempts that drain nothing before the graph is declared starved. Two: the
 * first EAGAIN gets a real chance to make room, the second proves the graph cannot.
 */
private const val MAX_STARVED_ATTEMPTS = 2

public actual class FilterGraph internal constructor(
    private val graph: CPointer<kc_filter_graph>,
    private val srcs: List<CPointer<kc_filter_ctx>>,
    private val sink: CPointer<kc_filter_ctx>,
    private val inputType: MediaType,
) : AutoCloseable {

    private val closed = atomic(false)

    /**
     * The graph's operation ledger. [opLock] serializes the non-suspending
     * operations against each other and against [close]; [operationDepth] counts operations in
     * flight so a close that arrives DURING one, from another thread or reentrantly from an
     * [feedInput] callback, marks the graph closed and lets the outermost operation free it on the
     * way out instead of freeing it under the running FFI call. [process] cannot hold a lock across
     * its suspending emits, so it holds only the ledger, which is exactly the part that keeps the
     * pointers alive.
     */
    private val opLock = kotlinx.atomicfu.locks.SynchronizedObject()
    private var operationDepth = 0
    private var freed = false

    private fun enterOperation() = kotlinx.atomicfu.locks.synchronized(opLock) {
        check(!closed.value) { "FilterGraph is closed" }
        operationDepth++
    }

    private fun exitOperation() = kotlinx.atomicfu.locks.synchronized(opLock) {
        operationDepth--
        if (closed.value && operationDepth == 0) freeNow()
    }

    /** Runs [block] as one ledgered operation, serialized against other non-suspending ones. */
    private inline fun <R> operation(block: () -> R): R =
        kotlinx.atomicfu.locks.synchronized(opLock) {
            enterOperation()
            try {
                block()
            } finally {
                exitOperation()
            }
        }

    public actual val inputCount: Int get() = srcs.size

    public actual val outputTimeBase: Rational = memScoped {
        val n = alloc<IntVar>(); val d = alloc<IntVar>()
        ffkmp_buffersink_time_base(sink, n.ptr, d.ptr)
        Rational(n.value, d.value.takeIf { it != 0 } ?: 1)
    }

    /** Reusable landing frame for buffersink output; allocated on first use, freed in [close]. */
    private var outFrameHolder: Frame? = null
    private fun outFrame(): Frame {
        check(!closed.value) { "FilterGraph is closed" }
        return outFrameHolder
            ?: FrameOps.acquire(streamIndex = -1, streamType = inputType, timeBase = outputTimeBase)
                .also { outFrameHolder = it }
    }

    @Throws(FFmpegException::class)
    public actual fun setOutputFrameSize(samples: Int) {
        require(samples > 0) { "frame size must be positive" }
        operation { ffkmp_buffersink_set_frame_size(sink, samples.toUInt()) }
    }

    @Throws(FFmpegException::class)
    public actual fun feedInput(index: Int, frame: Frame, onOutput: (Frame) -> Unit) {
        try {
            operation {
                val src = srcs.getOrNull(index)
                    ?: throw IllegalArgumentException("Input $index out of range (graph has ${srcs.size} inputs)")
                sendUntilAccepted(index, eofIsDone = false, onOutput = onOutput) {
                    // The frame lease covers only the send, never the drain: the drain runs user
                    // callbacks, and user code under another object's lock is how deadlocks are
                    // built. Same shape as the JVM actual.
                    frame.withNative { native -> ffkmp_graph_send(src, native) }
                }
                drainTo(onOutput)
            }
        } finally {
            frame.close()
        }
    }

    @Throws(FFmpegException::class)
    public actual fun flushInput(index: Int, onOutput: (Frame) -> Unit): Unit = operation {
        val src = srcs.getOrNull(index)
            ?: throw IllegalArgumentException("Input $index out of range (graph has ${srcs.size} inputs)")
        // A pad already at EOF is done, not broken, so a second flush is a no-op.
        sendUntilAccepted(index, eofIsDone = true, onOutput = onOutput) { ffkmp_graph_send(src, null) }
        drainTo(onOutput)
    }

    /**
     * Repeat [send] until the graph accepts it, draining the sink between attempts.
     *
     * EAGAIN from a buffersrc means the pad did not consume what it was given, so the SAME send
     * must be retried; dropping it would silently lose a frame. What the retry may not do is run
     * forever. In a multi-input graph an empty sink often means "this filter is waiting on the
     * OTHER pad": `overlay` and `amix` hold their output until every input has something, so
     * draining frees nothing and the retry never ends. Two attempts in a row that produce no
     * output therefore stop with a typed error naming that condition instead of spinning. Fair
     * scheduling across pads, which would let the caller be told WHICH pad to feed, is a larger
     * design and not this function's job.
     *
     * [send] is a parameter rather than an inlined call so both entry points obey one rule, and so
     * the starvation branch can be driven in a test: the FFmpeg this binds to answers a buffersrc
     * write with 0 or a hard error and never with EAGAIN, which leaves the branch unreachable from
     * the outside while still being the thing that has to terminate.
     */
    internal fun sendUntilAccepted(
        index: Int,
        eofIsDone: Boolean,
        onOutput: (Frame) -> Unit,
        send: () -> Int,
    ) {
        var starvedAttempts = 0
        while (true) {
            val rc = send()
            if (rc >= 0) return
            if (eofIsDone && rc == FFErrors.EOF) return
            if (rc != FFErrors.EAGAIN) throw FFmpegException(avError(rc))
            if (drainTo(onOutput)) {
                starvedAttempts = 0
            } else if (++starvedAttempts >= MAX_STARVED_ATTEMPTS) {
                throw FFmpegException(FFmpegError.InvalidArgument(0, starvedInputMessage(index)))
            }
        }
    }

    private fun starvedInputMessage(index: Int): String = if (srcs.size > 1) {
        "Filter graph input $index would not take a frame and the sink produced nothing, twice in a " +
            "row. This graph has ${srcs.size} inputs, and a multi-input filter such as overlay or " +
            "amix emits nothing until every input has frames, so feeding input $index alone starves " +
            "it: it can neither accept more here nor produce anything. Feed every input, and flush " +
            "the ones whose source has ended."
    } else {
        "Filter graph input $index would not take a frame and the sink produced nothing, twice in a " +
            "row, so the frame can never be consumed and retrying would not end."
    }

    /** Single-input convenience used by Transcoder. */
    internal fun feedFrame(frame: Frame, onOutput: (Frame) -> Unit) = feedInput(0, frame, onOutput)

    /** Flush every input, then drain. After this the graph cannot accept more frames. */
    internal fun flushInto(onOutput: (Frame) -> Unit) {
        for (i in srcs.indices) flushInput(i, onOutput)
    }

    /**
     * Hand every frame the sink has ready to [onOutput], and report whether any came out at all,
     * which is how [sendUntilAccepted] tells "the graph made room" apart from "the graph is starved".
     *
     * The landing frame is released after EVERY callback, with no exception for one that returns
     * quietly. `av_buffersink_get_frame` MOVES its result into the destination and requires that
     * destination to arrive empty; a callback that neither took ownership nor threw would otherwise
     * leave the previous frame's buffers sitting in it, and the next receive would overwrite and
     * leak them. A consumer that needs the data after its callback takes a [Frame.copy], which is
     * an O(1) reference bump.
     */
    private fun drainTo(onOutput: (Frame) -> Unit): Boolean {
        val landing = outFrame()
        var produced = false
        while (true) {
            val rc = ffkmp_graph_receive(sink, landing.nativeFrame)
            if (rc == FFErrors.EAGAIN || rc == FFErrors.EOF) return produced
            if (rc < 0) throw FFmpegException(avError(rc))
            produced = true
            // The wrapper itself must be closed, not just the landing frame unreffed: a callback
            // that retains the wrapper would otherwise hold an "open" Frame whose pointer aliases
            // storage this loop reuses and the graph eventually frees. Closing the wrapper both
            // invalidates it for any retainer and performs the unref the landing frame needs.
            val view = FrameOps.wrap(landing.nativeFrame, -1, inputType, outputTimeBase)
            try {
                onOutput(view)
            } finally {
                view.close()
            }
        }
    }

    public actual fun process(input: Flow<Frame>): Flow<Frame> = flow {
        check(srcs.size == 1) { "process() drives single-input graphs; use feedInput for multi-input" }
        val eagain = FFErrors.EAGAIN
        val eof    = FFErrors.EOF
        val src = srcs[0]
        // The ledger without the lock: emits suspend, and a lock held across a suspension is a
        // deadlock waiting for a dispatcher. The ledger alone is what keeps a concurrent close
        // from freeing the graph mid-collection; it defers the free to the finally below.
        enterOperation()
        try {
            val landing = outFrame()
            // Emissions are owned clones (O(1) refcount bumps). See Frame for the ownership
            // rule collectors follow. The reusable landing frame never escapes this call.
            suspend fun FlowCollector<Frame>.drainEmit() {
                while (true) {
                    val recRc = ffkmp_graph_receive(sink, landing.nativeFrame)
                    if (recRc == eagain || recRc == eof) break
                    if (recRc < 0) throw FFmpegException(avError(recRc))
                    val out = FrameOps.wrap(landing.nativeFrame, -1, inputType, outputTimeBase)
                    try {
                        emit(out.copy())
                    } finally {
                        out.close()
                    }
                }
            }
            input.collect { srcFrame ->
                try {
                    while (true) {
                        val sendRc = srcFrame.withNative { native -> ffkmp_graph_send(src, native) }
                        if (sendRc >= 0) break
                        if (sendRc == eagain) { drainEmit(); continue }  // not consumed, retry
                        throw FFmpegException(avError(sendRc))
                    }
                } finally {
                    srcFrame.close()  // buffersrc copied / reffed the buffer; release our hold.
                }
                drainEmit()
            }
            // Flush.
            while (true) {
                val flushRc = ffkmp_graph_send(src, null)
                if (flushRc >= 0 || flushRc == eof) break
                if (flushRc == eagain) { drainEmit(); continue }
                throw FFmpegException(avError(flushRc))
            }
            drainEmit()
        } finally {
            exitOperation()
            close()  // The graph cannot be reused after EOF, so release it with the flow.
        }
    }

    actual override fun close() {
        kotlinx.atomicfu.locks.synchronized(opLock) {
            if (!closed.compareAndSet(expect = false, update = true)) return
            // An operation is still inside native code, on this thread (a callback closing its own
            // graph) or another (which will block on opLock only for the non-suspending ops; a
            // running process() holds no lock). Mark closed so nothing new starts, and let the
            // outermost operation free the graph on its way out.
            if (operationDepth > 0) return
            freeNow()
        }
    }

    /** The one place the graph is actually freed. Only under [opLock], only once. */
    private fun freeNow() {
        if (freed) return
        freed = true
        outFrameHolder?.close()
        outFrameHolder = null
        val a = Arena()
        try {
            val gp = a.alloc<CPointerVar<kc_filter_graph>>().also { it.value = graph }
            ffkmp_graph_free(gp.ptr)
        } finally { a.clear() }
    }

    public actual companion object {
        @Throws(FFmpegException::class)
        public actual fun buildVideo(
            description: String,
            width: Int,
            height: Int,
            pixelFormat: PixelFormat,
            timeBase: Rational,
            frameRate: Rational,
            sampleAspectRatio: Rational,
        ): FilterGraph {
            // The FFmpeg identity gate, register item B1-02. Before the first allocation.
            requireCompatibleFFmpeg()
            val arena = Arena()
            val graphVar = arena.allocPointerTo<kc_filter_graph>()
            val srcVar = arena.allocPointerTo<kc_filter_ctx>()
            val sinkVar = arena.allocPointerTo<kc_filter_ctx>()

            val rc = ffkmp_graph_build_video(
                graphVar.ptr, srcVar.ptr, sinkVar.ptr,
                description,
                width, height, pixelFormatToAv(pixelFormat),
                timeBase.num, timeBase.den,
                frameRate.num, frameRate.den,
                sampleAspectRatio.num, sampleAspectRatio.den,
            )
            if (rc < 0) { arena.clear(); throw FFmpegException(avError(rc)) }

            val graph = graphVar.value!!
            val src = srcVar.value!!
            val sink = sinkVar.value!!
            arena.clear()
            return FilterGraph(graph, listOf(src), sink, MediaType.Video)
        }

        @Throws(FFmpegException::class)
        public actual fun buildAudio(
            description: String,
            sampleRate: Int,
            sampleFormat: SampleFormat,
            channels: Int,
            timeBase: Rational,
            outputSampleRate: Int,
            outputSampleFormat: SampleFormat,
            outputChannels: Int,
        ): FilterGraph {
            // The FFmpeg identity gate, register item B1-02. Before the first allocation.
            requireCompatibleFFmpeg()
            val arena = Arena()
            val graphVar = arena.allocPointerTo<kc_filter_graph>()
            val srcVar = arena.allocPointerTo<kc_filter_ctx>()
            val sinkVar = arena.allocPointerTo<kc_filter_ctx>()

            val outFmtAv = if (outputSampleFormat == SampleFormat.None) -1 else sampleFormatToAv(outputSampleFormat)
            val rc = ffkmp_graph_build_audio(
                graphVar.ptr, srcVar.ptr, sinkVar.ptr,
                description,
                sampleRate, sampleFormatToAv(sampleFormat), channels,
                timeBase.num, timeBase.den,
                outFmtAv, outputSampleRate, outputChannels,
            )
            if (rc < 0) { arena.clear(); throw FFmpegException(avError(rc)) }

            val graph = graphVar.value!!
            val src = srcVar.value!!
            val sink = sinkVar.value!!
            arena.clear()
            return FilterGraph(graph, listOf(src), sink, MediaType.Audio)
        }

        @Throws(FFmpegException::class)
        public actual fun buildVideoMulti(description: String, inputs: List<VideoInput>): FilterGraph {
            // The FFmpeg identity gate, register item B1-02. Before the first allocation.
            requireCompatibleFFmpeg()
            require(inputs.isNotEmpty()) { "Need at least one input" }
            memScoped {
                val n = inputs.size
                val graphVar = allocPointerTo<kc_filter_graph>()
                val sinkVar = allocPointerTo<kc_filter_ctx>()
                val srcsArr = allocArray<CPointerVar<kc_filter_ctx>>(n)
                val widths = allocArray<IntVar>(n); val heights = allocArray<IntVar>(n)
                val pixFmts = allocArray<IntVar>(n)
                val tbN = allocArray<IntVar>(n); val tbD = allocArray<IntVar>(n)
                val frN = allocArray<IntVar>(n); val frD = allocArray<IntVar>(n)
                val sarN = allocArray<IntVar>(n); val sarD = allocArray<IntVar>(n)
                inputs.forEachIndexed { i, inp ->
                    widths[i] = inp.width; heights[i] = inp.height
                    pixFmts[i] = pixelFormatToAv(inp.pixelFormat)
                    tbN[i] = inp.timeBase.num; tbD[i] = inp.timeBase.den
                    frN[i] = inp.frameRate.num; frD[i] = inp.frameRate.den
                    sarN[i] = inp.sampleAspectRatio.num; sarD[i] = inp.sampleAspectRatio.den
                }

                val rc = ffkmp_graph_build_video_multi(
                    graphVar.ptr, srcsArr, sinkVar.ptr,
                    description, n,
                    widths, heights, pixFmts, tbN, tbD, frN, frD, sarN, sarD,
                )
                if (rc < 0) throw FFmpegException(avError(rc))

                val srcs = (0 until n).map { srcsArr[it]!! }
                return FilterGraph(graphVar.value!!, srcs, sinkVar.value!!, MediaType.Video)
            }
        }

        @Throws(FFmpegException::class)
        public actual fun buildAudioMulti(
            description: String,
            inputs: List<AudioInput>,
            outputSampleRate: Int,
            outputSampleFormat: SampleFormat,
            outputChannels: Int,
        ): FilterGraph {
            // The FFmpeg identity gate, register item B1-02. Before the first allocation.
            requireCompatibleFFmpeg()
            require(inputs.isNotEmpty()) { "Need at least one input" }
            memScoped {
                val n = inputs.size
                val graphVar = allocPointerTo<kc_filter_graph>()
                val sinkVar = allocPointerTo<kc_filter_ctx>()
                val srcsArr = allocArray<CPointerVar<kc_filter_ctx>>(n)
                val rates = allocArray<IntVar>(n); val fmts = allocArray<IntVar>(n)
                val chans = allocArray<IntVar>(n)
                val tbN = allocArray<IntVar>(n); val tbD = allocArray<IntVar>(n)
                inputs.forEachIndexed { i, inp ->
                    rates[i] = inp.sampleRate
                    fmts[i] = sampleFormatToAv(inp.sampleFormat)
                    chans[i] = inp.channels
                    tbN[i] = inp.timeBase.num; tbD[i] = inp.timeBase.den
                }
                val outFmtAv = if (outputSampleFormat == SampleFormat.None) -1 else sampleFormatToAv(outputSampleFormat)

                val rc = ffkmp_graph_build_audio_multi(
                    graphVar.ptr, srcsArr, sinkVar.ptr,
                    description, n,
                    rates, fmts, chans, tbN, tbD,
                    outFmtAv, outputSampleRate, outputChannels,
                )
                if (rc < 0) throw FFmpegException(avError(rc))

                val srcs = (0 until n).map { srcsArr[it]!! }
                return FilterGraph(graphVar.value!!, srcs, sinkVar.value!!, MediaType.Audio)
            }
        }
    }
}
