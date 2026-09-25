package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codec_id
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codec_id_by_name
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codec_id_name
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_codec_name
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_find_decoder_by_id
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_find_decoder_by_name
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_find_encoder_by_id
import io.github.yuroyami.kiteffmpeg.wasm.ffkmp_find_encoder_by_name

internal actual val codecLookups: CodecLookups = object : CodecLookups {
    override fun formatId(name: String): Int = withCString(name) { ffkmp_codec_id_by_name(requireModule(), it) }
    override fun formatName(id: Int): String = utf8OrNull(requireModule(), ffkmp_codec_id_name(requireModule(), id)) ?: "none"
    override fun encoderFormat(name: String): Int? =
        withCString(name) { ffkmp_find_encoder_by_name(requireModule(), it) }.takeIf { it != 0 }?.let { ffkmp_codec_id(requireModule(), it) }
    override fun decoderFormat(name: String): Int? =
        withCString(name) { ffkmp_find_decoder_by_name(requireModule(), it) }.takeIf { it != 0 }?.let { ffkmp_codec_id(requireModule(), it) }
    override fun defaultEncoder(id: Int): String? =
        ffkmp_find_encoder_by_id(requireModule(), id).takeIf { it != 0 }?.let { utf8OrNull(requireModule(), ffkmp_codec_name(requireModule(), it)) }
    override fun defaultDecoder(id: Int): String? =
        ffkmp_find_decoder_by_id(requireModule(), id).takeIf { it != 0 }?.let { utf8OrNull(requireModule(), ffkmp_codec_name(requireModule(), it)) }
    override fun encoderNames(): List<String> = FFmpeg.components(FFmpegComponent.Encoders)
    override fun decoderNames(): List<String> = FFmpeg.components(FFmpegComponent.Decoders)
}
