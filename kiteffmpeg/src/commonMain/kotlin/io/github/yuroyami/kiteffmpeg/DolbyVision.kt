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
     * carries none. Built against an FFmpeg older than 7.1 it is always null, because FFmpeg
     * exports the RPU's extension blocks from 7.1 on.
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
 * Everything the RPU of one decoded frame says about turning its base layer into the graded
 * picture, as FFmpeg's decoder parsed it into `AVDOVIMetadata`: the [header], the reshaping and
 * inverse quantization in [mapping], and the matrices and levels of the signal in [color].
 *
 * [Frame.composeDolbyVision] applies the reshaping and the matrices on the CPU. This is for a caller
 * that composes somewhere else, such as in a shader, or inspects the RPU. [Frame.dolbyVision] is
 * the cheaper read when only the levels matter.
 *
 * Every number is the one FFmpeg holds, exactly, so each can be checked against what
 * `ffprobe -show_frames` prints. Pivots are code values of the base layer, of
 * [DolbyVisionRpuHeader.baseLayerBitDepth] bits. Coefficients are fixed-point numbers over
 * 2^[DolbyVisionRpuHeader.coefficientLog2Denominator], because FFmpeg turns an RPU that codes
 * them as floating point into that form too; [coefficientValue] gives one as a [Double]. Matrix
 * entries and offsets are [Rational]s.
 */
public data class DolbyVisionRpu(
    val header: DolbyVisionRpuHeader,
    val mapping: DolbyVisionMapping,
    val color: DolbyVisionColor,
) {
    /** The number a fixed-point [coefficient] of this RPU stands for. */
    public fun coefficientValue(coefficient: Long): Double =
        coefficient.toDouble() / 2.0.pow(header.coefficientLog2Denominator)
}

/**
 * The header of an RPU, FFmpeg's `AVDOVIRpuDataHeader`, each field under its name there. Its two
 * `ext_mapping_idc` fields are left out, because nothing in FFmpeg but its RPU encoder reads them.
 */
public data class DolbyVisionRpuHeader(
    /** `rpu_type`, which is 2 for every RPU FFmpeg reads. */
    val rpuType: Int,
    /** `rpu_format`, whose bits say how the rest of the RPU is laid out. */
    val rpuFormat: Int,
    /** `vdr_rpu_profile`. */
    val vdrRpuProfile: Int,
    /** `vdr_rpu_level`. */
    val vdrRpuLevel: Int,
    /** `chroma_resampling_explicit_filter_flag`. */
    val chromaResamplingExplicitFilter: Boolean,
    /** `coef_data_type`: 0 when the RPU codes its coefficients in fixed point, 1 in floating point. */
    val coefficientDataType: Int,
    /** `coef_log2_denom`: every coefficient of the RPU is a fixed-point number over 2 to this power. */
    val coefficientLog2Denominator: Int,
    /** `vdr_rpu_normalized_idc`. */
    val vdrRpuNormalizedIdc: Int,
    /** `bl_video_full_range_flag`: whether the base layer uses the full range of its codes. */
    val baseLayerFullRange: Boolean,
    /** `bl_bit_depth`: the bit depth of the base layer, which is the scale of every pivot. */
    val baseLayerBitDepth: Int,
    /** `el_bit_depth`: the bit depth of the enhancement layer. */
    val enhancementLayerBitDepth: Int,
    /** `vdr_bit_depth`: the bit depth of the reshaped picture. */
    val vdrBitDepth: Int,
    /** `spatial_resampling_filter_flag`. */
    val spatialResamplingFilter: Boolean,
    /** `el_spatial_resampling_filter_flag`. */
    val enhancementLayerSpatialResamplingFilter: Boolean,
    /** `disable_residual_flag`: true when the composition adds nothing from an enhancement layer. */
    val disableResidual: Boolean,
)

/**
 * How an RPU maps each component of the base layer, FFmpeg's `AVDOVIDataMapping`.
 *
 * The three [curves] are the components in the base layer's order, luma first.
 */
public data class DolbyVisionMapping(
    /** `vdr_rpu_id`. */
    val vdrRpuId: Int,
    /** `mapping_color_space`. */
    val mappingColorSpace: Int,
    /** `mapping_chroma_format_idc`. */
    val mappingChromaFormat: Int,
    /** The reshaping of each of the three components. */
    val curves: List<DolbyVisionCurve>,
    /**
     * How the residual of an enhancement layer is restored before it is added, or null when the
     * RPU carries none, which it never does when [DolbyVisionRpuHeader.disableResidual] is set.
     * FFmpeg does not decode that layer, so this matters only to a caller that decodes it on its
     * own.
     */
    val nonlinearQuantization: DolbyVisionNonlinearQuantization?,
    /** `num_x_partitions`. */
    val xPartitions: Int,
    /** `num_y_partitions`. */
    val yPartitions: Int,
)

/**
 * The reshaping of one component: a curve in pieces between [pivots], each piece a polynomial or
 * an MMR mapping, FFmpeg's `AVDOVIReshapingCurve`.
 *
 * A code from `pivots[i]` up to `pivots[i + 1]` goes through `pieces[i]`, so there is one piece
 * fewer than there are pivots. A piece is evaluated on the code divided by the largest code of
 * the base layer, so on a value from 0 to 1.
 */
public data class DolbyVisionCurve(
    /** From two to nine pivots, in ascending order, as code values of the base layer. */
    val pivots: List<Int>,
    /** The mapping of each span between two pivots. */
    val pieces: List<DolbyVisionPiece>,
)

/** The mapping of one span of a [DolbyVisionCurve]. */
public sealed class DolbyVisionPiece {

    /**
     * A polynomial of the component's own value x: `coefficients[0] + coefficients[1] * x`, plus
     * `coefficients[2] * x * x` when there are three. Each coefficient is fixed point, as
     * [DolbyVisionRpu] describes.
     */
    public data class Polynomial(val coefficients: List<Long>) : DolbyVisionPiece()

    /**
     * A multivariate multiple regression of all three components at the same place: [constant]
     * plus, for each order from 1 to `coefficients.size`, seven coefficients times seven terms
     * raised to that order. The terms are the three components, their products in pairs (first
     * and second, first and third, second and third) and the product of all three. Each
     * coefficient is fixed point, as [DolbyVisionRpu] describes.
     */
    public data class Mmr(val constant: Long, val coefficients: List<List<Long>>) : DolbyVisionPiece()
}

/**
 * The linear dead-zone inverse quantization that restores an enhancement layer's residual, the
 * one method FFmpeg reads, from FFmpeg's `AVDOVIDataMapping`.
 */
public data class DolbyVisionNonlinearQuantization(
    /**
     * `nlq_pivots`, two pivots as code values of the base layer, or null when built against an
     * FFmpeg older than 7.1, which does not export them.
     */
    val pivots: List<Int>?,
    /** The parameters of each of the three components. */
    val components: List<DolbyVisionNlqComponent>,
)

/** The inverse quantization of one component, FFmpeg's `AVDOVINLQParams`. */
public data class DolbyVisionNlqComponent(
    /** `nlq_offset`, a code value of the enhancement layer. */
    val offset: Int,
    /** `vdr_in_max`, a fixed-point coefficient. */
    val vdrInMax: Long,
    /** `linear_deadzone_slope`, a fixed-point coefficient. */
    val deadZoneSlope: Long,
    /** `linear_deadzone_threshold`, a fixed-point coefficient. */
    val deadZoneThreshold: Long,
)

/**
 * The colour of the reshaped signal, FFmpeg's `AVDOVIColorMetadata`, which FFmpeg fills with its
 * defaults when the RPU carries none of its own.
 *
 * A composition takes [yccToRgbOffset] off the reshaped components and turns them into L'M'S'
 * with [yccToRgbMatrix], makes them linear with the PQ curve, and applies [rgbToLmsMatrix]. Each
 * matrix is nine entries, row by row.
 */
public data class DolbyVisionColor(
    /** `dm_metadata_id`. */
    val dmMetadataId: Int,
    /** `scene_refresh_flag`. */
    val sceneRefresh: Int,
    /** `ycc_to_rgb_matrix`, applied before PQ linearisation. */
    val yccToRgbMatrix: List<Rational>,
    /** `ycc_to_rgb_offset`, taken off each component before [yccToRgbMatrix]. */
    val yccToRgbOffset: List<Rational>,
    /** `rgb_to_lms_matrix`, applied after PQ linearisation. */
    val rgbToLmsMatrix: List<Rational>,
    /** `signal_eotf`. */
    val signalEotf: Int,
    /** `signal_eotf_param0`. */
    val signalEotfParam0: Int,
    /** `signal_eotf_param1`. */
    val signalEotfParam1: Int,
    /** `signal_eotf_param2`. */
    val signalEotfParam2: Long,
    /** `signal_bit_depth`. */
    val signalBitDepth: Int,
    /** `signal_color_space`. */
    val signalColorSpace: Int,
    /** `signal_chroma_format`. */
    val signalChromaFormat: Int,
    /** `signal_full_range_flag`, from 0 to 3. */
    val signalFullRange: Int,
    /** The darkest level the content was graded for, as a 12-bit PQ code. */
    val sourceMinPq: Int,
    /** The brightest level the content was graded for, as a 12-bit PQ code. */
    val sourceMaxPq: Int,
    /** `source_diagonal`. */
    val sourceDiagonal: Int,
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

/** How many ints each of the C layer's two Dolby Vision readers fills. */
internal const val DOLBY_VISION_INTS: Int = 8

/** Reads the C layer's eight configuration ints, in the order `ffkmp_codecpar_dovi_config` writes them. */
internal fun dolbyVisionConfigOf(ints: IntArray): DolbyVisionConfig = DolbyVisionConfig(
    versionMajor = ints[0],
    versionMinor = ints[1],
    profile = ints[2],
    level = ints[3],
    hasRpu = ints[4] != 0,
    hasEnhancementLayer = ints[5] != 0,
    hasBaseLayer = ints[6] != 0,
    baseLayerCompatibility = ints[7],
)

/** Reads the C layer's eight frame ints, in the order `ffkmp_frame_dovi_metadata` writes them. */
internal fun dolbyVisionMetadataOf(ints: IntArray): DolbyVisionMetadata = DolbyVisionMetadata(
    baseLayerBitDepth = ints[0],
    usesEnhancementLayer = ints[1] != 0,
    sourceMinPq = ints[2],
    sourceMaxPq = ints[3],
    sceneBrightness = if (ints[4] != 0) DolbyVisionBrightness(minPq = ints[5], averagePq = ints[6], maxPq = ints[7]) else null,
)

/** How many ints `ffkmp_frame_dovi_rpu` writes, which is the only capacity it accepts. */
internal const val DOLBY_VISION_RPU_INTS: Int = 1402

/** The ints of one piece, and of one curve, in `ffkmp_frame_dovi_rpu`'s layout. */
private const val RPU_PIECE_INTS = 53
private const val RPU_CURVE_INTS = 1 + 9 + 8 * RPU_PIECE_INTS

/**
 * Reads the whole RPU in the layout `ffkmp_frame_dovi_rpu` writes, which its header comment states.
 * The C layer has already refused counts, kinds and orders outside FFmpeg's bounds.
 */
internal fun dolbyVisionRpuOf(ints: IntArray): DolbyVisionRpu {
    var at = 0
    fun int(): Int = ints[at++]
    fun longAt(index: Int): Long = (ints[index].toLong() shl 32) or (ints[index + 1].toLong() and 0xFFFFFFFFL)
    fun long(): Long = longAt(at).also { at += 2 }
    fun rational(): Rational = Rational(int(), int())

    val header = DolbyVisionRpuHeader(
        rpuType = int(),
        rpuFormat = int(),
        vdrRpuProfile = int(),
        vdrRpuLevel = int(),
        chromaResamplingExplicitFilter = int() != 0,
        coefficientDataType = int(),
        coefficientLog2Denominator = int(),
        vdrRpuNormalizedIdc = int(),
        baseLayerFullRange = int() != 0,
        baseLayerBitDepth = int(),
        enhancementLayerBitDepth = int(),
        vdrBitDepth = int(),
        spatialResamplingFilter = int() != 0,
        enhancementLayerSpatialResamplingFilter = int() != 0,
        disableResidual = int() != 0,
    )
    val vdrRpuId = int()
    val mappingColorSpace = int()
    val mappingChromaFormat = int()
    val nlqMethod = int()
    val xPartitions = int()
    val yPartitions = int()
    val curves = List(3) {
        val start = at
        val count = ints[start]
        val pivots = List(count) { i -> ints[start + 1 + i] }
        val pieces = List(count - 1) { i ->
            val piece = start + 10 + i * RPU_PIECE_INTS
            if (ints[piece] == 0) {
                DolbyVisionPiece.Polynomial(List(ints[piece + 1] + 1) { j -> longAt(piece + 2 + 2 * j) })
            } else {
                DolbyVisionPiece.Mmr(
                    constant = longAt(piece + 9),
                    coefficients = List(ints[piece + 8]) { j -> List(7) { t -> longAt(piece + 11 + 2 * (7 * j + t)) } },
                )
            }
        }
        at = start + RPU_CURVE_INTS
        DolbyVisionCurve(pivots, pieces)
    }
    val components = List(3) { DolbyVisionNlqComponent(offset = int(), vdrInMax = long(), deadZoneSlope = long(), deadZoneThreshold = long()) }
    val pivotsExported = int() != 0
    val nlqPivots = listOf(int(), int())
    val mapping = DolbyVisionMapping(
        vdrRpuId = vdrRpuId,
        mappingColorSpace = mappingColorSpace,
        mappingChromaFormat = mappingChromaFormat,
        curves = curves,
        nonlinearQuantization = if (nlqMethod == 0) {
            DolbyVisionNonlinearQuantization(pivots = nlqPivots.takeIf { pivotsExported }, components = components)
        } else {
            null
        },
        xPartitions = xPartitions,
        yPartitions = yPartitions,
    )
    val color = DolbyVisionColor(
        dmMetadataId = int(),
        sceneRefresh = int(),
        yccToRgbMatrix = List(9) { rational() },
        yccToRgbOffset = List(3) { rational() },
        rgbToLmsMatrix = List(9) { rational() },
        signalEotf = int(),
        signalEotfParam0 = int(),
        signalEotfParam1 = int(),
        signalEotfParam2 = int().toLong() and 0xFFFFFFFFL,
        signalBitDepth = int(),
        signalColorSpace = int(),
        signalChromaFormat = int(),
        signalFullRange = int(),
        sourceMinPq = int(),
        sourceMaxPq = int(),
        sourceDiagonal = int(),
    )
    check(at == DOLBY_VISION_RPU_INTS) { "the Dolby Vision RPU layout read $at of $DOLBY_VISION_RPU_INTS ints" }
    return DolbyVisionRpu(header, mapping, color)
}

/** The refusal of a composition on a hardware frame, before the C layer is asked. */
internal fun dolbyVisionHardwareRefusal(): FFmpegException = FFmpegException(
    FFmpegError.InvalidArgument(
        0,
        "A hardware frame's picture is in GPU memory; download it with downloadFromHardware() before composing its Dolby Vision",
    ),
)

/** The C layer's refusal to prepare a composition, [error], said in terms of the frame. */
internal fun dolbyVisionPrepareFailure(error: FFmpegError, pixelFormat: PixelFormat): FFmpegException = FFmpegException(
    when (error) {
        is FFmpegError.Unsupported -> FFmpegError.Unsupported(
            error.code,
            "Dolby Vision composition reads 4:2:0 YUV of 8 to 16 bits, and this frame is ${pixelFormat.name}",
        )
        is FFmpegError.InvalidArgument -> FFmpegError.InvalidArgument(
            error.code,
            "This frame's Dolby Vision RPU cannot be composed, because a curve or a matrix in it is malformed",
        )
        else -> error
    },
)
