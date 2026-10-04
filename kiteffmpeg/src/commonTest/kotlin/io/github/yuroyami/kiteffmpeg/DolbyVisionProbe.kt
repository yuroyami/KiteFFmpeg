package io.github.yuroyami.kiteffmpeg

import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Dolby Vision metadata `ffprobe -show_frames -of flat` prints for each frame, by frame index,
 * each as its keys below the side data entry, such as `rpu_type` or
 * `components.component.0.pieces.piece.1.poly_coef`, with the quotes taken off the values.
 */
internal fun probedRpus(flat: String): Map<Int, Map<String, String>> {
    val line = Regex("""frames\.frame\.(\d+)\.side_data_list\.side_data\.(\d+)\.([^=]+)=(.*)""")
    val entries = mutableMapOf<Pair<Int, Int>, MutableMap<String, String>>()
    flat.lineSequence().map { it.trim() }.forEach { text ->
        val (frame, entry, key, value) = (line.matchEntire(text) ?: return@forEach).destructured
        entries.getOrPut(frame.toInt() to entry.toInt()) { mutableMapOf() }[key] = value.removeSurrounding("\"")
    }
    val metadata = entries.filterValues { it["side_data_type"] == "Dolby Vision Metadata" }
    check(metadata.keys.map { it.first }.toSet().size == metadata.size) { "a frame with two Dolby Vision metadata entries" }
    return metadata.entries.associate { (key, fields) -> key.first to fields - "side_data_type" }
}

/**
 * Holds [rpu] to [probe], one frame's fields from [probedRpus], field by field: every value
 * `ffprobe` prints must equal the RPU's, and every field it prints must be one this compares, so a
 * piece or a component the RPU lacks fails too. Only the `_name` spellings of numbers are left out.
 */
internal fun assertRpuMatchesProbe(rpu: DolbyVisionRpu, probe: Map<String, String>, label: String) {
    val unread = probe.keys.filterNot { it.endsWith("_name") }.toMutableSet()
    fun field(key: String): String = assertNotNull(probe[key], "$label: ffprobe prints no $key").also { unread -= key }
    fun int(key: String, actual: Int) = assertEquals(field(key).toInt(), actual, "$label: $key")
    fun long(key: String, actual: Long) = assertEquals(field(key).toLong(), actual, "$label: $key")
    fun flag(key: String, actual: Boolean) = int(key, if (actual) 1 else 0)
    fun ints(key: String, actual: List<Int>) = assertEquals(field(key).split(' ').map(String::toInt), actual, "$label: $key")
    fun longs(key: String, actual: List<Long>) = assertEquals(field(key).split(' ').map(String::toLong), actual, "$label: $key")
    fun rationals(key: String, actual: List<Rational>) = assertEquals(
        field(key).split(' ').map { entry -> entry.split('/').let { Rational(it[0].toInt(), it[1].toInt()) } },
        actual,
        "$label: $key",
    )

    val header = rpu.header
    int("rpu_type", header.rpuType)
    int("rpu_format", header.rpuFormat)
    int("vdr_rpu_profile", header.vdrRpuProfile)
    int("vdr_rpu_level", header.vdrRpuLevel)
    flag("chroma_resampling_explicit_filter_flag", header.chromaResamplingExplicitFilter)
    int("coef_data_type", header.coefficientDataType)
    int("coef_log2_denom", header.coefficientLog2Denominator)
    int("vdr_rpu_normalized_idc", header.vdrRpuNormalizedIdc)
    flag("bl_video_full_range_flag", header.baseLayerFullRange)
    int("bl_bit_depth", header.baseLayerBitDepth)
    int("el_bit_depth", header.enhancementLayerBitDepth)
    int("vdr_bit_depth", header.vdrBitDepth)
    flag("spatial_resampling_filter_flag", header.spatialResamplingFilter)
    flag("el_spatial_resampling_filter_flag", header.enhancementLayerSpatialResamplingFilter)
    flag("disable_residual_flag", header.disableResidual)

    val mapping = rpu.mapping
    int("vdr_rpu_id", mapping.vdrRpuId)
    int("mapping_color_space", mapping.mappingColorSpace)
    int("mapping_chroma_format_idc", mapping.mappingChromaFormat)
    int("nlq_method_idc", if (mapping.nonlinearQuantization == null) -1 else 0)
    int("num_x_partitions", mapping.xPartitions)
    int("num_y_partitions", mapping.yPartitions)
    assertEquals(3, mapping.curves.size, "$label: the curves")
    mapping.curves.forEachIndexed { c, curve ->
        val component = "components.component.$c"
        ints("$component.pivots", curve.pivots)
        assertEquals(curve.pivots.size - 1, curve.pieces.size, "$label: the pieces of component $c")
        curve.pieces.forEachIndexed { i, piece ->
            val at = "$component.pieces.piece.$i"
            when (piece) {
                is DolbyVisionPiece.Polynomial -> {
                    int("$at.mapping_idc", 0)
                    int("$at.poly_order", piece.coefficients.size - 1)
                    longs("$at.poly_coef", piece.coefficients)
                }
                is DolbyVisionPiece.Mmr -> {
                    int("$at.mapping_idc", 1)
                    int("$at.mmr_order", piece.coefficients.size)
                    long("$at.mmr_constant", piece.constant)
                    piece.coefficients.forEach { assertEquals(7, it.size, "$label: a row of $at") }
                    longs("$at.mmr_coef", piece.coefficients.flatten())
                }
            }
        }
        mapping.nonlinearQuantization?.let { nlq ->
            val params = nlq.components[c]
            int("$component.nlq_offset", params.offset)
            long("$component.vdr_in_max", params.vdrInMax)
            long("$component.linear_deadzone_slope", params.deadZoneSlope)
            long("$component.linear_deadzone_threshold", params.deadZoneThreshold)
        }
    }

    val color = rpu.color
    int("dm_metadata_id", color.dmMetadataId)
    int("scene_refresh_flag", color.sceneRefresh)
    rationals("ycc_to_rgb_matrix", color.yccToRgbMatrix)
    rationals("ycc_to_rgb_offset", color.yccToRgbOffset)
    rationals("rgb_to_lms_matrix", color.rgbToLmsMatrix)
    int("signal_eotf", color.signalEotf)
    int("signal_eotf_param0", color.signalEotfParam0)
    int("signal_eotf_param1", color.signalEotfParam1)
    long("signal_eotf_param2", color.signalEotfParam2)
    int("signal_bit_depth", color.signalBitDepth)
    int("signal_color_space", color.signalColorSpace)
    int("signal_chroma_format", color.signalChromaFormat)
    int("signal_full_range_flag", color.signalFullRange)
    int("source_min_pq", color.sourceMinPq)
    int("source_max_pq", color.sourceMaxPq)
    int("source_diagonal", color.sourceDiagonal)

    assertEquals(emptySet(), unread, "$label: fields ffprobe prints that the RPU has no counterpart for")
}
