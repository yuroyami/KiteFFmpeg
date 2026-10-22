package io.github.yuroyami.kiteffmpeg.plugin

import io.github.yuroyami.kiteffmpeg.buildtools.Bakes
import io.github.yuroyami.kiteffmpeg.buildtools.BuildLog
import io.github.yuroyami.kiteffmpeg.buildtools.BundleHostJniTask
import io.github.yuroyami.kiteffmpeg.buildtools.CompileKiteFFmpegCTask
import io.github.yuroyami.kiteffmpeg.buildtools.ExecOperations
import io.github.yuroyami.kiteffmpeg.buildtools.FFmpegInterop
import io.github.yuroyami.kiteffmpeg.buildtools.FFmpegLicense
import io.github.yuroyami.kiteffmpeg.buildtools.FFmpegPaths
import io.github.yuroyami.kiteffmpeg.buildtools.FileSystemOperations
import io.github.yuroyami.kiteffmpeg.buildtools.LinkKiteFFmpegJniTask
import io.github.yuroyami.kiteffmpeg.buildtools.PrepareKiteFFmpegJniHarnessTask
import io.github.yuroyami.kiteffmpeg.buildtools.TargetTriple
import io.github.yuroyami.kiteffmpeg.buildtools.checkJniRegistration
import io.github.yuroyami.kiteffmpeg.buildtools.compileHelper
import io.github.yuroyami.kiteffmpeg.buildtools.ensureKonanSysroot
import io.github.yuroyami.kiteffmpeg.buildtools.handleTree
import io.github.yuroyami.kiteffmpeg.buildtools.helperBuildDefines
import io.github.yuroyami.kiteffmpeg.buildtools.hostIsArm64Mac
import io.github.yuroyami.kiteffmpeg.buildtools.hostIsWindows
import io.github.yuroyami.kiteffmpeg.buildtools.jdkIncludeDir
import io.github.yuroyami.kiteffmpeg.buildtools.jniTree
import io.github.yuroyami.kiteffmpeg.buildtools.konanDataDir
import io.github.yuroyami.kiteffmpeg.buildtools.konanJniLinkTools
import io.github.yuroyami.kiteffmpeg.buildtools.macosJniLinkFlags
import java.io.File
import java.nio.file.Path
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

/**
 * Compiles the C helper layer for one Kotlin/Native target and writes the `ffmpeg` cinterop definition
 * that embeds it, together with the target's FFmpeg tree from `native-libs/lgpl/<target>`.
 *
 * The FFmpeg tree is read but never built here: `./kotlin do buildFFmpegFor<Target>` builds it.
 */
@TaskAction
fun prepareFFmpegInterop(
    konanTarget: String,
    repoRoot: String,
    homebrewPrefix: String,
    kotlinVersion: String,
    @Input cSources: Path,
    @Input cInclude: Path,
    @Input defTemplates: Path,
    @Input(inferTaskDependency = false) ffmpegTree: Path,
    @Output archiveDir: Path,
    @Output defFile: Path,
) {
    val triple = tripleOf(konanTarget)
    val license = Bakes.selectedLicense(triple)
    if (Bakes.autoBakeRequested && license == FFmpegLicense.LGPL) Bakes.ffmpeg(File(repoRoot), triple)
    ensureKonanSysroot(konanTarget, kotlinVersion)
    val paths = FFmpegPaths.resolve(
        File(repoRoot), triple, license, homebrewPrefix, buildDir = defFile.parent.toFile(),
    )
    compileHelper(konanTarget, cSources.toFile(), cInclude.toFile(), paths, archiveDir.toFile())
    val android = konanTarget.startsWith("android_")
    // Android only: defines stdout and stderr, which the API 21 libc of Kotlin/Native lacks. See interop/android-stdio/.
    // The helper compile wants an output folder named after the konan target.
    val stdioShim = archiveDir.resolve("android-stdio").resolve(konanTarget).toFile()
    if (android) {
        compileHelper(konanTarget, defTemplates.resolve("android-stdio").toFile(), cInclude.toFile(), paths, stdioShim)
    }
    // Android arm64 only: the outline atomics helpers that the FFmpeg objects call. See FFmpegInterop.
    val atomics = archiveDir.resolve("android-atomics/libkc-outline-atomics.a").toFile()
    if (konanTarget == "android_arm64") FFmpegInterop.outlineAtomicsArchive(atomics)
    FFmpegInterop.writeDefinition(
        vendoredTemplate = defTemplates.resolve("ffmpeg.def").toFile(),
        systemTemplate = defTemplates.resolve("ffmpeg-system.def").toFile(),
        paths = paths,
        helperArchive = archiveDir.resolve(CompileKiteFFmpegCTask.ARCHIVE_NAME).toFile(),
        ownInclude = cInclude.toFile(),
        destination = defFile.toFile(),
        singleArchive = android,
        extraArchives = when {
            konanTarget == "android_arm64" -> listOf(stdioShim.resolve(CompileKiteFFmpegCTask.ARCHIVE_NAME), atomics)
            android -> listOf(stdioShim.resolve(CompileKiteFFmpegCTask.ARCHIVE_NAME))
            else -> emptyList()
        },
    )
    if (!paths.isStaticVendored) {
        BuildLog.warn(
            "[KiteFFmpeg] $konanTarget links the system FFmpeg in ${paths.libDir}. Such a klib only works on this " +
                "machine. Run `./kotlin do buildFFmpegFor${triple.commandSuffix}` for a self-contained one.",
        )
    }
}

/**
 * Links the JNI library of one desktop JVM platform and lays it out at `kiteffmpeg-native/<platform>/`,
 * where `JniLibrary.jvm.kt` reads it from the JVM jar.
 *
 * A platform is skipped, with a message, when its FFmpeg tree is missing or this host cannot link it
 * (macOS needs an arm64 Mac). `./kotlin do checkDesktopJni` fails while any platform is missing, so a
 * release cannot ship without one.
 */
@TaskAction
fun linkDesktopJni(
    platformDirectory: String,
    repoRoot: String,
    kotlinVersion: String,
    @Input jniSources: Path,
    @Input handleSources: Path,
    @Input cSources: Path,
    @Input cInclude: Path,
    @Input(inferTaskDependency = false) ffmpegTree: Path,
    @Output linkDir: Path,
    @Output resourcesDir: Path,
) {
    val resources = resourcesDir.toFile().apply { deleteRecursively(); mkdirs() }
    val tree = ffmpegTree.toFile()
    val isMac = platformDirectory == MACOS_JNI_PLATFORM
    val recipe = LinkKiteFFmpegJniTask.DESKTOP_JNI_RECIPES.firstOrNull { it.platformDirectory == platformDirectory }
    require(isMac || recipe != null) { "No JNI recipe for $platformDirectory." }
    val skipReason = when {
        !tree.resolve("lib").isDirectory ->
            "no FFmpeg tree at $tree (run `./kotlin do buildFFmpegFor${tripleOf(konanTargetOf(platformDirectory)).commandSuffix}`)"
        isMac && !hostIsArm64Mac() -> "the macOS library links on an arm64 Mac only"
        !isMac && hostIsWindows() -> "the desktop cross links run on macOS and Linux hosts"
        else -> null
    }
    if (skipReason != null) {
        BuildLog.lifecycle("[KiteFFmpeg] the JVM jar carries no $platformDirectory JNI library: $skipReason.")
        return
    }

    checkJniRegistration(jniSources.toFile())
    val konanTarget = konanTargetOf(platformDirectory)
    if (!isMac) ensureKonanSysroot(konanTarget, kotlinVersion)
    val work = linkDir.toFile()
    val paths = FFmpegPaths(tree.resolve("include").absolutePath, tree.resolve("lib").absolutePath, isStaticVendored = true)
    val helperDir = work.resolve("c/$konanTarget")
    compileHelper(konanTarget, cSources.toFile(), cInclude.toFile(), paths, helperDir)

    val jdkInclude = jdkIncludeDir()
    val jni = jniSources.toFile()
    val handles = handleSources.toFile()
    val link = LinkKiteFFmpegJniTask(ExecOperations()).apply {
        name = "linkKiteFFmpegJni-$platformDirectory"
        temporaryDir = work.resolve("tmp")
        this.jniSources.from(jniTree(jni))
        this.jniSources.from(handleTree(handles))
        opaqueIncludeDir.set(cInclude.toFile())
        helperArchive.from(helperDir.resolve(CompileKiteFFmpegCTask.ARCHIVE_NAME))
        ffmpegLibDir.set(tree.resolve("lib"))
        libSearchDirs.set(emptyList())
        if (isMac) {
            compiler.set("/usr/bin/clang")
            extraIncludeDirs.set(listOf(jdkInclude.absolutePath, jdkInclude.resolve("darwin").absolutePath))
            linkFlags.set(macosJniLinkFlags())
            exportControlFile.set(jni.resolve("exports.macos"))
            exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS)
            outputDirectory.set(work.resolve("lib"))
            outputLibrary.set(work.resolve("lib/libkitecodec_jni.dylib"))
        } else {
            val desktop = checkNotNull(recipe)
            val tools = konanJniLinkTools(desktop.konanTargetName)
            compiler.set(tools.clang)
            extraIncludeDirs.set(listOf(jdkInclude.absolutePath))
            jniPlatformHeader.set(LinkKiteFFmpegJniTask.jniPlatformHeader(desktop))
            // dav1d follows the tree. It comes after the libav* group, because libavcodec draws on it.
            linkFlags.set(
                LinkKiteFFmpegJniTask.desktopLinkFlags(desktop, tools.flags, dav1d = tree.resolve("lib/libdav1d.a").exists()),
            )
            if (desktop.isWindows) {
                exportControlFile.set(jni.resolve("exports.def"))
                exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.PE_MODULE_DEFINITION)
            } else {
                exportControlFile.set(jni.resolve("exports.map"))
                exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.ELF_VERSION_SCRIPT)
            }
            outputDirectory.set(work.resolve("lib"))
            outputLibrary.set(work.resolve("lib/${desktop.libraryFileName}"))
        }
    }
    link.link()
    BundleHostJniTask().apply {
        jniLibrary.set(link.outputLibrary.get().asFile)
        this.platformDirectory.set(platformDirectory)
        outputDir.set(resources)
    }.bundle()
}

/**
 * Links the two deliberately broken macOS JNI libraries of the JNI boundary tests: one whose FFmpeg
 * identity has the wrong major version, and one with a wrong RegisterNatives descriptor.
 *
 * It writes their paths to the test resource `kiteffmpeg-jni-harness.properties`. On a host that is not
 * an arm64 Mac, the file says why there are none, and those tests skip.
 */
@TaskAction
fun linkJniHarness(
    repoRoot: String,
    @Input jniSources: Path,
    @Input handleSources: Path,
    @Input cSources: Path,
    @Input cInclude: Path,
    @Input fakeHeaders: Path,
    @Input(inferTaskDependency = false) ffmpegTree: Path,
    @Output workDir: Path,
    @Output resourcesDir: Path,
) {
    val resources = resourcesDir.toFile().apply { deleteRecursively(); mkdirs() }
    val properties = resources.resolve(HARNESS_PROPERTIES)
    val tree = ffmpegTree.toFile()
    val unavailable = when {
        !hostIsArm64Mac() -> "the JNI harness links on an arm64 Mac only"
        !tree.resolve("lib").isDirectory -> "no macOS arm64 FFmpeg tree at $tree"
        else -> null
    }
    if (unavailable != null) {
        properties.writeText("unavailable=$unavailable\n")
        BuildLog.lifecycle("[KiteFFmpeg] no JNI harness libraries: $unavailable.")
        return
    }

    checkJniRegistration(jniSources.toFile())
    val work = workDir.toFile().apply { mkdirs() }
    val jni = jniSources.toFile()
    val opaqueInclude = cInclude.toFile()
    val ffmpeg = FFmpegPaths(tree.resolve("include").absolutePath, tree.resolve("lib").absolutePath, isStaticVendored = true)
    val jdkInclude = jdkIncludeDir()

    // The normal helper, which the corrupt-descriptor library links.
    val normalHelper = work.resolve("c/macos_arm64")
    compileHelper("macos_arm64", cSources.toFile(), cInclude.toFile(), ffmpeg, normalHelper)

    // An identity helper whose FFmpeg major version is one below the real one.
    val mismatchHeaders = work.resolve("major-mismatch/include")
    PrepareKiteFFmpegJniHarnessTask(FileSystemOperations()).apply {
        sourceDirectory.set(opaqueInclude)
        mutationSourceFile.set(fakeHeaders.resolve("major_mismatch/kitecodec_ffmpeg_versions.h").toFile())
        relativeFile.set("kitecodec_ffmpeg_versions.h")
        expectedText.set("#define KC_CASE kc_major_mismatch\n#include \"../kc_rename.h\"\n\n")
        replacementText.set("")
        outputDirectory.set(mismatchHeaders)
    }.prepare()
    val mismatchHelper = work.resolve("major-mismatch-c/macos_arm64")
    CompileKiteFFmpegCTask(ExecOperations()).apply {
        name = "compileKiteFFmpegCForJniMacosArm64MajorMismatch"
        konanTargetName.set("macos_arm64")
        sourceDir.set(cSources.toFile())
        includeDir.set(mismatchHeaders)
        // The generated fake header's include_next resolves the production copy second.
        ffmpegIncludeDirs.set(listOf(opaqueInclude.absolutePath, ffmpeg.includeDir))
        buildDefines.set(helperBuildDefines(ffmpeg.libDir))
        konanDataDir.set(konanDataDir())
        outputDir.set(mismatchHelper)
    }.compile()
    val mismatchLibrary = work.resolve("libs/major-mismatch/libkitecodec_jni_major_mismatch.dylib")
    macosHarnessLink(
        name = "linkKiteFFmpegJniMacosArm64MajorMismatch",
        jniTree = jniTree(jni),
        handles = handleSources.toFile(),
        opaqueInclude = opaqueInclude,
        helperArchive = mismatchHelper.resolve(CompileKiteFFmpegCTask.ARCHIVE_NAME),
        ffmpegLib = tree.resolve("lib"),
        jdkInclude = jdkInclude,
        exportControl = jni.resolve("exports.macos"),
        output = mismatchLibrary,
        work = work,
    )

    // A JNI tree whose RegisterNatives descriptor for nativeAbiVersion is wrong.
    val corruptTree = work.resolve("corrupt-descriptor/jni")
    PrepareKiteFFmpegJniHarnessTask(FileSystemOperations()).apply {
        sourceDirectory.set(jni)
        mutationSourceFile.set(jni.resolve("methods.def"))
        relativeFile.set("methods.def")
        expectedText.set(
            """KJ_METHOD("io/github/yuroyami/kiteffmpeg/Internals", "nativeAbiVersion",       "()I",                    kj_abi_version)""",
        )
        replacementText.set(
            """KJ_METHOD("io/github/yuroyami/kiteffmpeg/Internals", "nativeAbiVersion",       "()J",                    kj_abi_version)""",
        )
        outputDirectory.set(corruptTree)
    }.prepare()
    val corruptLibrary = work.resolve("libs/corrupt-descriptor/libkitecodec_jni_corrupt_descriptor.dylib")
    macosHarnessLink(
        name = "linkKiteFFmpegJniMacosArm64CorruptDescriptor",
        jniTree = jniTree(corruptTree),
        handles = handleSources.toFile(),
        opaqueInclude = opaqueInclude,
        helperArchive = normalHelper.resolve(CompileKiteFFmpegCTask.ARCHIVE_NAME),
        ffmpegLib = tree.resolve("lib"),
        jdkInclude = jdkInclude,
        exportControl = corruptTree.resolve("exports.macos"),
        output = corruptLibrary,
        work = work,
    )

    properties.writeText(
        "kiteffmpeg.jni.mismatch.path=${mismatchLibrary.absolutePath}\n" +
            "kiteffmpeg.jni.corrupt.path=${corruptLibrary.absolutePath}\n",
    )
}

internal const val MACOS_JNI_PLATFORM = "macos-arm64"
internal const val HARNESS_PROPERTIES = "kiteffmpeg-jni-harness.properties"

private fun macosHarnessLink(
    name: String,
    jniTree: List<File>,
    handles: File,
    opaqueInclude: File,
    helperArchive: File,
    ffmpegLib: File,
    jdkInclude: File,
    exportControl: File,
    output: File,
    work: File,
) {
    LinkKiteFFmpegJniTask(ExecOperations()).apply {
        this.name = name
        temporaryDir = work.resolve("tmp/$name")
        jniSources.from(jniTree)
        // The staged tree is a copy of native/kitecodec-jni only. The shared handle table is not in it.
        jniSources.from(handleTree(handles))
        opaqueIncludeDir.set(opaqueInclude)
        this.helperArchive.from(helperArchive)
        ffmpegLibDir.set(ffmpegLib)
        compiler.set("/usr/bin/clang")
        extraIncludeDirs.set(listOf(jdkInclude.absolutePath, jdkInclude.resolve("darwin").absolutePath))
        libSearchDirs.set(emptyList())
        linkFlags.set(macosJniLinkFlags())
        exportControlFile.set(exportControl)
        exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS)
        outputDirectory.set(output.parentFile)
        outputLibrary.set(output)
    }.link()
}

internal fun tripleOf(konanTarget: String): TargetTriple =
    TargetTriple.entries.firstOrNull { it.konanTargetName == konanTarget }
        ?: error("No FFmpeg target is named $konanTarget.")

internal fun konanTargetOf(platformDirectory: String): String = when (platformDirectory) {
    MACOS_JNI_PLATFORM -> "macos_arm64"
    else -> LinkKiteFFmpegJniTask.DESKTOP_JNI_RECIPES.first { it.platformDirectory == platformDirectory }.konanTargetName
}
