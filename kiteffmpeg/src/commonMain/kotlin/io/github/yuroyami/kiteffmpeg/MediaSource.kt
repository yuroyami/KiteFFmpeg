package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * An opened input: a local file or a URL. It owns the container cursor and per-stream resources,
 * and closing it releases them all. Confine it to one coroutine context, because the underlying
 * media objects are not safe to call concurrently.
 */
public expect class MediaSource : AutoCloseable {

    /** Every stream the container declares, including streams this build cannot decode. */
    public val streams: List<StreamInfo>
    /** The container's duration in microseconds, or null when it declares none, as for a live stream. */
    public val durationMicros: Long?

    /**
     * Where [durationMicros] came from, or null when there is no duration (#134).
     *
     * [DurationOrigin.Bitrate] marks a length FFmpeg guessed from the bit rate of the first frames
     * because the input states none. For variable bit rate audio that guess can be minutes out in
     * either direction, so a caller should treat it as a hint: not cut a seek at it, and not report
     * it as the end of the media.
     */
    public val durationOrigin: DurationOrigin?
    /** FFmpeg's name for the container format, such as `mov,mp4,m4a,3gp,3g2,mj2` or `matroska,webm`. */
    public val formatName: String
    /** The container-level tags, such as `title` and `artist`, as the file wrote them. */
    public val metadata: Map<String, String>

    /** The container's chapter table. Empty when the container declares none. */
    public val chapters: List<Chapter>

    /**
     * The container's programmes, each a set of [streams] that play together, such as the channels
     * of a transport stream multiplex. Empty when the container declares none, as MP4 and Matroska
     * do not. Read once, like [streams], and every stream index in it names one of [streams]. See
     * [Program].
     */
    public val programs: List<Program>

    /**
     * The pre-open option keys FFmpeg did NOT consume on this open. Always empty for the plain
     * [open]; a non-empty list after an options open is a caller mistake worth reading back and
     * logging.
     */
    public val unusedOpenOptions: List<String>

    /**
     * The CONTAINER's own bit rate estimate in bits per second, or null when it has none.
     *
     * Not the sum of the streams' rates and not a measurement: it is what the demuxer wrote down,
     * which for a variable-rate file is an estimate and for a live source is usually nothing. Read
     * it as a hint for a progress bar or a quality label, never as an exact figure.
     */
    public val bitrateBps: Long?

    /**
     * Where this container's timeline begins, in microseconds. It is 0 for most mp4 files, and
     * commonly around 1.4s for MPEG-TS.
     *
     * This is the offset between the two timelines KiteFFmpeg deals in. Timestamps it reports
     * ([StreamInfo], [FrameInfo.pts]) are absolute and include this value. Timestamps it accepts
     * ([seekMicros], [extractFrame]'s `atMicros`, [Transcoder.transcode] and [Remuxer.remux] trim
     * bounds) are relative to the start of the content, so `10_000_000` always means ten seconds
     * in. Subtract this from a frame's own pts to move it onto the timeline those parameters use.
     */
    public val startTimeMicros: Long

    /**
     * Whether this input can seek at all, read from the input rather than assumed.
     *
     * False for a pipe, a capture device and anything else whose bytes only move forward. A player
     * must ask before it offers a seek bar, because on such an input every [seekMicros] and every
     * `PacketReader.seek` fails.
     *
     * It is conservative in one direction: a demuxer that implements its own seek without a
     * seekable byte stream reports false here, because since FFmpeg 7 nothing in the public
     * headers proves otherwise. It never reports true for an input that cannot seek.
     */
    public val isSeekable: Boolean

    /**
     * The first video stream that is not cover art ([Disposition.attachedPicture]). A file whose
     * only video is its cover art returns that picture rather than null. Picked by
     * [TrackSelector.Default].
     */
    public val primaryVideo: StreamInfo?

    /**
     * The audio stream [TrackSelector.Default] picks beside [primaryVideo], from that picture's own
     * programme when the source has [programs], or null when there is no audio stream to pick.
     */
    public val primaryAudio: StreamInfo?

    /**
     * What the batch decode flows do when FFmpeg reports damaged data. [CorruptData.Skip] by
     * default, which is what every backend always did, silently.
     *
     * Set it before collecting. Changing it mid-flow applies from the next packet, which is well
     * defined but rarely what anyone means.
     */
    public var corruptData: CorruptData

    /**
     * Packets and frames this source skipped as damaged since it was opened.
     *
     * Zero for a healthy file. Non-zero means the decoded result is INCOMPLETE, which is the fact
     * that used to be unobservable: a caller could not tell a clean decode from a damaged one.
     * Meaningless under [CorruptData.Fail], which throws instead of skipping.
     */
    public var corruptDataSkipped: Long
        private set

    /**
     * Where the container's declaration and the decoder's output disagree, one entry per field.
     *
     * Empty for an honest file, which is nearly all of them. A non-empty list means the numbers a
     * caller allocated against before decoding started were wrong, and the decoded ones are the
     * ones to trust. See [StreamDivergence].
     *
     * Filled from the FIRST decoded frame of each stream, so it is empty until decoding has
     * started and it answers "did the container lie", not "did the decoder change its mind part
     * way through". Never reset while the source is open, like [corruptDataSkipped].
     */
    public val streamDivergences: List<StreamDivergence>

    /**
     * Decode this stream and emit each decoded frame, owned by the collector.
     *
     * Only one decode flow may collect at a time, because the demuxer is a single cursor.
     * Starting a second concurrent collection throws [IllegalStateException]; use
     * [decodeStreams] to read several streams together. The decoder is freed when collection
     * completes or is cancelled.
     *
     * @see Frame for the ownership rule every collected frame is subject to
     */
    public fun decodedFrames(stream: StreamInfo): Flow<Frame>

    /**
     * Decode several streams in one demuxer pass, emitting frames interleaved in container order
     * and tagged by [FrameInfo.streamIndex]. This is the only correct way to transcode video and
     * audio together: two concurrent [decodedFrames] flows would race the underlying demuxer, so
     * that is rejected with [IllegalStateException].
     *
     * @see Frame for the ownership rule every collected frame is subject to
     */
    public fun decodeStreams(streams: List<StreamInfo>): Flow<Frame>

    /**
     * Seek the demuxer to [micros], measured from the start of the content. Lands on the keyframe
     * at or before that point, so the next decode flow resumes from there. Not allowed while a
     * decode flow is collecting, since the demuxer cursor is shared.
     *
     * The keyframe is the last one that shows at or before [micros] in the first video stream that
     * is not a cover picture, or the first keyframe when [micros] comes before it. FFmpeg finds a
     * keyframe by when it decodes, which with B-frames is earlier than when it shows, and MPEG-TS
     * finds one by searching byte positions, so the seek reads on to the keyframe it landed on and
     * aims earlier when that one shows too late. What it read is handed to the reads after it, so a
     * seek FFmpeg landed right costs no more than before. Keyframes more than 32 MB of input apart
     * are not checked, and the seek then stays where FFmpeg put it. The other streams start where
     * the container puts them beside that keyframe, which can be a little before it.
     *
     * A keyframe is as close as a seek gets. Decode forward and check [FrameInfo.pts] to reach an
     * exact point; [extractFrame] and [Transcoder.transcode]'s trim already do this for you.
     *
     * @param micros where to seek to, relative to the start of the content (see [startTimeMicros])
     * @throws FFmpegException when the seek fails
     */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun seekMicros(micros: Long)

    /**
     * Decode and return the frame at (or first after) [atMicros]. Use this to extract a
     * thumbnail. It seeks to the preceding keyframe and decodes forward to the exact target.
     * Pair with [Frame.encodeImage] for jpg/png bytes.
     *
     * @param atMicros where to read, relative to the start of the content (see [startTimeMicros])
     * @param stream which stream to read; default = primary video
     * @return an owned frame: hold it as long as you like, then close it
     * @throws FFmpegException when the seek or decode fails
     */
    @Throws(FFmpegException::class, CancellationException::class)
    public suspend fun extractFrame(atMicros: Long, stream: StreamInfo? = null): Frame

    /**
     * Opens the low-level demux cursor for exactly [streams]. The returned reader owns the source's
     * cursor until it is closed; no batch decode or source-level seek may run concurrently.
     */
    @KiteFFmpegLowLevelApi
    public fun openPacketReader(streams: List<StreamInfo>): PacketReader

    /**
     * Opens one independently driven decoder for [stream].
     *
     * A null [decoder] lets FFmpeg choose its default implementation for the stream's codec. A
     * non-null value selects that exact decoder and refuses it unless it can decode this stream's
     * codec. This is the selection seam used for FFmpeg-owned hardware decoders such as
     * [DecoderId.H264MediaCodec]; it does not call a platform decoder API directly.
     *
     * [hardware] requests an HWACCEL behind the chosen decoder ([HardwareAccel]); it is attached
     * between context creation and open and fails typed when the running FFmpeg cannot honour it.
     */
    @KiteFFmpegLowLevelApi
    @Throws(FFmpegException::class)
    public fun openDecoder(
        stream: StreamInfo,
        threadCount: Int = 0,
        lowDelay: Boolean = false,
        decoder: DecoderId? = null,
        options: io.github.yuroyami.kiteffmpeg.dsl.DecoderOptions? = null,
        hardware: HardwareAccel? = null,
        corruptData: CorruptData = CorruptData.Skip,
    ): StreamDecoder

    /**
     * Opens a decoder for the subtitle [stream]: Blu-ray (PGS), DVB and DVD images, and the text
     * formats this build of FFmpeg decodes.
     *
     * @throws IllegalArgumentException when [stream] is not a subtitle stream of this source
     * @throws FFmpegException with [FFmpegError.DecoderNotFound] when this build has no decoder for it
     */
    @KiteFFmpegLowLevelApi
    @Throws(FFmpegException::class)
    public fun openSubtitleDecoder(stream: StreamInfo): SubtitleDecoder

    /**
     * Requests that every current and future blocking call on this source return with a typed
     * [FFmpegError.Interrupted] failure.
     *
     * FFmpeg polls an interrupt seam at the top of its blocking loops, so a read or seek already
     * in flight returns promptly and later calls fail fast. One-way by design: an interrupted
     * source is being abandoned, and there is no way to clear the flag, because a cancelled read
     * resuming into freed state is the bug this exists to prevent. [close] stays both legal and
     * required.
     *
     * This is the ONE member callable from another thread while a read, seek or decode is blocked
     * on this source. It must still never run concurrently with, or after, [close].
     *
     * On wasmJs the runtime is single threaded, so nothing can be blocked while this runs; the
     * flag is still set and later calls still fail fast.
     */
    public fun interrupt()

    /** Runs [release] once, after [close] has freed the container context. */
    internal fun releaseAtClose(release: () -> Unit)

    override fun close()

    /** Opens sources. */
    public companion object {
        /**
         * Open a local file or URL.
         *
         * This is a blocking call. Network URLs perform I/O inside the native open, so call it
         * from a background dispatcher (`Dispatchers.IO` or your media dispatcher). Never call it
         * on the UI thread.
         */
        @Throws(FFmpegException::class)
        public fun open(path: String): MediaSource

        /**
         * Open with pre-open options: pairs applied between allocation and open, the only moment
         * probesize, fflags and format forcing can act. Keys FFmpeg does not consume are reported
         * through [unusedOpenOptions], never silently dropped.
         *
         * [interrupt] lets another thread stop this open while it runs, and then stays with the
         * returned source; see [OpenInterrupt]. The open fails with [FFmpegError.Interrupted].
         */
        @Throws(FFmpegException::class)
        public fun open(
            path: String,
            options: Map<String, String>,
            interrupt: OpenInterrupt? = null,
        ): MediaSource

        /**
         * Open over caller-supplied bytes: FFmpeg demuxes whatever [io] reads, with no path
         * involved. The returned source OWNS [io] and closes it when it closes. Pre-open
         * [options] and [interrupt] behave exactly like the path overload's. Blocking, like every
         * open here: call it off the UI thread, and expect [io]'s own read latency to shape the
         * open time.
         *
         * Three optional parameters say where the bytes come from:
         *
         * - [url] is the address that [io] reads. The bytes still come from [io]. FFmpeg uses the
         *   address to recognise the format, as it uses a file name, and to resolve the relative
         *   addresses inside the media.
         * - [mimeType] is the type the bytes arrived with, such as a server's `Content-Type`. The
         *   probe uses it. An HLS playlist whose [url] does not end in `.m3u8` or `.m3u` opens only
         *   with an HLS type, such as `application/vnd.apple.mpegurl`.
         * - [nestedOpener] opens the other addresses that the media names, such as the segments and
         *   keys of an HLS playlist. Without it, FFmpeg opens those addresses with its own
         *   protocols, which have no https.
         *
         * An HLS stream over https needs [url] and [nestedOpener], and [mimeType] too when [url]
         * does not end in `.m3u8`:
         *
         * ```kotlin
         * val source = MediaSource.open(
         *     io = playlistBytes,
         *     url = "https://cdn.example/live/index",
         *     mimeType = "application/vnd.apple.mpegurl",
         *     nestedOpener = { address -> if (address.startsWith("https://cdn.example/")) fetch(address) else null },
         * )
         * ```
         *
         * On the web, [io] is read as FFmpeg asks for its bytes wherever a read may block, which is
         * a Worker, so it can answer with a synchronous range request, and it is closed when the
         * returned source closes. On a page's main thread, where nothing may block, it is read whole
         * into the codec module's memory during the open, up to 512 MB, and closed then. Every
         * source that [nestedOpener] returns is read whole as soon as the opener returns it, and
         * closed. The opener has to answer at once as well, which on a page means bytes it already
         * holds, and in a Worker can mean a synchronous request.
         */
        @Throws(FFmpegException::class)
        public fun open(
            io: MediaByteSource,
            options: Map<String, String> = emptyMap(),
            interrupt: OpenInterrupt? = null,
            url: String? = null,
            mimeType: String? = null,
            nestedOpener: MediaByteOpener? = null,
        ): MediaSource

        /**
         * The byte-source open before `url`, `mimeType` and `nestedOpener`, kept so that compiled
         * callers still link. The defaults stay too, because a call that used them links to them.
         */
        @Deprecated("Use the overload with url, mimeType and nestedOpener.", level = DeprecationLevel.HIDDEN)
        @Throws(FFmpegException::class)
        public fun open(
            io: MediaByteSource,
            options: Map<String, String> = emptyMap(),
            interrupt: OpenInterrupt? = null,
        ): MediaSource
    }
}
