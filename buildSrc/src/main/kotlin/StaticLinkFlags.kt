package io.github.yuroyami.kiteffmpeg.buildtools

/**
 * What a final native link needs when KiteFFmpeg is built against a VENDORED STATIC FFmpeg
 * (`native-libs/<license>/<target>/`).
 *
 * `ffmpeg.def` names only the six libav* libraries. That is enough for a shared/system FFmpeg,
 * whose dylibs resolve their own dependencies at load time, but a static `libavcodec.a` resolves
 * nothing: every symbol it draws from outside itself must be named at the consumer's link.
 *
 * Since the portable profiles (owner decision 2026-08-22) every target's needs are platform
 * services plus exactly ONE third-party archive, the cross-built dav1d (mandatory):
 *  - Apple (macOS and iOS): SDK zlib plus the media frameworks the VideoToolbox/AudioToolbox
 *    codecs reference.
 *  - Linux: zlib, maths, dynamic loader, pthreads (all from the konan sysroot / OS).
 *  - Windows (mingw): zlib, iconv (both from the msys2 sysroot) plus the sockets/media/COM APIs
 *    FFmpeg's network and mpegts code reaches for.
 *  - Android: nothing (MediaCodec is a platform service; zlib is a platform library named by
 *    the def file's linker opts).
 *
 * Consumers never see this class: since FFmpeg was embedded the published klibs EMBED the archives and the
 * def file carries the platform flags, so the per-target lists here and in `ffmpeg.def` are two
 * renderings of one truth. KEEP THEM IN AGREEMENT.
 */
object StaticLinkFlags {

    private val APPLE_TARGETS = setOf(
        TargetTriple.MacosArm64, TargetTriple.MacosX64,
        TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64, TargetTriple.IosX64,
    )

    /**
     * Archive FILENAMES to bundle into the vendored tree's `lib/`, so it is self-contained.
     * OS/SDK-provided libraries (zlib, iconv) are deliberately excluded: they must come from the
     * platform, not from a copy. Since the portable profiles this is dav1d only, and dav1d is
     * MANDATORY since 2026-08-22.
     */
    fun thirdPartyArchives(target: TargetTriple, license: FFmpegLicense): List<String> =
        listOf("libdav1d.a")

    /**
     * Extra `-L` search paths for a LOCAL vendored build. Since the portable profiles no target
     * resolves anything from a host package manager, so this is always empty. The signature stays
     * because `kiteffmpeg-core/build.gradle.kts` wires it per target, and a future profile that
     * reintroduces a host dependency changes this ONE function instead of that script.
     */
    fun hostFallbackSearchFlags(
        target: TargetTriple,
        hostPrefix: String,
        isStaticVendored: Boolean,
    ): List<String> = emptyList()

    /** The `-l` flags the final link needs: dav1d first (always carried), then platform basics. */
    fun forTarget(
        target: TargetTriple,
        license: FFmpegLicense,
        isStaticVendored: Boolean,
    ): List<String> {
        if (!isStaticVendored) return emptyList()
        // dav1d first: libavcodec draws from it, and GNU ld resolves static archives left to right.
        return listOf("-ldav1d") + when {
            target in APPLE_TARGETS -> listOf(
                "-lz",
                // Every Apple profile enables VideoToolbox (decode everywhere, encode outside the
                // simulators) and AudioToolbox, so the static archives carry undefined references
                // into the media frameworks. A shared build resolved these through its own dylib
                // dependencies; a static one must name them.
                "-framework", "CoreFoundation",
                "-framework", "CoreMedia",
                "-framework", "CoreVideo",
                "-framework", "VideoToolbox",
                "-framework", "AudioToolbox",
            )
            // iconv backs FFmpeg's subtitle charset conversion and msys2's sysroot has it, so the
            // mingw configure autodetects it and the link must name it, along with the Windows
            // sockets, media and time APIs FFmpeg's network and mpegts code reaches for.
            target == TargetTriple.MingwX64 -> listOf(
                "-lz", "-liconv",
                "-lws2_32", "-lbcrypt", "-lsecur32", "-lmfplat", "-lole32", "-lstrmiids", "-luuid",
            )
            target == TargetTriple.LinuxX64 || target == TargetTriple.LinuxArm64 -> listOf(
                "-lz", "-lm", "-ldl", "-lpthread",
            )
            // Android: the def file already names zlib; MediaCodec is a platform service.
            else -> emptyList()
        }
    }
}
