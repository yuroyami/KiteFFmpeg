package io.github.yuroyami.kiteffmpeg

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.link
import platform.posix.symlink
import platform.posix.unlink
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The native refusal of an output that names the input. Device and inode see through a link and
 * through another spelling of the path. SameFileRefusalTest covers the JVM twin.
 */
@OptIn(ExperimentalForeignApi::class)
class SameFileNativeTest {

    private val made = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        made.forEach { unlink(it) }
        made.clear()
    }

    private fun path(name: String): String =
        "${systemTempRoot()}/kiteffmpeg-same-${Random.nextLong().toULong()}-$name".also(made::add)

    private fun file(name: String): String = path(name).also { path ->
        val stream = checkNotNull(fopen(path, "w")) { "could not create $path" }
        fputs("x", stream)
        fclose(stream)
    }

    private fun assertRefused(input: String, output: String) {
        val refusal = assertFailsWith<FFmpegException> { refuseSameFile(input, output) }
        assertTrue("same file" in refusal.message.orEmpty(), "refused for another reason: ${refusal.message}")
    }

    @Test
    fun theSamePathIsRefused() {
        val input = file("input")
        assertRefused(input, input)
    }

    @Test
    fun anotherSpellingOfThePathIsRefused() {
        val input = file("input")
        assertRefused(input, input.substringBeforeLast('/') + "/./" + input.substringAfterLast('/'))
    }

    @Test
    fun aSymbolicLinkToTheInputIsRefused() {
        val input = file("input")
        val link = path("symbolic")
        check(symlink(input, link) == 0) { "could not link $link" }
        assertRefused(input, link)
    }

    @Test
    fun aHardLinkToTheInputIsRefused() {
        val input = file("input")
        val link = path("hard")
        check(link(input, link) == 0) { "could not link $link" }
        assertRefused(input, link)
    }

    @Test
    fun aDifferentFileIsAllowed() {
        refuseSameFile(file("input"), file("output"))
    }

    @Test
    fun anOutputThatDoesNotExistYetIsAllowed() {
        refuseSameFile(file("input"), path("missing"))
    }
}
