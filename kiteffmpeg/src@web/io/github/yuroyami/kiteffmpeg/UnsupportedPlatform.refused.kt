package io.github.yuroyami.kiteffmpeg

internal const val PLACEHOLDER_BACKEND_UNAVAILABLE: String =
    "KiteFFmpeg uses a placeholder backend on this target; decoding and encoding are not implemented yet."

internal val placeholderUnavailableIdentity: FFmpegIdentity = FFmpegIdentity(
    status = FFmpegError.AVERROR_PATCHWELCOME,
    bypassed = false,
    bypassedStatus = 0,
    cAbiVersion = "0.0",
    libraries = listOf(
        "libavutil",
        "libavcodec",
        "libavformat",
        "libavfilter",
        "libswscale",
        "libswresample",
    ).map { name ->
        FFmpegLibraryIdentity(
            name = name,
            headerMajor = 0,
            headerMinor = 0,
            headerMicro = 0,
            runtimeMajor = 0,
            runtimeMinor = 0,
            runtimeMicro = 0,
            verdict = "platform unavailable",
        )
    },
    configurationsAgree = true,
    configurationsDisagreed = emptyList(),
    buildFFmpegRef = "none",
    buildLicenseFlavour = "none",
    buildProvisioningDir = "none",
    runtimeVersionInfo = "unavailable",
    runtimeLicense = "unavailable",
    provisioning = PLACEHOLDER_BACKEND_UNAVAILABLE,
)

internal fun placeholderBackendUnavailable(operation: String): Nothing {
    throw FFmpegException(
        FFmpegError.Unsupported(
            FFmpegError.AVERROR_PATCHWELCOME,
            "$operation is unavailable. $PLACEHOLDER_BACKEND_UNAVAILABLE",
        ),
    )
}
