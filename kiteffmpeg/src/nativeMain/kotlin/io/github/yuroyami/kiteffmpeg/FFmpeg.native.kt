package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_filter_exists
import ffmpeg.ffkmp_find_decoder_by_name
import ffmpeg.ffkmp_find_encoder_by_name
import ffmpeg.kc_ffmpeg_configuration
import kotlinx.cinterop.toKString

public actual object FFmpeg {

    /*
     * The version and configuration queries come through the identity gate, and the
     * three availability queries come through KiteFFmpeg's opaque helper boundary. No raw libav
     * declaration is needed by this file.
     *
     * Every member here except `identity` calls requireCompatibleFFmpeg() first. `identity` runs the
     * gate and does not throw, because a diagnostic that is unreadable on a rejected runtime is
     * unreadable exactly when someone needs it.
     */

    public actual val buildConfiguration: String
        get() {
            requireCompatibleFFmpeg()
            return kc_ffmpeg_configuration()?.toKString() ?: ""
        }

    public actual val versions: Versions
        get() {
            requireCompatibleFFmpeg()
            return versionsFrom(ffmpegIdentity)
        }

    public actual val identity: FFmpegIdentity
        get() = ffmpegIdentity

    public actual fun hasEncoder(name: String): Boolean {
        requireCompatibleFFmpeg()
        return ffkmp_find_encoder_by_name(name) != null
    }

    public actual fun hasDecoder(name: String): Boolean {
        requireCompatibleFFmpeg()
        return ffkmp_find_decoder_by_name(name) != null
    }

    public actual fun hasFilter(name: String): Boolean {
        requireCompatibleFFmpeg()
        return ffkmp_filter_exists(name) != 0
    }
}
