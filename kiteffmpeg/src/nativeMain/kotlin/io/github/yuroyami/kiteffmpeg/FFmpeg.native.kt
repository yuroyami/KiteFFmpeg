package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_component_names
import ffmpeg.ffkmp_filter_exists
import ffmpeg.ffkmp_find_decoder_by_name
import ffmpeg.ffkmp_find_encoder_by_name
import ffmpeg.kc_ffmpeg_configuration
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned

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

    public actual fun components(kind: FFmpegComponent): List<String> {
        requireCompatibleFFmpeg()
        val code = componentCode(kind)
        val needed = ffkmp_component_names(code, null, 0)
        if (needed < 0) throw FFmpegException(avError(needed))
        val bytes = ByteArray(needed + 1)
        val written = bytes.usePinned { ffkmp_component_names(code, it.addressOf(0), bytes.size) }
        if (written < 0) throw FFmpegException(avError(written))
        return componentList(bytes.decodeToString(0, minOf(written, needed)))
    }

    public actual fun codecOf(encoder: EncoderId): CodecId? {
        requireCompatibleFFmpeg()
        return codecLookups.codecOf(encoder)
    }

    public actual fun codecOf(decoder: DecoderId): CodecId? {
        requireCompatibleFFmpeg()
        return codecLookups.codecOf(decoder)
    }

    public actual fun encodersFor(codec: CodecId): List<EncoderId> {
        requireCompatibleFFmpeg()
        return codecLookups.encodersFor(codec)
    }

    public actual fun decodersFor(codec: CodecId): List<DecoderId> {
        requireCompatibleFFmpeg()
        return codecLookups.decodersFor(codec)
    }
}
