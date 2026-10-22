package io.github.yuroyami.kiteffmpeg.plugin

import io.github.yuroyami.kiteffmpeg.buildtools.BuildFailure
import io.github.yuroyami.kiteffmpeg.buildtools.BuildLog
import io.github.yuroyami.kiteffmpeg.buildtools.GenerateWasmBindingTask
import io.github.yuroyami.kiteffmpeg.buildtools.LinkKiteFFmpegAsyncWasmModulesTask
import io.github.yuroyami.kiteffmpeg.buildtools.captureOutput
import io.github.yuroyami.kiteffmpeg.buildtools.compileWebHelper
import io.github.yuroyami.kiteffmpeg.buildtools.kotlinToolchainCache
import io.github.yuroyami.kiteffmpeg.buildtools.linkAsyncWebModules
import io.github.yuroyami.kiteffmpeg.buildtools.linkWebModule
import io.github.yuroyami.kiteffmpeg.buildtools.webPackageEntries
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

/** Writes the web binding (the emcc export list and the Kotlin externals) from the signature baseline. */
@TaskAction
fun generateWasmBinding(@Input signatureBaseline: Path, @Output outputDir: Path) {
    GenerateWasmBindingTask().apply {
        this.signatureBaseline.set(signatureBaseline.toFile())
        this.outputDir.set(outputDir.toFile())
    }.generate()
}

/** Compiles the C helper layer for the web, against the headers of the web FFmpeg tree. */
@TaskAction
fun compileWasmHelper(
    @Input cSources: Path,
    @Input cInclude: Path,
    @Input handleSources: Path,
    @Input ffmpegTree: Path,
    @Output outputDir: Path,
) {
    compileWebHelper(cSources.toFile(), cInclude.toFile(), handleSources.toFile(), ffmpegTree.toFile(), outputDir.toFile())
}

/** Links `kite.mjs` and `kite.wasm`, the codec module that the web backend loads. */
@TaskAction
fun linkWasmModule(
    @Input helperDir: Path,
    @Input ffmpegTree: Path,
    @Input signatureBaseline: Path,
    @Output outputDir: Path,
) {
    linkWebModule(helperDir.toFile(), ffmpegTree.toFile(), signatureBaseline.toFile(), outputDir.toFile())
}

/** Links `kite-jspi` and `kite-asyncify`, the codec modules whose byte sources may answer later. */
@TaskAction
fun linkAsyncWasmModules(
    @Input helperDir: Path,
    @Input ffmpegTree: Path,
    @Input signatureBaseline: Path,
    @Input bridgeSource: Path,
    @Input importsLibrary: Path,
    @Input cInclude: Path,
    @Input handleSources: Path,
    @Output outputDir: Path,
) {
    linkAsyncWebModules(
        helperDir.toFile(), ffmpegTree.toFile(), signatureBaseline.toFile(), bridgeSource.toFile(),
        importsLibrary.toFile(), cInclude.toFile(), handleSources.toFile(), outputDir.toFile(),
    )
}

/**
 * Drives the real FFmpeg in each asynchronous module from Node: every byte source call answers from a
 * timer, a live playlist stops growing, and the event loop must run while it waits.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkAsyncWasmModules(@Input modulesDir: Path, @Input testsDir: Path) {
    val node = findNode()
    val script = testsDir.resolve("check_async_module.mjs").toFile()
    for (strategy in LinkKiteFFmpegAsyncWasmModulesTask.Strategy.entries) {
        val module = modulesDir.resolve(strategy.moduleFile).toFile()
        val process = ProcessBuilder(node, script.absolutePath, module.absolutePath, strategy.name.lowercase())
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw BuildFailure("The check of ${strategy.moduleFile} failed:\n$output")
        BuildLog.lifecycle(output.trim())
    }
}

/**
 * Zips a web codec module with its build record and licence texts, for a web app to unpack beside its page.
 * The zip in [zipDir] holds the files of the kiteffmpeg-web (or kiteffmpeg-web-async) jar, and keeps the name of
 * the zips of the releases up to 0.5.1: `kiteffmpeg-wasm-js-<version>-<classifier>.zip`.
 */
@TaskAction
fun zipWebModule(
    version: String?,
    classifier: String,
    @Input moduleDir: Path,
    @Input(inferTaskDependency = false) ffmpegTree: Path,
    @Input licenseFiles: List<Path>,
    @Output zipDir: Path,
) {
    val entries = webPackageEntries(moduleDir.toFile(), ffmpegTree.toFile(), licenseFiles.map(Path::toFile))
    if (entries.none { !it.first.contains('/') }) throw BuildFailure("No web module in $moduleDir.")
    val dir = zipDir.toFile().apply { deleteRecursively(); mkdirs() }
    val out = dir.resolve("kiteffmpeg-wasm-js-${version ?: throw BuildFailure("The module has no publishing version.")}-$classifier.zip")
    ZipOutputStream(out.outputStream().buffered()).use { zip ->
        entries.forEach { (name, file) ->
            zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
    BuildLog.lifecycle("[KiteFFmpeg wasm] wrote $out (${entries.size} files)")
}

/** `node` from PATH, or the Node that the Kotlin Toolchain downloaded. */
private fun findNode(): String {
    runCatching { captureOutput(listOf("sh", "-c", "command -v node || true")).trim() }.getOrNull()
        ?.takeIf { it.isNotEmpty() }?.let { return it }
    return kotlinToolchainCache().resolve("extract.cache").listFiles().orEmpty()
        .filter { it.isDirectory && "-node-v" in it.name }
        .sortedByDescending { it.name }
        .flatMap { root -> root.walkTopDown().maxDepth(3).filter { it.name == "node" && it.parentFile.name == "bin" }.toList() }
        .firstOrNull()?.absolutePath
        ?: throw BuildFailure("node is not on PATH, and the asynchronous codec modules are checked with it.")
}
