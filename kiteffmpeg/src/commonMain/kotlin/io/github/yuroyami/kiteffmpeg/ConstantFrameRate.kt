package io.github.yuroyami.kiteffmpeg

/**
 * Turns video frames with any timing into frames at one constant rate, the way FFmpeg's `fps`
 * filter does. Each output tick shows the latest input frame that starts at or before it, so a
 * faster input loses frames, a slower one repeats them, and the duration stays the same.
 *
 * Tick `k` starts at `k / rate` seconds on the input's own timeline: a timestamp becomes a tick by
 * rounding `timestamp * rate` to the nearest whole number. The first frame with a timestamp sets
 * the first tick, and a frame without one before it is dropped. At the end, the last frame
 * repeats until the tick where it ends.
 *
 * Where the last frame ends depends on [durationsHold]. A frame straight from a decoder ends where
 * its duration says, as in FFmpeg, unless that duration is one unit of its time base and the gap
 * before it is more than twice as long. That is the duration FFmpeg's demuxers and muxers make up
 * for a stream that states none, and what a stream written at a fine constant rate to place
 * frames at uneven times says, so FFmpeg's command line takes the gap instead
 * (`video_duration_estimate` in `fftools/ffmpeg_dec.c`), and so does this. After a filter the
 * duration can be stale, because `setpts` moves timestamps and leaves durations as they were, so
 * a filtered frame is taken to last as long as the gap before it. Either way, a frame with
 * neither lasts one tick.
 *
 * The caller keeps every frame it pushes. This class holds its own copy of the latest one, and
 * hands that copy to `emit` once for every tick it fills; `emit` must not close it.
 *
 * The input's timing decides how many ticks one push or [finish] fills, with no upper limit: two
 * frames a day apart fill a day of ticks. `emit` is where the caller checks for cancellation.
 */
internal class ConstantFrameRate(rate: Rational, private val durationsHold: Boolean) : AutoCloseable {
    init {
        require(rate.num > 0) { "the output frame rate must be positive, not $rate" }
    }

    /** The time base that ticks are counted in: one output frame interval. */
    val tickBase: Rational = rate.inverse

    private var held: Frame? = null
    private var heldEnd = 0L
    private var started = false
    private var nextTick = 0L
    private var previousPts = FrameInfo.NOPTS
    private var previousTimeBase: Rational? = null

    /** Takes the next input [frame], and emits every tick that the frame closes. */
    fun push(frame: Frame, emit: (Frame, Long) -> Unit) {
        val info = frame.info
        // FFmpeg answers a rescale it cannot represent with the no-timestamp value.
        val tick = if (info.hasPts) rescaleQ(info.pts, info.timeBase, tickBase) else FrameInfo.NOPTS
        if (tick == FrameInfo.NOPTS) {
            // Nothing can place it. It takes the held frame's place and keeps its end.
            if (held != null) hold(frame, heldEnd)
            return
        }
        if (!started) {
            nextTick = tick
            started = true
        }
        held?.let { current ->
            while (nextTick < tick) emit(current, nextTick++)
        }
        hold(frame, endTick(info, tick))
        previousPts = info.pts
        previousTimeBase = info.timeBase
    }

    /** Ends the input: the held frame repeats up to the tick where it ends. */
    fun finish(emit: (Frame, Long) -> Unit) {
        val current = held ?: return
        while (nextTick < heldEnd) emit(current, nextTick++)
        close()
    }

    /** Releases the held frame without emitting it. */
    override fun close() {
        held?.close()
        held = null
    }

    private fun hold(frame: Frame, end: Long) {
        val copy = frame.copy()
        held?.close()
        held = copy
        heldEnd = end
    }

    private fun endTick(info: FrameInfo, tick: Long): Long {
        // A gap too wide for a Long wraps below zero, and then counts as no gap.
        val gap = if (previousTimeBase == info.timeBase && info.pts > previousPts) info.pts - previousPts else 0L
        val madeUp = info.duration == 1L && gap > 2L
        val length = when {
            durationsHold && info.duration > 0L && !madeUp -> info.duration
            gap > 0L -> gap
            else -> info.duration
        }
        val end = if (length > 0L && info.pts <= Long.MAX_VALUE - length) {
            rescaleQ(info.pts + length, info.timeBase, tickBase)
        } else {
            FrameInfo.NOPTS
        }
        // A frame whose end cannot be represented lasts one tick, like a frame with no length.
        if (end != FrameInfo.NOPTS) return end
        return if (tick < Long.MAX_VALUE) tick + 1 else tick
    }
}
