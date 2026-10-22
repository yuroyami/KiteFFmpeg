package io.github.yuroyami.kiteffmpeg.androidjni

import io.github.yuroyami.kiteffmpeg.buildtools.BuildFailure
import io.github.yuroyami.kiteffmpeg.buildtools.BuildLog
import io.github.yuroyami.kiteffmpeg.buildtools.CompileKiteFFmpegCTask
import io.github.yuroyami.kiteffmpeg.buildtools.ExecOperations
import io.github.yuroyami.kiteffmpeg.buildtools.FFmpegPaths
import io.github.yuroyami.kiteffmpeg.buildtools.LinkKiteFFmpegJniTask
import io.github.yuroyami.kiteffmpeg.buildtools.androidNdkHome
import io.github.yuroyami.kiteffmpeg.buildtools.checkJniRegistration
import io.github.yuroyami.kiteffmpeg.buildtools.compileHelper
import io.github.yuroyami.kiteffmpeg.buildtools.ensureKonanSysroot
import io.github.yuroyami.kiteffmpeg.buildtools.handleTree
import io.github.yuroyami.kiteffmpeg.buildtools.jniTree
import java.nio.file.Path
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

/**
 * Links `libkitecodec_jni.so` for one Android ABI and puts it at `lib/<abi>/` in [resourcesDir].
 * AGP takes the `.so` files under `lib/<abi>/` out of a plain jar dependency and packages them as native libraries of the app.
 *
 * An ABI whose FFmpeg tree is missing is skipped with a message. `./kotlin check androidJni` fails while
 * any ABI is missing, so a release cannot ship without one.
 */
@TaskAction
fun linkAndroidJni(
    abi: String,
    kotlinVersion: String,
    @Input jniSources: Path,
    @Input handleSources: Path,
    @Input cSources: Path,
    @Input cInclude: Path,
    @Input(inferTaskDependency = false) ffmpegTree: Path,
    @Output linkDir: Path,
    @Output resourcesDir: Path,
) {
    val recipe = LinkKiteFFmpegJniTask.ANDROID_ABI_RECIPES.firstOrNull { it.abiDirectory == abi }
        ?: throw BuildFailure("No Android JNI recipe for the ABI $abi.")
    val resources = resourcesDir.toFile().apply { deleteRecursively(); mkdirs() }
    val tree = ffmpegTree.toFile()
    if (!tree.resolve("lib").isDirectory) {
        BuildLog.lifecycle(
            "[KiteFFmpeg] kiteffmpeg-android-natives carries no $abi library: no FFmpeg tree at $tree " +
                "(run `./kotlin do buildFFmpegFor${recipe.linkTaskName.removePrefix("linkKiteFFmpegJni")}`).",
        )
        return
    }
    checkJniRegistration(jniSources.toFile())
    ensureKonanSysroot(recipe.konanTargetName, kotlinVersion)
    val work = linkDir.toFile()
    val paths = FFmpegPaths(tree.resolve("include").absolutePath, tree.resolve("lib").absolutePath, isStaticVendored = true)
    val helperDir = work.resolve("c/${recipe.konanTargetName}")
    compileHelper(recipe.konanTargetName, cSources.toFile(), cInclude.toFile(), paths, helperDir)

    val jni = jniSources.toFile()
    val output = resources.resolve("lib/$abi/libkitecodec_jni.so")
    LinkKiteFFmpegJniTask(ExecOperations()).apply {
        name = recipe.linkTaskName
        temporaryDir = work.resolve("tmp")
        this.jniSources.from(jniTree(jni))
        this.jniSources.from(handleTree(handleSources.toFile()))
        opaqueIncludeDir.set(cInclude.toFile())
        helperArchive.from(helperDir.resolve(CompileKiteFFmpegCTask.ARCHIVE_NAME))
        ffmpegLibDir.set(tree.resolve("lib"))
        compiler.set(LinkKiteFFmpegJniTask.ndkClang(androidNdkHome().absolutePath))
        extraIncludeDirs.set(emptyList())
        libSearchDirs.set(emptyList())
        linkFlags.set(LinkKiteFFmpegJniTask.androidLinkFlags(recipe, dav1d = tree.resolve("lib/libdav1d.a").exists()))
        exportControlFile.set(jni.resolve("exports.map"))
        exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.ELF_VERSION_SCRIPT)
        outputDirectory.set(resources.resolve("lib"))
        outputLibrary.set(output)
    }.link()
    BuildLog.lifecycle("[KiteFFmpeg] linked $output (${output.length()} bytes)")
}

/** Fails unless the jar carries the library of every Android ABI. Run it before a release. */
@TaskAction
fun checkAndroidJni(
    abis: List<String>,
    @Input libraries: List<Path>,
) {
    val missing = abis.zip(libraries).filter { (_, dir) -> !dir.resolve("lib").toFile().walkTopDown().any { it.name == "libkitecodec_jni.so" } }
    if (missing.isNotEmpty()) {
        throw BuildFailure(
            "kiteffmpeg-android-natives carries no JNI library for ${missing.joinToString { it.first }}. " +
                "Build the FFmpeg trees of those ABIs first.",
        )
    }
    println("kiteffmpeg-android-natives carries the JNI library of ${abis.joinToString()}.")
}
