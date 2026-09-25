package io.github.yuroyami.kiteffmpeg

public actual object FFmpeg {
    public actual val buildConfiguration: String
        get() {
            Internals.requireCompatible()
            return Internals.configuration
        }

    public actual val versions: Versions
        get() {
            Internals.requireCompatible()
            return versionsFrom(Internals.identity)
        }

    public actual val identity: FFmpegIdentity
        get() = Internals.identity

    public actual fun hasEncoder(name: String): Boolean {
        Internals.requireCompatible()
        return Internals.hasEncoder(name)
    }

    public actual fun hasDecoder(name: String): Boolean {
        Internals.requireCompatible()
        return Internals.hasDecoder(name)
    }

    public actual fun hasFilter(name: String): Boolean {
        Internals.requireCompatible()
        return Internals.hasFilter(name)
    }

    public actual fun components(kind: FFmpegComponent): List<String> {
        Internals.requireCompatible()
        return componentList(Internals.componentNames(componentCode(kind)))
    }

    // Wired in the next commit; until then these refuse rather than guess.
    public actual fun codecOf(encoder: EncoderId): CodecId? = throw mappingNotWired()

    public actual fun codecOf(decoder: DecoderId): CodecId? = throw mappingNotWired()

    public actual fun encodersFor(codec: CodecId): List<EncoderId> = throw mappingNotWired()

    public actual fun decodersFor(codec: CodecId): List<DecoderId> = throw mappingNotWired()
}

internal fun mappingNotWired(): FFmpegException =
    FFmpegException(FFmpegError.Unsupported(FFmpegError.AVERROR_PATCHWELCOME, "the codec and implementation mapping is not wired yet"))
