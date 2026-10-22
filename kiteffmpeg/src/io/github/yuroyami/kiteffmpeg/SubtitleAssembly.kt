package io.github.yuroyami.kiteffmpeg

/** KC_SUBTITLE_BITMAP: a rectangle that is an image rather than text. */
internal const val SUBTITLE_BITMAP = 1

/** The C layer's INT64_MIN, for a subtitle time that is not known. */
internal const val SUBTITLE_TIME_UNKNOWN = Long.MIN_VALUE

/**
 * A [Subtitle] from what the C layer reports: its times, the decoder's canvas and [count]
 * rectangles, each read through [rect] as { type, x, y, width, height, forced } and then through
 * [rgba] for an image or [text] for text. An image with no area is dropped.
 */
internal inline fun assembleSubtitle(
    start: Long,
    end: Long,
    canvasWidth: Int,
    canvasHeight: Int,
    count: Int,
    rect: (Int) -> IntArray,
    rgba: (Int) -> ByteArray,
    text: (Int) -> String?,
): Subtitle {
    val images = ArrayList<SubtitleImage>()
    val texts = ArrayList<String>()
    for (i in 0 until count) {
        val r = rect(i)
        if (r[0] == SUBTITLE_BITMAP) {
            if (r[3] > 0 && r[4] > 0) images += SubtitleImage(r[1], r[2], r[3], r[4], rgba(i), r[5] != 0)
        } else {
            text(i)?.let(texts::add)
        }
    }
    return Subtitle(
        startMicros = start.takeUnless { it == SUBTITLE_TIME_UNKNOWN },
        endMicros = end.takeUnless { it == SUBTITLE_TIME_UNKNOWN },
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        images = images,
        texts = texts,
    )
}
