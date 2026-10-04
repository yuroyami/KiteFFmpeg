package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A build compiles a vendored checkout only when it holds its pinned commit and nothing beside it,
 * and the checkout's state moves whenever what a build would read moves (#145). Each case makes a
 * small repository of its own, so nothing here depends on a vendored source being present.
 */
class VendoredCheckoutTest {

    private val scratch: File = Files.createTempDirectory("vendored-checkout-test").toFile()

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    private fun git(dir: File, vararg args: String): String {
        val process = ProcessBuilder(
            listOf(
                "git", "-c", "user.name=test", "-c", "user.email=test@example.com",
                "-c", "commit.gpgsign=false", "-C", dir.absolutePath,
            ) + args,
        ).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out.trim()
    }

    /** A checkout with two commits, a tracked `.gitignore` for `*.o`, and the second commit at HEAD. */
    private fun checkout(name: String = "lib"): Pair<File, List<String>> {
        val dir = scratch.resolve(name).also { it.mkdirs() }
        git(dir, "init", "-q")
        dir.resolve("source.c").writeText("int answer(void) { return 41; }\n")
        dir.resolve(".gitignore").writeText("*.o\n")
        git(dir, "add", "-A")
        git(dir, "commit", "-q", "-m", "first")
        val first = git(dir, "rev-parse", "HEAD")
        dir.resolve("source.c").writeText("int answer(void) { return 42; }\n")
        git(dir, "commit", "-q", "-am", "second")
        val second = git(dir, "rev-parse", "HEAD")
        return dir to listOf(first, second)
    }

    private fun pin(commit: String) = PinnedSource("lib", "https://example.com/lib.git", "v2", commit)

    private fun refusal(dir: File, commit: String): String =
        assertFailsWith<GradleException> { VendoredCheckout.requirePristine(dir, pin(commit), "Patch it instead.") }
            .message.orEmpty()

    @Test
    fun `a clean checkout at its pin passes and its state is its commit`() {
        val (dir, commits) = checkout()
        VendoredCheckout.requirePristine(dir, pin(commits[1]), "unused")
        assertEquals(commits[1], VendoredCheckout.state(dir))
        assertEquals(commits[1], VendoredCheckout.state(dir), "reading the state must not change it")
    }

    @Test
    fun `a checkout at another commit is refused naming both commits and the clone`() {
        val (dir, commits) = checkout()
        val message = refusal(dir, commits[0])
        assertTrue(commits[1] in message && commits[0] in message, message)
        assertTrue("git clone --depth 1 --branch v2 https://example.com/lib.git" in message, message)
    }

    @Test
    fun `an edited tracked file is refused and names the file`() {
        val (dir, commits) = checkout()
        dir.resolve("source.c").writeText("int answer(void) { return 43; }\n")
        val message = refusal(dir, commits[1])
        assertTrue(" M source.c" in message, message)
        assertTrue("Patch it instead." in message, message)
        assertTrue(VendoredCheckout.state(dir).startsWith("${commits[1]} with changes sha256:"))
    }

    @Test
    fun `a staged change is refused`() {
        val (dir, commits) = checkout()
        dir.resolve("source.c").writeText("int answer(void) { return 43; }\n")
        git(dir, "add", "source.c")
        assertTrue("M  source.c" in refusal(dir, commits[1]))
    }

    @Test
    fun `an untracked file is refused`() {
        val (dir, commits) = checkout()
        dir.resolve("extra.h").writeText("#define EXTRA 1\n")
        assertTrue("?? extra.h" in refusal(dir, commits[1]))
        assertNotEquals(commits[1], VendoredCheckout.state(dir))
    }

    @Test
    fun `an ignored file is refused`() {
        val (dir, commits) = checkout()
        dir.resolve("stale.o").writeText("object")
        assertTrue("!! stale.o" in refusal(dir, commits[1]))
        assertNotEquals(commits[1], VendoredCheckout.state(dir))
    }

    @Test
    fun `a file git is told to assume unchanged is refused`() {
        val (dir, commits) = checkout()
        git(dir, "update-index", "--assume-unchanged", "source.c")
        dir.resolve("source.c").writeText("int answer(void) { return 43; }\n")
        assertEquals("", git(dir, "status", "--porcelain"), "git status itself calls this clean")
        val message = refusal(dir, commits[1])
        assertTrue("source.c (assumed unchanged)" in message, message)
        assertNotEquals(commits[1], VendoredCheckout.state(dir))
    }

    @Test
    fun `each edit gives its own state and undoing it gives the commit back`() {
        val (dir, commits) = checkout()
        val source = dir.resolve("source.c")
        source.writeText("int answer(void) { return 43; }\n")
        val once = VendoredCheckout.state(dir)
        source.writeText("int answer(void) { return 44; }\n")
        val twice = VendoredCheckout.state(dir)
        assertNotEquals(once, twice)
        git(dir, "checkout", "--", "source.c")
        assertEquals(commits[1], VendoredCheckout.state(dir))
    }

    @Test
    fun `an untracked file that changes gives a new state`() {
        val (dir, _) = checkout()
        val extra = dir.resolve("extra.h")
        extra.writeText("#define EXTRA 1\n")
        val before = VendoredCheckout.state(dir)
        extra.writeText("#define EXTRA 22\n")
        assertNotEquals(before, VendoredCheckout.state(dir))
    }

    @Test
    fun `a directory inside another checkout is refused`() {
        val (dir, commits) = checkout()
        val inner = dir.resolve("inner").also { it.mkdirs() }
        val message = refusal(inner, commits[1])
        assertTrue("not a git checkout of its own" in message, message)
        assertTrue(VendoredCheckout.state(inner).startsWith("unreadable:"))
    }

    @Test
    fun `a missing checkout is refused with the clone command`() {
        val missing = scratch.resolve("missing dir")
        val message = refusal(missing, "0".repeat(40))
        assertTrue("git clone --depth 1 --branch v2 https://example.com/lib.git '${missing.absolutePath}'" in message, message)
        assertEquals("absent", VendoredCheckout.state(missing))
    }

    @Test
    fun `a linked checkout is read through its link`() {
        val (dir, commits) = checkout()
        val link = scratch.resolve("vendor-link")
        Files.createSymbolicLink(link.toPath(), dir.toPath())
        VendoredCheckout.requirePristine(link, pin(commits[1]), "unused")
        assertEquals(commits[1], VendoredCheckout.state(link))
    }

    @Test
    fun `a rename counts once and keeps its new name`() {
        val changes = VendoredCheckout.parseStatus("R  new.c\u0000old.c\u0000?? extra.h\u0000".toByteArray())
        assertEquals(listOf("R  new.c", "?? extra.h"), changes.map { it.display })
    }

    @Test
    fun `a pin is a full commit id`() {
        assertFailsWith<IllegalArgumentException> { pin("946fcce") }
    }
}
