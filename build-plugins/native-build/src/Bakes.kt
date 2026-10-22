package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File

/**
 * The bakes of the native trees under `native-libs/`. The plugin commands call them, and so does the
 * interop step when `KITEFFMPEG_AUTO_BAKE=true` is set.
 *
 * Each bake skips itself when its output is current (see [BakeStamp]), so a long FFmpeg build runs
 * only when its inputs changed or its folder is gone.
 */
object Bakes {

    /** `true` makes a release bake fail when a dependency is not inside the tree as a `.a`. */
    const val SELF_CONTAINED_ENV: String = "KITEFFMPEG_FFMPEG_SELF_CONTAINED"

    /** Where the emscripten LLVM tools are, for the web FFmpeg builds. */
    const val EMSCRIPTEN_LLVM_BIN_ENV: String = "KITEFFMPEG_EMSCRIPTEN_LLVM_BIN"

    /** `true` makes the interop step bake a missing or stale FFmpeg tree first. */
    const val AUTO_BAKE_ENV: String = "KITEFFMPEG_AUTO_BAKE"

    /** `gpl` makes the interop step link a GPL tree from `native-libs/gpl/<target>` that you baked yourself. */
    const val LICENSE_ENV: String = "KITEFFMPEG_FFMPEG_LICENSE"

    /** The licence of the FFmpeg tree that [target] links. Android always links the LGPL tree. */
    fun selectedLicense(target: TargetTriple): FFmpegLicense =
        if (!target.isAndroid && System.getenv(LICENSE_ENV).equals("gpl", ignoreCase = true)) FFmpegLicense.GPL else FFmpegLicense.LGPL

    val selfContainedRequested: Boolean get() = System.getenv(SELF_CONTAINED_ENV).toBoolean()

    val autoBakeRequested: Boolean get() = System.getenv(AUTO_BAKE_ENV).toBoolean()

    fun ffmpegTask(repoRoot: File, target: TargetTriple, selfContained: Boolean = selfContainedRequested): BuildFFmpegTask =
        BuildFFmpegTask().apply {
            name = "buildFFmpegFor${target.commandSuffix}"
            this.target.set(target)
            license.set(FFmpegLicense.LGPL)
            sourceRef.set(BuildFFmpegTask.DEFAULT_SOURCE_REF)
            sourceCommit.set(BuildFFmpegTask.DEFAULT_SOURCE_COMMIT)
            sourcePatches.from(patches(repoRoot, "ffmpeg"))
            requireSelfContained.set(selfContained)
            sourceDir.set(repoRoot.resolve("vendor/ffmpeg"))
            outputDir.set(repoRoot.resolve("native-libs/${FFmpegLicense.LGPL.dirName}/${target.dirName}"))
        }

    /** The Kotlin/Native version whose konan sysroots the Linux and Windows bakes compile against. */
    const val KOTLIN_VERSION: String = "2.4.21"

    /** Bakes dav1d and then FFmpeg for [target], each only when it is not current. */
    fun ffmpeg(
        repoRoot: File,
        target: TargetTriple,
        selfContained: Boolean = selfContainedRequested,
        kotlinVersion: String = KOTLIN_VERSION,
    ) {
        dav1d(repoRoot, target, kotlinVersion)
        val task = ffmpegTask(repoRoot, target, selfContained)
        val output = task.outputDir.get().asFile
        val inputs = BakeStamp.inputs(
            task.name,
            listOf(target.dirName, task.sourceRef.get(), task.sourceCommit.get(), "selfContained=$selfContained") + floors(target),
            task.sourcePatches.files,
        )
        val evidence = output.resolve(BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH)
        val stale = if (evidence.isFile) {
            BuildFFmpegTask.staleReason(evidence.readText().trim(), task.expectedRecipeFingerprint().toList())
        } else {
            "it has no configure record"
        }
        val required = BuildFFmpegTask.REQUIRED_LIBS.map { "lib/$it.a" } + "include/libavformat/avformat.h"
        // A self-contained bake must have run as one: an older tree may link libraries of the host.
        val current = stale == null && if (selfContained) {
            output.resolve(BakeStamp.FILE_NAME).let { it.isFile && it.readText() == inputs }
        } else {
            BakeStamp.isCurrent(output, inputs, required, "the FFmpeg tree of ${target.dirName}")
        }
        if (current) return
        stale?.let { BuildLog.lifecycle("[KiteFFmpeg] baking FFmpeg for ${target.dirName}: $it") }
        ensureKonanSysroot(target.konanTargetName, kotlinVersion)
        task.run()
        BakeStamp.record(output, inputs)
    }

    fun dav1d(repoRoot: File, target: TargetTriple, kotlinVersion: String = KOTLIN_VERSION) {
        if (target !in BuildDav1dTask.SUPPORTED_TARGETS) return
        val task = BuildDav1dTask().apply {
            name = "buildDav1dFor${target.commandSuffix}"
            this.target.set(target)
            sourceRef.set(BuildDav1dTask.DEFAULT_SOURCE_REF)
            sourceCommit.set(BuildDav1dTask.DEFAULT_SOURCE_COMMIT)
            sourceDir.set(repoRoot.resolve("vendor/dav1d"))
            outputDir.set(repoRoot.resolve("native-libs/deps/${target.dirName}/dav1d"))
        }
        val output = task.outputDir.get().asFile
        val inputs = BakeStamp.inputs(task.name, listOf(target.dirName, task.sourceRef.get(), task.sourceCommit.get()) + floors(target))
        if (BakeStamp.isCurrent(output, inputs, listOf("lib/libdav1d.a", "include/dav1d/dav1d.h"), "dav1d for ${target.dirName}")) return
        ensureKonanSysroot(target.konanTargetName, kotlinVersion)
        task.run()
        BakeStamp.record(output, inputs)
    }

    fun assChain(repoRoot: File, target: TargetTriple, kotlinVersion: String = KOTLIN_VERSION) {
        val task = BuildAssChainTask().apply {
            name = "buildAssChainFor${target.commandSuffix}"
            this.target.set(target)
            sourceRefs.set(BuildAssChainTask.DEFAULT_SOURCE_REFS)
            vendorDir.set(repoRoot.resolve("vendor"))
            sourcePatches.from(patches(repoRoot, "libass"))
            outputDir.set(repoRoot.resolve("native-libs/deps/${target.dirName}/ass-chain"))
        }
        val output = task.outputDir.get().asFile
        val inputs = BakeStamp.inputs(task.name, listOf(target.dirName, task.sourceRefs.get()) + floors(target), task.sourcePatches.files)
        if (BakeStamp.isCurrent(output, inputs, ASS_CHAIN_LIBS, "the libass chain of ${target.dirName}")) return
        ensureKonanSysroot(target.konanTargetName, kotlinVersion)
        task.run()
        BakeStamp.record(output, inputs)
    }

    fun assChainWasm(repoRoot: File) {
        val task = BuildAssChainWasmTask().apply {
            name = "buildAssChainForWasm32"
            sourceRefs.set(BuildAssChainTask.DEFAULT_SOURCE_REFS)
            vendorDir.set(repoRoot.resolve("vendor"))
            sourcePatches.from(patches(repoRoot, "libass"))
            outputDir.set(repoRoot.resolve("native-libs/deps/wasm32/ass-chain"))
        }
        val output = task.outputDir.get().asFile
        val inputs = BakeStamp.inputs(task.name, listOf(task.sourceRefs.get()), task.sourcePatches.files)
        if (BakeStamp.isCurrent(output, inputs, ASS_CHAIN_LIBS, "the web libass chain")) return
        task.run()
        BakeStamp.record(output, inputs)
    }

    /** The web FFmpeg tree of one [variant]: `base`, `simd` or `mt`. */
    fun ffmpegWasm(repoRoot: File, variant: String) {
        val task = BuildFFmpegWasmTask().apply {
            name = "buildFFmpegForWasm" + if (variant == "base") "" else variant.replaceFirstChar(Char::uppercase)
            this.variant.set(variant)
            sourceRef.set(BuildFFmpegTask.DEFAULT_SOURCE_REF)
            sourceCommit.set(BuildFFmpegTask.DEFAULT_SOURCE_COMMIT)
            sourcePatches.from(patches(repoRoot, "ffmpeg"))
            emscriptenLlvmBin.set(
                System.getenv(EMSCRIPTEN_LLVM_BIN_ENV)?.takeIf { it.isNotBlank() } ?: BuildFFmpegWasmTask.DEFAULT_EMSCRIPTEN_LLVM_BIN,
            )
            sourceDir.set(repoRoot.resolve("vendor/ffmpeg"))
            outputDir.set(wasmTree(repoRoot, variant))
        }
        val output = task.outputDir.get().asFile
        val inputs = BakeStamp.inputs(task.name, listOf(variant, task.sourceRef.get(), task.sourceCommit.get()), task.sourcePatches.files)
        val required = BuildFFmpegTask.REQUIRED_LIBS.map { "lib/$it.a" } + "include/libavformat/avformat.h"
        if (BakeStamp.isCurrent(output, inputs, required, "the web FFmpeg tree ($variant)")) return
        task.run()
        BakeStamp.record(output, inputs)
    }

    fun wasmTree(repoRoot: File, variant: String = "base"): File =
        repoRoot.resolve("native-libs/lgpl/wasm32" + if (variant == "base") "" else "-$variant")

    /** What `checkFFmpegRecipes` compares: the recipe each LGPL tree would be baked with now. */
    fun ffmpegRecipeExpectations(repoRoot: File): List<FFmpegRecipeExpectation> =
        TargetTriple.entries.map { target ->
            val task = ffmpegTask(repoRoot, target, selfContained = false)
            FFmpegRecipeExpectation(task.name, task.outputDir.get().asFile.absolutePath, task.expectedRecipeFingerprint())
        }

    private val ASS_CHAIN_LIBS = listOf("lib/libass.a", "lib/libfreetype.a", "lib/libfribidi.a", "lib/libharfbuzz.a")

    private fun patches(repoRoot: File, name: String): List<File> =
        repoRoot.resolve("native/patches/$name").listFiles { file -> file.isFile && file.extension == "patch" }
            .orEmpty().sortedBy { it.name }

    private fun floors(target: TargetTriple): List<String> = when {
        target.dirName.startsWith("ios") -> listOf("ios=${BuildFFmpegTask.IOS_DEPLOYMENT_TARGET}")
        target.dirName.startsWith("macos") -> listOf("macos=${BuildFFmpegTask.MACOS_DEPLOYMENT_TARGET}")
        else -> emptyList()
    }
}
