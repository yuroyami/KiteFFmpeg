package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two cases plan section 15.2 B1.3 names for [CompileKiteCodecCTask]: a correct object archives,
 * and an object of the wrong architecture fails with a message naming both architectures and the
 * target. A third case covers the output-directory guard, because that guard is the other half of
 * register item B1-11 and is as easy to break as it is to state.
 *
 * The compiler is the real konan clang and the objects are real objects. A fixture would prove that
 * a fake `file` output matches a hand written expectation and nothing about the toolchain. The
 * translation unit is deliberately trivial and includes nothing, so these cases need no FFmpeg tree
 * and run on any host whose Kotlin/Native distribution is installed.
 */
class CompileKiteCodecCTaskTest {

    @Test
    fun aCorrectObjectArchives() {
        val fixture = fixture()
        val task = newTask("macos_arm64", fixture)
        task.compile()

        val archive = fixture.outputRoot.resolve("macos_arm64/${CompileKiteCodecCTask.ARCHIVE_NAME}")
        assertTrue(archive.isFile, "no archive at ${archive.absolutePath}")
        assertTrue(archive.length() > 0, "the archive at ${archive.absolutePath} is empty")
        assertContains(describe(archive), "ar archive")

        val objectFile = fixture.outputRoot.resolve("macos_arm64/obj/probe.o")
        assertTrue(objectFile.isFile, "no object at ${objectFile.absolutePath}")
        assertContains(describe(objectFile), CompileKiteCodecCTask.expectedObjectDescription("macos_arm64"))
    }

    @Test
    fun anObjectOfTheWrongArchitectureIsRefusedNamingBothArchitecturesAndTheTarget() {
        // A real object of the wrong architecture: the same source, compiled for macos_x64, which is
        // what an output directory shared between two targets would leave behind.
        val fixture = fixture()
        newTask("macos_x64", fixture).compile()
        val x64Object = fixture.outputRoot.resolve("macos_x64/obj/probe.o")
        assertTrue(x64Object.isFile, "the x64 object was not produced at ${x64Object.absolutePath}")

        val actual = describe(x64Object)
        val failure = assertFails {
            CompileKiteCodecCTask.verifyObjectArchitecture("macos_arm64", x64Object, actual)
        }
        val message = failure.message ?: ""
        // Both architectures, so the reader does not have to guess which end is wrong.
        assertContains(message, actual)
        assertContains(message, CompileKiteCodecCTask.expectedObjectDescription("macos_arm64"))
        // And the target, so the reader knows which archive would have been poisoned.
        assertContains(message, "macos_arm64")
        assertContains(message, x64Object.absolutePath)
    }

    @Test
    fun anOutputDirectoryNotNamedAfterItsTargetIsRefused() {
        val fixture = fixture()
        val task = newTask("macos_arm64", fixture)
        // The one mistake that produces a wrong-architecture archive without any compiler being
        // wrong: two targets writing into one directory (register item B1-11).
        task.outputDir.set(fixture.outputRoot.resolve("shared"))

        val message = assertFails { task.compile() }.message ?: ""
        assertContains(message, "B1-11")
        assertContains(message, "macos_arm64")
        assertContains(message, "shared")
    }

    /**
     * The producer-side guard is a guard only if the task action CALLS it. The interlude (I-10)
     * measured that replacing the verifyObjectArchitecture call site with a comment left this
     * whole suite green at 4 tests, because every case drove the predicate directly. This case
     * runs the real compile() with a describeFile that lies about the produced object, and the
     * only thing standing between that lie and a poisoned archive is the call site.
     */
    @Test
    fun compileItselfRefusesAnObjectWhoseDescriptionIsWrong() {
        val fixture = fixture()
        val project = ProjectBuilder.builder().build()
        val task = project.tasks
            .register("compileWithLyingDescribe", LyingDescribeTask::class.java)
            .get()
        task.konanTargetName.set("macos_arm64")
        task.sourceDir.set(fixture.sourceDir)
        task.includeDir.set(fixture.includeDir)
        task.ffmpegIncludeDirs.set(emptyList<String>())
        task.konanDataDir.set(konanDataDir)
        task.outputDir.set(fixture.outputRoot.resolve("macos_arm64"))

        val message = assertFails { task.compile() }.message ?: ""
        assertContains(message, "macos_arm64")
        assertContains(message, LyingDescribeTask.WRONG_DESCRIPTION)
        assertContains(message, CompileKiteCodecCTask.expectedObjectDescription("macos_arm64"))
    }

    /** A task whose `file -b` answer is always wrong, for the call-site case above. */
    abstract class LyingDescribeTask @javax.inject.Inject constructor(
        execOperations: org.gradle.process.ExecOperations,
    ) : CompileKiteCodecCTask(execOperations) {
        override fun describeFile(file: File): String = WRONG_DESCRIPTION
        companion object {
            const val WRONG_DESCRIPTION: String = "Mach-O 64-bit object x86_64 (a lie, planted by the call-site test)"
        }
    }

    @Test
    fun aStaleObjectFromAPreviousRunIsCleared() {
        // Interlude (I-10): the clearing line in compile() had no test, so deleting it kept the
        // suite green. A renamed or removed source must not leave its old object behind, where
        // the CI archive listing and any obj/-globbing tool would read it as current.
        val fixture = fixture()
        val task = newTask("macos_arm64", fixture)
        val stale = fixture.outputRoot.resolve("macos_arm64/obj/stale.o")
        stale.parentFile.mkdirs()
        stale.writeText("not an object at all")

        task.compile()

        assertTrue(!stale.exists(), "the stale object survived the compile")
        val objects = fixture.outputRoot.resolve("macos_arm64/obj").listFiles().orEmpty().map { it.name }
        assertEquals(listOf("probe.o"), objects, "obj/ must hold exactly the objects of this run")
    }

    @Test
    fun theLlvmPackageResolverPrefersTheNamedPackage() {
        val root = createTempDirectory()
        val preferred = root.resolve("llvm-21-aarch64-macos-essentials-97/bin")
        writeExecutable(preferred.resolve("clang"))
        writeExecutable(root.resolve("llvm-19-aarch64-macos-essentials-79/bin/clang"))

        val resolved = CompileKiteCodecCTask.resolveLlvmBinDir(root, "llvm-21-aarch64-macos-essentials-97")
        assertEquals(preferred.absolutePath, resolved.absolutePath)
    }

    @Test
    fun theLlvmPackageResolverFallsBackNumericallyAndSaysSo() {
        // Copied across from CompileKiteRtTaskTest at the interlude (I-10) so the two near-twin
        // tasks are covered identically. Numbers, not text: llvm-9 must not sort above llvm-21,
        // and essentials-97 must beat essentials-79 within the same LLVM version.
        val root = createTempDirectory()
        writeExecutable(root.resolve("llvm-9-aarch64-macos-essentials-99/bin/clang"))
        writeExecutable(root.resolve("llvm-21-aarch64-macos-essentials-79/bin/clang"))
        val newest = root.resolve("llvm-21-aarch64-macos-essentials-97/bin")
        writeExecutable(newest.resolve("clang"))

        val messages = mutableListOf<String>()
        val resolved = CompileKiteCodecCTask.resolveLlvmBinDir(root, "llvm-22-does-not-exist") { messages += it }

        assertEquals(newest.absolutePath, resolved.absolutePath)
        assertEquals(1, messages.size, "the substitution must be reported exactly once")
        assertContains(messages.single(), "llvm-21-aarch64-macos-essentials-97")
    }

    @Test
    fun noLlvmPackageAtAllFailsNamingWhatIsMissing() {
        val root = createTempDirectory()
        val failure = assertFails {
            CompileKiteCodecCTask.resolveLlvmBinDir(root, "llvm-21-aarch64-macos-essentials-97")
        }
        assertContains(failure.message.orEmpty(), "llvm-21-aarch64-macos-essentials-97")
    }


    @Test
    fun aWindowsShapedDependenciesTreeResolvesTheExeNames() {
        // Interlude item I-20. A Windows konan package ships clang.exe and llvm-ar.exe, and the
        // review measured File("bin/clang").canExecute() false against a Windows shaped tree, so
        // every candidate was rejected and the windows-x64 CI job could not pass. Resolution now
        // tries the bare name and then the .exe name.
        val root = createTempDirectory()
        val bin = root.resolve("llvm-21-x86_64-windows-essentials-97/bin")
        writeExecutable(bin.resolve("clang.exe"))
        writeExecutable(bin.resolve("llvm-ar.exe"))

        val resolved = CompileKiteCodecCTask.resolveLlvmBinDir(root, "llvm-21-x86_64-windows-essentials-97")
        assertEquals(bin.absolutePath, resolved.absolutePath)
        assertEquals("clang.exe", CompileKiteCodecCTask.resolveTool(resolved, "clang")?.name)
        assertEquals("llvm-ar.exe", CompileKiteCodecCTask.resolveTool(resolved, "llvm-ar")?.name)
    }

    @Test
    fun theAndroidToolchainPackageIsNamedAfterTheBuildHost() {
        // Interlude item I-20. The sysroot path hardcoded the osx infix, and on an Ubuntu runner
        // the osx package never exists, so the C compile threw before cinterop and the three
        // android CI jobs could not pass. The infix now follows the host, the way konan's own
        // konan.properties names the packages.
        val root = createTempDirectory()
        val linuxSysroot = root.resolve("target-toolchain-2-linux-android_ndk/sysroot")
        assertTrue(linuxSysroot.mkdirs())

        assertEquals("osx", CompileKiteCodecCTask.konanHostInfix("Mac OS X"))
        assertEquals("windows", CompileKiteCodecCTask.konanHostInfix("Windows Server 2022"))
        assertEquals("linux", CompileKiteCodecCTask.konanHostInfix("Linux"))
        assertTrue(
            root.resolve(CompileKiteCodecCTask.androidToolchainSysroot("Linux")).isDirectory,
            "the Linux shaped tree must resolve on a Linux host name",
        )
    }

    private fun writeExecutable(file: File) {
        file.parentFile.mkdirs()
        file.writeText("#!/bin/sh\nexit 0\n")
        file.setExecutable(true)
    }

    @Test
    fun theFFmpegVersionHeadersAreTrackedByContent() {
        // Interlude item I-07. The path STRINGS in ffmpegIncludeDirs survive a brew upgrade that
        // rewrites every file under them, which was measured to leave this task UP-TO-DATE while
        // cinterop regenerated, so the two bakings inside one klib disagreed at byte level. The
        // version headers are therefore declared as content-tracked input files; this case pins
        // the declaration so it cannot be dropped quietly. The out-of-dateness itself was proved
        // against the real build in both directions at the interlude (UP-TO-DATE, content change,
        // EXECUTED, restore, EXECUTED, UP-TO-DATE) and is recorded in the I.4 Execution log entry.
        val fixture = fixture()
        val versionHeader = fixture.includeDir.resolve("libavutil/version.h")
        versionHeader.parentFile.mkdirs()
        versionHeader.writeText("#define LIBAVUTIL_VERSION_MAJOR 60\n")
        val task = newTask("macos_arm64", fixture)
        task.ffmpegVersionHeaders.from(versionHeader)
        assertTrue(
            task.inputs.files.files.contains(versionHeader),
            "ffmpegVersionHeaders is not part of the task's tracked inputs",
        )
    }

    /**
     * One trivial translation unit plus the directories the task requires. The `.c` includes nothing
     * and is warning clean under `-Wall -Wextra -Werror`, so it compiles for every target the task
     * knows without an FFmpeg tree.
     */
    private class Fixture(val sourceDir: File, val includeDir: File, val outputRoot: File)

    private fun fixture(): Fixture {
        val root = createTempDirectory()
        val sourceDir = root.resolve("src").also { it.mkdirs() }
        val includeDir = root.resolve("include").also { it.mkdirs() }
        sourceDir.resolve("probe.c").writeText("int kc_probe(void) { return 0; }\n")
        return Fixture(sourceDir, includeDir, root.resolve("out"))
    }

    private fun newTask(konanTarget: String, fixture: Fixture): CompileKiteCodecCTask {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks
            .register("compileKiteCodecCFor$konanTarget", CompileKiteCodecCTask::class.java)
            .get()
        task.konanTargetName.set(konanTarget)
        task.sourceDir.set(fixture.sourceDir)
        task.includeDir.set(fixture.includeDir)
        task.ffmpegIncludeDirs.set(emptyList<String>())
        task.konanDataDir.set(konanDataDir)
        task.outputDir.set(fixture.outputRoot.resolve(konanTarget))
        return task
    }

    /** `~/.konan`, or whatever `KONAN_DATA_DIR` points at, the same resolution the build uses. */
    private val konanDataDir: File
        get() {
            val fromEnv = System.getenv("KONAN_DATA_DIR")
            val dir = if (fromEnv.isNullOrBlank()) {
                File(System.getProperty("user.home"), ".konan")
            } else {
                File(fromEnv)
            }
            assertTrue(
                dir.resolve("dependencies").isDirectory,
                "No konan dependencies under ${dir.absolutePath}. They arrive with the " +
                    "Kotlin/Native distribution, so run any Kotlin/Native compilation first.",
            )
            return dir
        }

    private fun describe(file: File): String {
        val tool = if (File("/usr/bin/file").canExecute()) "/usr/bin/file" else "file"
        val process = ProcessBuilder(tool, "-b", file.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertTrue(process.waitFor() == 0, "$tool -b failed: $output")
        return output
    }

    private fun createTempDirectory(): File =
        File.createTempFile("compile-kitecodec-c", "").let { placeholder ->
            placeholder.delete()
            placeholder.mkdirs()
            placeholder.deleteOnExit()
            placeholder
        }

    /**
     * The three defines of register item B1-02's identity gate, as clang sees them.
     *
     * The value has to arrive wrapped in C string quotes: `src/kitecodec_abi.c` reads each one as a
     * `const char *`, so `-DKC_BUILD_FFMPEG_REF=n8.0` would expand to an identifier that unit has never
     * heard of and the compile would fail. No shell is involved, because ExecOperations.exec passes argv
     * straight through, so the quotes are characters clang receives rather than something a shell strips.
     *
     * Sorted by name so the command line is deterministic: an unordered map would make the compile
     * arguments differ between runs, and Gradle's up-to-date check would flap on nothing.
     */
    @Test
    fun buildDefinesBecomeQuotedMinusDArgumentsInNameOrder() {
        assertEquals(
            listOf(
                """-DKC_BUILD_FFMPEG_DIR="/opt/homebrew/lib"""",
                """-DKC_BUILD_FFMPEG_LICENSE="lgpl"""",
                """-DKC_BUILD_FFMPEG_REF="n8.0"""",
            ),
            CompileKiteCodecCTask.defineArguments(
                mapOf(
                    CompileKiteCodecCTask.DEFINE_FFMPEG_REF to "n8.0",
                    CompileKiteCodecCTask.DEFINE_FFMPEG_DIR to "/opt/homebrew/lib",
                    CompileKiteCodecCTask.DEFINE_FFMPEG_LICENSE to "lgpl",
                ),
            ),
        )
        assertEquals(emptyList(), CompileKiteCodecCTask.defineArguments(emptyMap()))
    }

    /**
     * Gradle wraps a task action failure, so the exception the caller sees is not always the
     * [GradleException] the task threw. This walks the cause chain for it and fails when there is
     * none, which keeps every assertion below about the message rather than about the wrapper.
     */
    private fun assertFails(block: () -> Unit): Throwable {
        val thrown = try {
            block()
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue(thrown != null, "expected a failure, but the call succeeded")
        var candidate: Throwable? = thrown
        while (candidate != null) {
            if (candidate is GradleException) return candidate
            candidate = candidate.cause
        }
        return thrown
    }

    /**
     * FFmpeg's include directories must never be able to shadow a system header.
     *
     * On Debian and Ubuntu the libav* headers live in the MULTIARCH system include directory,
     * `/usr/include/x86_64-linux-gnu`, right next to `sys/cdefs.h`. Passing that as `-I` puts the
     * HOST's glibc ahead of the konan sysroot this cross-compile targets, and konan's clang then
     * read the host's `sys/cdefs.h` against a glibc 2.19 sysroot and produced roughly two hundred
     * errors starting with `function-like macro '__glibc_clang_prereq' is not defined`.
     *
     * `-idirafter` is exactly the right tool: it is searched AFTER the system directories, so the
     * sysroot wins every header it actually has, while `libavformat/avformat.h`, which no sysroot
     * has, still resolves. The project's OWN include directory stays `-I`, because those headers
     * are ours and must win.
     */
    @Test
    fun ffmpegIncludesAreSearchedAfterTheSysrootAndOnlyOursUsesDashI() {
        val args = CompileKiteCodecCTask.includeArguments(
            ownInclude = "/work/native/kitecodec-c/include",
            ffmpegIncludes = listOf("/usr/include/x86_64-linux-gnu", "/work/native-libs/lgpl/linux-x64/include"),
        )
        assertEquals(
            listOf(
                "-I/work/native/kitecodec-c/include",
                "-idirafter/usr/include/x86_64-linux-gnu",
                "-idirafter/work/native-libs/lgpl/linux-x64/include",
            ),
            args,
        )
    }

    /** With no FFmpeg directories at all, only our own include survives, still as -I. */
    @Test
    fun onlyTheProjectIncludeIsEmittedWhenThereAreNoFfmpegDirectories() {
        assertEquals(
            listOf("-I/work/include"),
            CompileKiteCodecCTask.includeArguments(ownInclude = "/work/include", ffmpegIncludes = emptyList()),
        )
    }
}
