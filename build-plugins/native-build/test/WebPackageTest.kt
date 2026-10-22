package io.github.yuroyami.kiteffmpeg.buildtools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class WebPackageTest {

    @Test
    fun theModuleFilesSitAtTheRootBeforeTheBuildRecordAndTheLicences() {
        val root = Files.createTempDirectory("web-package-test").toFile()
        try {
            val modules = root.resolve("modules").apply { mkdirs() }
            modules.resolve("kite.wasm").writeText("wasm")
            modules.resolve("kite.mjs").writeText("mjs")
            val tree = root.resolve("wasm32")
            tree.resolve("lib/kiteffmpeg").apply { mkdirs() }.resolve("web-build-info.txt").writeText("info")
            val license = root.resolve("COPYING.LGPLv2.1").apply { writeText("lgpl") }

            val entries = webPackageEntries(modules, tree, listOf(license))

            assertEquals(
                listOf("kite.mjs", "kite.wasm", "build-info/web-build-info.txt", "licenses/COPYING.LGPLv2.1"),
                entries.map { it.first },
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
