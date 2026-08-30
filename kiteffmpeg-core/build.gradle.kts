import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import io.github.yuroyami.kiteffmpeg.buildtools.BuildFFmpegTask
import io.github.yuroyami.kiteffmpeg.buildtools.FFmpegRecipeExpectation
import io.github.yuroyami.kiteffmpeg.buildtools.CheckFFmpegRecipesTask
import io.github.yuroyami.kiteffmpeg.buildtools.BundleHostJniTask
import io.github.yuroyami.kiteffmpeg.buildtools.CompareCodecContractTask
import io.github.yuroyami.kiteffmpeg.buildtools.CompileKiteFFmpegCTask
import io.github.yuroyami.kiteffmpeg.buildtools.ExtractJdkHeadersTask
import io.github.yuroyami.kiteffmpeg.buildtools.konanLinuxTools
import io.github.yuroyami.kiteffmpeg.buildtools.FFmpegLicense
import io.github.yuroyami.kiteffmpeg.buildtools.FFmpegPaths
import io.github.yuroyami.kiteffmpeg.buildtools.StaticLinkFlags
import io.github.yuroyami.kiteffmpeg.buildtools.TargetTriple
import io.github.yuroyami.kiteffmpeg.buildtools.IOS_GPL_REFUSAL
import io.github.yuroyami.kiteffmpeg.buildtools.KiteFFmpegJvmTestArgumentProvider
import io.github.yuroyami.kiteffmpeg.buildtools.LinkKiteFFmpegJniTask
import io.github.yuroyami.kiteffmpeg.buildtools.PrepareKiteFFmpegJniHarnessTask
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
// Named explicitly because inside a Gradle Kotlin script `java` resolves to the java extension,
// so `java.io.File(...)` does not compile.
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library).apply(false)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

// The AGP KMP library plugin always creates an Android target and requires compileSdk, even when
// no Android DSL block is present.
val phoneTargetsOnly = providers.gradleProperty("kiteffmpeg.phoneTargetsOnly")
    .map { it.toBoolean() }.getOrElse(false)
// The Android AAR is a FIRST-CLASS published artifact, so
// the Android plugin applies by default. It needs an Android SDK at configuration time; every
// GitHub runner and dev machine here has one, and -Pkiteffmpeg.noAndroid=true is the escape
// hatch for a host that does not (that host then publishes nothing).
val withAndroid = !providers.gradleProperty("kiteffmpeg.noAndroid")
    .map { it.toBoolean() }.getOrElse(false)
if (withAndroid) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

// BCV 0.18.1 emits a second trailing LF for JVM dumps. Canonicalize the declared build output so
// apiDump, apiCheck and Git's blank-at-EOF whitespace gate all consume the same one-LF bytes.
tasks.configureEach {
    if (name == "jvmApiBuild") {
        doLast {
            val dump = outputs.files.singleFile
            val current = dump.readText()
            val canonical = current.trimEnd('\r', '\n') + "\n"
            if (current != canonical) {
                dump.writeText(canonical)
            }
        }
    }
}

kotlin {
    jvmToolchain(21)

    // Keep Kotlin's normal native/Apple hierarchy while adding the two deliberate sharing edges
    // below. An explicit template is required once a project also configures dependsOn manually.
    applyDefaultHierarchyTemplate()

    // Published library: every public declaration states its visibility and return type.
    explicitApi()

    compilerOptions {
        // -Xcontext-parameters was here and is gone. Context parameters are
        // no longer behind a flag on this Kotlin, and this module declares none anyway: a grep for
        // a context declaration across every source set returns nothing. A flag that enables an
        // unused feature on a compiler that no longer needs the flag is two kinds of dead.
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
        )
    }

    /*
     * v0.1 target tiers.
     *
     * STABLE: the targets with prebuilt FFmpeg Release assets and CI coverage:
     * macosArm64, linuxX64, androidNativeArm64/Arm32/X64. JVM, JS and Wasm are always registered;
     * they are portable API variants and do not alter which FFmpeg-backed native targets ship.
     * Since KC-EMBED (2026-08-22) ALL 11 native triples are part of the published set: every
     * one has a CI-proven FFmpeg tree, and FFmpeg rides inside each klib.
     *
     *   -Pkiteffmpeg.stableTargetsOnly=true  OPTIONAL narrowing to the original stable set
     *                                        (macosArm64, linuxX64, android x3). No longer a
     *                                        publish requirement. Default false:
     *                                        local dev, CI and publication see every target.
     *   -Pkiteffmpeg.hostTargetsOnly=true    Register only THIS host's own native desktop target
     *                                        (macosArm64 on an arm64 Mac, linuxX64 on x64 Linux),
     *                                        plus the always-present JVM/JS/Wasm variants.
     *                                        Used exclusively by the CI consumer-e2e smoke job to
     *                                        publishToMavenLocal with just a system FFmpeg present.
     *                                        Mutually exclusive with every other target scope.
     *   -Pkiteffmpeg.applePhoneTargetsOnly=true
     *                                        Register macosArm64, iosArm64 and
     *                                        iosSimulatorArm64 on an arm64 Mac. Local publication
     *                                        only; remote publication always refuses this scope.
     *   -Pkiteffmpeg.phoneTargetsOnly=true
     *                                        Register macosArm64, iosArm64, iosSimulatorArm64,
     *                                        and the ordinary Android JVM library target on an
     *                                        arm64 Mac. Local publication only; remote publication
     *                                        always refuses this scope. JVM/JS/Wasm are always registered.
     */
    val stableTargetsOnly = providers.gradleProperty("kiteffmpeg.stableTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val hostTargetsOnly = providers.gradleProperty("kiteffmpeg.hostTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val applePhoneTargetsOnly = providers.gradleProperty("kiteffmpeg.applePhoneTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val selectedTargetScopes = listOf(
        "kiteffmpeg.stableTargetsOnly" to stableTargetsOnly,
        "kiteffmpeg.hostTargetsOnly" to hostTargetsOnly,
        "kiteffmpeg.applePhoneTargetsOnly" to applePhoneTargetsOnly,
        "kiteffmpeg.phoneTargetsOnly" to phoneTargetsOnly,
    ).filter { it.second }.map { it.first }
    if (selectedTargetScopes.size > 1) {
        throw GradleException(
            "KiteFFmpeg target-set properties are mutually exclusive; selected: " +
                selectedTargetScopes.joinToString(),
        )
    }

    /*
     * Publish guard. Publishing kiteffmpeg-core (anything whose task name starts with "publish",
     * except tasks addressed to :kiteffmpeg-gradle-plugin, which publishes independently via
     * publishPlugins) requires BOTH:
     *   (a) -Pkiteffmpeg.stableTargetsOnly=true, because experimental targets must not leak into
     *       publications, and
     *   (b) an FFmpeg tree for EVERY configured target, enforced below by treating a publish
     *       run as if kiteffmpeg.requireAllTargets=true, so a publication can never silently
     *       drop a target.
     * Exceptions: publishToMavenLocal also accepts -Pkiteffmpeg.hostTargetsOnly=true (the CI
     * consumer-e2e smoke path), the arm64-Mac applePhoneTargetsOnly scope or the S1.c
     * phoneTargetsOnly superset. Remote publishes never accept an experimental scope.
     * Checked against gradle.startParameter.taskNames, which is simple and configuration-cache safe.
     */
    val corePublishTaskNames = gradle.startParameter.taskNames.filter { name ->
        val simpleName = name.substringAfterLast(':')
        simpleName.startsWith("publish") &&
            // Plugin Portal upload, which exists only in :kiteffmpeg-gradle-plugin, never touches core.
            simpleName != "publishPlugins" &&
            !name.startsWith(":kiteffmpeg-gradle-plugin:")
    }
    val corePublishRequested = corePublishTaskNames.isNotEmpty()
    val onlyLocalPublishes = corePublishRequested && corePublishTaskNames.all { it.contains("MavenLocal") }
    if (corePublishRequested && applePhoneTargetsOnly && !onlyLocalPublishes) {
        throw GradleException(
            "Experimental phone selector refusal: -Pkiteffmpeg.applePhoneTargetsOnly=true may only " +
                "be used with publishToMavenLocal; remote publication is forbidden.",
        )
    }
    if (corePublishRequested && phoneTargetsOnly && !onlyLocalPublishes) {
        throw GradleException(
            "Phone-superset selector refusal: -Pkiteffmpeg.phoneTargetsOnly=true may only " +
                "be used with publishToMavenLocal; remote publication is forbidden.",
        )
    }
    // The FULL 11-target set IS the release set. FFmpeg rides inside
    // every native klib, all 11 triples have CI-proven trees, and the every-target-tree hard
    // fail below (requireAllTargets implied true while publishing) is what keeps a publication
    // from silently dropping one. The old stableTargetsOnly gate survives only as an optional
    // narrowing flag; it is no longer a publish requirement.

    fun requireArm64Mac(selector: String) {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        if ("mac" !in osName || osArch !in setOf("aarch64", "arm64")) {
            throw GradleException("$selector=true requires an arm64 Mac; found $osName/$osArch.")
        }
    }

    // Portable variants are part of every publication. Public JVM and Web always use the explicit
    // Kotlin placeholder from unsupportedMain. The phone proof adds an unpublished custom JVM
    // compilation for its JNI boundary tests; it never changes the consumer JVM artifact.
    val jvmTarget = jvm()
    val jniJvmCompilation = if (phoneTargetsOnly) {
        jvmTarget.compilations.create("jniHarness")
    } else {
        null
    }
    val jniJvmTestCompilation = if (phoneTargetsOnly) {
        jvmTarget.compilations.create("jniHarnessTest").apply {
            associateWith(checkNotNull(jniJvmCompilation))
        }
    } else {
        null
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js {
        browser()
        nodejs()
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    if (withAndroid) {
        val androidTarget = (this as ExtensionAware).extensions.getByName("android")
            as KotlinMultiplatformAndroidLibraryTarget
        androidTarget.apply {
            namespace = "io.github.yuroyami.kiteffmpeg"
            compileSdk = 36
            minSdk = 26
            withHostTest {}
            withDeviceTestBuilder {
                sourceSetTreeName = "test"
            }.configure {
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            optimization {
                consumerKeepRules.apply {
                    publish = true
                    file("consumer-rules.pro")
                }
            }
        }
    }

    // Every Kotlin/Native target gets the same single consolidated cinterop. Each resolves its
    // own FFmpeg install via FFmpegPaths.resolve(...): vendored static if available, else system.
    // -Pkiteffmpeg.withDesktopTargets=true ADDS the three cross desktop triples to the phone scope
    // rather than replacing it (phase W). A desktop publication that dropped
    // the Apple and Android variants would break every mobile consumer resolving the same version,
    // which is why this is an addition and not its own scope.
    val withDesktopTargets = providers.gradleProperty("kiteffmpeg.withDesktopTargets")
        .map { it == "true" }.getOrElse(false)

    val knTargetMap: Map<KotlinNativeTarget, TargetTriple> = if (phoneTargetsOnly || applePhoneTargetsOnly) {
        if (applePhoneTargetsOnly) requireArm64Mac("kiteffmpeg.applePhoneTargetsOnly")
        buildMap {
            put(macosArm64(), TargetTriple.MacosArm64)
            put(iosArm64(), TargetTriple.IosArm64)
            put(iosSimulatorArm64(), TargetTriple.IosSimulatorArm64)
            if (withDesktopTargets) {
                put(linuxX64(), TargetTriple.LinuxX64)
                put(linuxArm64(), TargetTriple.LinuxArm64)
                put(mingwX64(), TargetTriple.MingwX64)
            }
        }
    } else if (hostTargetsOnly) {
        // Consumer-e2e smoke scope: just the host's own native desktop target. Portable
        // JVM/JS/Wasm variants are registered above in every scope.
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        when {
            "mac" in osName && osArch == "aarch64" -> mapOf(macosArm64() to TargetTriple.MacosArm64)
            "mac" in osName -> mapOf(macosX64() to TargetTriple.MacosX64)
            "linux" in osName && osArch in setOf("amd64", "x86_64") -> mapOf(linuxX64() to TargetTriple.LinuxX64)
            "linux" in osName && osArch == "aarch64" -> mapOf(linuxArm64() to TargetTriple.LinuxArm64)
            else -> throw GradleException(
                "kiteffmpeg.hostTargetsOnly=true has no desktop target mapping for host $osName/$osArch.",
            )
        }
    } else {
        buildMap {
            // v0.1 STABLE targets.
            put(macosArm64(), TargetTriple.MacosArm64)
            put(linuxX64(), TargetTriple.LinuxX64)
            // Android NDK targets (LGPL FFmpeg profile w/ MediaCodec, see BuildFFmpegTask).
            // Vendored-only: run :kiteffmpeg-core:buildFFmpegForAndroid<Abi> first.
            put(androidNativeArm64(), TargetTriple.AndroidArm64)
            put(androidNativeArm32(), TargetTriple.AndroidArm32)
            put(androidNativeX64(), TargetTriple.AndroidX64)
            if (!stableTargetsOnly) {
                // The rest of the 11-triple set (published since KC-EMBED).
                put(macosX64(), TargetTriple.MacosX64)
                put(iosArm64(), TargetTriple.IosArm64)
                put(iosSimulatorArm64(), TargetTriple.IosSimulatorArm64)
                put(iosX64(), TargetTriple.IosX64)
                put(linuxArm64(), TargetTriple.LinuxArm64)
                put(mingwX64(), TargetTriple.MingwX64)
            }
        }
    }

    val homebrewPrefix = providers.gradleProperty("kiteffmpeg.macos.homebrew.prefix")
        .getOrElse(BuildFFmpegTask.DEFAULT_HOMEBREW_PREFIX)

    // Which FFmpeg flavour to link against locally: -Pkiteffmpeg.ffmpeg.license=gpl for the GPL
    // build, else the LGPL default. Android always links its LGPL MediaCodec profile.
    val selectedLicense =
        if (providers.gradleProperty("kiteffmpeg.ffmpeg.license").orNull?.equals("gpl", ignoreCase = true) == true) {
            FFmpegLicense.GPL
        } else {
            FFmpegLicense.LGPL
        }

    if (
        selectedLicense == FFmpegLicense.GPL &&
        knTargetMap.values.any {
            it == TargetTriple.IosArm64 ||
                it == TargetTriple.IosSimulatorArm64 ||
                it == TargetTriple.IosX64
        }
    ) {
        throw GradleException(IOS_GPL_REFUSAL)
    }

    // Targets whose FFmpeg is missing are skipped with a warning by default; releases/publishing
    // must not silently drop targets, so -Pkiteffmpeg.requireAllTargets=true makes it fail instead.
    // A publish run implies it (see the publish guard above): every target the publication claims
    // must actually have compiled against a real FFmpeg.
    val requireAllTargets = providers.gradleProperty("kiteffmpeg.requireAllTargets")
        .map { it.toBoolean() }.getOrElse(false) || corePublishRequested || phoneTargetsOnly

    if (phoneTargetsOnly) {
        listOf(
            TargetTriple.MacosArm64,
            TargetTriple.IosArm64,
            TargetTriple.IosSimulatorArm64,
            TargetTriple.AndroidArm64,
            TargetTriple.AndroidX64,
        ).forEach { triple ->
            val install = rootDir.resolve("native-libs/lgpl/${triple.dirName}")
            val missing = BuildFFmpegTask.REQUIRED_LIBS.filterNot { library ->
                install.resolve("lib/$library.a").isFile
            }
            val hasHeaders = install.resolve("include/libavformat/avformat.h").isFile
            val hasProvenance = install.resolve(BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH).isFile
            if (missing.isNotEmpty() || !hasHeaders || !hasProvenance) {
                throw GradleException(
                    "kiteffmpeg.phoneTargetsOnly=true requires its complete vendored ${triple.dirName} FFmpeg tree. " +
                        "Run :kiteffmpeg-core:buildFFmpegFor${triple.gradleSuffix} first.",
                )
            }
        }
    }

    val autoBakeFFmpeg = providers.gradleProperty("kiteffmpeg.ffmpeg.autoBake")
        .map { it.toBoolean() }.orElse(false).get()

    knTargetMap.forEach { (target, triple) ->
        val license = if (triple.isAndroid) FFmpegLicense.LGPL else selectedLicense
        val paths = try {
            FFmpegPaths.resolve(project, triple, license)
        } catch (e: GradleException) {
            if (requireAllTargets) throw e
            logger.lifecycle(
                "warning: [KiteFFmpeg] SKIPPING FFmpeg cinterop/link setup for target '${triple.dirName}' " +
                    "(${license.dirName}) because no FFmpeg build found. ${e.message} " +
                    "Set -Pkiteffmpeg.requireAllTargets=true to fail the build instead.",
            )
            null
        } ?: return@forEach

        /*
         * The FFmpeg helper layer, compiled for THIS target into its own directory and embedded in
         * the cinterop klib by ffmpeg.def's `staticLibraries = libkitecodec.a`. It sits after the
         * FFmpeg path resolution above on purpose: the helper units include 16 libav headers, so
         * a target with no FFmpeg tree cannot compile it and is skipped here exactly as it is
         * skipped for the cinterop.
         *
         * The output directory is keyed by the konan target name and shared with nothing, which is
         * register item B1-11: a wrong-architecture archive is embedded without complaint and fails
         * only at the consumer's final link.
         */
        val compileC = tasks.register<CompileKiteFFmpegCTask>("compileKiteFFmpegCFor${triple.gradleSuffix}") {
            /*
             * Auto-bake (-Pkiteffmpeg.ffmpeg.autoBake=true).
             *
             * The FFmpeg tree is a dead artifact: nothing rebuilds it, so a recipe change in
             * buildSrc and the .a files on disk drift apart in silence. Measured 2026-08-19: the
             * av1_videotoolbox pin landed and every Apple tree still lacked it a day later with no
             * gate red. Depending on the bake hands that problem to Gradle, which already knows
             * when the task's inputs (including its buildSrc implementation classpath) moved, and
             * skips it as UP-TO-DATE when they did not.
             *
             * Opt-in, because the first bake of a missing tree is tens of minutes and nobody wants
             * that to happen by surprise inside an ordinary build.
             */
            if (autoBakeFFmpeg) dependsOn("buildFFmpegFor${triple.gradleSuffix}${license.taskSuffix}")
            konanTargetName.set(target.konanTarget.name)
            sourceDir.set(rootDir.resolve("native/kitecodec-c/src"))
            includeDir.set(rootDir.resolve("native/kitecodec-c/include"))
            ffmpegIncludeDirs.set(listOf(paths.includeDir))
            // The version headers the archive freezes, tracked by CONTENT:
            // a path string survives a brew upgrade that rewrites every file under it.
            ffmpegVersionHeaders.from(
                listOf("libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample")
                    .flatMap { lib ->
                        listOf("version.h", "version_major.h").map { "${paths.includeDir}/$lib/$it" }
                    } + "${paths.includeDir}/libavutil/ffversion.h",
            )
            /*
             * What this archive was built for, read by the FFmpeg identity gate in
             * native/kitecodec-c/src/kitecodec_abi.c and reported in every rejection and every
             * diagnostic dump (register items B1-02 and B1-21). They are reported, never compared:
             * the comparison is between the six LIB*_VERSION_INT macros the same compile froze and
             * the six *_version() functions the linked runtime answers with. What these three add is
             * the other half of an actionable sentence, which is what the build decided to provision.
             *
             * The licence one is the one that matters most today. This build declares a flavour here
             * while the linked Homebrew runtime's avutil_license() returns "GPL version 3 or later",
             * so both strings ride in the report and the contradiction is visible instead of latent.
             */
            buildDefines.set(
                mapOf(
                    CompileKiteFFmpegCTask.DEFINE_FFMPEG_REF to BuildFFmpegTask.DEFAULT_SOURCE_REF,
                    CompileKiteFFmpegCTask.DEFINE_FFMPEG_LICENSE to license.dirName,
                    CompileKiteFFmpegCTask.DEFINE_FFMPEG_DIR to paths.libDir,
                ),
            )
            // java.io.File and not project.file(...): the latter captures a reference to this
            // script inside the provider, which the configuration cache refuses to serialize with
            // "cannot serialize Gradle script object references".
            konanDataDir.fileProvider(
                providers.environmentVariable("KONAN_DATA_DIR")
                    .orElse(providers.systemProperty("user.home").map { home -> "$home/.konan" })
                    .map { path -> File(path) },
            )
            outputDir.set(layout.buildDirectory.dir("kitecodec-c/${target.konanTarget.name}"))
        }

        target.compilations.getByName("main").cinterops {
            // One cinterop module for all six libav* libraries, which keeps AVCodec, AVFrame,
            // AVPacket etc. as a SINGLE Kotlin type across every binding (each cinterop module
            // otherwise generates its own duplicate copy of identical C types).
            create("ffmpeg") {
                // The VENDORED def embeds the six libav* archives plus
                // libdav1d into the cinterop klib (the same `staticLibraries` slot libkitecodec.a
                // has always ridden), and carries the platform linker flags, so a consumer needs
                // nothing but the dependency line. The SYSTEM def is the dev fallback for a host
                // with only a shared brew/apt FFmpeg: nothing to embed, plain -lav* flags.
                val defName = if (paths.isStaticVendored) "ffmpeg.def" else "ffmpeg-system.def"
                defFile(project.file("src/nativeInterop/cinterop/$defName"))
                includeDirs.allHeaders(paths.includeDir)
                extraOpts("-libraryPath", paths.libDir)
                compilerOpts("-I${paths.includeDir}")
                // The helper layer: its header for the `headers` entry, and its archive directory as
                // a SECOND -libraryPath beside FFmpeg's. Two independent -libraryPath entries
                // coexist. This is not a libraryPaths line in the def because a def-relative path
                // resolves against the Gradle project directory rather than against the def, and
                // because there is one archive directory per konan target.
                val cIncludeDir = rootDir.resolve("native/kitecodec-c/include")
                val cArchiveDir = layout.buildDirectory.dir("kitecodec-c/${target.konanTarget.name}")
                    .get().asFile
                includeDirs.allHeaders(cIncludeDir)
                compilerOpts("-I${cIncludeDir.absolutePath}")
                extraOpts("-libraryPath", cArchiveDir.absolutePath)
            }
        }
        /*
         * cinterop embeds the archive, so it has to exist first, AND the archive has to be a
         * declared input of the cinterop task.
         *
         * The dependency alone is not enough, and the plan's section 15.0 said otherwise on the
         * strength of a different prototype. Measured here at B1.3, in a checkout with no copied
         * Gradle state: editing only a helper source re-executes the C compile and writes a
         * new archive, and `cinteropFfmpegMacosArm64` then reports UP-TO-DATE and keeps the STALE
         * archive inside the klib, with or without the configuration cache. Gradle says why under
         * `--info`: "Caching disabled for task ':kiteffmpeg-core:cinteropFfmpegMacosArm64' because:
         * CInterop task uses custom Up-To-Date check for content of headers instead of Gradle
         * mechanisms." That check covers the def file and the headers, not a library the def merely
         * names. A clean build and CI were always correct; local incremental development was not,
         * and every sub-phase from B1.4 onward edits C bodies.
         *
         * `inputs.files` on the archive fixes it: an input change makes a task out of date no
         * matter what its own predicate says. The missing-archive direction never needed this,
         * because cinterop fails loudly with a non-zero exit when `staticLibraries` cannot be
         * found.
         *
         * `matching { }.configureEach { }` rather than `named(...)`: the cinterop task is registered
         * by the Kotlin plugin after this block runs, and a filtered live collection covers tasks
         * added later while `named` would fail on a task that does not exist yet.
         */
        val cinteropTaskName = "cinteropFfmpeg${target.name.replaceFirstChar { it.uppercaseChar() }}"
        tasks.matching { it.name == cinteropTaskName }.configureEach {
            dependsOn(compileC)
            inputs.files(compileC.map { c -> c.outputDir.file(CompileKiteFFmpegCTask.ARCHIVE_NAME) })
                .withPropertyName("kiteCodecCArchive")
                .withPathSensitivity(PathSensitivity.NAME_ONLY)
            // The embedded FFmpeg archives need the same treatment for the same measured reason
            // (see the block comment above): cinterop's own up-to-date check covers headers, not
            // libraries the def names, so a rebaked tree would otherwise publish a STALE embed.
            if (paths.isStaticVendored) {
                inputs.files(fileTree(paths.libDir) { include("*.a") })
                    .withPropertyName("kiteCodecFFmpegArchives")
                    .withPathSensitivity(PathSensitivity.NAME_ONLY)
            }
        }
        target.binaries.all {
            linkerOpts("-L${paths.libDir}")
            // A STATIC libavcodec.a resolves nothing itself: dav1d (mandatory since KC-EMBED)
            // and the platform libraries/frameworks must be named. Since the embedded def these
            // flags also ride the klib for consumers; naming them here keeps this project's own
            // test binaries correct even under the system def.
            linkerOpts(StaticLinkFlags.forTarget(triple, license, paths.isStaticVendored))
            if (!paths.isStaticVendored && triple in setOf(TargetTriple.MacosArm64, TargetTriple.MacosX64)) {
                // Embed Homebrew rpath for dev convenience; release builds use static vendored libs.
                linkerOpts("-rpath", paths.libDir)
            }
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlinx.cinterop.BetaInteropApi")
            }
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // The decode paths are flows, so collecting one in a test needs a coroutine runner that
            // works on the web too, where there is no thread to block.
            implementation(libs.kotlinx.coroutines.test)
        }
        val commonMain = getByName("commonMain")
        // Encode, mux and filter are refused on BOTH web targets: S6 is "it plays on
        // the web", and every one of those classes is hand-written work with a strong platform
        // alternative. One copy of each refusal, shared, rather than two that drift.
        val webRefusedMain = maybeCreate("webRefusedMain").apply {
            dependsOn(commonMain)
        }
        getByName("wasmJsMain").dependsOn(webRefusedMain)
        // `js` refuses everything else too; `wasmJs` implements the playback half for real.
        val unsupportedMain = maybeCreate("unsupportedMain").apply {
            dependsOn(webRefusedMain)
        }
        // `js` keeps the placeholder. `wasmJs` does NOT: it carries a real backend over the
        // generated binding, so it must not inherit the throwing actuals. Attaching
        // unsupportedMain to webMain would give both of them to it.
        getByName("jsMain").dependsOn(unsupportedMain)

        val commonTest = getByName("commonTest")
        val unsupportedTest = maybeCreate("unsupportedTest").apply {
            dependsOn(commonTest)
        }
        getByName("jsTest").dependsOn(unsupportedTest)

        // The JVM is a REAL backend, not a placeholder: it runs the same JNI adapter the Android
        // target runs, over the same opaque C ABI (phase W). unsupportedMain
        // is web's alone now.
        val jvmAndAndroidMain = maybeCreate("jvmAndAndroidMain").apply {
            dependsOn(commonMain)
        }
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)

        // The shared codec-contract suite is what a real backend has to satisfy, so the jvm test
        // tree runs it instead of the placeholder's throw-everything assertions.
        val codecContractTest = maybeCreate("codecContractTest").apply {
            dependsOn(commonTest)
        }
        getByName("jvmTest").dependsOn(codecContractTest)
        // The macOS native arm runs the same suite, and always did; it only sat inside the phone
        // scope because the source set used to be created there.
        //
        // findByName, not getByName: under -Pkiteffmpeg.hostTargetsOnly=true the registered desktop
        // target is the HOST's, so on a Linux or Windows runner there is no macosArm64 target and
        // therefore no macosArm64Test source set. getByName threw at configuration time and took
        // the whole build with it, which is how the Linux consumer smoke job failed with
        // "KotlinSourceSet with name 'macosArm64Test' not found". A missing source set here means
        // the target is not in this build at all, not that the wiring was forgotten.
        findByName("macosArm64Test")?.dependsOn(codecContractTest)

        if (withAndroid) {
            getByName("androidMain").dependsOn(jvmAndAndroidMain)
        }
        if (phoneTargetsOnly) {
            // A custom JVM compilation belongs to its own source-set tree, so it cannot legally
            // depend on the published main/test trees. Mirror their source directories into an
            // isolated harness tree instead; no source is copied and no harness variant is published.
            val jniHarnessCommonMain = maybeCreate("jniHarnessCommonMain").apply {
                kotlin.srcDir("src/commonMain/kotlin")
                dependencies {
                    implementation(libs.kotlinx.coroutines.core)
                    implementation(libs.kotlinx.atomicfu)
                }
            }
            val jniHarnessPlatformMain = maybeCreate("jniHarnessPlatformMain").apply {
                kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
                dependsOn(jniHarnessCommonMain)
            }
            val jniJvmMain = maybeCreate("jniJvmMain").apply {
                kotlin.srcDir("src/jvmMain/kotlin")
                dependsOn(jniHarnessPlatformMain)
            }
            checkNotNull(jniJvmCompilation).defaultSourceSet.dependsOn(jniJvmMain)

            val jniHarnessCommonTest = maybeCreate("jniHarnessCommonTest").apply {
                kotlin.srcDir("src/commonTest/kotlin")
                dependencies {
                    implementation(kotlin("test"))
                    implementation(kotlin("test-junit"))
                }
            }
            val jniHarnessContractTest = maybeCreate("jniHarnessContractTest").apply {
                kotlin.srcDir("src/codecContractTest/kotlin")
                dependsOn(jniHarnessCommonTest)
            }
            val jniJvmTest = maybeCreate("jniJvmTest").apply {
                // The JVM contract actuals live in the PUBLISHED jvm test tree now that the jvm
                // variant is real; the harness mirrors them rather than keeping a twin.
                kotlin.srcDir("src/jvmTest/kotlin")
                dependsOn(jniHarnessContractTest)
            }
            checkNotNull(jniJvmTestCompilation).defaultSourceSet.dependsOn(jniJvmTest)
            getByName("androidDeviceTest").apply {
                dependsOn(codecContractTest)
                dependencies {
                    implementation(kotlin("test"))
                    implementation(libs.androidx.test.core)
                    implementation(libs.androidx.test.runner)
                    implementation(libs.androidx.test.ext.junit)
                }
            }
        }
    }

    if (phoneTargetsOnly) {
        val jniTestCompilation = checkNotNull(jniJvmTestCompilation)
        tasks.register<Test>("jniJvmTest") {
            group = "verification"
            description = "Runs the unpublished JVM/JNI boundary and shared codec-contract harness."
            testClassesDirs = jniTestCompilation.output.classesDirs
            classpath = files(
                jniTestCompilation.output.allOutputs,
                jniTestCompilation.runtimeDependencyFiles,
            )
            shouldRunAfter("jvmTest")
        }
        tasks.named("allTests") {
            dependsOn("jniJvmTest")
        }
    }
}

/*
 * checkFFmpegRecipes: is every vendored tree still what this checkout would bake?
 *
 * The manual half of what -Pkiteffmpeg.ffmpeg.autoBake=true automates; the reasoning lives on
 * CheckFFmpegRecipesTask. Each bake feeds it below, and only when this task is actually asked for:
 * the expectations are gathered inside its own configure block, so an ordinary build never
 * realises a BuildFFmpegTask to compute them.
 */
val checkFFmpegRecipes = tasks.register<CheckFFmpegRecipesTask>("checkFFmpegRecipes") {
    group = "kiteffmpeg"
    description =
        "Fails when a vendored FFmpeg tree was baked with a different recipe than this checkout describes."
}

// Register the :buildFFmpegFor<Target>[Gpl] tasks. Users run these to populate
// native-libs/<license>/<target> with static .a files; subsequent Gradle syncs pick them up.
fun registerBuildFFmpeg(triple: TargetTriple, flavour: FFmpegLicense) = registerBake(
    tasks.register<BuildFFmpegTask>("buildFFmpegFor${triple.gradleSuffix}${flavour.taskSuffix}") {
        // Hand this bake's identity and expected recipe to checkFFmpegRecipes. Realised when that
        // task is, and pure: expectedRecipeFingerprint stubs every toolchain lookup.
        target = triple
        license = flavour
        sourceRef = BuildFFmpegTask.DEFAULT_SOURCE_REF
        // Committed source patches, applied to the scratch copy before configure (window 2c).
        sourcePatches.from(fileTree(rootDir.resolve("native/patches/ffmpeg")) { include("*.patch") })
        // Release builds must produce a tree that links on a machine with none of these installed.
        requireSelfContained.set(
            providers.gradleProperty("kiteffmpeg.ffmpeg.selfContained").map { it.toBoolean() }.orElse(false),
        )
        // dav1d is MANDATORY since KC-EMBED (2026-08-22): one bake task produces a complete
        // tree, so the dav1d cross-build always runs (UP-TO-DATE when already built).
        dependsOn("buildDav1dFor${triple.gradleSuffix}")
        sourceDir.set(rootDir.resolve("vendor/ffmpeg"))
        outputDir.set(rootDir.resolve("native-libs/${flavour.dirName}/${triple.dirName}"))
    },
)

/**
 * Registers [bake] with checkFFmpegRecipes and returns it unchanged.
 *
 * `bake.get()` realises the task OBJECT to read its recipe; it creates no dependency and runs no
 * build. It happens inside the check task's configure block, so the cost is paid only by someone
 * who asked for the check.
 */
fun registerBake(bake: TaskProvider<BuildFFmpegTask>): TaskProvider<BuildFFmpegTask> {
    checkFFmpegRecipes.configure {
        val task = bake.get()
        expectations.add(
            FFmpegRecipeExpectation(
                taskName = task.name,
                treePath = task.outputDir.get().asFile.absolutePath,
                fingerprint = task.expectedRecipeFingerprint(),
            ),
        )
    }
    return bake
}

// Register :buildFFmpegForWasm[Simd|Mt]. Not a TargetTriple: konan has no wasm
// target, so none of the cross-toolchain plumbing above applies. Output goes to a sibling of the
// native trees so packaging finds it the same way.
fun registerBuildFFmpegWasm(variantName: String, taskSuffix: String) =
    tasks.register<io.github.yuroyami.kiteffmpeg.buildtools.BuildFFmpegWasmTask>(
        "buildFFmpegForWasm$taskSuffix",
    ) {
        variant = variantName
        sourceRef = BuildFFmpegTask.DEFAULT_SOURCE_REF
        emscriptenLlvmBin.set(
            providers.gradleProperty("kiteffmpeg.emscripten.llvmBin")
                .orElse(io.github.yuroyami.kiteffmpeg.buildtools.BuildFFmpegWasmTask.DEFAULT_EMSCRIPTEN_LLVM_BIN),
        )
        sourceDir.set(rootDir.resolve("vendor/ffmpeg"))
        outputDir.set(rootDir.resolve("native-libs/lgpl/wasm32${if (variantName == "base") "" else "-" + variantName}"))
    }

registerBuildFFmpegWasm("base", "")

// Compile the portable C helper layer for wasm. Depends on the wasm FFmpeg tree for
// its headers, which is why it names the base variant's include directory explicitly.
val wasmFFmpegRoot = rootDir.resolve("native-libs/lgpl/wasm32")
// The web binding, generated from the gated signature baseline.
tasks.register<io.github.yuroyami.kiteffmpeg.buildtools.GenerateWasmBindingTask>("generateWasmBinding") {
    signatureBaseline.set(rootDir.resolve("native/kitecodec-c/signature-baseline.txt"))
    outputDir.set(rootDir.resolve("native-libs/deps/wasm32/binding"))
}

// The generator writes into gitignored native-libs/, so the file that actually
// COMPILES is a committed copy under wasmJsMain. This holds the two equal; without it the drift
// only shows up at runtime in a browser.
val checkWasmBindingMirror =
    tasks.register<io.github.yuroyami.kiteffmpeg.buildtools.CheckWasmBindingMirrorTask>("checkWasmBindingMirror") {
        group = "verification"
        description = "Fails if the committed wasm binding differs from what generateWasmBinding would write."
        signatureBaseline.set(rootDir.resolve("native/kitecodec-c/signature-baseline.txt"))
        mirrorFile.set(
            rootDir.resolve("kiteffmpeg-core/src/wasmJsMain/kotlin/io/github/yuroyami/kiteffmpeg/wasm/KiteFFmpegWasm.kt"),
        )
    }

tasks.named("check") { dependsOn(checkWasmBindingMirror) }

tasks.register<io.github.yuroyami.kiteffmpeg.buildtools.CompileKiteFFmpegCWasmTask>("compileKiteFFmpegCForWasm") {
    dependsOn("buildFFmpegForWasm")
    sourceDir.set(rootDir.resolve("native/kitecodec-c/src"))
    includeDir.set(rootDir.resolve("native/kitecodec-c/include"))
    handlesDir.set(rootDir.resolve("native/kitecodec-handles"))
    ffmpegIncludeDir.set(wasmFFmpegRoot.resolve("include"))
    ffmpegVersionHeaders.from(
        wasmFFmpegRoot.resolve("include/libavutil/ffversion.h"),
    )
    buildDefines.set(
        mapOf(
            CompileKiteFFmpegCTask.DEFINE_FFMPEG_REF to BuildFFmpegTask.DEFAULT_SOURCE_REF,
            CompileKiteFFmpegCTask.DEFINE_FFMPEG_LICENSE to FFmpegLicense.LGPL.dirName,
            CompileKiteFFmpegCTask.DEFINE_FFMPEG_DIR to wasmFFmpegRoot.resolve("lib").absolutePath,
        ),
    )
    // NOT under native-libs/lgpl/wasm32: that is BuildFFmpegWasmTask's declared output, and it
    // wipes the directory before installing. Nesting here made FFmpeg rebuild on every run and
    // would have deleted this archive the next time it did. Siblings, like the other deps trees.
    outputDir.set(rootDir.resolve("native-libs/deps/wasm32/kiteffmpeg"))
}
registerBuildFFmpegWasm("simd", "Simd")
registerBuildFFmpegWasm("mt", "Mt")

// Register the :buildDav1dFor<Target> tasks: cross-compile dav1d into
// native-libs/deps/<target>, which is where a dav1d-enabled buildFFmpegFor<Target> looks.
fun registerBuildDav1d(triple: TargetTriple) =
    tasks.register<io.github.yuroyami.kiteffmpeg.buildtools.BuildDav1dTask>("buildDav1dFor${triple.gradleSuffix}") {
        target = triple
        sourceRef = io.github.yuroyami.kiteffmpeg.buildtools.BuildDav1dTask.DEFAULT_SOURCE_REF
        sourceDir.set(rootDir.resolve("vendor/dav1d"))
        outputDir.set(rootDir.resolve("native-libs/deps/${triple.dirName}/dav1d"))
        // Configuration-time capture; the action may not touch Project (config cache).
        repoRoot.set(rootDir)
    }

// Linux and Windows joined the set when KC-AV1SW went from "demand-driven" to demanded: AV1 files
// reach a desktop as often as a phone, and neither tree compiles a single hwaccel, so without dav1d
// their AV1 route is the typed refusal and nothing else. The set lives on the task so the tasks
// registered and the cross files written can never disagree.
io.github.yuroyami.kiteffmpeg.buildtools.BuildDav1dTask.SUPPORTED_TARGETS.forEach { registerBuildDav1d(it) }

// Register the :buildAssChainFor<Target> tasks (phase L, owner-pulled 2026-08-16): the libass
// rendering chain into native-libs/deps/<target>/ass-chain, consumed by kiteplayer-libass and
// the plugin's libass toggle, never by a default artifact.
fun registerBuildAssChain(triple: TargetTriple) =
    tasks.register<io.github.yuroyami.kiteffmpeg.buildtools.BuildAssChainTask>("buildAssChainFor${triple.gradleSuffix}") {
        target = triple
        sourceRefs = io.github.yuroyami.kiteffmpeg.buildtools.BuildAssChainTask.DEFAULT_SOURCE_REFS
        vendorDir.set(rootDir.resolve("vendor"))
        outputDir.set(rootDir.resolve("native-libs/deps/${triple.dirName}/ass-chain"))
    }

io.github.yuroyami.kiteffmpeg.buildtools.BuildAssChainTask.SUPPORTED_TARGETS.forEach { registerBuildAssChain(it) }

TargetTriple.entries.forEach { triple ->
    // LGPL flavour for every target (the default).
    registerBuildFFmpeg(triple, FFmpegLicense.LGPL)
}

/*
 * NO GPL BUILD TASKS. Owner decision 2026-08-21, taken as this repository went public.
 *
 * This project builds and publishes the LGPL flavour only. `FFmpegLicense.GPL` still EXISTS,
 * because it labels a tree rather than naming a feature: a consumer who builds their own
 * x264/x265 FFmpeg needs to say so, since the label is a path segment
 * (`<localRoot>/gpl/<triple>`) and it rides into the identity report. What is gone is KiteFFmpeg
 * PRODUCING such a tree.
 *
 * Two reasons, and the second is the one that forced the timing:
 *
 * 1. Distributing a GPL-flavoured binary makes the consumer's whole application GPL-3.0. That is
 *    a decision no library should make on a user's behalf by default, and publishing it as a
 *    Release asset is exactly making it on their behalf.
 * 2. Register row P0-14: `portableDesktopArgs()` IGNORES the licence argument, so
 *    `buildFFmpegForLinuxX64Gpl` and its two siblings produced trees containing no GPL code at
 *    all and wrote them into a directory named `gpl`. Private, that is a curiosity. Published,
 *    it is a false public statement about licensing. Deleting the tasks deletes the row.
 */

tasks.register("buildFFmpegForAll") {
    group = "kiteffmpeg"
    description = "Cross-compile the LGPL FFmpeg for every supported Kotlin/Native target."
    dependsOn(TargetTriple.entries.map { "buildFFmpegFor${it.gradleSuffix}" })
}

/*
 * Publishing to Maven Central (Central Portal). Signing only activates when in-memory GPG keys are
 * present in the environment (ORG_GRADLE_PROJECT_signingInMemoryKey /
 * ORG_GRADLE_PROJECT_signingInMemoryKeyPassword), the vanniktech plugin's default, so local builds
 * without keys are unaffected.
 */
mavenPublishing {
    publishToMavenCentral()
    // Only when a key actually exists. The comment above said signing "only activates when
    // in-memory GPG keys are present", and that was not true of an unconditional
    // signAllPublications(): a publishToMavenLocal on a machine with no key failed with
    // "Cannot perform signing task ':kiteffmpeg-core:signJsPublication' because it has no
    // configured signatory". That is how the consumer smoke job died once it finally got far
    // enough to publish. publish.yml sets ORG_GRADLE_PROJECT_signingInMemoryKey, which Gradle
    // exposes as this property, so the real Central publication still signs everything.
    // Blank counts as absent: a CI runner that exports the variable from an unset secret gets an
    // empty string, and isPresent alone would call that a key and fail at signing time instead.
    if (!providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()) {
        signAllPublications()
    } else {
        logger.lifecycle(
            "[KiteFFmpeg] no signingInMemoryKey: publications are UNSIGNED. Fine for mavenLocal " +
                "and CI smoke tests; Maven Central rejects unsigned artifacts, and publish.yml " +
                "supplies the key.",
        )
    }

    // Coordinates come from the project defaults: GROUP / VERSION in gradle.properties (applied to
    // allprojects at the root) + this module's name -> io.github.yuroyami:kiteffmpeg-core:<VERSION>.
    // (An explicit coordinates() call is not possible here: another applied plugin already reads them,
    // and thereby finalises them, during configuration.)

    pom {
        name = "KiteFFmpeg"
        description = providers.gradleProperty("DESCRIPTION").get()
        url = "https://github.com/yuroyami/KiteFFmpeg"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
            // The native klibs and the JVM/Android native libraries EMBED
            // compiled FFmpeg, so the artifact redistributes LGPL bytes and must say so. The
            // complete corresponding source is attached to the matching v-tag GitHub release.
            license {
                name = "GNU Lesser General Public License, Version 2.1 or later (bundled FFmpeg)"
                url = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt"
                distribution = "repo"
            }
            license {
                name = "BSD 2-Clause License (bundled dav1d)"
                url = "https://code.videolan.org/videolan/dav1d/-/blob/master/COPYING"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "yuroyami"
                name = "yuroyami"
                url = "https://github.com/yuroyami"
            }
        }
        scm {
            url = "https://github.com/yuroyami/KiteFFmpeg"
            connection = "scm:git:git://github.com/yuroyami/KiteFFmpeg.git"
            developerConnection = "scm:git:ssh://git@github.com/yuroyami/KiteFFmpeg.git"
        }
    }

    // Last: configure() finalises the coordinates above.
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = true,
        ),
    )
}

/*
 * ── The JNI adapter link tasks (S1.c.1 step 6) ──────────────────────────────────────────────
 *
 * Scaffolded 2026-08-12 by the planner from a hand-proved link on this machine; see PLANNING.md
 * 17.4.3's scaffold layer. Three arms:
 *
 *   linkKiteFFmpegJniMacosArm64   test-only dylib jvmTest loads via the kiteffmpeg.jni.path
 *                                system property. Links the vendored macOS LGPL FFmpeg plus the
 *                                Homebrew SvtAv1Enc/graphite2 the vendored archives reference,
 *                                exactly as the hand proof measured, and exports only JNI_OnLoad
 *                                through -exported_symbols_list (Mach-O has no version script).
 *   linkKiteFFmpegJniAndroidArm64 / linkKiteFFmpegJniAndroidX64
 *                                the AAR's jniLibs inputs. NDK r29 clang, 16 KiB page flags and
 *                                the ELF version script per the S1.c.1 recipe. They require the
 *                                Android FFmpeg trees the producer tasks vendor first.
 */
run {
    val jniDir = rootDir.resolve("native/kitecodec-jni")
    // The handle table, shared with the web binding rather than copied.
    val handlesDir = rootDir.resolve("native/kitecodec-handles")
    val opaqueInclude = rootDir.resolve("native/kitecodec-c/include")
    val javaHome = javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath.asFile }

    val konanDataDirProvider = providers.environmentVariable("KONAN_DATA_DIR")
        .orElse(providers.systemProperty("user.home").map { home -> "$home/.konan" })
        .map(::File)
    val macosFfmpegInclude = rootDir.resolve("native-libs/lgpl/macos-arm64/include")
    val macosFfmpegLib = rootDir.resolve("native-libs/lgpl/macos-arm64/lib")
    // dav1d is vendored per target and toggled by the consumer DSL, so the flag follows the tree
    // rather than a constant: the Android arms already do exactly this a few lines below.
    // The portable macOS profile: the six libav* archives, the mandatory dav1d, SDK
    // zlib and the media frameworks. Nothing from Homebrew: the fat third-party stack died with
    // the fat profile, and the JNI bundle is self-contained again because there are no shared
    // dylib dependencies left to carry.
    val macosJniLinkFlags = listOf(
        "-lavformat", "-lavcodec", "-lavfilter", "-lavutil", "-lswscale", "-lswresample",
        "-ldav1d",
        "-lz",
        "-framework", "CoreFoundation", "-framework", "CoreMedia",
        "-framework", "CoreVideo", "-framework", "VideoToolbox",
        "-framework", "AudioToolbox",
    )
    val androidHelperTasks = LinkKiteFFmpegJniTask.ANDROID_ABI_RECIPES.associateWith { arm ->
        val ffmpegInclude = rootDir.resolve("native-libs/lgpl/${arm.ffmpegDirName}/include")
        val ffmpegLib = rootDir.resolve("native-libs/lgpl/${arm.ffmpegDirName}/lib")
        tasks.register<CompileKiteFFmpegCTask>(arm.helperTaskName) {
            konanTargetName.set(arm.konanTargetName)
            sourceDir.set(rootDir.resolve("native/kitecodec-c/src"))
            includeDir.set(opaqueInclude)
            ffmpegIncludeDirs.set(listOf(ffmpegInclude.absolutePath))
            ffmpegVersionHeaders.from(
                listOf("libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample")
                    .flatMap { library ->
                        listOf("version.h", "version_major.h").map { "$ffmpegInclude/$library/$it" }
                    } + "$ffmpegInclude/libavutil/ffversion.h",
            )
            buildDefines.set(
                mapOf(
                    CompileKiteFFmpegCTask.DEFINE_FFMPEG_REF to BuildFFmpegTask.DEFAULT_SOURCE_REF,
                    CompileKiteFFmpegCTask.DEFINE_FFMPEG_LICENSE to FFmpegLicense.LGPL.dirName,
                    CompileKiteFFmpegCTask.DEFINE_FFMPEG_DIR to ffmpegLib.absolutePath,
                ),
            )
            konanDataDir.fileProvider(konanDataDirProvider)
            outputDir.set(layout.buildDirectory.dir("kitecodec-c-jni/${arm.konanTargetName}"))
        }
    }

    val macosJniLink = tasks.register<LinkKiteFFmpegJniTask>(
        "linkKiteFFmpegJniMacosArm64",
    ) {
        group = "kiteffmpeg"
        description = "Links the test-only macOS JNI dylib against the vendored LGPL FFmpeg"
        jniSources.from(fileTree(jniDir) { include("*.c", "*.h", "methods.def") })
        jniSources.from(fileTree(handlesDir) { include("*.c", "*.h") })
        opaqueIncludeDir.set(opaqueInclude)
        val helperCompile = tasks.named<CompileKiteFFmpegCTask>("compileKiteFFmpegCForMacosArm64")
        dependsOn(helperCompile)
        helperArchive.from(helperCompile.flatMap { it.outputDir.file(CompileKiteFFmpegCTask.ARCHIVE_NAME) })
        ffmpegLibDir.set(macosFfmpegLib)
        compiler.set("/usr/bin/clang")
        extraIncludeDirs.set(
            javaHome.map { listOf("${it.absolutePath}/include", "${it.absolutePath}/include/darwin") },
        )
        libSearchDirs.set(emptyList())
        linkFlags.set(macosJniLinkFlags)
        exportControlFile.set(jniDir.resolve("exports.macos"))
        exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS)
        outputDirectory.set(layout.buildDirectory.dir("kitecodec-jni/macos-arm64"))
        outputLibrary.set(outputDirectory.file("libkitecodec_jni.dylib"))
    }

    // The two Android arms, exactly the S1.c.1 step 6 recipe. ANDROID_NDK_HOME is read at
    // configuration from the environment the S1.c commands pin; a missing NDK or FFmpeg tree
    // fails the arm at execution with the producer task named in the message.
    val ndkHome = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse("/Users/macbook/WORKSTATION/AndroidSDK/ndk/29.0.14206865")
    val androidJniLinks = LinkKiteFFmpegJniTask.ANDROID_ABI_RECIPES.associateWith { arm ->
        val helperCompile = androidHelperTasks.getValue(arm)
        tasks.register<LinkKiteFFmpegJniTask>(arm.linkTaskName) {
            group = "kiteffmpeg"
            description = "Links libkitecodec_jni.so for ${arm.abiDirectory}"
            jniSources.from(fileTree(jniDir) { include("*.c", "*.h", "methods.def") })
            jniSources.from(fileTree(handlesDir) { include("*.c", "*.h") })
            opaqueIncludeDir.set(opaqueInclude)
            dependsOn(helperCompile)
            helperArchive.from(helperCompile.flatMap { it.outputDir.file(CompileKiteFFmpegCTask.ARCHIVE_NAME) })
            ffmpegLibDir.set(rootDir.resolve("native-libs/lgpl/${arm.ffmpegDirName}/lib"))
            compiler.set(
                ndkHome.map { "$it/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang" },
            )
            extraIncludeDirs.set(emptyList())
            libSearchDirs.set(emptyList())
            linkFlags.set(
                LinkKiteFFmpegJniTask.androidLinkFlags(
                    arm,
                    dav1d = rootDir.resolve("native-libs/lgpl/${arm.ffmpegDirName}/lib/libdav1d.a").exists(),
                ),
            )
            exportControlFile.set(jniDir.resolve("exports.map"))
            exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.ELF_VERSION_SCRIPT)
            outputDirectory.set(layout.buildDirectory.dir("kitecodec-jni/${arm.ffmpegDirName}"))
            outputLibrary.set(outputDirectory.file("${arm.abiDirectory}/libkitecodec_jni.so"))
        }
    }

    if (phoneTargetsOnly) {
        val prepareMajorMismatchHeaders = tasks.register<PrepareKiteFFmpegJniHarnessTask>(
            "prepareKiteFFmpegJniMajorMismatchHeaders",
        ) {
            group = "verification"
            description = "Generates an unrenamed overlay from the hermetic major-mismatch fake header."
            sourceDirectory.set(opaqueInclude)
            mutationSourceFile.set(
                rootDir.resolve(
                    "native/kitecodec-c/tests/fake_headers/major_mismatch/kitecodec_ffmpeg_versions.h",
                ),
            )
            relativeFile.set("kitecodec_ffmpeg_versions.h")
            expectedText.set("#define KC_CASE kc_major_mismatch\n#include \"../kc_rename.h\"\n\n")
            replacementText.set("")
            outputDirectory.set(layout.buildDirectory.dir("generated/kitecodec-jni-harness/major-mismatch/include"))
        }
        val mismatchHelperCompile = tasks.register<CompileKiteFFmpegCTask>(
            "compileKiteFFmpegCForJniMacosArm64MajorMismatch",
        ) {
            konanTargetName.set("macos_arm64")
            sourceDir.set(rootDir.resolve("native/kitecodec-c/src"))
            includeDir.set(prepareMajorMismatchHeaders.flatMap { it.outputDirectory })
            // The generated fake header's include_next resolves the production copy second.
            ffmpegIncludeDirs.set(listOf(opaqueInclude.absolutePath, macosFfmpegInclude.absolutePath))
            ffmpegVersionHeaders.from(
                listOf("libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample")
                    .flatMap { library ->
                        listOf("version.h", "version_major.h").map { "$macosFfmpegInclude/$library/$it" }
                    } + "$macosFfmpegInclude/libavutil/ffversion.h",
            )
            buildDefines.set(
                mapOf(
                    CompileKiteFFmpegCTask.DEFINE_FFMPEG_REF to BuildFFmpegTask.DEFAULT_SOURCE_REF,
                    CompileKiteFFmpegCTask.DEFINE_FFMPEG_LICENSE to FFmpegLicense.LGPL.dirName,
                    CompileKiteFFmpegCTask.DEFINE_FFMPEG_DIR to macosFfmpegLib.absolutePath,
                ),
            )
            konanDataDir.fileProvider(konanDataDirProvider)
            outputDir.set(layout.buildDirectory.dir("kitecodec-c-jni-major-mismatch/macos_arm64"))
        }
        val mismatchJniLink = tasks.register<LinkKiteFFmpegJniTask>(
            "linkKiteFFmpegJniMacosArm64MajorMismatch",
        ) {
            group = "verification"
            description = "Links a test-only JNI dylib with a genuinely major-mismatched identity helper."
            jniSources.from(fileTree(jniDir) { include("*.c", "*.h", "methods.def") })
            jniSources.from(fileTree(handlesDir) { include("*.c", "*.h") })
            opaqueIncludeDir.set(opaqueInclude)
            dependsOn(mismatchHelperCompile)
            helperArchive.from(
                mismatchHelperCompile.flatMap { it.outputDir.file(CompileKiteFFmpegCTask.ARCHIVE_NAME) },
            )
            ffmpegLibDir.set(macosFfmpegLib)
            compiler.set("/usr/bin/clang")
            extraIncludeDirs.set(
                javaHome.map { listOf("${it.absolutePath}/include", "${it.absolutePath}/include/darwin") },
            )
            libSearchDirs.set(emptyList())
            linkFlags.set(macosJniLinkFlags)
            exportControlFile.set(jniDir.resolve("exports.macos"))
            exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS)
            outputDirectory.set(layout.buildDirectory.dir("kitecodec-jni-harness/major-mismatch/macos-arm64"))
            outputLibrary.set(outputDirectory.file("libkitecodec_jni_major_mismatch.dylib"))
        }

        val prepareCorruptJni = tasks.register<PrepareKiteFFmpegJniHarnessTask>(
            "prepareKiteFFmpegJniCorruptDescriptorSources",
        ) {
            group = "verification"
            description = "Generates an isolated JNI tree with one invalid RegisterNatives descriptor."
            sourceDirectory.set(jniDir)
            mutationSourceFile.set(jniDir.resolve("methods.def"))
            relativeFile.set("methods.def")
            expectedText.set(
                """KJ_METHOD("io/github/yuroyami/kiteffmpeg/Internals", "nativeAbiVersion",       "()I",                    kj_abi_version)""",
            )
            replacementText.set(
                """KJ_METHOD("io/github/yuroyami/kiteffmpeg/Internals", "nativeAbiVersion",       "()J",                    kj_abi_version)""",
            )
            outputDirectory.set(layout.buildDirectory.dir("generated/kitecodec-jni-harness/corrupt-descriptor"))
        }
        val normalHelperCompile = tasks.named<CompileKiteFFmpegCTask>("compileKiteFFmpegCForMacosArm64")
        val corruptJniLink = tasks.register<LinkKiteFFmpegJniTask>(
            "linkKiteFFmpegJniMacosArm64CorruptDescriptor",
        ) {
            group = "verification"
            description = "Links a test-only JNI dylib whose RegisterNatives descriptor must fail."
            jniSources.from(
                prepareCorruptJni.flatMap { it.outputDirectory }.map { directory -> directory.asFileTree },
            )
            // The staged tree is a copy of native/kitecodec-jni only; the shared handle table is
            // not in it and the link would fail on every kj_handle_* symbol without this.
            jniSources.from(fileTree(handlesDir) { include("*.c", "*.h") })
            opaqueIncludeDir.set(opaqueInclude)
            dependsOn(normalHelperCompile)
            helperArchive.from(
                normalHelperCompile.flatMap { it.outputDir.file(CompileKiteFFmpegCTask.ARCHIVE_NAME) },
            )
            ffmpegLibDir.set(macosFfmpegLib)
            compiler.set("/usr/bin/clang")
            extraIncludeDirs.set(
                javaHome.map { listOf("${it.absolutePath}/include", "${it.absolutePath}/include/darwin") },
            )
            libSearchDirs.set(emptyList())
            linkFlags.set(macosJniLinkFlags)
            exportControlFile.set(prepareCorruptJni.flatMap { it.outputDirectory.file("exports.macos") })
            exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.MACHO_EXPORTED_SYMBOLS)
            outputDirectory.set(layout.buildDirectory.dir("kitecodec-jni-harness/corrupt-descriptor/macos-arm64"))
            outputLibrary.set(outputDirectory.file("libkitecodec_jni_corrupt_descriptor.dylib"))
        }

        val jvmTranscriptFile = layout.buildDirectory.file("contract-transcripts/jvm.txt")
        val macosTranscriptFile = layout.buildDirectory.file("contract-transcripts/macosArm64.txt")

        tasks.named<Test>("jniJvmTest") {
            dependsOn(macosJniLink, mismatchJniLink, corruptJniLink)
            val testRuntimeClasspath = classpath
            jvmArgumentProviders.add(
                objects.newInstance<KiteFFmpegJvmTestArgumentProvider>().apply {
                    normalJniLibrary.set(macosJniLink.flatMap { it.outputLibrary })
                    mismatchJniLibrary.set(mismatchJniLink.flatMap { it.outputLibrary })
                    corruptJniLibrary.set(corruptJniLink.flatMap { it.outputLibrary })
                    contractTranscript.set(jvmTranscriptFile)
                    probeClasspath.from(testRuntimeClasspath)
                },
            )
            outputs.file(jvmTranscriptFile).withPropertyName("codecContractTranscript")
        }
        tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>("macosArm64Test") {
            environment(
                "KITECODEC_CONTRACT_TRANSCRIPT",
                macosTranscriptFile.get().asFile.absolutePath,
            )
            outputs.file(macosTranscriptFile).withPropertyName("codecContractTranscript")
            doFirst {
                val parent = macosTranscriptFile.get().asFile.parentFile
                if (!parent.isDirectory && !parent.mkdirs()) {
                    throw GradleException("Could not create codec-contract transcript directory: $parent")
                }
            }
        }
        tasks.register<CompareCodecContractTask>("compareJvmNativeContract") {
            group = "verification"
            description = "Compares the unpublished JVM/JNI and macOS codec-contract transcripts byte for byte."
            dependsOn("jniJvmTest", "macosArm64Test")
            jvmTranscript.set(jvmTranscriptFile)
            macosArm64Transcript.set(macosTranscriptFile)
        }

    }

    if (withAndroid) {
        extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
            onVariants { variant ->
                val jniLibs = checkNotNull(variant.sources.jniLibs) {
                    "AGP did not expose jniLibs sources for Kotlin Multiplatform Android variant ${variant.name}."
                }
                androidJniLinks.values.forEach { linkTask ->
                    jniLibs.addGeneratedSourceDirectory(
                        linkTask,
                        LinkKiteFFmpegJniTask::outputDirectory,
                    )
                }
            }
        }
    }
}

/*
 * ── The host JNI library rides the jvm artifact (phase W) ────────────────
 *
 * `linkKiteFFmpegJniMacosArm64` was scaffolded as test-only, loaded through the
 * `kiteffmpeg.jni.path` property. A desktop consumer has no such property, so W-01's real JVM
 * variant would still fail at the first `System.loadLibrary`. Staging the dylib into the jvm
 * resource tree under `kiteffmpeg-native/<os>-<arch>/` is what makes one `implementation()` line
 * enough on a desktop, and it is the layout `JniLibrary.jvm.kt` reads. Linux and Windows twins
 * drop into the same map once their FFmpeg trees exist (W.5); nothing else has to change.
 *
 * Guarded on the vendored tree because the link cannot run without it, and an arm64 Mac because
 * that is the only host whose JNI arm is wired today. Everywhere else the jvm artifact publishes
 * without a bundled library and the loader's own message says how to supply one.
 */
run {
    val hostArm = "macos-arm64"
    val hostFfmpegLib = rootDir.resolve("native-libs/lgpl/$hostArm/lib")
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val osArch = System.getProperty("os.arch").orEmpty().lowercase()
    val hostIsArm64Mac = "mac" in osName && osArch in setOf("aarch64", "arm64")
    if (hostIsArm64Mac && hostFfmpegLib.isDirectory) {
        // The Linux JNI libraries. Opt in with -Pkiteffmpeg.jni.linux=true, because they need
        // a running Docker daemon for the JDK headers and a cross-built FFmpeg tree, and an
        // ordinary build must need neither. Each writes into the same resource root under its own
        // platform directory, so processResources merges them without knowing how many there are.
        val linuxJniStages = if (
            providers.gradleProperty("kiteffmpeg.jni.linux").orNull == "true"
        ) {
            val jniDir = rootDir.resolve("native/kitecodec-jni")
            val handlesDir = rootDir.resolve("native/kitecodec-handles")
            val opaqueInclude = rootDir.resolve("native/kitecodec-c/include")
            val konanDataDirProvider = providers.environmentVariable("KONAN_DATA_DIR")
                .orElse(providers.systemProperty("user.home").map { home -> "$home/.konan" })
                .map(::File)
            listOf(
                Triple("linux-arm64", "linux_arm64", "linux/arm64"),
                Triple("linux-x64", "linux_x64", "linux/amd64"),
            ).mapNotNull { (dirName, konanTarget, containerPlatform) ->
                val ffmpegRoot = rootDir.resolve("native-libs/lgpl/$dirName")
                if (!ffmpegRoot.resolve("lib").isDirectory) {
                    logger.lifecycle(
                        "[KiteFFmpeg] skipping the $dirName JNI library: no FFmpeg tree at " +
                            "$ffmpegRoot. Run :kiteffmpeg-core:buildFFmpegFor${dirName.split("-")
                                .joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }} first.",
                    )
                    return@mapNotNull null
                }
                val headers = tasks.register<ExtractJdkHeadersTask>(
                    "extractJdkHeadersFor${konanTarget.replaceFirstChar(Char::uppercase)}",
                ) {
                    image.set("eclipse-temurin:21-jdk")
                    platform.set(containerPlatform)
                    outputDir.set(layout.buildDirectory.dir("jdk-headers/$konanTarget"))
                }
                val helper = tasks.register<CompileKiteFFmpegCTask>(
                    "compileKiteFFmpegCFor${konanTarget.replaceFirstChar(Char::uppercase)}Jni",
                ) {
                    konanTargetName.set(konanTarget)
                    sourceDir.set(rootDir.resolve("native/kitecodec-c/src"))
                    includeDir.set(opaqueInclude)
                    ffmpegIncludeDirs.set(listOf(ffmpegRoot.resolve("include").absolutePath))
                    buildDefines.set(
                        mapOf(
                            CompileKiteFFmpegCTask.DEFINE_FFMPEG_REF to BuildFFmpegTask.DEFAULT_SOURCE_REF,
                            CompileKiteFFmpegCTask.DEFINE_FFMPEG_LICENSE to FFmpegLicense.LGPL.dirName,
                            CompileKiteFFmpegCTask.DEFINE_FFMPEG_DIR to ffmpegRoot.resolve("lib").absolutePath,
                        ),
                    )
                    konanDataDir.fileProvider(konanDataDirProvider)
                    outputDir.set(layout.buildDirectory.dir("kitecodec-c-jni/$konanTarget"))
                }
                val link = tasks.register<LinkKiteFFmpegJniTask>(
                    "linkKiteFFmpegJni${konanTarget.split("_")
                        .joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }}",
                ) {
                    group = "kiteffmpeg"
                    description = "Links libkitecodec_jni.so for $dirName."
                    jniSources.from(fileTree(jniDir) { include("*.c", "*.h", "methods.def") })
                    jniSources.from(fileTree(handlesDir) { include("*.c", "*.h") })
                    opaqueIncludeDir.set(opaqueInclude)
                    dependsOn(helper, headers)
                    helperArchive.from(
                        helper.flatMap { it.outputDir.file(CompileKiteFFmpegCTask.ARCHIVE_NAME) },
                    )
                    ffmpegLibDir.set(ffmpegRoot.resolve("lib"))
                    val tools = konanLinuxTools(konanTarget)
                    compiler.set(tools.clang)
                    extraIncludeDirs.set(
                        listOf(
                            ffmpegRoot.resolve("include").absolutePath,
                            headers.get().outputDir.get().asFile.absolutePath,
                            headers.get().outputDir.get().asFile.resolve("linux").absolutePath,
                        ),
                    )
                    libSearchDirs.set(emptyList())
                    // The libav* archives are NOT optional here, and --no-undefined is what makes
                    // that enforceable: ELF -shared permits undefined symbols by default, so
                    // omitting them once produced a 137 KB library that linked happily and could
                    // only have failed at load. The flag turns that into a link error.
                    //
                    // dav1d follows the same tree-presence truth as every other link, and
                    // comes AFTER the libav* group because ld resolves static archives left to
                    // right and it is libavcodec that draws on it. It was missing here alone: the
                    // dav1d surge enabled --enable-libdav1d for the linux trees without adding the
                    // flag to this one link, so --no-undefined did its job and reported every
                    // dav1d_* symbol undefined from libdav1d.o. It lives in the same lib/ this
                    // link already searches, so nothing but the name was ever needed.
                    val jniDav1d = if (ffmpegRoot.resolve("lib/libdav1d.a").exists()) {
                        listOf("-ldav1d")
                    } else {
                        emptyList()
                    }
                    linkFlags.set(
                        tools.flags + listOf(
                            "-lavformat", "-lavcodec", "-lavfilter",
                            "-lavutil", "-lswscale", "-lswresample",
                        ) + jniDav1d + listOf(
                            "-lz", "-lm", "-ldl", "-lpthread",
                            "-Wl,--no-undefined",
                        ),
                    )
                    exportControlFile.set(jniDir.resolve("exports.map"))
                    exportControlKind.set(LinkKiteFFmpegJniTask.ExportControlKind.ELF_VERSION_SCRIPT)
                    outputDirectory.set(layout.buildDirectory.dir("kitecodec-jni/$dirName"))
                    outputLibrary.set(outputDirectory.file("libkitecodec_jni.so"))
                }
                tasks.register<BundleHostJniTask>(
                    "stage${konanTarget.split("_")
                        .joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }}JniForJvm",
                ) {
                    jniLibrary.set(link.flatMap { it.outputLibrary })
                    platformDirectory.set(dirName)
                    outputDir.set(layout.buildDirectory.dir("kiteffmpeg-jvm-resources-$dirName"))
                }
            }
        } else {
            emptyList()
        }

        val stageHostJni = tasks.register<BundleHostJniTask>("stageHostJniForJvm") {
            val link = tasks.named<LinkKiteFFmpegJniTask>("linkKiteFFmpegJniMacosArm64")
            jniLibrary.set(link.flatMap { it.outputLibrary })
            platformDirectory.set(hostArm)
            outputDir.set(layout.buildDirectory.dir("kiteffmpeg-jvm-resources"))
        }
        tasks.named<ProcessResources>("jvmProcessResources") {
            from(stageHostJni.flatMap { it.outputDir })
            linuxJniStages.forEach { stage -> from(stage.flatMap { it.outputDir }) }
        }
    }

    // The falsifiability arm for W-01 and W-02, kept in the build so it can be re-run rather than
    // described. `-Pkiteffmpeg.jni.falsify=true` points the loader at a path that cannot exist, so
    // every jvm test that touches the backend must fail. A green run under this flag would mean
    // the suite is not reaching the native library at all.
    if (providers.gradleProperty("kiteffmpeg.jni.falsify").orNull == "true") {
        tasks.named<Test>("jvmTest") {
            systemProperty("kiteffmpeg.jni.path", "/nonexistent/libkitecodec_jni.dylib")
        }
    }
}
