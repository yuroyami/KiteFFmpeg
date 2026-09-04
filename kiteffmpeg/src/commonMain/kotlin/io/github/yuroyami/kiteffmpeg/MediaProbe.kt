package io.github.yuroyami.kiteffmpeg

/**
 * Everything a container says about itself, read in one call and with nothing left open.
 *
 * What it is for: deciding. A library screen wants a duration and a language list, a picker wants
 * to know whether a file is playable at all, a resume dialogue wants chapters. All of that is
 * available from an open [MediaSource] already, and every one of those callers had to remember to
 * close it. A probe that hands back a value instead of a handle cannot be leaked.
 *
 * A snapshot, not a view: nothing here changes once it is returned, and the source it was read
 * from is closed by the time you hold it.
 */
public data class MediaProbe(
    /** The demuxer FFmpeg chose, for example `mov,mp4,m4a,3gp,3g2,mj2`. */
    val formatName: String,
    /** How long the container says it is, or null when it does not say, which live sources do not. */
    val durationMicros: Long?,
    /** Where its timeline starts. Rarely zero for a transport stream. */
    val startTimeMicros: Long,
    /** Whether a seek can be honoured at all. */
    val isSeekable: Boolean,
    /** Container-level tags, as the demuxer read them. */
    val metadata: Map<String, String>,
    val chapters: List<Chapter>,
    val streams: List<StreamInfo>,
) {
    /** The first video stream that is not cover art, which is what a thumbnail or a size wants. */
    public val primaryVideo: StreamInfo?
        get() = streams.firstOrNull { it.type == MediaType.Video }

    /** The first audio stream, which is what a duration or a language usually means. */
    public val primaryAudio: StreamInfo?
        get() = streams.firstOrNull { it.type == MediaType.Audio }
}

/**
 * Opens [path], reads what it says about itself, and closes it again.
 *
 * Blocking, exactly like [MediaSource.Companion.open], which it is: a probe is an open, a read of
 * already-parsed fields, and a close. Call it off the UI thread, and expect a network URL to cost
 * what opening one costs.
 *
 * An extension on the companion rather than a member of it: [MediaSource] is an expect class, so a
 * member would owe an actual in four platform source sets for a body that is the same everywhere
 * and calls only what each of them already has.
 *
 * @throws FFmpegException when the source cannot be opened, the same way [MediaSource.Companion.open] does.
 */
@Throws(FFmpegException::class)
public fun MediaSource.Companion.probe(
    path: String,
    options: Map<String, String> = emptyMap(),
): MediaProbe = open(path, options).use { it.toProbe() }

/**
 * The same over caller-supplied bytes. The source owns [io] and closes it, so [io] is spent when
 * this returns, exactly as it would be after an [MediaSource.Companion.open] and a close.
 */
@Throws(FFmpegException::class)
public fun MediaSource.Companion.probe(
    io: MediaByteSource,
    options: Map<String, String> = emptyMap(),
): MediaProbe = open(io, options).use { it.toProbe() }

private fun MediaSource.toProbe(): MediaProbe = MediaProbe(
    formatName = formatName,
    durationMicros = durationMicros,
    startTimeMicros = startTimeMicros,
    isSeekable = isSeekable,
    metadata = metadata,
    chapters = chapters,
    streams = streams,
)
