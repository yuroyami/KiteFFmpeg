package io.github.yuroyami.kiteffmpeg

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Refuses an audio frame the encoder cannot read, before FFmpeg reads it.
 *
 * Measured on 2026-08-30, before this guard existed: a frame whose channel count or sample format
 * did not match the encoder **segfaulted the process**. FFmpeg reads
 * `nb_samples * channels * bytes_per_sample` using the ENCODER's idea of both, so a narrower or
 * differently-typed frame is read past its end. A sample-rate mismatch did not crash; it was
 * accepted silently and encoded at the wrong rate, which is the quieter half of the same bug.
 *
 * [audioForEncoder] now converts every frame it can, so this refuses only what is left: a sample
 * rate change into an encoder that takes a fixed chunk size, because a converted frame no longer
 * has that size. Frames that declare nothing (rate 0, no channels, [SampleFormat.None]) are passed
 * through rather than guessed at, the same way the video guard skips a frame with no dimensions.
 *
 * ### It closes the frame it refuses
 *
 * `drive` CONSUMES every frame it is handed: `EncoderCore.encode` closes it in a `finally`, on the
 * failure paths too. A guard that threw before reaching that would quietly leak the frame it just
 * refused, which is a poor trade for the crash it prevents.
 */
internal fun requireEncodableAudio(
    frame: Frame,
    sampleFormat: SampleFormat,
    sampleRate: Int,
    channels: Int,
) {
    val info = frame.info
    val problems = buildList {
        if (info.sampleRate > 0 && sampleRate > 0 && info.sampleRate != sampleRate) {
            add("sample rate ${info.sampleRate} where the encoder was opened for $sampleRate")
        }
        if (info.channelCount > 0 && channels > 0 && info.channelCount != channels) {
            add("${info.channelCount} channels where the encoder was opened for $channels")
        }
        if (info.sampleFormat != SampleFormat.None &&
            sampleFormat != SampleFormat.None &&
            info.sampleFormat != sampleFormat
        ) {
            add("sample format ${info.sampleFormat.name} where the encoder was opened for ${sampleFormat.name}")
        }
    }
    if (problems.isEmpty()) return
    frame.close()
    throw FFmpegException(
        FFmpegError.InvalidArgument(
            0,
            "This audio frame does not match the encoder: " + problems.joinToString("; ") + ". " +
                "The encoder takes fixed-size chunks, so a resampled frame would not fit it. Route " +
                "the audio through FilterGraph.buildAudio with setOutputFrameSize, or open the " +
                "encoder at the rate you actually have.",
        ),
    )
}


/**
 * [input] with every frame converted to what the encoder takes, through a [Resampler].
 *
 * The sample format, the channel count and the channel layout are always converted, because that
 * leaves the sample count, and so the chunk size, unchanged. A frame that names no layout is taken
 * to have the encoder's. The sample rate is converted only when [frameSize] is 0, meaning the codec
 * takes any chunk size; a rate change into a fixed-size encoder such as AAC is refused by
 * [requireEncodableAudio]. A source frame is closed once converted, and the resampler's held
 * samples are emitted when [input] ends.
 */
internal fun audioForEncoder(
    input: Flow<Frame>,
    sampleFormat: SampleFormat,
    sampleRate: Int,
    channels: Int,
    frameSize: Int,
    channelLayoutMask: Long?,
): Flow<Frame> = flow {
    val target = AudioSpec(sampleRate, channels, sampleFormat, channelLayoutMask)
    var resampler: Resampler? = null
    suspend fun drain(done: Resampler) {
        while (true) emit(done.flush() ?: break)
    }
    try {
        input.collect { frame ->
            val info = frame.info
            val declaresNothing = info.sampleRate <= 0 || info.channelCount <= 0 || info.sampleFormat == SampleFormat.None
            val source = AudioSpec(info.sampleRate, info.channelCount, info.sampleFormat, info.channelLayoutMask)
            val sameLayout = info.channelLayoutMask == null || channelLayoutMask == null ||
                info.channelLayoutMask == channelLayoutMask
            val matches = info.sampleRate == sampleRate && info.channelCount == channels &&
                info.sampleFormat == sampleFormat && sameLayout
            if (declaresNothing || matches) {
                emit(frame)
                return@collect
            }
            if (info.sampleRate != sampleRate && frameSize != 0) {
                requireEncodableAudio(frame, sampleFormat, sampleRate, channels)
            }
            val current = resampler?.takeIf { it.input == source } ?: run {
                resampler?.let { previous ->
                    drain(previous)
                    previous.close()
                }
                Resampler(source, target).also { resampler = it }
            }
            val converted = try {
                current.convert(frame)
            } finally {
                frame.close()
            }
            if (converted != null) emit(converted)
        }
        resampler?.let { drain(it) }
    } finally {
        resampler?.close()
    }
}
