package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineDispatcher

/** Progress snapshot delivered during [Transcoder.transcode]. */
public data class TranscodeProgress(
    /** Video frames encoded so far (0 for audio-only transcodes). */
    val framesEncoded: Long,
    /** Where the output timeline currently ends, in microseconds. */
    val outputMicros: Long,
    /** 0.0–1.0 against the trim window / input duration, or null when the duration is unknown. */
    val percent: Double?,
)

/**
 * High-level pipeline: open input → decode → optionally filter → encode → mux. The equivalent of
 * `ffmpeg -ss 12 -to 45 -i in.mp4 -vf "scale=…" -c:v libx264 -c:a aac out.mp4` as one
 * Kotlin call with proper cancellation and typed errors.
 *
 * All selected streams are demuxed in a single pass. Their packets are interleaved into the
 * output as they are produced, so memory use stays constant regardless of input length.
 */
public expect object Transcoder {

    /**
     * Run a transcode end-to-end. Suspends until done.
     *
     * The work runs on [dispatcher], not on the caller's dispatcher: every step is a blocking
     * call into FFmpeg, and running them on a UI thread would freeze it until the whole file is
     * written. Cancelling the caller stops the work within one packet of the input, and this
     * function throws [kotlinx.coroutines.CancellationException] only after every decoder,
     * encoder, filter graph and file the work opened is closed. A read that blocks inside FFmpeg,
     * such as a stalled network input, is not interrupted; it ends when the read returns.
     *
     * @param input  input file path
     * @param output output file path
     * @param spec video encoder spec. Null (with [videoCopy] false) gives audio-only output
     *             and drops any input video. When set but the input has no video stream,
     *             this throws. The video is written at a constant [VideoEncoderSpec.frameRate]:
     *             frames are dropped or repeated against the input timeline the way FFmpeg's
     *             `fps` filter does, so a rate change keeps the duration and never the speed.
     *             The colour, pixel shape and HDR metadata the spec leaves null are copied from
     *             the first frame the encoder receives, after [videoFilter], so a filter that
     *             changes them decides what the output declares. Only what that frame declares
     *             is copied, never a guess. To read that frame the transcode opens [input] a
     *             second time and decodes until the frame comes out; a spec that sets all three
     *             skips that.
     * @param videoFilter filter graph description applied to the video stream. Null passes
     *                    decoded frames straight into the encoder. Requires [spec].
     * @param videoCopy stream-copy the video instead of re-encoding (`-c:v copy`): bit-exact
     *                  and near-free. Combine it with [audioSpec] to keep the video and
     *                  re-encode only the audio. Mutually exclusive with [spec]/[videoFilter].
     *                  Trimming a copied video stream is keyframe-snapped, not frame-exact.
     * @param audioSpec audio encoder spec. Null (with [audioCopy] false) drops audio. When set
     *                  but the input has no audio stream, the output is silently video-only. A
     *                  null [AudioEncoderSpec.channelLayoutMask] copies the input stream's layout
     *                  when it has [AudioEncoderSpec.channels] channels.
     * @param audioFilter filter chain for the audio stream (e.g. `volume=0.5,atempo=1.25`).
     *                    Null means plain resample and reformat to what the encoder needs.
     * @param audioCopy stream-copy the audio instead of re-encoding (`-c:a copy`): bit-exact,
     *                  near-free. Mutually exclusive with [audioSpec]/[audioFilter].
     * @param subtitleCopy stream-copy every subtitle stream into the output. Works for
     *                     subtitle codecs the output container accepts (mkv: almost all;
     *                     mp4: mov_text). Otherwise the muxer raises a typed error.
     * @param startMicros trim start, a position in the input relative to the start of the content
     *                    (see [MediaSource.startTimeMicros]). Re-encoded video keeps the frames
     *                    that start at or after it, and re-encoded audio keeps the samples from it
     *                    on. Copied streams keep whole packets: copied video from the keyframe at
     *                    or before it, copied audio and subtitles from the first packet at or
     *                    after it. Output timestamps are rebased to zero.
     * @param endMicros trim end, a position in the input on the same scale. Re-encoded streams
     *                  exclude it: video keeps the frames that start before it, and audio is cut
     *                  to the sample, the way FFmpeg's `atrim` filter cuts it, so a window from
     *                  1 s to 2 s holds exactly one second. Copied streams drop the packets whose
     *                  decode time is after it. Demuxing stops once the lead stream reaches it.
     *                  The trim selects decoded input before any filter runs, so a filter that
     *                  moves time, such as `setpts=2*PTS` or `atempo=0.5`, makes the output longer
     *                  or shorter than the selection, and every frame the filter makes from the
     *                  selection is encoded.
     * @param metadata container tags written into the output header (`title`, `artist`, …)
     * @param dispatcher where the blocking work runs. Null runs it on `Dispatchers.IO`, the pool
     *                   for blocking calls. Pass your own to choose the threads, for example
     *                   `Dispatchers.IO.limitedParallelism(2)` to cap how many transcodes run
     *                   at once.
     * @param onProgress invoked about every 30 encoded video frames (or 100 audio frames when
     *                   audio-only) with a [TranscodeProgress], in the caller's own coroutine
     *                   context and never on [dispatcher], so a UI caller may update its views
     *                   from it. A caller that is busy when a report arrives gets only the newest
     *                   one, and the last report arrives before this function returns.
     */
    public suspend fun transcode(
        input: String,
        output: String,
        spec: VideoEncoderSpec? = null,
        videoFilter: String? = null,
        videoCopy: Boolean = false,
        audioSpec: AudioEncoderSpec? = null,
        audioFilter: String? = null,
        audioCopy: Boolean = false,
        subtitleCopy: Boolean = false,
        startMicros: Long = 0L,
        endMicros: Long = Long.MAX_VALUE,
        metadata: Map<String, String> = emptyMap(),
        dispatcher: CoroutineDispatcher? = null,
        onProgress: ((TranscodeProgress) -> Unit)? = null,
    )
}
