package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File

/** Compiles the C helper layer for the web into [outputDir], against the headers of the web FFmpeg tree. */
fun compileWebHelper(cSources: File, cInclude: File, handleSources: File, ffmpegTree: File, outputDir: File) {
    CompileKiteFFmpegCWasmTask().apply {
        sourceDir.set(cSources)
        includeDir.set(cInclude)
        handlesDir.set(handleSources)
        ffmpegIncludeDir.set(ffmpegTree.resolve("include"))
        ffmpegVersionHeaders.from(ffmpegTree.resolve("include/libavutil/ffversion.h"))
        buildDefines.set(
            mapOf(
                CompileKiteFFmpegCTask.DEFINE_FFMPEG_REF to BuildFFmpegTask.DEFAULT_SOURCE_REF,
                CompileKiteFFmpegCTask.DEFINE_FFMPEG_LICENSE to FFmpegLicense.LGPL.dirName,
                CompileKiteFFmpegCTask.DEFINE_FFMPEG_DIR to ffmpegTree.resolve("lib").absolutePath,
            ),
        )
        this.outputDir.set(outputDir)
    }.compile()
}

/** Links `kite.mjs` and `kite.wasm` into [outputDir] from the helper archive in [helperDir]. */
fun linkWebModule(helperDir: File, ffmpegTree: File, signatureBaseline: File, outputDir: File) {
    LinkKiteFFmpegWasmModuleTask().apply {
        helperArchive.set(helperDir.resolve(CompileKiteFFmpegCTask.ARCHIVE_NAME))
        ffmpegLibDir.set(ffmpegTree.resolve("lib"))
        this.signatureBaseline.set(signatureBaseline)
        this.outputDir.set(outputDir)
    }.link()
}

/** Links `kite-jspi` and `kite-asyncify` into [outputDir] from the helper archive in [helperDir]. */
fun linkAsyncWebModules(
    helperDir: File,
    ffmpegTree: File,
    signatureBaseline: File,
    bridgeSource: File,
    importsLibrary: File,
    cInclude: File,
    handleSources: File,
    outputDir: File,
) {
    LinkKiteFFmpegAsyncWasmModulesTask().apply {
        helperArchive.set(helperDir.resolve(CompileKiteFFmpegCTask.ARCHIVE_NAME))
        ffmpegLibDir.set(ffmpegTree.resolve("lib"))
        this.signatureBaseline.set(signatureBaseline)
        this.bridgeSource.set(bridgeSource)
        this.importsLibrary.set(importsLibrary)
        includeDirs.from(cInclude, handleSources, ffmpegTree.resolve("include"))
        this.outputDir.set(outputDir)
    }.link()
}

/**
 * The files of a web package, as pairs of path and source file: the codec module files at the root,
 * the build record under `build-info/` and the licence texts under `licenses/`.
 * The web zips and the jars of kiteffmpeg-web and kiteffmpeg-web-async hold these files.
 */
fun webPackageEntries(moduleDir: File, ffmpegTree: File, licenseFiles: List<File>): List<Pair<String, File>> {
    val buildInfo = ffmpegTree.resolve("lib/kiteffmpeg")
    return buildList {
        moduleDir.walkTopDown().filter { it.isFile }.sorted()
            .forEach { add(it.relativeTo(moduleDir).invariantSeparatorsPath to it) }
        WEB_BUILD_INFO_FILES.map { buildInfo.resolve(it) }.filter { it.isFile }
            .forEach { add("build-info/${it.name}" to it) }
        licenseFiles.forEach { add("licenses/${it.name}" to it) }
    }
}

/** The build record files that the web FFmpeg tree keeps under `lib/kiteffmpeg/`. */
val WEB_BUILD_INFO_FILES = listOf("web-build-info.txt", "ffmpeg-patches.txt")
