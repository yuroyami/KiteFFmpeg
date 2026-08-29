package io.github.yuroyami.kiteffmpeg

/**
 * One container chapter (KPKMP 17.10, KD-5), bounds in microseconds on the same ABSOLUTE
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
