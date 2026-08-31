package io.github.yuroyami.kiteffmpeg

/**
 * Refuses an audio frame the encoder cannot read, before FFmpeg reads it.
 *
 * ### Why this is a guard and not a conversion
 *
 * The video side validates dimensions and converts pixel formats on the way in. The audio side did
 * neither, and the cost was not a cryptic error: measured on 2026-08-30, a frame whose channel
 * count or sample format did not match the encoder **segfaulted the process**. FFmpeg reads
 * `nb_samples * channels * bytes_per_sample` using the ENCODER's idea of both, so a narrower or
 * differently-typed frame is read past its end. A sample-rate mismatch did not crash; it was
 * accepted silently and encoded at the wrong rate, which is the quieter half of the same bug.
 *
 * A public API must not be able to segfault its caller, so this refuses. It does NOT resample or
 * repack, and the remainder is honest: converting would need swresample bound through the C ABI,
 * which nothing here reaches yet.
 *
 * Frames that declare nothing (rate 0, no channels, [SampleFormat.None]) are passed through rather
 * than guessed at, the same way the video guard skips a frame with no dimensions.
 *
 * ### It closes the frame it refuses
 *
 * `drive` CONSUMES every frame it is handed: `EncoderCore.encode` closes it in a `finally`, on the
 * failure paths too. A guard that threw before reaching that would quietly leak the frame it just
 * refused, which is a poor trade for the crash it prevents. The contract suite's owner ledger
 * caught exactly that on the first attempt at this.
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
                "Reading it would run past the end of its buffers. Route the audio through " +
                "FilterGraph.buildAudio to resample and repack it, or open the encoder for the " +
                "format you actually have.",
        ),
    )
}
