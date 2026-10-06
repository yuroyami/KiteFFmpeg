package io.github.yuroyami.kiteffmpeg

@KiteFFmpegLowLevelApi
public actual class ClosedCaptionDecoder private constructor(
    private var codecContext: Long,
) : AutoCloseable {
    // Excludes close() while a decode is inside native code.
    private val lock = Any()

    @Throws(FFmpegException::class)
    public actual fun decode(captions: ByteArray, ptsMicros: Long): Subtitle? = synchronized(lock) {
        check(codecContext != 0L) { "ClosedCaptionDecoder is closed" }
        if (captions.isEmpty()) return null
        assembled(Internals.captionDecode(codecContext, captions, ptsMicros))
    }

    @Throws(FFmpegException::class)
    public actual fun drain(): Subtitle? = synchronized(lock) {
        check(codecContext != 0L) { "ClosedCaptionDecoder is closed" }
        // A packet token of 0 is the C layer's drain.
        assembled(Internals.subtitleDecode(codecContext, 0L))
    }

    /** The subtitle behind the token [subtitle], which this frees, or null for no subtitle. */
    private fun assembled(subtitle: Long): Subtitle? {
        if (subtitle == 0L) return null
        try {
            val (start, end, count) = Internals.subtitleInfo(subtitle)
            return assembleSubtitle(
                start, end,
                Internals.codecCtxWidth(codecContext), Internals.codecCtxHeight(codecContext),
                count.toInt(),
                rect = { Internals.subtitleRect(subtitle, it) },
                rgba = { Internals.subtitleRectRgba(subtitle, it) },
                text = { Internals.subtitleRectText(subtitle, it) },
            )
        } finally {
            Internals.subtitleFree(subtitle)
        }
    }

    public actual fun flush(): Unit = synchronized(lock) {
        check(codecContext != 0L) { "ClosedCaptionDecoder is closed" }
        Internals.codecCtxFlush(codecContext)
    }

    actual override fun close() {
        synchronized(lock) {
            val owned = codecContext
            if (owned == 0L) return
            codecContext = 0L
            Internals.codecCtxFree(owned)
        }
    }

    public actual companion object {
        @Throws(FFmpegException::class)
        public actual fun open(realTime: Boolean): ClosedCaptionDecoder =
            ClosedCaptionDecoder(Internals.captionDecoderOpen(realTime))
    }
}
