package io.github.yuroyami.kiteffmpeg.dsl

import io.github.yuroyami.kiteffmpeg.AudioEncoderSpec
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec

/**
 * The typed encoder layer: sugar compiling INTO the existing `options` maps of the encoder specs.
 * Zero C, zero new funnel; [applyTo] returns a new spec whose options carry the typed knobs as the
 * exact `av_opt_set` strings the wrappers already send.
 *
 * Rate control is one sealed choice, so CRF plus CBR is unrepresentable by construction.
 *
 * ### Knobs belong to encoders, not to encoding
 *
 * `preset`, `tune` and `crf` are x264-family options. FFmpeg does not have a generic version of
 * any of them, so asking `h264_videotoolbox` for `preset=slow` sets nothing: the option is dropped
 * at open, the encode runs at the hardware encoder's own defaults, and the only trace is an entry
 * in the sink's unused-option report that nobody reads. This class used to emit them for every
 * codec. It refuses them now for an encoder that does not have them, which is the same rule the
 * collision check below has always applied: a caller who asked for two things gets told, rather
 * than getting one of them silently.
 *
 * That refusal has teeth in this project specifically. The recipes build LGPL FFmpeg with no
 * external encoders at all, so `libx264` is not in any shipped artifact; the video encoders that
 * exist are `mpeg4`, `mjpeg`, `png` and the platform hardware ones, and NONE of them has a preset
 * or a CRF. A preset in this library was, until now, always a no-op.
 */
public data class VideoEncoderTuning(
    val preset: EncoderPreset? = null,
    val profile: String? = null,
    val tune: String? = null,
    val rateControl: RateControl? = null,
) {
    init {
        require(profile == null || profile.isNotBlank()) { "profile is blank; leave it null instead" }
        require(tune == null || tune.isNotBlank()) { "tune is blank; leave it null instead" }
    }

    /**
     * The `av_opt_set` pairs this tuning becomes for [codec].
     *
     * @throws IllegalArgumentException when a knob does not exist on that encoder.
     */
    public fun compile(codec: CodecId): Map<String, String> = buildMap {
        preset?.let {
            requireKnob(codec, "preset", PRESET_ENCODERS)
            put("preset", it.ff)
        }
        // `profile` IS generic: it is an AVCodecContext field, so aac, mpeg4 and the VideoToolbox
        // encoders all take it. It is the one knob here that needs no family check.
        profile?.let { put("profile", it) }
        tune?.let {
            requireKnob(codec, "tune", PRESET_ENCODERS)
            put("tune", it)
        }
        when (rateControl) {
            is RateControl.ConstantQuality -> {
                requireKnob(codec, "crf", CRF_ENCODERS)
                put("crf", rateControl.crf.toString())
            }
            is RateControl.ConstantBitrate -> {
                // maxrate/minrate/bufsize are AVCodecContext fields, so this shape works on every
                // encoder. What it produces on its own is a CAPPED PIPE, not conformant CBR: the
                // encoder holds the ceiling but writes nothing to fill an undershoot.
                put("maxrate", rateControl.bitrateBps.toString())
                put("minrate", rateControl.bitrateBps.toString())
                put("bufsize", (rateControl.bitrateBps / 2).toString())
                // x264 is the one encoder here that can make it real: nal-hrd=cbr turns on the
                // HRD model and the filler NAL units that hold the rate exactly. Anywhere else
                // the option does not exist, and the capped pipe above is the honest best.
                if (codec.name in NAL_HRD_ENCODERS) put("nal-hrd", "cbr")
            }
            is RateControl.AverageBitrate, null -> Unit
        }
    }

    /**
     * Returns [spec] with the compiled knobs merged in. Average and constant bitrate flow
     * through the spec's own `bitrateBps`; constant quality zeroes it so the encoder rates by
     * quality alone (FFmpeg reads a zero bit_rate as unset).
     */
    public fun applyTo(spec: VideoEncoderSpec): VideoEncoderSpec {
        val compiled = compile(spec.codec)
        val collision = compiled.keys.firstOrNull { spec.options.containsKey(it) }
        require(collision == null) {
            "the typed knob '$collision' collides with the same key in the spec's options map; " +
                "keep one owner for it"
        }
        val bitrate = when (rateControl) {
            is RateControl.ConstantQuality -> 0L
            is RateControl.AverageBitrate -> rateControl.bitrateBps
            is RateControl.ConstantBitrate -> rateControl.bitrateBps
            null -> spec.bitrateBps
        }
        return spec.copy(bitrateBps = bitrate, options = spec.options + compiled)
    }

    private companion object {
        /** Encoders carrying the x264 speed ladder. NVENC spells the same option the same way. */
        val PRESET_ENCODERS: Set<String> = setOf("libx264", "libx265", "libsvtav1")

        /** Encoders with a `crf` option. Wider than the preset set: the VPx and AV1 ones have it. */
        val CRF_ENCODERS: Set<String> =
            setOf("libx264", "libx265", "libsvtav1", "libvpx-vp9", "libaom-av1")

        /** Encoders that can hold a rate exactly rather than merely cap it. */
        val NAL_HRD_ENCODERS: Set<String> = setOf("libx264")

        fun requireKnob(codec: CodecId, knob: String, accepted: Set<String>) {
            val ok = codec.name in accepted || codec.name.endsWith("_nvenc")
            require(ok) {
                "'$knob' is an x264-family option and the encoder '${codec.name}' does not have " +
                    "it, so setting it would change nothing and the encode would run at that " +
                    "encoder's own defaults. Encoders that accept it: " +
                    accepted.sorted().joinToString(", ") + ", and the *_nvenc family."
            }
        }
    }
}

public data class AudioEncoderTuning(
    val profile: String? = null,
    val bitrateBps: Long? = null,
) {
    init {
        require(profile == null || profile.isNotBlank()) { "profile is blank; leave it null instead" }
        require(bitrateBps == null || bitrateBps > 0) {
            "bitrateBps must be positive, got $bitrateBps; leave it null to keep the spec's own"
        }
    }

    public fun applyTo(spec: AudioEncoderSpec): AudioEncoderSpec {
        require(profile == null || !spec.options.containsKey("profile")) {
            "the typed knob 'profile' collides with the same key in the spec's options map; " +
                "keep one owner for it"
        }
        val options = profile?.let { spec.options + ("profile" to it) } ?: spec.options
        return spec.copy(bitrateBps = bitrateBps ?: spec.bitrateBps, options = options)
    }
}

public enum class EncoderPreset(internal val ff: String) {
    UltraFast("ultrafast"),
    SuperFast("superfast"),
    VeryFast("veryfast"),
    Faster("faster"),
    Fast("fast"),
    Medium("medium"),
    Slow("slow"),
    Slower("slower"),
    VerySlow("veryslow"),
}

public sealed interface RateControl {
    /** Quality-targeted: one CRF value, bitrate left to the encoder. */
    public data class ConstantQuality(val crf: Int) : RateControl {
        init {
            require(crf in 0..63) { "crf lives in 0..63, got $crf" }
        }
    }

    /** The spec's plain bitrate field, made explicit. */
    public data class AverageBitrate(val bitrateBps: Long) : RateControl {
        init {
            require(bitrateBps > 0) { "bitrateBps must be positive, got $bitrateBps" }
        }
    }

    /**
     * Holds the rate: maxrate = minrate = bitrate, bufsize half of it.
     *
     * On x264 this is conformant CBR, because the compiled options carry `nal-hrd=cbr` and the
     * encoder writes filler to hold an undershoot. Everywhere else it is a CAPPED PIPE: the
     * ceiling is real and the floor is a request the encoder has no way to honour.
     */
    public data class ConstantBitrate(val bitrateBps: Long) : RateControl {
        init {
            require(bitrateBps > 0) { "bitrateBps must be positive, got $bitrateBps" }
        }
    }
}
