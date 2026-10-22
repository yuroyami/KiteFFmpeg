package io.github.yuroyami.kiteffmpeg.plugin

import io.github.yuroyami.kiteffmpeg.buildtools.Bakes
import io.github.yuroyami.kiteffmpeg.buildtools.BuildAssChainTask
import io.github.yuroyami.kiteffmpeg.buildtools.BuildFailure
import io.github.yuroyami.kiteffmpeg.buildtools.TargetTriple
import java.io.File
import java.nio.file.Path
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

/*
 * The bakes of native-libs/. Each one skips itself when its output is current, so these tasks turn
 * off the toolchain's own execution avoidance. The @Output paths let other tasks depend on a bake.
 */

/** Bakes dav1d for one target into `native-libs/deps/<target>/dav1d`. */
@TaskAction(ExecutionAvoidance.Disabled)
fun bakeDav1d(target: String, repoRoot: String, kotlinVersion: String, @Output outputDir: Path) {
    Bakes.dav1d(File(repoRoot), triple(target), kotlinVersion)
    expectTree(outputDir)
}

/** Bakes the LGPL FFmpeg of one target into `native-libs/lgpl/<target>`. dav1d comes first. */
@TaskAction(ExecutionAvoidance.Disabled)
fun bakeFFmpeg(target: String, repoRoot: String, kotlinVersion: String, @Input dav1dTree: Path, @Output outputDir: Path) {
    Bakes.ffmpeg(File(repoRoot), triple(target), kotlinVersion = kotlinVersion)
    expectTree(outputDir)
}

/** Depends on every FFmpeg bake. `./kotlin do buildFFmpegForAll` runs it. */
@TaskAction(ExecutionAvoidance.Disabled)
fun bakeAllFFmpeg(@Input trees: List<Path>) {
    trees.forEach(::expectTree)
}

/** Bakes the libass chain of one target into `native-libs/deps/<target>/ass-chain`. */
@TaskAction(ExecutionAvoidance.Disabled)
fun bakeAssChain(target: String, repoRoot: String, kotlinVersion: String, @Output outputDir: Path) {
    val triple = triple(target)
    if (triple !in BuildAssChainTask.SUPPORTED_TARGETS) {
        throw BuildFailure("The libass chain does not support ${triple.dirName}.")
    }
    Bakes.assChain(File(repoRoot), triple, kotlinVersion)
    expectTree(outputDir)
}

/** Bakes the web libass chain into `native-libs/deps/wasm32/ass-chain`. */
@TaskAction(ExecutionAvoidance.Disabled)
fun bakeAssChainWasm(repoRoot: String, @Output outputDir: Path) {
    Bakes.assChainWasm(File(repoRoot))
    expectTree(outputDir)
}

/** Bakes one web FFmpeg variant (`base`, `simd` or `mt`) into `native-libs/lgpl/wasm32[-<variant>]`. */
@TaskAction(ExecutionAvoidance.Disabled)
fun bakeFFmpegWasm(variant: String, repoRoot: String, @Output outputDir: Path) {
    Bakes.ffmpegWasm(File(repoRoot), variant)
    expectTree(outputDir)
}

private fun triple(name: String): TargetTriple =
    TargetTriple.entries.firstOrNull { it.commandSuffix == name } ?: throw BuildFailure("Unknown target $name.")

private fun expectTree(dir: Path) {
    if (!dir.toFile().isDirectory) throw BuildFailure("The bake left no folder at $dir.")
}
