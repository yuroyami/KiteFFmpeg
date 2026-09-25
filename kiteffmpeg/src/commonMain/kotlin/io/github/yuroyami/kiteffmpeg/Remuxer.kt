package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Lossless container rewrite: `ffmpeg -c copy`. Packets move from input to output without
 * touching a decoder or encoder, so a full-length movie remuxes in seconds and bit-exact
 * quality is preserved. Use it to change containers (mp4 → mkv), strip streams, cut on
 * keyframes, or re-wrap after a download.
 */
public expect object Remuxer {

    /**
     * Copy [streamIndices] (default: every stream the demuxer understands) from [input] into a
     * fresh container at [output]. Format inferred from the output extension.
     *
     * The cut is keyframe-snapped, which is the price of not re-encoding: it starts at the last
     * keyframe at or before [startMicros] and stops once the first selected stream passes
     * [endMicros]. Output timestamps are rebased to start at zero.
     *
     * Each copied stream keeps its tags (language, title and the rest), its disposition flags and
     * its display matrix, and the output gets the input's chapters that overlap the trim window,
     * moved onto the output's timeline. What the target container cannot store is dropped by its
     * muxer: MP4 has no stream titles, and Matroska has no display matrix.
     *
     * Bitstream filters are not applied yet, so container pairs that need one (h264-in-mp4 to
     * MPEG-TS Annex B) fail with a muxer error rather than producing a broken file.
     *
     * @param startMicros trim start, relative to the start of the content (see
     *                    [MediaSource.startTimeMicros])
     * @param endMicros trim end, on the same content-relative scale
     * The copy loop runs on [dispatcher], so a call from an app's main thread does not block it.
     * Cancelling the caller stops the loop before its next packet. Every native object is closed
     * before this function returns or throws, and the output is finished with whatever was
     * written up to that point.
     *
     * @param metadata container tags written into the output header (`title`, `artist`, …)
     * @param dispatcher where the blocking work runs. Null runs it on `Dispatchers.IO`, the pool
     *                   for blocking calls.
     * @param onProgress invoked every ~100 packets with the running packet count, in the caller's
     *                   own coroutine context and never on [dispatcher]. A caller that is busy when
     *                   a report arrives gets only the newest one, and the last report arrives
     *                   before this function returns.
     */
    public suspend fun remux(
        input: String,
        output: String,
        streamIndices: List<Int>? = null,
        startMicros: Long = 0L,
        endMicros: Long = Long.MAX_VALUE,
        metadata: Map<String, String> = emptyMap(),
        dispatcher: CoroutineDispatcher? = null,
        onProgress: ((packetsWritten: Long) -> Unit)? = null,
    )
}
