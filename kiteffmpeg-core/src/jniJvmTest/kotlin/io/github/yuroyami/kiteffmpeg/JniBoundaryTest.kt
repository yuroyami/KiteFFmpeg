package io.github.yuroyami.kiteffmpeg

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JniBoundaryTest {
    @Test
    fun normalManifestRegistersAndOwnedHandlesCloseExactlyOnce() {
        assertTrue(FFmpeg.identity.isAcceptable, FFmpeg.identity.describe())
        val baseline = Internals.liveHandles()
        val packet = Internals.packetAlloc()
        val copy = Internals.packetClone(packet)
        assertEquals(baseline + 2, Internals.liveHandles())
        Internals.packetFree(packet)
        Internals.packetFree(packet)
        Internals.packetFree(copy)
        assertEquals(baseline, Internals.liveHandles())
    }

    @Test
    fun staticCodecTokensReleaseIdempotentlyAcrossRepeatedAcquisition() {
        val baseline = Internals.liveHandles()
        repeat(2) {
            val codec = Internals.findDecoderByName("mpeg4")
            assertTrue(codec != 0L)
            assertEquals(baseline + 1, Internals.liveHandles())
            assertTrue(Internals.codecId(codec) != 0)
            Internals.codecRelease(codec)
            assertEquals(baseline, Internals.liveHandles())
            Internals.codecRelease(codec)
            assertEquals(baseline, Internals.liveHandles())
        }
    }

    @Test
    fun zeroStaleAndWrongKindTokensAreTypedInsteadOfDereferenced() {
        assertFailsWith<FFmpegException> { Internals.packetPts(0) }
        val packet = Internals.packetAlloc()
        Internals.packetFree(packet)
        assertFailsWith<FFmpegException> { Internals.packetPts(packet) }
        val frame = Internals.frameAlloc()
        try {
            assertFailsWith<FFmpegException> { Internals.packetPts(frame) }
        } finally {
            Internals.frameFree(frame)
        }
    }

    @Test
    fun parentCloseInvalidatesBorrowedStreamAndReleasesLedgerOnce() {
        val media = materializeContractMedia(ContractMedia.bytes, ContractMedia.sha256)
        try {
            val baseline = Internals.liveHandles()
            val format = Internals.fmtOpenInput(media)
            check0(Internals.fmtFindStreamInfo(format), "find stream info")
            val stream = Internals.fmtStream(format, 0)
            val parameters = Internals.streamCodecPar(stream)
            assertEquals(baseline + 3, Internals.liveHandles())
            Internals.fmtCloseInput(format)
            assertEquals(baseline, Internals.liveHandles())
            assertFailsWith<FFmpegException> { Internals.streamIndex(stream) }
            assertFailsWith<FFmpegException> { Internals.codecParId(parameters) }
            Internals.borrowedRelease(stream, Internals.KIND_STREAM)
            Internals.borrowedRelease(parameters, Internals.KIND_CODEC_PAR)
            assertEquals(baseline, Internals.liveHandles())
        } finally {
            deleteContractPath(media)
        }
    }

    @Test
    fun cStringBoundaryRefusesEmbeddedNulAndMalformedUtf16() {
        val embeddedNul = assertFailsWith<FFmpegException> {
            FFmpeg.hasDecoder("mpeg4\u0000ignored")
        }
        assertIs<FFmpegError.Internal>(embeddedNul.error)
        assertTrue("embedded NUL" in embeddedNul.error.message)

        listOf(
            String(charArrayOf('\uD83E')),
            String(charArrayOf('\uDE81')),
        ).forEach { malformed ->
            val unpairedSurrogate = assertFailsWith<FFmpegException> {
                FFmpeg.hasFilter(malformed)
            }
            assertIs<FFmpegError.Internal>(unpairedSurrogate.error)
            assertTrue("unpaired UTF-16 surrogate" in unpairedSurrogate.error.message)
        }

        val malformedPath = assertFailsWith<FFmpegException> {
            Internals.fmtAllocOutput("bad\u0000path.mp4", "mp4")
        }
        assertIs<FFmpegError.Internal>(malformedPath.error)

        val output = contractOutputPath("mkv")
        val format = Internals.fmtAllocOutput(output, "matroska")
        try {
            val malformedKey = assertFailsWith<FFmpegException> {
                Internals.fmtSetMetadata(format, "bad\u0000key", "non-null-value")
            }
            assertIs<FFmpegError.Internal>(malformedKey.error)
        } finally {
            Internals.fmtFreeOutput(format)
            deleteContractPath(output)
        }
    }

    @Test
    fun corruptedDescriptorLibraryFailsRegistrationInIsolatedJvm() {
        runProbe("kiteffmpeg.jni.corrupt.path", "corrupt")
    }

    private fun runProbe(pathProperty: String, mode: String) {
        val library = requireFileProperty(pathProperty)
        val classpath = requireNotNull(System.getProperty("kiteffmpeg.jni.probe.classpath")) {
            "jvmTest did not provide kiteffmpeg.jni.probe.classpath"
        }.also { require(it.isNotBlank()) { "kiteffmpeg.jni.probe.classpath is blank" } }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java,
            "-cp", classpath,
            "-Dkiteffmpeg.jni.path=$library",
            "-Dkiteffmpeg.jni.probe=$mode",
            "io.github.yuroyami.kiteffmpeg.JniProbeMain",
        ).redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "isolated JNI probe timed out")
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        assertTrue("PASS $mode" in output, output)
    }

    private fun requireFileProperty(name: String): String {
        val value = requireNotNull(System.getProperty(name)) { "jvmTest did not provide $name" }
        require(Files.isRegularFile(Path.of(value))) { "$name is not a file: $value" }
        return value
    }
}

/** A separate process is required because JNI registration and native-library loading are
 * process-global. This main is launched by the tests above with a single candidate dylib. */
internal object JniProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = System.getProperty("kiteffmpeg.jni.probe")
        when (mode) {
            "corrupt" -> {
                val failure = runCatching { FFmpeg.identity }.exceptionOrNull()
                check(
                    failure is UnsatisfiedLinkError || failure is NoSuchMethodError ||
                        failure?.cause is UnsatisfiedLinkError || failure?.cause is NoSuchMethodError,
                ) {
                    "corrupt manifest unexpectedly loaded: $failure"
                }
            }
            "mismatch" -> {
                val report = FFmpeg.identity
                check(!report.isAcceptable) {
                    "mismatched runtime identity was unexpectedly acceptable: ${report.describe()}"
                }
                check(report.libraries.size == 6)
                check(report.provisioning.isNotBlank())
                check(report.status != 0)

                val failure = runCatching { FFmpeg.hasDecoder("mpeg4") }.exceptionOrNull()
                check(failure is FFmpegException && failure.error is FFmpegError.IncompatibleFFmpegRuntime) {
                    "ordinary operation on mismatched runtime did not produce typed identity error: $failure"
                }
                val rejected = (failure.error as FFmpegError.IncompatibleFFmpegRuntime).identity
                check(rejected.cAbiVersion == report.cAbiVersion)
                check(rejected.libraries.map { it.name } == report.libraries.map { it.name })
                check(rejected.provisioning == report.provisioning)
            }
            else -> error("unknown probe mode: $mode")
        }
        println("PASS $mode")
    }
}
