package io.github.yuroyami.kiteffmpeg

/** A sample rate, a channel count and a sample format: the shape of one audio stream. */
public data class AudioSpec(
    val sampleRate: Int,
    val channels: Int,
    val sampleFormat: SampleFormat,
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
 * Channel layouts are FFmpeg's default for each channel count, so two channels are stereo and six
 * are 5.1. A rate change holds some samples back to filter them, so a converted frame can be
 * shorter than the one that went in, and [flush] returns the samples still held once the input
 * ends. Output frames carry microsecond timestamps: the first one starts at the first input frame's
 * timestamp (or zero when it had none), and every later one follows from the samples before it.
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
    }
    if (problems.isEmpty()) return
    throw FFmpegException(
        FFmpegError.InvalidArgument(0, "This frame does not match the resampler's input: " + problems.joinToString("; ")),
    )
}

/** The microsecond timestamp of the output sample at [samplesBefore], from [startMicros]. */
internal fun resampledPtsMicros(startMicros: Long, samplesBefore: Long, sampleRate: Int): Long =
    startMicros + samplesBefore * 1_000_000L / sampleRate
