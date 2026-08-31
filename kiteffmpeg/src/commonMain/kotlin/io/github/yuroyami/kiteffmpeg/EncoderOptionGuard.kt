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
    // NOT ch_layout. It is the only way to say 5.1(side) rather than 5.1(back), which a channel
    // COUNT cannot express, so it refines the typed field instead of duplicating it. Listing it
    // here broke a passing test that used it for exactly that, which is the check working.
    "sample_fmt" to "sampleFormat",
    "time_base" to "sampleRate",
)

internal fun requireNoTypedVideoOptionCollision(options: Map<String, String>): Unit =
    refuseCollisions(options, VIDEO_TYPED_OPTIONS, "VideoEncoderSpec")

internal fun requireNoTypedAudioOptionCollision(options: Map<String, String>): Unit =
    refuseCollisions(options, AUDIO_TYPED_OPTIONS, "AudioEncoderSpec")

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
