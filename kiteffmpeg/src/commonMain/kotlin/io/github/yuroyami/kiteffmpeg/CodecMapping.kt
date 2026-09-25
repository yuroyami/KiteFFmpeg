package io.github.yuroyami.kiteffmpeg

/** The lookups the format and implementation mapping needs, over FFmpeg's own tables. */
internal interface CodecLookups {
    /** The codec id of format [name], or 0 when FFmpeg has no such format. */
    fun formatId(name: String): Int

    /** The format name of codec id [id]. */
    fun formatName(id: Int): String

    /** The codec id encoder [name] writes, or null when this build has no such encoder. */
    fun encoderFormat(name: String): Int?

    /** The codec id decoder [name] reads, or null when this build has no such decoder. */
    fun decoderFormat(name: String): Int?

    /** The name of the encoder FFmpeg picks for codec id [id], or null when there is none. */
    fun defaultEncoder(id: Int): String?

    /** The name of the decoder FFmpeg picks for codec id [id], or null when there is none. */
    fun defaultDecoder(id: Int): String?

    /** Every encoder name this build links. */
    fun encoderNames(): List<String>

    /** Every decoder name this build links. */
    fun decoderNames(): List<String>
}

/** This backend's lookups. */
internal expect val codecLookups: CodecLookups

internal fun CodecLookups.codecOf(encoder: EncoderId): CodecId? =
    encoderFormat(encoder.name)?.let { CodecId(formatName(it)) }

internal fun CodecLookups.codecOf(decoder: DecoderId): CodecId? =
    decoderFormat(decoder.name)?.let { CodecId(formatName(it)) }

internal fun CodecLookups.encodersFor(codec: CodecId): List<EncoderId> {
    val id = formatId(codec.name).takeIf { it != 0 } ?: return emptyList()
    val preferred = defaultEncoder(id) ?: return emptyList()
    return (listOf(preferred) + encoderNames().filter { it != preferred && encoderFormat(it) == id }).map(::EncoderId)
}

internal fun CodecLookups.decodersFor(codec: CodecId): List<DecoderId> {
    val id = formatId(codec.name).takeIf { it != 0 } ?: return emptyList()
    val preferred = defaultDecoder(id) ?: return emptyList()
    return (listOf(preferred) + decoderNames().filter { it != preferred && decoderFormat(it) == id }).map(::DecoderId)
}

/**
 * The name of the encoder an encoder spec opens for [codec]: [encoder] when it writes that format,
 * or FFmpeg's default encoder for the format when [encoder] is null.
 *
 * @throws FFmpegException with [FFmpegError.InvalidArgument] for a name that is not a format, or
 *         an encoder that writes another format, and [FFmpegError.EncoderNotFound] when this build
 *         has no such encoder or none for the format.
 */
internal fun CodecLookups.encoderFor(codec: CodecId, encoder: EncoderId?): String {
    val id = formatId(codec.name)
    if (id == 0) {
        throw FFmpegException(
            FFmpegError.InvalidArgument(
                0,
                "'${codec.name}' is not a format FFmpeg knows. If it names an encoder, pass it as " +
                    "encoder = EncoderId(\"${codec.name}\") and set codec to the format it writes.",
            ),
        )
    }
    if (encoder == null) {
        return defaultEncoder(id) ?: throw FFmpegException(
            FFmpegError.EncoderNotFound(0, "This build has no encoder for '${codec.name}'."),
        )
    }
    val writes = encoderFormat(encoder.name) ?: throw FFmpegException(
        FFmpegError.EncoderNotFound(
            0,
            "No encoder named '${encoder.name}' in this build. FFmpeg.encodersFor(CodecId(\"${codec.name}\")) " +
                "lists the ones it has for that format.",
        ),
    )
    if (writes != id) {
        throw FFmpegException(
            FFmpegError.InvalidArgument(
                0,
                "The encoder '${encoder.name}' writes '${formatName(writes)}', not '${codec.name}'. Set " +
                    "codec to CodecId(\"${formatName(writes)}\"), or pick an encoder from FFmpeg.encodersFor.",
            ),
        )
    }
    return encoder.name
}
