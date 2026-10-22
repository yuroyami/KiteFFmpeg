package io.github.yuroyami.kiteffmpeg.webpackage

import io.github.yuroyami.kiteffmpeg.buildtools.BuildFailure
import io.github.yuroyami.kiteffmpeg.buildtools.BuildLog
import io.github.yuroyami.kiteffmpeg.buildtools.LinkKiteFFmpegAsyncWasmModulesTask
import io.github.yuroyami.kiteffmpeg.buildtools.LinkKiteFFmpegWasmModuleTask
import io.github.yuroyami.kiteffmpeg.buildtools.WEB_BUILD_INFO_FILES
import io.github.yuroyami.kiteffmpeg.buildtools.compileWebHelper
import io.github.yuroyami.kiteffmpeg.buildtools.linkAsyncWebModules
import io.github.yuroyami.kiteffmpeg.buildtools.linkWebModule
import io.github.yuroyami.kiteffmpeg.buildtools.webPackageEntries
import java.nio.file.Path
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

/**
 * Links the web codec modules of [variant] with emscripten, and puts them in [resourcesDir] with the build
 * record of the web FFmpeg tree and the licence texts. The files of [resourcesDir] are the root of the jar.
 *
 * Without a web FFmpeg tree the jar stays empty and a message says so. `./kotlin check webPackage` fails
 * while a jar is empty, so a release cannot ship one.
 */
@TaskAction
fun packageWebModules(
    variant: String,
    @Input cSources: Path,
    @Input cInclude: Path,
    @Input handleSources: Path,
    @Input signatureBaseline: Path,
    @Input bridgeSource: Path,
    @Input importsLibrary: Path,
    @Input(inferTaskDependency = false) ffmpegTree: Path,
    @Input licenseFiles: List<Path>,
    @Output workDir: Path,
    @Output resourcesDir: Path,
) {
    val async = isAsync(variant)
    val resources = resourcesDir.toFile().apply { deleteRecursively(); mkdirs() }
    val tree = ffmpegTree.toFile()
    if (!tree.resolve("lib").isDirectory) {
        BuildLog.lifecycle(
            "[KiteFFmpeg wasm] kiteffmpeg-$variant carries no codec module: no web FFmpeg tree at $tree " +
                "(run `./kotlin do buildFFmpegForWasm`).",
        )
        return
    }
    val work = workDir.toFile()
    val helper = work.resolve("helper")
    val modules = work.resolve("modules")
    compileWebHelper(cSources.toFile(), cInclude.toFile(), handleSources.toFile(), tree, helper)
    if (async) {
        linkAsyncWebModules(
            helper, tree, signatureBaseline.toFile(), bridgeSource.toFile(), importsLibrary.toFile(),
            cInclude.toFile(), handleSources.toFile(), modules,
        )
    } else {
        linkWebModule(helper, tree, signatureBaseline.toFile(), modules)
    }
    val entries = webPackageEntries(modules, tree, licenseFiles.map(Path::toFile))
    entries.forEach { (name, file) -> file.copyTo(resources.resolve(name), overwrite = true) }
    BuildLog.lifecycle("[KiteFFmpeg wasm] staged ${entries.size} files of kiteffmpeg-$variant in $resources")
}

/** Fails unless the jar of [variant] carries its codec modules and the build record. Run it before a release. */
@TaskAction
fun checkWebPackage(variant: String, @Input resourcesDir: Path) {
    val expected = moduleFiles(variant) + WEB_BUILD_INFO_FILES.map { "build-info/$it" }
    val missing = expected.filterNot { resourcesDir.resolve(it).toFile().isFile }
    if (missing.isNotEmpty()) {
        throw BuildFailure(
            "kiteffmpeg-$variant carries no ${missing.joinToString()}. " +
                "Build the web FFmpeg tree first (`./kotlin do buildFFmpegForWasm`).",
        )
    }
    println("kiteffmpeg-$variant carries ${expected.joinToString()}.")
}

private fun isAsync(variant: String): Boolean = when (variant) {
    "web" -> false
    "web-async" -> true
    else -> throw BuildFailure("Unknown web-package variant '$variant'. Use web or web-async.")
}

private fun moduleFiles(variant: String): List<String> =
    if (isAsync(variant)) {
        LinkKiteFFmpegAsyncWasmModulesTask.Strategy.entries.flatMap { listOf(it.moduleFile, it.wasmFile) }
    } else {
        listOf(LinkKiteFFmpegWasmModuleTask.MODULE_FILE, LinkKiteFFmpegWasmModuleTask.WASM_FILE)
    }
