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

    // Wired in the next commit; until then every backend refuses rather than answers empty.
    public actual fun components(kind: FFmpegComponent): List<String> =
        throw FFmpegException(FFmpegError.Unsupported(FFmpegError.AVERROR_PATCHWELCOME, "listing FFmpeg components is not wired yet"))
}
