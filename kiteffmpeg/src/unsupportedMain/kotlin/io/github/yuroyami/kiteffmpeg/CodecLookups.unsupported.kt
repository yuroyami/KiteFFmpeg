package io.github.yuroyami.kiteffmpeg

/** No FFmpeg is linked here, so nothing is known and nothing exists. */
internal actual val codecLookups: CodecLookups = object : CodecLookups {
    override fun formatId(name: String): Int = 0
    override fun formatName(id: Int): String = "none"
    override fun encoderFormat(name: String): Int? = null
    override fun decoderFormat(name: String): Int? = null
    override fun defaultEncoder(id: Int): String? = null
    override fun defaultDecoder(id: Int): String? = null
    override fun encoderNames(): List<String> = emptyList()
    override fun decoderNames(): List<String> = emptyList()
}
