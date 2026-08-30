package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.inject.Inject

/**
 * Compiles `native/kitecodec-c/src` to a wasm32 archive with emscripten (PLANNING.md).
 *
 * Separate from [CompileKiteFFmpegCTask] for the same reason [BuildFFmpegWasmTask] is separate from
 * its sibling: that task resolves a konan LLVM package, a konan sysroot and an Apple SDK, and
 * verifies the object's architecture with `file(1)`. None of those exist for wasm, and a branch in
 * the shared task would be dead for eleven targets and load-bearing for one.
 *
 * The sources are unchanged and are not permitted to change: `native/kitecodec-c` is portable C
 * with no platform call, which is the property that makes this task small. If a `#ifdef __EMSCRIPTEN__`
 * ever appears in that directory, this comment is the thing that was wrong.
 */
abstract class CompileKiteFFmpegCWasmTask @Inject constructor() : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDir: DirectoryProperty

    /**
     * `native/kitecodec-handles`, the generation-tagged handle table shared with the JNI adapter
     * (17.14 X-04). Its own directory rather than a copy, so a fix here reaches both bindings.
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val handlesDir: DirectoryProperty

    /** The wasm FFmpeg tree's `include`, produced by [BuildFFmpegWasmTask]. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ffmpegIncludeDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ffmpegVersionHeaders: ConfigurableFileCollection

    /** The same three provenance defines the native task passes; see its `defineArguments`. */
    @get:Input
    abstract val buildDefines: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val emcc = requireOnPath("emcc")
        val emar = requireOnPath("emar")
        val out = outputDir.get().asFile
        val sources = (
            sourceDir.get().asFile.listFiles().orEmpty().toList() +
                handlesDir.get().asFile.listFiles().orEmpty().toList()
            ).filter { it.isFile && it.extension == "c" }
            .sortedBy { it.name }
        if (sources.isEmpty()) {
            throw GradleException("No .c sources under ${sourceDir.get().asFile.absolutePath}.")
        }

        // Same reason as the native task: `emar crs` updates in place, so a stale object from an
        // earlier run would survive into the archive.
        val objectDir = out.resolve("obj")
        objectDir.deleteRecursively()
        objectDir.mkdirs()
        val archive = out.resolve(CompileKiteFFmpegCTask.ARCHIVE_NAME)
        archive.delete()

        val includeArgs = listOf(
            "-I${includeDir.get().asFile.absolutePath}",
            "-I${handlesDir.get().asFile.absolutePath}",
            "-I${ffmpegIncludeDir.get().asFile.absolutePath}",
        )
        val defineArgs = CompileKiteFFmpegCTask.defineArguments(buildDefines.get())

        logger.lifecycle("[KiteFFmpeg wasm] compiling ${sources.size} C source(s) with $emcc")
        val objects = sources.map { source ->
            val objectFile = objectDir.resolve("${source.nameWithoutExtension}.o")
            runCommand(
                listOf(emcc) + COMPILER_FLAGS + defineArgs + includeArgs +
                    listOf("-c", source.absolutePath, "-o", objectFile.absolutePath),
            )
            objectFile
        }
        runCommand(listOf(emar, "crs", archive.absolutePath) + objects.map { it.absolutePath })
        check(archive.isFile) { "emar reported success but produced no ${archive.name}" }
        logger.lifecycle("[KiteFFmpeg wasm] ${archive.name} is ${archive.length()} bytes")
    }

    private fun requireOnPath(tool: String): String {
        val proc = ProcessBuilder("which", tool).redirectErrorStream(true).start()
        val path = proc.inputStream.bufferedReader().readText().trim()
        check(proc.waitFor() == 0 && path.isNotEmpty()) {
            "$tool is not on PATH. Install emscripten (brew install emscripten) first."
        }
        return path
    }

    private fun runCommand(command: List<String>) {
        logger.info("[KiteFFmpeg wasm] " + command.joinToString(" "))
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        check(code == 0) {
            "Command exited with $code: ${command.joinToString(" ")}\n$output"
        }
    }

    companion object {
        /**
         * The native task's flags minus `-fPIC`, which emscripten warns is meaningless for wasm.
         *
         * `-Werror` is kept deliberately. It is what makes this task worth running at all: the
         * point is not that an archive appears, it is that portable C really is portable, and a
         * warning demoted to a note would hide exactly the assumption that breaks.
         */
        val COMPILER_FLAGS: List<String> = listOf(
            "-O2", "-std=c11",
            "-fvisibility=hidden",
            "-Wall", "-Wextra", "-Werror", "-Werror=vla",
        )
    }
}
