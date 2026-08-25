package io.github.yuroyami.kitecodec

import io.github.yuroyami.kitecodec.wasm.ReportLayout
import kotlin.js.JsAny
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The identity report crossing out of codec memory into Kotlin.
 *
 * This is the layer nothing could reach before: `webIdentity` reads a 2,176-byte C struct field by
 * field at generated offsets, and a wrong offset reads the NEIGHBOURING field and answers something
 * plausible. Every assertion here fails if a number in `ReportLayout` moves without the struct
 * moving with it, which is the whole reason that file is generated rather than written.
 */
class WebIdentityTest {

    @BeforeTest fun start() = forgetCodecModule()

    @AfterTest fun finish() = forgetCodecModule()

    @Test
    fun aHealthyReportDecodesFieldForField() {
        val module = fakeCodecModule()
        stageHealthyReport(module)
        useCodecModule(module)

        val identity = FFmpeg.identity

        assertEquals(0, identity.status)
        assertTrue(identity.isAcceptable)
        assertFalse(identity.bypassed)
        assertEquals("3.7", identity.cAbiVersion)
        assertEquals("n8.0", identity.buildFFmpegRef)
        assertEquals("lgpl", identity.buildLicenseFlavour)
        assertEquals("/opt/kite/ffmpeg", identity.buildProvisioningDir)
        assertEquals("8.0-static", identity.runtimeVersionInfo)
        assertEquals("LGPL version 2.1 or later", identity.runtimeLicense)
        assertEquals("Link the vendored LGPL tree, or rebuild it with scripts/build-ffmpeg.sh.", identity.provisioning)
        assertTrue(identity.configurationsAgree)
        assertEquals(emptyList(), identity.configurationsDisagreed)
    }

    @Test
    fun allSixLibraryRowsCarryTheirOwnTwoVersionColumns() {
        val module = fakeCodecModule()
        stageHealthyReport(module)
        useCodecModule(module)

        val libraries = FFmpeg.identity.libraries

        assertEquals(ReportLayout.LIBRARY_COUNT, libraries.size)
        assertEquals(
            listOf("libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample"),
            libraries.map { it.name },
        )
        // Each row was staged with a distinct value per column, so a row reading its NEIGHBOUR's
        // slot, or one column reading another's, lands on a number that is written down here.
        assertEquals("60.1.100", libraries[0].headerVersion)
        assertEquals("60.1.101", libraries[0].runtimeVersion)
        assertEquals("65.6.105", libraries[5].headerVersion)
        assertEquals("65.6.106", libraries[5].runtimeVersion)
        assertTrue(libraries.all { it.isOk })
        assertEquals(emptyList(), FFmpeg.identity.problems)
    }

    @Test
    fun aRejectedRuntimeReportsItsStatusAndNamesTheLibrariesThatFailed() {
        val module = fakeCodecModule()
        stageHealthyReport(module)
        val report = stagedReportPointer(module)
        writeInt32(module, report + ReportLayout.status, -22)
        // libavcodec and libavformat are the two that disagree; the other four stay ok.
        writeInt32(module, report + ReportLayout.verdict + 1 * 4, VERDICT_INCOMPATIBLE)
        writeInt32(module, report + ReportLayout.verdict + 2 * 4, VERDICT_RUNTIME_NEWER)
        useCodecModule(module)

        val identity = FFmpeg.identity

        assertEquals(-22, identity.status)
        assertFalse(identity.isAcceptable)
        assertEquals(listOf("libavcodec", "libavformat"), identity.problems.map { it.name })
        assertEquals("incompatible", identity.libraries[1].verdict)
        assertEquals("runtime_newer", identity.libraries[2].verdict)
    }

    @Test
    fun aMixedInstallReportsTheDisagreeingLibrariesAsASplitList() {
        val module = fakeCodecModule()
        stageHealthyReport(module)
        val report = stagedReportPointer(module)
        writeInt32(module, report + ReportLayout.configurationAgrees, 0)
        writeCString(module, report + ReportLayout.configurationDisagreed, "libavcodec, libswscale")
        useCodecModule(module)

        val identity = FFmpeg.identity

        assertFalse(identity.configurationsAgree)
        assertEquals(listOf("libavcodec", "libswscale"), identity.configurationsDisagreed)
    }

    /**
     * The bypass flag is its own field, not a reading of [FFmpegIdentity.status].
     *
     * A bypassed run reports status 0, so a decoder that derived the flag from the status would
     * look correct on every healthy report and be wrong on exactly the case the flag exists for.
     */
    @Test
    fun theBypassFlagIsReadFromItsOwnSlot() {
        val module = fakeCodecModule()
        stageHealthyReport(module)
        writeInt32(module, stagedReportPointer(module) + ReportLayout.bypassed, 1)
        useCodecModule(module)

        val identity = FFmpeg.identity

        assertTrue(identity.bypassed)
        assertEquals(0, identity.status)
    }

    /**
     * Reading the report copies 2,176 bytes and decodes seven strings, so it is cached per module.
     * Pinned because the cache key is module identity, and a key on anything else would serve a
     * stale answer across a reload.
     */
    @Test
    fun identityIsComputedOncePerLoadedModule() {
        val first = fakeCodecModule()
        stageHealthyReport(first)
        useCodecModule(first)
        val a = FFmpeg.identity
        val b = FFmpeg.identity
        assertSame(a, b, "a second read of the same module must not recompute the report")

        val second = fakeCodecModule()
        stageHealthyReport(second)
        writeCString(module = second, pointer = stagedReportPointer(second) + ReportLayout.buildFfmpegRef, text = "n7.1")
        useCodecModule(second)
        val c = FFmpeg.identity
        assertEquals("n7.1", c.buildFFmpegRef, "a new module must be read afresh, not served from the cache")
    }

    @Test
    fun describeCarriesEveryRowAndTheVerdictLine() {
        val module = fakeCodecModule()
        stageHealthyReport(module)
        useCodecModule(module)

        val text = FFmpeg.identity.describe()

        assertTrue("acceptable" in text, text)
        assertTrue("built for FFmpeg n8.0, lgpl flavour" in text, text)
        for (name in listOf("libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample")) {
            assertTrue("  $name: headers" in text, "describe must carry a row for $name:\n$text")
        }
        assertTrue("configure lines agree" in text, text)
    }
}

private const val VERDICT_OK = 0
private const val VERDICT_RUNTIME_NEWER = 2
private const val VERDICT_INCOMPATIBLE = 3

/**
 * Writes a healthy `kc_ffmpeg_report` into the fake module's staging area.
 *
 * Every library row gets values derived from its own index, so a field that reads the wrong slot
 * returns a number this file can name rather than one that happens to look reasonable.
 */
private fun stageHealthyReport(module: JsAny) {
    val report = stagedReportPointer(module)
    fun int(offset: Int, value: Int) = writeInt32(module, report + offset, value)
    fun perLibrary(base: Int, value: (Int) -> Int) {
        for (i in 0 until ReportLayout.LIBRARY_COUNT) writeInt32(module, report + base + i * 4, value(i))
    }
    fun text(offset: Int, value: String) = writeCString(module, report + offset, value)

    int(ReportLayout.status, 0)
    int(ReportLayout.bypassed, 0)
    int(ReportLayout.abiMajor, 3)
    int(ReportLayout.abiMinor, 7)
    perLibrary(ReportLayout.headerMajor) { 60 + it }
    perLibrary(ReportLayout.headerMinor) { 1 + it }
    perLibrary(ReportLayout.headerMicro) { 100 + it }
    perLibrary(ReportLayout.runtimeMajor) { 60 + it }
    perLibrary(ReportLayout.runtimeMinor) { 1 + it }
    perLibrary(ReportLayout.runtimeMicro) { 101 + it }
    perLibrary(ReportLayout.verdict) { VERDICT_OK }
    int(ReportLayout.configurationAgrees, 1)
    int(ReportLayout.configurationDisagreedCount, 0)
    text(ReportLayout.configurationDisagreed, "")
    text(ReportLayout.buildFfmpegRef, "n8.0")
    text(ReportLayout.buildLicenseFlavour, "lgpl")
    text(ReportLayout.buildProvisioningDir, "/opt/kite/ffmpeg")
    text(ReportLayout.runtimeVersionInfo, "8.0-static")
    text(ReportLayout.runtimeLicense, "LGPL version 2.1 or later")
    text(ReportLayout.provisioning, "Link the vendored LGPL tree, or rebuild it with scripts/build-ffmpeg.sh.")
}
