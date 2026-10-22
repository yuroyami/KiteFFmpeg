package io.github.yuroyami.kiteffmpeg.buildtools


/** Compares the stable JVM and macOS codec-contract transcripts byte for byte. */
open class CompareCodecContractTask : BuildStep() {

    val jvmTranscript = RegularFileProperty()

    val macosArm64Transcript = RegularFileProperty()

    fun compare() {
        val jvmFile = jvmTranscript.get().asFile
        val macosFile = macosArm64Transcript.get().asFile
        assertIdentical(jvmFile.readBytes(), macosFile.readBytes())
        logger.lifecycle(
            "codec contract transcripts match byte for byte: ${jvmFile.length()} bytes",
        )
    }

    companion object {
        internal fun assertIdentical(jvm: ByteArray, macosArm64: ByteArray) {
            val sharedLength = minOf(jvm.size, macosArm64.size)
            val firstMismatch = (0 until sharedLength).firstOrNull { jvm[it] != macosArm64[it] }
            if (firstMismatch != null) {
                throw BuildFailure(
                    "Codec contract differs first at byte $firstMismatch: " +
                        "JVM ${hex(jvm[firstMismatch])}, macosArm64 ${hex(macosArm64[firstMismatch])}.",
                )
            }
            if (jvm.size != macosArm64.size) {
                val longerSide = if (jvm.size > macosArm64.size) "JVM" else "macosArm64"
                val nextByte = if (jvm.size > macosArm64.size) jvm[sharedLength] else macosArm64[sharedLength]
                throw BuildFailure(
                    "Codec contract differs first at byte $sharedLength: " +
                        "$longerSide continues with ${hex(nextByte)} " +
                        "(JVM ${jvm.size} bytes, macosArm64 ${macosArm64.size} bytes).",
                )
            }
        }

        private fun hex(value: Byte): String = "0x%02x".format(value.toInt() and 0xff)
    }
}
