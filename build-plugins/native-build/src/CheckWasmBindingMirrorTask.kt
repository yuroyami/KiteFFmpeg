package io.github.yuroyami.kiteffmpeg.buildtools


/**
 * Holds the COMPILED wasm binding equal to the one the generator would write.
 *
 * [GenerateWasmBindingTask] writes `KiteFFmpegWasm.kt` into `native-libs/`, which is gitignored, so
 * the file that actually COMPILES is a committed copy under `wasmJsMain`. Two copies with nothing
 * comparing them is a drift that surfaces at runtime in a browser, which is the most expensive place
 * this project has to find anything.
 *
 * This regenerates the expected text in memory from `signature-baseline.txt`, the same gated input
 * the generator reads, and compares. It never writes: a check that repairs what it checks teaches
 * nobody anything, and the repair is one command away.
 */
open class CheckWasmBindingMirrorTask : BuildStep() {

    /** `native/kitecodec-c/signature-baseline.txt`, the gated C surface both copies derive from. */
    val signatureBaseline = RegularFileProperty()

    /** The committed copy that the wasmJs compilation actually reads. */
    val mirrorFile = RegularFileProperty()

    fun check() {
        val baseline = signatureBaseline.get().asFile
        val mirror = mirrorFile.get().asFile

        val declarations = GenerateWasmBindingTask.parse(baseline.readText())
        if (declarations.isEmpty()) {
            throw BuildFailure("no KC_API declarations parsed from ${baseline.path}")
        }
        val exported = declarations.filterNot { it.name in GenerateWasmBindingTask.HAND_WRITTEN }
        val expected = GenerateWasmBindingTask.kotlinBinding(exported)
        val actual = mirror.readText()
        if (expected == actual) {
            logger.lifecycle("[KiteFFmpeg wasm] binding mirror matches the generator: ${exported.size} externals")
            return
        }

        throw BuildFailure(buildString {
            appendLine("The committed wasm binding has drifted from the generator.")
            appendLine()
            appendLine("  committed: ${mirror.path}")
            appendLine("  generated: from ${baseline.path}")
            appendLine(firstDifference(expected, actual))
            appendLine()
            appendLine("Regenerate and copy it back:")
            appendLine("  ./kotlin do generateWasmBinding")
            appendLine("  cp native-libs/deps/wasm32/binding/${GenerateWasmBindingTask.KOTLIN_FILE} ${mirror.path}")
        })
    }

    private companion object {
        /** The first line that differs, with both sides, because a 607-line diff is not a message. */
        fun firstDifference(expected: String, actual: String): String {
            val e = expected.lines()
            val a = actual.lines()
            val index = (0 until maxOf(e.size, a.size)).firstOrNull { e.getOrNull(it) != a.getOrNull(it) }
                ?: return "  the files differ only in trailing content (${e.size} vs ${a.size} lines)"
            return buildString {
                appendLine("  first difference at line ${index + 1} (${e.size} generated lines, ${a.size} committed):")
                appendLine("    generated: ${e.getOrNull(index) ?: "<end of file>"}")
                append("    committed: ${a.getOrNull(index) ?: "<end of file>"}")
            }
        }
    }
}
