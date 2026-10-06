package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_caption_decode
import ffmpeg.ffkmp_caption_decoder_open
import ffmpeg.ffkmp_codecctx_flush
import ffmpeg.ffkmp_codecctx_free
import ffmpeg.ffkmp_subtitle_decode
import ffmpeg.kc_codec_ctx
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

@KiteFFmpegLowLevelApi
@OptIn(ExperimentalForeignApi::class)
public actual class ClosedCaptionDecoder private constructor(
    private val codecCtx: CPointer<kc_codec_ctx>,
) : AutoCloseable {
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private var closed = false

    @Throws(FFmpegException::class)
    public actual fun decode(captions: ByteArray, ptsMicros: Long): Subtitle? = kotlinx.atomicfu.locks.synchronized(lock) {
        check(!closed) { "ClosedCaptionDecoder is closed" }
        if (captions.isEmpty()) return null
        decodeSubtitleInto(codecCtx) { slot ->
            captions.usePinned { pinned ->
                ffkmp_caption_decode(codecCtx, pinned.addressOf(0).reinterpret(), captions.size, ptsMicros, slot)
            }
        }
    }

    @Throws(FFmpegException::class)
    public actual fun drain(): Subtitle? = kotlinx.atomicfu.locks.synchronized(lock) {
        check(!closed) { "ClosedCaptionDecoder is closed" }
        // A NULL packet is the C layer's drain.
        decodeSubtitleInto(codecCtx) { slot -> ffkmp_subtitle_decode(codecCtx, null, slot) }
    }

    public actual fun flush(): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        check(!closed) { "ClosedCaptionDecoder is closed" }
        ffkmp_codecctx_flush(codecCtx)
    }

    actual override fun close(): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        if (closed) return
        closed = true
        ffkmp_codecctx_free(codecCtx)
    }

    public actual companion object {
        @Throws(FFmpegException::class)
        public actual fun open(realTime: Boolean): ClosedCaptionDecoder = memScoped {
            val slot = alloc<CPointerVar<kc_codec_ctx>>()
            val rc = ffkmp_caption_decoder_open(slot.ptr, if (realTime) 1 else 0)
            if (rc < 0) throw FFmpegException(avError(rc))
            ClosedCaptionDecoder(checkNotNull(slot.value) { "the caption decoder opened to nothing" })
        }
    }
}
