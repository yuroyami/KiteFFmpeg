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
 * Builds the libass chain (fribidi, freetype, harfbuzz, libass) for wasm32 with emscripten and
 * installs it into `native-libs/deps/wasm32/ass-chain/`, the same layout the konan targets get.
 *
 * Its own task rather than a branch of [BuildAssChainTask], for the reason [BuildFFmpegWasmTask]
 * is separate from its sibling: wasm32 is not a [TargetTriple], has no konan sysroot and no
 * archive-format question, and everything it needs is `emcc` on PATH. The chain is single-threaded
 * and carries no SIMD, matching the codec's shipped `base` variant: a module that hangs on an
 * embedder's site without cross-origin isolation is worse than a slower one.
 *
 * No font provider exists here at all: fonts reach the web renderer only as container attachments,
 * application-supplied files, or the script's own `[Fonts]` section, which is what
 * `--disable-require-system-font-provider` lets libass accept.
 */
abstract class BuildAssChainWasmTask : DefaultTask() {

    /** One combined pin: bumping any vendored checkout means bumping this, which rebuilds. */
    @get:Input
    abstract val sourceRefs: Property<String>

    @get:Internal
    abstract val vendorDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kiteffmpeg"
        description = "Cross-compile the libass chain (fribidi, freetype, harfbuzz, libass) for wasm32 with emscripten."
    }

    @TaskAction
    fun run() {
        val vendor = vendorDir.get().asFile
        val output = outputDir.get().asFile
        listOf("fribidi", "freetype", "harfbuzz", "libass").forEach { name ->
            require(vendor.resolve(name).isDirectory) {
                "missing checkout vendor/$name; clone it first (see BuildAssChainTask's KDoc)"
            }
        }
        val emcc = which("emcc") ?: throw GradleException("emcc not found. brew install emscripten")
        val emxx = which("em++") ?: throw GradleException("em++ not found beside emcc")
        val emar = which("emar") ?: throw GradleException("emar not found beside emcc")
        val emranlib = which("emranlib") ?: throw GradleException("emranlib not found beside emcc")
        val meson = which("meson") ?: throw GradleException("meson not found. brew install meson ninja")
        val ninja = which("ninja") ?: throw GradleException("ninja not found. brew install ninja")
        val scratch = Files.createTempDirectory("kiteffmpeg-asschain-wasm").toFile()
        try {
            val install = scratch.resolve("install")
            val pkgconfig = install.resolve("lib/pkgconfig")
            val cross = scratch.resolve("emscripten.cross").apply {
                writeText(
                    """
                    [binaries]
                    c = '$emcc'
                    cpp = '$emxx'
                    ar = '$emar'
                    ranlib = '$emranlib'
                    strip = '${which("emstrip") ?: "emstrip"}'
                    pkg-config = 'pkg-config'

                    [built-in options]
                    c_args = ['-O2']
                    # HarfBuzz promotes its warnings to errors with pragmas in hb.hh, which no command
                    # line flag can undo, and emscripten's clang is newer than the one it was tuned
                    # for: -Wunused-template fires on hb-meta.hh's helper templates. The macro is
                    # HarfBuzz's own switch for exactly this situation.
                    cpp_args = ['-O2', '-DHB_NO_PRAGMA_GCC_DIAGNOSTIC_ERROR']

                    [host_machine]
                    system = 'emscripten'
                    cpu_family = 'wasm32'
                    cpu = 'wasm32'
                    endian = 'little'
                    """.trimIndent() + "\n",
                )
            }
            val env = mapOf("PKG_CONFIG_LIBDIR" to pkgconfig.absolutePath)
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
                    "--cross-file", cross.absolutePath,
                    "-Dpkg_config_path=${pkgconfig.absolutePath}",
                )
                setup.addAll(options)
                runIn(source, setup, env)
                runIn(build, listOf(ninja), env)
                runIn(build, listOf(ninja, "install"), env)
            }
            mesonBuild("fribidi", listOf("-Ddocs=false", "-Dtests=false", "-Dbin=false"))
            // freetype takes its own bundled zlib: emscripten's libc has none, and the port flag
            // would drag a second toolchain feature into a chain that is otherwise plain C.
            mesonBuild(
                "freetype",
                listOf(
                    "-Dharfbuzz=disabled", "-Dbrotli=disabled", "-Dbzip2=disabled",
                    "-Dpng=disabled", "-Dtests=disabled", "-Dzlib=internal",
                ),
            )
            mesonBuild(
                "harfbuzz",
                listOf(
                    "--auto-features=disabled", "-Dfreetype=enabled",
                    "-Dtests=disabled", "-Ddocs=disabled", "-Dutilities=disabled",
                ),
            )

            val source = scratch.resolve("src-libass")
            copyTreeKeepingExecutableBits(vendor.resolve("libass"), source)
            val toolchain = env + mapOf(
                "CC" to emcc, "CXX" to emxx, "AR" to emar, "RANLIB" to emranlib,
                // configure's link probes must not try to produce and run a native binary.
                "LDFLAGS" to "-O2",
            )
            runIn(source, listOf("autoreconf", "-ivf"), toolchain)
            val build = scratch.resolve("build-libass").also(File::mkdirs)
            runIn(
                build,
                listOf(
                    source.resolve("configure").absolutePath,
                    "--prefix=${install.absolutePath}",
                    "--host=wasm32-unknown-emscripten",
                    "--enable-static", "--disable-shared",
                    "--disable-fontconfig", "--disable-require-system-font-provider",
                    "--disable-asm", "--disable-libunibreak",
                ),
                toolchain,
            )
            runIn(build, listOf("make", "-j${Runtime.getRuntime().availableProcessors()}"), toolchain)
            runIn(build, listOf("make", "install"), toolchain)

            listOf("libfribidi.a", "libfreetype.a", "libharfbuzz.a", "libass.a").forEach { archive ->
                check(install.resolve("lib/$archive").isFile) { "the wasm chain build produced no lib/$archive" }
            }
            output.deleteRecursively()
            output.mkdirs()
            install.resolve("include").copyRecursively(output.resolve("include"), overwrite = true)
            install.resolve("lib").copyRecursively(output.resolve("lib"), overwrite = true)
            output.resolve("lib/pkgconfig").listFiles { f: File -> f.extension == "pc" }?.forEach { pc ->
                pc.writeText(
                    pc.readText().replace(Regex("^prefix=.*$", RegexOption.MULTILINE)) { "prefix=\${pcfiledir}/../.." },
                )
            }
            logger.lifecycle("[KiteFFmpeg] ass chain (${sourceRefs.get()}) for wasm32 installed into $output")
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun copyTreeKeepingExecutableBits(from: File, to: File) {
        from.copyRecursively(to, overwrite = true)
        from.walkTopDown().forEach { original ->
            if (!original.isFile || !original.canExecute()) return@forEach
            to.resolve(original.relativeTo(from).path).setExecutable(true)
        }
    }

    private fun which(tool: String): String? =
        ProcessBuilder("which", tool).redirectErrorStream(true).start().let { proc ->
            val path = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() == 0 && path.isNotEmpty()) path else null
        }

    private fun runIn(workDir: File, command: List<String>, env: Map<String, String>) {
        logger.lifecycle("[KiteFFmpeg ass-chain wasm] " + command.joinToString(" "))
        val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
        builder.environment().putAll(env)
        val proc = builder.start()
        proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { logger.lifecycle("  $it") } }
        val code = proc.waitFor()
        check(code == 0) { "Command exited with $code: ${command.joinToString(" ")}" }
    }
}
