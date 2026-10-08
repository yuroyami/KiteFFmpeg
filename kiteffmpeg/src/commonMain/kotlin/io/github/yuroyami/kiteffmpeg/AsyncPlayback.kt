@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.DecoderSkip
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads the packets of an [AsyncMediaSource]. It is the suspending form of [PacketReader]. A
 * source has one read position, so it has one open reader at a time.
 */
@KiteFFmpegLowLevelApi
public class AsyncPacketReader internal constructor(
    private val lane: AsyncLane,
    private val owner: AsyncMediaSource,
    private val reader: PacketReader,
) : AsyncCloseable {

    internal val handle = AsyncHandle(lane, "AsyncPacketReader") { reader.close() }

    init {
        // Made with the lane held. The source closes this reader before it closes itself.
        owner.input.reader = handle
    }

    /**
     * The next packet of the selected streams, or null at the end. The read may wait for the
     * byte source. The caller owns the packet and closes it.
     */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun read(): AsyncPacket? {
        handle.requireOpen()
        return lane.demux(owner.input, dispose = { it?.handle?.discard() }) {
            val packet = lane.engine.readPacket(reader) ?: return@demux null
            lane.engine.immediate { owner.capturePacket(packet) }
        }
    }

    /** Moves the read position. The parameters mean what they mean in [PacketReader.seek]. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun seek(
        micros: Long,
        direction: SeekDirection = SeekDirection.Backward,
        notEarlierThan: Long? = null,
    ) {
        handle.requireOpen()
        lane.demux(owner.input) {
            lane.engine.seek(reader, micros, direction, notEarlierThan)
            lane.engine.immediate { owner.refresh() }
        }
    }

    /** Changes the streams this reader hands out. See [PacketReader.reselect]. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun reselect(streams: List<StreamInfo>) {
        handle.requireOpen()
        lane.immediate { reader.reselect(streams) }
    }

    /** Gives the read position back to the source. */
    @Throws(Exception::class)
    override suspend fun close() {
        handle.close()
    }
}

/**
 * One packet of an [AsyncMediaRuntime]. It is the suspending form of [Packet]. Every property
 * was read when the packet was made, so a getter never waits and never enters FFmpeg.
 */
@KiteFFmpegLowLevelApi
public class AsyncPacket internal constructor(private val lane: AsyncLane, internal val packet: Packet) : AsyncCloseable {

    // Read with the lane held, as the constructor runs there. The handle comes last, so a
    // failed read leaves nothing registered.
    private val capturedTimeBase: Rational = packet.timeBase
    private val capturedStreamIndex: Int = packet.streamIndex
    private val capturedPts: Long = packet.pts
    private val capturedDts: Long = packet.dts
    private val capturedDuration: Long = packet.duration
    private val capturedKeyframe: Boolean = packet.isKeyframe
    private val capturedSize: Int = packet.sizeBytes
    private val capturedPosition: Long = packet.bytePosition
    private val capturedHasPts: Boolean = packet.hasPts
    private val capturedPtsMicros: Long? = packet.ptsMicros
    private val capturedDtsMicros: Long? = packet.dtsMicros
    private val capturedDurationMicros: Long? = packet.durationMicros
    private val capturedContainerTags: Map<String, String>? = packet.newContainerTags
    private val capturedStreamTags: Map<String, String>? = packet.newStreamTags
    private val capturedStreams: List<StreamInfo>? = packet.newStreams
    private val capturedPrograms: List<Program>? = packet.newPrograms

    internal val handle = AsyncHandle(lane, "AsyncPacket") { packet.close() }

    /** True when the read of this packet changed what the container says about itself. */
    internal val changesContainer: Boolean
        get() = capturedContainerTags != null || capturedStreamTags != null || capturedStreams != null || capturedPrograms != null

    private inline fun <T> open(value: () -> T): T {
        handle.requireOpen()
        return value()
    }

    public val timeBase: Rational get() = open { capturedTimeBase }
    public val streamIndex: Int get() = open { capturedStreamIndex }
    public val pts: Long get() = open { capturedPts }
    public val dts: Long get() = open { capturedDts }
    public val duration: Long get() = open { capturedDuration }
    public val isKeyframe: Boolean get() = open { capturedKeyframe }
    public val sizeBytes: Int get() = open { capturedSize }
    public val bytePosition: Long get() = open { capturedPosition }
    public val hasPts: Boolean get() = open { capturedHasPts }
    public val ptsMicros: Long? get() = open { capturedPtsMicros }
    public val dtsMicros: Long? get() = open { capturedDtsMicros }
    public val durationMicros: Long? get() = open { capturedDurationMicros }
    public val newContainerTags: Map<String, String>? get() = open { capturedContainerTags }
    public val newStreamTags: Map<String, String>? get() = open { capturedStreamTags }
    public val newStreams: List<StreamInfo>? get() = open { capturedStreams }
    public val newPrograms: List<Program>? get() = open { capturedPrograms }

    /** A second reference to the same data, which the caller owns and closes. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun copy(): AsyncPacket {
        handle.requireOpen()
        return lane.immediate(dispose = { it.handle.discard() }) {
            val cloned = packet.copy()
            try {
                AsyncPacket(lane, cloned)
            } catch (failure: Throwable) {
                cloned.close()
                throw failure
            }
        }
    }

    /** The packet's compressed bytes, copied out. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun copyBytes(): ByteArray {
        handle.requireOpen()
        return lane.immediate { packet.copyBytes() }
    }

    @Throws(Exception::class)
    override suspend fun close() {
        handle.close()
    }
}

/** Decodes the packets of one stream. It is the suspending form of [StreamDecoder]. */
@KiteFFmpegLowLevelApi
public class AsyncStreamDecoder internal constructor(private val lane: AsyncLane, private val decoder: StreamDecoder) : AsyncCloseable {

    /** The stream this decoder decodes. */
    public val stream: StreamInfo = decoder.stream

    @Volatile
    private var drained: Boolean = decoder.isDrained

    @Volatile
    private var skippedRaw: Long = decoder.corruptDataSkipped

    @Volatile
    private var skippedBase: Long = 0L

    internal val handle = AsyncHandle(lane, "AsyncStreamDecoder") { decoder.close() }

    /** True once the decoder gave its last frame after a drain. */
    public val isDrained: Boolean
        get() {
            handle.requireOpen()
            return drained
        }

    /** How many damaged packets and frames this decoder skipped. */
    public val corruptDataSkipped: Long
        get() {
            handle.requireOpen()
            return skippedNow
        }

    /** [corruptDataSkipped], also after the close. */
    internal val skippedNow: Long get() = skippedRaw - skippedBase

    /** Copies the decoder's counters. Runs with the lane held. */
    private fun sync() {
        drained = decoder.isDrained
        skippedRaw = decoder.corruptDataSkipped
    }

    /**
     * Offers [packet] to the decoder, or the drain signal when it is null. False means the
     * decoder did not take it: receive its frames and offer the same packet again. A packet of
     * another runtime is refused.
     */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun send(packet: AsyncPacket?): Boolean {
        handle.requireOpen()
        if (packet != null) {
            require(packet.handle.lane === lane) {
                "this packet belongs to another AsyncMediaRuntime. A packet stays in the runtime that read it."
            }
            packet.handle.requireOpen()
        }
        return lane.immediate {
            try {
                decoder.send(packet?.packet)
            } finally {
                sync()
            }
        }
    }

    /** The next decoded frame, or null when the decoder needs more input. The caller closes it. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun receive(): AsyncFrame? {
        handle.requireOpen()
        return lane.immediate(dispose = { it?.handle?.discard() }) {
            val frame = try {
                decoder.receive()
            } finally {
                sync()
            }
            frame?.let { wrapFrame(lane, it) }
        }
    }

    /** Drops what the decoder holds, as after a seek. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun flush() {
        handle.requireOpen()
        lane.immediate {
            decoder.flush()
            sync()
            skippedBase = 0L
        }
    }

    /** Tells a video decoder which frames to skip. See [StreamDecoder.setSkipFrame]. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun setSkipFrame(skip: DecoderSkip) {
        handle.requireOpen()
        lane.immediate { decoder.setSkipFrame(skip) }
    }

    /** Sets [corruptDataSkipped] back to zero. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun resetCorruptDataSkipped() {
        handle.requireOpen()
        skippedBase = skippedRaw
    }

    @Throws(Exception::class)
    override suspend fun close() {
        handle.close()
    }
}

/** Decodes subtitle packets. It is the suspending form of [SubtitleDecoder]. */
@KiteFFmpegLowLevelApi
public class AsyncSubtitleDecoder internal constructor(private val lane: AsyncLane, private val decoder: SubtitleDecoder) : AsyncCloseable {

    internal val handle = AsyncHandle(lane, "AsyncSubtitleDecoder") { decoder.close() }

    /** The subtitle [packet] holds, or null when it holds none. A packet of another runtime is refused. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun decode(packet: AsyncPacket): Subtitle? {
        handle.requireOpen()
        require(packet.handle.lane === lane) {
            "this packet belongs to another AsyncMediaRuntime. A packet stays in the runtime that read it."
        }
        packet.handle.requireOpen()
        return lane.immediate { decoder.decode(packet.packet) }
    }

    /** The subtitle the decoder still holds at the end of the stream, or null. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun drain(): Subtitle? {
        handle.requireOpen()
        return lane.immediate { decoder.drain() }
    }

    /** Drops what the decoder holds, as after a seek. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun flush() {
        handle.requireOpen()
        lane.immediate { decoder.flush() }
    }

    @Throws(Exception::class)
    override suspend fun close() {
        handle.close()
    }
}

/**
 * One decoded frame of an [AsyncMediaRuntime]. It is the suspending form of [Frame]. [info] was
 * read when the frame was made. Everything that reads the frame's data waits for the lane.
 */
public class AsyncFrame internal constructor(private val lane: AsyncLane, internal val frame: Frame) : AsyncCloseable {

    // Read with the lane held, as the constructor runs there.
    internal val capturedInfo: FrameInfo = frame.info

    internal val capturedPtsMicros: Long? =
        if (capturedInfo.hasPts) rescaleQ(capturedInfo.pts, capturedInfo.timeBase, Rational.Tb_us) else null

    private val capturedDurationMicros: Long? =
        if (capturedInfo.duration > 0L) rescaleQ(capturedInfo.duration, capturedInfo.timeBase, Rational.Tb_us) else null

    internal val handle = AsyncHandle(lane, "AsyncFrame") { frame.close() }

    /** What the frame holds: its size, format, time and colour. */
    public val info: FrameInfo
        get() {
            handle.requireOpen()
            return capturedInfo
        }

    /** The frame's time in microseconds, or null when it has none. */
    @KiteFFmpegLowLevelApi
    public val ptsMicros: Long?
        get() {
            handle.requireOpen()
            return capturedPtsMicros
        }

    /** The frame's duration in microseconds, or null when it has none. */
    @KiteFFmpegLowLevelApi
    public val durationMicros: Long?
        get() {
            handle.requireOpen()
            return capturedDurationMicros
        }

    private suspend fun <T> read(block: (Frame) -> T): T {
        handle.requireOpen()
        return lane.immediate { block(frame) }
    }

    private suspend fun derive(block: (Frame) -> Frame): AsyncFrame {
        handle.requireOpen()
        return lane.immediate(dispose = { it.handle.discard() }) { wrapFrame(lane, block(frame)) }
    }

    /** A second reference to the same data, which the caller owns and closes. */
    @KiteFFmpegLowLevelApi
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun copy(): AsyncFrame = derive { it.copy() }

    /** How many bytes [copyPlanesInto] writes. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun planesByteCount(): Int = read { it.planesByteCount() }

    /** Copies the frame's planes into [destination] and answers the count. See [Frame.copyPlanesInto]. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun copyPlanesInto(destination: ByteArray): Int = read { it.copyPlanesInto(destination) }

    /** The frame's planes in a new array. See [Frame.copyPlanesToByteArray]. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun copyPlanesToByteArray(): ByteArray = read { it.copyPlanesToByteArray() }

    /** The closed caption bytes the frame carries, or null. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun closedCaptions(): ByteArray? = read { it.closedCaptions() }

    /** The Dolby Vision metadata the frame carries, or null. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun dolbyVision(): DolbyVisionMetadata? = read { it.dolbyVision() }

    /** The Dolby Vision RPU the frame carries, or null. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun dolbyVisionRpu(): DolbyVisionRpu? = read { it.dolbyVisionRpu() }

    /** A copy of a hardware frame in main memory. See [Frame.downloadFromHardware]. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun downloadFromHardware(): AsyncFrame = derive { it.downloadFromHardware() }

    @Throws(Exception::class)
    override suspend fun close() {
        handle.close()
    }
}

/** Wraps [frame] and frees it when the wrap fails. Runs with the lane held. */
internal fun wrapFrame(lane: AsyncLane, frame: Frame): AsyncFrame = try {
    AsyncFrame(lane, frame)
} catch (failure: Throwable) {
    frame.close()
    throw failure
}
