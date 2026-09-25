package io.github.yuroyami.kiteffmpeg

public actual class Resampler actual constructor(
    public actual val input: AudioSpec,
    public actual val output: AudioSpec,
) : AutoCloseable {
    init {
        placeholderBackendUnavailable("Resampling audio")
    }

    @Throws(FFmpegException::class)
    public actual fun convert(frame: Frame): Frame? = placeholderBackendUnavailable("Resampling audio")

    @Throws(FFmpegException::class)
    public actual fun flush(): Frame? = placeholderBackendUnavailable("Resampling audio")

    actual override fun close(): Unit = Unit
}
