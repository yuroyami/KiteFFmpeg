package io.github.yuroyami.kiteffmpeg.dsl

/**
 * The one escaping function every typed filter argument passes through (KPKMP 17.10, KD-1).
 *
 * FFmpeg's filter description syntax gives `\`, `'`, `:`, `,`, `;`, `[`, `]` and `=` structural
 * meaning. A value containing any of them (or whitespace, which the parser trims) is escaped the
 * way FFmpeg's own docs prescribe: backslash-escape `\` and `'`, then wrap the whole value in
 * single quotes. A plain value passes through untouched so the compiled strings stay readable,
 * which law 4 (values, not magic) cares about: the compiled description is what a bug report
 * carries.
 */
public fun escapeFilterValue(value: String): String {
    val needsQuoting = value.any { it in STRUCTURAL || it.isWhitespace() }
    if (!needsQuoting) return value
    val escaped = buildString(value.length + 8) {
        for (ch in value) {
            if (ch == '\\' || ch == '\'') append('\\')
            append(ch)
        }
    }
    return "'$escaped'"
}

private const val STRUCTURAL: String = "\\':,;[]="
