package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Links the codec module the web backend loads: `kite.mjs` and `kite.wasm`, from the C helper
 * layer compiled for the web and the web FFmpeg libraries. `KiteFFmpegWeb.load()` fetches
 * `./kite.mjs` by default, so a page serves both files beside itself.
 *
 * The module exports every C helper the generated web binding calls, the hand-written open that
 * takes a byte source, and the runtime pieces `KiteFFmpegWeb.attach` checks for. Needs `emcc` on
 * PATH when it runs.
 */
abstract class LinkKiteFFmpegWasmModuleTask : DefaultTask() {

    /** `libkitecodec.a` from `compileKiteFFmpegCForWasm`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val helperArchive: RegularFileProperty

    /** The web FFmpeg tree's `lib` directory. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ffmpegLibDir: DirectoryProperty

    /** `native/kitecodec-c/signature-baseline.txt`, which names every helper to export. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val signatureBaseline: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun link() {
        val emcc = locate("emcc")
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val exportsFile = temporaryDir.resolve("exports.json")
        exportsFile.writeText(exports(signatureBaseline.get().asFile.readText()).joinToString(",", "[", "]") { "\"$it\"" })
        val libDir = ffmpegLibDir.get().asFile
        val command = command(emcc, helperArchive.get().asFile, libDir, exportsFile, out.resolve(MODULE_FILE))
        logger.lifecycle("[KiteFFmpeg wasm] linking ${out.resolve(MODULE_FILE)} with $emcc")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("emcc failed linking the codec module:\n$output")
        if (!out.resolve(WASM_FILE).isFile) throw GradleException("emcc wrote no $WASM_FILE into $out")
    }

    private fun locate(tool: String): String {
        val process = ProcessBuilder("sh", "-c", "command -v $tool").redirectErrorStream(true).start()
        val path = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0 || path.isEmpty()) {
            throw GradleException("$tool is not on PATH. Install emscripten (brew install emscripten) to link the web codec module.")
        }
        return path
    }

    companion object {
        const val MODULE_FILE = "kite.mjs"
        const val WASM_FILE = "kite.wasm"

        /** What the web backend calls besides the generated binding. */
        val EXTRA_EXPORTS = listOf("_ffkmp_fmt_open_input_io", "_malloc", "_free")

        /** The runtime pieces the backend reads; KiteFFmpegWeb.attach refuses a module without them. */
        val RUNTIME_METHODS = listOf(
            "ccall", "cwrap", "UTF8ToString", "stringToUTF8", "lengthBytesUTF8",
            "addFunction", "removeFunction", "HEAP32", "HEAPU8", "HEAPU32",
        )

        /** The FFmpeg libraries, in the order the linker resolves them. */
        val FFMPEG_LIBS = listOf("avfilter", "avformat", "avcodec", "swscale", "swresample", "avutil")

        /** Every exported C name: the generated binding's helpers plus [EXTRA_EXPORTS]. */
        fun exports(signatureBaseline: String): List<String> =
            GenerateWasmBindingTask.parse(signatureBaseline)
                .filterNot { it.name in GenerateWasmBindingTask.HAND_WRITTEN }
                .map { "_${it.name}" } + EXTRA_EXPORTS

        fun command(emcc: String, helperArchive: File, ffmpegLibDir: File, exportsFile: File, output: File): List<String> =
            listOf(emcc, "-O3", helperArchive.path) +
                FFMPEG_LIBS.map { ffmpegLibDir.resolve("lib$it.a").path } +
                listOf(
                    "-sEXPORTED_FUNCTIONS=@${exportsFile.path}",
                    "-sEXPORTED_RUNTIME_METHODS=${RUNTIME_METHODS.joinToString(",", "[", "]") { "\"$it\"" }}",
                    // addFunction needs a table that can grow; 64-bit timestamps cross as BigInt.
                    "-sALLOW_TABLE_GROWTH=1",
                    "-sWASM_BIGINT=1",
                    "-sALLOW_MEMORY_GROWTH=1",
                    "-sMODULARIZE=1",
                    "-sEXPORT_ES6=1",
                    "-o", output.path,
                )
    }
}
