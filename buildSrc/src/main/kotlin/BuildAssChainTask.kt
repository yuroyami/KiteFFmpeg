package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files

/**
 * Cross-compiles the libass rendering chain (fribidi, freetype, harfbuzz, libass) as static
 * libraries for one [target] and installs them into `native-libs/deps/<target>/ass-chain/`
 * (KPKMP 17.12 phase L, pulled forward by owner order 2026-08-16). The chain is OPTIONAL by
 * decision D-7: nothing here enters a default artifact; the `kiteplayer-libass` module and the
 * plugin's libass toggle are its only consumers.
 *
 * Fontconfig and libunibreak are deliberately absent from this first chain: libass renders
 * without both (CoreText provides fonts on Apple; Android supplies fonts at runtime through
 * `ass_add_font`, which is how every Android libass consumer works), and each would be its own
 * cross-build with its own maintenance duty.
 *
 * Needs meson, ninja, autotools (libass builds with autoconf) and source checkouts at
 * `vendor/{fribidi,freetype,harfbuzz,libass}`. Builds happen in a scratch directory because
 * this repo lives under `#Kite`, a path pkg-config and autotools cannot be trusted with.
 */
abstract class BuildAssChainTask : DefaultTask() {

    @get:Input
    abstract val target: Property<TargetTriple>

    /** One combined pin: bumping any vendored checkout means bumping this, which rebuilds. */
    @get:Input
    abstract val sourceRefs: Property<String>

    @get:Internal
    abstract val vendorDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kiteffmpeg"
        description = "Cross-compile the libass chain (fribidi, freetype, harfbuzz, libass) for one target."
    }

    @TaskAction
    fun run() {
        val target = target.get()
        val vendor = vendorDir.get().asFile
        val output = outputDir.get().asFile
        listOf("fribidi", "freetype", "harfbuzz", "libass").forEach { name ->
            require(vendor.resolve(name).isDirectory) {
                "missing checkout vendor/$name; clone it first (see BuildAssChainTask's KDoc)"
            }
        }
        val meson = which("meson") ?: throw GradleException("meson not found. brew install meson ninja")
        val ninja = which("ninja") ?: throw GradleException("ninja not found. brew install ninja")

        val scratch = Files.createTempDirectory("kiteffmpeg-asschain").toFile()
        try {
            val install = scratch.resolve("install")
            val pkgconfig = install.resolve("lib/pkgconfig")
            val cross = crossFileFor(target, scratch)
            val env = mutableMapOf("PKG_CONFIG_LIBDIR" to pkgconfig.absolutePath)

            fun mesonBuild(name: String, options: List<String>) {
                val source = scratch.resolve("src-$name")
                copyTreeKeepingExecutableBits(vendor.resolve(name), source)
                val build = scratch.resolve("build-$name")
                val setup = mutableListOf(
                    meson, "setup", build.absolutePath, source.absolutePath,
                    "--prefix", install.absolutePath,
                    "--libdir", "lib",
                    "--buildtype", "release",
                    "--default-library", "static",
                )
                setup.addAll(options)
                // Cross builds ignore PKG_CONFIG_LIBDIR from the environment for host-machine
                // dependencies; the built-in option reaches them either way.
                setup.add("-Dpkg_config_path=${pkgconfig.absolutePath}")
                cross?.let { setup.addAll(listOf("--cross-file", it.absolutePath)) }
                runIn(source, setup, env)
                runIn(build, listOf(ninja), env)
                runIn(build, listOf(ninja, "install"), env)
            }

            // -Dbin=false: fribidi's command-line tools are no use to a static chain anywhere, and
            // on mingw they are actively fatal, failing to link `fribidi.exe` and taking the whole
            // build with them long after libfribidi.a itself was fine.
            mesonBuild("fribidi", listOf("-Ddocs=false", "-Dtests=false", "-Dbin=false"))
            // freetype first WITHOUT harfbuzz: the two reference each other, and libass'
            // shaping needs harfbuzz-with-freetype, not the reverse.
            mesonBuild(
                "freetype",
                listOf(
                    "-Dharfbuzz=disabled", "-Dbrotli=disabled", "-Dbzip2=disabled",
                    "-Dpng=disabled", "-Dtests=disabled",
                    // mingw takes freetype's own bundled zlib. The system one is present in the
                    // msys2 package, but meson resolves it to a WRAPDB SUBPROJECT that ships its
                    // own `zlib1.rc`, which drags a second file through the resource compiler for
                    // no gain. 'internal' is freetype's vendored copy and needs neither.
                    if (target == TargetTriple.MingwX64) "-Dzlib=internal" else "-Dzlib=system",
                ),
            )
            mesonBuild(
                "harfbuzz",
                listOf(
                    "--auto-features=disabled", "-Dfreetype=enabled",
                    "-Dtests=disabled", "-Ddocs=disabled", "-Dutilities=disabled",
                ),
            )

            buildLibass(target, vendor, scratch, install, env)

            // libtool names a Windows static archive `libass.lib`; the meson members of the chain
            // produce `.a` on every target including this one. The extension is convention and the
            // bytes are an ordinary ar archive, so normalising here keeps ONE filename downstream:
            // the check below, `-lass`, and kiteplayer-libass' link line all stay target-agnostic.
            if (target == TargetTriple.MingwX64) {
                val windowsName = install.resolve("lib/libass.lib")
                val unixName = install.resolve("lib/libass.a")
                if (windowsName.isFile && !unixName.isFile) {
                    check(windowsName.renameTo(unixName)) { "could not rename $windowsName to $unixName" }
                }
            }
            listOf("libfribidi.a", "libfreetype.a", "libharfbuzz.a", "libass.a").forEach { archive ->
                check(install.resolve("lib/$archive").isFile) { "the chain build produced no lib/$archive" }
            }
            output.deleteRecursively()
            output.mkdirs()
            install.resolve("include").copyRecursively(output.resolve("include"), overwrite = true)
            install.resolve("lib").copyRecursively(output.resolve("lib"), overwrite = true)
            // Every installed .pc gets the pcfiledir prefix: the '#' in this repo's path has no
            // pkg-config escape (the dav1d task documents the same rewrite).
            output.resolve("lib/pkgconfig").listFiles { f: File -> f.extension == "pc" }?.forEach { pc ->
                pc.writeText(
                    pc.readText().replace(Regex("^prefix=.*$", RegexOption.MULTILINE)) { "prefix=\${pcfiledir}/../.." },
                )
            }
            logger.lifecycle("[KiteFFmpeg] ass chain (${sourceRefs.get()}) for ${target.dirName} installed into $output")
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun buildLibass(
        target: TargetTriple,
        vendor: File,
        scratch: File,
        install: File,
        env: Map<String, String>,
    ) {
        val source = scratch.resolve("src-libass")
        copyTreeKeepingExecutableBits(vendor.resolve("libass"), source)
        val fullEnv = env + toolchainEnv(target, scratch)
        runIn(source, listOf("autoreconf", "-ivf"), fullEnv)
        val configure = mutableListOf(
            source.resolve("configure").absolutePath,
            "--prefix=${install.absolutePath}",
            "--enable-static", "--disable-shared",
            "--disable-fontconfig",
        )
        if (target.isAndroid || target.isPortableDesktop) {
            // No system font provider is reachable here. On Android fonts arrive through
            // ass_add_font, and the Linux/Windows chain deliberately carries no fontconfig
            // (see the class KDoc), so the same escape hatch applies for the same reason.
            configure += "--disable-require-system-font-provider"
        }
        hostTripleFor(target)?.let { configure += "--host=$it" }
        val build = scratch.resolve("build-libass").also(File::mkdirs)
        runIn(build, configure, fullEnv)
        runIn(build, listOf("make", "-j${Runtime.getRuntime().availableProcessors()}"), fullEnv)
        runIn(build, listOf("make", "install"), fullEnv)
    }

    /**
     * The one define that lets harfbuzz compile against konan's msys2 package.
     *
     * `hb.hh` reaches for `<intrin.h>` on MinGW unless `__MINGW32_VERSION` is defined, in which
     * case it sets WIN32_LEAN_AND_MEAN and skips the header entirely. That header is the problem:
     * konan ships a 2019-era msys2, clang 21 declares the SSE3 intrinsics constexpr, and msys2's
     * `intrin.h` redeclares them non-constexpr, which ends every harfbuzz translation unit with
     * "non-constexpr declaration of '_mm_movedup_pd' follows constexpr declaration". Only C++ is
     * affected, which is why FFmpeg and dav1d cross-build to Windows without noticing.
     *
     * Defining it is safe to the letter: `__MINGW32_VERSION` appears in exactly ONE place in all
     * of harfbuzz, the branch above, so this buys the WIN32_LEAN_AND_MEAN path and nothing else.
     * The alternative was a newer mingw sysroot, which decision W-D3 exists to forbid.
     */
    private fun mingwCppDefines(target: TargetTriple): List<String> =
        if (target == TargetTriple.MingwX64) listOf("-D__MINGW32_VERSION=1") else emptyList()

    /**
     * llvm-ar, pinned to the GNU archive format, for mingw only.
     *
     * Left to itself on an arm64 macOS host, llvm-ar wrote freetype's archive with an ARM64EC
     * symbol index ("Archive EC map" rather than "Archive map"), which lld does not consult when
     * linking x86-64 mingw. That produces the worst shape of link error: libfreetype.a sits on the
     * command line, no library is reported missing, and every FT_* symbol is undefined anyway.
     * Only freetype tripped it; the flag covers the whole chain because the other members already
     * produce exactly what it asks for.
     */
    private fun gnuArWrapper(scratch: File, realAr: String): String {
        val script = scratch.resolve("ar-gnu-format.sh")
        script.writeText("#!/bin/sh\nexec \"" + realAr + "\" --format=gnu \"$@\"\n")
        script.setExecutable(true)
        return script.absolutePath
    }

    /**
     * The resource compiler behind a two-line script that hands it the mingw headers.
     *
     * `windows.compile_resources()` passes meson's own arguments and nothing of `c_args`, so the
     * resource compiler runs its preprocessor with no include path and dies on `windows.h`. Meson
     * offers no cross-file knob for this, so the include path is baked into the binary it is told
     * to use, which is a wrapper rather than a lie about what the compiler is.
     */
    private fun windresWrapper(scratch: File, sysroot: String): String {
        val real = windresOrThrow()
        // clang's mingw driver finds the Windows headers on its own; windres does not. The
        // directory is SEARCHED rather than composed from the triple, because the two spellings
        // disagree: clang calls this target `x86_64-pc-windows-gnu` while the msys2 package lays
        // its headers out under the GNU spelling `x86_64-w64-mingw32`.
        val root = File(sysroot)
        val includeDir = sequenceOf(root.resolve("include"))
            .plus(root.listFiles().orEmpty().asSequence().filter { it.isDirectory }.map { it.resolve("include") })
            .firstOrNull { it.resolve("windows.h").isFile }
            ?: throw GradleException("No windows.h under $sysroot; cannot preprocess resources.")
        val script = scratch.resolve("windres-with-includes.sh")
        script.writeText(
            "#!/bin/sh\nexec \"$real\" --include-dir \"${includeDir.absolutePath}\" \"$@\"\n",
        )
        script.setExecutable(true)
        return script.absolutePath
    }

    /**
     * A GNU-windres-compatible resource compiler, for the one target that needs one.
     *
     * konan's LLVM is the "essentials" set and ships none, and the msys2 package's own binaries are
     * Windows PE and cannot run on this host. `llvm-windres` from an Android NDK is the copy most
     * likely to already exist on a machine that builds this project; it is a thin GNU-compatible
     * wrapper over llvm-rc, which is exactly the interface meson drives.
     */
    private fun windresOrThrow(): String {
        which("llvm-windres")?.let { return it }
        which("windres")?.let { return it }
        val candidates = listOfNotNull(
            runCatching { ndkToolchainBin() }.getOrNull(),
            File("/opt/homebrew/opt/llvm/bin"),
            File("/usr/local/opt/llvm/bin"),
        )
        candidates.forEach { dir ->
            listOf("llvm-windres", "windres").forEach { name ->
                dir.resolve(name).takeIf { it.canExecute() }?.let { return it.absolutePath }
            }
        }
        throw GradleException(
            "No Windows resource compiler found, and mingw-x64 needs one: freetype compiles " +
                "ftver.rc for every windows host. Put `llvm-windres` on PATH (an Android NDK's " +
                "llvm toolchain ships one, so does Homebrew's llvm formula).",
        )
    }

    /**
     * Copies a checkout into the scratch tree WITH its executable bits intact.
     *
     * `File.copyRecursively` drops permissions, which stays invisible until a build actually
     * executes one of the copied files. libass is where it surfaces: its x86 assembly path runs
     * `ltnasm.sh` through libtool and dies with "Permission denied". Nothing caught this before
     * because no x86 chain target had ever been built here; the Apple and Android arm64 targets
     * never enter `libass/x86` at all.
     */
    private fun copyTreeKeepingExecutableBits(from: File, to: File) {
        from.copyRecursively(to, overwrite = true)
        from.walkTopDown().forEach { original ->
            if (!original.isFile || !original.canExecute()) return@forEach
            to.resolve(original.relativeTo(from).path).setExecutable(true)
        }
    }

    /** CC/CXX/AR/RANLIB for autotools cross builds; empty for the host. */
    private fun toolchainEnv(target: TargetTriple, scratch: File): Map<String, String> = when (target) {
        TargetTriple.MacosArm64 -> emptyMap()
        TargetTriple.AndroidArm64, TargetTriple.AndroidX64 -> {
            val bin = ndkToolchainBin()
            val prefix = if (target == TargetTriple.AndroidArm64) "aarch64-linux-android" else "x86_64-linux-android"
            val cc = bin.resolve("$prefix${BuildFFmpegTask.ANDROID_API}-clang")
            require(cc.exists()) { "NDK compiler not found: $cc" }
            // -fPIC is not optional on Android and the meson members already get it by default
            // (meson's b_staticpic). libass builds through autotools, which does not, and the
            // omission only surfaces at the far end: its archive links fine into a Kotlin/Native
            // executable and then refuses to go into a SHARED library, which is exactly what the
            // JNI adapter needs, with "relocation R_AARCH64_ADR_PREL_PG_HI21 cannot be used
            // against symbol 'font_cache_desc'".
            mapOf(
                "CC" to "${cc.absolutePath} -fPIC",
                "CXX" to "${cc.absolutePath}++ -fPIC",
                "AR" to bin.resolve("llvm-ar").absolutePath,
                "RANLIB" to bin.resolve("llvm-ranlib").absolutePath,
                "STRIP" to bin.resolve("llvm-strip").absolutePath,
            )
        }
        TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64 -> {
            val sdkName = if (target == TargetTriple.IosArm64) "iphoneos" else "iphonesimulator"
            val minFlag = if (target == TargetTriple.IosArm64) "-mios-version-min=14.0" else "-mios-simulator-version-min=14.0"
            val sdk = xcrunSdkPath(sdkName)
            val flags = "-arch arm64 -isysroot $sdk $minFlag"
            mapOf(
                "CC" to "clang $flags",
                "CXX" to "clang++ $flags",
            )
        }
        // Linux and Windows use the SAME konan toolchain FFmpeg and dav1d use for them (W-D3), so
        // libass links against the libc its neighbours in the final binary were built against.
        // -fuse-ld=lld is as mandatory here as everywhere else: autoconf link-probes a program
        // before it believes the compiler exists, and Apple's ld cannot link ELF or PE.
        TargetTriple.LinuxX64, TargetTriple.LinuxArm64, TargetTriple.MingwX64 -> {
            val konan = KonanCross.resolve(target) { logger.lifecycle("[KiteFFmpeg ass-chain] $it") }
            val common = "-target ${konan.triple} --sysroot=${konan.sysroot}" +
                // __USE_MINGW_ANSI_STDIO fixes a cause rather than silencing a symptom. libass
                // prints with the standard PRId64, which mingw expands to "I64d" for the old
                // msvcrt; clang then reports it is not ISO C, and libass compiles that warning
                // with -Werror=format-non-iso of its own choosing, so a correct line fails the
                // build. The define selects mingw-w64's own standards-conforming stdio, where
                // PRId64 is "lld" and there is nothing to warn about. Suppressing the warning
                // instead would not work anyway: libass appends its flags to CFLAGS, so nothing
                // passed through CC can come after them.
                (if (target == TargetTriple.MingwX64) " -std=gnu11 -D__USE_MINGW_ANSI_STDIO=1" else "")
            val link = "-fuse-ld=lld -B${konan.toolchainBin}" +
                (konan.runtimeDir?.let { " -B$it -L$it" } ?: "")
            mapOf(
                "CC" to "${konan.clang} $common $link",
                // The C++ half is harfbuzz's, and it reaches this env only through libass'
                // configure; the chain's own C++ came out of the meson builds above.
                "CXX" to "${konan.clang}++ $common $link",
                "AR" to if (target == TargetTriple.MingwX64) gnuArWrapper(scratch, konan.ar) else konan.ar,
                "RANLIB" to "${konan.ar} s",
            )
        }
        else -> throw GradleException("BuildAssChainTask has no toolchain for $target yet.")
    }

    private fun hostTripleFor(target: TargetTriple): String? = when (target) {
        TargetTriple.MacosArm64 -> null
        TargetTriple.AndroidArm64 -> "aarch64-linux-android"
        TargetTriple.AndroidX64 -> "x86_64-linux-android"
        TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64 -> "arm64-apple-darwin"
        TargetTriple.LinuxX64 -> "x86_64-unknown-linux-gnu"
        TargetTriple.LinuxArm64 -> "aarch64-unknown-linux-gnu"
        TargetTriple.MingwX64 -> "x86_64-w64-mingw32"
        else -> null
    }

    /** Null means a native host build. The same shape the dav1d task generates. */
    private fun crossFileFor(target: TargetTriple, scratch: File): File? {
        val text = when (target) {
            TargetTriple.MacosArm64 -> return null
            TargetTriple.AndroidArm64, TargetTriple.AndroidX64 -> {
                val (cpuFamily, cpu, ccPrefix) = when (target) {
                    TargetTriple.AndroidArm64 -> Triple("aarch64", "aarch64", "aarch64-linux-android")
                    else -> Triple("x86_64", "x86_64", "x86_64-linux-android")
                }
                val bin = ndkToolchainBin()
                val cc = bin.resolve("$ccPrefix${BuildFFmpegTask.ANDROID_API}-clang")
                require(cc.exists()) { "NDK compiler not found: $cc" }
                """
                [binaries]
                c = '${cc.absolutePath}'
                cpp = '${cc.absolutePath}++'
                ar = '${bin.resolve("llvm-ar").absolutePath}'
                strip = '${bin.resolve("llvm-strip").absolutePath}'
                pkg-config = 'pkg-config'

                [host_machine]
                system = 'android'
                cpu_family = '$cpuFamily'
                cpu = '$cpu'
                endian = 'little'
                """.trimIndent()
            }
            TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64 -> {
                val sdkName = if (target == TargetTriple.IosArm64) "iphoneos" else "iphonesimulator"
                val minFlag = if (target == TargetTriple.IosArm64) "-mios-version-min=14.0" else "-mios-simulator-version-min=14.0"
                val sdk = xcrunSdkPath(sdkName)
                """
                [binaries]
                c = 'clang'
                cpp = 'clang++'
                ar = 'ar'
                strip = 'strip'
                pkg-config = 'pkg-config'

                [built-in options]
                c_args = ['-arch', 'arm64', '-isysroot', '$sdk', '$minFlag']
                c_link_args = ['-arch', 'arm64', '-isysroot', '$sdk', '$minFlag']
                cpp_args = ['-arch', 'arm64', '-isysroot', '$sdk', '$minFlag']
                cpp_link_args = ['-arch', 'arm64', '-isysroot', '$sdk', '$minFlag']

                [host_machine]
                system = 'darwin'
                cpu_family = 'aarch64'
                cpu = 'aarch64'
                endian = 'little'
                """.trimIndent()
            }
            TargetTriple.LinuxX64, TargetTriple.LinuxArm64, TargetTriple.MingwX64 -> {
                val konan = KonanCross.resolve(target) { logger.lifecycle("[KiteFFmpeg ass-chain] $it") }
                val cpuFamily = if (target == TargetTriple.LinuxArm64) "aarch64" else "x86_64"
                val system = if (target == TargetTriple.MingwX64) "windows" else "linux"
                val compileArgs = konan.compileArgs(target)
                val linkArgs = konan.linkArgs(target)
                // pkg-config MUST be named here. In a cross build meson resolves host-machine
                // dependencies through a HOST-machine pkg-config, and with none declared it reports
                // "Found pkg-config: NO" and then fails harfbuzz on a freetype2 that was installed
                // into the chain prefix seconds earlier. The Apple and Android cross files get away
                // without it; these do not.
                val pkgConfig = which("pkg-config")
                    ?: throw GradleException("pkg-config not found. brew install pkg-config")
                // A Windows resource compiler is required for mingw and for nothing else: freetype
                // compiles `ftver.rc` whenever the host system is windows, with no option to skip
                // it, even for the static library this chain actually wants.
                val windres = if (target == TargetTriple.MingwX64) {
                    windresWrapper(scratch, konan.sysroot)
                } else {
                    null
                }
                val archiver = if (target == TargetTriple.MingwX64) {
                    gnuArWrapper(scratch, konan.ar)
                } else {
                    konan.ar
                }
                """
                [binaries]
                c = '${konan.clang}'
                cpp = '${konan.clang}++'
                ar = '$archiver'
                strip = 'true'
                pkg-config = '$pkgConfig'
${windres?.let { "                windres = '$it'\n" } ?: ""}
                [built-in options]
                c_args = [${compileArgs.joinToString { "'$it'" }}]
                c_link_args = [${linkArgs.joinToString { "'$it'" }}]
                cpp_args = [${(konan.cppCompileArgs(target) + mingwCppDefines(target)).joinToString { "'$it'" }}]
                cpp_link_args = [${konan.cppLinkArgs(target).joinToString { "'$it'" }}]

                [host_machine]
                system = '$system'
                cpu_family = '$cpuFamily'
                cpu = '$cpuFamily'
                endian = 'little'
                """.trimIndent()
            }
            else -> throw GradleException("BuildAssChainTask has no cross file for $target yet.")
        }
        val file = scratch.resolve("cross-${target.dirName}.txt")
        file.writeText(text)
        return file
    }

    private fun ndkToolchainBin(): File {
        val fromEnv = sequenceOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME")
            .mapNotNull { System.getenv(it) }
            .map(::File)
            .firstOrNull { it.isDirectory }
        val ndk = fromEnv ?: run {
            val sdkNdk = File(System.getProperty("user.home"), "Library/Android/sdk/ndk")
                .takeIf { it.isDirectory }
                ?: File(System.getProperty("user.home"), "Android/Sdk/ndk").takeIf { it.isDirectory }
            sdkNdk?.listFiles { f: File -> f.isDirectory }?.maxByOrNull { it.name }
                ?: throw GradleException("Android NDK not found. Set ANDROID_NDK_HOME or install one via the SDK manager.")
        }
        val prebuilt = ndk.resolve("toolchains/llvm/prebuilt")
        val hostDir = prebuilt.listFiles { f: File -> f.isDirectory }?.firstOrNull()
            ?: throw GradleException("No prebuilt toolchain under $prebuilt")
        return hostDir.resolve("bin")
    }

    private fun xcrunSdkPath(sdkName: String): String {
        val proc = ProcessBuilder("xcrun", "--sdk", sdkName, "--show-sdk-path")
            .redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        check(proc.waitFor() == 0 && output.isNotEmpty()) { "xcrun --sdk $sdkName --show-sdk-path failed: $output" }
        return output
    }

    private fun which(tool: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, tool) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath

    private fun runIn(workDir: File, command: List<String>, env: Map<String, String>) {
        logger.lifecycle("[KiteFFmpeg ass-chain] " + command.joinToString(" "))
        val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
        builder.environment().putAll(env)
        val proc = builder.start()
        proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { logger.lifecycle("  $it") } }
        val code = proc.waitFor()
        check(code == 0) { "Command exited with $code: ${command.joinToString(" ")}" }
    }

    companion object {
        /**
         * Every target this task can cross-build the chain for, and therefore every target
         * `:kiteffmpeg-core` may register a `buildAssChainFor<Target>` task for. One list, read by
         * both the registration and the refusals, so the two can never disagree.
         *
         * wasm32 is absent because it is not a [TargetTriple]. Android is PRESENT here and still
         * absent from `:kiteplayer-libass`: this task produces the chain, and consuming it from
         * Android additionally needs a JNI bridge that does not exist yet.
         */
        val SUPPORTED_TARGETS: Set<TargetTriple> = setOf(
            TargetTriple.MacosArm64,
            TargetTriple.AndroidArm64, TargetTriple.AndroidX64,
            TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64,
            TargetTriple.LinuxX64, TargetTriple.LinuxArm64, TargetTriple.MingwX64,
        )

        /** The chain this repo builds by default; mpv-android ships the same series. */
        const val DEFAULT_SOURCE_REFS = "fribidi-1.0.16 freetype-2.14.3 harfbuzz-14.2.1 libass-0.17.4"
    }
}
