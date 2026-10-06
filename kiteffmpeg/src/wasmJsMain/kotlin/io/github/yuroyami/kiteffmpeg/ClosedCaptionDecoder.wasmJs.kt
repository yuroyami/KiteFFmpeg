package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_caption_decode
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_caption_decoder_open
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_flush
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_subtitle_decode

@KiteFFmpegLowLevelApi
public actual class ClosedCaptionDecoder private constructor(private val context: Int) : AutoCloseable {
    private var closed = false

    private fun alive() = check(!closed) { "ClosedCaptionDecoder is closed" }

    public actual fun decode(captions: ByteArray, ptsMicros: Long): Subtitle? {
        alive()
        if (captions.isEmpty()) return null
        val m = requireModule()
        // The bytes cross once, as one chunk, rather than one call per byte.
        val bytes = wasmAlloc(m, captions.size)
        try {
            writeBytes(m, bytes, captions, captions.size)
            return decodeSubtitleInto(m, context) { module, slot ->
                ffkmp_caption_decode(module, context, bytes, captions.size, ptsMicros, slot)
            }
        } finally {
            wasmFree(m, bytes)
        }
    }

    // A NULL packet is the C layer's drain.
    public actual fun drain(): Subtitle? {
        alive()
        return decodeSubtitleInto(requireModule(), context) { module, slot -> ffkmp_subtitle_decode(module, context, 0, slot) }
    }

    public actual fun flush() {
        alive()
        ffkmp_codecctx_flush(requireModule(), context)
    }

    actual override fun close() {
        if (closed) return
        closed = true
        ffkmp_codecctx_free(requireModule(), context)
    }

    public actual companion object {
        public actual fun open(realTime: Boolean): ClosedCaptionDecoder {
            val m = requireModule()
            val slot = wasmAlloc(m, 4)
            try {
                val rc = ffkmp_caption_decoder_open(m, slot, if (realTime) 1 else 0)
                if (rc < 0) throw FFmpegException(FFmpegError.fromCode(rc, "opening the closed caption decoder failed with $rc"))
                return ClosedCaptionDecoder(readInt32(m, slot))
            } finally {
                wasmFree(m, slot)
            }
        }
    }
}
