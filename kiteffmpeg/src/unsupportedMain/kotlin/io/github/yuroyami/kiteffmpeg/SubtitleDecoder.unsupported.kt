package io.github.yuroyami.kiteffmpeg

public actual class SubtitleDecoder private constructor() : AutoCloseable {
    public actual val stream: StreamInfo
        get() = placeholderBackendUnavailable("Reading a subtitle decoder stream")

    @Throws(FFmpegException::class)
    public actual fun decode(packet: Packet): Subtitle? = placeholderBackendUnavailable("Decoding a subtitle")

    public actual fun flush(): Unit = placeholderBackendUnavailable("Flushing a subtitle decoder")

    actual override fun close(): Unit = Unit
}
