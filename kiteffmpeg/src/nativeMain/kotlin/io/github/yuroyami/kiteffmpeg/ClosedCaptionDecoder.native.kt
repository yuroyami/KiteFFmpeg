package io.github.yuroyami.kiteffmpeg

@KiteFFmpegLowLevelApi
public actual class ClosedCaptionDecoder private constructor() : AutoCloseable {
    @Throws(FFmpegException::class)
    public actual fun decode(captions: ByteArray, ptsMicros: Long): Subtitle? = notWiredYet()

    @Throws(FFmpegException::class)
    public actual fun drain(): Subtitle? = notWiredYet()

    public actual fun flush(): Unit = notWiredYet()

    actual override fun close(): Unit = Unit

    public actual companion object {
        @Throws(FFmpegException::class)
        public actual fun open(): ClosedCaptionDecoder = notWiredYet()
    }
}

/** The shape of #179 lands before its wiring, which the next commit adds. */
private fun notWiredYet(): Nothing =
    throw FFmpegException(FFmpegError.Unsupported(FFmpegError.AVERROR_PATCHWELCOME, "the closed caption decoder is not wired yet"))
