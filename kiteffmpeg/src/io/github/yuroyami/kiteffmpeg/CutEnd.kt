package io.github.yuroyami.kiteffmpeg

/**
 * Where the read of a cut stops: once every stream that keeps time has passed the end on its own,
 * or once the read runs a window past it (#233).
 *
 * A cut used to stop every stream at the first packet of one lead stream past the end. A demuxer
 * does not hand packets out in time order across streams, so the sound that sat later in the file
 * than the first late picture was never read and the cut's sound ended early. And a cover picture,
 * which is a video stream with one packet at the start, became the lead and never passed the end,
 * so a trimmed song was read to the end of its file. Now each stream stops at its own end. A
 * stream with no timeline, a cover picture or subtitles, is not waited for, and the window bounds
 * the read a stream that never reaches the end would cost.
 */
internal class CutEnd(waiting: Iterable<Int>, private val endMicros: Long) {
    private val open = waiting.toMutableSet()
    private var pastEnd = 0

    /**
     * Notes that stream [index] gave a packet or frame past the end, at [micros] when it is known,
     * and answers whether the read stops here. What is past the end is never written.
     */
    fun passed(index: Int, micros: Long = Long.MIN_VALUE): Boolean {
        open.remove(index)
        pastEnd++
        return open.isEmpty() ||
            (micros != Long.MIN_VALUE && micros > endMicros + WINDOW_MICROS) ||
            pastEnd > MAX_PAST_END
    }

    internal companion object {
        /** FFmpeg's own interleaving window, past which no stream still owes a packet before the end. */
        const val WINDOW_MICROS: Long = 1_000_000L

        /** Packets past the end read while a stream still owes some, before the read gives up on it. */
        const val MAX_PAST_END: Int = 4096

        /** The streams of [selected] a cut waits for: sound and pictures, not a cover or subtitles. */
        fun waitingFor(selected: List<StreamInfo>): List<Int> = selected
            .filter { (it.type == MediaType.Video && !it.disposition.attachedPicture) || it.type == MediaType.Audio }
            .map { it.index }
    }
}
