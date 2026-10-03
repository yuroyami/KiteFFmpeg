package io.github.yuroyami.kiteffmpeg

/**
 * How many rows and columns at each edge of a stream's decoded pictures are not part of the image,
 * as the container says: a Matroska track's `PixelCrop` elements, or an MP4 track's clean aperture.
 * Encoders use it to show 1080 lines of a 1088-line coded picture, cameras to hide sensor margins,
 * and remuxers to cut black bars without encoding again.
 *
 * FFmpeg reads it and does not apply it, so a renderer shows the picture without [top] rows at the
 * top, [bottom] at the bottom, [left] columns at the left and [right] at the right, and takes the
 * display aspect from what is left. The crop that the bitstream itself carries, such as the
 * cropping of an H.264 sequence parameter set, is a different thing: the decoder applies that one
 * before a frame arrives, and it is not here.
 */
public data class VideoCrop(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
)
