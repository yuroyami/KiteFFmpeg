package io.github.yuroyami.kiteffmpeg.plugin

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface KiteFFmpegNativeSettings {
    /**
     * The Homebrew prefix to try first when a macOS target has no vendored FFmpeg tree in `native-libs/`.
     * A system FFmpeg is only a fallback for local work on the host's own target.
     */
    val homebrewPrefix: String get() = "/opt/homebrew"

    /** The Kotlin/Native version whose sysroots the C builds use. Keep it equal to `settings.kotlin.version`. */
    val kotlinVersion: String get() = "2.4.21"
}
