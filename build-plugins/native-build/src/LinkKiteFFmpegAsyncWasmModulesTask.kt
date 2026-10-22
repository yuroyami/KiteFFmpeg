package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File

/**
 * Links the two asynchronous codec modules (#183): `kite-jspi` and `kite-asyncify`, each a `.mjs`
 * and a `.wasm`. They hold the same helpers and FFmpeg libraries as `kite`, plus the asynchronous
 * input bridge of `native/kitecodec-web`, whose imports may answer later while the C stack that
 * called them is parked.
 *
 * `kite-jspi` parks through JavaScript Promise Integration, which the engine must have.
 * `kite-asyncify` parks through code the linker adds, which runs on every engine and makes the
 * module larger. Both send FFmpeg's `av_usleep` to the bridge's timer import, so the live HLS
 * reader's wait does not hold the thread. Needs `emcc` on PATH when it runs.
 */
open class LinkKiteFFmpegAsyncWasmModulesTask : BuildStep() {

    /** `libkitecodec.a` from `compileKiteFFmpegCForWasm`. */
    val helperArchive = RegularFileProperty()

    /** The web FFmpeg tree's `lib` directory. */
    val ffmpegLibDir = DirectoryProperty()

    /** `native/kitecodec-c/signature-baseline.txt`, which names every helper to export. */
    val signatureBaseline = RegularFileProperty()

    /** `kite_async_bridge.c`. */
    val bridgeSource = RegularFileProperty()

    /** `kite_async_imports.js`, the library that marks the bridge's imports as suspending. */
    val importsLibrary = RegularFileProperty()

    /** The directories the bridge's includes resolve in: the helper headers and FFmpeg's. */
    val includeDirs = ConfigurableFileCollection()

    val outputDir = DirectoryProperty()

    fun link() {
        val emcc = locate("emcc")
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val bridgeObject = temporaryDir.resolve("kite_async_bridge.o")
        run(
            listOf(emcc) + CompileKiteFFmpegCWasmTask.COMPILER_FLAGS + includeDirs.elements.map { "-I${it.absolutePath}" } +
                listOf("-c", bridgeSource.get().asFile.absolutePath, "-o", bridgeObject.absolutePath),
            "compiling the asynchronous bridge",
        )
        val exportsFile = temporaryDir.resolve("exports.json")
        exportsFile.writeText(exports(signatureBaseline.get().asFile.readText()).joinToString(",", "[", "]") { "\"$it\"" })
        for (strategy in Strategy.entries) {
            val module = out.resolve(strategy.moduleFile)
            logger.lifecycle("[KiteFFmpeg wasm] linking $module with $emcc")
            run(
                command(
                    emcc, strategy, bridgeObject, helperArchive.get().asFile, ffmpegLibDir.get().asFile,
                    importsLibrary.get().asFile, exportsFile, module,
                ),
                "linking ${strategy.moduleFile}",
            )
            if (!out.resolve(strategy.wasmFile).isFile) throw BuildFailure("emcc wrote no ${strategy.wasmFile} into $out")
        }
    }

    private fun run(command: List<String>, what: String) {
        logger.info("[KiteFFmpeg wasm] " + command.joinToString(" "))
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw BuildFailure("emcc failed $what:\n$output")
    }

    private fun locate(tool: String): String {
        val process = ProcessBuilder("sh", "-c", "command -v $tool").redirectErrorStream(true).start()
        val path = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0 || path.isEmpty()) {
            throw BuildFailure("$tool is not on PATH. Install emscripten (brew install emscripten) to link the web codec modules.")
        }
        return path
    }

    /** How a module parks a C stack, and the files it is linked into. */
    enum class Strategy(val moduleFile: String, val wasmFile: String) {
        Jspi("kite-jspi.mjs", "kite-jspi.wasm"),
        Asyncify("kite-asyncify.mjs", "kite-asyncify.wasm"),
    }

    companion object {
        /** The bridge's own exports. */
        val BRIDGE_EXPORTS = listOf(
            "ffkmp_async_bridge_version", "ffkmp_async_open_input", "ffkmp_async_seek_file", "ffkmp_async_seek_micros",
        )

        /**
         * The exports that can reach a suspending import, so that can park: the open, the stream
         * discovery, a packet read, the seeks, pause and play, and the close, which closes the
         * nested sources. With JSPI each returns a Promise. Asyncify lets any export park, and
         * these are the ones its callers must await.
         */
        val PARKING_EXPORTS = listOf(
            "ffkmp_async_open_input", "ffkmp_async_seek_file", "ffkmp_async_seek_micros",
            "ffkmp_fmt_find_stream_info", "ffkmp_fmt_read_frame", "ffkmp_fmt_read_pause", "ffkmp_fmt_read_play",
            "ffkmp_fmt_close_input_io",
        )

        /** What the bridge's host reads besides the plain module's runtime pieces. */
        val RUNTIME_METHODS = LinkKiteFFmpegWasmModuleTask.RUNTIME_METHODS + "HEAP64"

        /**
         * The stack Asyncify saves a parked call into, in bytes. The open of an HLS stream parks
         * about forty frames deep, inside the reader of a segment inside the reader of the
         * playlist, and the default of 4096 bytes does not hold them.
         */
        const val ASYNCIFY_STACK_BYTES = 262144

        /** Every exported C name: the plain module's, and the bridge's. */
        fun exports(signatureBaseline: String): List<String> =
            LinkKiteFFmpegWasmModuleTask.exports(signatureBaseline) + BRIDGE_EXPORTS.map { "_$it" }

        fun command(
            emcc: String,
            strategy: Strategy,
            bridgeObject: File,
            helperArchive: File,
            ffmpegLibDir: File,
            importsLibrary: File,
            exportsFile: File,
            output: File,
        ): List<String> =
            listOf(emcc, "-O3", bridgeObject.path, helperArchive.path) +
                LinkKiteFFmpegWasmModuleTask.FFMPEG_LIBS.map { ffmpegLibDir.resolve("lib$it.a").path } +
                listOf(
                    "--js-library", importsLibrary.path,
                    "-Wl,--wrap=av_usleep",
                    "-sEXPORTED_FUNCTIONS=@${exportsFile.path}",
                    "-sEXPORTED_RUNTIME_METHODS=${RUNTIME_METHODS.joinToString(",", "[", "]") { "\"$it\"" }}",
                    "-sALLOW_TABLE_GROWTH=1",
                    "-sALLOW_MEMORY_GROWTH=1",
                    "-sMODULARIZE=1",
                    "-sEXPORT_ES6=1",
                ) +
                when (strategy) {
                    Strategy.Jspi -> listOf("-sJSPI=1", "-sJSPI_EXPORTS=${PARKING_EXPORTS.joinToString(",", "[", "]") { "\"$it\"" }}")
                    Strategy.Asyncify -> listOf("-sASYNCIFY=1", "-sASYNCIFY_STACK_SIZE=$ASYNCIFY_STACK_BYTES")
                } +
                listOf("-o", output.path)
    }
}
