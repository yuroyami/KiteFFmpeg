package io.github.yuroyami.kiteffmpeg

/** FFmpeg's `AVCOL_RANGE_JPEG`, full range. */
private const val RANGE_FULL = 2

// FFmpeg's pixel format numbers, unchanged since the format list began.
private const val YUV422P = 4
private const val YUV444P = 5
private const val YUVJ420P = 12
private const val YUVJ422P = 13
private const val YUVJ444P = 14

/**
 * The pixel format a frame of [format] and [range] must become before [codec] encodes it as an
 * image, or null when it goes in as it is.
 *
 * FFmpeg's MJPEG encoder takes plain `yuv420p` beside the `yuvj` formats, and a JPEG is read as
 * full range, but nothing in FFmpeg expands a limited-range frame's samples, so black stayed at
 * 16 and white at 235 (#193). A limited or untagged frame for MJPEG therefore goes through the
 * `yuvj` format of the same subsampling, which the converter produces full range.
 */
internal fun imageConversionFor(codec: CodecId, format: Int, range: Int): Int? {
    if (codec != CodecId.Mjpeg) return null
    if (range == RANGE_FULL || format == YUVJ420P || format == YUVJ422P || format == YUVJ444P) return null
    return when (format) {
        YUV422P -> YUVJ422P
        YUV444P -> YUVJ444P
        else -> YUVJ420P
    }
}
