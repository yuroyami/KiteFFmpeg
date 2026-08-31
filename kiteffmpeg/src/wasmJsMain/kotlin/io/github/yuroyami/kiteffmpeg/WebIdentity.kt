package io.github.yuroyami.kiteffmpeg

import io.github.yuroyami.kiteffmpeg.wasm.ReportLayout
import io.github.yuroyami.kiteffmpeg.wasm.kc_ffmpeg_library_name
import io.github.yuroyami.kiteffmpeg.wasm.kc_ffmpeg_report_get
import io.github.yuroyami.kiteffmpeg.wasm.kc_verdict_name

/**
 * Reads `kc_ffmpeg_report` out of the codec module's memory.
 *
 * The whole struct in one call, then field by field at generated offsets, exactly as the JNI
 * adapter does it in C. Nothing here counts bytes by hand: `ReportLayout` is `offsetof()` output
 * and `scripts/wasm-report-offsets.sh` fails the build if the struct moves underneath it.
 */
internal fun webIdentity(): FFmpegIdentity {
    val m = requireModule()
    val buffer = wasmAlloc(m, ReportLayout.SIZE_OF)
    try {
        kc_ffmpeg_report_get(m, buffer)
        fun int(offset: Int) = readInt32(m, buffer + offset)
        fun intAt(base: Int, index: Int) = readInt32(m, buffer + base + index * 4)
        fun text(offset: Int, limit: Int) = readFixedString(m, buffer + offset, limit)

        val libraries = (0 until ReportLayout.LIBRARY_COUNT).map { i ->
            FFmpegLibraryIdentity(
                name = readFixedString(m, kc_ffmpeg_library_name(m, i), NAME_SCAN),
                headerMajor = intAt(ReportLayout.headerMajor, i),
                headerMinor = intAt(ReportLayout.headerMinor, i),
                headerMicro = intAt(ReportLayout.headerMicro, i),
                runtimeMajor = intAt(ReportLayout.runtimeMajor, i),
                runtimeMinor = intAt(ReportLayout.runtimeMinor, i),
                runtimeMicro = intAt(ReportLayout.runtimeMicro, i),
                verdict = readFixedString(m, kc_verdict_name(m, intAt(ReportLayout.verdict, i)), NAME_SCAN),
            )
        }
        val disagreed = text(ReportLayout.configurationDisagreed, ReportLayout.TEXT_LIST)
        return FFmpegIdentity(
            status = int(ReportLayout.status),
            bypassed = int(ReportLayout.bypassed) != 0,
            // Always 0, and that is a limit of the C report rather than a value.
            // `kc_ffmpeg_report` carries the post-bypass status and no original one, so what the
            // status WOULD have been cannot be recovered here. The first draft derived it from
            // `status`, which is 0 in exactly the case `bypassed` is true, so the field looked
            // populated while carrying nothing. Reported as unknown instead.
            bypassedStatus = 0,
            cAbiVersion = "${int(ReportLayout.abiMajor)}.${int(ReportLayout.abiMinor)}",
            libraries = libraries,
            configurationsAgree = int(ReportLayout.configurationAgrees) != 0,
            configurationsDisagreed = disagreed.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            buildFFmpegRef = text(ReportLayout.buildFfmpegRef, ReportLayout.TEXT_REF),
            buildLicenseFlavour = text(ReportLayout.buildLicenseFlavour, ReportLayout.TEXT_REF),
            buildProvisioningDir = text(ReportLayout.buildProvisioningDir, ReportLayout.TEXT_PATH),
            runtimeVersionInfo = text(ReportLayout.runtimeVersionInfo, ReportLayout.TEXT_NAME),
            runtimeLicense = text(ReportLayout.runtimeLicense, ReportLayout.TEXT_NAME),
            provisioning = text(ReportLayout.provisioning, ReportLayout.TEXT_SENTENCE),
        )
    } finally {
        wasmFree(m, buffer)
    }
}

/** Library and verdict names are C literals of unknown length; this bounds the scan generously. */
private const val NAME_SCAN = 64
