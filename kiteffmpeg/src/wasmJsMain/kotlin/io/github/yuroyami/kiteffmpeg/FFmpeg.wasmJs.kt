package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_filter_exists
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_find_decoder_by_name
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_find_encoder_by_name
import io.github.yuroyami.kiteffmpeg.wasm.kc_ffmpeg_configuration

/**
 * The real web backend, over the generated binding.
 *
 * Every member requires [KiteFFmpegWeb.load] to have completed. That is not a quirk of this object:
 * the codec is a separate wasm module and there is nothing to ask before it is instantiated.
 */
public actual object FFmpeg {

    public actual val buildConfiguration: String
        get() = utf8OrNull(requireModule(), kc_ffmpeg_configuration(requireModule())).orEmpty()

    public actual val versions: Versions get() = versionsFrom(identity)

    /**
     * Cached per loaded module, not per call.
     *
     * Reading it copies the whole 2,176-byte `kc_ffmpeg_report` out of codec memory and decodes
     * seven strings, and `KitePlayerPlatform.availability` touches it more than once per player.
     * Keyed on the module so a reload after `KiteFFmpegWeb.load` cannot serve a stale answer, which
     * is the same reason the web platform defaults refuse to cache availability.
     */
    private var cachedFor: kotlin.js.JsAny? = null
    private var cached: FFmpegIdentity? = null

    public actual val identity: FFmpegIdentity
        get() {
            val module = requireModule()
            val hit = cached
            if (hit != null && cachedFor === module) return hit
            val fresh = webIdentity()
            cached = fresh
            cachedFor = module
            return fresh
        }

    public actual fun hasEncoder(name: String): Boolean =
        withCString(name) { ptr -> ffkmp_find_encoder_by_name(requireModule(), ptr) != 0 }

    public actual fun hasDecoder(name: String): Boolean =
        withCString(name) { ptr -> ffkmp_find_decoder_by_name(requireModule(), ptr) != 0 }

    public actual fun hasFilter(name: String): Boolean =
        withCString(name) { ptr -> ffkmp_filter_exists(requireModule(), ptr) != 0 }
}
