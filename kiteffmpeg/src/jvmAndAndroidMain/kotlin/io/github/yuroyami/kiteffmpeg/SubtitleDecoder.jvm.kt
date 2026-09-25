package io.github.yuroyami.kiteffmpeg

// Wired in the next commit; until then MediaSource.openSubtitleDecoder refuses, so none is made.
@KiteFFmpegLowLevelApi
public actual class SubtitleDecoder private constructor(public actual val stream: StreamInfo) : AutoCloseable {
    @Throws(FFmpegException::class)
    public actual fun decode(packet: Packet): Subtitle? = throw notWired()

    public actual fun flush(): Unit = Unit

    actual override fun close(): Unit = Unit
}

internal fun notWired(): FFmpegException =
    FFmpegException(FFmpegError.Unsupported(FFmpegError.AVERROR_PATCHWELCOME, "subtitle decoding is not wired yet"))
