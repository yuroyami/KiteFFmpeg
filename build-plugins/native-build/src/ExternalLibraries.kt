package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import java.io.Serializable
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/**
 * The libraries outside FFmpeg's own source that its configure enabled for one tree, as the
 * configure banner names them.
 *
 * [libraries] is the banner's "External libraries" section, for example `libdav1d` and `zlib`.
 * [hardware] is its "External libraries providing hardware acceleration" section, for example
 * `videotoolbox`. Every profile turns off configure's autodetect, so both sets follow from the
 * recipe alone. The bake fails when configure enables another set, for example after a sysroot or
 * NDK update (#223).
 */
data class ExternalLibraries(val libraries: List<String>, val hardware: List<String>) : Serializable {

    /** The record that the bake installs at [RECORD_RELATIVE_PATH], and that BUILD-INFO.txt copies. */
    fun record(): String = buildString {
        appendLine("# The libraries outside FFmpeg that its configure enabled, as its banner names them.")
        appendLine("$LIBRARIES_FIELD: ${libraries.spelled()}")
        appendLine("$HARDWARE_FIELD: ${hardware.spelled()}")
    }

    /** Why [found] differs from this expected set, or null when it does not. */
    fun mismatch(found: ExternalLibraries): String? =
        if (found == this) {
            null
        } else {
            "configure enabled other libraries than this checkout expects\n" +
                "    expected: ${describe()}\n" +
                "    found:    ${found.describe()}"
        }

    private fun describe(): String = "${libraries.spelled()} | ${hardware.spelled()}"

    companion object {
        private const val serialVersionUID: Long = 1L

        const val RECORD_RELATIVE_PATH = "lib/kiteffmpeg/ffmpeg-external-libraries.txt"
        const val LIBRARIES_FIELD = "External libraries"
        const val HARDWARE_FIELD = "Hardware acceleration libraries"
        private const val NONE = "(none)"

        /**
         * What configure must enable for each target: the external libraries, a `|`, then the
         * hardware acceleration libraries. `scripts/native-sbom.sh` reads these lines, so keep one
         * entry on each line, in this shape.
         */
        val EXPECTED: Map<TargetTriple, ExternalLibraries> = mapOf(
            TargetTriple.MacosArm64 to of("libdav1d zlib | audiotoolbox videotoolbox"),
            TargetTriple.MacosX64 to of("libdav1d zlib | audiotoolbox videotoolbox"),
            TargetTriple.IosArm64 to of("libdav1d zlib | audiotoolbox videotoolbox"),
            TargetTriple.IosSimulatorArm64 to of("libdav1d zlib | audiotoolbox videotoolbox"),
            TargetTriple.IosX64 to of("libdav1d zlib | audiotoolbox videotoolbox"),
            TargetTriple.LinuxX64 to of("iconv libdav1d zlib | v4l2_m2m"),
            TargetTriple.LinuxArm64 to of("iconv libdav1d zlib | v4l2_m2m"),
            TargetTriple.MingwX64 to of("iconv libdav1d zlib | d3d11va dxva2"),
            TargetTriple.AndroidArm64 to of("jni libdav1d mediacodec zlib |"),
            TargetTriple.AndroidArm32 to of("jni libdav1d mediacodec zlib |"),
            TargetTriple.AndroidX64 to of("jni libdav1d mediacodec zlib |"),
        )

        /** Reads a spec of [EXPECTED]: names separated by spaces, with one `|` between the two sets. */
        fun of(spec: String): ExternalLibraries {
            val parts = spec.split('|')
            require(parts.size == 2) { "Expected one '|' in the library spec '$spec'." }
            return ExternalLibraries(parts[0].names(), parts[1].names())
        }

        /**
         * Reads the two sections out of configure's output [lines], or null when one is missing.
         * Each section is a heading line, then the names in columns, then a blank line.
         */
        fun fromBanner(lines: List<String>): ExternalLibraries? {
            fun section(heading: String): List<String>? {
                val start = lines.indexOfFirst { it.trim() == heading }
                if (start < 0) return null
                return lines.drop(start + 1).takeWhile { it.isNotBlank() }.joinToString(" ").names()
            }
            val libraries = section("External libraries:") ?: return null
            val hardware = section("External libraries providing hardware acceleration:") ?: return null
            return ExternalLibraries(libraries, hardware)
        }

        /** Reads a record that [record] wrote, or null when [text] is not one. */
        fun fromRecord(text: String): ExternalLibraries? {
            val lines = text.lines().map { it.trimEnd('\r') }
            fun field(name: String): String? =
                lines.singleOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()
            val libraries = field(LIBRARIES_FIELD) ?: return null
            val hardware = field(HARDWARE_FIELD) ?: return null
            return ExternalLibraries(libraries.names(), hardware.names())
        }

        /** Installs the record of [found] into the tree at [install]. */
        fun writeRecord(found: ExternalLibraries, install: Path) {
            val record = install.resolve(RECORD_RELATIVE_PATH)
            Files.createDirectories(record.parent)
            Files.writeString(record, found.record(), UTF_8)
        }

        /** Why the tree at [tree] does not record the libraries of [expected], or null when it does. */
        fun staleReason(tree: File, expected: ExternalLibraries): String? {
            val record = tree.resolve(RECORD_RELATIVE_PATH)
            if (!record.isFile) {
                return "it has no $RECORD_RELATIVE_PATH, so it was baked while configure still looked " +
                    "for libraries by itself"
            }
            val found = fromRecord(record.readText()) ?: return "its $RECORD_RELATIVE_PATH cannot be read"
            return expected.mismatch(found)
        }

        private fun String.names(): List<String> =
            trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it != NONE }.sorted()

        private fun List<String>.spelled(): String = if (isEmpty()) NONE else joinToString(" ")
    }
}
