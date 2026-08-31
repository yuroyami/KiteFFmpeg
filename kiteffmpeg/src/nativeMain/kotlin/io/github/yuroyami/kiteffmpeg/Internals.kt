package io.github.yuroyami.kiteffmpeg

import ffmpeg.KC_FFMPEG_LIBRARY_COUNT
import ffmpeg.ffkmp_averror_eagain
import ffmpeg.ffkmp_averror_eof
import ffmpeg.ffkmp_pix_fmt_from_name
import ffmpeg.ffkmp_pix_fmt_name
import ffmpeg.ffkmp_sample_fmt_from_name
import ffmpeg.ffkmp_sample_fmt_name
import ffmpeg.ffkmp_strerror
import ffmpeg.kc_ffmpeg_library_name
import ffmpeg.kc_ffmpeg_report
import ffmpeg.kc_ffmpeg_report_get
import ffmpeg.kc_init
import ffmpeg.kc_verdict_name
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
// The primitive-specialised `CPointer<IntVarOf<Int>>.get(index)` operator, which is what makes
// `report.header_major[index]` read element `index` as an Int. Without this import the `[]` resolves
// against MatchGroupCollection.get and the file does not compile, which is a useful accident: the
// report's arrays are one dimensional precisely so that indexing means what it looks like.
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString

/** Cached AVERROR codes. Read once at first call (lazy through `by lazy`). */
internal object FFErrors {
    val EAGAIN: Int by lazy { ffkmp_averror_eagain() }
    val EOF: Int    by lazy { ffkmp_averror_eof() }
}

/**
 * The FFmpeg header versus runtime identity gate, on the Kotlin side.
 *
 * Every public entry point in this library calls [requireCompatibleFFmpeg] as its first statement,
 * before it allocates anything. Not a Kotlin `object` initialiser: Kotlin/Native initialises an object
 * when it first becomes reachable on the calling thread, which gives no ordering guarantee relative to
 * a different entry point on a different thread, and the whole value of the gate is that it runs before
 * the first FFmpeg struct is touched. `kc_init` is guarded by `pthread_once` in C, so this is one
 * process-wide run and every later call is a load of a cached int.
 */
internal fun requireCompatibleFFmpeg() {
    if (kc_init() == 0) return
    throw FFmpegException(FFmpegError.IncompatibleFFmpegRuntime(ffmpegIdentity))
}

/**
 * The identity report, read once.
 *
 * Cached because the report cannot change: the C side fills it inside its own once-only block and never
 * writes it again. Reading it is one `nativeHeap.alloc` plus plain field reads, which is the reason the
 * report is flat plain data with fixed char arrays and no pointers.
 */
internal val ffmpegIdentity: FFmpegIdentity by lazy { readFFmpegIdentity() }

private fun readFFmpegIdentity(): FFmpegIdentity {
    val report = nativeHeap.alloc<kc_ffmpeg_report>()
    try {
        kc_ffmpeg_report_get(report.ptr)
        return report.ptr.toFFmpegIdentity()
    } finally {
        nativeHeap.free(report)
    }
}

/**
 * Turns a filled `kc_ffmpeg_report` into the public [FFmpegIdentity].
 *
 * `internal` and taking the pointer rather than reading the singleton, so `FFmpegIdentityTest` can hand
 * it a report it filled itself and assert the translation on a REJECTING shape. The C side's own
 * verdicts are proved by `native/kitecodec-c/tests/test_identity.c` against doctored header trees; what
 * this function has to get right is that both columns, both licence strings and the provisioning
 * sentence survive the crossing, and that is what a synthetic report can check on a healthy machine.
 *
 * Every per-library name comes from `kc_ffmpeg_library_name(i)` and every verdict from
 * `kc_verdict_name(v)`, so neither table is written down a second time here.
 */
internal fun CPointer<kc_ffmpeg_report>.toFFmpegIdentity(): FFmpegIdentity {
    val report = this.pointed
    val libraries = (0 until KC_FFMPEG_LIBRARY_COUNT).map { index ->
        FFmpegLibraryIdentity(
            name = kc_ffmpeg_library_name(index)?.toKString() ?: "",
            headerMajor = report.header_major[index],
            headerMinor = report.header_minor[index],
            headerMicro = report.header_micro[index],
            runtimeMajor = report.runtime_major[index],
            runtimeMinor = report.runtime_minor[index],
            runtimeMicro = report.runtime_micro[index],
            verdict = kc_verdict_name(report.verdict[index])?.toKString() ?: "unknown",
        )
    }
    val disagreed = report.configuration_disagreed.toKString()
    return FFmpegIdentity(
        status = report.status,
        bypassed = report.bypassed != 0,
        bypassedStatus = report.bypassed,
        cAbiVersion = "${report.abi_major}.${report.abi_minor}",
        libraries = libraries,
        configurationsAgree = report.configuration_agrees != 0,
        configurationsDisagreed = if (disagreed.isEmpty()) emptyList() else disagreed.split(", "),
        buildFFmpegRef = report.build_ffmpeg_ref.toKString(),
        buildLicenseFlavour = report.build_license_flavour.toKString(),
        buildProvisioningDir = report.build_provisioning_dir.toKString(),
        runtimeVersionInfo = report.runtime_version_info.toKString(),
        runtimeLicense = report.runtime_license.toKString(),
        provisioning = report.provisioning.toKString(),
    )
}

/** Wrap an FFmpeg int return code as a typed [FFmpegError] (semantic classification). */
internal fun avError(code: Int): FFmpegError {
    val msg = ffkmp_strerror(code)?.toKString() ?: "AVERROR($code)"
    return FFmpegError.fromCode(code, "$msg (code=$code)")
}

internal fun check0(rc: Int, label: String) {
    if (rc < 0) {
        val err = avError(rc)
        throw FFmpegException(FFmpegError.fromCode(err.code, "$label: ${err.message}"))
    }
}

internal fun pixelFormatFromAv(value: Int): PixelFormat {
    if (value < 0) return PixelFormat.None
    val name = ffkmp_pix_fmt_name(value)?.toKString() ?: return PixelFormat.None
    return PixelFormat(name)
}

internal fun pixelFormatToAv(format: PixelFormat): Int = ffkmp_pix_fmt_from_name(format.name)

internal fun sampleFormatFromAv(value: Int): SampleFormat {
    if (value < 0) return SampleFormat.None
    val name = ffkmp_sample_fmt_name(value)?.toKString() ?: return SampleFormat.None
    return SampleFormat(name)
}

internal fun sampleFormatToAv(format: SampleFormat): Int = ffkmp_sample_fmt_from_name(format.name)
