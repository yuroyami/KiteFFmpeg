package io.github.yuroyami.kiteffmpeg

/**
 * Static facade for global FFmpeg state: version info, build flags and capability probing.
 *
 * Use it to feature-detect the bound FFmpeg before opening a codec or a filter, because builds
 * differ in what they contain. A system FFmpeg may or may not have `libx264`; KiteFFmpeg's
 * vendored LGPL profile never does, and asking for it there throws [FFmpegException] from
 * [MediaSink.addVideoEncoder].
 */
public expect object FFmpeg {

    /** Comma-separated configure flags the bound FFmpeg was built with. */
    public val buildConfiguration: String

    /** Per-library version triplets, useful for compatibility checks. */
    public val versions: Versions

    /**
     * What FFmpeg this build expected and what it actually got.
     *
     * The one member of this facade that never throws. Everything else here rejects an incompatible
     * runtime with [FFmpegException], and a diagnostic that could not be read on a rejected runtime
     * would be a diagnostic that is unavailable exactly when it is needed. Put this in a bug report.
     */
    public val identity: FFmpegIdentity

    /** Whether the bound FFmpeg has a given encoder compiled in. Pass an FFmpeg codec name. */
    public fun hasEncoder(name: String): Boolean

    /** Whether the bound FFmpeg has a given decoder compiled in. */
    public fun hasDecoder(name: String): Boolean

    /** Whether the bound FFmpeg has a given filter compiled in. */
    public fun hasFilter(name: String): Boolean
}

/**
 * Per-library version triplets, both columns.
 *
 * The `*Header` values are what the headers said when KiteFFmpeg's C layer was compiled; the others
 * are what the linked runtime answers today. Two columns and not one, because a single column cannot
 * express the failure that matters: the two disagreeing while every symbol still resolves.
 */
public data class Versions(
    val avutil:     String,
    val avcodec:    String,
    val avformat:   String,
    val avfilter:   String,
    val swscale:    String,
    val swresample: String,
    val avutilHeader:     String,
    val avcodecHeader:    String,
    val avformatHeader:   String,
    val avfilterHeader:   String,
    val swscaleHeader:    String,
    val swresampleHeader: String,
)

/**
 * The FFmpeg identity report: what this build was compiled against, what it is linked to, and
 * whether the two are compatible.
 *
 * **Why this type exists.** In the direction that matters, older headers against a newer runtime,
 * every symbol resolves and the link succeeds while struct field offsets are wrong. 38 offsets were
 * measured to move across one FFmpeg major, and the failure that follows is a wrong value read and
 * then a crash inside FFmpeg's own code, or nothing visible at all. Version numbers alone do not even
 * cover it: six libraries taken from two different builds can report six agreeing triples and still be
 * a mixed install, which is what [configurationsAgree] is for.
 *
 * KiteFFmpeg compares the two columns once per process, before anything allocates, and refuses to run
 * on a combination it knows is unsafe. This report is what that refusal carries, and it is also
 * readable on a healthy runtime as an ordinary diagnostic.
 */
public class FFmpegIdentity(
    /** 0 when the runtime is acceptable, negative when it is not. */
    public val status: Int,
    /**
     * True when a rejection was downgraded to a warning by the diagnostic escape hatch.
     *
     * The escape hatch is opt-in, never the default, and never quiet: setting it prints a warning once
     * per process naming both identities. It exists so that a false rejection is not an outage inside
     * an application that cannot patch KiteFFmpeg. It is not a supported configuration, and this flag is
     * here so that no investigation ever starts from a gate that was bypassed silently.
     */
    public val bypassed: Boolean,
    /** What [status] would have been if the escape hatch had not been used; 0 when it was not. */
    public val bypassedStatus: Int,
    /** The version of KiteFFmpeg's own C surface, as `major.minor`. */
    public val cAbiVersion: String,
    /** One row per FFmpeg library, in the order libavutil, libavcodec, libavformat, libavfilter, libswscale, libswresample. */
    public val libraries: List<FFmpegLibraryIdentity>,
    /** Whether all six libraries report the same configure line. False means a mixed install. */
    public val configurationsAgree: Boolean,
    /** The libraries whose configure line differed from libavutil's. */
    public val configurationsDisagreed: List<String>,
    /** The FFmpeg release this artifact was built for, for example `n8.0`. */
    public val buildFFmpegRef: String,
    /**
     * The FFmpeg licence flavour this artifact was built for, `lgpl` or `gpl`.
     *
     * Compare it with [runtimeLicense]. They can disagree, and on a developer machine with a Homebrew
     * FFmpeg they routinely do: the build declares LGPL and the runtime answers "GPL version 3 or
     * later". KiteFFmpeg reports the pair rather than deciding what it means, because which licence
     * obligations a shipped application takes on is a distribution question and not an ABI one.
     */
    public val buildLicenseFlavour: String,
    /** The directory the build resolved FFmpeg from. */
    public val buildProvisioningDir: String,
    /** The linked runtime's own version string, from FFmpeg's `av_version_info`. */
    public val runtimeVersionInfo: String,
    /** The linked runtime's licence string, from FFmpeg's `avutil_license`. See [buildLicenseFlavour]. */
    public val runtimeLicense: String,
    /** One actionable sentence: what to link, or how to rebuild. Never empty. */
    public val provisioning: String,
) {
    /** True when KiteFFmpeg is willing to run against this runtime. */
    public val isAcceptable: Boolean get() = status == 0

    /** Every row whose verdict is not `ok`. Empty on a healthy runtime. */
    public val problems: List<FFmpegLibraryIdentity> get() = libraries.filter { !it.isOk }

    /** The whole report as one multi-line block, which is what an exception message and a bug report want. */
    public fun describe(): String = buildString {
        append("FFmpeg identity: ")
        append(if (isAcceptable) "acceptable" else "REJECTED")
        append(" (status=").append(status).append(')')
        if (bypassed) append(" BYPASSED, original status=").append(bypassedStatus)
        appendLine()
        appendLine("  built for FFmpeg $buildFFmpegRef, $buildLicenseFlavour flavour, from $buildProvisioningDir")
        appendLine("  runtime $runtimeVersionInfo, licence \"$runtimeLicense\", KiteFFmpeg C ABI $cAbiVersion")
        for (library in libraries) {
            appendLine("  ${library.name}: headers ${library.headerVersion}, runtime ${library.runtimeVersion}, ${library.verdict}")
        }
        append("  configure lines ")
        appendLine(if (configurationsAgree) "agree" else "DISAGREE: ${configurationsDisagreed.joinToString()}")
        append("  ").append(provisioning)
    }

    override fun toString(): String = describe()
}

/** One FFmpeg library's two version columns and the verdict comparing them. */
public class FFmpegLibraryIdentity(
    /** `libavutil`, `libavcodec` and so on. */
    public val name: String,
    public val headerMajor: Int,
    public val headerMinor: Int,
    public val headerMicro: Int,
    public val runtimeMajor: Int,
    public val runtimeMinor: Int,
    public val runtimeMicro: Int,
    /** `ok`, `major mismatch`, `runtime older than headers`, `micro older than headers` or `configuration disagrees`. */
    public val verdict: String,
) {
    /** What the headers said, as `major.minor.micro`. */
    public val headerVersion: String get() = "$headerMajor.$headerMinor.$headerMicro"

    /** What the runtime answers, as `major.minor.micro`. */
    public val runtimeVersion: String get() = "$runtimeMajor.$runtimeMinor.$runtimeMicro"

    /** True when this library's two columns are compatible. */
    public val isOk: Boolean get() = verdict == "ok"

    override fun toString(): String = "$name(headers=$headerVersion, runtime=$runtimeVersion, $verdict)"
}

/** Both version columns of [identity], flattened into the legacy [Versions] view. */
internal fun versionsFrom(identity: FFmpegIdentity): Versions = Versions(
    avutil = identity.libraries[0].runtimeVersion,
    avcodec = identity.libraries[1].runtimeVersion,
    avformat = identity.libraries[2].runtimeVersion,
    avfilter = identity.libraries[3].runtimeVersion,
    swscale = identity.libraries[4].runtimeVersion,
    swresample = identity.libraries[5].runtimeVersion,
    avutilHeader = identity.libraries[0].headerVersion,
    avcodecHeader = identity.libraries[1].headerVersion,
    avformatHeader = identity.libraries[2].headerVersion,
    avfilterHeader = identity.libraries[3].headerVersion,
    swscaleHeader = identity.libraries[4].headerVersion,
    swresampleHeader = identity.libraries[5].headerVersion,
)

/** Unpacks FFmpeg's `(major << 16) | (minor << 8) | micro` version representation. */
internal fun decodePackedVersion(packed: UInt): String {
    val major = (packed shr 16) and 0xFFu
    val minor = (packed shr 8) and 0xFFu
    val micro = packed and 0xFFu
    return "$major.$minor.$micro"
}
