package io.github.yuroyami.kiteffmpeg

/** A sample rate, a channel count and a sample format: the shape of one audio stream. */
public data class AudioSpec(
    val sampleRate: Int,
    val channels: Int,
    val sampleFormat: SampleFormat,
    /**
     * Which speaker each channel belongs to, as an FFmpeg channel mask. Null means FFmpeg's default
     * layout for [channels]. Its channel count must equal [channels].
     */
    val channelLayoutMask: Long? = null,
)

/**
 * Converts audio frames between sample rates, channel counts and sample formats with FFmpeg's
 * libswresample.
 *
 * ```kotlin
 * Resampler(AudioSpec(48_000, 2, SampleFormat.FltP), AudioSpec(44_100, 2, SampleFormat.S16)).use { resampler ->
 *     decoded.forEach { frame -> frame.use { resampler.convert(it)?.use(::write) } }
 *     generateSequence { resampler.flush() }.forEach { it.use(::write) }
 * }
 * ```
 *
 * A channel layout is FFmpeg's default for the channel count unless [AudioSpec.channelLayoutMask]
 * names one, so two channels are stereo and six are 5.1 with back surrounds. A layout change at the
 * same count is converted too, such as side surrounds to back surrounds. A rate change holds some samples back to filter them, so a converted frame can be
 * shorter than the one that went in, and [flush] returns the samples still held once the input
 * ends. Output frames carry microsecond timestamps: the first one starts at the first input frame's
 * timestamp (or zero when it had none), and every later one follows from the samples before it,
 * except that an input frame whose timestamp jumps more than 100 ms past where the samples before
 * it end moves the output by the same jump, so a gap in the input stays a gap in the output.
 *
 * Not thread safe: use one resampler from one thread at a time. Available on the JVM, Android and
 * the native targets; on the web the constructor throws [FFmpegError.Unsupported].
 *
 * @throws FFmpegException when a rate or channel count is not positive, or a sample format is not
 *   one FFmpeg knows.
 */
public expect class Resampler(input: AudioSpec, output: AudioSpec) : AutoCloseable {
    /** What every frame handed to [convert] must be. */
    public val input: AudioSpec

    /** What every frame this returns is. */
    public val output: AudioSpec

    /**
     * Converts [frame], which stays the caller's to close. The caller owns the frame this returns,
     * and gets null when the resampler held every sample back.
     *
     * @throws FFmpegException when [frame] does not match [input].
     */
    @Throws(FFmpegException::class)
    public fun convert(frame: Frame): Frame?

    /**
     * The samples the resampler still holds, as one frame the caller owns, or null when it holds
     * none. Call it until it returns null once the input has ended.
     */
    @Throws(FFmpegException::class)
    public fun flush(): Frame?

    override fun close()
}

/** Refuses a frame that is not [spec], before FFmpeg reads it with the resampler's geometry. */
internal fun requireAudioSpec(frame: Frame, spec: AudioSpec) {
    val info = frame.info
    val problems = buildList {
        if (info.sampleRate != spec.sampleRate) add("sample rate ${info.sampleRate}, not ${spec.sampleRate}")
        if (info.channelCount != spec.channels) add("${info.channelCount} channels, not ${spec.channels}")
        if (info.sampleFormat != spec.sampleFormat) {
            add("sample format ${info.sampleFormat.name}, not ${spec.sampleFormat.name}")
        }
        // A frame that names no layout is read as the input's; one that names another is refused.
        val frameMask = info.channelLayoutMask
        if (frameMask != null && frameMask != spec.layoutMaskOrDefault()) {
            add("channel layout 0x${frameMask.toString(16)}, not 0x${spec.layoutMaskOrDefault().toString(16)}")
        }
    }
    if (problems.isEmpty()) return
    throw FFmpegException(
        FFmpegError.InvalidArgument(0, "This frame does not match the resampler's input: " + problems.joinToString("; ")),
    )
}

/**
 * The output timeline of one [Resampler]. Output frames are dated by counting samples from the
 * first input frame's time, so the small jitter of a decoder's timestamps never reaches them, but a
 * forward jump in the input of more than [GAP_MICROS] moves the count by the jump, as FFmpeg's
 * `aresample` does with a hard compensation, so a gap in the input stays a gap in the output (#201).
 * The few samples a rate change still holds from before the gap move with it.
 */
internal class ResampleTimeline(private val inputRate: Int, private val outputRate: Int) {
    /** The time of output sample zero, set by the first input frame. */
    private var originMicros: Long? = null
    private var shiftMicros = 0L
    private var samplesIn = 0L
    private var samplesOut = 0L

    /** Notes an input frame of [samples] samples starting at [ptsMicros]. */
    fun input(ptsMicros: Long?, samples: Int) {
        val origin = originMicros
        if (origin == null) {
            originMicros = ptsMicros ?: 0L
        } else if (ptsMicros != null) {
            val counted = resampledPtsMicros(origin + shiftMicros, samplesIn, inputRate)
            val jump = ptsMicros - counted
            if (jump > GAP_MICROS) shiftMicros += jump
        }
        samplesIn += samples
    }

    /** The time of the next output frame, of [samples] samples. */
    fun stamp(samples: Int): Long =
        resampledPtsMicros((originMicros ?: 0L) + shiftMicros, samplesOut, outputRate).also { samplesOut += samples }

    internal companion object {
        /** FFmpeg's `min_hard_comp` default: a jump smaller than this is jitter, not a gap. */
        const val GAP_MICROS = 100_000L
    }
}

/** The microsecond timestamp of the output sample at [samplesBefore], from [startMicros]. */
internal fun resampledPtsMicros(startMicros: Long, samplesBefore: Long, sampleRate: Int): Long =
    startMicros + samplesBefore * 1_000_000L / sampleRate

/** The mask this spec names, or FFmpeg's default layout mask for its channel count. */
internal fun AudioSpec.layoutMaskOrDefault(): Long = channelLayoutMask ?: defaultLayoutMask(channels)

/**
 * FFmpeg's default layout for [channels] channels, as `av_channel_layout_default` picks it in the
 * linked FFmpeg: the first native layout with that count. Six channels are 5.1 with back
 * surrounds, and in FFmpeg 9.0.2 ten, twelve, fourteen, sixteen and twenty-four channels have one
 * too. 0 when FFmpeg has no native layout with that count.
 *
 * It is asked of FFmpeg rather than copied, because the resampler takes the same default on the C
 * side. A copy in Kotlin stopped at eight channels, so a frame decoded in FFmpeg's default layout
 * for ten channels was refused for not being a layout of 0 (#129).
 */
internal expect fun defaultLayoutMask(channels: Int): Long
