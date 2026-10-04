package io.github.yuroyami.kiteffmpeg

/**
 * Reads a [MatroskaSegment] from the payloads of a segment's `Info` and `Chapters` elements, the
 * bytes between each element's size and its end, which FFmpeg's Matroska demuxer exports as its
 * `info_payload` and `chapters_payload` options (#173).
 *
 * The payloads come from the file, so nothing here trusts them: a truncated or corrupt element ends
 * its parent where the damage starts and keeps what was read before it, an element of the wrong
 * size reads as absent, and chapters nest no deeper than [MAX_DEPTH]. It never throws.
 */
internal object MatroskaReader {
    /** The deepest chapter nesting read. A file nesting deeper has its deeper chapters left out. */
    const val MAX_DEPTH: Int = 64

    private const val SEGMENT_UUID = 0x73A4
    private const val SEGMENT_FILENAME = 0x7384
    private const val PREV_UUID = 0x3CB923
    private const val PREV_FILENAME = 0x3C83AB
    private const val NEXT_UUID = 0x3EB923
    private const val NEXT_FILENAME = 0x3E83BB
    private const val SEGMENT_FAMILY = 0x4444

    private const val EDITION_ENTRY = 0x45B9
    private const val EDITION_UID = 0x45BC
    private const val EDITION_FLAG_HIDDEN = 0x45BD
    private const val EDITION_FLAG_DEFAULT = 0x45DB
    private const val EDITION_FLAG_ORDERED = 0x45DD
    private const val EDITION_DISPLAY = 0x4520
    private const val EDITION_STRING = 0x4521
    private const val EDITION_LANGUAGE_IETF = 0x45E4

    private const val CHAPTER_ATOM = 0xB6
    private const val CHAPTER_UID = 0x73C4
    private const val CHAPTER_STRING_UID = 0x5654
    private const val CHAPTER_TIME_START = 0x91
    private const val CHAPTER_TIME_END = 0x92
    private const val CHAPTER_FLAG_HIDDEN = 0x98
    private const val CHAPTER_FLAG_ENABLED = 0x4598
    private const val CHAPTER_SEGMENT_UUID = 0x6E67
    private const val CHAPTER_SKIP_TYPE = 0x4588
    private const val CHAPTER_SEGMENT_EDITION_UID = 0x6EBC
    private const val CHAPTER_DISPLAY = 0x80
    private const val CHAP_STRING = 0x85
    private const val CHAP_LANGUAGE = 0x437C
    private const val CHAP_LANGUAGE_BCP47 = 0x437D
    private const val CHAP_COUNTRY = 0x437E

    /**
     * The segment of a source FFmpeg opened as [formatName], from the demuxer options [exported]
     * answers with their bytes, or null when the source is not Matroska or its demuxer exports no
     * `info_payload`, as one without the patch that adds it does not.
     */
    fun read(formatName: String, exported: (name: String) -> ByteArray?): MatroskaSegment? {
        if (formatName != "matroska,webm") return null
        val info = exported("info_payload") ?: return null
        return segment(info, exported("chapters_payload"))
    }

    /** The segment that [info] describes, with the editions of [chapters], or none when it is null. */
    fun segment(info: ByteArray, chapters: ByteArray?): MatroskaSegment {
        var uid: String? = null
        var filename: String? = null
        var previousUid: String? = null
        var previousFilename: String? = null
        var nextUid: String? = null
        var nextFilename: String? = null
        val families = mutableListOf<String>()
        Elements(info, 0, info.size).forEach { id, start, end ->
            when (id) {
                SEGMENT_UUID -> if (uid == null) uid = uuid(info, start, end)
                SEGMENT_FILENAME -> if (filename == null) filename = text(info, start, end)
                PREV_UUID -> if (previousUid == null) previousUid = uuid(info, start, end)
                PREV_FILENAME -> if (previousFilename == null) previousFilename = text(info, start, end)
                NEXT_UUID -> if (nextUid == null) nextUid = uuid(info, start, end)
                NEXT_FILENAME -> if (nextFilename == null) nextFilename = text(info, start, end)
                SEGMENT_FAMILY -> uuid(info, start, end)?.let(families::add)
            }
        }
        val editions = mutableListOf<MatroskaEdition>()
        if (chapters != null) {
            Elements(chapters, 0, chapters.size).forEach { id, start, end ->
                if (id == EDITION_ENTRY) editions += edition(chapters, start, end, uid)
            }
        }
        return MatroskaSegment(uid, filename, previousUid, previousFilename, nextUid, nextFilename, families, editions)
    }

    private fun edition(bytes: ByteArray, from: Int, to: Int, ownUid: String?): MatroskaEdition {
        var uid: Long? = null
        var hidden: Long? = null
        var default: Long? = null
        var ordered: Long? = null
        val names = mutableListOf<MatroskaName>()
        val chapters = mutableListOf<MatroskaChapter>()
        Elements(bytes, from, to).forEach { id, start, end ->
            when (id) {
                EDITION_UID -> if (uid == null) uid = uint(bytes, start, end)
                EDITION_FLAG_HIDDEN -> if (hidden == null) hidden = uint(bytes, start, end)
                EDITION_FLAG_DEFAULT -> if (default == null) default = uint(bytes, start, end)
                EDITION_FLAG_ORDERED -> if (ordered == null) ordered = uint(bytes, start, end)
                EDITION_DISPLAY -> names += editionName(bytes, start, end)
                CHAPTER_ATOM -> chapter(bytes, start, end, ownUid, 1)?.let(chapters::add)
            }
        }
        return MatroskaEdition(
            uid = uid,
            isDefault = (default ?: 0L) != 0L,
            isHidden = (hidden ?: 0L) != 0L,
            isOrdered = (ordered ?: 0L) != 0L,
            names = names,
            chapters = chapters,
        )
    }

    private fun editionName(bytes: ByteArray, from: Int, to: Int): MatroskaName {
        var display: String? = null
        val languages = mutableListOf<String>()
        Elements(bytes, from, to).forEach { id, start, end ->
            when (id) {
                EDITION_STRING -> if (display == null) display = text(bytes, start, end)
                EDITION_LANGUAGE_IETF -> languages += text(bytes, start, end)
            }
        }
        return MatroskaName(display ?: "", languages, emptyList())
    }

    private fun chapter(bytes: ByteArray, from: Int, to: Int, ownUid: String?, depth: Int): MatroskaChapter? {
        if (depth > MAX_DEPTH) return null
        var uid: Long? = null
        var stringUid: String? = null
        var start: Long? = null
        var end: Long? = null
        var hidden: Long? = null
        var enabled: Long? = null
        var segmentUid: String? = null
        var segmentEditionUid: Long? = null
        var skipType: Long? = null
        val names = mutableListOf<MatroskaName>()
        val chapters = mutableListOf<MatroskaChapter>()
        Elements(bytes, from, to).forEach { id, s, e ->
            when (id) {
                CHAPTER_UID -> if (uid == null) uid = uint(bytes, s, e)
                CHAPTER_STRING_UID -> if (stringUid == null) stringUid = text(bytes, s, e)
                CHAPTER_TIME_START -> if (start == null) start = uint(bytes, s, e)
                CHAPTER_TIME_END -> if (end == null) end = uint(bytes, s, e)
                CHAPTER_FLAG_HIDDEN -> if (hidden == null) hidden = uint(bytes, s, e)
                CHAPTER_FLAG_ENABLED -> if (enabled == null) enabled = uint(bytes, s, e)
                CHAPTER_SEGMENT_UUID -> if (segmentUid == null) segmentUid = uuid(bytes, s, e)
                CHAPTER_SEGMENT_EDITION_UID -> if (segmentEditionUid == null) segmentEditionUid = uint(bytes, s, e)
                CHAPTER_SKIP_TYPE -> if (skipType == null) skipType = uint(bytes, s, e)
                CHAPTER_DISPLAY -> names += chapterName(bytes, s, e)
                CHAPTER_ATOM -> chapter(bytes, s, e, ownUid, depth + 1)?.let(chapters::add)
            }
        }
        val skipTypes = MatroskaSkipType.entries
        return MatroskaChapter(
            uid = uid ?: 0L,
            stringUid = stringUid,
            startNanos = start ?: 0L,
            endNanos = end,
            isHidden = (hidden ?: 0L) != 0L,
            isEnabled = (enabled ?: 1L) != 0L,
            segmentUid = segmentUid?.takeIf { it != ownUid },
            segmentEditionUid = segmentEditionUid,
            skipType = skipType?.takeIf { it in 0L until skipTypes.size.toLong() }?.let { skipTypes[it.toInt()] },
            names = names,
            chapters = chapters,
        )
    }

    private fun chapterName(bytes: ByteArray, from: Int, to: Int): MatroskaName {
        var display: String? = null
        val codes = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val countries = mutableListOf<String>()
        Elements(bytes, from, to).forEach { id, start, end ->
            when (id) {
                CHAP_STRING -> if (display == null) display = text(bytes, start, end)
                CHAP_LANGUAGE -> codes += text(bytes, start, end)
                CHAP_LANGUAGE_BCP47 -> tags += text(bytes, start, end)
                CHAP_COUNTRY -> countries += text(bytes, start, end)
            }
        }
        // RFC 9559: with a ChapLanguageBCP47 present, ChapLanguage and ChapCountry are ignored.
        return if (tags.isNotEmpty()) {
            MatroskaName(display ?: "", tags, emptyList())
        } else {
            MatroskaName(display ?: "", codes.ifEmpty { listOf("eng") }, countries)
        }
    }

    /** An unsigned integer of up to eight bytes, its bits in a [Long]; empty reads as 0. */
    private fun uint(bytes: ByteArray, from: Int, to: Int): Long? {
        if (to - from > 8) return null
        var value = 0L
        for (i in from until to) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        return value
    }

    /** A string, cut at its first NUL, which EBML allows as padding. Malformed UTF-8 is replaced. */
    private fun text(bytes: ByteArray, from: Int, to: Int): String {
        var end = from
        while (end < to && bytes[end] != 0.toByte()) end++
        return bytes.decodeToString(from, end)
    }

    /** Sixteen bytes as 32 lowercase hexadecimal digits; any other length reads as absent. */
    private fun uuid(bytes: ByteArray, from: Int, to: Int): String? {
        if (to - from != 16) return null
        val digits = "0123456789abcdef"
        return buildString(32) {
            for (i in from until to) {
                val b = bytes[i].toInt() and 0xFF
                append(digits[b shr 4])
                append(digits[b and 0xF])
            }
        }
    }

    /**
     * The elements of one EBML master's payload, from [from] to [to]: each one's ID with its marker
     * bits, as the specification writes IDs, and the bounds of its own payload. An element whose ID
     * or size cannot be read, whose size is unknown, or that runs past [to] ends the walk.
     */
    private class Elements(private val bytes: ByteArray, private val from: Int, private val to: Int) {
        inline fun forEach(action: (id: Int, start: Int, end: Int) -> Unit) {
            var at = from
            while (at < to) {
                val idLength = vintLength(at) ?: return
                if (idLength > 4 || at + idLength > to) return
                var id = 0
                for (i in 0 until idLength) id = (id shl 8) or (bytes[at + i].toInt() and 0xFF)
                at += idLength
                if (at == to) return
                val sizeLength = vintLength(at) ?: return
                if (at + sizeLength > to) return
                var size = (bytes[at].toLong() and 0xFF) and (0xFFL shr sizeLength)
                var allOnes = size == (0xFFL shr sizeLength)
                for (i in 1 until sizeLength) {
                    val b = bytes[at + i].toLong() and 0xFF
                    size = (size shl 8) or b
                    allOnes = allOnes && b == 0xFFL
                }
                at += sizeLength
                if (allOnes || size > (to - at).toLong()) return
                val end = at + size.toInt()
                action(id, at, end)
                at = end
            }
        }

        /** The length of the variable-size integer at [at], from its leading zero bits. */
        private fun vintLength(at: Int): Int? {
            val first = bytes[at].toInt() and 0xFF
            if (first == 0) return null
            var length = 1
            while (first and (0x80 shr (length - 1)) == 0) length++
            return length
        }
    }
}
