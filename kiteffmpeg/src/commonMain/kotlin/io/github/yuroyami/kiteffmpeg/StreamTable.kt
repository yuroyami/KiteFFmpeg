package io.github.yuroyami.kiteffmpeg

import kotlin.concurrent.Volatile

/**
 * A source's [MediaSource.streams] and [MediaSource.programs] as its reads change them (#151).
 *
 * FFmpeg adds a stream while it reads, as a transport stream's demuxer does when a new programme
 * table names one, and moves a stream into or out of a programme the same way, and it raises no flag
 * for either. So every read asks the context for its layout stamp, a number that moves when the
 * stream count or the programme table does, and the lists are read again only when it moved. An
 * entry read before FFmpeg had a packet of its stream is read again at the first one, because
 * FFmpeg's parser corrects what the programme table could not say, such as an MP2 sound that the
 * table names MP3 with no channels.
 *
 * Everything but the two lists and [owns] runs on the thread reading the source, which the source's
 * own rules already make one at a time. The lists are replaced, never changed in place, so another
 * thread reading them sees one whole list or the next.
 */
internal class StreamTable(
    streams: List<StreamInfo>,
    programs: List<Program>,
    /** The layout stamp now. */
    private val readStamp: () -> Long,
    /** The context's stream count now. */
    private val readCount: () -> Int,
    /** The entry of the stream at an index, as the open reads it. */
    private val readEntry: (Int) -> StreamInfo,
    /** The programme table, against the streams given. */
    private val readPrograms: (List<StreamInfo>) -> List<Program>,
) {
    @Volatile
    var streams: List<StreamInfo> = streams
        private set

    @Volatile
    var programs: List<Program> = programs
        private set

    /** Each entry that a later reading of its stream replaced, by index; each still names that stream. */
    @Volatile
    private var replaced: Map<Int, List<StreamInfo>> = emptyMap()

    private var stamp: Long = readStamp()

    /** The streams whose entry was read before FFmpeg had a packet of them. */
    private val unsettled: MutableSet<Int> = streams.filter { it.readBeforeItsPackets() }.mapTo(HashSet()) { it.index }

    private var streamsMoved = false
    private var programsMoved = false

    /**
     * Brings both lists up to date after a read that returned a packet of the stream at [packetIndex],
     * and answers the indexes of the streams this read added, usually none.
     */
    fun afterRead(packetIndex: Int): List<Int> {
        var added = emptyList<Int>()
        val now = readStamp()
        if (now != stamp) {
            stamp = now
            added = grow(packetIndex)
            val next = readPrograms(streams)
            if (next != programs) {
                programs = next
                programsMoved = true
            }
        }
        if (unsettled.isNotEmpty() && unsettled.remove(packetIndex)) settle(packetIndex)
        return added
    }

    private fun grow(packetIndex: Int): List<Int> {
        val known = streams
        val count = readCount()
        if (count <= known.size) return emptyList()
        val grown = ArrayList<StreamInfo>(count)
        grown.addAll(known)
        for (index in known.size until count) {
            grown += readEntry(index)
            // Read in the read that returned its own packet, so FFmpeg's parser has already seen it.
            if (index != packetIndex) unsettled += index
        }
        streams = grown
        streamsMoved = true
        return (known.size until count).toList()
    }

    private fun settle(index: Int) {
        val list = streams
        val old = list.getOrNull(index) ?: return
        val fresh = readEntry(index)
        if (fresh == old) return
        // The old entry joins the replaced ones before the list stops holding it, so [owns] never
        // misses it on another thread.
        replaced = replaced + (index to (replaced[index].orEmpty() + old))
        streams = list.toMutableList().also { it[index] = fresh }
        streamsMoved = true
    }

    /** The streams, once, when they changed since the last call; null when they did not. */
    fun takeStreamsChange(): List<StreamInfo>? {
        if (!streamsMoved) return null
        streamsMoved = false
        return streams
    }

    /** The programmes, once, when they changed since the last call; null when they did not. */
    fun takeProgramsChange(): List<Program>? {
        if (!programsMoved) return null
        programsMoved = false
        return programs
    }

    /**
     * Whether [info] names a stream of this source: it is the stream's entry, or one that entry
     * replaced. [StreamInfo] is a public data class and can be forged, and a forged entry with a valid
     * index here would read this source's packets with another source's time base.
     */
    fun owns(info: StreamInfo): Boolean =
        streams.getOrNull(info.index) == info || replaced[info.index]?.contains(info) == true

    /** Refuses an entry that does not name a stream of this source, as [owns] decides. */
    fun requireOwn(supplied: StreamInfo) {
        require(owns(supplied)) {
            "StreamInfo(index=${supplied.index}) does not belong to this MediaSource. Pass entries " +
                "from THIS source's streams list; stream identity is source-bound."
        }
    }
}

/**
 * Whether this entry may say less than FFmpeg's parser will know at the stream's first packet: its
 * codec is unknown, its sound has no sample rate or no channel count, or its picture has no size.
 */
private fun StreamInfo.readBeforeItsPackets(): Boolean =
    codec.name == "none" ||
        audio?.let { it.sampleRate <= 0 || it.channels <= 0 } == true ||
        video?.let { it.width <= 0 || it.height <= 0 } == true

/**
 * The packets a packet reader holds for the streams FFmpeg added while it read, until its caller
 * says whether it wants them (#151).
 *
 * A stream FFmpeg adds is not selected, so its packets would be skipped, but the caller hears of the
 * stream only on the next packet the reader hands out, and FFmpeg may hand out the new stream's first
 * packets before that. So a new stream waits: its packets are held from the read that added it until
 * the first [PacketReader.read] or [PacketReader.seek] after the announcement. A read then hands out
 * the held packets of every stream the caller selected meanwhile, before reading on, and drops the
 * rest, and the streams nobody selected stop waiting. A seek drops every held packet, because they
 * belong before it.
 *
 * [P] is the backend's packet; [sizeOf] measures one and [free] releases it.
 */
internal class HeldPackets<P : Any>(
    private val sizeOf: (P) -> Int,
    private val free: (P) -> Unit,
) {
    private class Held<P>(val index: Int, val packet: P, val size: Int)

    /** The streams added since the open whose packets are held rather than skipped. */
    private val waiting = HashSet<Int>()

    /** The waiting streams a handed-out packet announced. */
    private val announced = HashSet<Int>()

    private val held = ArrayDeque<Held<P>>()
    private var heldBytes = 0L

    /** The streams whose packets FFmpeg must keep handing out, besides the selected ones. */
    val waitingStreams: Set<Int> get() = waiting

    fun isWaiting(index: Int): Boolean = index in waiting

    /** Whether [decide] has anything to do: a stream was announced or a packet is held. */
    val hasDecisions: Boolean get() = announced.isNotEmpty() || held.isNotEmpty()

    /** Streams the last read added, which wait from now on. */
    fun added(indexes: List<Int>) {
        waiting += indexes
    }

    /**
     * Keeps [packet] of the waiting stream at [index]. Past [HELD_BYTES_LIMIT] the oldest held packet
     * goes, so a stream that waits for a long time, as one does when the selected streams fall silent
     * and no packet can announce it, cannot fill the memory; its later packets stay contiguous.
     */
    fun hold(index: Int, packet: P) {
        val size = sizeOf(packet)
        held.addLast(Held(index, packet, size))
        heldBytes += size
        while (heldBytes > HELD_BYTES_LIMIT && held.size > 1) {
            val oldest = held.removeFirst()
            heldBytes -= oldest.size
            free(oldest.packet)
        }
    }

    /** A packet carrying the new stream list went out, so every waiting stream is the caller's to choose now. */
    fun announce() {
        announced += waiting
    }

    /**
     * Settles every announced stream at a read or a seek: it stops waiting, and a stream not in
     * [selected] loses its held packets. A seek drops every held packet. Answers whether a stream
     * stopped waiting without being selected, so the reader must tell FFmpeg to skip it.
     */
    fun decide(selected: Set<Int>, seeking: Boolean): Boolean {
        var skipped = false
        for (index in announced) {
            waiting.remove(index)
            if (index !in selected) skipped = true
        }
        announced.clear()
        if (held.isNotEmpty()) {
            val kept = ArrayDeque<Held<P>>(held.size)
            for (entry in held) {
                if (!seeking && (entry.index in selected || entry.index in waiting)) {
                    kept.addLast(entry)
                } else {
                    heldBytes -= entry.size
                    free(entry.packet)
                }
            }
            held.clear()
            held.addAll(kept)
        }
        return skipped
    }

    /** The oldest held packet of a stream in [selected], taken out, or null. */
    fun next(selected: Set<Int>): Pair<Int, P>? {
        val iterator = held.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.index in selected) {
                iterator.remove()
                heldBytes -= entry.size
                return entry.index to entry.packet
            }
        }
        return null
    }

    /** Frees every held packet; the reader is closing. */
    fun clear() {
        while (held.isNotEmpty()) free(held.removeFirst().packet)
        heldBytes = 0
        waiting.clear()
        announced.clear()
    }

    companion object {
        /** How many bytes of packets the waiting streams may hold together: seconds of a broadcast. */
        const val HELD_BYTES_LIMIT: Long = 16L * 1024 * 1024
    }
}
