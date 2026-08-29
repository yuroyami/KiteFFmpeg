package io.github.yuroyami.kiteffmpeg

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JniIdentityTest {
    @Test
    fun identityHasAllTypedFieldsAndTheCurrentAbi() {
        // The pin is deliberate: an ABI bump nobody wrote down must fail here. 2.6 adds owned
        // stream colour and codec-profile metadata; move this with KITECODEC_C_ABI_MINOR only.
        val identity = FFmpeg.identity
        assertTrue(identity.isAcceptable, identity.describe())
        assertEquals("2.6", identity.cAbiVersion)
        assertEquals(
            listOf("libavutil", "libavcodec", "libavformat", "libavfilter", "libswscale", "libswresample"),
            identity.libraries.map { it.name },
        )
        assertTrue(identity.buildFFmpegRef.isNotBlank())
        assertTrue(identity.buildProvisioningDir.isNotBlank())
        assertTrue(identity.runtimeVersionInfo.isNotBlank())
        assertTrue(identity.provisioning.isNotBlank())
    }

    @Test
    fun mismatchLibraryRejectsBeforeAttachWithTypedFullReport() {
        val library = requireNotNull(System.getProperty("kiteffmpeg.jni.mismatch.path")) {
            "jvmTest did not provide kiteffmpeg.jni.mismatch.path"
        }.also { require(Files.isRegularFile(Path.of(it))) { "mismatch JNI library is not a file: $it" } }
        val classpath = requireNotNull(System.getProperty("kiteffmpeg.jni.probe.classpath")) {
            "jvmTest did not provide kiteffmpeg.jni.probe.classpath"
        }.also { require(it.isNotBlank()) { "kiteffmpeg.jni.probe.classpath is blank" } }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java,
            "-cp", classpath,
            "-Dkiteffmpeg.jni.path=$library",
            "-Dkiteffmpeg.jni.probe=mismatch",
            "io.github.yuroyami.kiteffmpeg.JniProbeMain",
        ).redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "isolated identity probe timed out")
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        assertTrue("PASS mismatch" in output, output)
    }
}
