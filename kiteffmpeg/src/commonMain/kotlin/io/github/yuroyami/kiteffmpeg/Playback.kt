package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.DecoderSkip

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

    /** [duration] converted to microseconds, or null when the container supplied none or one that is not positive. */
    public val durationMicros: Long?

    /**
     * The container's tags as they stand from this packet on, present only on the first packet a
     * read hands out after FFmpeg applied new container tags during playback, and null on every
     * other packet. A station's ICY title through FFmpeg's own `http`, an ID3 tag between ADTS
     * frames and an FLV `onMetaData` arrive this way. It is the whole new set, which
     * [MediaSource.metadata] holds from the same read on. A [copy] carries it too.
     */
    public val newContainerTags: Map<String, String>?

    /**
     * This packet's stream's tags as they stand from this packet on, present only on the first
     * packet of that stream a read hands out after FFmpeg applied new tags to it during playback,
     * and null on every other packet. The next song of a chained Ogg brings its comments this way,
     * as the whole set, without the keys of the song before it, and a timed ID3 packet of an
     * MPEG-TS or HLS data stream carries what it states on itself, so read that data stream to
     * receive them. [StreamInfo.metadata] keeps what the stream said at open. A [copy] carries it too.
     */
    public val newStreamTags: Map<String, String>?

    /**
     * The source's [MediaSource.streams] as they stand from this packet on, present only on the first
     * packet a read hands out after FFmpeg added a stream or an entry was read again at its stream's
     * first packet, and null on every other packet (#151). It is the whole list, so a stream is new
     * when its index is past the end of the list before, and corrected when its entry differs. A
     * change that a decode flow read, or that the reader before this one read and never handed a
     * packet out after, rides this reader's first packet.
     *
     * The packets FFmpeg handed out, before this one, of a stream that is new in this list are held:
     * add the stream with [PacketReader.reselect] before the next [PacketReader.read], and that read
     * hands them out first, so the stream arrives from its first packet. They come after this packet,
     * although FFmpeg read them before it, which a player that queues each stream apart does not
     * notice. Read on or seek without the stream and they are dropped, and the stream is skipped as
     * any stream the reader does not select is. At most 16 MB of them are held, the oldest going
     * first, which matters only when the selected streams fall silent for long enough that no packet
     * announces the new one. A [copy] carries it too.
     */
    public val newStreams: List<StreamInfo>?

    /**
     * The source's [MediaSource.programs] as they stand from this packet on, present only on the
     * first packet a read hands out after FFmpeg changed them, and null on every other packet (#151).
     * A stream the container stopped carrying has left its programme here. A [copy] carries it too.
     */
    public val newPrograms: List<Program>?

    /**
     * Returns a separately owned O(1) reference to this packet's compressed payload and metadata.
     * The two packets may be closed independently, in either order.
     */
    @KiteFFmpegLowLevelApi
    @Throws(FFmpegException::class)
    public fun copy(): Packet

    /**
     * The packet's compressed payload, copied. For TEXT subtitle streams this is the cue
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

    /**
     * The nearest indexed frame, whether or not it is a keyframe. A demuxer without its own
     * two-sided seek, which includes MP4, Matroska, MPEG-TS, AVI and FLV, looks backward from the
     * target only.
     */
    Any,
}

/**
 * The lowest and the highest container-absolute time a seek to [target] in [direction] may land on,
 * as `avformat_seek_file` takes them.
 *
 * A demuxer without its own two-sided seek, which includes MP4, Matroska, MPEG-TS, AVI and FLV,
 * picks its direction from which side of this window is nearer the target, so an unbounded floor
 * turned every Forward seek backward. Forward is floored at the target itself.
 */
@OptIn(KiteFFmpegLowLevelApi::class)
internal fun seekWindow(target: Long, direction: SeekDirection, notEarlierThan: Long?): Pair<Long, Long> =
    when (direction) {
        SeekDirection.Backward -> (notEarlierThan ?: Long.MIN_VALUE) to target
        SeekDirection.Forward -> maxOf(target, notEarlierThan ?: target) to Long.MAX_VALUE
        // Any. An expect enum cannot be matched exhaustively here, so it is the remainder.
        else -> (notEarlierThan ?: Long.MIN_VALUE) to Long.MAX_VALUE
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
     *
     * After [reselect] added a stream that [Packet.newStreams] announced, the packets of it that
     * were held for that come first.
     */
    @Throws(FFmpegException::class)
    public fun read(): Packet?

    /**
     * Moves the demuxer cursor without flushing decoders or clearing caller-owned queues. The
     * caller must discard the old packet/frame generation and flush every decoder itself.
     *
     * A backward seek on an indexless container may otherwise land arbitrarily early, so
     * [notEarlierThan] can bound that search.
     *
     * A backward seek lands on the last keyframe that shows at or before [micros], and no earlier
     * than [notEarlierThan], in the source's first video stream that is not a cover picture, when
     * this reader selects it; the first packet of that stream it reads is that keyframe. FFmpeg
     * finds a keyframe by when it decodes, which with B-frames is earlier than when it shows, so the
     * seek reads on to the keyframe it landed on and aims earlier when that one shows too late, and
     * [read] then hands out what the seek read before it reads anything new. Keyframes more than
     * 32 MB of input apart are not checked. The other selected streams start where the container
     * puts them beside that keyframe. A [SeekDirection.Forward] or [SeekDirection.Any] seek is
     * FFmpeg's own and is not checked. Check the first decoded timestamp when the exact landing
     * matters.
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
     * Every entry must name a stream of the source that opened this reader, by its entry in
     * [MediaSource.streams] or by an entry that one replaced. The list must be non-empty and contain
     * no duplicate indices. Invalid requests leave the previous selection unchanged. A stream FFmpeg
     * added while reading can be selected as soon as it is listed; [Packet.newStreams] says which of
     * its packets wait for that.
     *
     * This operation changes delivery from the demuxer's *current* cursor onward. It does not seek
     * backwards to recover packets from a newly selected stream, clear caller-owned queues or flush
     * decoders. A player that has read ahead and needs the new stream at its presentation position
     * must perform its own seek/cache refresh.
     *
     * Between a [seek] and the first [read] after it, a newly selected stream starts where that
     * seek would have landed it had the stream been selected for it. After a read, the cursor a
     * newly selected stream joins at can be ahead of the last packet read by what the seek read to
     * check its keyframe.
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
 * Canonicalizes a packet-reader selection against the stream table of its source. StreamInfo is a
 * public data class and can be forged, so an index alone is not enough: accepting foreign timing
 * metadata would stamp this source's packets with another source's time base. An entry that a later
 * reading of its stream replaced still names it (#151), and the time base is the stream's own as the
 * table lists it now.
 */
internal fun canonicalPacketSelection(
    table: StreamTable,
    requestedStreams: List<StreamInfo>,
): Map<Int, Rational> {
    require(requestedStreams.isNotEmpty()) { "Need at least one stream to read" }
    require(requestedStreams.distinctBy { it.index }.size == requestedStreams.size) {
        "Duplicate stream indices"
    }
    requestedStreams.forEach(table::requireOwn)
    val current = table.streams
    return requestedStreams.associate { it.index to (current.getOrNull(it.index)?.timeBase ?: it.timeBase) }
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
     * and this counter is the only way to find out that it happened. Under
     * [CorruptData.Fail] the first damage throws instead, so this stays zero.
     */
    public var corruptDataSkipped: Long
        private set

    /**
     * Offers [packet], or null to start the decoder drain.
     *
     * A packet belonging to a different stream is REFUSED rather than decoded. Feeding one used to
     * reach FFmpeg, which answered INVALIDDATA, which every backend swallowed as consumed, so the
     * input vanished and nothing said why. The check is by stream index, which
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

    /**
     * Sets which frames this video decoder skips, from the next packet [send] offers.
     *
     * This is FFmpeg's `skip_frame`, the setting
     * [DecoderOptions.skipFrame][io.github.yuroyami.kiteffmpeg.dsl.DecoderOptions.skipFrame] gives
     * a decoder when it opens. A decoder reads it as it decodes each packet, so a caller can raise
     * it for a stretch of a stream and lower it again with no [flush], for example to skip the
     * frames nothing will show on the way to a precise seek target. [DecoderSkip.None] decodes
     * every frame, as a decoder opened without the setting does.
     *
     * Each decoder decides which frames a level covers, and a decoder that does not read the setting
     * decodes every frame whatever it holds. [DecoderSkip.NonReference] skips frames no other frame
     * predicts from, so an H.264 decode lowered from it at any packet goes on exactly as one that
     * never skipped. A level that skips frames others predict from, such as [DecoderSkip.NonKey],
     * leaves the frames decoded after it is lowered damaged until the next keyframe.
     *
     * @throws FFmpegException with [FFmpegError.InvalidArgument] when [stream] is not video,
     *         because only video decoders read this setting
     */
    @Throws(FFmpegException::class)
    public fun setSkipFrame(skip: DecoderSkip)

    override fun close()
}

/**
 * Refuses [StreamDecoder.setSkipFrame] on a stream that is not video, for every backend.
 *
 * Only video decoders read FFmpeg's `skip_frame`, so on any other stream the call would set a field
 * nothing reads and the caller would never learn that nothing was skipped.
 */
internal fun requireSkippableStream(stream: StreamInfo) {
    if (stream.type != MediaType.Video) {
        throw FFmpegException(
            FFmpegError.InvalidArgument(
                0,
                "stream ${stream.index} is ${stream.type.name.lowercase()}, and only a video decoder " +
                    "reads frame skipping.",
            ),
        )
    }
}

/**
 * Refuses a packet that does not belong to [stream], for every backend's [StreamDecoder.send].
 *
 * Null is the drain signal and always belongs. Everything else is checked by stream index: a packet
 * routed to the wrong decoder used to reach FFmpeg, come back as INVALIDDATA, and be swallowed as
 * consumed, so the input disappeared silently.
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
