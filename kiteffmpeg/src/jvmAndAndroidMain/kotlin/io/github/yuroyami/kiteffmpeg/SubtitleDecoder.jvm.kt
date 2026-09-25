package io.github.yuroyami.kiteffmpeg

@KiteFFmpegLowLevelApi
public actual class SubtitleDecoder internal constructor(
    public actual val stream: StreamInfo,
    private var codecContext: Long,
) : AutoCloseable {
    // Excludes close() while a decode is inside native code; lock order is decoder → packet.
    private val lock = Any()

    @Throws(FFmpegException::class)
    public actual fun decode(packet: Packet): Subtitle? = synchronized(lock) {
        check(codecContext != 0L) { "SubtitleDecoder is closed" }
        requireOwnStream(packet, stream)
        val subtitle = packet.locked { Internals.subtitleDecode(codecContext, it) }
        if (subtitle == 0L) return null
        try {
            val (start, end, count) = Internals.subtitleInfo(subtitle)
            assembleSubtitle(
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
        check(codecContext != 0L) { "SubtitleDecoder is closed" }
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
}
