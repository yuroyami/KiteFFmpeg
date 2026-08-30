@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_convert_pixfmt
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_copy_to_buffer
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_free
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_height
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_frame_width
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_image_get_buffer_size
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_pix_fmt_from_name
import kotlin.js.JsAny

/**
 * Converts video frames to RGBA without the pixels ever entering Kotlin memory.
 *
 * ### Why this exists at all, when `copyPlanesToByteArray` is right there
 *
 * Because that one is twenty times slower on the web, and the difference is not the conversion, it
 * is the crossing. Kotlin/Wasm has no bulk typed-array bridge, so a `ByteArray` of pixels is filled
 * one byte at a time through a JS call each: the X-01 probe measured that path at 160 to 240 ms per
 * 1080p frame against a 33.3 ms budget, which is not playback. Doing the same work with the
 * conversion in C and the result handed straight to a JS array measured 8.5 to 9.7 ms.
 *
 * So this class never returns pixels. It fills a JS array the CALLER owns, which in practice is an
 * `ImageData.data` a canvas is about to draw. The bytes go from the codec module's heap into that
 * array in one JS `set`, and Kotlin only ever passes two integers and a handle.
 *
 * ### Owning one of these
 *
 * The scratch buffer is reused across frames and grows only when a bigger frame arrives, because a
 * 4K frame is 24.9 MB and allocating that sixty times a second is the other way to lose the
 * measurement. Own one per renderer, not one per frame, and [close] it with the renderer.
 *
 * Not thread-safe, which on the web is not a constraint: there are no threads.
 */
public class WebRgbaConverter : AutoCloseable {

    private var buffer: Int = 0
    private var capacity: Int = 0
    private var rgbaFormat: Int = UNRESOLVED
    private var closed: Boolean = false

    /**
     * Converts [frame] to RGBA and copies it into [destination].
     *
     * @param destination a JS `Uint8ClampedArray` or `Uint8Array` whose length is exactly
     *        width times height times four. An `ImageData.data` is the intended argument.
     * @return false when this frame cannot be drawn: it carries no picture, the RGBA format is not
     *         in this build, the conversion failed, or [destination] is the wrong size. False is
     *         not an error here. A renderer answers the engine's `present` with it, the schedule
     *         counts a drop, and playback carries on, which is what the renderer contract asks for.
     */
    public fun copyInto(frame: Frame, destination: JsAny): Boolean {
        check(!closed) { "this WebRgbaConverter is closed" }
        val module = requireModule()
        val source = frame.pointer
        if (source == 0) return false

        val width = ffkmp_frame_width(module, source)
        val height = ffkmp_frame_height(module, source)
        if (width <= 0 || height <= 0) return false

        val format = resolveRgba(module)
        if (format < 0) return false

        val size = ffkmp_image_get_buffer_size(module, format, width, height, 1)
        if (size <= 0) return false
        if (webByteLength(destination) != size) return false

        // The converted frame is a NEW frame owned here and freed on every path out, including the
        // failure ones. It is not the caller's to close and it must not outlive this call.
        val converted = ffkmp_frame_convert_pixfmt(module, source, format)
        if (converted == 0) return false
        try {
            if (!reserve(module, size)) return false
            // Through a packed buffer rather than reading plane 0 directly, because a converted
            // frame's linesize may carry row padding and a canvas will not accept a stride.
            if (ffkmp_frame_copy_to_buffer(module, converted, buffer, size) != size) return false
            webCopyHeapInto(module, buffer, size, destination)
            return true
        } finally {
            ffkmp_frame_free(module, converted)
        }
    }

    /** Grows the scratch buffer when a bigger frame arrives, and never shrinks it back. */
    private fun reserve(module: JsAny, size: Int): Boolean {
        if (capacity >= size) return true
        if (buffer != 0) wasmFree(module, buffer)
        buffer = wasmAlloc(module, size)
        capacity = if (buffer == 0) 0 else size
        return buffer != 0
    }

    /**
     * Looked up once and remembered, INCLUDING the failure.
     *
     * `rgba` is absent from a build only if someone disabled it, and re-asking per frame would
     * allocate a C string sixty times a second to learn the same thing.
     */
    private fun resolveRgba(module: JsAny): Int {
        if (rgbaFormat != UNRESOLVED) return rgbaFormat
        rgbaFormat = withCString("rgba") { name -> ffkmp_pix_fmt_from_name(module, name) }
        return rgbaFormat
    }

    override fun close() {
        if (closed) return
        closed = true
        val held = buffer
        buffer = 0
        capacity = 0
        if (held != 0) wasmFree(requireModule(), held)
    }

    private companion object {
        /** Not a pixel format: AV_PIX_FMT_NONE is -1, so a sentinel has to sit outside that. */
        const val UNRESOLVED = -2
    }
}

/** One `set` over a heap view. The whole reason this path is twenty times the byte-at-a-time one. */
@JsFun("(m, p, n, dst) => { dst.set(m.HEAPU8.subarray(p, p + n)); }")
private external fun webCopyHeapInto(module: JsAny, pointer: Int, length: Int, destination: JsAny)

/** -1 for anything that is not a typed array, so a wrong argument is refused rather than trusted. */
@JsFun("(a) => (a && typeof a.length === 'number' && a.BYTES_PER_ELEMENT === 1) ? a.length : -1")
private external fun webByteLength(array: JsAny): Int
