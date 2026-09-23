package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NdkVersionOrderTest {

    @Test
    fun aTwoDigitMinorIsNewerThanAOneDigitMinor() {
        assertTrue(NdkVersionOrder.compare("29.10.0", "29.2.0") > 0)
        assertEquals("29.10.0", listOf("29.2.0", "29.10.0", "28.2.13676358").maxWithOrNull(NdkVersionOrder))
    }

    @Test
    fun buildNumbersCompareAsNumbers() {
        assertTrue(NdkVersionOrder.compare("27.0.12077973", "27.0.9999999") > 0)
        assertEquals(0, NdkVersionOrder.compare("29.0.14206865", "29.0.14206865"))
    }

    @Test
    fun aSuffixedBuildSortsBelowTheReleaseOfTheSameNumber() {
        assertTrue(NdkVersionOrder.compare("29.0.14206865-rc1", "29.0.14206865") < 0)
        assertTrue(NdkVersionOrder.compare("29.0", "29.0.1") < 0)
    }

    @Test
    fun theNewestSideBySideNdkFolderWinsAndFilesAreIgnored() {
        val root = createTempDirectory("ndk").toFile()
        try {
            listOf("28.2.13676358", "29.2.0", "29.10.0").forEach { root.resolve(it).mkdirs() }
            // A macOS Finder alias sits beside the NDK folders as an ordinary file.
            root.resolve("29.99.0 alias").writeText("a Finder alias is a file, not an NDK")
            assertEquals("29.10.0", newestNdk(root)?.name)
            assertNull(newestNdk(File(root, "missing")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun everyBuildTaskThatLooksForAnNdkUsesTheSharedOrder() {
        val sources = File(checkNotNull(System.getProperty("kiteffmpeg.repo.root")))
            .resolve("buildSrc/src/main/kotlin")
        listOf("BuildFFmpegTask.kt", "BuildAssChainTask.kt", "BuildDav1dTask.kt").forEach { name ->
            val text = sources.resolve(name).readText()
            assertTrue("::newestNdk" in text, "$name must pick its NDK with newestNdk")
            assertFalse("maxByOrNull { it.name }" in text, "$name still picks an NDK by its folder name")
        }
    }
}
