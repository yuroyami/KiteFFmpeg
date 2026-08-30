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
 * Cross-compiles dav1d (VideoLAN's SIMD AV1 software decoder) as a static library for one
 * [target] and installs it into `native-libs/deps/<target>/{include,lib}` where
 * [BuildFFmpegTask] picks it up when its dav1d switch is on (ACCEPTED,
 * demand-driven, optional).
 *
 * Needs meson, ninja and (for x86 asm) nasm on the host, and a dav1d source checkout at
 * [sourceDir]:  `git clone --depth 1 --branch <ref> https://code.videolan.org/videolan/dav1d
 * vendor/dav1d`. Like the FFmpeg task, the checkout is tracked by [sourceRef], not content.
 */
abstract class BuildDav1dTask : DefaultTask() {

    @get:Input
    abstract val target: Property<TargetTriple>

    /** The dav1d tag the [sourceDir] checkout is pinned to, an input so bumping it rebuilds. */
    @get:Input
    abstract val sourceRef: Property<String>

    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kiteffmpeg"
        description = "Cross-compile dav1d as a static library for the given target."
    }

    /**
     * The repository root, captured at CONFIGURATION time purely so the error message below can
     * print a relative clone path.
     *
     * It used to read `project.rootDir` inside the task action, which the configuration cache
     * forbids outright. Nothing caught it because nothing ever ran this task in CI: it failed on
     * the first run that did, taking both iOS dav1d flavours with it.
     */
    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @TaskAction
    fun run() {
        val target = target.get()
        val source = sourceDir.get().asFile
        val output = outputDir.get().asFile
        require(source.resolve("meson.build").isFile) {
            "dav1d source not found at $source. Run:\n" +
                "  git clone --depth 1 --branch ${sourceRef.get()} " +
                "https://code.videolan.org/videolan/dav1d " +
                source.relativeTo(repoRoot.get().asFile)
        }
        val meson = which("meson") ?: throw GradleException("meson not found. brew install meson ninja nasm")
        val ninja = which("ninja") ?: throw GradleException("ninja not found. brew install ninja")

        val scratch = Files.createTempDirectory("kiteffmpeg-dav1d").toFile()
        try {
            val build = scratch.resolve("build")
            val install = scratch.resolve("install")
            val setup = mutableListOf(
                meson, "setup", build.absolutePath, source.absolutePath,
                "--prefix", install.absolutePath,
                "--libdir", "lib",
                "--buildtype", "release",
                "--default-library", "static",
                "-Denable_tools=false", "-Denable_tests=false", "-Denable_examples=false",
            )
            crossFileFor(target, scratch)?.let { setup += listOf("--cross-file", it.absolutePath) }
            runIn(source, setup)
            runIn(build, listOf(ninja))
            runIn(build, listOf(ninja, "install"))

            check(install.resolve("lib/libdav1d.a").isFile) {
                "meson install produced no lib/libdav1d.a under $install"
            }
            output.deleteRecursively()
            output.mkdirs()
            install.resolve("include").copyRecursively(output.resolve("include"), overwrite = true)
            install.resolve("lib").copyRecursively(output.resolve("lib"), overwrite = true)
            // meson bakes the SCRATCH prefix into the installed .pc; without this rewrite,
            // pkg-config resolves the package to a directory that no longer exists and FFmpeg's
            // configure compile-probe dies on the missing dav1d headers. pcfiledir rather
            // than the absolute path, because this repo lives under '#Kite' and pkg-config
            // treats '#' as a comment start with no escape (the same character that banned
            // make from build-host.sh).
            val pc = output.resolve("lib/pkgconfig/dav1d.pc")
            check(pc.isFile) { "meson install produced no dav1d.pc under $output" }
            pc.writeText(
                pc.readText().replace(Regex("^prefix=.*$", RegexOption.MULTILINE)) { "prefix=\${pcfiledir}/../.." },
            )
            logger.lifecycle("[KiteFFmpeg] dav1d ${sourceRef.get()} (${target.dirName}) installed into $output")
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** Null means a native host build (macOS arm64 on this machine). */
    private fun crossFileFor(target: TargetTriple, scratch: File): File? {
        val text = when (target) {
            TargetTriple.MacosArm64 -> return null
            // Cross to Intel macOS from this arm64 host: same clang, -arch does the aiming. The
            // x86 asm needs nasm, which the tool preflight above already demands.
            TargetTriple.MacosX64 ->
                """
                [binaries]
                c = 'clang'
                ar = 'ar'
                strip = 'strip'

                [built-in options]
                c_args = ['-arch', 'x86_64']
                c_link_args = ['-arch', 'x86_64']

                [host_machine]
                system = 'darwin'
                cpu_family = 'x86_64'
                cpu = 'x86_64'
                endian = 'little'
                """.trimIndent()
            TargetTriple.AndroidArm64, TargetTriple.AndroidArm32, TargetTriple.AndroidX64 -> {
                val (cpuFamily, cpu, ccPrefix) = when (target) {
                    TargetTriple.AndroidArm64 -> Triple("aarch64", "aarch64", "aarch64-linux-android")
                    // meson spells 32-bit ARM 'arm'; dav1d's NEON asm keys off it.
                    TargetTriple.AndroidArm32 -> Triple("arm", "armv7a", "armv7a-linux-androideabi")
                    else -> Triple("x86_64", "x86_64", "x86_64-linux-android")
                }
                val bin = ndkToolchainBin()
                val cc = bin.resolve("$ccPrefix${BuildFFmpegTask.ANDROID_API}-clang")
                require(cc.exists()) { "NDK compiler not found: $cc" }
                """
                [binaries]
                c = '${cc.absolutePath}'
                ar = '${bin.resolve("llvm-ar").absolutePath}'
                strip = '${bin.resolve("llvm-strip").absolutePath}'

                [host_machine]
                system = 'android'
                cpu_family = '$cpuFamily'
                cpu = '$cpu'
                endian = 'little'
                """.trimIndent()
            }
            TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64, TargetTriple.IosX64 -> {
                val sdkName = if (target == TargetTriple.IosArm64) "iphoneos" else "iphonesimulator"
                val minFlag = if (target == TargetTriple.IosArm64) "-mios-version-min=14.0" else "-mios-simulator-version-min=14.0"
                val (arch, cpuFamily) = if (target == TargetTriple.IosX64) "x86_64" to "x86_64" else "arm64" to "aarch64"
                val sdk = xcrunSdkPath(sdkName)
                """
                [binaries]
                c = 'clang'
                ar = 'ar'
                strip = 'strip'

                [built-in options]
                c_args = ['-arch', '$arch', '-isysroot', '$sdk', '$minFlag']
                c_link_args = ['-arch', '$arch', '-isysroot', '$sdk', '$minFlag']

                [host_machine]
                system = 'darwin'
                cpu_family = '$cpuFamily'
                cpu = '$cpuFamily'
                endian = 'little'
                """.trimIndent()
            }
            // Linux and Windows cross-build with the SAME konan toolchain FFmpeg uses for them
            // (decision W-D3): konan's clang aimed by -target over the sysroot konan ships. Anything
            // else can reference a glibc symbol that sysroot lacks, and the failure would land at
            // link time in a consumer's build. The link args carry -B and the gcc runtime because
            // meson LINKS a sanity program before it believes the compiler works, which is more
            // than dav1d's own static archive would ever need.
            TargetTriple.LinuxX64, TargetTriple.LinuxArm64, TargetTriple.MingwX64 -> {
                val konan = KonanCross.resolve(target) { logger.lifecycle("[KiteFFmpeg dav1d] $it") }
                val cpuFamily = when (target) {
                    TargetTriple.LinuxArm64 -> "aarch64"
                    else -> "x86_64"
                }
                val system = if (target == TargetTriple.MingwX64) "windows" else "linux"
                // meson probes the linker with `-Wl,--version` before it will believe the
                // compiler works, so the link flags matter even though dav1d is a static archive.
                val compileArgs = konan.compileArgs(target)
                val linkArgs = konan.linkArgs(target)
                """
                [binaries]
                c = '${konan.clang}'
                ar = '${konan.ar}'
                strip = 'true'

                [built-in options]
                c_args = [${compileArgs.joinToString { "'$it'" }}]
                c_link_args = [${linkArgs.joinToString { "'$it'" }}]

                [host_machine]
                system = '$system'
                cpu_family = '$cpuFamily'
                cpu = '$cpuFamily'
                endian = 'little'
                """.trimIndent()
            }
            else -> throw GradleException(
                "BuildDav1dTask has no cross file for $target. Supported: $SUPPORTED_TARGETS. " +
                    "wasm32 is deliberately absent and is not a TargetTriple: dav1d needs pthreads " +
                    "(its meson.build takes dependency('threads') on every non-Windows host), while " +
                    "the shipped wasm profile is the single-threaded 'base' variant.",
            )
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

    private fun runIn(workDir: File, command: List<String>) {
        logger.lifecycle("[KiteFFmpeg dav1d] " + command.joinToString(" "))
        val proc = ProcessBuilder(command).directory(workDir).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { logger.lifecycle("  $it") } }
        val code = proc.waitFor()
        check(code == 0) { "Command exited with $code: ${command.joinToString(" ")}" }
    }

    companion object {
        /** The dav1d release this repo builds by default; mpv-android ships the same series. */
        const val DEFAULT_SOURCE_REF = "1.5.4"

        /**
         * Every target this task can write a cross file for, and therefore every target
         * `:kiteffmpeg-core` may register a `buildDav1dFor<Target>` task for.
         *
         * ONE list, read by both the registration in `kiteffmpeg-core/build.gradle.kts` and the
         * refusal above, because the two drifting apart produces the worst shape of this bug: a
         * task that exists, runs, and dies on "needs its cross file written first".
         *
         * wasm32 is absent by nature rather than by omission: it is not a [TargetTriple] at all,
         * and dav1d could not join the shipped wasm profile anyway (see the refusal message).
         */
        val SUPPORTED_TARGETS: Set<TargetTriple> = TargetTriple.entries.toSet()
    }
}
