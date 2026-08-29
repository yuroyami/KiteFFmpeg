package io.github.yuroyami.kiteffmpeg

public actual class Frame private constructor() : AutoCloseable {
    public actual val info: FrameInfo
        get() = placeholderBackendUnavailable("Reading a frame")

    @Throws(FFmpegException::class)
    public actual fun copyPlanesToByteArray(): ByteArray =
        placeholderBackendUnavailable("Copying frame planes")

    @Throws(FFmpegException::class)
    public actual fun copy(): Frame = placeholderBackendUnavailable("Copying a frame")

    @Throws(FFmpegException::class)
    public actual fun downloadFromHardware(): Frame =
        placeholderBackendUnavailable("Downloading a hardware frame")

    @Throws(FFmpegException::class)
    public actual fun encodeImage(codec: CodecId): ByteArray =
        placeholderBackendUnavailable("Encoding an image")

    actual override fun close(): Unit = Unit

    public actual companion object {
        @Throws(FFmpegException::class)
        public actual fun ofVideo(
            bytes: ByteArray,
            width: Int,
            height: Int,
            pixelFormat: PixelFormat,
            ptsMicros: Long,
        ): Frame = placeholderBackendUnavailable("Creating a video frame")

        @Throws(FFmpegException::class)
        public actual fun ofAudio(
            bytes: ByteArray,
            sampleCount: Int,
            sampleRate: Int,
            channels: Int,
            sampleFormat: SampleFormat,
            ptsMicros: Long,
        ): Frame = placeholderBackendUnavailable("Creating an audio frame")
    }
}

internal actual fun rescaleQ(value: Long, source: Rational, destination: Rational): Long =
    placeholderBackendUnavailable("Rescaling an FFmpeg timestamp")
