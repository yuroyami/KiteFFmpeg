package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompareCodecContractTaskTest {

    @Test
    fun identicalTranscriptsPass() {
        val transcript = "identity=accepted\nframe=8f14e45f\n".encodeToByteArray()
        CompareCodecContractTask.assertIdentical(transcript, transcript.copyOf())
    }

    @Test
    fun bothTranscriptFilesAreDeclaredInputs() {
        val root = Files.createTempDirectory("kiteffmpeg-contract-input-test").toFile()
        try {
            val jvm = root.resolve("jvm.txt").apply { writeText("identity=accepted\n") }
            val macos = root.resolve("macosArm64.txt").apply { writeText("identity=accepted\n") }
            val task = ProjectBuilder.builder().build().tasks.create(
                "compareCodecContract",
                CompareCodecContractTask::class.java,
            ).apply {
                jvmTranscript.set(jvm)
                macosArm64Transcript.set(macos)
            }

            assertEquals(setOf(jvm, macos), task.inputs.files.files)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsTheFirstDifferingByte() {
        val failure = assertFailsWith<GradleException> {
            CompareCodecContractTask.assertIdentical(
                byteArrayOf(0x10, 0x20, 0x30),
                byteArrayOf(0x10, 0x21, 0x30),
            )
        }

        assertEquals(
            "Codec contract differs first at byte 1: JVM 0x20, macosArm64 0x21.",
            failure.message,
        )
    }

    @Test
    fun reportsTheFirstExtraByte() {
        val failure = assertFailsWith<GradleException> {
            CompareCodecContractTask.assertIdentical(
                byteArrayOf(0x10, 0x20, 0x30),
                byteArrayOf(0x10, 0x20),
            )
        }

        assertTrue(
            failure.message.orEmpty().startsWith(
                "Codec contract differs first at byte 2: JVM continues with 0x30",
            ),
        )
    }
}
