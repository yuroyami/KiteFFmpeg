package io.github.yuroyami.kiteffmpeg

/**
 * One container chapter, bounds in microseconds on the same ABSOLUTE
 * timeline every other timestamp KiteFFmpeg reports uses (subtract
 * [MediaSource.startTimeMicros] to move onto the relative timeline seeks accept).
 */
public data class Chapter(
    val id: Long,
    val startMicros: Long,
    val endMicros: Long,
    /** The chapter's own metadata dictionary. The title, when the container wrote one, is here. */
    val metadata: Map<String, String> = emptyMap(),
) {
    public val title: String? get() = metadata["title"]
}

/**
 * The container-level facts in one value: what a player's media screen shows before any
 * stream is selected. Assembled from the source's own members, so it can never disagree with
 * them.
 */
public data class MediaInfo(
    val durationMicros: Long?,
    val formatName: String,
    val metadata: Map<String, String>,
    val chapters: List<Chapter>,
)

/** The assembled container-level view. Reads only members the source already exposes. */
public val MediaSource.mediaInfo: MediaInfo
    get() = MediaInfo(
        durationMicros = durationMicros,
        formatName = formatName,
        metadata = metadata,
        chapters = chapters,
    )

/**
 * [chapters] of a source moved onto the timeline of an output cut from it: [originMicros] is the
 * absolute time that becomes zero and [lengthMicros] how long the output runs, or
 * [Long.MAX_VALUE] when it runs to the end. A chapter outside the window is dropped and one
 * crossing its edge is clipped to it.
 */
internal fun chaptersForOutput(chapters: List<Chapter>, originMicros: Long, lengthMicros: Long): List<Chapter> =
    chapters.mapNotNull { chapter ->
        val start = (chapter.startMicros - originMicros).coerceAtLeast(0L)
        val end = (chapter.endMicros - originMicros).let { if (lengthMicros == Long.MAX_VALUE) it else minOf(it, lengthMicros) }
        if (end <= start) null else chapter.copy(startMicros = start, endMicros = end)
    }

/**
 * A source's chapters for an output cut from it, waiting for the origin the output's media takes.
 *
 * Copied media is rebased on the earliest time the output shows (#153), and a copy cut between
 * keyframes writes from the keyframe before the cut, so that origin is known only once the first
 * packets or frame are seen. The sink places these chapters against it when it writes its header
 * (#144). They used to be placed against the requested start, and showed early by the distance
 * from that keyframe to the cut.
 */
internal class SourceChapters(
    val chapters: List<Chapter>,
    /** The absolute time of the requested start, the origin when the output writes no timestamp. */
    val requestedOriginMicros: Long,
    /** The absolute time the output ends at, or [Long.MAX_VALUE] when it runs to the end. */
    val endMicros: Long,
) {
    /** The chapters on the timeline of an output whose zero is the absolute time [originMicros]. */
    fun placedAt(originMicros: Long): List<Chapter> = chaptersForOutput(
        chapters,
        originMicros = originMicros,
        lengthMicros = if (endMicros == Long.MAX_VALUE) Long.MAX_VALUE else endMicros - originMicros,
    )

    companion object {
        /** [source]'s chapters for an output of the window from [startMicros] to [endMicros], both relative. */
        fun of(source: MediaSource, startMicros: Long, endMicros: Long): SourceChapters = SourceChapters(
            chapters = source.chapters,
            requestedOriginMicros = source.startTimeMicros + startMicros,
            endMicros = if (endMicros == Long.MAX_VALUE) Long.MAX_VALUE else source.startTimeMicros + endMicros,
        )
    }
}
