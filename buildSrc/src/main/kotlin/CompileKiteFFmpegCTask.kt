package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Compiles every `.c` file under `native/kitecodec-c/src` into one static archive per Kotlin/Native
 * target and leaves it at `<outputDir>/libkitecodec.a`, where the `ffmpeg` cinterop picks it up
 * through the `staticLibraries = libkitecodec.a` line of `ffmpeg.def` plus a `-libraryPath`
 * pointing here.
 *
 * **Why this task exists at all.** Until B1.3 the FFmpeg helper layer was 949 lines of
 * `static inline` C inside `ffmpeg.def`. Text in a def file has no translation unit, so it had no
 * object file, no sanitizer run, no coverage and no test other than whatever Kotlin happened to
 * call (register item B1-01). Compiling it here gives it all of those and gives the helpers real
 * external linkage, which is what the metadata differential of
 * `native/kitecodec-c/scripts/klib-metadata-diff.sh` measures as one added
 * `@kotlinx/cinterop/internal/CCall.Direct` annotation per helper.
 *
 * **Why the compiler is konan's and not Apple's.** The archive is embedded in a klib and is linked
 * into whatever the consumer builds by Kotlin/Native's own linker. Using the compiler
 * Kotlin/Native itself uses, `<konan data>/dependencies/<llvm package>/bin/clang`, keeps the object
 * format, the target triples and the runtime assumptions identical to everything else in that link.
 * Apple clang is the right choice for the host test binaries of `scripts/build-host.sh` and the
 * wrong one here.
 *
 * **Why no make, no cmake and no ninja** (register item B1-15): cmake is not installed on the
 * proving machine, and GNU make starts a comment at an unescaped `#` while this repository lives
 * under a path containing `#Kite`. Driving clang and `llvm-ar` directly is the only form that is
 * both available and safe under this path.
 *
 * **Two properties of this implementation are load bearing rather than incidental.**
 *
 *  - [outputDir] is keyed by the konan target name and is never shared between targets, and the
 *    task refuses to run when the directory it was handed is not named after its own target. That
 *    is register item B1-11: a wrong-architecture archive is embedded by cinterop without a word of
 *    complaint and fails only at the consumer's final link, with
 *    `ld: archive member '/' not a mach-o file`. The producer must catch it, so every object is run
 *    past [verifyObjectArchitecture] before it is archived.
 *  - `xcrun` runs inside [compile] and never at configuration time. This project has the
 *    configuration cache on (`gradle.properties`), and starting an external process while
 *    configuring is one of the things it rejects outright.
 */
abstract class CompileKiteFFmpegCTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /**
     * The konan target name, spelled the way Kotlin/Native spells it: `macos_arm64`, `linux_x64`,
     * `android_arm64` and so on. Chooses the triple and the sysroot, and must equal the name of
     * [outputDir].
     */
    @get:Input
    abstract val konanTargetName: Property<String>

    /** `native/kitecodec-c/src`, holding one or more `.c` files. All of them are compiled. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    /** `native/kitecodec-c/include`, holding `kitecodec_helpers.h`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDir: DirectoryProperty

    /**
     * The FFmpeg include directories for this target, the same ones the cinterop gets from
     * [FFmpegPaths]. The helper units include 16 libav headers, so they cannot compile without
     * them, and they must be the target's own headers rather than the host's.
     */
    @get:Input
    abstract val ffmpegIncludeDirs: ListProperty<String>

    /**
     * The header files whose CONTENT the archive freezes: the six libraries' version headers.
     * [ffmpegIncludeDirs] above is deliberately a plain `@Input` over the path STRINGS, because
     * hashing an entire FFmpeg include tree per target per build would be the wrong trade; but a
     * path string does not change when `brew upgrade ffmpeg` rewrites what it points at, and that
     * gap was measured at byte level (interlude item I-07): editing LIBAVUTIL_VERSION_MICRO inside
     * the tree left this task UP-TO-DATE while cinterop re-executed, and the archive kept its old
     * frozen expectation, one byte different from a forced recompile's truth. These files are what
     * the frozen `LIB*_VERSION_INT` macros and the identity report actually read, so their content
     * is a real input; a listed file that does not exist (a library without `version_major.h`)
     * contributes nothing and is fine. `klib-metadata-diff.sh`'s two-bakings assertion is the
     * backstop that reads both halves of the built klib and refuses a disagreement.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val ffmpegVersionHeaders: ConfigurableFileCollection

    /**
     * Preprocessor defines describing what this archive was built for, passed as `-DNAME="value"`.
     *
     * Three of them today, all read by `src/kitecodec_abi.c` and reported by the FFmpeg identity gate
     * of register item B1-02: `KC_BUILD_FFMPEG_REF`, `KC_BUILD_FFMPEG_LICENSE` and
     * `KC_BUILD_FFMPEG_DIR`. They are reported and never compared, because the comparison the gate
     * makes is between the header macros and the runtime; these describe the provisioning decision the
     * build took, which is what turns a rejection into an actionable sentence and what makes register
     * item B1-21's contradiction visible (the build declares a licence flavour, the linked runtime
     * answers with another, and both strings ride in the report).
     *
     * An `@Input` and not a hardcoded string, so changing the FFmpeg ref or the licence flavour
     * rebuilds every archive. Emitted in sorted key order so the command line is deterministic and the
     * up-to-date check does not flap on map iteration order.
     */
    @get:Input
    abstract val buildDefines: MapProperty<String, String>

    /**
     * The konan data directory, `~/.konan` unless `KONAN_DATA_DIR` says otherwise. Deliberately
     * [Internal]: declaring it an input directory would hash an entire LLVM distribution on every
     * build. What is tracked instead is [llvmPackageName].
     */
    @get:Internal
    abstract val konanDataDir: DirectoryProperty

    /**
     * The LLVM package under `<konan data>/dependencies` that supplies `clang` and `llvm-ar`. An
     * input, so bumping the Kotlin version, and with it the compiler, rebuilds every archive.
     *
     * When the named package is absent, [resolveLlvmBinDir] falls back to the newest LLVM package
     * present and logs which one it chose, so a CI host that ships a different one still builds.
     */
    @get:Input
    abstract val llvmPackageName: Property<String>

    /**
     * Where `libkitecodec.a` and the objects behind it land. Its last path segment must be
     * [konanTargetName]; see the class note on register item B1-11.
     */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kiteffmpeg"
        description = "Compile the FFmpeg helper layer into a static archive for one Kotlin/Native target."
        llvmPackageName.convention(DEFAULT_LLVM_PACKAGE)
    }

    @TaskAction
    fun compile() {
        val target = konanTargetName.get()
        val spec = specFor(target)
        val out = outputDir.get().asFile
        if (out.name != target) {
            throw GradleException(
                "The C archive output directory must be named after its konan target and shared " +
                    "with no other target (register item B1-11): target '$target' was handed " +
                    "'${out.absolutePath}', whose name is '${out.name}'.",
            )
        }

        val dependencies = konanDataDir.get().asFile.resolve("dependencies")
        val llvmBin = resolveLlvmBinDir(dependencies, llvmPackageName.get()) { message ->
            logger.lifecycle("[KiteFFmpeg] $message")
        }
        val clang = resolveTool(llvmBin, "clang") ?: throw GradleException(
            "Cannot compile the FFmpeg helper layer for '$target': no clang (or clang.exe) under " +
                "${llvmBin.absolutePath}. It arrives with the Kotlin/Native distribution, so a " +
                "Gradle build that has already compiled Kotlin/Native code has it.",
        )
        val archiver = resolveTool(llvmBin, "llvm-ar") ?: throw GradleException(
            "Cannot compile the FFmpeg helper layer for '$target': no llvm-ar (or llvm-ar.exe) " +
                "under ${llvmBin.absolutePath}.",
        )

        val sysrootArgs = when {
            spec.appleSdk != null -> listOf("-isysroot", xcrunSdkPath(spec.appleSdk))
            spec.konanSysroot != null -> {
                val sysroot = dependencies.resolve(spec.konanSysroot)
                if (!sysroot.isDirectory) {
                    throw GradleException(
                        "Cannot compile the FFmpeg helper layer for '$target': no sysroot at " +
                            "${sysroot.absolutePath}. It is part of the konan dependency package " +
                            "'${spec.konanSysroot.substringBefore('/')}'.",
                    )
                }
                listOf("--sysroot=${sysroot.absolutePath}")
            }
            else -> emptyList()
        }

        val sources = sourceDir.get().asFile.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "c" }
            .sortedBy { it.name }
        if (sources.isEmpty()) {
            throw GradleException("No .c sources under ${sourceDir.get().asFile.absolutePath}.")
        }

        // A stale object from a previous run must never end up in the archive, and `llvm-ar crs`
        // updates an existing archive in place rather than replacing it, so both are cleared first.
        val objectDir = out.resolve("obj")
        objectDir.deleteRecursively()
        objectDir.mkdirs()
        val archive = out.resolve(ARCHIVE_NAME)
        archive.delete()

        val includeArgs = includeArguments(
            ownInclude = includeDir.get().asFile.absolutePath,
            ffmpegIncludes = ffmpegIncludeDirs.get(),
        )
        // Written next to the objects and force-included, rather than passed as -D. See
        // buildDefinesHeader: -D cannot carry a quoted string or a Windows path safely.
        val definesHeader = out.resolve("kitecodec_build_defines.h")
        definesHeader.writeText(buildDefinesHeader(buildDefines.get()))
        val defineArgs = listOf("-include", definesHeader.absolutePath)

        logger.lifecycle(
            "[KiteFFmpeg] compiling ${sources.size} C source(s) for $target " +
                "(${spec.triple}) with ${clang.absolutePath}",
        )

        val objects = sources.map { source ->
            val objectFile = objectDir.resolve("${source.nameWithoutExtension}.o")
            val command = listOf(clang.absolutePath, "-target", spec.triple) +
                sysrootArgs + COMPILER_FLAGS + defineArgs + includeArgs +
                listOf("-c", source.absolutePath, "-o", objectFile.absolutePath)
            logger.info("[KiteFFmpeg] " + command.joinToString(" "))
            execOperations.exec {
                commandLine(command)
            }
            verifyObjectArchitecture(target, objectFile, describeFile(objectFile))
            objectFile
        }

        execOperations.exec {
            commandLine(listOf(archiver.absolutePath, "crs", archive.absolutePath) + objects.map { it.absolutePath })
        }
        if (!archive.isFile) {
            throw GradleException("llvm-ar reported success but produced no ${archive.absolutePath}.")
        }
        logger.lifecycle(
            "[KiteFFmpeg] $target: ${archive.absolutePath} " +
                "(${archive.length()} bytes, ${objects.size} object(s), ${describeFile(archive)})",
        )
    }

    /**
     * `file -b <path>`, which is how the architecture of an object or an archive is read.
     * Protected and open since the interlude (I-10), for exactly one caller: the call-site test.
     * The review measured that deleting the verifyObjectArchitecture call from [compile] left the
     * whole suite green, because every case exercised the predicate directly and none proved the
     * task action invokes it. A test subclass overrides this to describe a wrong architecture,
     * and compile() must then throw; delete the call site and that test fails.
     */
    protected open fun describeFile(file: File): String {
        val stdout = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(FILE_TOOL, "-b", file.absolutePath)
            standardOutput = stdout
        }
        return stdout.toString().trim()
    }

    /**
     * Resolves an Apple SDK sysroot. Called from the task action and never from configuration: the
     * configuration cache rejects `Starting an external process 'xcrun ...' during configuration
     * time`, which is exactly what the ad-hoc `tasks.register { doLast { } }` shape produced.
     */
    private fun xcrunSdkPath(sdkName: String): String {
        val stdout = ByteArrayOutputStream()
        execOperations.exec {
            commandLine("xcrun", "--sdk", sdkName, "--show-sdk-path")
            standardOutput = stdout
        }
        val path = stdout.toString().trim()
        if (path.isEmpty() || !File(path).isDirectory) {
            throw GradleException("xcrun --sdk $sdkName --show-sdk-path returned '$path', which is not a directory.")
        }
        return path
    }

    /**
     * How one konan target is spelled to clang: its [triple] plus exactly one sysroot, either an
     * Apple SDK resolved through `xcrun` ([appleSdk]) or a path inside
     * `<konan data>/dependencies` ([konanSysroot]).
     */
    data class CTargetSpec(
        val triple: String,
        val appleSdk: String? = null,
        val konanSysroot: String? = null,
    )

    companion object {

        /** The archive name `ffmpeg.def`'s `staticLibraries` line asks cinterop for. */
        const val ARCHIVE_NAME: String = "libkitecodec.a"

        /** The three defines of [buildDefines], named here so the build script cannot misspell one. */
        const val DEFINE_FFMPEG_REF: String = "KC_BUILD_FFMPEG_REF"
        const val DEFINE_FFMPEG_LICENSE: String = "KC_BUILD_FFMPEG_LICENSE"
        const val DEFINE_FFMPEG_DIR: String = "KC_BUILD_FFMPEG_DIR"

        /**
         * Turns [defines] into `-DNAME="value"` arguments, sorted by name.
         *
         * The value is wrapped in C string quotes here rather than by the caller, because every one of
         * these is read as a `const char *` in `src/kitecodec_abi.c` and a define whose value is not a
         * string literal would compile to an identifier the unit has never heard of. No shell is
         * involved: `ExecOperations.exec` passes argv straight through, so the quotes are literal
         * characters clang sees and not something a shell would strip.
         *
         * Pure, so [CompileKiteFFmpegCTaskTest] can assert the shape without running a compile.
         */
        /**
         * The build metadata as a C header, one `#define` per entry, values as escaped literals.
         *
         * **Why a header and not `-D`.** `-DNAME="value"` is broken on Windows twice over. Java's
         * process launcher does not preserve the inner quotes, so clang received
         * `-DKC_BUILD_FFMPEG_REF=n8.0` and reported `use of undeclared identifier 'n8'`. And even
         * with quotes intact, the provisioning directory there is a path like `D:\a\KiteFFmpeg`,
         * whose backslashes are escape sequences inside a C string literal (`\a` is a bell).
         *
         * Writing a header and passing it with `-include` removes command-line quoting from the
         * problem entirely, and lets the values be escaped properly for C. `kitecodec_abi.c` guards
         * each name with `#ifndef`, so `-include` arriving first simply wins and the "unknown"
         * fallbacks stay for anyone compiling the sources by hand.
         *
         * Sorted, so the file is byte-stable and does not flap the up-to-date check.
         */
        fun buildDefinesHeader(defines: Map<String, String>): String = buildString {
            appendLine("/* Generated by CompileKiteFFmpegCTask. Do not edit. */")
            appendLine("#pragma once")
            defines.entries.sortedBy { it.key }.forEach { (name, value) ->
                val literal = value.replace("\\", "\\\\").replace("\"", "\\\"")
                appendLine("#define $name \"$literal\"")
            }
        }

        fun defineArguments(defines: Map<String, String>): List<String> =
            defines.entries
                .sortedBy { it.key }
                .map { (name, value) -> "-D$name=\"$value\"" }

        /**
         * The compiler Kotlin/Native itself uses on an arm64 Mac, per konan.properties. Overridable
         * through [llvmPackageName] and, failing that, through [resolveLlvmBinDir]'s fallback.
         */
        /**
         * The include flags for one compile: ours with `-I`, FFmpeg's with `-idirafter`.
         *
         * **The distinction is load bearing on Linux.** Debian and Ubuntu put the libav* headers in
         * the MULTIARCH system include directory, `/usr/include/<tuple>`, in the same directory as
         * `sys/cdefs.h`. This task cross-compiles against konan's own sysroot, so passing that
         * directory as `-I` puts the HOST's glibc ahead of the sysroot's; konan's clang then read a
         * glibc 2.39 `sys/cdefs.h` against a glibc 2.19 sysroot and emitted about two hundred errors
         * beginning with `function-like macro '__glibc_clang_prereq' is not defined`.
         *
         * `-idirafter` is searched AFTER the system directories, which is exactly the semantics
         * wanted: the sysroot wins every header it has, and `libavformat/avformat.h`, which no
         * sysroot has, still resolves. It is applied to vendored trees too, and safely, because a
         * vendored include directory holds nothing but `libav*` and `libsw*` subdirectories and so
         * has nothing that could shadow a system header either way.
         *
         * Our own include directory keeps `-I`: those headers are the project's and must win.
         */
        fun includeArguments(ownInclude: String, ffmpegIncludes: List<String>): List<String> =
            listOf("-I$ownInclude") + ffmpegIncludes.map { "-idirafter$it" }

        const val DEFAULT_LLVM_PACKAGE: String = "llvm-21-aarch64-macos-essentials-97"

        /** `file(1)`, which reads the architecture of an object file or an archive. */
        private val FILE_TOOL: String =
            if (File("/usr/bin/file").canExecute()) "/usr/bin/file" else "file"

        /**
         * The flag set, fixed rather than configurable.
         *
         *  - `-fvisibility=hidden` keeps the helpers out of a consumer binary's dynamic symbol
         *    table. The static linker still resolves them inside the one link that embeds the
         *    archive, which is the only link there is.
         *  - `-fPIC` because the archive can end up inside a shared library or a framework.
         *  - `-Werror` with `-Wall -Wextra` is what makes the header the proof of the sources: the
         *    `.c` includes its own generated header, so a declaration that does not match its
         *    definition is a hard failure here rather than a warning nobody reads.
         *  - `-Werror=vla` because a variable-length array on a real-time or callback path is a
         *    stack overflow waiting for a large input, and the helper layer has 18 snprintf sites
         *    over fixed buffers precisely to avoid needing one.
         */
        val COMPILER_FLAGS: List<String> = listOf(
            "-O2", "-std=c11",
            "-fvisibility=hidden", "-fPIC",
            "-Wall", "-Wextra", "-Werror", "-Werror=vla",
        )

        /**
         * Triple and sysroot per konan target.
         *
         * The five in the plan (macos_arm64, ios_arm64, linux_x64, android_arm64, mingw_x64) were
         * measured working from this macOS host, and the six siblings follow the same rule; all
         * eleven were confirmed to compile a translation unit here. The android trap is worth
         * naming: the sysroot has to come from the `target-toolchain-*-android_ndk` package, not
         * from `target-sysroot-*-android_ndk`, whose `--sysroot` fails with
         * `'stdlib.h' file not found`.
         *
         * Compiling is level 7 evidence in the terms of plan section 2 and says nothing about
         * behaviour on any of these targets.
         */
        fun specFor(konanTargetName: String): CTargetSpec = when (konanTargetName) {
            // SOL-B4: one floor for the whole product, defined in BuildFFmpegTask. These read it
            // rather than restating it, because a hand-synced number is what this row was about.
            "macos_arm64" -> CTargetSpec(
                "arm64-apple-macos${BuildFFmpegTask.MACOS_DEPLOYMENT_TARGET}",
                appleSdk = "macosx",
            )
            "macos_x64" -> CTargetSpec(
                "x86_64-apple-macos${BuildFFmpegTask.MACOS_DEPLOYMENT_TARGET}",
                appleSdk = "macosx",
            )
            "ios_arm64" -> CTargetSpec("arm64-apple-ios14.0", appleSdk = "iphoneos")
            "ios_simulator_arm64" -> CTargetSpec("arm64-apple-ios14.0-simulator", appleSdk = "iphonesimulator")
            "ios_x64" -> CTargetSpec("x86_64-apple-ios14.0-simulator", appleSdk = "iphonesimulator")
            "linux_x64" -> CTargetSpec(
                "x86_64-unknown-linux-gnu",
                konanSysroot = "x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2/" +
                    "x86_64-unknown-linux-gnu/sysroot",
            )
            "linux_arm64" -> CTargetSpec(
                "aarch64-unknown-linux-gnu",
                konanSysroot = "aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25-kernel-4.9-2/" +
                    "aarch64-unknown-linux-gnu/sysroot",
            )
            "android_arm64" -> CTargetSpec(
                "aarch64-unknown-linux-android24",
                konanSysroot = androidToolchainSysroot(),
            )
            "android_arm32" -> CTargetSpec(
                "armv7a-unknown-linux-androideabi24",
                konanSysroot = androidToolchainSysroot(),
            )
            "android_x64" -> CTargetSpec(
                "x86_64-unknown-linux-android24",
                konanSysroot = androidToolchainSysroot(),
            )
            "mingw_x64" -> CTargetSpec(
                "x86_64-pc-windows-gnu",
                konanSysroot = "msys2-mingw-w64-x86_64-2",
            )
            else -> throw GradleException(
                "No C compilation triple is known for konan target '$konanTargetName'. Add one to " +
                    "CompileKiteFFmpegCTask.specFor together with the sysroot it needs.",
            )
        }

        /**
         * The Android NDK sysroot, from the toolchain package. Using `target-sysroot-1-android_ndk`
         * instead fails with `'stdlib.h' file not found`.
         */
        /**
         * Resolves a konan LLVM tool by its bare name and then by its `.exe` name (interlude item
         * I-20): a Windows konan package ships `clang.exe`, so `File("bin/clang").canExecute()`
         * is false there and every candidate used to be rejected. Null when neither exists.
         */
        internal fun resolveTool(binDir: File, name: String): File? =
            listOf(binDir.resolve(name), binDir.resolve("$name.exe")).firstOrNull { it.canExecute() }

                /**
         * The konan HOST infix, the word konan itself uses to name per-host dependency packages:
         * the authoritative konan.properties reads `targetToolchain.linux_x64-android_arm64 =
         * target-toolchain-2-linux-android_ndk` and `targetToolchain.mingw_x64-... =
         * target-toolchain-2-windows-...` beside the osx one. Hardcoding `osx` here was interlude
         * item I-20: on an Ubuntu or Windows runner the osx package never exists, so the C compile
         * threw before cinterop and four CI jobs could not pass. Parameterised on the os.name so a
         * test can drive every host shape from one machine.
         */
        internal fun konanHostInfix(osName: String = System.getProperty("os.name").orEmpty()): String = when {
            osName.startsWith("Mac") || osName.startsWith("Darwin") -> "osx"
            osName.startsWith("Windows") -> "windows"
            else -> "linux"
        }

        /** The Android NDK sysroot inside the konan dependencies tree, named after the BUILD host. */
        internal fun androidToolchainSysroot(osName: String = System.getProperty("os.name").orEmpty()): String =
            "target-toolchain-2-${konanHostInfix(osName)}-android_ndk/sysroot"

        /**
         * What `file -b` must report, as a prefix, for an object built for [konanTargetName]. All
         * eleven strings were measured on this host by compiling one translation unit per target.
         *
         * `file` reads the architecture and not the platform, so macos_arm64, ios_arm64 and
         * ios_simulator_arm64 share a string. That is the right scope: register item B1-11 is about
         * an archive of the wrong ARCHITECTURE reaching a target, which is what was measured to
         * pass silently through cinterop and fail at the consumer's link. The platform is fixed by
         * the triple and the sysroot, and cross-target mixing is prevented by keying [outputDir] on
         * the target name.
         */
        fun acceptedObjectDescriptions(konanTargetName: String): List<String> = when (konanTargetName) {
            "macos_arm64", "ios_arm64", "ios_simulator_arm64" -> listOf("Mach-O 64-bit object arm64")
            "macos_x64", "ios_x64" -> listOf("Mach-O 64-bit object x86_64")
            "linux_x64", "android_x64" -> listOf("ELF 64-bit LSB relocatable, x86-64")
            "linux_arm64", "android_arm64" -> listOf("ELF 64-bit LSB relocatable, ARM aarch64")
            "android_arm32" -> listOf("ELF 32-bit LSB relocatable, ARM, EABI5")
            // TWO spellings of one architecture, and the list is the point. `file` renamed this
            // between releases: the Windows runner says "x86-64 COFF object file" where an older
            // one said "Intel amd64 COFF object file". The object was right and the build failed on
            // a string. This guard is about the ARCHITECTURE (register item B1-11), so it accepts
            // every spelling of the right one and no spelling of a wrong one.
            "mingw_x64" -> listOf("Intel amd64 COFF object file", "x86-64 COFF object file")
            else -> throw GradleException(
                "No expected object architecture is known for konan target '$konanTargetName'. Add " +
                    "one to CompileKiteFFmpegCTask.acceptedObjectDescriptions.",
            )
        }

        /** The canonical spelling, used in messages. The check itself uses the full accepted list. */
        fun expectedObjectDescription(konanTargetName: String): String =
            acceptedObjectDescriptions(konanTargetName).first()

        /**
         * Fails when [fileOutput], the `file -b` description of [objectFile], is not what
         * [konanTargetName] must produce. Pure on purpose, so the test can hand it a real object of
         * the wrong architecture and a real `file` output.
         */
        fun verifyObjectArchitecture(konanTargetName: String, objectFile: File, fileOutput: String) {
            val accepted = acceptedObjectDescriptions(konanTargetName)
            if (accepted.any { fileOutput.startsWith(it) }) return
            val expected = accepted.joinToString(" or ") { "'$it'" }
            throw GradleException(
                "Wrong object architecture for konan target '$konanTargetName': " +
                    "${objectFile.absolutePath} is '$fileOutput', expected $expected.\n" +
                    "Archiving it would embed a wrong-architecture library in the klib, which " +
                    "cinterop accepts without complaint and which then fails at the consumer's " +
                    "final link with `ld: archive member '/' not a mach-o file` (register item " +
                    "B1-11). Check the triple and the sysroot in CompileKiteFFmpegCTask.specFor.",
            )
        }

        /**
         * The `bin` directory holding `clang` and `llvm-ar`, inside [dependenciesDir]. Prefers
         * [preferredPackage]; when that is absent it takes the newest LLVM package present, reports
         * the substitution through [log], and lets the build proceed, because a CI host with a
         * different Kotlin/Native distribution ships a differently named one.
         */
        fun resolveLlvmBinDir(
            dependenciesDir: File,
            preferredPackage: String,
            log: (String) -> Unit = {},
        ): File {
            val preferred = dependenciesDir.resolve(preferredPackage)
            if (resolveTool(preferred.resolve("bin"), "clang") != null) return preferred.resolve("bin")

            val alternative = dependenciesDir.listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name.startsWith("llvm-") }
                .filter { resolveTool(it.resolve("bin"), "clang") != null }
                .maxWithOrNull(LLVM_PACKAGE_ORDER)
                ?: throw GradleException(
                    "No LLVM package with a usable clang under ${dependenciesDir.absolutePath}. " +
                        "Expected '$preferredPackage'. It arrives with the Kotlin/Native " +
                        "distribution, so a build that has already compiled Kotlin/Native code " +
                        "has it.",
                )
            log(
                "the konan LLVM package '$preferredPackage' is not installed; " +
                    "compiling the FFmpeg helper layer with '${alternative.name}' instead.",
            )
            return alternative.resolve("bin")
        }

        /**
         * Orders LLVM package directories by the numbers in their names rather than as text, so
         * `llvm-21-aarch64-macos-essentials-97` sorts above `llvm-9-...` and above
         * `llvm-21-...-essentials-79`. Plain string ordering would put `llvm-9` above `llvm-21`.
         */
        private val LLVM_PACKAGE_ORDER: Comparator<File> = Comparator { left, right ->
            val a = llvmPackageNumbers(left.name)
            val b = llvmPackageNumbers(right.name)
            var verdict = 0
            for (index in 0 until maxOf(a.size, b.size)) {
                verdict = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
                if (verdict != 0) break
            }
            if (verdict != 0) verdict else left.name.compareTo(right.name)
        }

        private fun llvmPackageNumbers(name: String): List<Int> =
            Regex("""\d+""").findAll(name).mapNotNull { it.value.toIntOrNull() }.toList()
    }
}
