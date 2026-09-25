package io.github.yuroyami.kiteffmpeg

internal actual val codecLookups: CodecLookups = object : CodecLookups {
    override fun formatId(name: String): Int = Internals.codecIdByName(name)
    override fun formatName(id: Int): String = Internals.codecIdName(id)
    override fun encoderFormat(name: String): Int? = released(Internals.findEncoderByName(name), Internals::codecId)
    override fun decoderFormat(name: String): Int? = released(Internals.findDecoderByName(name), Internals::codecId)
    override fun defaultEncoder(id: Int): String? = released(Internals.findEncoderById(id), Internals::codecName)
    override fun defaultDecoder(id: Int): String? = released(Internals.findDecoderById(id), Internals::codecName)
    override fun encoderNames(): List<String> = FFmpeg.components(FFmpegComponent.Encoders)
    override fun decoderNames(): List<String> = FFmpeg.components(FFmpegComponent.Decoders)

    /** [read] of the codec behind [token], releasing the token; null when there is no codec. */
    private fun <T> released(token: Long, read: (Long) -> T?): T? {
        if (token == 0L) return null
        return try {
            read(token)
        } finally {
            Internals.codecRelease(token)
        }
    }
}
