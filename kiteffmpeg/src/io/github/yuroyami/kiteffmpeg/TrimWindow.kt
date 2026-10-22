package io.github.yuroyami.kiteffmpeg

/**
 * The trim window of a transcode, and the one place that decides what of a decoded frame it keeps.
 *
 * Both bounds are positions in the input, relative to the start of the content, and [endMicros]
 * is excluded: a window from 1 s to 2 s holds exactly one second. A video frame is kept whole or
 * not at all, by the time it starts. An audio frame is cut to the sample, the way FFmpeg's `atrim`
 * cuts it, so a window shorter than one decoded block still keeps its samples.
 *
 * A [startMicros] of 0 is no lower bound at all: audio priming with a negative timestamp passes.
 * A frame without a timestamp cannot be placed, so it is always inside.
 */
internal class TrimWindow(
    private val startMicros: Long,
    private val endMicros: Long,
    private val contentStartMicros: Long,
) {
    private val hasStart = startMicros > 0L
    private val hasEnd = endMicros != Long.MAX_VALUE

    private fun relativeMicros(info: FrameInfo): Long =
        rescaleQ(info.pts, info.timeBase, Rational.Tb_us) - contentStartMicros

    /** Where [info]'s first sample sits, counted in samples on the stream's own timeline. */
    private fun firstSample(info: FrameInfo): Long = rescaleQ(info.pts, info.timeBase, Rational(1, info.sampleRate))

    /** A bound in microseconds, moved onto the same sample grid as [firstSample]. */
    private fun sampleAt(relativeMicros: Long, sampleRate: Int): Long =
        rescaleQ(relativeMicros + contentStartMicros, Rational.Tb_us, Rational(1, sampleRate))

    /** True when [frame] starts at or after the end: nothing of it, and nothing after it, is kept. */
    fun isPastEnd(frame: Frame): Boolean {
        val info = frame.info
        if (!hasEnd || !info.hasPts) return false
        return if (info.type == MediaType.Audio && info.sampleRate > 0) {
            firstSample(info) >= sampleAt(endMicros, info.sampleRate)
        } else {
            relativeMicros(info) >= endMicros
        }
    }

    /** True when the video [frame] starts before the start, so it is not kept. */
    fun startsBeforeStart(frame: Frame): Boolean {
        val info = frame.info
        return hasStart && info.hasPts && relativeMicros(info) < startMicros
    }

    /**
     * The part of the audio [frame] inside the window: [frame] itself when all of it is, a new
     * owned frame holding only the kept samples when part of it is, and null when none of it is.
     * The caller still owns [frame] in every case.
     */
    fun keptAudio(frame: Frame): Frame? {
        val info = frame.info
        if (!info.hasPts || info.sampleRate <= 0 || info.sampleCount <= 0) return frame
        val first = firstSample(info)
        val count = info.sampleCount
        val from = if (!hasStart) 0 else (sampleAt(startMicros, info.sampleRate) - first).coerceIn(0L, count.toLong()).toInt()
        val until = if (!hasEnd) count else (sampleAt(endMicros, info.sampleRate) - first).coerceIn(0L, count.toLong()).toInt()
        return when {
            from >= until -> null
            from == 0 && until == count -> frame
            else -> cutSamples(frame, info, from, until)
        }
    }

    /**
     * Samples [from] up to [until] of [frame] as a new owned frame, its timestamp moved to the first
     * kept sample. FFmpeg's `atrim` makes the cut, so planar and interleaved data and every sample
     * type are cut the same way. The cut keeps the frame's own channel layout: a graph built for
     * FFmpeg's default layout refuses 5.1 with side surrounds, which is what AC-3 decodes to.
     */
    private fun cutSamples(frame: Frame, info: FrameInfo, from: Int, until: Int): Frame =
        FilterGraph.buildAudio(
            description = "atrim=start_sample=$from:end_sample=$until",
            sampleRate = info.sampleRate,
            sampleFormat = info.sampleFormat,
            channels = info.channelCount,
            timeBase = info.timeBase,
            channelLayoutMask = info.channelLayoutMask,
        ).use { graph ->
            var kept: Frame? = null
            val keep: (Frame) -> Unit = { output ->
                check(kept == null) { "atrim split one frame in two" }
                kept = output.copy()
            }
            graph.feedInput(0, frame.copy(), keep)
            graph.flushInput(0, keep)
            kept ?: throw FFmpegException(FFmpegError.Internal("atrim kept none of samples $from until $until"))
        }
}
