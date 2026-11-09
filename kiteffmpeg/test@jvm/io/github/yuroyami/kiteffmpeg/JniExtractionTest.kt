package io.github.yuroyami.kiteffmpeg

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The desktop loader's extraction is reused across launches instead of copied each time (#208). */
class JniExtractionTest {

    private val bundle = listOf(
        JniExtraction.BundleFile("libdep.so", byteArrayOf(1, 2, 3)),
        JniExtraction.BundleFile("libkitecodec_jni.so", ByteArray(4096) { it.toByte() }),
    )

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("jni-extraction-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test
    fun aSecondLaunchReusesTheFolderAndWritesNothing() = withRoot { root ->
        val first = assertNotNull(JniExtraction.extract(root, bundle))
        val library = File(first, "libkitecodec_jni.so")
        library.setLastModified(1_000_000_000L)
        val second = assertNotNull(JniExtraction.extract(root, bundle))
        assertEquals(first, second)
        assertEquals(1_000_000_000L, library.lastModified(), "the second launch rewrote the library")
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test
    fun aDamagedCopyIsWrittenAgain() = withRoot { root ->
        val directory = assertNotNull(JniExtraction.extract(root, bundle))
        File(directory, "libdep.so").writeBytes(byteArrayOf(9, 9, 9))
        JniExtraction.extract(root, bundle)
        assertContentEquals(byteArrayOf(1, 2, 3), File(directory, "libdep.so").readBytes())
    }

    @Test
    fun anotherBundleGetsAnotherFolder() = withRoot { root ->
        val first = assertNotNull(JniExtraction.extract(root, bundle))
        val changed = listOf(JniExtraction.BundleFile("libkitecodec_jni.so", byteArrayOf(7)))
        val second = assertNotNull(JniExtraction.extract(root, changed))
        assertNotEquals(first, second)
    }

    @Test
    fun oldFoldersAreRemovedOnlyOnceStale() = withRoot { root ->
        val now = System.currentTimeMillis()
        val stale = File(root, "kitecodec-jni123").apply { mkdirs(); File(this, "x.dll").writeText("x") }
        stale.setLastModified(now - JniExtraction.STALE_AFTER_MILLIS - 1_000L)
        val fresh = File(root, "kiteffmpeg-jni-0000-other").apply { mkdirs() }
        val unrelated = File(root, "something-else").apply { mkdirs() }
        unrelated.setLastModified(now - JniExtraction.STALE_AFTER_MILLIS - 1_000L)
        JniExtraction.extract(root, bundle, now)
        assertFalse(stale.exists(), "a stale per-launch folder survived")
        assertTrue(fresh.exists(), "a folder another launch may have just made was deleted")
        assertTrue(unrelated.exists(), "a folder that is not ours was deleted")
    }
}
