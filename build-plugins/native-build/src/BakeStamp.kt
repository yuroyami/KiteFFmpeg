package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import java.security.MessageDigest

/**
 * The record of what a bake was made from, kept in its output folder.
 *
 * The Kotlin Toolchain always runs a command's task the first time, so a bake cannot rely on the
 * toolchain to skip it. Each bake writes this record when it finishes, and skips itself when the
 * record still matches its inputs and its outputs are all there.
 */
object BakeStamp {

    const val FILE_NAME: String = ".kiteffmpeg-bake-inputs.txt"

    /** The inputs of one bake as text: the [step] name, the [values] that configure it and the contents of the [files] it reads. */
    fun inputs(step: String, values: List<String>, files: Collection<File> = emptyList()): String = buildString {
        appendLine("step $step")
        values.forEach { appendLine("value $it") }
        files.sortedBy { it.name }.forEach { appendLine("file ${it.name} ${digest(it.readBytes())}") }
    }

    /**
     * True when [outputDir] holds every one of [requiredFiles] and its record equals [inputs].
     *
     * A tree that has its files but no record was made before the record existed. It is reused
     * without a record, because a rebuild takes a long time. Delete the folder to bake it again.
     */
    fun isCurrent(outputDir: File, inputs: String, requiredFiles: List<String>, label: String): Boolean {
        if (requiredFiles.any { !outputDir.resolve(it).isFile }) return false
        val stamp = outputDir.resolve(FILE_NAME)
        if (!stamp.isFile) {
            BuildLog.lifecycle("[KiteFFmpeg] reusing $label at $outputDir. It has no bake record. Delete the folder to bake it again.")
            return true
        }
        if (stamp.readText() != inputs) return false
        BuildLog.lifecycle("[KiteFFmpeg] $label is up to date at $outputDir")
        return true
    }

    fun record(outputDir: File, inputs: String) {
        outputDir.mkdirs()
        outputDir.resolve(FILE_NAME).writeText(inputs)
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
}
