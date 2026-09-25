package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_codecctx_flush
import ffmpeg.ffkmp_codecctx_free
import ffmpeg.ffkmp_codecctx_height
import ffmpeg.ffkmp_codecctx_width
import ffmpeg.ffkmp_subtitle_decode
import ffmpeg.ffkmp_subtitle_free
import ffmpeg.ffkmp_subtitle_rect
import ffmpeg.ffkmp_subtitle_rect_count
import ffmpeg.ffkmp_subtitle_rect_rgba
import ffmpeg.ffkmp_subtitle_rect_text
import ffmpeg.ffkmp_subtitle_times
import ffmpeg.kc_codec_ctx
import ffmpeg.kc_subtitle
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

@KiteFFmpegLowLevelApi
@OptIn(ExperimentalForeignApi::class)
public actual class SubtitleDecoder internal constructor(
    public actual val stream: StreamInfo,
    private val codecCtx: CPointer<kc_codec_ctx>,
) : AutoCloseable {
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private var closed = false

    @Throws(FFmpegException::class)
    public actual fun decode(packet: Packet): Subtitle? = kotlinx.atomicfu.locks.synchronized(lock) {
        check(!closed) { "SubtitleDecoder is closed" }
        requireOwnStream(packet, stream)
        memScoped {
            val slot = alloc<CPointerVar<kc_subtitle>>()
            val rc = packet.locked { live -> ffkmp_subtitle_decode(codecCtx, live, slot.ptr) }
            if (rc < 0) throw FFmpegException(avError(rc))
            val subtitle = slot.value ?: return null
            try {
                val start = alloc<LongVar>()
                val end = alloc<LongVar>()
                ffkmp_subtitle_times(subtitle, start.ptr, end.ptr)
                val fields = List(6) { alloc<IntVar>() }
                assembleSubtitle(
                    start.value, end.value,
                    ffkmp_codecctx_width(codecCtx), ffkmp_codecctx_height(codecCtx),
                    ffkmp_subtitle_rect_count(subtitle),
                    rect = { i ->
                        check0(
                            ffkmp_subtitle_rect(
                                subtitle, i,
                                fields[0].ptr, fields[1].ptr, fields[2].ptr, fields[3].ptr, fields[4].ptr, fields[5].ptr,
                            ),
                            "subtitle rectangle",
                        )
                        IntArray(6) { fields[it].value }
                    },
                    // Called right after rect(i), so fields still hold rectangle i's size.
                    rgba = { i ->
                        val bytes = ByteArray(fields[3].value * fields[4].value * 4)
                        bytes.usePinned { pinned ->
                            check0(
                                ffkmp_subtitle_rect_rgba(subtitle, i, pinned.addressOf(0).reinterpret(), bytes.size),
                                "subtitle image",
                            )
                        }
                        bytes
                    },
                    text = { i -> ffkmp_subtitle_rect_text(subtitle, i)?.toKString() },
                )
            } finally {
                val owned = alloc<CPointerVar<kc_subtitle>>()
                owned.value = subtitle
                ffkmp_subtitle_free(owned.ptr)
            }
        }
    }

    public actual fun flush(): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        check(!closed) { "SubtitleDecoder is closed" }
        ffkmp_codecctx_flush(codecCtx)
    }

    actual override fun close(): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        if (closed) return
        closed = true
        ffkmp_codecctx_free(codecCtx)
    }
}
