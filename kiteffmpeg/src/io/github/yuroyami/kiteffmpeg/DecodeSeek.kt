package io.github.yuroyami.kiteffmpeg

/**
 * How far before its target a seek for a decode that walks forward aims, for [stream] among
 * [streams] with every stream on (#252).
 *
 * The C seek reads on after it lands and aims earlier again until the keyframe it lands on shows
 * by the target, but only for the stream FFmpeg calls the default one. With every stream on, that
 * is [stream] exactly when [stream] is the only video stream that has a size and is not a cover
 * picture: FFmpeg scores such a stream above any audio stream. Such a seek lands where the walk
 * should begin, so it aims at the target itself; aiming five seconds early only decoded up to five
 * seconds of pictures that were thrown away, about 120 of them at 24 fps. Every other stream, an
 * audio one among them, keeps the five second back-off, because nothing checks where its seek
 * lands, and a byte-position seek on an indexless container such as MPEG-TS can land past the
 * keyframe it aimed at.
 */
internal fun decodeSeekBackoffMicros(streams: List<StreamInfo>, stream: StreamInfo?): Long {
    val pictures = streams.filter { it.isCheckedPicture() }
    return if (stream != null && pictures.size == 1 && pictures[0].index == stream.index) 0L else DECODE_SEEK_BACKOFF_MICROS
}

/**
 * [decodeSeekBackoffMicros] for a seek through a packet reader that has only [stream] on. FFmpeg's
 * default stream is then [stream] whatever else the media holds, so the check covers any video
 * stream that is not a cover picture.
 */
internal fun readerSeekBackoffMicros(stream: StreamInfo): Long =
    if (stream.type == MediaType.Video && !stream.disposition.attachedPicture) 0L else DECODE_SEEK_BACKOFF_MICROS

private fun StreamInfo.isCheckedPicture(): Boolean =
    type == MediaType.Video && !disposition.attachedPicture && (video?.width ?: 0) > 0 && (video?.height ?: 0) > 0

/** The back-off for a stream the C seek does not check. */
internal const val DECODE_SEEK_BACKOFF_MICROS: Long = 5_000_000L

/** What FFmpeg answers a seek by time on a stream with no way to do one, such as raw H.264: AVERROR(EPERM). */
internal const val SEEK_REFUSED: Int = -1

/** FFmpeg's `AVSEEK_FLAG_BYTE`: the seek's target is a byte offset, not a time. */
internal const val AVSEEK_FLAG_BYTE: Int = 2

/**
 * The error [MediaSource.extractFrame] answers when a stream refuses its seek by time and cannot
 * go back to its first byte either, as a pipe cannot (#253). A bare EPERM said neither.
 */
internal fun unseekableExtract(atMicros: Long): FFmpegException = FFmpegException(
    FFmpegError.Unsupported(
        SEEK_REFUSED,
        "this stream cannot seek, by time or back to its start, so a frame at ${atMicros}µs cannot be " +
            "reached; open the source again to extract from it",
    ),
)

/**
 * The time of the [index]th frame decoded from the first byte of a stream whose frames carry no
 * timestamp, from the stream's [frameRate] (#253); null when the stream declares no rate. Raw
 * H.264 is such a stream, and this is how the `ffmpeg` command line dates its frames too.
 */
internal fun untimedFrameMicros(index: Int, frameRate: Rational?): Long? {
    val rate = frameRate?.takeIf { it.num > 0 && it.den > 0 } ?: return null
    return index * 1_000_000L * rate.den / rate.num
}
