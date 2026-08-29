package io.github.yuroyami.kiteffmpeg.dsl

import io.github.yuroyami.kiteffmpeg.AudioEncoderSpec
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec

/**
 * The typed encoder layer (KPKMP 17.10, KD-3): sugar compiling INTO the existing `options` maps
 * of the encoder specs. Zero C, zero new funnel; [applyTo] returns a new spec whose options
 * carry the typed knobs as the exact `av_opt_set` strings the wrappers already send.
 *
 * Rate control is one sealed choice, so the register's contradiction (CRF plus CBR) is
 * unrepresentable by construction; the refusal golden that remains is a typed knob colliding
 * with the same key already present in the spec's own escape hatch, which is a caller confusion
 * this class refuses rather than silently resolving.
 */
public data class VideoEncoderTuning(
    val preset: EncoderPreset? = null,
    val profile: String? = null,
    val tune: String? = null,
    val rateControl: RateControl? = null,
) {
    public fun compile(): Map<String, String> = buildMap {
        preset?.let { put("preset", it.ff) }
        profile?.let { put("profile", it) }
        tune?.let { put("tune", it) }
        when (rateControl) {
            is RateControl.ConstantQuality -> put("crf", rateControl.crf.toString())
            is RateControl.ConstantBitrate -> {
                put("maxrate", rateControl.bitrateBps.toString())
                put("minrate", rateControl.bitrateBps.toString())
                put("bufsize", (rateControl.bitrateBps / 2).toString())
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
        val compiled = compile()
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
}

public data class AudioEncoderTuning(
    val profile: String? = null,
    val bitrateBps: Long? = null,
) {
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
    public data class AverageBitrate(val bitrateBps: Long) : RateControl

    /** Capped pipe shape: maxrate = minrate = bitrate, bufsize half of it. */
    public data class ConstantBitrate(val bitrateBps: Long) : RateControl
}
