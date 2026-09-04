package io.github.yuroyami.kiteffmpeg

/** Read-only snapshot of an input stream's metadata. */
public data class StreamInfo(
    val index: Int,
    val type: MediaType,
    val codec: CodecId,
    val timeBase: Rational,
    val durationMicros: Long?,
    val bitrateBps: Long?,
    val video: VideoStreamInfo? = null,
    val audio: AudioStreamInfo? = null,
    /** Per-stream tags: `language` (`eng`, `jpn`, …), `title`, `handler_name`, … */
    val metadata: Map<String, String> = emptyMap(),
    /** What the container says this stream is for. Needed to mark or auto-select forced subtitles. */
    val disposition: Disposition = Disposition.None,
    /**
     * Clockwise rotation a renderer must apply, in degrees, from the container's display matrix.
     *
     * Phones write this into every recording. Ignoring it plays portrait video on its side, which
     * is the single most common visible bug in a first video pipeline.
     */
    val rotationDegrees: Int = 0,
    /** Where this stream's own timeline starts, in microseconds. May differ from the container's. */
    val startTimeMicros: Long = 0,
    /**
     * An owned copy of the codec configuration carried by the container, such as an avcC or hvcC
     * record. Null when the stream has no separate configuration record.
     */
    val codecExtradata: ByteArray? = null,
) {
    /** BCP 47 or the raw three letter code, whichever the container provided. Null when absent. */
    val language: String? get() = metadata["language"]

    val title: String? get() = metadata["title"]

    /**
     * Content equality, including [codecExtradata].
     *
     * A data class compares a `ByteArray` by REFERENCE, so two descriptions of the same stream from
     * two probes of the same file compared unequal purely because each held its own copy of the
     * same bytes. Anything keyed on a stream, cached by one, or checking that a stream belongs to
     * a source inherited that instability.
     *
     * The array itself is still the caller's to leave alone: it is exposed directly rather than
     * copied on every read, and mutating it after construction changes what this compares. Making
     * the bytes structurally immutable is an API change and belongs with the immutable-descriptor
     * work, not here.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamInfo) return false
        return index == other.index &&
            type == other.type &&
            codec == other.codec &&
            timeBase == other.timeBase &&
            durationMicros == other.durationMicros &&
            bitrateBps == other.bitrateBps &&
            video == other.video &&
            audio == other.audio &&
            metadata == other.metadata &&
            disposition == other.disposition &&
            rotationDegrees == other.rotationDegrees &&
            startTimeMicros == other.startTimeMicros &&
            extradataEquals(codecExtradata, other.codecExtradata)
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + type.hashCode()
        result = 31 * result + codec.hashCode()
        result = 31 * result + timeBase.hashCode()
        result = 31 * result + durationMicros.hashCode()
        result = 31 * result + bitrateBps.hashCode()
        result = 31 * result + video.hashCode()
        result = 31 * result + audio.hashCode()
        result = 31 * result + metadata.hashCode()
        result = 31 * result + disposition.hashCode()
        result = 31 * result + rotationDegrees
        result = 31 * result + startTimeMicros.hashCode()
        result = 31 * result + (codecExtradata?.contentHashCode() ?: 0)
        return result
    }

    private companion object {
        fun extradataEquals(a: ByteArray?, b: ByteArray?): Boolean = when {
            a === b -> true
            a == null || b == null -> false
            else -> a.contentEquals(b)
        }
    }
}

public data class VideoStreamInfo(
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val frameRate: Rational,
    val sampleAspectRatio: Rational,
    /** Owned snapshot of the container/bitstream colour declaration. */
    val color: ColorInfo = ColorInfo.Unspecified,
    /** VP9 sequence metadata, present only for VP9 streams. Unknown fields stay null. */
    val vp9: Vp9CodecInfo? = null,
    /**
     * Whether the stream is interlaced, and which field a deinterlacer must show first.
     *
     * [FieldOrder.Unknown] on most files, because most containers do not say. That is not the same
     * as progressive and must not be treated as it: a caller deciding whether to deinterlace has
     * to choose what an unknown answer means for it.
     */
    val fieldOrder: FieldOrder = FieldOrder.Unknown,
)

/**
 * How a video stream's fields are ordered, in DISPLAY order.
 *
 * FFmpeg distinguishes four interlaced values by coded order as well (TT, BB, TB, BT); the second
 * letter says only how the fields were stored, which nothing acting on this needs, so they collapse
 * onto the order they are presented in.
 */
public enum class FieldOrder {
    /** The container did not say. Not a synonym for [Progressive]. */
    Unknown,
    Progressive,
    /** Interlaced, top field shown first. */
    TopFirst,
    /** Interlaced, bottom field shown first. */
    BottomFirst,
    ;

    /** True for the two interlaced answers, which is the question a deinterlacer actually asks. */
    public val isInterlaced: Boolean get() = this == TopFirst || this == BottomFirst

    public companion object {
        /** Maps the C layer's 0 to 3 onto this enum; anything else is [Unknown]. */
        internal fun ofCode(code: Int): FieldOrder = when (code) {
            1 -> Progressive
            2 -> TopFirst
            3 -> BottomFirst
            else -> Unknown
        }
    }
}

/** Codec-level VP9 declarations copied from FFmpeg's probed stream parameters. */
public data class Vp9CodecInfo(
    val profile: Vp9Profile?,
    val level: Vp9Level?,
    val bitDepth: Vp9BitDepth?,
    val chromaSubsampling: Vp9ChromaSubsampling?,
)

public enum class Vp9Profile(public val number: Int) {
    Profile0(0),
    Profile1(1),
    Profile2(2),
    Profile3(3),
    ;

    public companion object {
        public fun fromNumber(value: Int): Vp9Profile? = entries.firstOrNull { it.number == value }
    }
}

/** VP9 level code as carried by FFmpeg (`10` means level 1.0, `41` means level 4.1, etc.). */
public enum class Vp9Level(public val code: Int) {
    Level1(10),
    Level1_1(11),
    Level2(20),
    Level2_1(21),
    Level3(30),
    Level3_1(31),
    Level4(40),
    Level4_1(41),
    Level5(50),
    Level5_1(51),
    Level5_2(52),
    Level6(60),
    Level6_1(61),
    Level6_2(62),
    ;

    public companion object {
        public fun fromCode(value: Int): Vp9Level? = entries.firstOrNull { it.code == value }
    }
}

public enum class Vp9BitDepth(public val bits: Int) {
    Eight(8),
    Ten(10),
    Twelve(12),
    ;

    public companion object {
        public fun fromBits(value: Int): Vp9BitDepth? = entries.firstOrNull { it.bits == value }
    }
}

/** VP9 chroma plane resolution. Chroma siting remains in [ColorInfo.chromaLocation]. */
public enum class Vp9ChromaSubsampling(public val code: Int) {
    Monochrome(400),
    Yuv420(420),
    Yuv422(422),
    Yuv444(444),
    ;

    public companion object {
        public fun fromCode(value: Int): Vp9ChromaSubsampling? = entries.firstOrNull { it.code == value }
    }
}

public data class AudioStreamInfo(
    val sampleRate: Int,
    val channels: Int,
    val sampleFormat: SampleFormat,
    /**
     * Which speaker each channel belongs to, as FFmpeg's native order mask: one bit per speaker.
     *
     * [channels] alone cannot answer this. Six channels are 5.1 with side surrounds or 5.1 with
     * back surrounds, and a downmix that guesses wrong sends the surround content to the wrong
     * speakers. Null when the container declared no layout, or declared one no mask can describe
     * (a custom channel order, or ambisonics). A caller that gets null falls back to [channels]
     * and should say that it did.
     */
    val channelLayoutMask: Long? = null,
)

/** Immutable per-frame metadata snapshot: no native handle, safe to hold forever. */
public data class FrameInfo(
    val streamIndex: Int,
    val type: MediaType,
    val pts: Long,
    val timeBase: Rational,
    val width: Int = 0,
    val height: Int = 0,
    val pixelFormat: PixelFormat = PixelFormat.None,
    val sampleCount: Int = 0,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val sampleFormat: SampleFormat = SampleFormat.None,
    /**
     * Which speaker each channel of this audio frame belongs to, as FFmpeg's native order mask.
     *
     * Same meaning and same null cases as [AudioStreamInfo.channelLayoutMask]. It is repeated per
     * frame because the decoder, not the container, is the authority on what it produced.
     */
    val channelLayoutMask: Long? = null,
    /** The decoder's own duration for this frame, in [timeBase] units. 0 when it gave none. */
    val duration: Long = 0,
    /** True when this frame can be decoded without any earlier frame. */
    val isKeyframe: Boolean = false,
    /**
     * The colour metadata a renderer must honour. Ignoring it produces a picture that is present
     * and wrong, which is worse than one that is absent: hues shift, or black turns grey.
     */
    val color: ColorInfo = ColorInfo.Unspecified,
    /** Non-square pixel aspect, when the stream declares one. 1:1 otherwise. */
    val sampleAspectRatio: Rational = Rational(1, 1),
    /**
     * True when the pixels live in GPU or hardware memory rather than in main memory.
     *
     * Such a frame has no readable planes. It is presented by the renderer that matches the decoder
     * which produced it, or downloaded first, which costs the copy the hardware path existed to
     * avoid.
     */
    val isHardware: Boolean = false,
) {
    /** False when the frame carries no timestamp (`AV_NOPTS_VALUE`). [ptsSeconds] is meaningless then. */
    val hasPts: Boolean get() = pts != NOPTS

    val ptsSeconds: Double get() = if (hasPts) pts * timeBase.asDouble else Double.NaN

    public companion object {
        /** FFmpeg's `AV_NOPTS_VALUE` sentinel. */
        public const val NOPTS: Long = Long.MIN_VALUE
    }
}
