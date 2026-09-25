package io.github.yuroyami.kiteffmpeg

/**
 * One container chapter (KD-5), bounds in microseconds on the same ABSOLUTE
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
 * The container-level facts in one value (KD-5): what a player's media screen shows before any
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
 * absolute time that becomes zero (the source's start time plus the start of any trim) and
 * [lengthMicros] how long the output runs, or [Long.MAX_VALUE] when it runs to the end. A chapter
 * outside the window is dropped and one crossing its edge is clipped to it.
 */
internal fun chaptersForOutput(chapters: List<Chapter>, originMicros: Long, lengthMicros: Long): List<Chapter> =
    chapters.mapNotNull { chapter ->
        val start = (chapter.startMicros - originMicros).coerceAtLeast(0L)
        val end = (chapter.endMicros - originMicros).let { if (lengthMicros == Long.MAX_VALUE) it else minOf(it, lengthMicros) }
        if (end <= start) null else chapter.copy(startMicros = start, endMicros = end)
    }
