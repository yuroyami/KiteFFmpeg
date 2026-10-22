@file:OptIn(KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.DecoderOptions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads media whose bytes may answer later, from an [AsyncMediaByteSource].
 *
 * A runtime owns one lane, which is the one place it runs codec work. Every operation of every
 * handle it gave out waits its turn there, so the sources of one runtime take turns and the
 * sources of two runtimes do not wait for each other. On the JVM, Android and native the lane is a
 * thread. On the web it is a codec module of its own, apart from the one [MediaSource] uses.
 *
 * Create one with `FFmpeg.createAsyncRuntime()`, or on the web with
 * `KiteFFmpegWeb.loadAsyncRuntime`. Close it when every source is done: the close also closes
 * every source, reader, decoder, packet and frame that is still open.
 *
 * While one source waits for bytes, every other operation of the same runtime waits too, the copy
 * and the close of a frame included. Copy the pictures and the sound you need before you start a
 * read that may wait.
 */
public class AsyncMediaRuntime internal constructor(internal val lane: AsyncLane) : AsyncCloseable {

    /** What FFmpeg this runtime runs. On the web it describes the runtime's own codec module. */
    public val identity: FFmpegIdentity get() = lane.engine.identity

    /**
     * Opens media from [io]. The parameters mean what they mean in the byte-source
     * [MediaSource.open], and [nestedOpener] serves the other addresses the media names.
     *
     * The runtime owns [io] from this call on, also when the open fails or its caller is
     * cancelled, and closes it once. An object the runtime already holds is refused with
     * [IllegalArgumentException], and is not closed.
     */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun open(
        io: AsyncMediaByteSource,
        options: Map<String, String> = emptyMap(),
        interrupt: OpenInterrupt? = null,
        url: String? = null,
        mimeType: String? = null,
        nestedOpener: AsyncMediaByteOpener? = null,
    ): AsyncMediaSource = lane.open(io, options, interrupt, url, mimeType, nestedOpener)

    /**
     * Stops every source, waits for the work in the lane, then closes every handle and source.
     * The runtime refuses every operation from this call on.
     */
    @Throws(Exception::class)
    override suspend fun close() {
        lane.close()
    }
}

/**
 * What a container says about itself, as one snapshot. It never changes: an operation that
 * changes the container publishes a new snapshot in [AsyncMediaSource.info].
 */
public class AsyncMediaInfo internal constructor(
    public val streams: List<StreamInfo>,
    public val durationMicros: Long?,
    public val durationOrigin: DurationOrigin?,
    public val formatName: String,
    public val metadata: Map<String, String>,
    public val chapters: List<Chapter>,
    public val matroska: MatroskaSegment?,
    public val programs: List<Program>,
    public val unusedOpenOptions: List<String>,
    public val bitrateBps: Long?,
    public val startTimeMicros: Long,
    public val isSeekable: Boolean,
    public val primaryVideo: StreamInfo?,
    public val primaryAudio: StreamInfo?,
)

/**
 * Reads [source] into a snapshot, with the lane held. What cannot change after the open comes
 * from [previous], so a refresh does not copy the chapters or the Matroska data again.
 */
internal fun captureInfo(source: MediaSource, previous: AsyncMediaInfo?): AsyncMediaInfo {
    val streams = source.streams
    val programs = source.programs
    val sameTracks = previous != null && previous.streams === streams && previous.programs === programs
    return AsyncMediaInfo(
        streams = streams,
        durationMicros = source.durationMicros,
        durationOrigin = source.durationOrigin,
        formatName = previous?.formatName ?: source.formatName,
        metadata = source.metadata,
        chapters = previous?.chapters ?: source.chapters,
        matroska = if (previous != null) previous.matroska else source.matroska,
        programs = programs,
        unusedOpenOptions = previous?.unusedOpenOptions ?: source.unusedOpenOptions,
        bitrateBps = source.bitrateBps,
        startTimeMicros = source.startTimeMicros,
        isSeekable = source.isSeekable,
        primaryVideo = if (sameTracks) previous.primaryVideo else source.primaryVideo,
        primaryAudio = if (sameTracks) previous.primaryAudio else source.primaryAudio,
    )
}

/**
 * An open container of an [AsyncMediaRuntime]. It is the suspending form of [MediaSource], and
 * its members mean what the members of the same name mean there.
 *
 * A caller that is cancelled while a read, a seek, a pause or a resume is in FFmpeg stops the
 * source for good: the operation unwinds, and every later read fails with
 * [FFmpegError.Interrupted]. Close the source and open the media again. A caller cancelled while
 * it still waits for the lane changes nothing.
 */
public class AsyncMediaSource internal constructor(
    private val lane: AsyncLane,
    internal val input: AsyncInput,
    private val source: MediaSource,
) : AsyncCloseable {

    private val once = CloseOnce()

    // Read with the lane held, as the constructor runs there.
    @Volatile
    private var snapshot: AsyncMediaInfo = captureInfo(source, null)

    @Volatile
    private var policy: CorruptData = CorruptData.Skip

    @Volatile
    private var skipped: Long = 0L

    private val divergences = DivergenceRecorder()

    private fun requireOpen() {
        check(!input.closed) { "AsyncMediaSource is closed" }
    }

    /** The container as the last completed operation left it. */
    public val info: AsyncMediaInfo
        get() {
            requireOpen()
            return snapshot
        }

    /** What the decode flows do with damaged data. Change it with [setCorruptData]. */
    public val corruptData: CorruptData get() = policy

    /** How many damaged packets and frames the decode flows of this source skipped. */
    public val corruptDataSkipped: Long get() = skipped

    /** Where a stream's first decoded frame disagreed with what the container declared. */
    public val streamDivergences: List<StreamDivergence> get() = divergences.found

    /** Publishes a new snapshot. Runs with the lane held. */
    internal fun refresh() {
        snapshot = captureInfo(source, snapshot)
    }

    /** Wraps [packet], which a read just returned. Runs with the lane held. */
    internal fun capturePacket(packet: Packet): AsyncPacket {
        try {
            val captured = AsyncPacket(lane, packet)
            // The packet reports a change once, and the snapshot follows the same change.
            if (captured.changesContainer) refresh()
            return captured
        } catch (failure: Throwable) {
            packet.close()
            throw failure
        }
    }

    /** The frames of [stream], decoded in order. The collector owns each frame it is given. */
    public fun decodedFrames(stream: StreamInfo): Flow<AsyncFrame> = decodeStreams(listOf(stream))

    /**
     * The frames of [streams], decoded in container order. The collector owns each frame from the
     * moment its `emit` is called with it, and closes it. The flow holds the source's one read
     * position while it is collected, and gives the lane back before it hands over a frame.
     */
    public fun decodeStreams(streams: List<StreamInfo>): Flow<AsyncFrame> = AsyncDecodeFlow(this, streams)

    /** Collects the frames of [streams] into [collector]. */
    internal suspend fun decodeInto(streams: List<StreamInfo>, collector: FlowCollector<AsyncFrame>) {
        requireOpen()
        require(streams.isNotEmpty()) { "Need at least one stream to read" }
        // Refused before any decoder exists, so a second decoder never replaces the first.
        require(streams.distinctBy { it.index }.size == streams.size) { "Duplicate stream indices" }
        val decoders = LinkedHashMap<Int, AsyncStreamDecoder>()
        val declared = streams.associateBy { it.index }
        val skippedBefore = skipped
        var reader: AsyncPacketReader? = null
        suspend fun deliver(decoder: AsyncStreamDecoder) {
            while (true) {
                val frame = decoder.receive() ?: break
                declared[decoder.stream.index]?.let { stream -> divergences.observe(stream) { frame.capturedInfo } }
                // Called directly, with no cancellation check between: the frame is the
                // collector's from here, also when the collector then throws.
                collector.emit(frame)
            }
        }
        try {
            streams.forEach { decoders[it.index] = openDecoder(it, corruptData = policy) }
            val live = openPacketReader(streams).also { reader = it }
            while (true) {
                val packet = live.read()
                if (packet == null) {
                    for (decoder in decoders.values) {
                        // The drain signal can be refused like any packet.
                        while (!decoder.send(null)) deliver(decoder)
                        deliver(decoder)
                    }
                    return
                }
                packet.useAsync { held ->
                    val decoder = decoders[held.streamIndex]
                    if (decoder != null) {
                        // False means the decoder did not take it. Drain, then offer it again.
                        while (!decoder.send(held)) deliver(decoder)
                        deliver(decoder)
                    }
                }
                skipped = skippedBefore + decoders.values.sumOf { it.skippedNow }
            }
        } finally {
            skipped = skippedBefore + decoders.values.sumOf { it.skippedNow }
            withContext(NonCancellable) {
                try {
                    reader?.close()
                } catch (ignored: Throwable) {
                    // The decode's own outcome is the one the collector receives.
                }
                decoders.values.forEach { decoder ->
                    try {
                        decoder.close()
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
    }

    /** Moves the read position to the keyframe at or before [micros] on the content timeline. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun seekMicros(micros: Long) {
        val streams = info.streams
        if (streams.isEmpty()) throw FFmpegException(FFmpegError.InvalidArgument(0, "cannot seek media with no streams"))
        openPacketReader(streams).useAsync { it.seek(micros, SeekDirection.Backward, null) }
    }

    /**
     * The first frame of [stream], or of the primary video stream, whose time reaches [atMicros].
     * The caller owns the frame and closes it.
     */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun extractFrame(atMicros: Long, stream: StreamInfo? = null): AsyncFrame {
        val target = stream ?: info.primaryVideo
            ?: throw FFmpegException(FFmpegError.Internal("this media has no video stream to extract from"))
        val startTimeMicros = info.startTimeMicros
        // A seek lands on a keyframe at or before its target, so the walk starts well before it.
        val landing = (atMicros - EXTRACT_SEEK_BACKOFF_MICROS).coerceAtLeast(0L)
        openPacketReader(listOf(target)).useAsync { it.seek(landing, SeekDirection.Backward, null) }
        var reader: AsyncPacketReader? = null
        var decoder: AsyncStreamDecoder? = null
        try {
            val liveReader = openPacketReader(listOf(target)).also { reader = it }
            val liveDecoder = openDecoder(target).also { decoder = it }
            // The first frame whose own time reaches the target. A frame with no time is passed over.
            suspend fun next(): AsyncFrame? {
                while (true) {
                    val frame = liveDecoder.receive() ?: return null
                    val pts = frame.capturedPtsMicros
                    if (pts != null && pts - startTimeMicros >= atMicros) return frame
                    frame.close()
                }
            }
            while (true) {
                val packet = liveReader.read() ?: break
                val found = packet.useAsync { held ->
                    var hit: AsyncFrame? = null
                    while (hit == null && !liveDecoder.send(held)) hit = next()
                    hit ?: next()
                }
                if (found != null) return found
            }
            while (!liveDecoder.send(null)) next()?.let { return it }
            next()?.let { return it }
            throw FFmpegException(FFmpegError.Internal("no frame at ${atMicros}us (beyond the end of the stream?)"))
        } finally {
            withContext(NonCancellable) {
                try {
                    reader?.close()
                } finally {
                    decoder?.close()
                }
            }
        }
    }

    /** Opens the one packet reader this source can have at a time, for [streams]. */
    @KiteFFmpegLowLevelApi
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun openPacketReader(streams: List<StreamInfo>): AsyncPacketReader {
        requireOpen()
        return lane.immediate(dispose = { it.handle.discard() }) {
            val reader = source.openPacketReader(streams)
            try {
                AsyncPacketReader(lane, this, reader)
            } catch (failure: Throwable) {
                reader.close()
                throw failure
            }
        }
    }

    /** Opens a decoder for [stream]. The parameters mean what they mean in [MediaSource.openDecoder]. */
    @KiteFFmpegLowLevelApi
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun openDecoder(
        stream: StreamInfo,
        threadCount: Int = 0,
        lowDelay: Boolean = false,
        decoder: DecoderId? = null,
        options: DecoderOptions? = null,
        hardware: HardwareAccel? = null,
        corruptData: CorruptData = CorruptData.Skip,
    ): AsyncStreamDecoder {
        requireOpen()
        return lane.immediate(dispose = { it.handle.discard() }) {
            val opened = source.openDecoder(stream, threadCount, lowDelay, decoder, options, hardware, corruptData)
            try {
                AsyncStreamDecoder(lane, opened)
            } catch (failure: Throwable) {
                opened.close()
                throw failure
            }
        }
    }

    /** Opens a subtitle decoder for [stream]. See [MediaSource.openSubtitleDecoder]. */
    @KiteFFmpegLowLevelApi
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun openSubtitleDecoder(stream: StreamInfo, realTime: Boolean = false): AsyncSubtitleDecoder {
        requireOpen()
        return lane.immediate(dispose = { it.handle.discard() }) {
            val opened = source.openSubtitleDecoder(stream, realTime)
            try {
                AsyncSubtitleDecoder(lane, opened)
            } catch (failure: Throwable) {
                opened.close()
                throw failure
            }
        }
    }

    /** Asks a network source to pause. True when the source paused. See [MediaSource.pause]. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun pause(): Boolean {
        requireOpen()
        return lane.demux(input) { lane.engine.pause(source) }
    }

    /** Lifts a pause. True when one was in effect. See [MediaSource.resume]. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun resume(): Boolean {
        requireOpen()
        return lane.demux(input) { lane.engine.resume(source) }
    }

    /** Sets what the decode flows started after this call do with damaged data. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun setCorruptData(value: CorruptData) {
        requireOpen()
        policy = value
    }

    /** Sets [corruptDataSkipped] back to zero. */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun resetCorruptDataSkipped() {
        requireOpen()
        skipped = 0L
    }

    /**
     * Stops this source for good. An operation of it that waits for bytes fails with
     * [FFmpegError.Interrupted], and so does every later one. Safe from any thread, and it does
     * not wait.
     */
    public fun interrupt() {
        if (input.closed) return
        lane.stop(input)
    }

    /**
     * Closes the container and every byte source it owns. An operation of this source that is in
     * FFmpeg is stopped first, and the close waits until it has unwound. The open packet reader
     * of the source closes with it. Packets and frames stay valid until their own close.
     */
    @Throws(Exception::class)
    override suspend fun close() {
        lane.enter()
        once.close { lane.closeSource(input) }
    }

    private companion object {
        /** How far before the wanted time an extraction seek aims, as on [MediaSource]. */
        const val EXTRACT_SEEK_BACKOFF_MICROS = 5_000_000L
    }
}

/**
 * Implements [Flow] directly. The `flow` builder checks for cancellation before it passes a value
 * on, so a frame already decoded could be refused there and reach nobody.
 */
private class AsyncDecodeFlow(
    private val source: AsyncMediaSource,
    private val streams: List<StreamInfo>,
) : Flow<AsyncFrame> {
    override suspend fun collect(collector: FlowCollector<AsyncFrame>) {
        source.decodeInto(streams, collector)
    }
}
