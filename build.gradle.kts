import io.github.yuroyami.kiteffmpeg.buildtools.BuildFFmpegTask
import io.github.yuroyami.kiteffmpeg.buildtools.CheckCinteropCouplingTask
import io.github.yuroyami.kiteffmpeg.buildtools.CheckReleaseTargetMirrorTask

plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    // Applied (not deferred) at the root so `dokkaGenerate` aggregates every
    // library module into one API site at build/dokka/html (deployed to /api/).
    alias(libs.plugins.dokka)
    // Guards the public API surface of :kiteffmpeg (apiDump / apiCheck, klib-aware).
    alias(libs.plugins.binary.compatibility.validator)
}

allprojects {
    group   = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION").get()
}

/* Aggregate the published library modules into a single Dokka API reference. */
dependencies {
    dokka(project(":kiteffmpeg"))
}

dokka {
    moduleName.set("KiteFFmpeg")
}

// Shared Kite theme. Sources live in ../_kite-docs; ./_kite-docs/sync.sh copies
// them here, so this repo still builds standalone from a fresh clone.
//
// This has to be applied to every project that has Dokka, not just the root:
// under aggregation the root only renders the "all modules" landing page, and
// each module renders its own pages from its own configuration. Configuring
// only the root leaves every actual API page on the stock theme.
allprojects {
    plugins.withId("org.jetbrains.dokka") {
        extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
            pluginsConfiguration.html {
                customStyleSheets.from(
                    rootProject.layout.projectDirectory.file("docs/api-theme/kite.css"),
                )
                templatesDir.set(
                    rootProject.layout.projectDirectory.dir("dokka-templates"),
                )
                footerMessage.set("Apache-2.0 · KiteFFmpeg is part of the Kite family.")
            }

            // A module with a Module.md gets its description onto the aggregated
            // "all modules" landing page, which is otherwise a bare list of names.
            dokkaSourceSets.configureEach {
                val moduleDoc = layout.projectDirectory.file("Module.md")
                if (moduleDoc.asFile.exists()) {
                    includes.from(moduleDoc)
                }
            }

        }
    }
}


apiValidation {
    // Only :kiteffmpeg is a published library with a guarded API surface.
    ignoredProjects += listOf("kiteffmpeg-sample")

    // Native declarations remain guarded in klibs. Every scope has one public JVM target using
    // the unavailable placeholder, so its dump lives directly under kiteffmpeg/api/. The
    // phone proof adds an unpublished custom JNI compilation without changing that artifact.
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}

/*
 * The ratchet on kiteffmpeg's coupling to FFmpeg's C types. It recomputes the four counts of
 * native/kitecodec-c/coupling-baseline.txt and fails when any one of them rose. See
 * CheckCinteropCouplingTask for what each count is and why the deferral needs a ratchet at all.
 */
tasks.register<CheckCinteropCouplingTask>("checkCinteropCoupling") {
    group = "verification"
    description = "Fails when kiteffmpeg's coupling to FFmpeg's C types grew past its baseline."
    sourceDir.set(layout.projectDirectory.dir("kiteffmpeg/src"))
    baselineFile.set(layout.projectDirectory.file("native/kitecodec-c/coupling-baseline.txt"))
    // Count four needs the C of the helper layer. Before B1.3 that was the def body; from B1.3 it is
    // this tree, and reading both is what keeps the count identical across the move. The file tree
    // rather than two fixed names, because B1.4 splits the single .c into nine.
    cDeclarationFiles.from(
        fileTree(layout.projectDirectory.dir("native/kitecodec-c")) {
            include("include/**/*.h", "src/**/*.c")
        },
    )
}

/*
 * The same drift, one level up: the release job repeats every triple by hand, and a triple missing
 * there is a prebuilt nobody builds. Nothing goes red for it, because the build and the tests do
 * not care which jobs a workflow declares.
 */
tasks.register<CheckReleaseTargetMirrorTask>("checkReleaseTargetMirror") {
    group = "verification"
    description = "Fails when the release workflow and the TargetTriple enum name different targets."
    workflowFile.set(layout.projectDirectory.file(".github/workflows/release-binaries.yml"))
}

/*
 * The expected FFmpeg release is written down in more than one place bound only by a
 * comment asking the reader to keep them in sync, and nothing checked any of them against the vendored
 * checkout. This is that check, and it is a build-time ASSERTION rather than a task on purpose: a task
 * has to be asked for, and the failure this prevents is one nobody would think to ask about. It runs
 * during configuration of every build in this repository.
 *
 * Why the file reads go through `providers.fileContents`. Reading a file with File.readText() at
 * configuration time is invisible to the configuration cache, so a cached entry would keep passing after
 * one of these files drifted, which is the one outcome a drift check must not have. A `fileContents`
 * provider is a tracked configuration input: change publish.yml and Gradle discards the entry and
 * re-runs this. It also starts no process, which is the other thing the configuration cache forbids.
 */
run {
    val workflow = layout.projectDirectory.file(".github/workflows/publish.yml")
    val workflowText = providers.fileContents(workflow).asText.orNull
        ?: throw GradleException(
            "Cannot check the FFmpeg release pins: no ${workflow.asFile.path}.",
        )
    val workflowRef = BuildFFmpegTask.readWorkflowFFmpegVersion(workflowText)
        ?: throw GradleException(
            "Cannot check the FFmpeg release pins: " +
                ".github/workflows/publish.yml has no `FFMPEG_VERSION:` line in its env block. It is " +
                "one of the places that must name the release; a workflow that stopped pinning " +
                "one is exactly the drift this check exists to catch.",
        )
    // Absent on a checkout that never vendored FFmpeg, which is normal and not a failure. Present at
    // the wrong release is a failure, because that tree is what the C would be compiled against.
    val vendorRelease = providers
        .fileContents(layout.projectDirectory.file("vendor/ffmpeg/RELEASE"))
        .asText
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    // The plugin pin site died with the plugin (2026-08-22): with FFmpeg embedded in
    // the klibs there is no consumer-side version to keep honest, only the two producer pins.
    // ci.yml joined the comparison on 2026-08-30, and it joined because it had already drifted.
    // The 8.1.2 bump moved buildSrc and publish.yml and left ci.yml's three `git clone --branch`
    // literals at n8.0, so every job that BUILDS FFmpeg from source cloned 8.0, met a build
    // expecting 8.1.2, and went red. The check that exists to catch exactly this could not see the
    // file it happened in, because it only ever read `FFMPEG_VERSION:`.
    //
    // The clone refs are read rather than the env var, and that is deliberate. `FFMPEG_VERSION:` in
    // ci.yml and docs.yml names the PREBUILT zips a job downloads, so it is keyed to binaries that
    // exist on a release tag. A clone ref names the SOURCE a job compiles. Only the second has to
    // equal what buildSrc expects; conflating them is what would force the two to move together
    // when they legitimately move apart.
    val ciWorkflow = layout.projectDirectory.file(".github/workflows/ci.yml")
    val ciText = providers.fileContents(ciWorkflow).asText.orNull
        ?: throw GradleException("Cannot check the FFmpeg release pins: no ${ciWorkflow.asFile.path}.")
    val ciCloneRefs = BuildFFmpegTask.readWorkflowFFmpegCloneRefs(ciText)
    if (ciCloneRefs.isEmpty()) {
        throw GradleException(
            "Cannot check the FFmpeg release pins: .github/workflows/ci.yml clones FFmpeg nowhere. " +
                "Either a job stopped building from source, or the clone line changed shape and " +
                "this check went blind, which is the failure it exists to prevent.",
        )
    }

    val refSites = mutableListOf(
        BuildFFmpegTask.FFmpegRefSite(
            "buildSrc/src/main/kotlin/BuildFFmpegTask.kt DEFAULT_SOURCE_REF",
            BuildFFmpegTask.DEFAULT_SOURCE_REF,
        ),
        BuildFFmpegTask.FFmpegRefSite(".github/workflows/publish.yml FFMPEG_VERSION", workflowRef),
    )
    ciCloneRefs.forEachIndexed { index, ref ->
        refSites.add(BuildFFmpegTask.FFmpegRefSite("ci.yml FFmpeg clone " + (index + 1), ref))
    }

    BuildFFmpegTask.assertFFmpegRefsAgree(refSites, vendorRelease)

}
