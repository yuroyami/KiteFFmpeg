package io.github.yuroyami.kiteffmpeg

import ffmpeg.ffkmp_codec_id
import ffmpeg.ffkmp_codec_id_by_name
import ffmpeg.ffkmp_codec_id_name
import ffmpeg.ffkmp_codec_name
import ffmpeg.ffkmp_find_decoder_by_id
import ffmpeg.ffkmp_find_decoder_by_name
import ffmpeg.ffkmp_find_encoder_by_id
import ffmpeg.ffkmp_find_encoder_by_name
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

@OptIn(ExperimentalForeignApi::class)
internal actual val codecLookups: CodecLookups = object : CodecLookups {
    override fun formatId(name: String): Int = ffkmp_codec_id_by_name(name)
    override fun formatName(id: Int): String = ffkmp_codec_id_name(id)?.toKString() ?: "none"
    override fun encoderFormat(name: String): Int? = ffkmp_find_encoder_by_name(name)?.let(::ffkmp_codec_id)
    override fun decoderFormat(name: String): Int? = ffkmp_find_decoder_by_name(name)?.let(::ffkmp_codec_id)
    override fun defaultEncoder(id: Int): String? = ffkmp_find_encoder_by_id(id)?.let { ffkmp_codec_name(it)?.toKString() }
    override fun defaultDecoder(id: Int): String? = ffkmp_find_decoder_by_id(id)?.let { ffkmp_codec_name(it)?.toKString() }
    override fun encoderNames(): List<String> = FFmpeg.components(FFmpegComponent.Encoders)
    override fun decoderNames(): List<String> = FFmpeg.components(FFmpegComponent.Decoders)
}
