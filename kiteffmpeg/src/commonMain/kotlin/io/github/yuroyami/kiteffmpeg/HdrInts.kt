package io.github.yuroyami.kiteffmpeg

/*
 * HDR metadata crosses the C layer as ints: a mastering display is ten num/den pairs in the order
 * red x, red y, green x, green y, blue x, blue y, white x, white y, minimum and maximum luminance,
 * plus flags that say which halves are present. These mirror KC_HDR_* in kitecodec_helpers.h.
 */

internal const val HDR_MASTERING_INTS = 20
internal const val HDR_HAS_PRIMARIES = 1
internal const val HDR_HAS_LUMINANCE = 2

/** Reads a mastering display from [q] at [offset]; null when [flags] name neither half. */
internal fun masteringDisplayOf(q: IntArray, offset: Int, flags: Int): MasteringDisplay? {
    fun at(pair: Int): Rational? {
        val den = q[offset + 2 * pair + 1]
        return if (den > 0) Rational(q[offset + 2 * pair], den) else null
    }
    val primaries = if (flags and HDR_HAS_PRIMARIES != 0) {
        val values = (0 until 8).map(::at)
        if (values.any { it == null }) null else values.requireNoNulls().let { v ->
            DisplayPrimaries(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
        }
    } else null
    val luminance = if (flags and HDR_HAS_LUMINANCE != 0) {
        val min = at(8)
        val max = at(9)
        if (min != null && max != null) LuminanceRange(min, max) else null
    } else null
    return if (primaries == null && luminance == null) null else MasteringDisplay(primaries, luminance)
}

/** This display as the C layer's twenty ints and its flags. */
internal fun MasteringDisplay.toInts(): Pair<IntArray, Int> {
    val q = IntArray(HDR_MASTERING_INTS) { if (it % 2 == 1) 1 else 0 }
    var flags = 0
    fun put(pair: Int, value: Rational) {
        q[2 * pair] = value.num
        q[2 * pair + 1] = value.den
    }
    primaries?.let { p ->
        listOf(p.redX, p.redY, p.greenX, p.greenY, p.blueX, p.blueY, p.whiteX, p.whiteY).forEachIndexed(::put)
        flags = flags or HDR_HAS_PRIMARIES
    }
    luminance?.let { l ->
        put(8, l.min)
        put(9, l.max)
        flags = flags or HDR_HAS_LUMINANCE
    }
    return q to flags
}

/** Both halves as one value; null when neither is present. */
internal fun hdrMetadataOf(display: MasteringDisplay?, light: ContentLightLevel?): HdrMetadata? =
    if (display == null && light == null) null else HdrMetadata(display, light)

/**
 * The JNI bridge's packing: [0] mastering flags, [1..20] its pairs, [21] 1 when a content light
 * level follows, [22] MaxCLL, [23] MaxFALL. Null in, null out.
 */
internal fun hdrFromInts(ints: IntArray?): HdrMetadata? {
    if (ints == null || ints.size < 24) return null
    val display = if (ints[0] != 0) masteringDisplayOf(ints, 1, ints[0]) else null
    val light = if (ints[21] == 1) ContentLightLevel(ints[22], ints[23]) else null
    return hdrMetadataOf(display, light)
}
