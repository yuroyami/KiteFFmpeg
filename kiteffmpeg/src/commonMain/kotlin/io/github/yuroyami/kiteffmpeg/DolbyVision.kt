package io.github.yuroyami.kiteffmpeg

import kotlin.math.max
import kotlin.math.pow

/**
 * The Dolby Vision configuration record a stream declares, as the container's `dvcC`, `dvvC` or
 * `dvwC` box, or Matroska's block addition mapping, carries it.
 *
 * A Dolby Vision stream is an ordinary HEVC or AV1 stream, the base layer, plus a reference
 * processing unit (the RPU) on every frame that says how to turn the base layer into the picture
 * that was graded. Whether the base layer is a picture of its own is the question a player asks
 * first, and [baseLayerPlaysAlone] answers it.
 */
public data class DolbyVisionConfig(
    /** The major version of the Dolby Vision specification the stream follows. */
    val versionMajor: Int,
    /** The minor version of the Dolby Vision specification the stream follows. */
    val versionMinor: Int,
    /**
     * The profile, such as 5, 7, 8 or 10. A profile written as 8.1 reports 8 here and 1 in
     * [baseLayerCompatibility].
     */
    val profile: Int,
    /** The level, which bounds the picture size and the frame rate. */
    val level: Int,
    /** Whether the frames carry an RPU. */
    val hasRpu: Boolean,
    /**
     * Whether the stream carries an enhancement layer, as profile 7 does. FFmpeg does not decode
     * that layer.
     */
    val hasEnhancementLayer: Boolean,
    /** Whether the stream carries a base layer. */
    val hasBaseLayer: Boolean,
    /**
     * What the base layer is when shown without the RPU, as the record's
     * `dv_bl_signal_compatibility_id`: 0 nothing (profiles 5 and 10.0), 1 HDR10, 2 SDR in
     * BT.709, 4 HLG and 6 HDR10 as an Ultra HD Blu-ray carries it.
     */
    val baseLayerCompatibility: Int,
) {
    /**
     * True when the base layer is an HDR10, HLG or SDR picture of its own, which a player may show
     * as it is. False for profile 5 and profile 10.0, whose base layer is coded in Dolby's IPT
     * colour space and shows green and purple without [Frame.composeDolbyVision], and for a
     * stream that carries no base layer.
     */
    public val baseLayerPlaysAlone: Boolean get() = hasBaseLayer && baseLayerCompatibility != 0
}

/**
 * The Dolby Vision metadata of one decoded frame, read from the RPU that FFmpeg's decoder parsed
 * and attached to it.
 *
 * Brightness is given as FFmpeg gives it, as a 12-bit PQ code from 0 to 4095, which is how Dolby
 * Vision writes it. [nitsOfPq] turns a code into candelas per square metre.
 */
public data class DolbyVisionMetadata(
    /** The bit depth of the base layer the RPU's reshaping curves were written for. */
    val baseLayerBitDepth: Int,
    /**
     * Whether the frame's composition adds a residual from an enhancement layer, as a profile 7
     * stream with a full enhancement layer does. FFmpeg does not decode that layer, so a
     * composition of such a frame is the base layer's part of the picture only.
     */
    val usesEnhancementLayer: Boolean,
    /** The darkest level the content was graded for, as a PQ code. */
    val sourceMinPq: Int,
    /** The brightest level the content was graded for, as a PQ code. */
    val sourceMaxPq: Int,
    /**
     * The brightness of the frame's scene, the RPU's level 1 metadata, or null when the frame
     * carries none. Built against an FFmpeg older than 7.0 it is always null, because FFmpeg
     * exports the RPU's extension blocks from 7.0 on.
     */
    val sceneBrightness: DolbyVisionBrightness?,
) {
    public companion object {
        /** The luminance in candelas per square metre that a 12-bit PQ [code] stands for. */
        public fun nitsOfPq(code: Int): Double {
            val e = code.coerceIn(0, 4095) / 4095.0
            val p = e.pow(1.0 / 78.84375)
            return 10000.0 * (max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p)).pow(1.0 / 0.1593017578125)
        }
    }
}

/**
 * The brightness of a scene, as the RPU's level 1 metadata states it: the darkest, the average and
 * the brightest level, each as a 12-bit PQ code. A tone mapper that knows the scene's peak can
 * keep more of a dark scene than one that only knows the peak of the whole title.
 */
public data class DolbyVisionBrightness(
    val minPq: Int,
    val averagePq: Int,
    val maxPq: Int,
)

/**
 * The Dolby Vision composition of one frame, which turns the base layer and its RPU into an
 * ordinary HDR10 picture: 10-bit 4:2:0 in BT.2020 with the PQ curve, limited range, the source's
 * range as its mastering display, and no Dolby Vision metadata left on it. Every renderer that can
 * show HDR10 can then show it.
 *
 * Start one with [Frame.beginDolbyVisionComposition], fill the picture with [composeRows], and
 * take it with [finish]. [Frame.composeDolbyVision] does all three in one call on the calling
 * thread. The composition is the reason to use this class instead: it costs tens of milliseconds
 * for a 1080p frame on one thread, and bands of rows can run on several.
 *
 * The composition holds its own reference to the source frame's buffers, so the source frame may
 * be closed as soon as this exists.
 *
 * Threading. [composeRows] calls may run at the same time on different threads, as long as their
 * rows do not overlap. [finish] and [close] must wait until every [composeRows] call has returned.
 *
 * Lifetime. Close it when you do not [finish] it. [finish] hands the picture over and closes the
 * rest, and a later [close] does nothing.
 */
public class DolbyVisionComposition internal constructor(
    source: Frame,
    output: Frame,
    /** The picture's height in rows, which is where the last band ends. */
    public val height: Int,
) : AutoCloseable {

    private var source: Frame? = source
    private var output: Frame? = output

    /**
     * Composes rows [startRow] up to [endRowExclusive]. A band starts on an even row and ends on
     * an even row or at [height], because one row of 4:2:0 chroma serves two rows of the picture.
     *
     * @throws IllegalStateException after [finish] or [close]
     * @throws FFmpegException when a row is out of range or a band boundary is odd, or when the
     *         RPU describes something the composer cannot do
     */
    @Throws(FFmpegException::class)
    public fun composeRows(startRow: Int, endRowExclusive: Int) {
        val from = checkNotNull(source) { "This Dolby Vision composition is finished or closed" }
        val to = checkNotNull(output) { "This Dolby Vision composition is finished or closed" }
        if (startRow < 0 || endRowExclusive > height || startRow > endRowExclusive ||
            startRow % 2 != 0 || (endRowExclusive % 2 != 0 && endRowExclusive != height)
        ) {
            throw FFmpegException(
                FFmpegError.InvalidArgument(
                    0,
                    "Rows $startRow until $endRowExclusive are not a band of a picture $height rows high; " +
                        "a band starts on an even row and ends on an even row or at the height",
                ),
            )
        }
        if (startRow == endRowExclusive) return
        composeDolbyVisionRows(from, to, startRow, endRowExclusive)
    }

    /**
     * The composed picture, which the caller now owns and closes. Every row must have been
     * composed; rows that were not hold whatever the allocation left in them.
     *
     * @throws IllegalStateException after [finish] or [close]
     */
    public fun finish(): Frame {
        val picture = checkNotNull(output) { "This Dolby Vision composition is finished or closed" }
        output = null
        source?.close()
        source = null
        return picture
    }

    override fun close() {
        output?.close()
        output = null
        source?.close()
        source = null
    }
}

/** Every row of [composition] on the calling thread, then the picture: the body of [Frame.composeDolbyVision]. */
internal fun composeWhole(composition: DolbyVisionComposition): Frame = composition.use {
    it.composeRows(0, it.height)
    it.finish()
}

/**
 * Composes rows [startRow] until [endRowExclusive] of [source] into [output], which the C layer
 * prepared. The composition owns both frames, so this reads their handles without their locks:
 * bands on other threads must not wait for one another.
 */
internal expect fun composeDolbyVisionRows(source: Frame, output: Frame, startRow: Int, endRowExclusive: Int)

/** Until the C layer reads them, the Dolby Vision calls refuse instead of answering that a frame carries nothing. */
internal fun dolbyVisionNotWired(): Nothing = throw FFmpegException(
    FFmpegError.Unsupported(FFmpegError.AVERROR_PATCHWELCOME, "Dolby Vision does not reach FFmpeg yet"),
)
