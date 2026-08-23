package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

const val IOS_GPL_REFUSAL =
    "iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL."

const val LGPL_ONLY_REFUSAL =
    "GPL refusal: KiteCodec builds and publishes the LGPL flavour only (owner decision " +
        "2026-08-21). A GPL tree is a consumer-built FFmpegSource.Local tree, never this task's."

private val TargetTriple.isIos: Boolean
    get() = this == TargetTriple.IosArm64 ||
        this == TargetTriple.IosSimulatorArm64 ||
        this == TargetTriple.IosX64

/** Debian's multiarch tuple for a Linux target, the directory component both lookups below need. */
private fun multiarchTuple(target: TargetTriple): String =
    if (target == TargetTriple.LinuxArm64) "aarch64-linux-gnu" else "x86_64-linux-gnu"

/**
 * Where to look for libav* headers on Linux, in order.
 *
 * The multiarch directory comes FIRST and its absence was a real defect: Debian and Ubuntu install
 * `libavformat-dev` headers to `/usr/include/<tuple>/libavformat/`, not `/usr/include/libavformat/`.
 * The lib lookup had always known this; the include lookup had not, so a correctly installed apt
 * FFmpeg resolved to nothing, cinterop was skipped for the host target, and every native file then
 * failed with `Unresolved reference 'ffmpeg'`.
 */
internal fun linuxIncludeCandidates(target: TargetTriple): List<String> =
    listOf("/usr/include/${multiarchTuple(target)}", "/usr/include", "/usr/local/include")

/** Same ordering rule, and for the same reason: /usr/lib may hold a foreign-architecture copy. */
internal fun linuxLibCandidates(target: TargetTriple): List<String> =
    listOf("/usr/lib/${multiarchTuple(target)}", "/usr/lib", "/usr/local/lib")

/** The header trees FFmpeg installs. A distribution that omits one simply has no such directory. */
internal val FFMPEG_HEADER_TREES = listOf(
    "libavcodec", "libavdevice", "libavfilter", "libavformat",
    "libavutil", "libswresample", "libswscale",
)

/**
 * Copies ONLY the FFmpeg header trees out of [systemInclude] into [destination].
 *
 * **Why this exists.** On Debian and Ubuntu the libav* headers share a directory with glibc's:
 * `/usr/include/<tuple>` holds `libavformat/` and `sys/cdefs.h` alike. Every consumer of a resolved
 * include directory hands it to clang with `-I`, and there are two of them, the C helper task and
 * the cinterop, which is why fixing one still left CI red. Those clang invocations target konan's
 * own sysroot, so the host's glibc headers won over the sysroot's and the compile collapsed with
 * `function-like macro '__glibc_clang_prereq' is not defined`.
 *
 * Pointing everything at a directory that contains nothing but FFmpeg removes the collision at its
 * source instead of ordering flags around it, and needs no change in any consumer.
 */
internal fun stageFFmpegHeaders(systemInclude: File, destination: File) {
    destination.mkdirs()
    FFMPEG_HEADER_TREES.forEach { tree ->
        val source = systemInclude.resolve(tree)
        if (!source.isDirectory) return@forEach
        val target = destination.resolve(tree)
        target.deleteRecursively()
        source.copyRecursively(target, overwrite = true)
    }
}

/**
 * Resolves where libav* headers and link libraries live for a given Kotlin/Native target.
 *
 * Two resolution modes:
 *
 *   - **System / Homebrew (default)**: for developer machines, just use whatever the OS package
 *     manager dropped on the box. Fastest path to a working build; users of the resulting library
 *     need their own FFmpeg installed.
 *
 *   - **Vendored static**: for releases. We expect a directory tree like
 *     `<repoRoot>/native-libs/<license>/<targetTriple>/{include,lib}` populated by the
 *     `:buildFFmpegFor<Target>` tasks (and desktop-only `Gpl` variants; see `BuildFFmpegTask.kt`).
 *     iOS supports only the LGPL standard software-playback profile. The resulting binaries fully
 *     embed FFmpeg. The `<license>` segment (`lgpl` / `gpl`) keeps the two flavours from colliding.
 *
 * Layered: vendored static wins if present; otherwise fall back to the system install.
 */
data class FFmpegPaths(
    val includeDir: String,
    val libDir: String,
    val isStaticVendored: Boolean,
) {
    companion object {
        fun resolve(
            project: Project,
            target: TargetTriple,
            license: FFmpegLicense = FFmpegLicense.LGPL,
        ): FFmpegPaths {
            if (target.isIos && license == FFmpegLicense.GPL) {
                throw GradleException(IOS_GPL_REFUSAL)
            }
            val vendored = project.rootDir.resolve("native-libs/${license.dirName}/${target.dirName}")
            if (vendored.resolve("include").isDirectory && vendored.resolve("lib").isDirectory) {
                return FFmpegPaths(
                    includeDir = vendored.resolve("include").absolutePath,
                    libDir = vendored.resolve("lib").absolutePath,
                    isStaticVendored = true,
                )
            }
            val host = hostTriple()
            return resolveSystem(project, target)
                ?: throw GradleException(
                    buildString {
                        append("No FFmpeg install found for $target (${license.dirName}). ")
                        append("Run :buildFFmpegFor${target.gradleSuffix}${license.taskSuffix} to vendor a ")
                        append("static build into native-libs/${license.dirName}/${target.dirName}/")
                        if (target == host) {
                            append(", or install FFmpeg system-wide (brew install ffmpeg / apt install the ")
                            append("libav*-dev packages).")
                        } else {
                            append(". A system FFmpeg cannot be used here: this host is ")
                            append("${host ?: "an unrecognised platform"}, so its libraries are built for ")
                            append("the wrong architecture. Only a vendored cross-build is valid for $target.")
                        }
                    },
                )
        }

        /**
         * A system FFmpeg is only ever valid for the HOST's own target.
         *
         * The paths below (`/opt/homebrew`, `/usr/lib/x86_64-linux-gnu`, …) are matched by
         * existence, not by architecture, so without this gate `resolve(project, MacosX64)` on an
         * Apple-silicon Mac happily returns the arm64 Homebrew prefix and `resolve(project,
         * LinuxArm64)` on an x64 box returns the x86_64 libraries: cinterop then parses headers
         * for one architecture and the linker is handed archives for another. Cross targets have
         * exactly one correct answer: a vendored build under `native-libs/`.
         */
        private fun hostTriple(): TargetTriple? {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val arch = System.getProperty("os.arch").orEmpty().lowercase()
            val isArm64 = arch in setOf("aarch64", "arm64")
            val isX64 = arch in setOf("amd64", "x86_64")
            return when {
                "mac" in os && isArm64 -> TargetTriple.MacosArm64
                "mac" in os && isX64 -> TargetTriple.MacosX64
                "linux" in os && isX64 -> TargetTriple.LinuxX64
                "linux" in os && isArm64 -> TargetTriple.LinuxArm64
                else -> null
            }
        }

        private fun resolveSystem(project: Project, target: TargetTriple): FFmpegPaths? {
            // iOS / mingw / Android never have a system install; every other target only counts
            // when it IS this host (see hostTriple).
            if (target != hostTriple()) return null
            return when (target) {
                TargetTriple.MacosArm64, TargetTriple.MacosX64 -> {
                    val configured = project.providers.gradleProperty("kitecodec.macos.homebrew.prefix").orNull
                    val prefixCandidates = listOfNotNull(configured, "/opt/homebrew", "/usr/local")
                    val prefix = prefixCandidates.firstOrNull { File("$it/include/libavformat/avformat.h").exists() }
                        ?: return null
                    FFmpegPaths("$prefix/include", "$prefix/lib", isStaticVendored = false)
                }
                TargetTriple.LinuxX64, TargetTriple.LinuxArm64 -> {
                    val include = linuxIncludeCandidates(target)
                        .firstOrNull { File("$it/libavformat/avformat.h").exists() } ?: return null
                    val lib = linuxLibCandidates(target)
                        .firstOrNull { File("$it/libavformat.so").exists() } ?: return null
                    // Never hand out the system include directory itself: on multiarch it carries
                    // glibc's headers beside FFmpeg's, and konan cross-compiles against its own
                    // sysroot. See stageFFmpegHeaders. macOS is left alone deliberately: Homebrew's
                    // prefix has no competing libc headers and that path is green.
                    val staged = project.layout.buildDirectory.get().asFile
                        .resolve("ffmpeg-system-headers/${target.dirName}")
                    stageFFmpegHeaders(File(include), staged)
                    FFmpegPaths(staged.absolutePath, lib, isStaticVendored = false)
                }
                else -> null
            }
        }
    }
}

/**
 * Which FFmpeg license profile a vendored build was produced under.
 *
 *   - [LGPL] is the default: no `--enable-gpl`, no x264 / x265. Desktop builds include their
 *     permissive encoder/text stack, Android uses MediaCodec, and iOS uses the standard software
 *     playback core plus SDK zlib. Safe for the App Store and closed-source distribution.
 *   - [GPL] is desktop-only and adds libx264 / libx265 for quality-focused software encode.
 *     Open-source / server use only; it makes the linked binary GPL. iOS rejects it.
 *
 * The [dirName] segment keeps the two flavours apart under `native-libs/`; [taskSuffix] disambiguates
 * the desktop `:buildFFmpegFor<Target>Gpl` Gradle tasks.
 */
enum class FFmpegLicense(val dirName: String, val taskSuffix: String) {
    LGPL("lgpl", ""),
    GPL("gpl", "Gpl"),
}

enum class TargetTriple(val dirName: String, val gradleSuffix: String) {
    MacosArm64("macos-arm64", "MacosArm64"),
    MacosX64("macos-x64", "MacosX64"),
    IosArm64("ios-arm64", "IosArm64"),
    IosSimulatorArm64("ios-simulator-arm64", "IosSimulatorArm64"),
    IosX64("ios-x64", "IosX64"),
    LinuxX64("linux-x64", "LinuxX64"),
    LinuxArm64("linux-arm64", "LinuxArm64"),
    MingwX64("mingw-x64", "MingwX64"),
    AndroidArm64("android-arm64", "AndroidArm64"),
    AndroidArm32("android-arm32", "AndroidArm32"),
    AndroidX64("android-x64", "AndroidX64"),
    ;

    val isAndroid: Boolean get() = this == AndroidArm64 || this == AndroidArm32 || this == AndroidX64

    /**
     * Linux and Windows: desktop targets with no cross-built third-party stack.
     *
     * They get the reduced profile of KPKMP.md 17.13's decision W-D4. The full desktop profile
     * demands x264, svt-av1, opus, libass and six more libraries that have never been cross-built
     * for these triples, and building nine dependencies three ways is not what phase W buys. The
     * reduced profile is the 17.6 `standard` tier and plays the whole 17.5 matrix; a consumer who
     * wants the GPL stack builds it through the plugin, which is what the plugin is for.
     */
    val isPortableDesktop: Boolean get() = this == LinuxX64 || this == LinuxArm64 || this == MingwX64

    /** How Kotlin/Native spells this target, which is how the C tasks key their sysroots. */
    val konanTargetName: String get() = dirName.replace('-', '_')
}
