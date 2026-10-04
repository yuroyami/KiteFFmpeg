package io.github.yuroyami.kiteffmpeg

/**
 * Where an output's timeline starts: the source time that becomes its zero, shared by every stream
 * so that they keep their places against each other, and the copied packets written before it is
 * known (#153).
 *
 * The origin is the earliest time the output shows anything. Copied audio shows from its first
 * packet plus the samples FFmpeg marks to be skipped there, which is how an AAC or Opus stream
 * hides its encoder's priming and how an MP4 edit list hides the samples before it starts. Copied
 * video shows from its earliest picture, leaving out the pictures FFmpeg marks as discarded. An
 * encoded frame shows from its own time. That is FFmpeg's own reading of where a stream starts, so
 * a whole remux is a copy. The origin used to be the decode time of the first packet written,
 * which comes earlier than anything shown for B-frame video and for priming, so the whole output
 * moved later by the difference and the priming was played.
 *
 * A stream's earliest packet is not always its first, because B-frames decode after a picture they
 * show before. No packet decodes after it shows, so a stream has shown its earliest time once one
 * of its packets decodes at or after it. Copied packets are held, in the order they were written,
 * until every audio and video stream has done that, or until the copy has read [SETTLE_MICROS] of
 * decode time past the earliest time found, for a stream with nothing near the start, or until
 * something has to be written now: an encoded frame, the header, or the close. Subtitle and data
 * streams are sparse, so they are not waited for, and they count only while no audio or video
 * stream has shown anything.
 *
 * Not thread safe: the sink calls it under its mux lock. [P] is the platform's packet handle, and
 * [free] releases one that is never handed back.
 */
internal class OutputOrigin<P : Any>(private val free: (P) -> Unit) {

    private class Lane(val awaited: Boolean) {
        /** The earliest time this stream shows, or [Long.MAX_VALUE] while it has shown nothing. */
        var earliest = Long.MAX_VALUE

        /** True once a packet of this stream decodes at or after [earliest], or an encoded frame came. */
        var settled = false
    }

    private class Held<P>(val lane: Int, val packet: P)

    private val lanes = ArrayList<Lane>()
    private var held = ArrayList<Held<P>>()

    /** The earliest and the latest decode time the held packets carry, in microseconds. */
    private var decodedFrom = Long.MAX_VALUE
    private var decodedTo = Long.MIN_VALUE

    /** The origin in microseconds, or [UNSET] until [settle] decides it. */
    var micros: Long = UNSET
        private set

    val isSettled: Boolean get() = micros != UNSET

    val isHolding: Boolean get() = held.isNotEmpty()

    /** Adds a stream, waited for when [awaited], which audio and video are. Returns its lane. */
    fun addLane(awaited: Boolean): Int {
        lanes += Lane(awaited)
        return lanes.lastIndex
    }

    /**
     * Holds [packet] of [lane], which shows from [shownMicros], or [NOTHING] when it shows nothing,
     * and decodes at [decodedMicros], or [NOTHING] when no time says. Returns true when the origin
     * can now be settled. Only before [isSettled]; afterwards a packet is written at once.
     */
    fun hold(lane: Int, packet: P, shownMicros: Long, decodedMicros: Long): Boolean {
        check(!isSettled) { "the origin is settled, so packets are written rather than held" }
        val stream = lanes[lane]
        held += Held(lane, packet)
        if (shownMicros != NOTHING && shownMicros < stream.earliest) stream.earliest = shownMicros
        if (decodedMicros != NOTHING) {
            if (decodedMicros < decodedFrom) decodedFrom = decodedMicros
            if (decodedMicros > decodedTo) decodedTo = decodedMicros
            if (stream.earliest != Long.MAX_VALUE && decodedMicros >= stream.earliest) stream.settled = true
        }
        return isReady()
    }

    private fun isReady(): Boolean {
        // Packets that carry no time at all bound the wait by their number instead.
        if (held.size >= HOLD_LIMIT) return true
        val earliest = earliest()
        if (earliest == Long.MAX_VALUE) return decodedTo != Long.MIN_VALUE && decodedTo - decodedFrom >= SETTLE_MICROS
        if (lanes.all { !it.awaited || it.settled }) return true
        return decodedTo != Long.MIN_VALUE && decodedTo - earliest >= SETTLE_MICROS
    }

    /** The earliest time an audio or video stream shows, or a sparse one while none has. */
    private fun earliest(): Long {
        val awaited = lanes.filter { it.awaited }.minOfOrNull { it.earliest } ?: Long.MAX_VALUE
        return if (awaited != Long.MAX_VALUE) awaited else lanes.minOfOrNull { it.earliest } ?: Long.MAX_VALUE
    }

    /**
     * Decides the origin: the earliest time shown so far, or [atMost] when that is earlier, which is
     * how an encoded frame takes part, or [fallback] when nothing has shown. Returns it.
     */
    fun settle(atMost: Long = Long.MAX_VALUE, fallback: Long = 0L): Long {
        check(!isSettled) { "the origin is already settled" }
        val earliest = minOf(earliest(), atMost)
        micros = if (earliest == Long.MAX_VALUE) fallback else earliest
        return micros
    }

    /**
     * Hands every held packet to [write], in the order they were written, with its lane. Each is
     * the caller's from then on. When [write] throws, the packets after it are freed and the
     * failure goes on.
     */
    fun release(write: (lane: Int, packet: P) -> Unit) {
        check(isSettled) { "held packets wait for the origin" }
        val taken = held
        held = ArrayList()
        var next = 0
        try {
            while (next < taken.size) {
                val item = taken[next++]
                write(item.lane, item.packet)
            }
        } finally {
            while (next < taken.size) free(taken[next++].packet)
        }
    }

    /** Frees every held packet, for a sink that closes before it could write them. */
    fun discard() {
        val taken = held
        held = ArrayList()
        taken.forEach { free(it.packet) }
    }

    companion object {
        const val UNSET: Long = Long.MIN_VALUE
        const val NOTHING: Long = Long.MIN_VALUE

        /**
         * How far past the earliest time found the copy reads before it stops waiting for a stream
         * that has shown nothing. FFmpeg's MP4 reader takes samples in file order while the
         * streams are within a second of each other, so a stream can deliver its first packet up
         * to that much behind another, and FFmpeg counts a subtitle stream's start towards a
         * file's only within a second of the others.
         */
        const val SETTLE_MICROS: Long = 1_000_000L

        /** How many packets are held at most, for packets that carry no time to measure that second by. */
        const val HOLD_LIMIT: Int = 1024
    }
}
