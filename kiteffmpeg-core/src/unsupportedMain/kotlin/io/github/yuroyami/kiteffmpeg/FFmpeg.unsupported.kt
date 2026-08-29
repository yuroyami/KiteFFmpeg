package io.github.yuroyami.kiteffmpeg

public actual object FFmpeg {
    public actual val buildConfiguration: String = "unavailable (placeholder backend)"

    public actual val versions: Versions = versionsFrom(placeholderUnavailableIdentity)

    public actual val identity: FFmpegIdentity = placeholderUnavailableIdentity

    public actual fun hasEncoder(name: String): Boolean = false

    public actual fun hasDecoder(name: String): Boolean = false

    public actual fun hasFilter(name: String): Boolean = false
}
