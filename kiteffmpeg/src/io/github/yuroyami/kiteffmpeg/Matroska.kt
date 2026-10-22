package io.github.yuroyami.kiteffmpeg

/**
 * What a Matroska file says about its own segment, its editions and their chapters, read as
 * RFC 9559 defines them (#173).
 *
 * A Matroska file can describe a timeline rather than a plain run of media. An ordered edition
 * plays a list of chapters in its own order, a chapter can play a range or a whole edition of
 * another file named by that file's [uid], and segments can be chained through [previousUid] and
 * [nextUid]. Anime releases share one opening and one ending file across a season this way, and a
 * film can carry a theatrical and an extended cut as two editions of one file.
 *
 * FFmpeg plays every such file as a plain file, and [MediaSource.chapters] is the plain reading of
 * [defaultEdition]. This is what the file says, so that a player can build the timeline itself, as
 * mpv and VLC do. Finding the files a segment links to, and playing them, is the player's work.
 *
 * Segment UIDs are 32 lowercase hexadecimal digits, the 16 bytes of the UID in the order the file
 * stores them, so two of them compare with `==`. Edition and chapter UIDs are the file's unsigned
 * 64-bit values carried in a [Long], as [Chapter.id] carries them.
 */
public data class MatroskaSegment(
    /** This segment's `SegmentUUID`, or null when the file states none. */
    val uid: String?,
    /** The `SegmentFilename` the file states for itself, or null. */
    val filename: String?,
    /** The `PrevUUID` of the segment this one follows when segments are chained, or null. */
    val previousUid: String?,
    /** The `PrevFilename` of that segment, or null. */
    val previousFilename: String?,
    /** The `NextUUID` of the segment that follows this one when segments are chained, or null. */
    val nextUid: String?,
    /** The `NextFilename` of that segment, or null. */
    val nextFilename: String?,
    /** Each `SegmentFamily` this segment belongs to, in the order the file lists them. */
    val families: List<String>,
    /** The editions of the file's `Chapters` element, in stored order. Empty when it has none. */
    val editions: List<MatroskaEdition>,
) {
    /**
     * The edition a player uses unless told otherwise: the first one flagged default, or the first
     * one when none is (RFC 9559, section 20.1.2). Null when the file has no editions.
     */
    public val defaultEdition: MatroskaEdition?
        get() = editions.firstOrNull { it.isDefault } ?: editions.firstOrNull()
}

/** One `EditionEntry`: a set of chapters, such as one cut of a film. */
public data class MatroskaEdition(
    /** The `EditionUID`, or null when the file states none. */
    val uid: Long?,
    /** `EditionFlagDefault`. */
    val isDefault: Boolean,
    /** `EditionFlagHidden`: a player does not offer this edition to choose. */
    val isHidden: Boolean,
    /**
     * `EditionFlagOrdered`: the chapters are a timeline to play in their stored order rather than
     * points to jump to, and a chapter's link to another segment is played. In an edition that is
     * not ordered such a link is only information.
     */
    val isOrdered: Boolean,
    /** The edition's names, one for each `EditionDisplay`. */
    val names: List<MatroskaName>,
    /** The top-level chapters, in stored order; each holds its own nested ones. */
    val chapters: List<MatroskaChapter>,
) {
    /**
     * The chapters an ordered edition plays, in the order it plays them: the lowest-level enabled
     * chapters, depth first in stored order, as RFC 9559 section 20.2.4 describes. A disabled
     * chapter is skipped along with everything nested in it, and a hidden one still plays.
     *
     * Null when the edition is not ordered, and when a chapter it would play has no end, which the
     * specification requires of it; a player then plays the file plainly, as mpv does.
     */
    public val playlist: List<MatroskaChapter>?
        get() {
            if (!isOrdered) return null
            val played = mutableListOf<MatroskaChapter>()
            fun visit(chapter: MatroskaChapter) {
                if (!chapter.isEnabled) return
                if (chapter.chapters.isEmpty()) played += chapter else chapter.chapters.forEach(::visit)
            }
            chapters.forEach(::visit)
            return played.takeIf { list -> list.all { it.endNanos != null } }
        }
}

/** One `ChapterAtom`. */
public data class MatroskaChapter(
    /** The `ChapterUID`. */
    val uid: Long,
    /** The `ChapterStringUID`, the chapter's WebVTT cue identifier, or null. */
    val stringUid: String?,
    /**
     * The `ChapterTimeStart` in nanoseconds, as the file states it. For a chapter that plays
     * another segment it is a time in that segment.
     */
    val startNanos: Long,
    /** The `ChapterTimeEnd` in nanoseconds, or null when the file states none. Not part of the chapter. */
    val endNanos: Long?,
    /** `ChapterFlagHidden`: not shown to a viewer. Nested chapters carry their own flag. */
    val isHidden: Boolean,
    /** `ChapterFlagEnabled`: a disabled chapter's content is skipped. */
    val isEnabled: Boolean,
    /**
     * The `ChapterSegmentUUID` of the segment this chapter plays, or null when it plays this one.
     * A link naming this segment's own UID breaks the specification and reads as null.
     */
    val segmentUid: String?,
    /**
     * The `ChapterSegmentEditionUID`, the edition of [segmentUid] this chapter plays whole, or null
     * when it plays the range from [startNanos] to [endNanos] of that segment.
     */
    val segmentEditionUid: Long?,
    /** The `ChapterSkipType`, which says what a "skip" button may skip, or null when absent or unknown. */
    val skipType: MatroskaSkipType?,
    /** The chapter's names, one for each `ChapterDisplay`. */
    val names: List<MatroskaName>,
    /** The chapters nested in this one, in stored order. */
    val chapters: List<MatroskaChapter>,
)

/**
 * One name of an edition or chapter in its languages.
 *
 * A chapter's display states its language either as BCP 47 tags (`ChapLanguageBCP47`) or as
 * ISO 639-2 codes with optional countries (`ChapLanguage`, `ChapCountry`). When it has a BCP 47 tag
 * the other two are ignored, as RFC 9559 says, so [languages] holds the tags and [countries] is
 * empty; otherwise [languages] holds the codes, `eng` when it states none. An edition's display
 * states BCP 47 tags only, and its [languages] is empty when it states none.
 */
public data class MatroskaName(
    val text: String,
    val languages: List<String>,
    val countries: List<String>,
)

/** What a chapter is, from its `ChapterSkipType`, so that a player can offer to skip it. */
public enum class MatroskaSkipType {
    /** 0: the chapter is content, not to be skipped. */
    NoSkipping,
    /** 1: opening credits. */
    OpeningCredits,
    /** 2: end credits. */
    EndCredits,
    /** 3: a recap of earlier episodes. */
    Recap,
    /** 4: a preview of the next episode. */
    NextPreview,
    /** 5: a preview of the current episode. */
    Preview,
    /** 6: an advertisement. */
    Advertisement,
    /** 7: an intermission. */
    Intermission,
}
