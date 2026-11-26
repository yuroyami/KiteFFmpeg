package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalLibrariesTest {

    // The banner of the macOS arm64 bake of FFmpeg 9.1, with configure's column padding.
    private val macosBanner = listOf(
        "threading support         pthreads",
        "",
        "External libraries:",
        "libdav1d                zlib",
        "",
        "External libraries providing hardware acceleration:",
        "audiotoolbox            videotoolbox",
        "",
        "Libraries:",
        "avcodec                 avformat                swscale",
    )

    @Test
    fun theBannerGivesBothSectionsSorted() {
        val found = assertNotNull(ExternalLibraries.fromBanner(macosBanner))
        assertEquals(listOf("libdav1d", "zlib"), found.libraries)
        assertEquals(listOf("audiotoolbox", "videotoolbox"), found.hardware)
        assertNull(ExternalLibraries.EXPECTED.getValue(TargetTriple.MacosArm64).mismatch(found))
    }

    @Test
    fun configurePrintsAnEmptySectionAsNoLines() {
        val android = listOf(
            "External libraries:",
            "jni                     mediacodec",
            "libdav1d                zlib",
            "",
            "External libraries providing hardware acceleration:",
            "",
            "Libraries:",
        )
        val found = assertNotNull(ExternalLibraries.fromBanner(android))
        assertEquals(ExternalLibraries.EXPECTED.getValue(TargetTriple.AndroidArm64), found)
        assertEquals(emptyList(), found.hardware)
    }

    @Test
    fun aBannerWithoutTheSectionsIsNotRead() {
        assertNull(ExternalLibraries.fromBanner(listOf("Libraries:", "avcodec")))
        assertNull(ExternalLibraries.fromBanner(macosBanner.take(5)))
    }

    @Test
    fun theRecordReadsBackAsTheSameSets() {
        TargetTriple.entries.forEach { target ->
            val expected = ExternalLibraries.EXPECTED.getValue(target)
            assertEquals(expected, ExternalLibraries.fromRecord(expected.record()), "$target")
        }
        val android = ExternalLibraries.EXPECTED.getValue(TargetTriple.AndroidArm64).record()
        assertTrue("Hardware acceleration libraries: (none)\n" in android, android)
        assertNull(ExternalLibraries.fromRecord("# a comment only\n"))
    }

    @Test
    fun everyTargetHasAnExpectedSet() {
        assertEquals(TargetTriple.entries.toSet(), ExternalLibraries.EXPECTED.keys)
    }

    @Test
    fun aMismatchNamesBothSets() {
        val expected = ExternalLibraries.of("iconv libdav1d zlib | v4l2_m2m")
        val found = ExternalLibraries.of("iconv libdav1d lzma zlib | v4l2_m2m vulkan")
        val reason = assertNotNull(expected.mismatch(found))
        assertTrue("expected: iconv libdav1d zlib | v4l2_m2m" in reason, reason)
        assertTrue("found:    iconv libdav1d lzma zlib | v4l2_m2m vulkan" in reason, reason)
    }

    @Test
    fun aTreeIsStaleWithoutTheRecordOrWithAnotherSet() {
        val tree = Files.createTempDirectory("kiteffmpeg-external-libraries").toFile()
        try {
            val expected = ExternalLibraries.EXPECTED.getValue(TargetTriple.MingwX64)
            assertNotNull(ExternalLibraries.staleReason(tree, expected))

            ExternalLibraries.writeRecord(expected, tree.toPath())
            assertNull(ExternalLibraries.staleReason(tree, expected))

            ExternalLibraries.writeRecord(ExternalLibraries.of("iconv libdav1d zlib | d3d11va"), tree.toPath())
            val reason = assertNotNull(ExternalLibraries.staleReason(tree, expected))
            assertTrue("d3d11va dxva2" in reason, reason)
        } finally {
            tree.deleteRecursively()
        }
    }

    // scripts/native-sbom.sh reads EXPECTED from the source with this pattern, one entry on each line.
    @Test
    fun theBillOfMaterialsScriptCanReadEveryEntry() {
        val root = File(checkNotNull(System.getProperty("kiteffmpeg.repo.root"))).canonicalFile
        val source = root.resolve("build-plugins/native-build/src/ExternalLibraries.kt").readText()
        val line = Regex("""^ *TargetTriple\.([A-Za-z0-9]+) to of\("([^"]*)"\),$""", RegexOption.MULTILINE)
        val read = line.findAll(source).associate { match ->
            TargetTriple.valueOf(match.groupValues[1]) to ExternalLibraries.of(match.groupValues[2])
        }
        assertEquals(ExternalLibraries.EXPECTED, read)
        assertTrue("sed -n 's/^ *TargetTriple" in root.resolve("scripts/native-sbom.sh").readText())
    }
}
