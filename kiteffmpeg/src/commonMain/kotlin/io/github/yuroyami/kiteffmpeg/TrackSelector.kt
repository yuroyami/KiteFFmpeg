package io.github.yuroyami.kiteffmpeg

/**
 * The policy that picks which video and audio stream to play when the caller did not name one.
 *
 * [MediaSource.primaryVideo] and [MediaSource.primaryAudio] apply [Default]. A player with its own
 * preferences builds a selector and calls [selectVideo] or [selectAudio] with
 * [MediaSource.streams].
 *
 * The rules, in order:
 *
 * - Video: the first stream that is not cover art ([Disposition.attachedPicture]). A file whose
 *   only video is its cover art gets that picture, because a picture beats nothing.
 * - Audio: a stream for a special audience never beats an ordinary sibling. Special means audio
 *   description ([Disposition.descriptions] or [Disposition.visualImpaired]), commentary
 *   ([Disposition.comment]) or a hearing-impaired mix ([Disposition.hearingImpaired]). Among the
 *   streams left, the best-ranked [preferredAudioLanguages] match wins, then the stream the
 *   container marks [Disposition.default], then container order.
 */
public data class TrackSelector(
    /**
     * Audio languages, best first, compared with each stream's `language` tag without regard to
     * case. A tag also matches on its primary subtag, so `en` matches `en-GB`. Two-letter and
     * three-letter codes are different strings: `en` does not match `eng`, so a caller that does
     * not know how its files are tagged lists both. Empty lets the other rules decide.
     */
    val preferredAudioLanguages: List<String> = emptyList(),
) {
    /** The video stream to play from [streams], or null when there is none. */
    public fun selectVideo(streams: List<StreamInfo>): StreamInfo? =
        streams.firstOrNull { it.type == MediaType.Video && !it.disposition.attachedPicture }
            ?: streams.firstOrNull { it.type == MediaType.Video }

    /** The audio stream to play from [streams], or null when there is none. */
    public fun selectAudio(streams: List<StreamInfo>): StreamInfo? =
        streams.firstOrNull { it.type == MediaType.Audio }

    public companion object {
        /** The selector with no language preference. */
        public val Default: TrackSelector = TrackSelector()
    }
}
