package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import javax.inject.Inject

private val TargetTriple.isIos: Boolean
    get() = this == TargetTriple.IosArm64 ||
        this == TargetTriple.IosSimulatorArm64 ||
        this == TargetTriple.IosX64

/**
 * Cross-compiles a minimal FFmpeg from source for one [target] under one [license] and writes the
 * resulting static archives + headers into `native-libs/<license>/<target.dirName>/{include,lib}`.
 * The Kotlin cinterop step picks them up via [FFmpegPaths].
 *
 * Every profile is LGPL and PORTABLE (owner decision 2026-08-22): the shared software playback
 * core plus platform services only, no third-party desktop stack. macOS gets SDK zlib and
 * VideoToolbox/AudioToolbox exactly like iOS (plus the VideoToolbox encoders, which do not exist
 * on the simulator); Linux and Windows get the reduced W-D4 profile. The fat macOS profile
 * (vpx/aom/opus/lame/webp encoders, freetype/harfbuzz/fribidi/libass, drawtext) is GONE: every
 * one of those had to come from Homebrew, Homebrew ships graphite2 shared-only, and a Release
 * asset that only links on a machine with Homebrew is not an asset. Decoding is untouched; the
 * read side is wide by class in [sharedCoreArgs]. Software AV1 is dav1d, MANDATORY in every bake
 * since 2026-08-22 (KC-EMBED): the on/off axis is dead.
 *
 * The **Android** profile: nothing GPL, nothing external;
 * hardware video encode/decode via MediaCodec (`h264_mediacodec`, `hevc_mediacodec`) plus FFmpeg's
 * native software decoders and aac encoder. Cross-compiled with the NDK toolchain (env
 * `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT` / `ANDROID_NDK_LATEST_HOME`, falling back to the SDK's
 * newest `ndk/<version>`).
 * The **iOS** profile is also LGPL-only: the shared software playback core plus SDK zlib, with no
 * desktop encoder/text stack and no VideoToolbox profile.
 *
 * Every flavour shares the same demuxer/decoder/filter core: the editor-relevant subset, ~75%
 * smaller than a "full" build (25 MB vs 110 MB per ABI).
 *
 * Expects an FFmpeg source tree at [sourceDir] (`vendor/ffmpeg` by convention). Either a git
 * submodule or a plain clone:
 * `git clone --depth 1 --branch n8.0 https://github.com/FFmpeg/FFmpeg vendor/ffmpeg`.
 * Up-to-date checking is input/output based: [sourceRef] pins the FFmpeg commit/tag the checkout is
 * expected to hold, so bumping it (or changing target/licence/configure flags) triggers a rebuild
 * while an already-populated output directory keeps the task UP-TO-DATE.
 */
abstract class BuildFFmpegTask @Inject constructor() : DefaultTask() {

    @get:Input
    abstract val target: Property<TargetTriple>

    /** Licence flavour. Android and iOS are LGPL-only; only desktop targets honour [FFmpegLicense.GPL]. */
    @get:Input
    abstract val license: Property<FFmpegLicense>

    /**
     * The FFmpeg tag/commit the [sourceDir] checkout is pinned to (for example `n8.0`). Declared as
     * an input so bumping the vendored FFmpeg invalidates previously built outputs. Hashing the
     * whole FFmpeg source tree as an input directory would be prohibitively slow.
     */
    @get:Input
    abstract val sourceRef: Property<String>

    /** The FFmpeg source checkout, `<repoRoot>/vendor/ffmpeg`. Tracked via [sourceRef], not by content. */
    @get:Internal
    abstract val sourceDir: DirectoryProperty

    /**
     * Committed source patches from `native/patches/ffmpeg`, applied in name order to the SCRATCH
     * copy of the source before configure. The vendored checkout itself stays pristine at
     * [sourceRef], which is what keeps its tracked-by-ref identity honest. Content-tracked, so
     * editing a patch rebuilds. Each application is `patch -p1` with `--forward` and a rejected
     * hunk fails the build loudly. The applied list and each patch's SHA-256 are written beside
     * the configure evidence in the install tree (`lib/kitecodec/ffmpeg-patches.txt`), so a
     * provenance question about a prebuilt tree has a one-file answer. First patch and the reason
     * it exists: KPKMP hotfix window 2c, the h264_mp4toannexb 4-byte start codes the Goldfish
     * API 36 MediaCodec decoder requires (measured 2026-08-12).
     */
    @get:org.gradle.api.tasks.InputFiles
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val sourcePatches: org.gradle.api.file.ConfigurableFileCollection

    /**
     * Whether the produced tree must be fully self-contained: every third-party archive FFmpeg
     * links present as a `.a` inside it. False (the default) for local development, where linking
     * a dependency from the host is fine. True for anything that becomes a Release asset, where a
     * missing archive means the zip cannot link on a consumer's machine.
     *
     * Set with `-Pkitecodec.ffmpeg.selfContained=true`.
     */
    @get:Input
    @get:Optional
    abstract val requireSelfContained: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kitecodec"
        description = "Cross-compile FFmpeg for the given Kotlin/Native target."
        license.convention(FFmpegLicense.LGPL)
    }

    @TaskAction
    fun run() {
        val target = target.get()
        val license = license.get()
        val sourceDir = sourceDir.get().asFile
        val outputDir = outputDir.get().asFile

        require(license != FFmpegLicense.GPL) {
            if (target.isIos) IOS_GPL_REFUSAL else LGPL_ONLY_REFUSAL
        }
        require(sourceDir.exists()) {
            "FFmpeg source not found at $sourceDir. Run:\n" +
                "  git clone --depth 1 --branch ${sourceRef.get()} https://github.com/FFmpeg/FFmpeg vendor/ffmpeg\n" +
                "(or add it as a git submodule for reproducible builds)"
        }
        val scratch = createScratchWorkspace(Path.of(System.getProperty("java.io.tmpdir")))
        var succeeded = false
        try {
            val scratchSource = scratch.resolve("source")
            val scratchBuild = scratch.resolve("build").also(Files::createDirectories)
            val scratchInstall = scratch.resolve("install")
            copySourceTree(sourceDir.toPath(), scratchSource)

            val patches = sourcePatches.files.filter { it.name.endsWith(".patch") }.sortedBy { it.name }
            patches.forEach { patchFile ->
                runIn(
                    scratchSource.toFile(),
                    listOf("/usr/bin/patch", "-p1", "--forward", "--fuzz=0", "-i", patchFile.absolutePath),
                )
            }

            // The deps tree rides to the scratch exactly like the FFmpeg source does: this
            // repo lives under '#Kite', and pkg-config shell-escapes the '#' in emitted -I/-L
            // flags while configure hands them to the compiler unevaluated, which no compiler
            // survives. Inside the scratch there is no '#' to escape.
            val dav1dRoot = dav1dRoot(target).let { real ->
                val copied = scratch.resolve("deps").toFile()
                real.copyRecursively(copied, overwrite = true)
                copied
            }
            val configureArgs = configureArguments(
                target = target,
                license = license,
                installPrefix = scratchInstall.toAbsolutePath().toString(),
                dav1dRoot = dav1dRoot,
            )
            val env = configureEnv(target, dav1dRoot)
            runIn(
                scratchBuild.toFile(),
                listOf(scratchSource.resolve("configure").toAbsolutePath().toString()) + configureArgs,
                env,
            )
            runIn(scratchBuild.toFile(), listOf("make", "-j${Runtime.getRuntime().availableProcessors()}"), env)
            runIn(scratchBuild.toFile(), listOf("make", "install"), env)

            writeConfigureEvidence(scratchBuild.resolve("ffbuild/config.log"), scratchInstall)
            run {
                val evidenceDir = scratchInstall.resolve("lib/kitecodec").also(Files::createDirectories)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val lines = buildString {
                    appendLine("# Source patches applied to the scratch FFmpeg before configure, in order.")
                    if (patches.isEmpty()) appendLine("(none)")
                    patches.forEach { p ->
                        val sha = digest.digest(p.readBytes()).joinToString("") { "%02x".format(it) }
                        appendLine("${p.name}  sha256=$sha")
                        digest.reset()
                    }
                }
                Files.writeString(evidenceDir.resolve("ffmpeg-patches.txt"), lines, UTF_8)
            }
            verifyInstall(scratchInstall)
            bundleThirdPartyArchives(target, license, scratchInstall.toFile())
            verifyInstall(scratchInstall)
            replaceOutputTree(scratchInstall, outputDir.toPath())
            succeeded = true
            logger.lifecycle(
                "[KiteCodec] FFmpeg ${sourceRef.get()} (${license.dirName}) installed into $outputDir",
            )
        } catch (failure: Throwable) {
            logger.error("[KiteCodec] FFmpeg scratch retained after failure: $scratch")
            throw failure
        } finally {
            if (succeeded) scratch.toFile().deleteRecursively()
        }
    }

    /**
     * The capability fingerprint this task WOULD bake right now, for [staleReason] to compare a
     * tree's stamp against.
     *
     * Every machine-specific input is stubbed rather than resolved: `xcrun`, the NDK lookup and the
     * konan toolchain all shell out or touch the filesystem, and none of their answers survive
     * [recipeFingerprint] anyway. Stubbing them is what lets a check run without a toolchain
     * present, and without the "starting an external process during configuration" refusal that
     * already bit the system-FFmpeg check.
     */
    public fun expectedRecipeFingerprint(): Set<String> {
        val stubKonan = KonanTools(
            clang = "clang", toolchainBin = "/stub/bin", runtimeDir = null,
            ar = "ar", nm = "nm", ranlib = "ranlib", triple = "stub", sysroot = "/stub/sysroot",
        )
        val args = configureArguments(
            target = target.get(),
            license = license.get(),
            installPrefix = "/stub/install",
            sdkPath = { "/stub/sdk" },
            ndkToolchainBin = { stubNdkToolchainBin() },
            dav1dRoot = File("/stub/dav1d"),
            konanBin = { stubKonan },
        )
        return recipeFingerprint(args)
    }

    /**
     * A throwaway NDK bin directory holding empty files named as the three Android profiles expect.
     *
     * `androidArgs` asserts its compiler EXISTS, which is a guard worth keeping for real bakes and
     * an obstacle for a fingerprint that discards every toolchain path anyway. Touching the names
     * satisfies it without weakening it, and reuses [ANDROID_API] rather than repeating the number,
     * so an API bump moves this with it instead of leaving a stale literal behind.
     */
    private fun stubNdkToolchainBin(): File {
        val dir = Files.createTempDirectory("kitecodec-recipe-stub-ndk").toFile()
        dir.deleteOnExit()
        listOf("aarch64-linux-android", "armv7a-linux-androideabi", "x86_64-linux-android").forEach {
            dir.resolve("$it$ANDROID_API-clang").apply { createNewFile(); deleteOnExit() }
        }
        return dir
    }

    internal fun configureArguments(
        target: TargetTriple,
        license: FFmpegLicense,
        installPrefix: String,
        sdkPath: (String) -> String = ::xcrunSdkPath,
        ndkToolchainBin: () -> File = ::ndkToolchainBin,
        dav1dRoot: File,
        konanBin: (TargetTriple) -> KonanTools = ::konanCrossTools,
    ): List<String> {
        require(license != FFmpegLicense.GPL) {
            if (target.isIos) IOS_GPL_REFUSAL else LGPL_ONLY_REFUSAL
        }
        val profileArgs = when {
            target.isAndroid -> androidArgs(target, ndkToolchainBin())
            target.isIos -> mobileAppleArgs(target, sdkPath)
            target.isPortableDesktop -> portableDesktopArgs(target) + desktopTargetArgs(target, konanBin)
            // macOS desktop: the portable Apple profile plus VideoToolbox encode.
            else -> desktopAppleArgs() + desktopTargetArgs(target, konanBin)
        }
        // dav1d is MANDATORY (KC-EMBED, 2026-08-22): the parameter is required so no caller can
        // forget it. configure discovers dav1d ONLY through pkg-config, so the host pkg-config is
        // forced even on cross builds; configureEnv points it at the deps tree and nothing else.
        // The decoder pin makes the intent survive class policy changes like the hwaccel pins do.
        val dav1dArgs = listOf("--enable-libdav1d", "--enable-decoder=libdav1d", "--pkg-config=pkg-config")
        return sharedCoreArgs() + profileArgs + dav1dArgs + listOf("--prefix=$installPrefix")
    }

    /**
     * The cross-built dav1d install for [target]. MANDATORY since the axis died (owner decision
     * 2026-08-22, KC-EMBED): every KiteCodec FFmpeg carries the dav1d AV1 software decoder,
     * because FFmpeg has no native software AV1 decoder and a build without one plays zero AV1.
     */
    private fun dav1dRoot(target: TargetTriple): File {
        val root = outputDir.get().asFile.parentFile.parentFile.resolve("deps/${target.dirName}/dav1d")
        require(root.resolve("lib/libdav1d.a").isFile) {
            "native-libs/deps/${target.dirName}/dav1d/lib/libdav1d.a does not exist, and dav1d is " +
                "mandatory in every KiteCodec FFmpeg. Run :kitecodec-core:buildDav1dFor${target.gradleSuffix} first."
        }
        return root
    }

    /**
     * Copy the third-party static archives FFmpeg was linked against into the vendored tree's
     * `lib/`, so `native-libs/<license>/<target>` is self-contained and has exactly the layout the
     * published release zip does.
     *
     * Since the portable profiles this means exactly one archive: the cross-built `libdav1d.a`
     * from the deps tree, mandatory in every bake. `make install` only installs FFmpeg's own
     * libraries, so without this copy the tree links only on the machine that baked the dav1d
     * build.
     */
    private fun bundleThirdPartyArchives(target: TargetTriple, license: FFmpegLicense, outputDir: File) {
        val wanted = StaticLinkFlags.thirdPartyArchives(target, license)
        if (wanted.isEmpty()) return

        val libDir = outputDir.resolve("lib").also { it.mkdirs() }
        val searchDirs = thirdPartySearchDirs(target).filter { it.isDirectory }
        val missing = mutableListOf<String>()
        wanted.forEach { archive ->
            if (libDir.resolve(archive).isFile) return@forEach
            val found = searchDirs.map { it.resolve(archive) }.firstOrNull { it.isFile }
            if (found == null) missing += archive else found.copyTo(libDir.resolve(archive), overwrite = true)
        }
        if (missing.isEmpty()) {
            logger.lifecycle("[KiteCodec] bundled ${wanted.size} third-party static archives into $libDir")
            return
        }

        // Some package managers ship a few of these as shared libraries only. Homebrew's svt-av1
        // is the standing example. A dev build can still link against the host's dylib, so warn
        // rather than block; a build destined for a Release asset cannot, so make it fatal there.
        val explanation =
            "These static third-party archives are not installed on this machine: " +
                "${missing.joinToString()}.\n" +
                "They are linked INTO libavcodec.a. Searched: ${searchDirs.joinToString()}.\n" +
                "Their package likely ships shared libraries only; the fix is to build those " +
                "dependencies statically from source. Never substitute the shared library into " +
                "the tree, which silently stops it being self-contained."
        if (requireSelfContained.getOrElse(false)) {
            throw GradleException(
                "$explanation\n" +
                    "-Pkitecodec.ffmpeg.selfContained=true was set (a distributable build), so " +
                    "this is fatal: the resulting zip would not link on a consumer's machine.",
            )
        }
        logger.warn(
            "warning: [KiteCodec] $outputDir is NOT self-contained.\n" +
                "$explanation\n" +
                "Local builds link these from the host instead, which is fine for development. " +
                "Pass -Pkitecodec.ffmpeg.selfContained=true to make this a hard failure.",
        )
    }

    /** Where the static archives live: the cross-built deps tree, nothing host-managed. */
    private fun thirdPartySearchDirs(target: TargetTriple): List<File> = listOf(
        outputDir.get().asFile.parentFile.parentFile.resolve("deps/${target.dirName}/dav1d/lib"),
    ) + when (target) {
        TargetTriple.LinuxX64 -> listOf(
            File("/usr/lib/x86_64-linux-gnu"), File("/usr/lib"), File("/usr/local/lib"),
        )
        TargetTriple.LinuxArm64 -> listOf(
            File("/usr/lib/aarch64-linux-gnu"), File("/usr/lib"), File("/usr/local/lib"),
        )
        else -> emptyList()
    }

    /**
     * Environment for configure/make. PKG_CONFIG_LIBDIR (not PATH) so the host's own .pc files
     * can never leak into any target's link, macOS included: the only pkg-config lookup any
     * profile makes is dav1d, and its deps tree is the whole pkg-config universe.
     */
    private fun configureEnv(target: TargetTriple, dav1dRoot: File): Map<String, String> =
        mapOf("PKG_CONFIG_LIBDIR" to dav1dRoot.resolve("lib/pkgconfig").absolutePath)

    /** The codec/filter core both profiles share. */
    private fun sharedCoreArgs(): List<String> = listOf(
        // Static-only, no shared, no programs (`ffmpeg`/`ffprobe`/`ffplay`).
        "--enable-static", "--disable-shared",
        "--disable-programs", "--disable-doc", "--disable-debug",
        "--disable-htmlpages", "--disable-manpages", "--disable-podpages", "--disable-txtpages",

        // The READ side is wide by class (KitePlayer KPKMP.md 17.4.9, owner order 2026-08-13):
        // decoders, demuxers, parsers, bitstream filters and hwaccels compile whole, so a
        // consumer plays what FFmpeg can play and an FFmpeg bump widens coverage without
        // touching this file. Bitstream filters ride whole with the demuxers because
        // libavformat inserts them ITSELF during stream copy; leaving one out corrupts output
        // silently instead of failing loudly. Only the WRITE side and the protocol list stay
        // curated: encoders, muxers and filters are the deliberate editor subset re-enabled by
        // name below, and protocols stay small because they are the attack surface (playlist
        // demuxers dial out through whatever protocols exist). Capture and playback devices
        // stay off entirely.
        "--disable-encoders", "--disable-muxers", "--disable-filters",
        "--disable-devices", "--disable-protocols",
        // file/pipe/data always; http+tcp so MediaSource.open() can actually take the URLs its
        // KDoc advertises. https is deliberately absent: it needs a TLS backend (openssl/gnutls/
        // mbedtls) cross-built for every target, which is a dependency escalation this profile
        // does not take on. See docs/platforms.md. --enable-network is explicit rather than
        // inherited so a target whose configure probe defaults it off fails loudly here. The
        // wide demuxer class includes members that SELECT network protocols (rtsp and its
        // relatives); the class disable above must win, so the configure banner's protocol
        // line is checked against exactly this five-name list after every profile change.
        // `fd` is what makes an Android content:// URI playable. The picker hands back a
        // descriptor, and the usual escape of re-opening `/proc/self/fd/N` through the `file`
        // protocol fails with EACCES on a modern device, because re-opening rechecks permissions
        // against the PATH while the descriptor itself stays perfectly valid (measured on a real
        // phone, 2026-08-13). The `fd` protocol takes the descriptor as a pre-open option and
        // `dup()`s it, so nothing is re-opened, and its fstat sets is_streamed correctly, which
        // keeps a regular file SEEKABLE. `pipe:<fd>` is not a substitute: it dups too, but hard
        // codes is_streamed = 1, so seeking dies and with it any sync.
        "--enable-network",
        "--enable-protocol=file,fd,pipe,data,http,tcp",
        // Fixed point 5 of 17.4.9, observed on the first wide configure: the rtsp/sdp demuxers
        // SELECT udp and rtp, and configure's select is stronger than a class disable, so the
        // banner grew both. A named disable is stronger than a select: with these two hard-off,
        // configure drops the demuxers that need them instead of resurrecting the protocols.
        "--disable-protocol=udp,rtp",
        // Several everyday extensions map to their OWN muxer rather than to the obvious one, and
        // `avformat_alloc_output_context2` simply fails to find a format when that muxer is absent:
        //   .mka → matroska_audio (not matroska)   .m4a → ipod (not mp4)
        // mpegts is what every "trim a broadcast capture" path needs, and it is the format whose
        // nonzero container start time the timestamp code is written against.
        "--enable-muxer=mp4,mov,ipod,webm,matroska,matroska_audio,mp3,wav,flac,ogg,opus,mpegts,image2",
        // Encoders that need no third-party library, so every profile (desktop LGPL, desktop GPL,
        // Android) has them. mpeg4 is the dependency-free video baseline: without it an LGPL build
        // can only encode video via libsvtav1 (slow) or mjpeg (intra-only), and the library's own
        // round-trip tests and sample have nothing portable to write with. flac and the pcm_* set
        // are what make the already-enabled flac/wav MUXERS able to write anything at all.
        //
        // aac JOINED THIS LIST 2026-08-24, and its absence here was a real shipped defect. It was
        // named only in desktopAppleArgs() and in the Android profile, so macOS and Android had it
        // while LINUX AND WINDOWS DID NOT: measured on the released trees, ff_aac_encoder is
        // present in macos-arm64 and absent from linux-x64 and mingw-x64. A consumer calling
        // Transcoder.transcode with default audio settings therefore got "No encoder named 'aac'"
        // on those two platforms, in 0.1.1 and 0.1.2. Nothing justified the split: the native aac
        // encoder is dependency-free, which is the same reason every other name on this line is
        // here. The flag had simply landed in an Apple-only block. mp4/mov without an AAC encoder
        // is also the odd combination, since that is the pairing the format is usually written with.
        "--enable-encoder=mpeg4,aac,flac,pcm_s16le,pcm_s24le,pcm_f32le,png,mjpeg",

        // buffer/buffersink/abuffer/abuffersink are how KiteCodec feeds and drains every graph.
        // Without them ffkmp_graph_build_* returns AVERROR_FILTER_NOT_FOUND.
        // Only filters EVERY profile can actually provide. Two kinds of exception live elsewhere,
        // because configure silently drops a filter whose dependencies are unmet. Listing one
        // here would make this a promise some builds quietly break:
        //   - drawtext needs libfreetype/libharfbuzz → desktopBaseArgs (Android links neither).
        //   - eq and boxblur are `deps="gpl"` in FFmpeg's own configure → desktopGplArgs.
        // `hue` (which has a brightness parameter), `colorlevels` and `curves` cover most of what
        // `eq` is reached for, and are available everywhere.
        // setparams added 2026-08-17 (phase W): the project's own P010 alignment test declares an
        // input link's colour space and range with it, and it had never been enabled. Nobody saw
        // that because the macOS host gate resolves FFmpeg from Homebrew, which carries every
        // filter, so the VENDORED profile was only ever exercised on phones. The Linux run is what
        // found it, which is the point of running on more than one surface.
        "--enable-filter=buffer,buffersink,abuffer,abuffersink,trim,setpts,setparams,scale,pad,overlay,hue,unsharp,vignette,colorbalance,colorlevels,curves,lut,format,colorchannelmixer,split,null,atrim,asetpts,asetrate,aresample,volume,atempo,adelay,afade,amix,anull,aformat,loop,tpad",

        "--enable-pthreads",
        "--enable-pic",
        "--enable-runtime-cpudetect",
    )

    /**
     * macOS desktop profile: the portable Apple set (owner decision 2026-08-22).
     *
     * Structurally [mobileAppleArgs] plus VideoToolbox ENCODE, which the simulator lacks. No
     * Homebrew stack at all: the old fat profile (vpx/aom/opus/lame/webp encoders, the
     * freetype/harfbuzz/fribidi/libass text stack, drawtext) could never produce a
     * self-contained Release asset because Homebrew ships graphite2 shared-only, and KitePlayer
     * renders subtitles through its own libass chain anyway. Decoding is untouched: the read
     * side is wide by class in [sharedCoreArgs], and software AV1 is the dav1d switch's job.
     * aac is no longer named here: it moved to the shared encoder list on 2026-08-24, because
     * being dependency-free is a reason EVERY profile should carry it, not just this one.
     */
    private fun desktopAppleArgs(): List<String> = listOf(
        "--disable-autodetect",
        "--enable-zlib",
        "--enable-audiotoolbox",
    ) + appleHardwareArgs()

    /**
     * VideoToolbox hardware encode on macOS desktop targets.
     *
     * `--enable-videotoolbox` alone only turns on the framework dependency. Under
     * `--disable-encoders` every encoder must additionally be named in `--enable-encoder=`, or
     * the resulting build advertises VideoToolbox support and then has no `h264_videotoolbox`
     * encoder to find at runtime. The Android profile avoids that mistake by listing
     * `h264_mediacodec` explicitly. Simulator targets are excluded: VideoToolbox encode is not
     * available there.
     */
    private fun appleHardwareArgs(): List<String> = listOf(
        "--enable-videotoolbox",
        "--enable-encoder=h264_videotoolbox,hevc_videotoolbox",
    ) + appleHwaccelDecodeArgs()

    /**
     * VideoToolbox hardware DECODE (KiteCodec window 3, KPKMP 17.4.8 S2.a). Unlike MediaCodec
     * there is no named decoder to enable: VideoToolbox decode is an hwaccel behind the ordinary
     * `h264`/`hevc` decoders. Since the 17.4.9 wide profile the hwaccel class compiles whole, so
     * this list is a PIN rather than the sole source: it guarantees the two hwaccels the player's
     * D-2 route depends on exist even if the class policy above ever changes. Decode is enabled
     * for EVERY Apple target including the simulator (decode works there on Apple silicon; it is
     * encode that does not), and a runtime refusal on any particular machine is FFmpeg's own
     * typed answer through `ffkmp_codecctx_use_videotoolbox`, which D-2's measured fallback
     * treats as one more fallback cause.
     */
    private fun appleHwaccelDecodeArgs(): List<String> = listOf(
        "--enable-hwaccel=h264_videotoolbox,hevc_videotoolbox,av1_videotoolbox",
    )

    /*
     * AV1 note, and it is a warning as much as a pin (register row PAR-6, and the new row it
     * opened).
     *
     * `av1_videotoolbox` is pinned above so the hwaccel exists on every Apple target that has AV1
     * silicon (A17 Pro, M3 and newer). configure's `av1_videotoolbox_hwaccel_select="av1_decoder"`
     * means this also compiles FFmpeg's NATIVE av1 decoder into every Apple build, which is the
     * cost: hwaccels attach to a decoder, and libdav1d is an external decoder that carries none.
     *
     * PINNING IT IS NOT ENOUGH TO GET HARDWARE AV1, and nothing here should be read as claiming
     * otherwise. `avcodec_find_decoder(AV_CODEC_ID_AV1)` walks `codec_list` in the order
     * `allcodecs.c` declares, where `ff_libdav1d_decoder` sits ahead of `ff_av1_decoder`. So on
     * any build that also carries dav1d, which is every KitePlayer consumer build, the lookup
     * returns libdav1d and VideoToolbox is never offered a format to negotiate.
     *
     * The missing half is a decoder chosen BY NAME plus a policy: open native `av1` with
     * VideoToolbox attached, and fall back to `libdav1d` in software when the hardware refuses.
     * KiteCodec has no by-name decoder path today. Until it does, this pin only guarantees the
     * hwaccel is present for that work to use.
     */

    /**
     * One Kotlin/Native cross toolchain: konan's clang, its binutils, the triple and the sysroot.
     *
     * Injected as a lambda so the configure goldens can pin the flag SHAPE without pinning this
     * machine's absolute paths, exactly as the Android arm injects its NDK bin directory.
     */
    internal data class KonanTools(
        val clang: String,
        /** `-B<dir>`: where clang finds `ld.lld`. Apple's own `ld` cannot link ELF or PE. */
        val toolchainBin: String,
        /** The gcc runtime directory (crtbegin/crtend, libgcc), which sits OUTSIDE the sysroot. */
        val runtimeDir: String?,
        val ar: String,
        val nm: String,
        val ranlib: String,
        val triple: String,
        val sysroot: String,
    )

    private fun konanCrossTools(target: TargetTriple): KonanTools {
        val konanRoot = System.getenv("KONAN_DATA_DIR")?.let(::File)
            ?: File(System.getProperty("user.home"), ".konan")
        val dependencies = konanRoot.resolve("dependencies")
        require(dependencies.isDirectory) {
            "No konan dependencies at ${dependencies.absolutePath}. They arrive with the " +
                "Kotlin/Native distribution, so a build that has already compiled Kotlin/Native " +
                "code has them."
        }
        val llvmBin = CompileKiteCodecCTask.resolveLlvmBinDir(
            dependencies,
            CompileKiteCodecCTask.DEFAULT_LLVM_PACKAGE,
        ) { message -> logger.lifecycle("[KiteCodec] $message") }
        fun tool(name: String): String = (CompileKiteCodecCTask.resolveTool(llvmBin, name)
            ?: throw GradleException(
                "Cannot cross-build FFmpeg for $target: no $name under ${llvmBin.absolutePath}.",
            )).absolutePath
        // The konan LLVM package is the "essentials" set: clang, lld, llvm-ar and little else.
        // ranlib is llvm-ar's own `s` operation, and Xcode's nm is LLVM's and reads ELF and PE
        // as happily as Mach-O (verified against a cross-linked linuxX64 binary). Stripping is
        // turned off in the configure args instead of hunting for a strip that can do it: these
        // are static archives, and a consumer's own link strips what it does not use.
        val archiver = tool("llvm-ar")
        val spec = CompileKiteCodecCTask.specFor(target.konanTargetName)
        val sysrootRelative = requireNotNull(spec.konanSysroot) { "$target has no konan sysroot" }
        val sysroot = dependencies.resolve(sysrootRelative)
        require(sysroot.isDirectory) {
            "Cannot cross-build FFmpeg for $target: no sysroot at ${sysroot.absolutePath}."
        }
        // konan's linux packages keep the gcc runtime beside the sysroot, not inside it, so lld
        // finds neither crtbeginS.o nor libgcc without being told. The version directory is not
        // hardcoded: the package pins its own gcc version and a konan bump would change it.
        val packageRoot = dependencies.resolve(sysrootRelative.substringBefore('/'))
        val runtimeDir = packageRoot.resolve("lib/gcc").listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { it.listFiles().orEmpty().filter { version -> version.isDirectory } }
            .firstOrNull { it.resolve("libgcc.a").isFile }
        return KonanTools(
            clang = tool("clang"),
            toolchainBin = llvmBin.absolutePath,
            runtimeDir = runtimeDir?.absolutePath,
            ar = archiver,
            nm = "/usr/bin/nm",
            ranlib = "$archiver s",
            triple = spec.triple,
            sysroot = sysroot.absolutePath,
        )
    }

    private fun desktopTargetArgs(
        target: TargetTriple,
        konanBin: (TargetTriple) -> KonanTools,
    ): List<String> = when (target) {
        // SOL-B4: the floor is PASSED, never inherited. Without it clang takes the SDK's, which
        // measured 26.0 on the committed archives while Kotlin/Native links these objects at 12.0.
        TargetTriple.MacosArm64 -> listOf(
            "--arch=arm64", "--target-os=darwin",
            "--cc=clang -arch arm64 -mmacosx-version-min=$MACOS_DEPLOYMENT_TARGET",
            "--enable-cross-compile",
        )
        TargetTriple.MacosX64 -> listOf(
            "--arch=x86_64", "--target-os=darwin",
            "--cc=clang -arch x86_64 -mmacosx-version-min=$MACOS_DEPLOYMENT_TARGET",
            "--enable-cross-compile",
        )
        // Linux and Windows cross-build with the SAME toolchain Kotlin/Native links against:
        // konan's own clang, aimed by -target, over the sysroot konan ships for that triple
        // (KPKMP.md 17.13, decision W-D3). This is not a preference. FFmpeg built by any other
        // toolchain can reference a glibc symbol the konan sysroot does not carry, and the failure
        // arrives at LINK time in a consumer's build, which is the worst place to find it.
        //
        // The konan "gcc" packages are not compilers on this host: their binaries are Linux ELF
        // and Windows PE respectively, shipped for their headers and libraries. Only the sysroot
        // is used, exactly as CompileKiteCodecCTask already does for the C helper layer.
        TargetTriple.LinuxX64, TargetTriple.LinuxArm64, TargetTriple.MingwX64 -> {
            val tools = konanBin(target)
            val arch = when (target) {
                TargetTriple.LinuxArm64 -> "aarch64"
                else -> "x86_64"
            }
            val targetOs = if (target == TargetTriple.MingwX64) "mingw32" else "linux"
            listOf(
                "--arch=$arch", "--target-os=$targetOs",
                "--enable-cross-compile",
                // -fuse-ld=lld with -B is not optional: without it clang hands the objects to
                // Apple's ld, which answers "unknown options: --sysroot ... --hash-style=gnu"
                // because it only links Mach-O.
                "--cc=${tools.clang} -target ${tools.triple} --sysroot=${tools.sysroot} " +
                    "-fuse-ld=lld -B${tools.toolchainBin}" +
                    (tools.runtimeDir?.let { " -B$it -L$it" } ?: ""),
                "--ar=${tools.ar}", "--nm=${tools.nm}", "--ranlib=${tools.ranlib}",
                "--disable-stripping",
                // configure builds a few tools that must run HERE, so they need the host compiler.
                "--host-cc=/usr/bin/clang",
            ) + if (target == TargetTriple.MingwX64) {
                listOf(
                    // mingw's headers need the GNU dialect, and konan's clang defaults to a newer
                    // C standard than the msys2 headers were written for.
                    "--extra-cflags=-std=gnu11",
                    // Windows threading is w32threads, not pthreads. sharedCoreArgs asks for
                    // pthreads for everyone else, and configure REFUSES a requested library it
                    // cannot find, so the request is withdrawn here rather than made conditional
                    // up there: configure takes the last word on a flag, so this pair wins.
                    "--disable-pthreads", "--enable-w32threads",
                )
            } else {
                emptyList()
            }
        }
        else -> error("desktopTargetArgs called for non-desktop target $target")
    }

    /**
     * The reduced desktop profile for Linux and Windows (decision W-D4).
     *
     * Software codecs, the desktop compression libraries the sysroots actually carry, and nothing
     * that needs a cross-built third-party library. What it costs against the macOS desktop
     * profile, stated rather than hidden: no libsvtav1/libvpx/libaom/libopus/libmp3lame ENCODERS,
     * no libwebp, no libass and no drawtext. Decoding is untouched, because the read side is wide
     * by class in [sharedCoreArgs] and needs no third-party library at all, so the 17.5 conformance
     * matrix plays in full.
     *
     * The compression libraries are per sysroot, measured rather than assumed: all three sysroots
     * carry zlib (msys2 keeps its copy at the package root rather than under the triple directory,
     * which is what made a first reading of this call it absent), and none of them carries bzlib or
     * lzma. FFmpeg REFUSES a configure that requests a library it cannot find, so asking for bzlib
     * would fail the build rather than silently drop it. zlib is REQUESTED rather than left to
     * autodetect for the opposite reason: an autodetected zlib compiles its symbols in while the
     * consumer's link line, which is written from this list, never learns to name -lz, and the
     * failure lands as `undefined symbol: inflate` in a downstream link. Asking makes the flag and
     * the link agree by construction. What zlib buys is matroska and mov compressed headers; bzlib
     * and lzma buy only rarely used matroska compression.
     */
    private fun portableDesktopArgs(target: TargetTriple): List<String> = listOf("--enable-zlib")

    /** Mobile Apple playback profile: shared software codecs plus SDK zlib, and no desktop stack. */
    private fun mobileAppleArgs(target: TargetTriple, sdkPath: (String) -> String): List<String> {
        val crossArgs = when (target) {
            TargetTriple.IosArm64 -> {
                val sdk = sdkPath("iphoneos")
                listOf(
                    "--arch=arm64", "--target-os=darwin",
                    "--cc=clang -arch arm64 -isysroot $sdk -mios-version-min=14.0",
                    "--enable-cross-compile",
                )
            }
            TargetTriple.IosSimulatorArm64 -> {
                val sdk = sdkPath("iphonesimulator")
                listOf(
                    "--arch=arm64", "--target-os=darwin",
                    "--cc=clang -arch arm64 -isysroot $sdk -mios-simulator-version-min=14.0",
                    "--enable-cross-compile",
                )
            }
            TargetTriple.IosX64 -> {
                val sdk = sdkPath("iphonesimulator")
                listOf(
                    "--arch=x86_64", "--target-os=darwin",
                    "--cc=clang -arch x86_64 -isysroot $sdk -mios-simulator-version-min=14.0",
                    "--enable-cross-compile",
                    "--disable-asm",
                )
            }
            else -> error("mobileAppleArgs called for non-iOS target $target")
        }
        // aarch64 asm is ON for the two arm64 iOS targets and stays off for IosX64, for exactly the
        // reason the Android profile states: x86_64 inline asm needs nasm in the env, arm64 asm does
        // not and it is what makes software decode fast. The arm64 iOS targets carried
        // `--disable-asm` for a long time with no recorded reason, which shipped every iPhone a
        // C-only libavcodec while the macOS tree built from the same clang carried ~1360 NEON
        // symbols. Measured after the change: ios-arm64 went 0 -> non-zero NEON symbols.
        //
        // AudioToolbox is REQUESTED rather than left to autodetect, for the same reason zlib is in
        // [portableDesktopArgs]: `--disable-autodetect` above means an unasked framework is simply
        // absent. Without it the iOS trees carried none of the `*_at` decoders the macOS tree gets
        // for free, so AAC, ALAC and both Dolby formats decoded on the CPU with no platform path
        // available at all. Enabling the framework compiles the `*_at` decoder class; naming one at
        // open is the player's decision, not this build's.
        return listOf(
            "--disable-autodetect",
            "--enable-zlib",
            "--enable-videotoolbox",
            "--enable-audiotoolbox",
        ) + appleHwaccelDecodeArgs() + crossArgs
    }

    /** Resolves an Apple SDK sysroot via `xcrun`, so the path tracks the installed Xcode. */
    private fun xcrunSdkPath(sdkName: String): String {
        val proc = ProcessBuilder("xcrun", "--sdk", sdkName, "--show-sdk-path")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        check(proc.waitFor() == 0 && output.isNotEmpty()) {
            "xcrun --sdk $sdkName --show-sdk-path failed: $output"
        }
        return output
    }

    /**
     * Android profile: LGPL (no `--enable-gpl`, no third-party libs), NDK clang toolchain,
     * MediaCodec hardware video encode/decode. `--enable-jni` is required by the MediaCodec
     * wrapper; at runtime the app must hand FFmpeg its JavaVM via `av_jni_set_java_vm` before
     * using `*_mediacodec` codecs (the surrounding KiteCodec Android substrate will own that call).
     */
    private fun androidArgs(target: TargetTriple, toolchainBin: File): List<String> {
        val (arch, cpu, ccPrefix) = when (target) {
            TargetTriple.AndroidArm64 -> Triple("aarch64", "armv8-a", "aarch64-linux-android")
            TargetTriple.AndroidArm32 -> Triple("arm", "armv7-a", "armv7a-linux-androideabi")
            TargetTriple.AndroidX64 -> Triple("x86_64", null, "x86_64-linux-android")
            else -> error("androidArgs called for non-android target $target")
        }
        val cc = toolchainBin.resolve("$ccPrefix$ANDROID_API-clang")
        require(cc.exists()) { "NDK compiler not found: $cc" }

        return listOf(
            "--target-os=android", "--arch=$arch",
            "--enable-cross-compile",
            "--cc=${cc.absolutePath}",
            "--cxx=${cc.absolutePath}++",
            "--ar=${toolchainBin.resolve("llvm-ar").absolutePath}",
            "--ranlib=${toolchainBin.resolve("llvm-ranlib").absolutePath}",
            "--nm=${toolchainBin.resolve("llvm-nm").absolutePath}",
            "--strip=${toolchainBin.resolve("llvm-strip").absolutePath}",
            "--enable-mediacodec", "--enable-jni",
            // Adds to the dependency-free encoder set in sharedCoreArgs.
            "--enable-encoder=h264_mediacodec,hevc_mediacodec",
            // Adds to the shared sw set. av1/vp9/vp8 ride along because FFmpeg has NO native
            // software AV1 decoder (av1dec.c is a hwaccel shell): on Android the MediaCodec
            // wrappers are the only AV1 route this profile can offer, and most devices carry
            // an AV1 MediaCodec from Android 10 on. Software AV1 needs vendored dav1d, which
            // is recorded in KPKMP 17.11 rather than pretended here.
            "--enable-decoder=h264_mediacodec,hevc_mediacodec,av1_mediacodec,vp9_mediacodec,vp8_mediacodec",
            "--enable-zlib",
        ) +
            (cpu?.let { listOf("--cpu=$it") } ?: emptyList()) +
            // x86_64 inline asm needs nasm in the env; arm32 asm is fragile with clang. Keep
            // arm64 asm (it matters for sw decode speed), disable elsewhere.
            (if (target != TargetTriple.AndroidArm64) listOf("--disable-asm") else emptyList())
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
                ?: error(
                    "Android NDK not found. Set ANDROID_NDK_HOME or install one via the SDK manager."
                )
        }
        val prebuilt = ndk.resolve("toolchains/llvm/prebuilt")
        val hostDir = prebuilt.listFiles { f: File -> f.isDirectory }?.firstOrNull()
            ?: error("No prebuilt toolchain under $prebuilt")
        return hostDir.resolve("bin")
    }

    private fun runIn(workDir: File, command: List<String>, env: Map<String, String> = emptyMap()) {
        logger.lifecycle("[KiteCodec build] " + command.joinToString(" "))
        val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
        builder.environment().putAll(env)
        val proc = builder.start()
        proc.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { logger.lifecycle("  $it") }
        }
        val code = proc.waitFor()
        check(code == 0) { "Command exited with $code: ${command.joinToString(" ")}" }
    }

    /**
     * One place where the expected FFmpeg release is written down, and where that is.
     *
     * Register item B1-04: the `n8.0` expectation lives in three files bound only by a comment in the
     * third one asking the reader to keep them in sync. Nothing enforced it, and nothing checked any of
     * them against the vendored checkout. [assertFFmpegRefsAgree] is the enforcement, and this is what
     * it compares. Declared at class level and not inside the companion, because a class nested in a
     * companion is spelled `BuildFFmpegTask.Companion.FFmpegRefSite` at the call site, which is not a
     * name anyone should have to write.
     */
    data class FFmpegRefSite(val where: String, val ref: String)

    companion object {
        /**
         * NDK API level the native libs are built against. The published minSdk is 26 (Android
         * 8.0), but the FFmpeg trees deliberately stay at 24: binaries built against a lower API
         * level run unchanged on newer devices, and raising this forces a full rebuild of every
         * Android FFmpeg tree for zero functional gain. Raise it only when a libav feature
         * actually needs a newer NDK symbol.
         */
        const val ANDROID_API = 24

        /**
         * The macOS deployment floor for every vendored tree, and the ONE place it is written.
         *
         * SOL-B4. Three floors used to disagree in one product, measured 2026-08-25: the archives
         * carried `minos 26.0` because the macOS branches passed no `-mmacosx-version-min` at all
         * and inherited the SDK's, the C helper layer compiled at 11.0, and Kotlin/Native links at
         * 12.0. The archive was the dangerous one: an object built for a NEWER floor than the
         * binary linking it means the product claims 12.0 support while carrying code that asks
         * for 26.
         *
         * 12.0 is not a preference. It is `minVersion.macos` from konan.properties for the Kotlin
         * this repository pins, so it is the floor the linker imposes whatever anything else says.
         * `CompileKiteCodecCTask` reads this same constant; raising it means raising konan first.
         */
        const val MACOS_DEPLOYMENT_TARGET = "12.0"

        /** The FFmpeg tag `vendor/ffmpeg` is expected to be checked out at. */
        const val DEFAULT_SOURCE_REF = "n8.1.2"

        /**
         * Normalises an FFmpeg release reference so a git tag and a release file can be compared.
         *
         * FFmpeg's git tags are `n8.0`; the `RELEASE` file in a checkout of that tag says `8.0`. Both
         * name the same release, so the leading `n` is dropped and the string is trimmed. Nothing else
         * is normalised: `8.0` and `8.0.1` must stay different, because they are.
         */
        fun normaliseFFmpegRef(ref: String): String = ref.trim().removePrefix("n")

        /**
         * Fails when the places that record the expected FFmpeg release do not agree.
         *
         * Pure, so `BuildFFmpegRefsTest` can hand it agreeing and disagreeing inputs without a build.
         * The message names every site and its value rather than only the mismatch, because the question
         * a reader has at that moment is "which one is wrong", and that needs all of them.
         *
         * [vendorRelease] is the `RELEASE` file of `vendor/ffmpeg` when the checkout is present, and null
         * when it is not. A missing checkout is not a failure: the vendored path is optional and most
         * builds use a prebuilt or system FFmpeg. A PRESENT checkout at the wrong release is a failure,
         * because that is the case where a build would compile against headers nobody declared.
         */
        fun assertFFmpegRefsAgree(sites: List<FFmpegRefSite>, vendorRelease: String?) {
            require(sites.isNotEmpty()) { "assertFFmpegRefsAgree needs at least one site" }
            val all = sites + listOfNotNull(
                vendorRelease?.let { FFmpegRefSite("vendor/ffmpeg/RELEASE", it) },
            )
            val distinct = all.map { normaliseFFmpegRef(it.ref) }.distinct()
            if (distinct.size == 1) return
            throw GradleException(
                buildString {
                    appendLine(
                        "The expected FFmpeg release is recorded in ${all.size} place(s) and they do " +
                            "not agree (register item B1-04). Found ${distinct.size} distinct values:",
                    )
                    for (site in all) {
                        appendLine("  ${site.where}: ${site.ref} (normalised ${normaliseFFmpegRef(site.ref)})")
                    }
                    appendLine()
                    append(
                        "A consumer that downloads one release and links it against a klib whose C was " +
                            "compiled against another gets a successful static link and wrong struct " +
                            "field offsets, which is register item B1-02. Make all of them name one " +
                            "release, or check the vendored tree out at the release they name.",
                    )
                },
            )
        }

        /**
         * Reads `FFMPEG_VERSION: <ref>` out of a GitHub Actions workflow's `env:` block.
         *
         * Returns null when the key is absent, which the caller reports as its own failure: a workflow
         * that stopped pinning the release is a drift this check exists to catch, and silently treating
         * it as agreement would be the wrong answer.
         */
        fun readWorkflowFFmpegVersion(workflowText: String): String? =
            workflowText.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("FFMPEG_VERSION:") }
                ?.substringAfter(':')
                ?.trim()
                ?.trim('"', '\'')
                ?.takeIf { it.isNotEmpty() }


        /** Reads `LIBAVUTIL_VERSION_MAJOR` out of a vendored `libavutil/version.h`, or null. */
        fun readVendoredAvutilMajor(versionHeaderText: String): Int? =
            Regex("""^\s*#define\s+LIBAVUTIL_VERSION_MAJOR\s+(\d+)""", RegexOption.MULTILINE)
                .find(versionHeaderText)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        /** Homebrew's prefix on Apple silicon; Intel Macs use /usr/local (override via the property). */
        const val DEFAULT_HOMEBREW_PREFIX = "/opt/homebrew"

        /** The six libav* archives every profile must produce; a partial install must never ship. */
        val REQUIRED_LIBS = listOf(
            "libavcodec", "libavformat", "libavutil",
            "libavfilter", "libswscale", "libswresample",
        )

        /** Stable installed provenance path consumed by packaging and release evidence. */
        const val CONFIGURE_EVIDENCE_RELATIVE_PATH = "lib/kitecodec/ffmpeg-configure.txt"

        /**
         * Configure keys whose values describe THIS MACHINE rather than the recipe.
         *
         * A tree baked on a laptop with Xcode 17 carries a different `--cc` sysroot than the same
         * recipe baked after an Xcode update, and `--prefix` is a fresh scratch directory on every
         * single run. Comparing those would make [staleReason] cry wolf constantly, which is worse
         * than not checking at all: a check nobody believes gets disabled.
         */
        private val MACHINE_SPECIFIC_CONFIGURE_KEYS = setOf(
            "--prefix", "--cc", "--cxx", "--ar", "--nm", "--ranlib", "--strip", "--as", "--ld",
            "--sysroot", "--pkg-config", "--extra-cflags", "--extra-ldflags", "--extra-libs",
            "--toolchain", "--cross-prefix", "--host-cc",
        )

        // No toggle-controlled flags remain: the dav1d switch died on 2026-08-22 (KC-EMBED),
        // so --enable-libdav1d and --enable-decoder=libdav1d are RECIPE, and a tree without
        // them is genuinely stale. --pkg-config=pkg-config is filtered by key above.

        /**
         * A deployment floor, wherever it rides.
         *
         * The platform name may itself carry a hyphen (`ios-simulator`), so device and simulator
         * floors stay distinguishable rather than collapsing onto one token.
         */
        private val DEPLOYMENT_FLOOR = Regex("""-m([a-z][a-z-]*)-version-min=([0-9][0-9.]*)""")

        /**
         * The synthetic key the extracted floor is filed under, and it is `--` prefixed ON PURPOSE.
         *
         * [recipeFingerprint] must be IDEMPOTENT: `CheckFFmpegRecipesTask` stores an already
         * fingerprinted set as its `@Input` and hands it back to [staleReason], which fingerprints
         * it again. Every other token survives that because every other token is a real `--flag`.
         * A bare `deployment-floor=...` did not survive the `--` filter, so the expected side lost
         * it while the installed side kept it, and every iOS tree was reported stale for a floor
         * that had never moved. This is never passed to configure; it exists only in the set.
         */
        private const val DEPLOYMENT_FLOOR_KEY = "--deployment-floor"

        /**
         * The CAPABILITY half of a configure command, as a comparable set.
         *
         * Everything that decides what the resulting FFmpeg can DO (which decoders, demuxers,
         * hwaccels, filters and features it carries) and nothing that decides where it was built.
         * Two renderings of one recipe must produce equal sets, so this also unquotes: FFmpeg's own
         * `config.log` echo writes `--enable-hwaccel='a,b'` where the task passes
         * `--enable-hwaccel=a,b`.
         *
         * Splitting a `config.log` line on spaces shreds multi-word values such as
         * `--cc=clang -arch arm64 ...` into fragments; the fragments do not start with `--` and are
         * dropped, and the `--cc=clang` head is dropped by key, so the shredding cannot invent a
         * difference.
         *
         * **The deployment floor is the one exception, and it is read back OUT of that shredding**
         * (KC-FLOOR-DRIFT). Which OS version a tree runs on is a capability, not a build location,
         * but it rides inside `--cc`, which is machine-specific by key and therefore stripped. The
         * floor was invisible on both sides: the task's unsplit `--cc=...` string is dropped by key,
         * and the installed side's `-mmacosx-version-min=12.0'` fragment starts with ONE dash, so
         * the `--` filter dropped it too. SOL-B4 pinned the macOS floor on 2026-08-25 and this check
         * could not have noticed if a tree ignored the pin. Scanning every token for the floor
         * pattern catches it from either rendering, and quoting is handled by the value pattern
         * stopping before the quote rather than by trimming.
         */
        public fun recipeFingerprint(args: List<String>): Set<String> {
            val capabilities = args.asSequence()
                .map { it.trim() }
                .filter { it.startsWith("--") }
                .map { arg ->
                    val equals = arg.indexOf('=')
                    if (equals < 0) arg else {
                        arg.substring(0, equals) + "=" + arg.substring(equals + 1).trim('\'', '"')
                    }
                }
                .filterNot { it.substringBefore('=') in MACHINE_SPECIFIC_CONFIGURE_KEYS }
                .filterNot { '/' in it.substringAfter('=', "") }
            val floors = args.asSequence()
                .flatMap { DEPLOYMENT_FLOOR.findAll(it) }
                .map { "$DEPLOYMENT_FLOOR_KEY=${it.groupValues[1]}:${it.groupValues[2]}" }
            return (capabilities + floors).toSet()
        }

        /**
         * Why an installed tree no longer matches the recipe this checkout describes, or null when
         * it does.
         *
         * THE HOLE THIS CLOSES. A baked tree is a dead artifact: nothing rebuilds it, and no gate
         * compared it against anything. Measured on 2026-08-19: `av1_videotoolbox` was pinned into
         * the Apple hwaccel list, every Apple tree on this machine still lacked it a day later, and
         * not one check anywhere was red. The recipe and the artifact drifted in silence, which is
         * the same failure shape the register keeps recording in prose.
         *
         * [installedConfigureLine] is the tree's own stamp, written by [writeConfigureEvidence].
         */
        public fun staleReason(
            installedConfigureLine: String,
            expectedArgs: List<String>,
        ): String? {
            val installed = recipeFingerprint(installedConfigureLine.split(" "))
            val expected = recipeFingerprint(expectedArgs)
            if (installed == expected) return null
            val dropped = (installed - expected).sorted()
            val gained = (expected - installed).sorted()
            return buildString {
                append("it was baked with a different recipe than this checkout now describes")
                if (gained.isNotEmpty()) {
                    append("\n    now asked for, but NOT in the tree: ")
                    append(gained.joinToString("\n                                  "))
                }
                if (dropped.isNotEmpty()) {
                    append("\n    in the tree, but no longer asked for: ")
                    append(dropped.joinToString("\n                                          "))
                }
            }
        }

        /** Creates the unique hash-free workspace that is the only path configure and make see. */
        internal fun createScratchWorkspace(temporaryRoot: Path): Path {
            Files.createDirectories(temporaryRoot)
            val workspace = Files.createTempDirectory(temporaryRoot, "kitecodec-ffmpeg-")
            require('#' !in workspace.toAbsolutePath().toString()) {
                "java.io.tmpdir must resolve to a path without '#': $workspace"
            }
            return workspace
        }

        /** Copies FFmpeg source while excluding repository metadata and every stale build subtree. */
        internal fun copySourceTree(source: Path, destination: Path) {
            copyTree(source, destination, excludeBuildState = true)
        }

        private fun copyTree(source: Path, destination: Path, excludeBuildState: Boolean) {
            Files.walkFileTree(
                source,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (
                            dir != source &&
                            excludeBuildState &&
                            (dir.fileName.toString() == ".git" || dir.fileName.toString() == "build")
                        ) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        Files.createDirectories(destination.resolve(source.relativize(dir)))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val target = destination.resolve(source.relativize(file))
                        Files.createDirectories(target.parent)
                        Files.copy(
                            file,
                            target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        )
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }

        /**
         * Installs the first configure invocation recorded by FFmpeg into the output tree.
         *
         * FFmpeg prefixes that line with `# ` in `ffbuild/config.log`; only that exact marker is
         * removed. The command itself is otherwise byte-for-byte stable and the installed record
         * is always one UTF-8 line terminated by exactly one newline.
         */
        internal fun writeConfigureEvidence(configLog: Path, install: Path) {
            check(Files.isRegularFile(configLog)) {
                "FFmpeg configure provenance is missing: $configLog"
            }
            val firstLine = Files.newBufferedReader(configLog, UTF_8).use { reader -> reader.readLine() }
                ?.removePrefix("# ")
            check(!firstLine.isNullOrBlank()) {
                "FFmpeg configure provenance has no nonblank first line: $configLog"
            }
            val evidence = install.resolve(CONFIGURE_EVIDENCE_RELATIVE_PATH)
            Files.createDirectories(evidence.parent)
            Files.writeString(evidence, "$firstLine\n", UTF_8)
        }

        internal fun verifyInstall(install: Path) {
            val missing = REQUIRED_LIBS.filterNot { Files.isRegularFile(install.resolve("lib/$it.a")) }
            check(missing.isEmpty()) {
                "FFmpeg reported a successful install but $install is missing " +
                    "${missing.joinToString { "lib/$it.a" }}. Check the configure/make output; " +
                    "the scratch install prefix may not have been honoured."
            }
            check(Files.isRegularFile(install.resolve("include/libavformat/avformat.h"))) {
                "FFmpeg installed libraries into $install but no headers; cinterop needs both."
            }
            val evidence = install.resolve(CONFIGURE_EVIDENCE_RELATIVE_PATH)
            check(Files.isRegularFile(evidence)) {
                "FFmpeg installed libraries into $install but no configure provenance record at " +
                    CONFIGURE_EVIDENCE_RELATIVE_PATH + "."
            }
            val evidenceText = Files.readString(evidence, UTF_8)
            check(
                evidenceText.endsWith('\n') &&
                    evidenceText.count { it == '\n' } == 1 &&
                    '\r' !in evidenceText &&
                    evidenceText.removeSuffix("\n").isNotBlank()
            ) {
                "FFmpeg configure provenance at $evidence must be one nonblank UTF-8 line " +
                    "terminated by exactly one newline."
            }
        }

        /** Verifies a sibling staging copy before swapping it into the declared output location. */
        internal fun replaceOutputTree(scratchInstall: Path, output: Path) {
            val absoluteOutput = output.toAbsolutePath().normalize()
            val parent = requireNotNull(absoluteOutput.parent) {
                "FFmpeg output has no parent directory: $output"
            }
            Files.createDirectories(parent)
            val nonce = UUID.randomUUID().toString()
            val staging = parent.resolve(".${absoluteOutput.fileName}.staging-$nonce")
            val backup = parent.resolve(".${absoluteOutput.fileName}.backup-$nonce")
            var movedOldOutput = false
            try {
                copyTree(scratchInstall, staging, excludeBuildState = false)
                verifyInstall(staging)
                if (Files.exists(absoluteOutput)) {
                    moveDirectory(absoluteOutput, backup)
                    movedOldOutput = true
                }
                try {
                    moveDirectory(staging, absoluteOutput)
                } catch (failure: Throwable) {
                    if (movedOldOutput) moveDirectory(backup, absoluteOutput)
                    throw failure
                }
                if (movedOldOutput) backup.toFile().deleteRecursively()
            } finally {
                staging.toFile().deleteRecursively()
                if (!Files.exists(absoluteOutput) && movedOldOutput && Files.exists(backup)) {
                    moveDirectory(backup, absoluteOutput)
                }
            }
        }

        private fun moveDirectory(source: Path, destination: Path) {
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, destination)
            }
        }
    }
}
