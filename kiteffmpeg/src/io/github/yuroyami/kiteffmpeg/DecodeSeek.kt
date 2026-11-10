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
