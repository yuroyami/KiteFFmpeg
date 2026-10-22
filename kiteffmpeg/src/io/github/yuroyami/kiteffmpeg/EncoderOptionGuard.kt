package io.github.yuroyami.kiteffmpeg

/**
 * Refuses an encoder option that says the same thing as a typed field on the spec.
 *
 * ### Why refusing beats picking a winner
 *
 * The typed fields are written into the codec context first and `options` is applied over them, so
 * an option that names the same setting silently wins. `VideoEncoderSpec(bitrateBps = 8_000_000,
 * options = mapOf("b" to "500k"))` encodes at 500k and reports 8 Mb/s in the field the caller set.
 *
 * Reversing the order would be no better, only wrong in the other direction: then a caller who
 * deliberately reached for an option would find it ignored. Neither is discoverable, because
 * nothing fails. The only honest answer is that the spec said two things and one of them has to
 * go, so this says which two and refuses.
 *
 * Only keys that genuinely duplicate a field are listed. An option this library has no field for
 * is the entire point of the map and passes through untouched.
 */
private val VIDEO_TYPED_OPTIONS: Map<String, String> = mapOf(
    "b" to "bitrateBps",
    "bit_rate" to "bitrateBps",
    "g" to "keyframeIntervalFrames",
    "gop_size" to "keyframeIntervalFrames",
    "pix_fmt" to "pixelFormat",
    "width" to "width",
    "height" to "height",
    "video_size" to "width and height",
    "time_base" to "frameRate",
    "framerate" to "frameRate",
    "r" to "frameRate",
)

private val AUDIO_TYPED_OPTIONS: Map<String, String> = mapOf(
    "b" to "bitrateBps",
    "bit_rate" to "bitrateBps",
    "ar" to "sampleRate",
    "sample_rate" to "sampleRate",
    "ac" to "channels",
    "channels" to "channels",
    // NOT ch_layout on its own. It refines the channel count rather than duplicating it, and a
    // passing test uses it to say 5.1(side). It collides only with channelLayoutMask, below.
    "sample_fmt" to "sampleFormat",
    "time_base" to "sampleRate",
)

/** Raw option keys that say what [VideoEncoderSpec.color] says. */
internal val COLOR_OPTION_KEYS: Set<String> =
    setOf("color_primaries", "color_trc", "colorspace", "color_range", "chroma_sample_location")

/** The raw option key for [VideoEncoderSpec.sampleAspectRatio]. */
internal const val SAR_OPTION_KEY = "aspect"

/** The raw option key for [AudioEncoderSpec.channelLayoutMask]. */
internal const val LAYOUT_OPTION_KEY = "ch_layout"

/** The spec's own collisions, plus the colour and pixel shape keys once their fields are set. */
internal fun requireNoTypedVideoOptionCollision(spec: VideoEncoderSpec): Unit = refuseCollisions(
    spec.options,
    VIDEO_TYPED_OPTIONS +
        (if (spec.color != null) COLOR_OPTION_KEYS.associateWith { "color" } else emptyMap()) +
        (if (spec.sampleAspectRatio != null) mapOf(SAR_OPTION_KEY to "sampleAspectRatio") else emptyMap()),
    "VideoEncoderSpec",
)

/** The spec's own collisions, plus `ch_layout` once [AudioEncoderSpec.channelLayoutMask] is set. */
internal fun requireNoTypedAudioOptionCollision(spec: AudioEncoderSpec): Unit = refuseCollisions(
    spec.options,
    AUDIO_TYPED_OPTIONS +
        (if (spec.channelLayoutMask != null) mapOf(LAYOUT_OPTION_KEY to "channelLayoutMask") else emptyMap()),
    "AudioEncoderSpec",
)

private fun refuseCollisions(
    options: Map<String, String>,
    typed: Map<String, String>,
    specName: String,
) {
    val clashes = options.keys.mapNotNull { key -> typed[key]?.let { key to it } }
    if (clashes.isEmpty()) return
    throw FFmpegException(
        FFmpegError.InvalidArgument(
            0,
            "$specName sets the same thing twice: " +
                clashes.joinToString("; ") { (key, field) -> "options[\"$key\"] and $field" } +
                ". The option would silently win over the field, so neither reading is safe to " +
                "assume. Drop one.",
        ),
    )
}
