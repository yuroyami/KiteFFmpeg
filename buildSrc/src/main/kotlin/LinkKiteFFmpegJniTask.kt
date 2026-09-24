package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.CommandLineArgumentProvider
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * Supplies the path-valued JVM-test properties without stringifying Gradle [Provider] objects.
 * Gradle asks for these arguments when it starts the test JVM, after the three dylibs exist. The
 * dylibs are real content-tracked inputs; the transcript is the test task's declared output.
 */
abstract class KiteFFmpegJvmTestArgumentProvider : CommandLineArgumentProvider {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val normalJniLibrary: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mismatchJniLibrary: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val corruptJniLibrary: RegularFileProperty

    /** Declared as the owning Test task's output; retained here only to materialize its path. */
    @get:Internal
    abstract val contractTranscript: RegularFileProperty

    /** The real JVM test runtime, not Gradle's often-minimal worker-process java.class.path. */
    @get:Classpath
    abstract val probeClasspath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf(
        "-Dkiteffmpeg.jni.path=${normalJniLibrary.get().asFile.absolutePath}",
        "-Dkiteffmpeg.jni.mismatch.path=${mismatchJniLibrary.get().asFile.absolutePath}",
        "-Dkiteffmpeg.jni.corrupt.path=${corruptJniLibrary.get().asFile.absolutePath}",
        "-Dkiteffmpeg.contract.transcript=${contractTranscript.get().asFile.absolutePath}",
        "-Dkiteffmpeg.jni.probe.classpath=${probeClasspath.asPath}",
    )
}

/**
 * Makes an isolated generated copy of a JNI/opaque-header tree and performs one exact mutation.
 * The exact-once gate is load bearing: a renamed or duplicated manifest/header record must fail the
 * harness producer instead of silently turning a falsifiability arm into a normal-library test.
 */
abstract class PrepareKiteFFmpegJniHarnessTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    /** File whose bytes are transformed into [relativeFile]; it may be an overlay outside the tree. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mutationSourceFile: RegularFileProperty

    @get:Input
    abstract val relativeFile: Property<String>

    @get:Input
    abstract val expectedText: Property<String>

    @get:Input
    abstract val replacementText: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val sourceRoot = sourceDirectory.get().asFile
        val outputRoot = outputDirectory.get().asFile.canonicalFile
        fileSystemOperations.sync {
            from(sourceRoot)
            into(outputRoot)
        }

        val target = outputRoot.resolve(relativeFile.get()).canonicalFile
        if (!target.toPath().startsWith(outputRoot.toPath()) || !target.isFile) {
            throw GradleException(
                "Harness mutation target '${relativeFile.get()}' is not a file inside " +
                    outputRoot.absolutePath,
            )
        }
        val original = mutationSourceFile.get().asFile.readText(StandardCharsets.UTF_8)
        target.writeText(
            replaceExactlyOnce(original, expectedText.get(), replacementText.get(), relativeFile.get()),
            StandardCharsets.UTF_8,
        )
    }

    companion object {



        internal fun replaceExactlyOnce(
            source: String,
            expected: String,
            replacement: String,
            label: String,
        ): String {
            if (expected.isEmpty()) {
                throw GradleException("Harness mutation for '$label' has an empty expected value.")
            }
            val first = source.indexOf(expected)
            val second = if (first >= 0) source.indexOf(expected, first + expected.length) else -1
            if (first < 0 || second >= 0) {
                val count = if (first < 0) 0 else 2
                throw GradleException(
                    "Harness mutation for '$label' expected exactly one source occurrence; found $count${if (second >= 0) "+" else ""}.",
                )
            }
            return source.replaceRange(first, first + expected.length, replacement)
        }
    }
}

/**
 * Compiles the `native/kitecodec-jni` adapter and links ONE shared JNI library against the opaque
 * helper archive and a static FFmpeg tree (S1.c.1 step 6).
 *
 * The registrations (kiteffmpeg/build.gradle.kts) are the test-only macOS dylib that jvmTest
 * loads through the `kiteffmpeg.jni.path` system property, and one Android arm per
 * [ANDROID_ABI_RECIPES] entry, whose outputs are the exact `jniLibs` inputs of the AAR. The Android
 * arms use the NDK's clang with the version script, and the 64-bit ones add the 16 KiB page flags;
 * the macOS arm uses the system clang with an `-exported_symbols_list`, because a Mach-O link does
 * not read an ELF version script. Both recipes were proved by hand at the S1.c scaffold
 * (2026-08-12) before being encoded here.
 *
 * The ELF and Mach-O outputs must export exactly `JNI_OnLoad`: `scripts/symbol-audit.sh` asserts it
 * per arm, and the S1.c.1 gate runs an ELF PT_LOAD 0x4000 check beside it for the 64-bit Android
 * arms. The Windows output exports `JNI_OnLoad` and the C archive's entry points; see
 * [jniPlatformHeader].
 */
/** The konan clang and the flags a desktop cross link needs, resolved from the konan tree. */
class KonanJniLinkTools(val clang: String, val flags: List<String>)

/**
 * Cross-link settings for a Linux or Windows JNI library, from the SAME konan toolchain
 * Kotlin/Native links with, so the result agrees with everything else this project ships.
 *
 * The Linux glibc floor comes from the konan sysroot, 2.19 for x64 and 2.25 for arm64. Building
 * inside a modern container instead would pin the floor at that container's glibc and quietly
 * drop older distributions. Windows links against konan's MinGW sysroot the same way.
 */
fun konanJniLinkTools(konanTarget: String): KonanJniLinkTools {
    val konanRoot = System.getenv("KONAN_DATA_DIR")?.let(::File)
        ?: File(System.getProperty("user.home"), ".konan")
    val dependencies = konanRoot.resolve("dependencies")
    val llvmBin = CompileKiteFFmpegCTask.resolveLlvmBinDir(
        dependencies,
        CompileKiteFFmpegCTask.DEFAULT_LLVM_PACKAGE,
    )
    val clang = CompileKiteFFmpegCTask.resolveTool(llvmBin, "clang")
        ?: throw GradleException("no clang under ${llvmBin.absolutePath}")
    val spec = CompileKiteFFmpegCTask.specFor(konanTarget)
    val sysrootRelative = requireNotNull(spec.konanSysroot) { "$konanTarget has no sysroot" }
    val sysroot = dependencies.resolve(sysrootRelative)
    val packageRoot = dependencies.resolve(sysrootRelative.substringBefore('/'))
    val runtime = packageRoot.resolve("lib/gcc").listFiles().orEmpty()
        .filter { it.isDirectory }
        .flatMap { it.listFiles().orEmpty().filter { version -> version.isDirectory } }
        .firstOrNull { it.resolve("libgcc.a").isFile }
    val flags = buildList {
        add("-target"); add(spec.triple)
        add("--sysroot=${sysroot.absolutePath}")
        // Apple's ld cannot link ELF, so lld is named explicitly and found through -B.
        add("-fuse-ld=lld")
        add("-B${llvmBin.absolutePath}")
        if (runtime != null) { add("-B${runtime.absolutePath}"); add("-L${runtime.absolutePath}") }
    }
    return KonanJniLinkTools(clang.absolutePath, flags)
}

abstract class LinkKiteFFmpegJniTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** `native/kitecodec-jni`: the adapter sources, headers and manifest. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jniSources: ConfigurableFileCollection

    /** `native/kitecodec-c/include`: the opaque boundary headers. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val opaqueIncludeDir: DirectoryProperty

    /** The compiled opaque helper archive for this target. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val helperArchive: ConfigurableFileCollection

    /** The static FFmpeg install tree's `lib` directory for this target. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ffmpegLibDir: DirectoryProperty

    /** Absolute path of the C compiler driver (NDK clang for Android, /usr/bin/clang for macOS). */
    @get:Input
    abstract val compiler: Property<String>

    /** Directories added as `-I` beyond the adapter's own and the opaque include dir (JNI headers). */
    @get:Input
    abstract val extraIncludeDirs: ListProperty<String>

    /** Exact link flags AFTER the objects and archives (libraries, frameworks, export control). */
    @get:Input
    abstract val linkFlags: ListProperty<String>

    /** The one platform-specific file that limits the dynamic export surface to `JNI_OnLoad`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val exportControlFile: RegularFileProperty

    /** Selects the linker spelling for [exportControlFile]. */
    @get:Input
    abstract val exportControlKind: Property<ExportControlKind>

    /** Extra `-L` directories, absolute. */
    @get:Input
    abstract val libSearchDirs: ListProperty<String>

    /**
     * The platform half of `jni.h`, for a link whose platform is not the building JDK's own. It
     * is written to this task's temporary directory as `jni_md.h` and searched before
     * [extraIncludeDirs], so the building JDK's `jni.h` picks it up. See [jniPlatformHeader].
     */
    @get:Input
    @get:Optional
    abstract val jniPlatformHeader: Property<String>

    /**
     * The generated-source root consumed by Android's `jniLibs` source API. It is deliberately
     * one level above the ABI directory, so AGP packages `arm64-v8a/...` and `x86_64/...` rather
     * than flattening either ABI out of the AAR.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** The shared library inside [outputDirectory]. The directory is the Gradle output. */
    @get:Internal
    abstract val outputLibrary: RegularFileProperty

    @TaskAction
    fun link() {
        val outputRoot = outputDirectory.get().asFile.canonicalFile
        val out = outputLibrary.get().asFile.canonicalFile
        assertOutputLibraryInsideDirectory(outputRoot, out)
        outputRoot.mkdirs()
        out.parentFile.mkdirs()
        val sources = jniSources.files.filter { it.name.endsWith(".c") }.sortedBy { it.name }
        if (sources.isEmpty()) throw GradleException("no adapter .c sources found for $name")
        // Every directory that holds a source, not just the first one's. The handle table moved to
        // native/kitecodec-handles so the web binding shares it, and `sorted by name`
        // puts kc_handles.c ahead of kj_*.c, so deriving one include dir from the first source
        // would have hidden kj_internal.h from the files that include it.
        val sourceDirs = sources.map { it.parentFile }.distinct()
        val platformHeaderDir = jniPlatformHeader.orNull?.let { header ->
            temporaryDir.resolve("jni-platform").apply {
                deleteRecursively()
                mkdirs()
                resolve("jni_md.h").writeText(header)
            }
        }

        val args = buildList {
            add(compiler.get())
            add("-shared")
            add("-fPIC")
            add("-fvisibility=hidden")
            add("-O2")
            add("-Wall"); add("-Wextra"); add("-Werror")
            sourceDirs.forEach { add("-I"); add(it.absolutePath) }
            add("-I"); add(opaqueIncludeDir.get().asFile.absolutePath)
            platformHeaderDir?.let { add("-I"); add(it.absolutePath) }
            extraIncludeDirs.get().forEach { add("-I"); add(it) }
            sources.forEach { add(it.absolutePath) }
            helperArchive.files.forEach { add(it.absolutePath) }
            add("-L"); add(ffmpegLibDir.get().asFile.absolutePath)
            libSearchDirs.get().forEach { add("-L"); add(it) }
            addAll(linkFlags.get())
            addAll(exportControlArguments(exportControlKind.get(), exportControlFile.get().asFile))
            add("-o"); add(out.absolutePath)
        }
        logger.lifecycle("linking ${out.name} with ${sources.size} adapter units")
        val result = execOperations.exec { commandLine(args) }
        result.assertNormalExitValue()
        if (!out.isFile) throw GradleException("link reported success but ${out.absolutePath} does not exist")
    }

    enum class ExportControlKind {
        ELF_VERSION_SCRIPT,
        MACHO_EXPORTED_SYMBOLS,
        /** A Windows module-definition file, which lld reads as an ordinary input. */
        PE_MODULE_DEFINITION,
    }

    data class AndroidAbiRecipe(
        val linkTaskName: String,
        val helperTaskName: String,
        val ffmpegDirName: String,
        val konanTargetName: String,
        val ndkTarget: String,
        val abiDirectory: String,
        val outputRelativePath: String,
        /**
         * Whether the library is aligned to 16 KiB pages. Android requires that of 64-bit
         * libraries only. A 32-bit ARM device runs 4 KiB pages, so that library keeps the
         * linker's own 4 KiB alignment rather than paying the padding for nothing.
         */
        val sixteenKibPages: Boolean,
    )

    /** One desktop JVM platform whose JNI library the jar carries beside the macOS one. */
    data class DesktopJniRecipe(
        /** The jar resource directory `JniLibrary.jvm.kt` reads, such as `linux-x64`. */
        val platformDirectory: String,
        /** The vendored FFmpeg tree under `native-libs/lgpl/`. */
        val ffmpegDirName: String,
        val konanTargetName: String,
        /** The end of this platform's task names, such as `LinuxX64`. */
        val taskSuffix: String,
        val libraryFileName: String,
        /** The Gradle property that turns this library on. */
        val switchProperty: String,
    ) {
        val isWindows: Boolean get() = konanTargetName == "mingw_x64"
    }

    companion object {
        /**
         * The Android arms, one per ABI the AAR carries. The ordinary Android KMP target does not
         * register an `androidNative*` target, so each recipe names its own dedicated
         * opaque-helper producer. `armeabi-v7a` is here for the streaming sticks and budget
         * television boxes, which are 32-bit only.
         */
        val ANDROID_ABI_RECIPES: List<AndroidAbiRecipe> = listOf(
            AndroidAbiRecipe(
                linkTaskName = "linkKiteFFmpegJniAndroidArm64",
                helperTaskName = "compileKiteFFmpegCForJniAndroidArm64",
                ffmpegDirName = "android-arm64",
                konanTargetName = "android_arm64",
                ndkTarget = "aarch64-linux-android24",
                abiDirectory = "arm64-v8a",
                outputRelativePath = "kitecodec-jni/android-arm64/arm64-v8a/libkitecodec_jni.so",
                sixteenKibPages = true,
            ),
            AndroidAbiRecipe(
                linkTaskName = "linkKiteFFmpegJniAndroidArm32",
                helperTaskName = "compileKiteFFmpegCForJniAndroidArm32",
                ffmpegDirName = "android-arm32",
                konanTargetName = "android_arm32",
                ndkTarget = "armv7a-linux-androideabi24",
                abiDirectory = "armeabi-v7a",
                outputRelativePath = "kitecodec-jni/android-arm32/armeabi-v7a/libkitecodec_jni.so",
                sixteenKibPages = false,
            ),
            AndroidAbiRecipe(
                linkTaskName = "linkKiteFFmpegJniAndroidX64",
                helperTaskName = "compileKiteFFmpegCForJniAndroidX64",
                ffmpegDirName = "android-x64",
                konanTargetName = "android_x64",
                ndkTarget = "x86_64-linux-android24",
                abiDirectory = "x86_64",
                outputRelativePath = "kitecodec-jni/android-x64/x86_64/libkitecodec_jni.so",
                sixteenKibPages = true,
            ),
        )

        /** The exact S1.c.1 Android link recipe after the objects and opaque helper archive.
         *  [dav1d] follows the tree-presence truth: true exactly when the vendored tree
         *  bundles libdav1d.a (the D-7 switch), which libavcodec then draws symbols from. */
        fun androidLinkFlags(recipe: AndroidAbiRecipe, dav1d: Boolean = false): List<String> = listOf(
            "--target=${recipe.ndkTarget}",
            "-lavformat", "-lavcodec", "-lavfilter", "-lavutil", "-lswscale", "-lswresample",
        ) + (if (dav1d) listOf("-ldav1d") else emptyList()) + listOf(
            "-lmediandk", "-landroid", "-llog", "-lz", "-ldl", "-lm",
            "-Wl,-z,defs", "-Wl,-z,noexecstack", "-Wl,-z,relro", "-Wl,-z,now",
            "-Wl,--gc-sections", "-Wl,--exclude-libs,ALL",
        ) + if (recipe.sixteenKibPages) {
            listOf("-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384")
        } else {
            emptyList()
        }

        /** The Gradle property that turns on the two Linux JNI libraries. */
        const val LINUX_SWITCH: String = "kiteffmpeg.jni.linux"

        /** The Gradle property that turns on the Windows JNI library. */
        const val WINDOWS_SWITCH: String = "kiteffmpeg.jni.windows"

        /**
         * The JVM platforms besides macOS arm64 whose JNI library the jar carries. Each is
         * cross-linked with konan's toolchain ([konanJniLinkTools]), which works from any host,
         * so the publish workflow links all three on its macOS runner.
         */
        val DESKTOP_JNI_RECIPES: List<DesktopJniRecipe> = listOf(
            DesktopJniRecipe(
                platformDirectory = "linux-arm64",
                ffmpegDirName = "linux-arm64",
                konanTargetName = "linux_arm64",
                taskSuffix = "LinuxArm64",
                libraryFileName = "libkitecodec_jni.so",
                switchProperty = LINUX_SWITCH,
            ),
            DesktopJniRecipe(
                platformDirectory = "linux-x64",
                ffmpegDirName = "linux-x64",
                konanTargetName = "linux_x64",
                taskSuffix = "LinuxX64",
                libraryFileName = "libkitecodec_jni.so",
                switchProperty = LINUX_SWITCH,
            ),
            DesktopJniRecipe(
                platformDirectory = "windows-x64",
                ffmpegDirName = "mingw-x64",
                konanTargetName = "mingw_x64",
                taskSuffix = "MingwX64",
                libraryFileName = "kitecodec_jni.dll",
                switchProperty = WINDOWS_SWITCH,
            ),
        )

        /**
         * The link flags after the objects for a desktop cross link: konan's [toolchainFlags],
         * FFmpeg's archives, dav1d when the tree bundles it, then what those archives need from
         * the platform. The platform libraries are the ones the tree's own pkg-config files name.
         *
         * On ELF, `--no-undefined` makes a missing archive a link error, because `-shared` would
         * otherwise leave the symbol for the loader to miss on a user's machine. On Windows,
         * `-Bstatic` takes every library below from its static archive: the MinGW sysroot also
         * ships DLL import libraries for zlib, iconv and winpthreads, and a DLL that imports them
         * does not load on a machine without MSYS2. The Windows system libraries are import stubs
         * either way.
         */
        fun desktopLinkFlags(recipe: DesktopJniRecipe, toolchainFlags: List<String>, dav1d: Boolean): List<String> {
            val ffmpeg = listOf("-lavformat", "-lavcodec", "-lavfilter", "-lavutil", "-lswscale", "-lswresample") +
                if (dav1d) listOf("-ldav1d") else emptyList()
            return if (recipe.isWindows) {
                toolchainFlags + "-Wl,-Bstatic" + ffmpeg + listOf(
                    "-liconv", "-lz", "-lm", "-lpthread",
                    "-lole32", "-luser32", "-lws2_32", "-lbcrypt",
                    "-static-libgcc",
                )
            } else {
                toolchainFlags + ffmpeg + listOf("-lz", "-lm", "-ldl", "-lpthread", "-Wl,--no-undefined")
            }
        }

        /**
         * `jni_md.h` for [recipe]'s platform: three macros and three typedefs, with the values the
         * JDK's own header for that platform uses. `jni.h` is identical on every platform and comes
         * from the building JDK, so a cross link needs no JDK, and no container, for its target.
         *
         * On Windows `JNIEXPORT` is empty rather than `__declspec(dllexport)`. The adapter marks
         * every native method with it, and `dllexport` would export all of them, so the
         * module-definition file exports `JNI_OnLoad` instead. The DLL still exports the C
         * archive's own entry points, which that archive marks `dllexport` on Windows. A DLL's
         * exports cannot collide with another library's on Windows, so this costs nothing, but it
         * is why only the ELF and Mach-O libraries export exactly `JNI_OnLoad`.
         */
        fun jniPlatformHeader(recipe: DesktopJniRecipe): String {
            val body = if (recipe.isWindows) {
                """
                #define JNIEXPORT
                #define JNIIMPORT __declspec(dllimport)
                #define JNICALL __stdcall
                typedef long jint;
                typedef long long jlong;
                typedef signed char jbyte;
                """
            } else {
                """
                #define JNIEXPORT __attribute__((visibility("default")))
                #define JNIIMPORT __attribute__((visibility("default")))
                #define JNICALL
                typedef int jint;
                #ifdef _LP64
                typedef long jlong;
                #else
                typedef long long jlong;
                #endif
                typedef signed char jbyte;
                """
            }.trimIndent()
            return "/* Written by the KiteFFmpeg build for a ${recipe.platformDirectory} JNI link. */\n" +
                "#ifndef _JAVASOFT_JNI_MD_H_\n#define _JAVASOFT_JNI_MD_H_\n" +
                body + "\n#endif\n"
        }

        fun exportControlArguments(kind: ExportControlKind, file: File): List<String> = when (kind) {
            ExportControlKind.ELF_VERSION_SCRIPT ->
                listOf("-Wl,--version-script=${file.absolutePath}")
            ExportControlKind.MACHO_EXPORTED_SYMBOLS ->
                listOf("-Wl,-exported_symbols_list,${file.absolutePath}")
            ExportControlKind.PE_MODULE_DEFINITION -> listOf(file.absolutePath)
        }

        internal fun assertOutputLibraryInsideDirectory(outputDirectory: File, outputLibrary: File) {
            val directoryPath = outputDirectory.canonicalFile.toPath()
            val libraryPath = outputLibrary.canonicalFile.toPath()
            if (libraryPath == directoryPath || !libraryPath.startsWith(directoryPath)) {
                throw GradleException(
                    "output library ${outputLibrary.absolutePath} must be inside output directory " +
                        outputDirectory.absolutePath,
                )
            }
        }
    }
}
