package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.GradleException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The expected FFmpeg release was written down in three files bound only by a
 * comment asking the reader to keep them in sync, and nothing checked any of them against the vendored
 * checkout.
 *
 * The check itself runs during configuration of every build in this repository, so this file's job is
 * the part a configuration-time assertion cannot show: that it fails on disagreeing inputs, that it
 * passes on agreeing ones, and that the readers behind it read what they claim to. The pass case is
 * also proved by the repository configuring at all, and the fail case is proved here because
 * deliberately breaking a pin to watch a build fail is not something a gate can do to itself.
 */
class BuildFFmpegRefsTest {

    private val repoRoot: File = File(System.getProperty("kiteffmpeg.repo.root") ?: "..").canonicalFile

    private fun site(where: String, ref: String) = BuildFFmpegTask.FFmpegRefSite(where, ref)

    @Test
    fun agreeingRefsPass() {
        BuildFFmpegTask.assertFFmpegRefsAgree(
            listOf(site("a", "n8.0"), site("b", "n8.0"), site("c", "n8.0")),
            vendorRelease = "8.0",
        )
    }

    @Test
    fun theTagPrefixIsNormalisedAwaySoAReleaseFileCanBeCompared() {
        // FFmpeg's git tag is n8.0 and the RELEASE file in that tag says 8.0. Same release.
        assertEquals("8.0", BuildFFmpegTask.normaliseFFmpegRef("n8.0"))
        assertEquals("8.0", BuildFFmpegTask.normaliseFFmpegRef("  8.0 \n"))
        // And nothing else is normalised: 8.0 and 8.0.1 are different releases and must stay different.
        assertEquals("8.0.1", BuildFFmpegTask.normaliseFFmpegRef("n8.0.1"))
    }

    @Test
    fun aDisagreeingSiteFailsAndTheMessageNamesEverySite() {
        val failure = assertFailsWith<GradleException> {
            BuildFFmpegTask.assertFFmpegRefsAgree(
                listOf(site("buildSrc", "n8.0"), site("plugin", "n7.1"), site("workflow", "n8.0")),
                vendorRelease = "8.0",
            )
        }
        val message = failure.message.orEmpty()
        assertContains(message, "they do not agree")
        // Every site, not only the odd one out: the reader's question is which one is wrong.
        assertContains(message, "buildSrc: n8.0")
        assertContains(message, "plugin: n7.1")
        assertContains(message, "workflow: n8.0")
        assertContains(message, "vendor/ffmpeg/RELEASE: 8.0")
        assertContains(message, "4 place(s)")
    }

    @Test
    fun aVendoredCheckoutAtTheWrongReleaseFails() {
        val failure = assertFailsWith<GradleException> {
            BuildFFmpegTask.assertFFmpegRefsAgree(
                listOf(site("buildSrc", "n8.0"), site("plugin", "n8.0"), site("workflow", "n8.0")),
                vendorRelease = "7.1",
            )
        }
        assertContains(failure.message.orEmpty(), "vendor/ffmpeg/RELEASE: 7.1")
    }

    @Test
    fun anAbsentVendoredCheckoutIsNotAFailure() {
        // Most builds never vendor FFmpeg. A missing optional checkout must not fail every build.
        BuildFFmpegTask.assertFFmpegRefsAgree(
            listOf(site("buildSrc", "n8.0"), site("plugin", "n8.0"), site("workflow", "n8.0")),
            vendorRelease = null,
        )
    }

    @Test
    fun theWorkflowReaderFindsThePinAndReportsItsAbsence() {
        val workflow = """
            |env:
            |  # a comment
            |  FFMPEG_VERSION: n8.0
            |jobs:
        """.trimMargin()
        assertEquals("n8.0", BuildFFmpegTask.readWorkflowFFmpegVersion(workflow))
        assertEquals("n8.0", BuildFFmpegTask.readWorkflowFFmpegVersion("  FFMPEG_VERSION: \"n8.0\"\n"))
        // A workflow that stopped pinning the release is a drift, so it must read as absent and not as
        // agreement. The caller turns null into its own failure.
        assertNull(BuildFFmpegTask.readWorkflowFFmpegVersion("env:\n  OTHER: 1\n"))
        assertNull(BuildFFmpegTask.readWorkflowFFmpegVersion("  FFMPEG_VERSION:\n"))
    }

    @Test
    fun theAvutilMajorReaderReadsARealHeader() {
        assertEquals(
            60,
            BuildFFmpegTask.readVendoredAvutilMajor(
                "#define LIBAVUTIL_VERSION_MAJOR  60\n#define LIBAVUTIL_VERSION_MINOR   8\n",
            ),
        )
        assertNull(BuildFFmpegTask.readVendoredAvutilMajor("#define SOMETHING_ELSE 1\n"))
    }

    /**
     * The sites this repository actually has, read the way the root build script reads them.
     * (The plugin's DEFAULT_FFMPEG_VERSION site died with the plugin on 2026-08-22.)
     *
     * This is the one case in the file that is not a fixture, and it earns that: it is the assertion
     * that the readers point at files that exist and find the pin in each of them. A reader that
     * silently returned null would make the whole check pass vacuously, which is a failure mode two
     * separate bugs in this sub-phase already demonstrated.
     */
    @Test
    fun theRepositorysOwnSitesAreReadableAndAgree() {
        val workflow = repoRoot.resolve(".github/workflows/publish.yml")
        assertTrue(workflow.isFile, "no ${workflow.path}")

        val workflowRef = BuildFFmpegTask.readWorkflowFFmpegVersion(workflow.readText())
        assertTrue(workflowRef != null, "publish.yml has no FFMPEG_VERSION pin")

        val vendorRelease = repoRoot.resolve("vendor/ffmpeg/RELEASE")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        BuildFFmpegTask.assertFFmpegRefsAgree(
            listOf(
                site("buildSrc", BuildFFmpegTask.DEFAULT_SOURCE_REF),
                site("workflow", workflowRef!!),
            ),
            vendorRelease,
        )
    }

    /**
     * The clone refs are a SECOND kind of pin, and reading only the first kind is what let CI go
     * red for two days. A workflow's `FFMPEG_VERSION:` names prebuilt zips that already exist on a
     * release tag; a `git clone --branch` names the source a job compiles. The 8.1.2 bump moved the
     * env vars and left three clone literals at n8.0.
     */
    @Test
    fun `every FFmpeg clone ref in a workflow is read, and the env var is not mistaken for one`() {
        val workflow = """
            env:
              FFMPEG_VERSION: n8.0
              FFMPEG_ASSET_TAG: ffmpeg-n8.0-r2
            jobs:
              a:
                steps:
                  - run: |
                      git clone --depth 1 --branch n8.1.2 https://github.com/FFmpeg/FFmpeg.git vendor/ffmpeg
                      git clone --depth 1 --branch 1.5.4 https://code.videolan.org/videolan/dav1d vendor/dav1d
              b:
                steps:
                  - run: git clone --depth 1 --branch n8.1.2 https://github.com/FFmpeg/FFmpeg.git vendor/ffmpeg
        """.trimIndent()

        assertEquals(
            listOf("n8.1.2", "n8.1.2"),
            BuildFFmpegTask.readWorkflowFFmpegCloneRefs(workflow),
            "the dav1d clone must not be read, and the env var must not be read as a clone",
        )
        // The env reader still answers its own question, and answers a different one.
        assertEquals("n8.0", BuildFFmpegTask.readWorkflowFFmpegVersion(workflow))
    }

    @Test
    fun `a workflow that clones no FFmpeg reports none, so a blind check can be told from a clean one`() {
        assertEquals(
            emptyList(),
            BuildFFmpegTask.readWorkflowFFmpegCloneRefs("jobs:\n  a:\n    steps:\n      - run: echo hi"),
        )
    }
}
