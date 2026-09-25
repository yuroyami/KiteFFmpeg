package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.FilterChain
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** Per-input description for [FilterGraph.buildVideoMulti]. */
public data class VideoInput(
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val timeBase: Rational,
    val frameRate: Rational,
    val sampleAspectRatio: Rational = Rational(1, 1),
)

/** Per-input description for [FilterGraph.buildAudioMulti]. */
public data class AudioInput(
    val sampleRate: Int,
    val sampleFormat: SampleFormat,
    val channels: Int,
    val timeBase: Rational,
    /**
     * Which speaker each channel belongs to, as an FFmpeg channel mask, such as a decoded frame's
     * [FrameInfo.channelLayoutMask]. Null means FFmpeg's default layout for [channels]. A frame
     * whose layout differs from the declared one is refused by the graph.
     */
    val channelLayoutMask: Long? = null,
)

/** What a [FilterGraph.feedInput] or [FilterGraph.flushInput] call produced, and what the graph needs next. */
public sealed interface FeedResult {
    /** How many frames the call handed to its callback. */
    public val produced: Int

    /** The graph can take more on any input. A graph with one input always answers this. */
    public data class Ready(override val produced: Int) : FeedResult

    /**
     * The graph cannot produce more until input [index] gets a frame or a flush: a filter with
     * several inputs, such as `overlay` or `amix`, waits for every one of them. A frame the graph
     * could not take yet stays with it and goes in first on the next call for its input.
     */
    public data class NeedsInput(val index: Int, override val produced: Int) : FeedResult
}

/**
 * A compiled `libavfilter` graph. Build with [FilterGraph.buildVideo] / [FilterGraph.buildAudio]
 * (single input) or [buildVideoMulti] / [buildAudioMulti] (N inputs: overlay, amix, …).
 * Feed frames through [process] (single input) or [feedInput] (any input). Close it when done.
 * [feedInput] and [flushInput] answer with a [FeedResult], which names the input a graph with several
 * inputs waits for.
 *
 * A graph is single-shot: once EOF has been flushed through it, it cannot accept more frames.
 * [process] closes the graph itself when its returned flow terminates; push-style users call
 * [close] (idempotent) when finished.
 *
 * Multi-input descriptions reference pads by label: inputs are `[in0]`…`[inN-1]`, the output is
 * `[out]`, e.g. `"[in0][in1]overlay=10:10[out]"` or `"[in0][in1]amix=inputs=2[out]"`.
 */
public class FilterGraph internal constructor(private val backend: FilterBackend) : AutoCloseable {

    /**
     * Serializes every native call on this graph against every other and against [close]. A
     * caller's callback never runs under it: output is taken under the lock as owned frames and
     * handed over after the lock is released, so a callback that waits on another thread, which
     * itself needs this graph, cannot stop both.
     */
    private val lock = SynchronizedObject()
    private var closed = false
    private var spent = false
    private var freed = false

    /**
     * Operations inside native code right now. [process] holds one across its suspending emits,
     * where it cannot hold [lock], so a [close] from another thread or from the collector marks
     * the graph closed and the last operation frees it on its way out.
     */
    private var operations = 0

    /**
     * Per input, what it would not take yet, oldest first: frames, and a null for its flush. All of
     * it goes in before anything newer for the same input.
     */
    private val waiting = Array(backend.inputCount) { ArrayDeque<Frame?>() }

    /** Inputs whose flush was asked for. They take no more frames. */
    private val flushed = BooleanArray(backend.inputCount)

    /** Number of buffersrc inputs this graph was built with. */
    public val inputCount: Int get() = backend.inputCount

    /** Time-base of frames leaving the graph. Filters like `fps`/`atempo` may change it. */
    public val outputTimeBase: Rational get() = backend.outputTimeBase

    private inline fun <R> operation(block: () -> R): R = synchronized(lock) {
        check(!closed) { closedMessage() }
        operations++
        try {
            block()
        } finally {
            operations--
            if (closed && operations == 0) freeNow()
        }
    }

    private fun requireUnspent() {
        check(!spent) { SPENT_MESSAGE }
    }

    /** Why a closed graph refuses: [process] closes the graph it spends, and that is the reason to name. */
    private fun closedMessage(): String = if (spent) SPENT_MESSAGE else "FilterGraph is closed"

    /**
     * Fixed-size sample chunking for the buffersink (audio graphs only). Encoders such as AAC
     * accept exactly `frameSize` samples per call; setting this makes the graph emit that.
     */
    @Throws(FFmpegException::class)
    public fun setOutputFrameSize(samples: Int) {
        require(samples > 0) { "frame size must be positive" }
        operation { backend.setOutputFrameSize(samples) }
    }

    /**
     * Push one frame into input [index]; every output frame that becomes available is handed
     * to [onOutput]. Closes [frame] (the graph keeps its own reference). Output frames are
     * valid only for the duration of the callback; [Frame.copy] to keep one.
     *
     * [onOutput] runs after this graph's lock is released, so it may call back into the graph,
     * [close] included; frames it has not seen yet stay valid, because each is its own reference.
     *
     * @return how many frames came out, and whether the graph now waits for a particular input
     * @throws IllegalStateException when input [index] was flushed
     */
    @Throws(FFmpegException::class)
    public fun feedInput(index: Int, frame: Frame, onOutput: (Frame) -> Unit): FeedResult {
        val outputs = ArrayList<Frame>()
        val result = try {
            operation {
                requireUnspent()
                requireInput(index)
                check(!flushed[index]) { "Input $index is flushed and takes no more frames." }
                // Anything the input would not take earlier goes first; a refused frame waits too.
                if (!sendWaiting(index, outputs) || !offer(index, outputs) { backend.send(index, frame) }) {
                    waiting[index].addLast(frame.copy())
                }
                drain(outputs)
                resultOf(outputs.size)
            }
        } catch (failure: Throwable) {
            outputs.forEach(Frame::close)
            throw failure
        } finally {
            frame.close()
        }
        deliver(outputs, onOutput)
        return result
    }

    /**
     * Signal EOF on input [index] and drain whatever the graph can produce. Filters like
     * `overlay` emit their final frames only once every input is flushed. Flush every input,
     * in any order. The last call delivers the remaining frames.
     *
     * [onOutput] runs after this graph's lock is released, as for [feedInput].
     *
     * @return how many frames came out, and whether the graph now waits for a particular input
     */
    @Throws(FFmpegException::class)
    public fun flushInput(index: Int, onOutput: (Frame) -> Unit): FeedResult {
        val outputs = ArrayList<Frame>()
        val result = try {
            operation {
                requireUnspent()
                requireInput(index)
                // A second flush of the same input adds nothing.
                if (!flushed[index]) {
                    flushed[index] = true
                    waiting[index].addLast(null)
                }
                sendWaiting(index, outputs)
                drain(outputs)
                resultOf(outputs.size)
            }
        } catch (failure: Throwable) {
            outputs.forEach(Frame::close)
            throw failure
        }
        deliver(outputs, onOutput)
        return result
    }

    private fun requireInput(index: Int) {
        require(index in 0 until backend.inputCount) {
            "Input $index out of range (graph has ${backend.inputCount} inputs)"
        }
    }

    /**
     * Makes one send to input [index], and makes it again each time the graph makes room by
     * producing output into [outputs]. False when the input would not take it and the graph
     * produced nothing: a graph with several inputs then waits for another one, and [resultOf]
     * names it. A graph with one input has no other input to wait for, so that is an error.
     *
     * [send] is a parameter so a test can drive the refusal: the FFmpeg this binds to answers a
     * buffer source write with 0 or a hard error and never with EAGAIN.
     */
    internal fun offer(index: Int, outputs: MutableList<Frame>, send: () -> Int): Boolean {
        while (true) {
            val rc = send()
            if (rc >= 0) return true
            if (!backend.isAgain(rc)) throw FFmpegException(backend.error(rc))
            if (drain(outputs)) continue
            if (backend.inputCount > 1) return false
            throw FFmpegException(
                FFmpegError.Internal(
                    "Filter graph input $index would not take a frame and produced nothing. The graph " +
                        "has no other input to wait for, so it can never take this frame.",
                ),
            )
        }
    }

    /** Sends what input [index] would not take earlier, oldest first. False when something still waits. */
    private fun sendWaiting(index: Int, outputs: MutableList<Frame>): Boolean {
        val queue = waiting[index]
        while (queue.isNotEmpty()) {
            val next = queue.first()
            val taken = if (next != null) {
                offer(index, outputs) { backend.send(index, next) }
            } else {
                // An input already at its end is done, not broken.
                offer(index, outputs) { backend.send(index, null).let { rc -> if (backend.isEof(rc)) 0 else rc } }
            }
            if (!taken) return false
            queue.removeFirst()?.close()
        }
        return true
    }

    /**
     * [produced], and the input the graph now waits for: the one it asked most often for a frame
     * it did not have, which is how FFmpeg's own command line chose its next input. A graph with
     * one input has nothing to choose between.
     */
    private fun resultOf(produced: Int): FeedResult {
        if (backend.inputCount < 2) return FeedResult.Ready(produced)
        var wanted = -1
        var most = 0
        for (i in 0 until backend.inputCount) {
            val requests = backend.failedRequests(i)
            if (requests > most) {
                most = requests
                wanted = i
            }
        }
        return if (wanted < 0) FeedResult.Ready(produced) else FeedResult.NeedsInput(wanted, produced)
    }

    /** Moves every frame the sink has ready into [outputs]; true when at least one came out. */
    private fun drain(outputs: MutableList<Frame>): Boolean {
        var produced = false
        while (true) {
            val out = backend.receive() ?: return produced
            outputs += out
            produced = true
        }
    }

    /** Hands [outputs] to [onOutput] one by one, closing each after its call and the rest on a throw. */
    private fun deliver(outputs: List<Frame>, onOutput: (Frame) -> Unit) {
        for (i in outputs.indices) {
            try {
                onOutput(outputs[i])
            } catch (failure: Throwable) {
                for (j in i + 1 until outputs.size) outputs[j].close()
                throw failure
            } finally {
                outputs[i].close()
            }
        }
    }

    /** Single-input convenience used by Transcoder. */
    internal fun feedFrame(frame: Frame, onOutput: (Frame) -> Unit) {
        feedInput(0, frame, onOutput)
    }

    /** Flush every input, then drain. After this the graph cannot accept more frames. */
    internal fun flushInto(onOutput: (Frame) -> Unit) {
        for (i in 0 until backend.inputCount) flushInput(i, onOutput)
    }

    /**
     * Drive [input] through the graph (single-input graphs only), emitting each processed frame
     * owned by the collector. Closes every input frame once consumed.
     *
     * One shot: the graph takes [input] to its end and is closed when the flow ends, because a
     * graph that has seen its end of stream cannot take more. A second call, or a [feedInput]
     * after it, fails with [IllegalStateException] naming the graph as spent. Build a new graph
     * for another stream.
     *
     * @see Frame for the ownership rule every emitted frame is subject to
     */
    public fun process(input: Flow<Frame>): Flow<Frame> {
        synchronized(lock) {
            requireUnspent()
            check(!closed) { "FilterGraph is closed" }
            check(backend.inputCount == 1) { "process() drives single-input graphs; use feedInput for multi-input" }
            spent = true
        }
        return flow {
            // The ledger without the lock across emits: a lock held across a suspension is a
            // deadlock waiting for a dispatcher. The ledger is what keeps a concurrent close from
            // freeing the graph mid-collection; the free waits for the finally below.
            synchronized(lock) {
                check(!closed) { closedMessage() }
                operations++
            }
            try {
                input.collect { frame ->
                    val outputs = ArrayList<Frame>()
                    try {
                        synchronized(lock) {
                            // One input: offer takes the frame or throws.
                            offer(0, outputs) { backend.send(0, frame) }
                            drain(outputs)
                        }
                    } catch (failure: Throwable) {
                        outputs.forEach(Frame::close)
                        throw failure
                    } finally {
                        frame.close()
                    }
                    emitOwned(outputs)
                }
                val outputs = ArrayList<Frame>()
                try {
                    synchronized(lock) {
                        offer(0, outputs) { backend.send(0, null).let { rc -> if (backend.isEof(rc)) 0 else rc } }
                        drain(outputs)
                    }
                } catch (failure: Throwable) {
                    outputs.forEach(Frame::close)
                    throw failure
                }
                emitOwned(outputs)
            } finally {
                synchronized(lock) {
                    operations--
                    closed = true
                    if (operations == 0) freeNow()
                }
            }
        }
    }

    /**
     * Emits each of [outputs], owned by the collector. A frame that reached emit is the
     * collector's even when emit then throws: `first` and `take` end a flow by throwing out of
     * emit after the value was delivered, and nothing here can tell that apart from a failed
     * delivery, so only the frames that never reached emit are closed.
     */
    private suspend fun FlowCollector<Frame>.emitOwned(outputs: List<Frame>) {
        for (i in outputs.indices) {
            try {
                emit(outputs[i])
            } catch (failure: Throwable) {
                for (j in i + 1 until outputs.size) outputs[j].close()
                throw failure
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed && freed) return
            closed = true
            if (operations == 0) freeNow()
        }
    }

    /** The one place the graph is freed. Only under [lock], only once. */
    private fun freeNow() {
        if (freed) return
        freed = true
        for (queue in waiting) {
            queue.forEach { it?.close() }
            queue.clear()
        }
        backend.free()
    }

    public companion object {
        /**
         * @param description filter chain, e.g. `scale=1280:720,eq=brightness=0.1,format=yuv420p`
         * @param width  input frame width
         * @param height input frame height
         * @param pixelFormat input pixel format (e.g. [PixelFormat.Yuv420p])
         * @param timeBase pts time-base of input frames
         * @param frameRate frame rate of input, used by `setpts`/`fps`-style filters
         * @param sampleAspectRatio SAR of input frames; default 1:1 for square pixels
         */
        @Throws(FFmpegException::class)
        public fun buildVideo(
            description: String,
            width: Int,
            height: Int,
            pixelFormat: PixelFormat,
            timeBase: Rational,
            frameRate: Rational,
            sampleAspectRatio: Rational = Rational(1, 1),
        ): FilterGraph = FilterGraph(
            buildVideoBackend(description, width, height, pixelFormat, timeBase, frameRate, sampleAspectRatio),
        )

        /**
         * Build a single-input audio graph. When the `output*` parameters are given, an
         * `aformat` stage is appended so emitted frames arrive encoder-ready (resampled /
         * reformatted / remixed inside the graph).
         *
         * The composed chain (the description plus any appended `aformat` stage) must fit in
         * 2048 bytes. A description that does not leave room is refused with
         * [FFmpegError.InvalidArgument]; it is never silently truncated.
         *
         * @param description filter chain, e.g. `volume=0.5,atempo=1.25`. Empty or `anull`
         *                    means passthrough.
         * @param sampleRate input sample rate
         * @param sampleFormat input sample format (decoder output, e.g. fltp)
         * @param channels input channel count
         * @param timeBase pts time-base of input frames
         * @param channelLayoutMask which speaker each input channel belongs to, as an FFmpeg
         *                          channel mask; pass the decoded frame's
         *                          [FrameInfo.channelLayoutMask]. Null means FFmpeg's default
         *                          layout for [channels], and a frame with another layout, such as
         *                          5.1 with side surrounds, is refused by the graph.
         * @param outputChannelLayoutMask the exact layout the output is converted to, when
         *                                [outputChannels] alone is ambiguous. Its channel count
         *                                must equal [outputChannels].
         */
        @Throws(FFmpegException::class)
        public fun buildAudio(
            description: String,
            sampleRate: Int,
            sampleFormat: SampleFormat,
            channels: Int,
            timeBase: Rational,
            outputSampleRate: Int = 0,
            outputSampleFormat: SampleFormat = SampleFormat.None,
            outputChannels: Int = 0,
            channelLayoutMask: Long? = null,
            outputChannelLayoutMask: Long? = null,
        ): FilterGraph = FilterGraph(
            buildAudioBackend(
                description, sampleRate, sampleFormat, channels, timeBase,
                outputSampleRate, outputSampleFormat, outputChannels, channelLayoutMask, outputChannelLayoutMask,
            ),
        )

        /**
         * N-input video graph for compositions. `"[in0][in1]overlay=W-w-10:H-h-10[out]"` puts
         * input 1 as a watermark in input 0's bottom-right corner.
         */
        @Throws(FFmpegException::class)
        public fun buildVideoMulti(description: String, inputs: List<VideoInput>): FilterGraph {
            require(inputs.isNotEmpty()) { "Need at least one input" }
            return FilterGraph(buildVideoMultiBackend(description, inputs))
        }

        /**
         * N-input audio graph. `"[in0][in1]amix=inputs=2:duration=longest[out]"` mixes two
         * tracks.
         *
         * The `output*` parameters append an `aformat` stage exactly as [buildAudio] does, but
         * only when [description] does not carry an explicit `[out]` label. A description that
         * labels its own output controls its own formats, so nothing is appended.
         *
         * The same 2048 byte bound as [buildAudio] applies to the composed chain, with the same
         * refusal rather than truncation.
         */
        @Throws(FFmpegException::class)
        public fun buildAudioMulti(
            description: String,
            inputs: List<AudioInput>,
            outputSampleRate: Int = 0,
            outputSampleFormat: SampleFormat = SampleFormat.None,
            outputChannels: Int = 0,
            outputChannelLayoutMask: Long? = null,
        ): FilterGraph {
            require(inputs.isNotEmpty()) { "Need at least one input" }
            return FilterGraph(
                buildAudioMultiBackend(description, inputs, outputSampleRate, outputSampleFormat, outputChannels, outputChannelLayoutMask),
            )
        }
    }
}

private const val SPENT_MESSAGE = "This FilterGraph is spent: process() took it to the end of its stream, and " +
    "a graph that has seen its end cannot take more. Build a new graph."

/**
 * The native half of a [FilterGraph], one per backend: the graph's own calls and nothing that
 * decides anything. The orchestration above it is the same on every backend.
 */
internal interface FilterBackend {
    val inputCount: Int
    val outputTimeBase: Rational
    fun setOutputFrameSize(samples: Int)

    /** Sends [frame] to input [index], or its end of stream when null; FFmpeg's return code. [frame] stays the caller's. */
    fun send(index: Int, frame: Frame?): Int

    /** The next output as a frame the caller owns, or null when the sink has nothing more for now or ever. */
    fun receive(): Frame?

    /** How often the graph asked input [index] for a frame it did not have, since its last frame. */
    fun failedRequests(index: Int): Int

    fun isAgain(rc: Int): Boolean
    fun isEof(rc: Int): Boolean
    fun error(rc: Int): FFmpegError

    /** Frees the graph and everything it holds. Called once, under the graph's lock. */
    fun free()
}

@Throws(FFmpegException::class)
internal expect fun buildVideoBackend(
    description: String,
    width: Int,
    height: Int,
    pixelFormat: PixelFormat,
    timeBase: Rational,
    frameRate: Rational,
    sampleAspectRatio: Rational,
): FilterBackend

@Throws(FFmpegException::class)
internal expect fun buildAudioBackend(
    description: String,
    sampleRate: Int,
    sampleFormat: SampleFormat,
    channels: Int,
    timeBase: Rational,
    outputSampleRate: Int,
    outputSampleFormat: SampleFormat,
    outputChannels: Int,
    channelLayoutMask: Long?,
    outputChannelLayoutMask: Long?,
): FilterBackend

@Throws(FFmpegException::class)
internal expect fun buildVideoMultiBackend(description: String, inputs: List<VideoInput>): FilterBackend

@Throws(FFmpegException::class)
internal expect fun buildAudioMultiBackend(
    description: String,
    inputs: List<AudioInput>,
    outputSampleRate: Int,
    outputSampleFormat: SampleFormat,
    outputChannels: Int,
    outputChannelLayoutMask: Long?,
): FilterBackend

/**
 * Builds a video graph from a typed [FilterChain], refusing before FFmpeg is asked to parse
 * anything when this build lacks one of the chain's filters.
 *
 * The refusal is [FilterChain.requireAvailable]'s: one [FFmpegError.FilterNotFound] naming every
 * missing filter. Ask [FilterChain.missingFilters] first if you would rather branch than catch.
 *
 * An extension on the companion rather than a member of it, which keeps the string overloads the
 * one set of builders every backend implements.
 */
public fun FilterGraph.Companion.buildVideo(
    chain: FilterChain,
    width: Int,
    height: Int,
    pixelFormat: PixelFormat,
    timeBase: Rational,
    frameRate: Rational,
    sampleAspectRatio: Rational = Rational(1, 1),
): FilterGraph {
    chain.requireAvailable()
    return buildVideo(chain.compile(), width, height, pixelFormat, timeBase, frameRate, sampleAspectRatio)
}

/** The audio half of [buildVideo]'s chain overload, with the same refusal. */
public fun FilterGraph.Companion.buildAudio(
    chain: FilterChain,
    sampleRate: Int,
    sampleFormat: SampleFormat,
    channels: Int,
    timeBase: Rational,
    outputSampleRate: Int = 0,
    outputSampleFormat: SampleFormat = SampleFormat.None,
    outputChannels: Int = 0,
    channelLayoutMask: Long? = null,
    outputChannelLayoutMask: Long? = null,
): FilterGraph {
    chain.requireAvailable()
    return buildAudio(
        chain.compile(), sampleRate, sampleFormat, channels, timeBase,
        outputSampleRate, outputSampleFormat, outputChannels, channelLayoutMask, outputChannelLayoutMask,
    )
}
