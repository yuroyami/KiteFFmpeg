package io.github.yuroyami.kiteffmpeg

import ffmpeg.KC_FFMPEG_LIBRARY_COUNT
import ffmpeg.KC_LIB_AVCODEC
import ffmpeg.KC_LIB_AVFILTER
import ffmpeg.KC_LIB_AVUTIL
import ffmpeg.KC_STATUS_CONFIGURATION_MISMATCH
import ffmpeg.KC_STATUS_MAJOR_MISMATCH
import ffmpeg.KC_STATUS_OK
import ffmpeg.KC_VERDICT_CONFIGURATION_DISAGREES
import ffmpeg.KC_VERDICT_MAJOR_MISMATCH
import ffmpeg.kc_abi_version
import ffmpeg.kc_ffmpeg_report
import ffmpeg.kc_ffmpeg_report_get
import ffmpeg.kc_init
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIsNot
import kotlin.test.assertTrue

/**
 * The Kotlin half of the FFmpeg identity gate, register item B1-02.
 *
 * The division of labour between this file and `native/kitecodec-c/tests/test_identity.c` is
 * deliberate and worth stating, because it decides what each one is evidence for.
 *
 * The C suite owns the VERDICTS. It compiles the gate five more times against doctored header trees,
 * so it can put a real header major of 59 against a real runtime major of 60 and watch the rejection
 * happen. Nothing here can do that: the compiled archive this test links against was built against the
 * headers of the FFmpeg it is linked to, which is exactly the healthy case.
 *
 * This file owns the CROSSING and the SURFACE: that a filled report survives the trip into Kotlin with
 * both version columns, both licence strings and the provisioning sentence intact, that a rejecting
 * report becomes the typed error a consumer can catch, and that on a healthy runtime the gate lets
 * every entry point through. The rejecting reports below are real `kc_ffmpeg_report` structs, filled by
 * the C side and then mutated into the shape a rejection has, which is the only honest way to reach the
 * rejecting path from a process whose FFmpeg is fine.
 */
class FFmpegIdentityTest {

    @Test
    fun theLinkedRuntimeIsAccepted() {
        assertEquals(0, kc_init(), "kc_init rejected the runtime this test is linked against")
        val identity = FFmpeg.identity
        assertTrue(identity.isAcceptable, identity.describe())
        assertEquals(0, identity.status)
        assertFalse(identity.bypassed, "the diagnostic bypass must not be on by default")
        assertEquals(0, identity.bypassedStatus)
        assertEquals(emptyList(), identity.problems.map { it.name })
    }

    @Test
    fun everyLibraryReportsBothColumnsAndTheyAgree() {
        val identity = FFmpeg.identity
        assertEquals(KC_FFMPEG_LIBRARY_COUNT, identity.libraries.size)
        assertEquals(
            listOf("libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample"),
            identity.libraries.map { it.name },
        )
        for (library in identity.libraries) {
            assertEquals("ok", library.verdict, library.toString())
            assertEquals(library.headerVersion, library.runtimeVersion, library.name)
            assertTrue(
                Regex("""\d+\.\d+\.\d+""").matches(library.headerVersion),
                "bad header version for ${library.name}: ${library.headerVersion}",
            )
        }
    }

    @Test
    fun theSixConfigureLinesAgreeSoThisIsNotAMixedInstall() {
        val identity = FFmpeg.identity
        assertTrue(identity.configurationsAgree, identity.describe())
        assertEquals(emptyList(), identity.configurationsDisagreed)
    }

    /**
     * Register item B1-21. The build declares one FFmpeg licence flavour and the linked runtime answers
     * with another; on this machine that is `lgpl` against "GPL version 3 or later". The gate does not
     * resolve the contradiction, which is B7's, but it must always make it visible, so both fields have
     * to be populated in every report.
     */
    @Test
    fun bothLicenceFieldsArePopulated() {
        val identity = FFmpeg.identity
        assertTrue(identity.buildLicenseFlavour.isNotEmpty(), "buildLicenseFlavour is empty")
        assertTrue(identity.runtimeLicense.isNotEmpty(), "runtimeLicense is empty")
        assertContains(identity.describe(), identity.buildLicenseFlavour)
        assertContains(identity.describe(), identity.runtimeLicense)
    }

    @Test
    fun theReportNamesWhatTheBuildProvisioned() {
        val identity = FFmpeg.identity
        assertTrue(identity.buildFFmpegRef.isNotEmpty(), "buildFFmpegRef is empty")
        assertTrue(identity.buildProvisioningDir.isNotEmpty(), "buildProvisioningDir is empty")
        assertTrue(identity.runtimeVersionInfo.isNotEmpty(), "runtimeVersionInfo is empty")
        assertTrue(identity.provisioning.isNotEmpty(), "the provisioning sentence is empty")
        // The sentence has to be actionable, which means it names both the way out and the escape hatch.
        assertContains(identity.provisioning, "rebuild KiteFFmpeg")
        assertContains(identity.provisioning, "KITECODEC_FFMPEG_ABI_BYPASS")
    }

    @Test
    fun theReportCarriesTheCAbiVersion() {
        val identity = FFmpeg.identity
        assertEquals(decodePackedVersion(kc_abi_version()).substringBeforeLast('.'), identity.cAbiVersion)
    }

    @Test
    fun versionsCarriesBothColumns() {
        val versions = FFmpeg.versions
        val identity = FFmpeg.identity
        assertEquals(identity.libraries[KC_LIB_AVUTIL].runtimeVersion, versions.avutil)
        assertEquals(identity.libraries[KC_LIB_AVUTIL].headerVersion, versions.avutilHeader)
        assertEquals(identity.libraries[KC_LIB_AVCODEC].runtimeVersion, versions.avcodec)
        assertEquals(identity.libraries[KC_LIB_AVCODEC].headerVersion, versions.avcodecHeader)
        listOf(
            versions.avutil, versions.avcodec, versions.avformat,
            versions.avfilter, versions.swscale, versions.swresample,
            versions.avutilHeader, versions.avcodecHeader, versions.avformatHeader,
            versions.avfilterHeader, versions.swscaleHeader, versions.swresampleHeader,
        ).forEach {
            assertTrue(Regex("""\d+\.\d+\.\d+""").matches(it), "bad version string: $it")
        }
    }

    @Test
    fun buildConfigurationComesThroughTheGateAndIsNotEmpty() {
        assertTrue(FFmpeg.buildConfiguration.isNotEmpty())
    }

    /**
     * A healthy runtime must not be rejected, and the way to check that is to reach a real failure
     * through an entry point and see the failure it was actually asked about. A missing file has to
     * report a missing file, not an ABI verdict.
     */
    @Test
    fun theGateDoesNotSwallowARealError() {
        val failure = assertFailsWith<FFmpegException> {
            MediaSource.open("/definitely/not/a/media/file.mp4")
        }
        assertIsNot<FFmpegError.IncompatibleFFmpegRuntime>(failure.error, failure.error.toString())
        assertTrue(failure.error is FFmpegError.FileNotFound, failure.error.toString())
    }

    @Test
    fun aMajorMismatchReportBecomesTheTypedError() {
        withMutatedReport({ report ->
            report.status = KC_STATUS_MAJOR_MISMATCH
            report.header_major[KC_LIB_AVUTIL] = report.runtime_major[KC_LIB_AVUTIL] - 1
            report.verdict[KC_LIB_AVUTIL] = KC_VERDICT_MAJOR_MISMATCH.toInt()
        }) { identity ->
            assertFalse(identity.isAcceptable)
            assertEquals(KC_STATUS_MAJOR_MISMATCH, identity.status)
            assertEquals(listOf("libavutil"), identity.problems.map { it.name })
            assertEquals("major mismatch", identity.libraries[KC_LIB_AVUTIL].verdict)

            val error = FFmpegError.IncompatibleFFmpegRuntime(identity)
            // Its own category, catchable, and never confusable with a media error or an internal bug.
            assertIsNot<FFmpegError.Internal>(error)
            assertIsNot<FFmpegError.InvalidData>(error)
            // 0 and not the verdict: AVERROR(EPERM) is also -1, so a verdict in `code` would be
            // indistinguishable from a permission error.
            assertEquals(0, error.code)
            assertEquals(identity, error.identity)

            val exception = FFmpegException(error)
            assertTrue(exception.error is FFmpegError.IncompatibleFFmpegRuntime)
            val message = exception.message.orEmpty()
            assertContains(message, "REJECTED")
            assertContains(message, identity.libraries[KC_LIB_AVUTIL].headerVersion)
            assertContains(message, identity.libraries[KC_LIB_AVUTIL].runtimeVersion)
            assertContains(message, "major mismatch")
        }
    }

    @Test
    fun aMixedInstallReportNamesTheDisagreeingLibrary() {
        withMutatedReport({ report ->
            report.status = KC_STATUS_CONFIGURATION_MISMATCH
            report.configuration_agrees = 0
            report.configuration_disagreed_count = 1
            report.verdict[KC_LIB_AVFILTER] = KC_VERDICT_CONFIGURATION_DISAGREES.toInt()
            writeText(report.configuration_disagreed, "libavfilter")
        }) { identity ->
            assertFalse(identity.isAcceptable)
            assertFalse(identity.configurationsAgree)
            assertEquals(listOf("libavfilter"), identity.configurationsDisagreed)
            assertContains(identity.describe(), "DISAGREE")
        }
    }

    @Test
    fun aTwoLibraryDisagreementSplitsTheCommaSeparatedList() {
        withMutatedReport({ report ->
            report.configuration_agrees = 0
            report.configuration_disagreed_count = 2
            writeText(report.configuration_disagreed, "libavfilter, libswscale")
        }) { identity ->
            assertEquals(listOf("libavfilter", "libswscale"), identity.configurationsDisagreed)
        }
    }

    /**
     * The third condition the owner set for the diagnostic bypass: the fact that it was used has to be
     * recorded in the diagnostics a bug report carries, so no investigation starts from a silently
     * bypassed gate. The C side proves it emits the warning once; this proves the record crosses into
     * Kotlin and reaches the human-readable form.
     */
    @Test
    fun aBypassedGateSaysSoInTheDiagnostics() {
        withMutatedReport({ report ->
            report.status = KC_STATUS_OK
            report.bypassed = KC_STATUS_MAJOR_MISMATCH
            report.header_major[KC_LIB_AVUTIL] = report.runtime_major[KC_LIB_AVUTIL] - 1
            report.verdict[KC_LIB_AVUTIL] = KC_VERDICT_MAJOR_MISMATCH.toInt()
        }) { identity ->
            // Accepting, because that is what a bypass is for.
            assertTrue(identity.isAcceptable)
            // And loud about it, because that is the condition attached to it.
            assertTrue(identity.bypassed)
            assertEquals(KC_STATUS_MAJOR_MISMATCH, identity.bypassedStatus)
            assertContains(identity.describe(), "BYPASSED")
            assertContains(identity.describe(), "original status=$KC_STATUS_MAJOR_MISMATCH")
        }
    }

    /**
     * Takes a real report from the C side, applies [mutate] to it, and hands the translation to
     * [assertions]. The singleton report is never touched: this is a private copy on the native heap,
     * so a test that makes it look rejecting cannot make any other test in this process fail.
     */
    private fun withMutatedReport(
        mutate: (kc_ffmpeg_report) -> Unit,
        assertions: (FFmpegIdentity) -> Unit,
    ) {
        val report = nativeHeap.alloc<kc_ffmpeg_report>()
        try {
            kc_ffmpeg_report_get(report.ptr)
            mutate(report)
            assertions(report.ptr.toFFmpegIdentity())
        } finally {
            nativeHeap.free(report)
        }
        // The real gate is untouched by the mutation above.
        assertEquals(0, kc_init())
        assertTrue(FFmpeg.identity.isAcceptable)
    }

    /** Writes a NUL terminated string into one of the report's fixed char arrays. */
    private fun writeText(field: kotlinx.cinterop.CArrayPointer<kotlinx.cinterop.ByteVar>, text: String) {
        val bytes = text.encodeToByteArray()
        for (index in bytes.indices) field[index] = bytes[index]
        field[bytes.size] = 0
    }
}
