package io.github.yuroyami.kiteffmpeg.buildtools

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
    fun compareReadsBothTranscriptFiles() {
        val root = Files.createTempDirectory("kiteffmpeg-contract-input-test").toFile()
        try {
            val jvm = root.resolve("jvm.txt").apply { writeText("identity=accepted\n") }
            val macos = root.resolve("macosArm64.txt").apply { writeText("identity=accepted\n") }
            val task = CompareCodecContractTask().apply {
                jvmTranscript.set(jvm)
                macosArm64Transcript.set(macos)
            }
            task.compare()

            // The plugin declares both files as inputs. A change in either one must reach the comparison.
            macos.writeText("identity=rejected\n")
            assertFailsWith<BuildFailure> { task.compare() }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsTheFirstDifferingByte() {
        val failure = assertFailsWith<BuildFailure> {
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
        val failure = assertFailsWith<BuildFailure> {
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
