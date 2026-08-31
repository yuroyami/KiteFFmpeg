package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

private const val MAX_STARVED_ATTEMPTS = 2

public actual class FilterGraph internal constructor(
    private var graphToken: Long,
    private val sources: LongArray,
    private val sinkToken: Long,
    private val inputType: MediaType,
) : AutoCloseable {
    private var outFrameHolder: Frame? = null

    // Excludes close() while an operation is inside native code. The JNI handle table catches a
    // stale token after close, but it cannot keep the graph alive across a call that already
    // resolved it; this lock can. Lock order is graph → frame.
    private val lock = Any()

    private fun checkOpen(): Long {
        check(graphToken != 0L) { "FilterGraph is closed" }
        return graphToken
    }

    public actual val inputCount: Int get() = sources.size
    public actual val outputTimeBase: Rational = Internals.graphTimeBase(sinkToken)

    private fun outFrame(): Frame {
        checkOpen()
        return outFrameHolder
            ?: FrameOps.acquire(streamType = inputType, timeBase = outputTimeBase)
                .also { outFrameHolder = it }
    }

    @Throws(FFmpegException::class)
    public actual fun setOutputFrameSize(samples: Int): Unit = synchronized(lock) {
        checkOpen()
        require(samples > 0) { "frame size must be positive" }
        Internals.graphSetFrameSize(sinkToken, samples)
    }

    @Throws(FFmpegException::class)
    public actual fun feedInput(index: Int, frame: Frame, onOutput: (Frame) -> Unit): Unit = synchronized(lock) {
        try {
            checkOpen()
            val source = sources.getOrNull(index)
                ?: throw IllegalArgumentException("Input $index out of range (graph has ${sources.size} inputs)")
            sendUntilAccepted(index, eofIsDone = false, onOutput = onOutput) {
                frame.locked { Internals.graphSend(source, it) }
            }
            drainTo(onOutput)
        } finally {
            frame.close()
        }
    }

    @Throws(FFmpegException::class)
    public actual fun flushInput(index: Int, onOutput: (Frame) -> Unit): Unit = synchronized(lock) {
        checkOpen()
        val source = sources.getOrNull(index)
            ?: throw IllegalArgumentException("Input $index out of range (graph has ${sources.size} inputs)")
        sendUntilAccepted(index, eofIsDone = true, onOutput = onOutput) {
            Internals.graphSend(source, 0L)
        }
        drainTo(onOutput)
    }

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
            if (eofIsDone && rc == Internals.errorEof) return
            if (rc != Internals.errorEagain) throw FFmpegException(avError(rc))
            if (drainTo(onOutput)) {
                starvedAttempts = 0
            } else if (++starvedAttempts >= MAX_STARVED_ATTEMPTS) {
                throw FFmpegException(FFmpegError.InvalidArgument(0, starvedInputMessage(index)))
            }
        }
    }

    private fun starvedInputMessage(index: Int): String = if (sources.size > 1) {
        "Filter graph input $index would not take a frame and the sink produced nothing, twice in a " +
            "row. This graph has ${sources.size} inputs; feed every input and flush those whose " +
            "source has ended."
    } else {
        "Filter graph input $index would not take a frame and the sink produced nothing, twice in a " +
            "row, so retrying cannot make progress."
    }

    internal fun feedFrame(frame: Frame, onOutput: (Frame) -> Unit): Unit = feedInput(0, frame, onOutput)

    internal fun flushInto(onOutput: (Frame) -> Unit) {
        sources.indices.forEach { flushInput(it, onOutput) }
    }

    private fun drainTo(onOutput: (Frame) -> Unit): Boolean {
        val landing = outFrame()
        var produced = false
        while (true) {
            val rc = Internals.graphReceive(sinkToken, landing.checkOpen())
            if (rc == Internals.errorEagain || rc == Internals.errorEof) return produced
            if (rc < 0) throw FFmpegException(avError(rc))
            produced = true
            val callback = FrameOps.wrap(landing.checkOpen(), -1, inputType, outputTimeBase)
            try {
                onOutput(callback)
            } finally {
                callback.close()
            }
        }
    }

    public actual fun process(input: Flow<Frame>): Flow<Frame> = flow {
        checkOpen()
        check(sources.size == 1) { "process() drives single-input graphs; use feedInput for multi-input" }
        val source = sources[0]
        try {
            val landing = outFrame()
            // Each native call runs under the graph lock so a cross-thread close() cannot free the
            // graph mid-call; the lock is NOT held across emit(), which is a suspension point.
            suspend fun FlowCollector<Frame>.drainEmit() {
                while (true) {
                    val out = synchronized(lock) {
                        checkOpen()
                        val rc = Internals.graphReceive(sinkToken, landing.checkOpen())
                        if (rc == Internals.errorEagain || rc == Internals.errorEof) return@synchronized null
                        if (rc < 0) throw FFmpegException(avError(rc))
                        val callback = FrameOps.wrap(landing.checkOpen(), -1, inputType, outputTimeBase)
                        try {
                            callback.copy()
                        } finally {
                            callback.close()
                        }
                    } ?: return
                    try {
                        emit(out)
                    } catch (error: Throwable) {
                        out.close()
                        throw error
                    }
                }
            }
            input.collect { frame ->
                try {
                    while (true) {
                        val rc = synchronized(lock) {
                            checkOpen()
                            frame.locked { Internals.graphSend(source, it) }
                        }
                        if (rc >= 0) break
                        if (rc == Internals.errorEagain) {
                            drainEmit()
                            continue
                        }
                        throw FFmpegException(avError(rc))
                    }
                } finally {
                    frame.close()
                }
                drainEmit()
            }
            while (true) {
                val rc = synchronized(lock) {
                    checkOpen()
                    Internals.graphSend(source, 0L)
                }
                if (rc >= 0 || rc == Internals.errorEof) break
                if (rc == Internals.errorEagain) {
                    drainEmit()
                    continue
                }
                throw FFmpegException(avError(rc))
            }
            drainEmit()
        } finally {
            close()
        }
    }

    actual override fun close() {
        synchronized(lock) {
            if (graphToken == 0L) return
            val owned = graphToken
            graphToken = 0L
            outFrameHolder?.close()
            outFrameHolder = null
            Internals.graphFree(owned)
        }
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
            Internals.requireCompatible()
            return fromTokens(
                Internals.graphBuildVideo(
                    description,
                    width,
                    height,
                    pixelFormatToAv(pixelFormat),
                    timeBase,
                    frameRate,
                    sampleAspectRatio,
                ),
                1,
                MediaType.Video,
            )
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
            Internals.requireCompatible()
            val outFormat = if (outputSampleFormat == SampleFormat.None) {
                -1
            } else sampleFormatToAv(outputSampleFormat)
            return fromTokens(
                Internals.graphBuildAudio(
                    description,
                    sampleRate,
                    sampleFormatToAv(sampleFormat),
                    channels,
                    timeBase,
                    outFormat,
                    outputSampleRate,
                    outputChannels,
                ),
                1,
                MediaType.Audio,
            )
        }

        @Throws(FFmpegException::class)
        public actual fun buildVideoMulti(description: String, inputs: List<VideoInput>): FilterGraph {
            Internals.requireCompatible()
            require(inputs.isNotEmpty()) { "Need at least one input" }
            return fromTokens(
                Internals.graphBuildVideoMulti(
                    description = description,
                    count = inputs.size,
                    widths = inputs.map { it.width }.toIntArray(),
                    heights = inputs.map { it.height }.toIntArray(),
                    formats = inputs.map { pixelFormatToAv(it.pixelFormat) }.toIntArray(),
                    tbns = inputs.map { it.timeBase.num }.toIntArray(),
                    tbds = inputs.map { it.timeBase.den }.toIntArray(),
                    frns = inputs.map { it.frameRate.num }.toIntArray(),
                    frds = inputs.map { it.frameRate.den }.toIntArray(),
                    sarns = inputs.map { it.sampleAspectRatio.num }.toIntArray(),
                    sards = inputs.map { it.sampleAspectRatio.den }.toIntArray(),
                ),
                inputs.size,
                MediaType.Video,
            )
        }

        @Throws(FFmpegException::class)
        public actual fun buildAudioMulti(
            description: String,
            inputs: List<AudioInput>,
            outputSampleRate: Int,
            outputSampleFormat: SampleFormat,
            outputChannels: Int,
        ): FilterGraph {
            Internals.requireCompatible()
            require(inputs.isNotEmpty()) { "Need at least one input" }
            val outFormat = if (outputSampleFormat == SampleFormat.None) {
                -1
            } else sampleFormatToAv(outputSampleFormat)
            return fromTokens(
                Internals.graphBuildAudioMulti(
                    description = description,
                    count = inputs.size,
                    rates = inputs.map { it.sampleRate }.toIntArray(),
                    formats = inputs.map { sampleFormatToAv(it.sampleFormat) }.toIntArray(),
                    channels = inputs.map { it.channels }.toIntArray(),
                    tbns = inputs.map { it.timeBase.num }.toIntArray(),
                    tbds = inputs.map { it.timeBase.den }.toIntArray(),
                    outFormat = outFormat,
                    outRate = outputSampleRate,
                    outChannels = outputChannels,
                ),
                inputs.size,
                MediaType.Audio,
            )
        }

        private fun fromTokens(tokens: LongArray, count: Int, type: MediaType): FilterGraph {
            if (tokens.size != count + 2 || tokens.any { it == 0L }) {
                tokens.firstOrNull()?.takeIf { it != 0L }?.let(Internals::graphFree)
                throw FFmpegException(FFmpegError.Internal("Malformed filter graph handle result"))
            }
            return FilterGraph(
                graphToken = tokens[0],
                sources = tokens.copyOfRange(1, count + 1),
                sinkToken = tokens.last(),
                inputType = type,
            )
        }
    }
}
