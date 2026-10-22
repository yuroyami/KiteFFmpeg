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
 *   streams left, the best-ranked [preferredAudioLanguages] match wins, then among the streams of
 *   that language the closest one, then the stream the container marks [Disposition.default],
 *   then container order.
 * - Audio beside a picture: in a source with programmes, such as a transport stream carrying
 *   several channels, the audio rules choose only among the sound of the picture's own programme,
 *   so the picture fixes the channel and a language preference never moves the sound to another
 *   one. Pass the picture and [MediaSource.programs] to the [selectAudio] that takes them.
 */
public data class TrackSelector(
    /**
     * Audio languages, best first, as BCP 47 tags or ISO 639 codes. A preference matches a stream
     * whose `language` names the same language, however either spells it: `en`, `eng` and `en-GB`
     * are all English, and `de`, `deu` and `ger` all German. Among the streams that match one
     * preference, one whose script and then region agree with it comes first, then one that names
     * none, then one that names another: `pt-BR` takes a `pt-BR` stream over a plain `por` one and
     * that over `pt-PT`, which it still takes over nothing. A region implies the script of Chinese,
     * so `zh-TW` is `zh-Hant`. A related language is not the language (`enm`, Middle English, is
     * not `en`), and a code that names none (`und`, `mul`, `mis`, `zxx`, `qaa` to `qtz`) matches
     * nothing. A tag not led by a language code, such as `x-klingon`, matches its own spelling
     * without regard to case. Empty lets the other rules decide.
     */
    val preferredAudioLanguages: List<String> = emptyList(),
) {
    /** The video stream to play from [streams], or null when there is none. */
    public fun selectVideo(streams: List<StreamInfo>): StreamInfo? =
        streams.firstOrNull { it.type == MediaType.Video && !it.disposition.attachedPicture }
            ?: streams.firstOrNull { it.type == MediaType.Video }

    /**
     * The audio stream to play beside [video], or null when there is none (#165).
     *
     * [programs] are the source's [MediaSource.programs]. When [video] sits in a programme, the
     * candidates are the audio streams of [streams] that share one of its programmes or, when
     * those programmes hold no audio, the ones that sit in no programme. Audio that only other
     * programmes hold is never a candidate, so a multiplex never plays one channel's picture with
     * another channel's sound. With no programmes, a null [video] or a video in no programme,
     * every audio stream is a candidate, as in the [selectAudio] that takes the streams alone. The
     * audio rules of [TrackSelector] then pick among the candidates.
     */
    public fun selectAudio(streams: List<StreamInfo>, programs: List<Program>, video: StreamInfo?): StreamInfo? {
        val audio = streams.filter { it.type == MediaType.Audio }
        val homes = if (video == null) emptyList() else programs.filter { video.index in it.streamIndexes }
        if (homes.isEmpty()) return pickAudio(audio)
        val beside = homes.flatMapTo(HashSet()) { it.streamIndexes }
        val placed = programs.flatMapTo(HashSet()) { it.streamIndexes }
        return pickAudio(audio.filter { it.index in beside }.ifEmpty { audio.filter { it.index !in placed } })
    }

    /**
     * The audio stream to play from [streams], or null when there is none. It knows nothing of
     * programmes, so for a source that has them use the [selectAudio] that takes the picture and
     * the programmes.
     */
    public fun selectAudio(streams: List<StreamInfo>): StreamInfo? =
        pickAudio(streams.filter { it.type == MediaType.Audio })

    /** The audio rules applied to [audio], which holds only audio streams, in container order. */
    private fun pickAudio(audio: List<StreamInfo>): StreamInfo? {
        val candidates = audio.filter { !it.disposition.isForSpecialAudience }.ifEmpty { audio }
        val wanted = preferredAudioLanguages.map { it to LanguageTag.parse(it) }
        val matches = candidates.associateWith { match(wanted, it.language) }
        // minWithOrNull keeps the first of equal streams, so container order breaks every tie.
        return candidates.minWithOrNull(
            compareBy<StreamInfo>(
                { matches.getValue(it)?.preference ?: wanted.size },
                { -(matches.getValue(it)?.closeness ?: 0) },
                { if (it.disposition.default) 0 else 1 },
            ),
        )
    }

    /** The first of [wanted] that a stream tagged [language] matches, and how closely, or null. */
    private fun match(wanted: List<Pair<String, LanguageTag?>>, language: String?): Match? {
        val tag = LanguageTag.parse(language)
        wanted.forEachIndexed { index, (raw, want) ->
            val closeness = when {
                want != null && tag != null -> if (want.language == tag.language) closeness(want, tag) else null
                // Neither names a language. Two tags that are no language codes at all can still be
                // spelled alike, but `und` never matches `und`.
                want == null && tag == null && !LanguageTag.isLanguageCode(raw) &&
                    !LanguageTag.isLanguageCode(language) && raw.trim().equals(language?.trim(), ignoreCase = true) -> 0
                else -> null
            }
            if (closeness != null) return Match(index, closeness)
        }
        return null
    }

    /**
     * How well [tag] agrees with [want], a tag of the same language: a script outweighs a region,
     * and a subtag that disagrees counts against a stream where one it lacks does not.
     */
    private fun closeness(want: LanguageTag, tag: LanguageTag): Int {
        fun agreement(wanted: String?, got: String?): Int = when {
            wanted == null || got == null -> 0
            wanted == got -> 1
            else -> -1
        }
        return 4 * agreement(want.impliedScript, tag.impliedScript) + agreement(want.region, tag.region)
    }

    private class Match(val preference: Int, val closeness: Int)

    public companion object {
        /** The selector with no language preference. */
        public val Default: TrackSelector = TrackSelector()
    }
}

/** Audio description, commentary or a hearing-impaired mix: never picked over ordinary audio. */
private val Disposition.isForSpecialAudience: Boolean
    get() = descriptions || visualImpaired || comment || hearingImpaired
