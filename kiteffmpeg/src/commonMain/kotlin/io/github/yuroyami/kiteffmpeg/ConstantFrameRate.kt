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
 * its duration says, as in FFmpeg. After a filter the duration can be stale, because `setpts`
 * moves timestamps and leaves durations as they were, so a filtered frame is taken to last as
 * long as the gap before it. Either way, a frame with neither lasts one tick.
 *
 * The caller keeps every frame it pushes. This class holds its own copy of the latest one, and
 * hands that copy to `emit` once for every tick it fills; `emit` must not close it.
 */
internal class ConstantFrameRate(rate: Rational, private val durationsHold: Boolean) : AutoCloseable {
    /** The time base that ticks are counted in: one output frame interval. */
    val tickBase: Rational = rate.inverse

    private var held: Frame? = null
    private var heldEnd = 0L
    private var nextTick = Long.MIN_VALUE
    private var previousPts = FrameInfo.NOPTS
    private var previousTimeBase: Rational? = null

    /** Takes the next input [frame], and emits every tick that the frame closes. */
    fun push(frame: Frame, emit: (Frame, Long) -> Unit) {
        val info = frame.info
        if (!info.hasPts) {
            // Nothing can place it. It takes the held frame's place and keeps its end.
            if (held != null) hold(frame, heldEnd)
            return
        }
        val tick = rescaleQ(info.pts, info.timeBase, tickBase)
        if (nextTick == Long.MIN_VALUE) nextTick = tick
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
        val gap = if (previousTimeBase == info.timeBase && info.pts > previousPts) info.pts - previousPts else 0L
        val length = when {
            durationsHold && info.duration > 0L -> info.duration
            gap > 0L -> gap
            else -> info.duration
        }
        return if (length > 0L) rescaleQ(info.pts + length, info.timeBase, tickBase) else tick + 1
    }
}
