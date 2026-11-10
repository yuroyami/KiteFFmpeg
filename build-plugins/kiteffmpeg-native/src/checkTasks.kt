package io.github.yuroyami.kiteffmpeg.plugin

import io.github.yuroyami.kiteffmpeg.buildtools.Bakes
import io.github.yuroyami.kiteffmpeg.buildtools.BuildFFmpegTask
import io.github.yuroyami.kiteffmpeg.buildtools.BuildFailure
import io.github.yuroyami.kiteffmpeg.buildtools.BuildLog
import io.github.yuroyami.kiteffmpeg.buildtools.BundleHostJniTask
import io.github.yuroyami.kiteffmpeg.buildtools.CheckCinteropCouplingTask
import io.github.yuroyami.kiteffmpeg.buildtools.CheckFFmpegRecipesTask
import io.github.yuroyami.kiteffmpeg.buildtools.CheckReleaseTargetMirrorTask
import io.github.yuroyami.kiteffmpeg.buildtools.CheckWasmBindingMirrorTask
import io.github.yuroyami.kiteffmpeg.buildtools.CompareCodecContractTask
import io.github.yuroyami.kiteffmpeg.buildtools.ElfHardening
import io.github.yuroyami.kiteffmpeg.buildtools.checkJniRegistration
import java.io.File
import java.nio.file.Path
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction

/*
 * The checks take their folders as strings and always run. They are fast, and an @Input on the
 * module folder would make the toolchain scan its build output before every run.
 */

/** Fails when the Kotlin code of the module uses more FFmpeg C types than the committed baseline allows. */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkCinteropCoupling(moduleDir: String, repoRoot: String) {
    val root = File(repoRoot)
    val c = root.resolve("native/kitecodec-c")
    CheckCinteropCouplingTask().apply {
        sourceDir.set(File(moduleDir))
        baselineFile.set(c.resolve("coupling-baseline.txt"))
        cDeclarationFiles.from(
            c.resolve("include").walkTopDown().filter { it.isFile && it.extension == "h" }.toList(),
            c.resolve("src").walkTopDown().filter { it.isFile && it.extension == "c" }.toList(),
        )
    }.check()
}

/** Fails when the release workflow and the `TargetTriple` enum name different targets. */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkReleaseTargetMirror(repoRoot: String) {
    CheckReleaseTargetMirrorTask().apply {
        workflowFile.set(File(repoRoot, ".github/workflows/release-binaries.yml"))
    }.check()
}

/** Fails when the committed web binding differs from what `./kotlin do generateWasmBinding` writes. */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkWasmBindingMirror(moduleDir: String, repoRoot: String) {
    CheckWasmBindingMirrorTask().apply {
        signatureBaseline.set(File(repoRoot, "native/kitecodec-c/signature-baseline.txt"))
        mirrorFile.set(File(moduleDir, "src@wasmJs/io/github/yuroyami/kiteffmpeg/wasm/KiteFFmpegWasm.kt"))
    }.check()
}

/** Fails when a C function that `methods.def` registers does not match its JNI descriptor. */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkJniRegistrationTable(repoRoot: String) {
    checkJniRegistration(File(repoRoot, "native/kitecodec-jni"))
}

/**
 * Fails when the places that name the FFmpeg release disagree: the bake recipe, the publish workflow,
 * every FFmpeg clone in the CI workflow and `vendor/ffmpeg/RELEASE`.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkFFmpegRefs(repoRoot: String) {
    val root = File(repoRoot)
    val publish = root.resolve(".github/workflows/publish.yml")
    if (!publish.isFile) throw BuildFailure("Cannot check the FFmpeg release pins: no $publish.")
    val publishRef = BuildFFmpegTask.readWorkflowFFmpegVersion(publish.readText()) ?: throw BuildFailure(
        "Cannot check the FFmpeg release pins: .github/workflows/publish.yml has no `FFMPEG_VERSION:` line in its " +
            "env block. It is one of the places that must name the release.",
    )
    val ci = root.resolve(".github/workflows/ci.yml")
    if (!ci.isFile) throw BuildFailure("Cannot check the FFmpeg release pins: no $ci.")
    val cloneRefs = BuildFFmpegTask.readWorkflowFFmpegCloneRefs(ci.readText())
    if (cloneRefs.isEmpty()) {
        throw BuildFailure(
            "Cannot check the FFmpeg release pins: .github/workflows/ci.yml clones FFmpeg nowhere. Either a job " +
                "stopped building from source, or the clone line changed shape and this check cannot see it.",
        )
    }
    // The fuzz job fuzzes the prebuilt tree that CI tests, so its two pins follow CI's (#205).
    val fuzz = root.resolve(".github/workflows/fuzz.yml")
    if (!fuzz.isFile) throw BuildFailure("Cannot check the FFmpeg release pins: no $fuzz.")
    val fuzzText = fuzz.readText()
    val fuzzRef = BuildFFmpegTask.readWorkflowFFmpegVersion(fuzzText) ?: throw BuildFailure(
        "Cannot check the FFmpeg release pins: .github/workflows/fuzz.yml has no `FFMPEG_VERSION:` line in its " +
            "env block. It names the prebuilt tree the fuzz job fuzzes.",
    )
    val assetTag = { text: String -> Regex("(?m)^\\s*FFMPEG_ASSET_TAG:\\s*\"?([^\"\\s#]+)").find(text)?.groupValues?.get(1) }
    val ciTag = assetTag(ci.readText())
    if (ciTag == null || ciTag != assetTag(fuzzText)) {
        throw BuildFailure(
            "The fuzz job fetches the prebuilt FFmpeg trees from FFMPEG_ASSET_TAG ${assetTag(fuzzText)}, and CI from " +
                "$ciTag. Both must name the release CI tests, so .github/workflows/fuzz.yml follows ci.yml.",
        )
    }
    val vendorRelease = root.resolve("vendor/ffmpeg/RELEASE").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    val sites = listOf(
        BuildFFmpegTask.FFmpegRefSite(
            "build-plugins/native-build/src/BuildFFmpegTask.kt DEFAULT_SOURCE_REF",
            BuildFFmpegTask.DEFAULT_SOURCE_REF,
        ),
        BuildFFmpegTask.FFmpegRefSite(".github/workflows/publish.yml FFMPEG_VERSION", publishRef),
        BuildFFmpegTask.FFmpegRefSite(".github/workflows/fuzz.yml FFMPEG_VERSION", fuzzRef),
    ) + cloneRefs.mapIndexed { index, ref -> BuildFFmpegTask.FFmpegRefSite("ci.yml FFmpeg clone ${index + 1}", ref) }
    BuildFFmpegTask.assertFFmpegRefsAgree(sites, vendorRelease)
    BuildLog.lifecycle("[KiteFFmpeg] ${sites.size} FFmpeg release pins agree on ${BuildFFmpegTask.DEFAULT_SOURCE_REF}")
}

/** Fails when a vendored FFmpeg tree was baked from another recipe than this checkout describes. */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkFFmpegRecipes(repoRoot: String) {
    CheckFFmpegRecipesTask().apply {
        expectations.set(Bakes.ffmpegRecipeExpectations(File(repoRoot)))
    }.check()
}

/**
 * Compares the codec-contract transcripts of the JVM tests and the macOS arm64 tests byte for byte.
 * Both test runs write them first; see CONTRIBUTING.md for the command.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun compareCodecContract(transcriptDir: String) {
    val dir = File(transcriptDir)
    val jvm = dir.resolve("jvm.txt")
    val macos = dir.resolve("macosArm64.txt")
    val missing = listOf(jvm, macos).filterNot { it.isFile }
    if (missing.isNotEmpty()) {
        throw BuildFailure(
            "No codec-contract transcript at ${missing.joinToString()}. Run the JVM and macOS arm64 tests first:\n" +
                "  KITECODEC_CONTRACT_TRANSCRIPT=${macos.absolutePath} ./kotlin test -m kiteffmpeg -p jvm -p macosArm64",
        )
    }
    CompareCodecContractTask().apply {
        jvmTranscript.set(jvm)
        macosArm64Transcript.set(macos)
    }.compare()
}

/**
 * Fails unless every desktop JVM platform has its JNI library. A release runs this before it publishes,
 * so the JVM jar cannot ship without one of them.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkDesktopJni(
    platforms: List<String>,
    @Input resourcesDirs: List<Path>,
) {
    val found = resourcesDirs.flatMap { dir ->
        dir.toFile().resolve(BundleHostJniTask.RESOURCE_ROOT).listFiles().orEmpty()
            .filter { it.resolve(BundleHostJniTask.MANIFEST_NAME).isFile }
            .map { it.name }
    }.toSet()
    val missing = platforms.filterNot { it in found }
    if (missing.isNotEmpty()) {
        throw BuildFailure(
            "The JVM jar has no JNI library for ${missing.joinToString()}. Bake the FFmpeg trees of those " +
                "platforms, and link macos-arm64 on an arm64 Mac.",
        )
    }
    // A Linux library must keep the hardening its link asks for, which no test can see (#224).
    val weak = resourcesDirs.flatMap { dir ->
        dir.toFile().resolve(BundleHostJniTask.RESOURCE_ROOT).listFiles().orEmpty()
            .filter { it.name.startsWith("linux-") }
            .flatMap { platform -> platform.listFiles().orEmpty().filter { it.name.endsWith(".so") } }
    }.mapNotNull { library ->
        val missing = ElfHardening.read(library.readBytes()).missing
        if (missing.isEmpty()) null else "${library.parentFile.name}/${library.name} lacks ${missing.joinToString()}"
    }
    if (weak.isNotEmpty()) throw BuildFailure("The JVM jar carries an unhardened JNI library: ${weak.joinToString("; ")}")
        BuildLog.lifecycle("[KiteFFmpeg] the JVM jar carries JNI libraries for ${platforms.joinToString()}")
}
