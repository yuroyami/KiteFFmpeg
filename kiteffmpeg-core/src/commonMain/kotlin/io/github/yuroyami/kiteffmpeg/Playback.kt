package io.github.yuroyami.kiteffmpeg

/**
 * A demuxed packet the caller owns.
 *
 * A player queues packets independently of its decoders, so a packet must outlive the read that
 * produced it. The compressed payload remains reference counted; taking or copying ownership is an
 * O(1) reference operation, not a copy of the compressed bytes.
 *
 * Close every packet exactly once. Reading any property, copying it or sending it after close throws
 * rather than resolving memory or a token that the allocator is free to reuse.
 */
@KiteFFmpegLowLevelApi
public expect class Packet : AutoCloseable {
    /** The stream's time base, used by the raw timestamp properties below. */
    public val timeBase: Rational

    public val streamIndex: Int

    /** Presentation timestamp in [timeBase] units, or [FrameInfo.NOPTS] when absent. */
    public val pts: Long

    /** Decode timestamp in [timeBase] units, or [FrameInfo.NOPTS] when absent. */
    public val dts: Long

    /** Duration in [timeBase] units. Zero means unknown. */
    public val duration: Long

    public val isKeyframe: Boolean
    public val sizeBytes: Int

    /** Byte offset in the container, or -1 when unknown. */
    public val bytePosition: Long

    public val hasPts: Boolean

    /** [pts] converted to microseconds on the stream's own timeline, or null when absent. */
    public val ptsMicros: Long?

    /** [dts] converted to microseconds on the stream's own timeline, or null when absent. */
    public val dtsMicros: Long?

    /** [duration] converted to microseconds, or null when the container supplied none. */
    public val durationMicros: Long?

    /**
     * Returns a separately owned O(1) reference to this packet's compressed payload and metadata.
     * The two packets may be closed independently, in either order.
     */
    @KiteFFmpegLowLevelApi
    @Throws(FFmpegException::class)
    public fun copy(): Packet

    /**
     * The packet's compressed payload, copied (S4.c). For TEXT subtitle streams this is the cue
     * body itself, which is why a subtitle decoder can be pure Kotlin. A copy per call: subtitle
     * packets are tiny and rare; never call this per video packet.
     */
    public fun copyBytes(): ByteArray

    override fun close()
}

/** Which way a seek may land relative to the target. */
@KiteFFmpegLowLevelApi
public expect enum class SeekDirection {
    /** At or before the target, on a keyframe. */
    Backward,

    /** At or after the target. */
    Forward,

    /** The nearest indexed frame, whether or not it is a keyframe. */
    Any,
}

/**
 * Reads owned packets from one [MediaSource] cursor under the caller's control. This is the
 * demuxing half of a player, kept separate from decoding so audio and video decoding can proceed
 * independently and a seek can replace the caller's queued generation explicitly.
 *
 * Reading, seeking and closing must be serialized by the caller. One reader may be open for a
 * source at a time, and while it is open the source's batch decode and direct seek APIs are refused.
 * Closing restores the source's default stream selection.
 */
@KiteFFmpegLowLevelApi
public expect class PacketReader : AutoCloseable {
    /**
     * Returns the next selected-stream packet, or null at container EOF. The returned packet is
     * owned by the caller. Null is the signal to begin decoder drain by sending a null packet.
     */
    @Throws(FFmpegException::class)
    public fun read(): Packet?

    /**
     * Moves the demuxer cursor without flushing decoders or clearing caller-owned queues. The
     * caller must discard the old packet/frame generation and flush every decoder itself.
     *
     * A backward seek on an indexless container may otherwise land arbitrarily early, so
     * [notEarlierThan] can bound that search. The caller still verifies the first decoded timestamp,
     * because some containers may land after the target.
     *
     * @param micros target on the content-relative timeline
     * @param notEarlierThan optional lower bound for a backward seek
     */
    @Throws(FFmpegException::class)
    public fun seek(
        micros: Long,
        direction: SeekDirection = SeekDirection.Backward,
        notEarlierThan: Long? = null,
    )

    /**
     * Changes which streams [read] delivers without reopening this reader or moving its demuxer
     * cursor.
     *
     * Every entry must come from the [MediaSource.streams] list of the source that opened this
     * reader. The list must be non-empty and contain no duplicate indices. Invalid requests leave
     * the previous selection unchanged.
     *
     * This operation changes delivery from the demuxer's *current* cursor onward. It does not seek
     * backwards to recover packets from a newly selected stream, clear caller-owned queues or flush
     * decoders. A player that has read ahead and needs the new stream at its presentation position
     * must perform its own seek/cache refresh.
     *
     * Reading, seeking, reselecting and closing must be serialized by the caller. On JVM/Android
     * they are additionally mutually excluded inside the reader; callers must not rely on that
     * implementation detail for portable code.
     */
    @Throws(FFmpegException::class)
    public fun reselect(streams: List<StreamInfo>)

    override fun close()
}

/**
 * Canonicalizes a packet-reader selection against the immutable stream table published by its
 * source. StreamInfo is a public data class and can be forged, so an index alone is not enough:
 * accepting foreign timing metadata would stamp this source's packets with another source's time
 * base.
 */
internal fun canonicalPacketSelection(
    sourceStreams: List<StreamInfo>,
    requestedStreams: List<StreamInfo>,
): Map<Int, Rational> {
    require(requestedStreams.isNotEmpty()) { "Need at least one stream to read" }
    require(requestedStreams.distinctBy { it.index }.size == requestedStreams.size) {
        "Duplicate stream indices"
    }
    val sourceByIndex = sourceStreams.associateBy { it.index }
    requestedStreams.forEach { supplied ->
        require(sourceByIndex[supplied.index] == supplied) {
            "StreamInfo(index=${supplied.index}) does not belong to this MediaSource. Pass entries " +
                "from THIS source's streams list; stream identity is source-bound."
        }
    }
    return requestedStreams.associate { it.index to it.timeBase }
}

/**
 * One decoder driven explicitly by the caller.
 *
 * The send/receive shape preserves FFmpeg's state machine: one packet can produce no frames or
 * several frames, and the decoder may need its output queue drained before accepting more input.
 *
 * A false [send] means the packet was not consumed: drain [receive], then retry the same packet.
 * Send null at input EOF, receive until [isDrained], and call [flush] after every seek only after
 * discarding packets and frames from the old position. Sending a closed packet is rejected at the
 * call site rather than allowing a dangling payload to reach the decoder.
 */
@KiteFFmpegLowLevelApi
public expect class StreamDecoder : AutoCloseable {
    public val stream: StreamInfo

    /** True after the decoder reports EOF, until [flush]. */
    public var isDrained: Boolean
        private set

    /**
     * Packets and frames this decoder skipped as damaged since it was opened, or since [flush].
     *
     * Zero for healthy input. Non-zero means the decoded result is INCOMPLETE: under
     * [CorruptData.Skip], which is the default, damaged data is dropped and decoding continues,
     * and this counter is the only way to find out that it happened (audit P1-05). Under
     * [CorruptData.Fail] the first damage throws instead, so this stays zero.
     */
    public var corruptDataSkipped: Long
        private set

    /**
     * Offers [packet], or null to start the decoder drain.
     *
     * A packet belonging to a different stream is REFUSED rather than decoded. Feeding one used to
     * reach FFmpeg, which answered INVALIDDATA, which every backend swallowed as consumed, so the
     * input vanished and nothing said why (audit P1-03). The check is by stream index, which
     * catches the ordinary mistake of routing a packet to the wrong decoder; it cannot catch a
     * packet from a DIFFERENT source that happens to share the index, which needs source-scoped
     * packet handles and is a later change.
     *
     * @return true when consumed; false when output must be drained before retrying the same packet
     * @throws FFmpegException when [packet] is closed or belongs to another stream
     */
    @Throws(FFmpegException::class)
    public fun send(packet: Packet?): Boolean

    /**
     * Returns one owned frame, or null when more input is needed or the decoder is drained. Inspect
     * [isDrained] to distinguish those two null cases. The frame is an O(1) owned clone and may be
     * queued independently of later decoder calls.
     */
    @Throws(FFmpegException::class)
    public fun receive(): Frame?

    /**
     * Discards buffered decode state and makes the decoder accept a new seek generation. Call this
     * after clearing old caller-owned queues; doing it first would let an old packet enter a freshly
     * reset decoder. Clears [isDrained].
     */
    public fun flush()

    override fun close()
}

/**
 * Refuses a packet that does not belong to [stream], for every backend's [StreamDecoder.send].
 *
 * Null is the drain signal and always belongs. Everything else is checked by stream index: a packet
 * routed to the wrong decoder used to reach FFmpeg, come back as INVALIDDATA, and be swallowed as
 * consumed, so the input disappeared silently (audit P1-03).
 */
@OptIn(KiteFFmpegLowLevelApi::class)
internal fun requireOwnStream(packet: Packet?, stream: StreamInfo) {
    if (packet == null) return
    val index = packet.streamIndex
    if (index != stream.index) {
        throw FFmpegException(
            FFmpegError.InvalidArgument(
                0,
                "this packet belongs to stream $index and this decoder decodes stream " +
                    "${stream.index}. Route packets to the decoder for their own stream.",
            ),
        )
    }
}
