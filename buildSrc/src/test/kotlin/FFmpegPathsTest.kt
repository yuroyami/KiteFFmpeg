package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FFmpegPathsTest {

    @Test
    fun everyIosTargetRejectsGplBeforeLookingForAFileTree() {
        val project = ProjectBuilder.builder().build()

        listOf(
            TargetTriple.IosArm64,
            TargetTriple.IosSimulatorArm64,
            TargetTriple.IosX64,
        ).forEach { target ->
            val failure = assertFailsWith<GradleException> {
                FFmpegPaths.resolve(project, target, FFmpegLicense.GPL)
            }
            assertEquals(IOS_GPL_REFUSAL, failure.message)
        }
    }

    /**
     * Debian and Ubuntu install the libav* headers under the MULTIARCH include directory, so
     * `libavformat-dev` on ubuntu-24.04 puts avformat.h at
     * `/usr/include/x86_64-linux-gnu/libavformat/`, not `/usr/include/libavformat/`.
     *
     * The lib side already knew this and looked in `/usr/lib/x86_64-linux-gnu` first. The include
     * side did not, so on CI the apt packages installed correctly, the resolver still answered "no
     * FFmpeg install found", the whole cinterop was skipped, and every native file then failed to
     * compile with `Unresolved reference 'ffmpeg'`. The Linux job had been red on this the entire
     * time it claimed to be testing an apt FFmpeg.
     */
    @Test
    fun linuxIncludeCandidatesCoverTheMultiarchDirectory() {
        assertEquals(
            listOf("/usr/include/x86_64-linux-gnu", "/usr/include", "/usr/local/include"),
            linuxIncludeCandidates(TargetTriple.LinuxX64),
            "x64 must look in its own multiarch include dir before the plain one",
        )
        assertEquals(
            listOf("/usr/include/aarch64-linux-gnu", "/usr/include", "/usr/local/include"),
            linuxIncludeCandidates(TargetTriple.LinuxArm64),
            "arm64 must look in ITS multiarch dir, never x64's",
        )
    }

    /** The lib side's ordering is load bearing too: /usr/lib may hold a foreign-arch copy. */
    @Test
    fun linuxLibCandidatesPutTheHostsMultiarchDirectoryFirst() {
        assertEquals(
            listOf("/usr/lib/x86_64-linux-gnu", "/usr/lib", "/usr/local/lib"),
            linuxLibCandidates(TargetTriple.LinuxX64),
        )
        assertEquals(
            listOf("/usr/lib/aarch64-linux-gnu", "/usr/lib", "/usr/local/lib"),
            linuxLibCandidates(TargetTriple.LinuxArm64),
        )
    }

    /**
     * A system FFmpeg on Debian/Ubuntu cannot be pointed at directly, and the reason is not a flag.
     *
     * `/usr/include/x86_64-linux-gnu` holds the libav* headers AND `sys/cdefs.h`. Every consumer of
     * the resolved include directory puts it on a clang command line with `-I`: the C helper task,
     * and separately the cinterop. Those clang invocations target konan's own sysroot, so the host's
     * glibc headers shadow the sysroot's and the compile dies with `function-like macro
     * '__glibc_clang_prereq' is not defined` followed by the sysroot's own stdio.h coming apart.
     *
     * Fixing each `-I` in turn is whack-a-mole; there were two and the second was only found by
     * failing CI twice. Staging the FFmpeg header trees into a directory that contains NOTHING ELSE
     * fixes it once, for every consumer, with no flag changes at all.
     */
    @Test
    fun stagingCopiesOnlyTheFfmpegHeaderTreesAndLeavesSystemHeadersBehind() {
        val systemInclude = createTempDirectory("kc-sysinclude").toFile()
        // A realistic slice of a multiarch include dir: FFmpeg trees next to glibc's.
        listOf("libavcodec", "libavformat", "libavutil", "libswscale").forEach { lib ->
            File(systemInclude, lib).mkdirs()
            File(systemInclude, "$lib/version.h").writeText("// $lib")
        }
        File(systemInclude, "sys").mkdirs()
        File(systemInclude, "sys/cdefs.h").writeText("#define __glibc_clang_prereq(a,b) 0")
        File(systemInclude, "stdio.h").writeText("// host stdio")

        val staged = createTempDirectory("kc-staged").toFile()
        stageFFmpegHeaders(systemInclude, staged)

        assertTrue(File(staged, "libavformat/version.h").isFile, "FFmpeg trees must be staged")
        assertTrue(File(staged, "libavcodec/version.h").isFile, "every present FFmpeg tree is staged")
        assertFalse(File(staged, "sys/cdefs.h").exists(), "glibc headers must NOT be staged: this is the whole point")
        assertFalse(File(staged, "stdio.h").exists(), "no loose system header may be staged either")
    }

    /** A tree the distribution did not install is simply absent, not an error. */
    @Test
    fun stagingSkipsFfmpegTreesThatAreNotInstalled() {
        val systemInclude = createTempDirectory("kc-sysinclude2").toFile()
        File(systemInclude, "libavformat").mkdirs()
        File(systemInclude, "libavformat/avformat.h").writeText("// avformat")

        val staged = createTempDirectory("kc-staged2").toFile()
        stageFFmpegHeaders(systemInclude, staged)

        assertTrue(File(staged, "libavformat/avformat.h").isFile)
        assertFalse(File(staged, "libavdevice").exists(), "libavdevice was never installed, so nothing to stage")
    }
}
