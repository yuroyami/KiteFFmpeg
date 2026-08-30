import io.github.yuroyami.kiteffmpeg.buildtools.FFmpegLicense
import io.github.yuroyami.kiteffmpeg.buildtools.FFmpegPaths
import io.github.yuroyami.kiteffmpeg.buildtools.StaticLinkFlags
import io.github.yuroyami.kiteffmpeg.buildtools.TargetTriple

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    // Same flavour selection as :kiteffmpeg-core. Without this the sample always resolved the LGPL
    // tree while the library it links was built against the GPL one, and the Windows CI job passes
    // -Pkiteffmpeg.ffmpeg.license=gpl and got away with it only because the DLLs were on PATH.
    val selectedLicense =
        if (providers.gradleProperty("kiteffmpeg.ffmpeg.license").orNull?.equals("gpl", ignoreCase = true) == true) {
            FFmpegLicense.GPL
        } else {
            FFmpegLicense.LGPL
        }

    // The phone-superset gate uses the core module's Apple selector together with
    // requireAllTargets. The sample must mirror that scope: its only executable in this mode is
    // the macOS host proof, while iOS remains a library target with no command-line executable.
    val applePhoneTargetsOnly = providers.gradleProperty("kiteffmpeg.applePhoneTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val phoneTargetsOnly = providers.gradleProperty("kiteffmpeg.phoneTargetsOnly")
        .map { it.toBoolean() }.getOrElse(false)
    val executables = if (applePhoneTargetsOnly || phoneTargetsOnly) {
        mapOf(macosArm64() to TargetTriple.MacosArm64)
    } else {
        mapOf(
            macosArm64() to TargetTriple.MacosArm64,
            macosX64() to TargetTriple.MacosX64,
            linuxX64() to TargetTriple.LinuxX64,
            linuxArm64() to TargetTriple.LinuxArm64,
            mingwX64() to TargetTriple.MingwX64,
        )
    }

    val homebrewPrefix = providers.gradleProperty("kiteffmpeg.macos.homebrew.prefix")
        .getOrElse(io.github.yuroyami.kiteffmpeg.buildtools.BuildFFmpegTask.DEFAULT_HOMEBREW_PREFIX)

    // Targets whose FFmpeg is missing are skipped with a warning by default; releases must not
    // silently drop targets, so -Pkiteffmpeg.requireAllTargets=true makes it fail instead.
    val requireAllTargets = providers.gradleProperty("kiteffmpeg.requireAllTargets")
        .map { it.toBoolean() }.getOrElse(false)

    executables.forEach { (target, triple) ->
        val paths = try {
            FFmpegPaths.resolve(project, triple, selectedLicense)
        } catch (e: GradleException) {
            if (requireAllTargets) throw e
            logger.lifecycle(
                "warning: [KiteFFmpeg] SKIPPING FFmpeg link setup for sample target '${triple.dirName}' " +
                    "because no FFmpeg build found. ${e.message} " +
                    "Set -Pkiteffmpeg.requireAllTargets=true to fail the build instead.",
            )
            null
        }
        target.binaries {
            executable {
                entryPoint = "io.github.yuroyami.kiteffmpeg.sample.main"
                if (paths != null) {
                    linkerOpts("-L${paths.libDir}")
                    // A static vendored FFmpeg needs dav1d (mandatory since FFmpeg was embedded) and the
                    // platform flags named at the final link, see StaticLinkFlags.
                    linkerOpts(StaticLinkFlags.forTarget(triple, selectedLicense, paths.isStaticVendored))
                    if (!paths.isStaticVendored && target.name.startsWith("macos")) {
                        linkerOpts("-rpath", paths.libDir)
                    }
                }
            }
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
            }
        }
        commonMain.dependencies {
            implementation(project(":kiteffmpeg-core"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
