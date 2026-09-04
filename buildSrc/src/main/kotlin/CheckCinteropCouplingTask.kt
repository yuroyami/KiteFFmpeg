package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * The zero ceiling on direct FFmpeg coupling in kiteffmpeg's Kotlin.
 *
 * S1.a.8 removes FFmpeg's headers from the cinterop definition. Kotlin may import and call the
 * KiteFFmpeg-owned `ffkmp_`, `kc_` and `KC_` surface, but it may neither import a raw FFmpeg name,
 * call libav directly nor name an FFmpeg struct type. The two ratcheted counts therefore mean:
 *
 *  1. `cinterop_import_lines`: direct FFmpeg imports from the `ffmpeg` cinterop package. Imports
 *     whose names begin `ffkmp_`, `kc_` or `KC_` are the owned boundary and are excluded.
 *  2. `ffmpeg_typed_crossings`: direct libav call sites only.
 *
 * `ffkmp_call_sites` remains reported-only boundary traffic and may grow. The direct-libav count
 * is printed separately as well as carrying the second ratchet name. FFmpeg struct candidates are
 * derived from the C declarations, including the private forward tags in handles.h, and every
 * candidate is forbidden in Kotlin source rather than allowlisted.
 *
 * All matching happens over a code-only Kotlin view. A context-stack lexer blanks ordinary-string,
 * raw-string and character-literal content while preserving whitespace, newlines, live template
 * expressions and backtick identifiers. Nested templates and braces remain code; comments inside
 * `${...}` are removed. Diagnostic text therefore cannot look like coupling, while live code in a
 * template cannot hide from the ratchet.
 *
 * Lowering a baseline number is a normal commit. Raising one needs a reason in the commit body,
 * and the issue that justifies it named there.
 *
 * Two properties of this implementation are load bearing rather than incidental:
 *
 *  - It skips every directory named `build` or `.claude`. Both hold gitignored scratch checkouts
 *    of the same source files, and walking them would roughly double every count.
 *  - It is configuration cache safe. Nothing but a directory and a file is captured, no script
 *    object is referenced from the task action, and no process is started at configuration time.
 */
abstract class CheckCinteropCouplingTask : DefaultTask() {

    /** The module source root to measure, `kiteffmpeg/src`. Also holds `ffmpeg.def`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    /**
     * `native/kitecodec-c/coupling-baseline.txt`: `name value` lines for the two zero ceilings.
     * `#` starts a comment.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFile: RegularFileProperty

    /**
     * The C of the FFmpeg helper layer: the headers under `native/kitecodec-c/include` and the
     * sources under `native/kitecodec-c/src`. It supplies the FFmpeg struct type names that the
     * def body used to supply before B1.3 moved the C.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cDeclarationFiles: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val root = sourceDir.get().asFile
        val baseline = baselineFile.get().asFile

        val recorded = parseBaseline(baseline)
        val measured = measure(root, cDeclarationFiles.files)

        val failures = mutableListOf<String>()
        for (name in RATCHETED_NAMES) {
            val actual = measured.counts.getValue(name)
            val ceiling = recorded.counts.getValue(name)
            if (actual > ceiling) failures += "$name: baseline $ceiling, actual $actual"
        }
        if (measured.namedStructTypes.isNotEmpty()) {
            failures +=
                "raw FFmpeg struct type(s) named in Kotlin: " +
                measured.namedStructTypes.sorted().joinToString()
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("The FFmpeg coupling ratchet failed against ${baseline.path}.")
                    for (line in failures) appendLine("  $line")
                    appendLine()
                    appendLine(
                        "Kotlin may cross only the KiteFFmpeg-owned ffkmp_/kc_/KC_ boundary. " +
                            "Remove the raw import, call or type. If a numeric ceiling must move " +
                            "deliberately, update it in the same commit and say why in the " +
                            "commit body.",
                    )
                },
            )
        }

        for (name in RATCHETED_NAMES) {
            logger.lifecycle("$name: ${measured.counts.getValue(name)} (ceiling ${recorded.counts.getValue(name)})")
        }
        for (name in REPORTED_NAMES) {
            logger.lifecycle("$name: ${measured.counts.getValue(name)} (reported only)")
        }
        logger.lifecycle("raw FFmpeg struct types named in Kotlin: ${measured.namedStructTypes.size}")
    }

    /** What [measure] returns: the four counts plus the struct type names Kotlin actually names. */
    data class Measurement(val counts: Map<String, Int>, val namedStructTypes: Set<String>)

    /** What [parseBaseline] returns: the two zero ceilings. */
    data class Baseline(val counts: Map<String, Int>)

    companion object {

        const val CINTEROP_IMPORT_LINES: String = "cinterop_import_lines"
        const val FFMPEG_TYPED_CROSSINGS: String = "ffmpeg_typed_crossings"
        const val FFKMP_CALL_SITES: String = "ffkmp_call_sites"
        const val DIRECT_LIBAV_CALL_SITES: String = "direct_libav_call_sites"
        /** The ratcheted counts, in the order the baseline file lists them. */
        val RATCHETED_NAMES: List<String> = listOf(CINTEROP_IMPORT_LINES, FFMPEG_TYPED_CROSSINGS)

        /** The reported-only view of opaque helper traffic and the direct-call count. */
        val REPORTED_NAMES: List<String> = listOf(FFKMP_CALL_SITES, DIRECT_LIBAV_CALL_SITES)

        /** `ffmpeg.def`, relative to the measured source root. */
        const val DEF_PATH: String = "nativeInterop/cinterop/ffmpeg.def"

        /**
         * Directory names never walked. Both hold gitignored scratch checkouts of the same files:
         * `build` the Gradle outputs, `.claude` the agent worktrees.
         */
        private val SKIPPED_DIRECTORY_NAMES = setOf("build", ".claude")

        /**
         * A direct FFmpeg import out of the `ffmpeg` cinterop package. The negative lookahead
         * excludes all three spellings of the KiteFFmpeg-owned opaque boundary.
         */
        private val IMPORT_DECLARATION = Regex("""^import[ \t]+""")

        private val CINTEROP_IMPORT = Regex("""^import[ \t]+ffmpeg\.(?!ffkmp_|kc_|KC_)""")

        private val HELPER_MENTION = Regex("""ffkmp_[A-Za-z0-9_]*""")

        /**
         * A call straight into libav. Longest prefix first so the alternation reads in the order
         * it resolves, although the engine would backtrack into the right branch either way.
         */
        private val LIBAV_CALL = Regex(
            """(?<![A-Za-z0-9_])`?(?:avcodec|avdevice|avfilter|avformat|avutil|avio|""" +
                """swresample|swscale|postproc|sws|swr|av)_[A-Za-z0-9_]*`?[ \t]*\(""",
        )

        /**
         * An FFmpeg CamelCase type token in C, with the `enum` keyword in front of it when it is
         * there. Group 1 empty means this occurrence proves the token is a struct type.
         */
        private val DEF_TYPE_TOKEN =
            Regex("""(enum\s+)?\b((?:AV|Sws|Swr)[A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*)\b""")

        /**
         * Removes Kotlin line comments, KDoc and nested block comments. Quoted content is kept:
         * `//`, `/*` and `*/` inside ordinary strings, raw strings or character literals are not
         * comments. Unescaped `${...}` in ordinary and raw strings re-enters code mode, so comments
         * inside template expressions are stripped. Newlines survive for readable diagnostics.
         */
        fun stripComments(text: String): String = lexKotlin(text, blankQuotedContent = false)

        /**
         * Returns only executable Kotlin text. String and character content is replaced with
         * whitespace, while live template expressions and backtick identifiers remain code.
         */
        fun codeOnly(text: String): String = lexKotlin(text, blankQuotedContent = true)

        private fun lexKotlin(text: String, blankQuotedContent: Boolean): String {
            val out = StringBuilder(text.length)
            val stack = mutableListOf(LexerFrame(LexerMode.CODE))

            fun appendBlanked(c: Char) {
                out.append(if (c.isWhitespace()) c else ' ')
            }

            fun appendQuoted(c: Char) {
                if (blankQuotedContent) appendBlanked(c) else out.append(c)
            }

            fun appendQuotedRange(start: Int, count: Int) {
                repeat(count) { offset -> appendQuoted(text[start + offset]) }
            }

            fun appendBlankedRange(start: Int, count: Int) {
                repeat(count) { offset -> appendBlanked(text[start + offset]) }
            }

            fun isIdentifierStart(c: Char): Boolean = c == '_' || c.isLetter()

            fun isIdentifierPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                val frame = stack.last()
                when (frame.mode) {
                    LexerMode.RAW_STRING -> {
                        if (c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                            appendQuotedRange(i, 3)
                            i += 3
                            stack.removeAt(stack.lastIndex)
                        } else if (c == '$' && i + 1 < n && text[i + 1] == '{') {
                            out.append("${'$'}{")
                            i += 2
                            stack += LexerFrame(LexerMode.CODE, templateBraceDepth = 1)
                        } else if (c == '$' && i + 1 < n && isIdentifierStart(text[i + 1])) {
                            out.append(c)
                            i++
                            while (i < n && isIdentifierPart(text[i])) {
                                out.append(text[i])
                                i++
                            }
                        } else if (c == '$' && i + 1 < n && text[i + 1] == '`') {
                            out.append("${'$'}`")
                            i += 2
                            stack += LexerFrame(LexerMode.BACKTICK_ID)
                        } else {
                            appendQuoted(c)
                            i++
                        }
                    }
                    LexerMode.STRING -> {
                        if (c == '\\' && i + 1 < n) {
                            appendQuotedRange(i, 2)
                            i += 2
                        } else if (c == '$' && i + 1 < n && text[i + 1] == '{') {
                            out.append("${'$'}{")
                            i += 2
                            stack += LexerFrame(LexerMode.CODE, templateBraceDepth = 1)
                        } else if (c == '$' && i + 1 < n && isIdentifierStart(text[i + 1])) {
                            out.append(c)
                            i++
                            while (i < n && isIdentifierPart(text[i])) {
                                out.append(text[i])
                                i++
                            }
                        } else if (c == '$' && i + 1 < n && text[i + 1] == '`') {
                            out.append("${'$'}`")
                            i += 2
                            stack += LexerFrame(LexerMode.BACKTICK_ID)
                        } else {
                            appendQuoted(c)
                            if (c == '"') stack.removeAt(stack.lastIndex)
                            i++
                        }
                    }
                    LexerMode.CHAR -> {
                        if (c == '\\' && i + 1 < n) {
                            appendQuotedRange(i, 2)
                            i += 2
                        } else {
                            appendQuoted(c)
                            if (c == '\'') stack.removeAt(stack.lastIndex)
                            i++
                        }
                    }
                    LexerMode.BACKTICK_ID -> {
                        out.append(c)
                        i++
                        if (c == '`') stack.removeAt(stack.lastIndex)
                    }
                    LexerMode.CODE -> when {
                        c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                            while (i < n && text[i] != '\n') {
                                appendBlanked(text[i])
                                i++
                            }
                        }
                        c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                            appendBlankedRange(i, 2)
                            i += 2
                            var blockDepth = 1
                            while (i < n && blockDepth > 0) {
                                when {
                                    text[i] == '/' && i + 1 < n && text[i + 1] == '*' -> {
                                        appendBlankedRange(i, 2)
                                        blockDepth++
                                        i += 2
                                    }
                                    text[i] == '*' && i + 1 < n && text[i + 1] == '/' -> {
                                        appendBlankedRange(i, 2)
                                        blockDepth--
                                        i += 2
                                    }
                                    else -> {
                                        appendBlanked(text[i])
                                        i++
                                    }
                                }
                            }
                        }
                        c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"' -> {
                            appendQuotedRange(i, 3)
                            i += 3
                            stack += LexerFrame(LexerMode.RAW_STRING)
                        }
                        c == '"' -> {
                            appendQuoted(c)
                            i++
                            stack += LexerFrame(LexerMode.STRING)
                        }
                        c == '\'' -> {
                            appendQuoted(c)
                            i++
                            stack += LexerFrame(LexerMode.CHAR)
                        }
                        c == '`' -> {
                            out.append(c)
                            i++
                            stack += LexerFrame(LexerMode.BACKTICK_ID)
                        }
                        frame.templateBraceDepth > 0 && c == '{' -> {
                            frame.templateBraceDepth++
                            out.append(c)
                            i++
                        }
                        frame.templateBraceDepth > 0 && c == '}' -> {
                            frame.templateBraceDepth--
                            out.append(c)
                            i++
                            if (frame.templateBraceDepth == 0) stack.removeAt(stack.lastIndex)
                        }
                        else -> {
                            out.append(c)
                            i++
                        }
                    }
                }
            }
            return out.toString()
        }

        private enum class LexerMode { CODE, STRING, RAW_STRING, CHAR, BACKTICK_ID }

        private data class LexerFrame(
            val mode: LexerMode,
            var templateBraceDepth: Int = 0,
        )

        /**
         * Recomputes the counts over [sourceDir]. [cDeclarationFiles] are the C headers and
         * sources, which together with the def supply the candidate struct type names.
         */
        fun measure(sourceDir: File, cDeclarationFiles: Collection<File> = emptyList()): Measurement {
            if (!sourceDir.isDirectory) {
                throw GradleException("Cannot measure the FFmpeg coupling: ${sourceDir.path} is not a directory.")
            }
            val def = sourceDir.resolve(DEF_PATH)
            if (!def.isFile) {
                throw GradleException("Cannot measure the FFmpeg coupling: no cinterop def at ${def.path}.")
            }

            val kotlinTexts = sourceDir.walkTopDown()
                .onEnter { it.name !in SKIPPED_DIRECTORY_NAMES }
                .filter { it.isFile && it.extension == "kt" }
                .map { codeOnly(it.readText()) }
                .toList()

            var importLines = 0
            var helperCalls = 0
            var libavCalls = 0
            for (text in kotlinTexts) {
                for (line in text.lineSequence()) {
                    val normalizedLine = line.trimStart()
                    // An import is a declaration of coupling, not a use of it: counted by count 1
                    // and excluded from the crossings so one import does not read as a call.
                    if (IMPORT_DECLARATION.containsMatchIn(normalizedLine)) {
                        if (CINTEROP_IMPORT.containsMatchIn(normalizedLine)) importLines++
                        continue
                    }
                    helperCalls += HELPER_MENTION.findAll(line).count()
                    libavCalls += LIBAV_CALL.findAll(line).count()
                }
            }

            val candidates = ffmpegStructTypeNames(listOf(def) + cDeclarationFiles.filter { it.isFile })
            val named = candidates.filterTo(linkedSetOf()) { name ->
                val wholeWord = Regex("""\b""" + Regex.escape(name) + """\b""")
                kotlinTexts.any { wholeWord.containsMatchIn(it) }
            }

            return Measurement(
                counts = mapOf(
                    CINTEROP_IMPORT_LINES to importLines,
                    FFMPEG_TYPED_CROSSINGS to libavCalls,
                    FFKMP_CALL_SITES to helperCalls,
                    DIRECT_LIBAV_CALL_SITES to libavCalls,
                ),
                namedStructTypes = named,
            )
        }

        /**
         * The FFmpeg struct type names named across [files]: every CamelCase `AV`, `Sws` or `Swr`
         * token that occurs at least once without `enum` in front of it.
         */
        fun ffmpegStructTypeNames(files: Collection<File>): Set<String> {
            val names = linkedSetOf<String>()
            for (file in files) {
                for (match in DEF_TYPE_TOKEN.findAll(file.readText())) {
                    if (match.groupValues[1].isEmpty()) names += match.groupValues[2]
                }
            }
            return names
        }

        /**
         * Reads `name value` lines for the ratcheted counts, ignoring blank lines and everything
         * from a `#` onwards.
         */
        fun parseBaseline(baselineFile: File): Baseline {
            val recorded = LinkedHashMap<String, Int>()
            baselineFile.readLines().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("""\s+"""))
                if (parts.size != 2) {
                    throw GradleException(
                        "${baselineFile.path}:${index + 1}: expected `name value`, found `$raw`.",
                    )
                }
                if (parts[0] !in RATCHETED_NAMES || parts[1].toIntOrNull() == null) {
                    throw GradleException(
                        "${baselineFile.path}:${index + 1}: unknown line `$raw`. The ratcheted " +
                            "counts are ${RATCHETED_NAMES.joinToString()}.",
                    )
                }
                recorded[parts[0]] = parts[1].toInt()
            }
            val missing = RATCHETED_NAMES - recorded.keys
            if (missing.isNotEmpty()) {
                throw GradleException("${baselineFile.path}: no baseline for ${missing.joinToString()}.")
            }
            return Baseline(recorded)
        }
    }
}
