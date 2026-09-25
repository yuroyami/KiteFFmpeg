package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_flush
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_height
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codecctx_width
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_subtitle_decode
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_subtitle_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_subtitle_rect
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_subtitle_rect_count
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_subtitle_rect_rgba
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_subtitle_rect_text
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_subtitle_times

@KiteFFmpegLowLevelApi
public actual class SubtitleDecoder internal constructor(
    private val context: Int,
    public actual val stream: StreamInfo,
    private val lifetime: SourceLifetime,
) : AutoCloseable {
    private var closed = false

    private fun alive() {
        check(!closed) { "SubtitleDecoder is closed" }
        lifetime.check("subtitle decoder")
    }

    public actual fun decode(packet: Packet): Subtitle? {
        alive()
        val m = requireModule()
        check(packet.pointer != 0) { "Packet is closed" }
        requireOwnStream(packet, stream)
        // One scratch block: the subtitle slot, the two 64-bit times, then six rectangle fields.
        val scratch = wasmAlloc(m, 8 + 16 + 24)
        val times = scratch + 8
        val fields = scratch + 24
        try {
            val rc = ffkmp_subtitle_decode(m, context, packet.pointer, scratch)
            if (rc < 0) throw FFmpegException(FFmpegError.InvalidData(rc, "decoding a subtitle failed with $rc"))
            val subtitle = readInt32(m, scratch)
            if (subtitle == 0) return null
            try {
                ffkmp_subtitle_times(m, subtitle, times, times + 8)
                return assembleSubtitle(
                    readInt64(m, times), readInt64(m, times + 8),
                    ffkmp_codecctx_width(m, context), ffkmp_codecctx_height(m, context),
                    ffkmp_subtitle_rect_count(m, subtitle),
                    rect = { i ->
                        val at = { k: Int -> fields + 4 * k }
                        val read = ffkmp_subtitle_rect(m, subtitle, i, at(0), at(1), at(2), at(3), at(4), at(5))
                        if (read < 0) throw FFmpegException(FFmpegError.Internal("reading subtitle rectangle $i failed with $read"))
                        IntArray(6) { readInt32(m, at(it)) }
                    },
                    // Called right after rect(i), so the fields still hold rectangle i's size.
                    rgba = { i ->
                        val size = readInt32(m, fields + 12) * readInt32(m, fields + 16) * 4
                        val pixels = wasmAlloc(m, size)
                        try {
                            val converted = ffkmp_subtitle_rect_rgba(m, subtitle, i, pixels, size)
                            if (converted < 0) throw FFmpegException(FFmpegError.Internal("converting subtitle image $i failed with $converted"))
                            readBytes(m, pixels, size)
                        } finally {
                            wasmFree(m, pixels)
                        }
                    },
                    text = { i -> utf8OrNull(m, ffkmp_subtitle_rect_text(m, subtitle, i)) },
                )
            } finally {
                writeInt32(m, scratch, subtitle)
                ffkmp_subtitle_free(m, scratch)
            }
        } finally {
            wasmFree(m, scratch)
        }
    }

    public actual fun flush() {
        alive()
        ffkmp_codecctx_flush(requireModule(), context)
    }

    actual override fun close() {
        if (closed) return
        closed = true
        // Unconditional, like StreamDecoder: the source's close never frees a caller's context.
        ffkmp_codecctx_free(requireModule(), context)
    }
}
