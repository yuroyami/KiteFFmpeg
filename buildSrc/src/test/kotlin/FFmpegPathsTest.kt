package io.github.yuroyami.kitecodec.buildtools

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
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
}
