package io.github.yuroyami.kiteffmpeg

/**
 * The local file [url] names, as FFmpeg's `file` protocol would open it, or null when it names no
 * local file. A bare path is one, and so is `file:` followed by a path, which FFmpeg opens with the
 * prefix cut off. Any other scheme, such as `https:` or `fd:`, is not a file this can compare. A
 * single letter before the colon is a Windows drive, not a scheme, as FFmpeg reads it too.
 */
internal fun localFileOf(url: String): String? {
    if (url.startsWith("file:")) return url.removePrefix("file:").ifEmpty { null }
    val colon = url.indexOf(':')
    if (colon < 2) return url.ifEmpty { null }
    val scheme = url.substring(0, colon)
    val looksLikeScheme = scheme.first().isLetter() && scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    return if (looksLikeScheme) null else url
}
