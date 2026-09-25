package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_codecctx_add_content_light
import ffmpeg.ffkmp_codecctx_add_mastering_display
import ffmpeg.kc_codec_ctx
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value

/**
 * The HDR metadata a pair of C readers reports: [display] fills a mastering display and its flags,
 * [light] a content light level, and each returns 1 when there is one.
 */
@OptIn(ExperimentalForeignApi::class)
internal inline fun readHdr(
    display: (q: CPointer<IntVar>, flags: CPointer<IntVar>) -> Int,
    light: (maxCll: CPointer<IntVar>, maxFall: CPointer<IntVar>) -> Int,
): HdrMetadata? = memScoped {
    val q = allocArray<IntVar>(HDR_MASTERING_INTS)
    val flags = alloc<IntVar>()
    val masteringDisplay = if (display(q, flags.ptr) > 0) {
        masteringDisplayOf(IntArray(HDR_MASTERING_INTS) { q[it] }, 0, flags.value)
    } else null
    val maxCll = alloc<IntVar>()
    val maxFall = alloc<IntVar>()
    val contentLight = if (light(maxCll.ptr, maxFall.ptr) > 0) ContentLightLevel(maxCll.value, maxFall.value) else null
    hdrMetadataOf(masteringDisplay, contentLight)
}

/** Gives an encoder [hdr] before it opens; each half is written only when present. */
@OptIn(ExperimentalForeignApi::class)
internal fun applyHdr(codecCtx: CPointer<kc_codec_ctx>, hdr: HdrMetadata) {
    hdr.masteringDisplay?.let { display ->
        val (values, flags) = display.toInts()
        memScoped {
            val q = allocArray<IntVar>(HDR_MASTERING_INTS)
            values.forEachIndexed { i, v -> q[i] = v }
            check0(ffkmp_codecctx_add_mastering_display(codecCtx, q, flags), "mastering display side data")
        }
    }
    hdr.contentLight?.let { light ->
        check0(ffkmp_codecctx_add_content_light(codecCtx, light.maxCll, light.maxFall), "content light side data")
    }
}
