package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.GradleException
import java.io.File

/**
 * The konan cross toolchain for one desktop [TargetTriple]: clang, its binutils, the triple and
 * the sysroot Kotlin/Native itself links against.
 *
 * Why this is shared rather than per-task: dav1d, the libass chain and FFmpeg all end up inside
 * ONE consumer binary, so all three must be built against the SAME sysroot. A dav1d compiled
 * against a different glibc than libavcodec is a link failure waiting in somebody else's project,
 * and the failure names a symbol rather than the mistake.
 *
 * [BuildFFmpegTask] keeps its own equivalent resolver. It predates this one, carries two extra
 * fields its configure line needs (`nm`, and `ranlib` as a two-word command), and is proven; the
 * values it resolves are identical to these. Change one, read the other.
 */
internal data class KonanCross(
    val clang: String,
    /** `-B<dir>`: where clang finds `ld.lld`. Apple's own `ld` links neither ELF nor PE. */
    val toolchainBin: String,
    /** The gcc runtime directory (crtbegin/crtend, libgcc), which sits OUTSIDE the sysroot. */
    val runtimeDir: String?,
    val ar: String,
    val triple: String,
    val sysroot: String,
    /**
     * libstdc++'s headers, in the order clang wants them, or empty when none were found.
     *
     * Needed because konan's sysroots are C sysroots: Kotlin/Native links C, so nothing in the
     * default include path answers `#include <cassert>`. The headers ARE shipped, just outside the
     * sysroot, the same way the gcc runtime is. Only a C++ dependency in a cross-built chain needs
     * these (harfbuzz is the one that does).
     */
    val cxxIncludeDirs: List<String> = emptyList(),
    /** Where `libstdc++.a` lives, for the link probe meson runs before it trusts the compiler. */
    val cxxLibDir: String? = null,
) {
    /** Aiming flags only, valid for either language. */
    private fun aimArgs(): List<String> = listOf("-target", triple, "--sysroot=$sysroot")

    /** `-target` and `--sysroot`, plus the C dialect mingw's headers insist on. */
    fun compileArgs(target: TargetTriple): List<String> = aimArgs() +
        listOfNotNull("-std=gnu11".takeIf { target == TargetTriple.MingwX64 })

    /**
     * The compile args plus everything a LINK needs.
     *
     * meson and autoconf both link a probe program before they will believe the compiler works, so
     * these are needed even when the artifact being built is a static archive that never links.
     */
    fun linkArgs(target: TargetTriple): List<String> = compileArgs(target) + listOfNotNull(
        "-fuse-ld=lld",
        "-B$toolchainBin",
        runtimeDir?.let { "-B$it" },
        runtimeDir?.let { "-L$it" },
    )

    /**
     * The C++ twin of [compileArgs]: aiming flags, libstdc++'s headers, and NO `-std=gnu11`.
     *
     * That last part is the whole reason this is a separate function. `-std=gnu11` is a C dialect,
     * and clang++ given it refuses every program it is asked to compile; meson reports that as the
     * memorable-but-unhelpful "Compiler ... cannot compile programs" while setting up harfbuzz, the
     * one C++ member of the chain.
     */
    fun cppCompileArgs(target: TargetTriple): List<String> =
        aimArgs() + cxxIncludeDirs.flatMap { listOf("-isystem", it) }

    /** [cppCompileArgs] plus everything a C++ link probe needs, libstdc++ included. */
    fun cppLinkArgs(target: TargetTriple): List<String> =
        cppCompileArgs(target) + listOfNotNull(
            "-fuse-ld=lld",
            "-B$toolchainBin",
            runtimeDir?.let { "-B$it" },
            runtimeDir?.let { "-L$it" },
            cxxLibDir?.let { "-L$it" },
        )

    companion object {
        fun resolve(target: TargetTriple, log: (String) -> Unit): KonanCross {
            val konanRoot = System.getenv("KONAN_DATA_DIR")?.let(::File)
                ?: File(System.getProperty("user.home"), ".konan")
            val dependencies = konanRoot.resolve("dependencies")
            if (!dependencies.isDirectory) {
                throw GradleException(
                    "No konan dependencies at ${dependencies.absolutePath}. They arrive with the " +
                        "Kotlin/Native distribution, so a build that has already compiled " +
                        "Kotlin/Native code has them.",
                )
            }
            val llvmBin = CompileKiteFFmpegCTask.resolveLlvmBinDir(
                dependencies,
                CompileKiteFFmpegCTask.DEFAULT_LLVM_PACKAGE,
                log,
            )
            fun tool(name: String): String = (CompileKiteFFmpegCTask.resolveTool(llvmBin, name)
                ?: throw GradleException("Cannot cross-build for $target: no $name under $llvmBin"))
                .absolutePath
            val spec = CompileKiteFFmpegCTask.specFor(target.konanTargetName)
            val sysrootRelative = spec.konanSysroot
                ?: throw GradleException("$target has no konan sysroot")
            val sysroot = dependencies.resolve(sysrootRelative)
            if (!sysroot.isDirectory) {
                throw GradleException("Cannot cross-build for $target: no sysroot at $sysroot.")
            }
            // konan's linux packages keep the gcc runtime BESIDE the sysroot rather than inside it,
            // so lld finds neither crtbeginS.o nor libgcc without being pointed at it. The version
            // directory is discovered, never hardcoded: a konan bump changes it.
            val packageRoot = dependencies.resolve(sysrootRelative.substringBefore('/'))
            val runtimeDir = packageRoot.resolve("lib/gcc").listFiles().orEmpty()
                .filter { it.isDirectory }
                .flatMap { it.listFiles().orEmpty().filter { version -> version.isDirectory } }
                .firstOrNull { it.resolve("libgcc.a").isFile }
            // libstdc++'s headers sit at <...>/include/c++/<version>/, with the machine-specific
            // half (bits/c++config.h) one level deeper under the target triple. Discovered from
            // c++config.h rather than composed from a hardcoded version, because the gcc version is
            // the konan package's business and a konan bump moves it.
            val cxxConfig = packageRoot.walkTopDown()
                .maxDepth(8)
                .firstOrNull { it.name == "c++config.h" && it.isFile }
            val cxxIncludeDirs = cxxConfig?.let { config ->
                val machineDir = config.parentFile.parentFile          // <version>/<triple>
                val versionDir = machineDir.parentFile                 // <version>
                listOfNotNull(
                    versionDir.absolutePath,
                    machineDir.absolutePath,
                    versionDir.resolve("backward").takeIf { it.isDirectory }?.absolutePath,
                )
            }.orEmpty()
            val cxxLibDir = sequenceOf(
                packageRoot.resolve("${spec.triple}/lib64"),
                packageRoot.resolve("${spec.triple}/lib"),
                runtimeDir,
            ).filterNotNull().firstOrNull { it.resolve("libstdc++.a").isFile }

            return KonanCross(
                clang = tool("clang"),
                toolchainBin = llvmBin.absolutePath,
                runtimeDir = runtimeDir?.absolutePath,
                ar = tool("llvm-ar"),
                triple = spec.triple,
                sysroot = sysroot.absolutePath,
                cxxIncludeDirs = cxxIncludeDirs,
                cxxLibDir = cxxLibDir?.absolutePath,
            )
        }
    }
}
