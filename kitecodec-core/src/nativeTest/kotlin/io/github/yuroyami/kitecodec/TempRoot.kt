package io.github.yuroyami.kitecodec

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Where this platform lets a test write scratch files.
 *
 * **One helper, because six copies is what broke it.** Every native test that writes a fixture had
 * its own version of this, and they disagreed in ways that only showed once Linux and Windows tests
 * actually ran for the first time:
 *
 *  - Five files required `TMPDIR`, `TEMP` or `TMP` and called `error()` when none was set. GitHub's
 *    Ubuntu runners set none of them, so every one of those suites died with an
 *    `IllegalStateException` before touching a codec.
 *  - `KdIntegrationTest` fell back to `/tmp`, which does not exist on Windows, so its fixtures could
 *    not be created and the failures surfaced as `FFmpegException` from deep inside FFmpeg.
 *
 * Neither is wrong on the machine it was written on, which is exactly why they survived.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun systemTempRoot(): String =
    pickTempRoot(
        tmpdir = getenv("TMPDIR")?.toKString(),
        temp = getenv("TEMP")?.toKString(),
        tmp = getenv("TMP")?.toKString(),
        posixFallback = POSIX_TEMP_FALLBACK,
    )

/** `/tmp` is only a legal answer where it exists; Windows always sets TEMP and TMP instead. */
internal const val POSIX_TEMP_FALLBACK: String = "/tmp"

/**
 * The pure half, so the choice is testable without a machine that happens to be configured for it.
 *
 * First non-blank of the three variables wins, trailing separators trimmed. When all three are
 * missing the POSIX fallback is used, which is right on Linux and macOS and unreachable on Windows,
 * where the OS guarantees `TEMP` and `TMP`.
 */
internal fun pickTempRoot(
    tmpdir: String?,
    temp: String?,
    tmp: String?,
    posixFallback: String = POSIX_TEMP_FALLBACK,
): String {
    val chosen = sequenceOf(tmpdir, temp, tmp).firstOrNull { !it.isNullOrBlank() } ?: posixFallback
    return chosen.trimEnd('/', '\\')
}
