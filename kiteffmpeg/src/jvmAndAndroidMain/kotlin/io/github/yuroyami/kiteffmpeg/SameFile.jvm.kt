package io.github.yuroyami.kiteffmpeg

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Refuses an output that names the input, before either is opened.
 *
 * The sink truncates on open and the source keeps reading from its buffer, so the same path
 * twice wrote a few frames over the caller's media and returned success. Identity is the file
 * key, which sees through symbolic links, hard links and any spelling of the path, a `file:`
 * prefix included. An output that does not exist yet cannot be the input, an input that does
 * not exist is left for the source open to report, and an address that names no local file is
 * not compared.
 */
internal fun refuseSameFile(input: String, output: String) {
    val inputFile = localFileOf(input) ?: return
    val outputFile = localFileOf(output) ?: return
    val same = try {
        val target = File(outputFile).toPath()
        Files.exists(target) && Files.isSameFile(File(inputFile).toPath(), target)
    } catch (_: IOException) {
        false
    } catch (_: java.nio.file.InvalidPathException) {
        false
    }
    if (same) {
        throw FFmpegException(
            FFmpegError.InvalidArgument(0, "the output names the same file as the input: $output"),
        )
    }
}
