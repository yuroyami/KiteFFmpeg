package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File

/** Compiles the C helper layer for [konanTarget] into [outputDir], which must be named after the target. */
fun compileHelper(konanTarget: String, cSources: File, cInclude: File, ffmpeg: FFmpegPaths, outputDir: File) {
    CompileKiteFFmpegCTask(ExecOperations()).apply {
        name = "compileKiteFFmpegCFor$konanTarget"
        konanTargetName.set(konanTarget)
        sourceDir.set(cSources)
        includeDir.set(cInclude)
        ffmpegIncludeDirs.set(listOf(ffmpeg.includeDir))
        buildDefines.set(helperBuildDefines(ffmpeg.libDir))
        konanDataDir.set(konanDataDir())
        this.outputDir.set(outputDir)
    }.compile()
}

/** What the archive reports about its build: the FFmpeg release, the licence and the tree it was compiled against. */
fun helperBuildDefines(ffmpegLibDir: String): Map<String, String> = mapOf(
    CompileKiteFFmpegCTask.DEFINE_FFMPEG_REF to BuildFFmpegTask.DEFAULT_SOURCE_REF,
    CompileKiteFFmpegCTask.DEFINE_FFMPEG_LICENSE to FFmpegLicense.LGPL.dirName,
    CompileKiteFFmpegCTask.DEFINE_FFMPEG_DIR to ffmpegLibDir,
)

/**
 * The portable macOS JNI link: the six libav* archives, the mandatory dav1d, SDK zlib and the media
 * frameworks. The floor is passed, never inherited: without it clang takes the SDK's own version.
 */
fun macosJniLinkFlags(): List<String> = listOf(
    "-mmacosx-version-min=${BuildFFmpegTask.MACOS_DEPLOYMENT_TARGET}",
    "-lavformat", "-lavcodec", "-lavfilter", "-lavutil", "-lswscale", "-lswresample",
    "-ldav1d",
    "-lz",
    "-framework", "CoreFoundation", "-framework", "CoreMedia",
    "-framework", "CoreVideo", "-framework", "VideoToolbox",
    "-framework", "AudioToolbox",
)

/**
 * RegisterNatives keeps each function as an untyped pointer, so the compiler never compares a C function
 * with its JNI descriptor. Every JNI link runs this comparison first.
 */
fun checkJniRegistration(jniSources: File) {
    CheckJniRegistrationTask().apply {
        manifest.set(jniSources.resolve("methods.def"))
        sources.from(jniSources.listFiles().orEmpty().filter { it.isFile && it.extension == "c" }.sorted())
    }.check()
}

/** The adapter sources of a JNI folder: its C files, its headers and `methods.def`. */
fun jniTree(dir: File): List<File> =
    dir.listFiles().orEmpty().filter { it.isFile && (it.extension == "c" || it.extension == "h" || it.name == "methods.def") }.sorted()

/** The handle table that the JNI adapter shares with the web binding. */
fun handleTree(dir: File): List<File> =
    dir.listFiles().orEmpty().filter { it.isFile && (it.extension == "c" || it.extension == "h") }.sorted()

fun hostIsArm64Mac(): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    return "mac" in os && arch in setOf("aarch64", "arm64")
}

fun hostIsWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
