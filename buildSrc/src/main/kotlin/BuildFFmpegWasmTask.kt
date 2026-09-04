package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject

/**
 * Cross-compiles FFmpeg to wasm32 with emscripten, for the web player.
 *
 * A separate task from [BuildFFmpegTask] rather than a [TargetTriple] entry, and the reason is not
 * style: konan has no wasm target, so every konan-derived path in that task (sysroots, cross
 * toolchains, the third-party bundling) is inapplicable here, and an enum entry would force each of
 * them to grow a branch that can never run.
 *
 * The configure shape comes from the S6 web spike, which built it five ways and measured the result
 * (`docs/spikes/2026-08-17-web-spike.md` in KitePlayer). Three corrections are folded in here and
 * are worth naming, because each one costs an hour to rediscover:
 *  - `--disable-postproc` does NOT exist on n8.0 and configure fails on it.
 *  - `--disable-asm` silently disables SIMD too, so the SIMD variant must not pass it.
 *  - the spike disabled avfilter, which its bare harness never needed. `libkitecodec.a` DOES need
 *    it: `helpers_filter.c` names `abuffer`, `abuffersink`, `anull`, `buffer` and `buffersink`, and
 *    calls `avfilter_graph_parse_ptr`. A build without it links until the first filter call.
 */
abstract class BuildFFmpegWasmTask @Inject constructor() : DefaultTask() {

    /** The FFmpeg source checkout, `<repoRoot>/vendor/ffmpeg`. Tracked via [sourceRef], not content. */
    @get:Internal
    abstract val sourceDir: DirectoryProperty

    /** The FFmpeg tag the checkout is pinned to, which is what up-to-date checking keys on. */
    @get:Input
    abstract val sourceRef: Property<String>

    /** `base`, `simd` or `mt`. The spike's verdict is that `base` is what v1 ships. */
    @get:Input
    abstract val variant: Property<String>

    /**
     * Emscripten's own llvm `bin`, needed because configure is told `--nm=llvm-nm` and emscripten
     * ships its own. Defaults to the Homebrew layout and is a property so a non-Homebrew emsdk works.
     */
    @get:Input
    abstract val emscriptenLlvmBin: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        // The variant is checked before anything else is read, so a typo names itself instead of
        // surfacing as "property 'sourceDir' has no value", which says nothing about the mistake.
        val variantName = variant.get()
        require(variantName in VARIANTS) { "unknown wasm variant '$variantName', expected one of $VARIANTS" }
        val source = sourceDir.get().asFile
        val install = outputDir.get().asFile
        require(source.exists()) {
            "FFmpeg source not found at $source. Run:\n" +
                "  git clone --depth 1 --branch ${sourceRef.get()} https://github.com/FFmpeg/FFmpeg vendor/ffmpeg"
        }
        val emcc = requireOnPath("emcc")
        logger.lifecycle("[KiteFFmpeg wasm] emcc at $emcc, variant $variantName")

        // A hash in the path breaks FFmpeg's configure, and this repository lives under a '#Kite'
        // directory, so the build happens in scratch and is copied back. Same reason as the sibling
        // task, restated because a reader of only this file would not know.
        // The system temp dir, NOT temporaryDir: Gradle's is inside the project, and this
        // repository lives under a '#Kite' directory. FFmpeg's configure cannot handle a '#'
        // anywhere in its path, so the build has to happen outside the tree entirely.
        val workspace = BuildFFmpegTask.createScratchWorkspace(
            Path.of(System.getProperty("java.io.tmpdir")),
        )
        try {
            val build = workspace.resolve("ffmpeg")
            val prefix = workspace.resolve("install")
            BuildFFmpegTask.copySourceTree(source.toPath(), build)

            val env = mapOf("PATH" to "${emscriptenLlvmBin.get()}:${System.getenv("PATH").orEmpty()}")
            runIn(build.toFile(), listOf("./configure") + configureArgs(variantName, prefix), env)
            runIn(build.toFile(), listOf("make", "-j", Runtime.getRuntime().availableProcessors().toString()), env)
            runIn(build.toFile(), listOf("make", "install"), env)

            BuildFFmpegTask.writeConfigureEvidence(build.resolve("ffbuild/config.log"), prefix)
            verifyWasmInstall(prefix)

            install.deleteRecursively()
            install.parentFile.mkdirs()
            BuildFFmpegTask.copySourceTree(prefix, install.toPath())
        } finally {
            workspace.toFile().deleteRecursively()
        }
        logger.lifecycle("[KiteFFmpeg wasm] installed to $install")
    }

    internal fun configureArgs(variantName: String, prefix: Path): List<String> {
        val common = listOf(
            // emscripten cross-compile
            "--target-os=none", "--arch=wasm32", "--enable-cross-compile",
            "--cc=emcc", "--cxx=em++", "--ar=emar", "--ranlib=emranlib", "--nm=llvm-nm",
            "--disable-stripping", "--disable-programs", "--disable-doc",
            "--enable-static", "--disable-shared",
            "--disable-debug", "--disable-htmlpages", "--disable-manpages",
            "--disable-podpages", "--disable-txtpages",
            "--enable-pic",
            // Start from nothing and add back by name: this is the 17.6 lean web tier.
            "--disable-everything", "--disable-autodetect", "--disable-network",
            "--disable-devices", "--disable-avdevice",
            "--disable-iconv", "--disable-zlib", "--disable-bzlib", "--disable-lzma",
            "--disable-sdl2", "--disable-xlib",
            "--disable-vaapi", "--disable-vdpau", "--disable-videotoolbox", "--disable-audiotoolbox",
            "--disable-runtime-cpudetect",
            "--enable-decoder=$DECODERS",
            "--enable-demuxer=$DEMUXERS",
            "--enable-parser=$PARSERS",
            "--enable-bsf=$BSFS",
            // avfilter, which the spike did not need and libkitecodec.a does. See the class KDoc.
            "--enable-avfilter",
            "--enable-filter=$FILTERS",
            "--enable-protocol=file",
            "--prefix=$prefix",
        )
        val perVariant = when (variantName) {
            "simd" -> listOf("--disable-pthreads", "--disable-w32threads", "--disable-os2threads")
            "mt" -> listOf("--disable-asm", "--enable-pthreads")
            else -> listOf("--disable-asm", "--disable-pthreads", "--disable-w32threads", "--disable-os2threads")
        }
        val flags = when (variantName) {
            "simd" -> "-O3 -msimd128"
            "mt" -> "-O3 -pthread"
            else -> "-O3"
        }
        return common + perVariant + listOf("--extra-cflags=$flags", "--extra-ldflags=$flags")
    }

    /**
     * The six archives must all exist, and headers with them.
     *
     * Deliberately NOT reusing [BuildFFmpegTask.REQUIRED_LIBS] by reference alone: that list is the
     * native profile's contract and this one is the web profile's, and they agree today only because
     * avfilter is enabled above. If a future web tier drops a library, this is the line that should
     * change and the native one should not.
     */
    private fun verifyWasmInstall(prefix: Path) {
        val missing = BuildFFmpegTask.REQUIRED_LIBS.filterNot {
            Files.isRegularFile(prefix.resolve("lib/$it.a"))
        }
        check(missing.isEmpty()) {
            "the wasm FFmpeg build reported success but $prefix is missing " +
                missing.joinToString { "lib/$it.a" }
        }
        check(Files.isRegularFile(prefix.resolve("include/libavformat/avformat.h"))) {
            "the wasm FFmpeg build installed libraries but no headers; the C library needs both."
        }
    }

    private fun requireOnPath(tool: String): String {
        val proc = ProcessBuilder("which", tool).redirectErrorStream(true).start()
        val path = proc.inputStream.bufferedReader().readText().trim()
        check(proc.waitFor() == 0 && path.isNotEmpty()) {
            "$tool is not on PATH. Install emscripten (brew install emscripten) before running this task."
        }
        return path
    }

    private fun runIn(workDir: File, command: List<String>, env: Map<String, String>) {
        logger.lifecycle("[KiteFFmpeg wasm] " + command.joinToString(" "))
        val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
        builder.environment().putAll(env)
        val proc = builder.start()
        proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { logger.info("  $it") } }
        val code = proc.waitFor()
        check(code == 0) { "Command exited with $code: ${command.joinToString(" ")}" }
    }

    companion object {
        val VARIANTS = listOf("base", "simd", "mt")

        /** Homebrew's emscripten layout; overridable for an emsdk checkout. */
        const val DEFAULT_EMSCRIPTEN_LLVM_BIN = "/opt/homebrew/opt/emscripten/libexec/llvm/bin"

        /**
         * The 17.6 lean web set: h264, hevc, vp9, aac, mp3, flac and pcm.
         *
         * vp9 was NOT in the first draft, and the 17.5 matrix run caught it: `vp9.webm` is a
         * MustPlay row and the web build could not decode it. The tier serves the matrix or the
         * matrix stops being the one definition of playing all formats, so vp9 is in.
         *
         * opus and vorbis are the same lesson one layer down. `ffprobe` says `vp9.webm`
         * and `av1.mkv` both carry an opus track, so the tier decoded the picture of two MustPlay
         * rows and silently dropped their sound. vorbis rides along because it is webm's other
         * audio codec and a file using it would fail the same inaudible way.
         */
        const val DECODERS = "h264,hevc,vp9,aac,aac_latm,aac_fixed,mp3,mp3float,mp3adu,mp3adufloat,flac," +
            "opus,vorbis," +
            "pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_u8,pcm_s8," +
            "pcm_f32le,pcm_f64le,pcm_alaw,pcm_mulaw"

        /**
         * `mov` covers mp4/mov/m4a; `matroska` covers mkv and webm.
         *
         * `mp3` and `flac` are the ELEMENTARY-stream demuxers and were missing from the first
         * draft, which carried their decoders but no way to open a bare `.mp3` or `.flac`. The
         * matrix run caught it as `open -29` on two rows: a decoder without its demuxer is a
         * codec nobody can reach.
         */
        const val DEMUXERS = "mov,matroska,mp3,flac"

        const val PARSERS = "h264,hevc,vp9,aac,aac_latm,mpegaudio,flac,opus,vorbis"

        const val BSFS = "h264_mp4toannexb,hevc_mp4toannexb,aac_adtstoasc,extract_extradata,null"

        /**
         * Every filter `libkitecodec.a` names, plus the four a parsed graph almost always negotiates
         * through. `helpers_filter.c` is the source of the first five.
         */
        const val FILTERS = "abuffer,abuffersink,anull,buffer,buffersink,null,aformat,format,scale,aresample"
    }
}
