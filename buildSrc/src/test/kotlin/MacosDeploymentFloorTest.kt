package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every macOS build here holds its code to [BuildFFmpegTask.MACOS_DEPLOYMENT_TARGET]. A build that
 * passes no floor takes the version of the SDK that built it, which measured macOS 26.0 on the
 * dav1d and libass chain archives and on the JNI library, inside artifacts that claim 12.0.
 */
class MacosDeploymentFloorTest {

    @Test
    fun theDeploymentEnvironmentHoldsBothMacosTargetsToTheOneFloor() {
        val expected = mapOf("MACOSX_DEPLOYMENT_TARGET" to BuildFFmpegTask.MACOS_DEPLOYMENT_TARGET)
        assertEquals(expected, BuildFFmpegTask.macosDeploymentEnv(TargetTriple.MacosArm64))
        assertEquals(expected, BuildFFmpegTask.macosDeploymentEnv(TargetTriple.MacosX64))
        // iOS passes its own floor, and nothing else is Apple at all.
        listOf(
            TargetTriple.IosArm64, TargetTriple.IosSimulatorArm64, TargetTriple.IosX64,
            TargetTriple.AndroidArm64, TargetTriple.LinuxX64, TargetTriple.MingwX64,
        ).forEach { target ->
            assertEquals(emptyMap(), BuildFFmpegTask.macosDeploymentEnv(target), "$target is not macOS")
        }
    }

    @Test
    fun theDav1dAndChainBuildsAndTheMacosJniLinkPassTheFloor() {
        val root = File(checkNotNull(System.getProperty("kiteffmpeg.repo.root")))
        listOf("BuildDav1dTask.kt", "BuildAssChainTask.kt").forEach { name ->
            val text = root.resolve("buildSrc/src/main/kotlin/$name").readText()
            assertTrue(
                "BuildFFmpegTask.macosDeploymentEnv(target)" in text,
                "$name must run its build with the macOS deployment environment",
            )
        }
        val script = root.resolve("kiteffmpeg/build.gradle.kts").readText()
        val start = script.indexOf("val macosJniLinkFlags = listOf(")
        assertTrue(start >= 0, "kiteffmpeg/build.gradle.kts no longer declares macosJniLinkFlags")
        val flags = script.substring(start, script.indexOf(")\n", start))
        assertTrue(
            "\"-mmacosx-version-min=\${BuildFFmpegTask.MACOS_DEPLOYMENT_TARGET}\"" in flags,
            "the macOS JNI link must pass the deployment floor",
        )
    }
}
